package com.sdau.campuskit

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/** Shared by both reminder switches. Never changes an already enabled reminder or system setting. */
internal class ReminderBackgroundAccessGuide(
    private val activity: ComponentActivity,
    private val host: () -> FrameLayout,
    private val capture: ((Bitmap?) -> Unit) -> Unit,
    private val preparePrompt: (() -> Unit) -> Unit,
    private val clearToast: () -> Unit,
    private val notify: (String) -> Unit,
    private val onReady: (ReminderFeature) -> Unit
) {
    private var flow: ReminderBackgroundFlow? = null
    private var dialog: LiquidConfirmDialogView? = null
    private var capturePending = false
    private var settingsLaunchPending = false
    private var generation = 0
    private val settingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // OEM settings may return RESULT_CANCELED after a change. Only fresh policy data matters.
        flow?.settingsReturned()
        render()
    }

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = render()
            override fun onPause(owner: LifecycleOwner) {
                // Keep the displayed dialog and its owned snapshot during Settings visits.
                // Only invalidate unfinished captures of a window that is no longer visible.
                generation++
                capturePending = false
                if (settingsLaunchPending) {
                    settingsLaunchPending = false
                    flow?.awaitingSettings = null // No external activity was launched yet.
                }
            }
            override fun onDestroy(owner: LifecycleOwner) {
                flow = null
                closeDialog()
            }
        })
    }

    fun begin(feature: ReminderFeature, action: ReminderAccessAction = ReminderAccessAction.ENABLE) {
        if (flow != null) return // Ignore rapid taps; never create parallel settings flows.
        flow = ReminderBackgroundFlow(feature, action)
        render()
    }

    fun cancel(feature: ReminderFeature? = null): Boolean {
        val current = flow ?: return false
        if (feature != null && current.feature != feature) return false
        flow = null
        closeDialog()
        return true
    }

    fun saveState(): Bundle? = flow?.let { current ->
        Bundle().apply {
            putString("feature", current.feature.name)
            putString("action", current.action.name)
            putBoolean("battery_visited", current.batterySettingsVisited)
            // A queued click is not an outstanding ActivityResult request after recreation.
            putString("awaiting", current.awaitingSettings?.name.takeUnless { settingsLaunchPending })
        }
    }

    fun restoreState(state: Bundle?) {
        if (state == null) return
        val feature = ReminderFeature.entries.firstOrNull { it.name == state.getString("feature") } ?: return
        flow = ReminderBackgroundFlow(
            feature = feature,
            action = ReminderAccessAction.entries.firstOrNull { it.name == state.getString("action") }
                ?: ReminderAccessAction.ENABLE,
            batterySettingsVisited = state.getBoolean("battery_visited"),
            // Old saved skips/confirmations are intentionally ignored after upgrade.
            awaitingSettings = ReminderAccessStep.BATTERY.takeIf { state.getString("awaiting") != null }
        )
    }

    private fun render() {
        val current = flow ?: return
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        // In particular, do not run binder/provider reads during the outgoing transition.
        if (current.awaitingSettings != null) return

        val battery = ReminderBackgroundSettings.batteryAccess(activity)
        when (current.nextStep(battery)) {
            ReminderAccessStep.WAITING -> return
            ReminderAccessStep.DONE -> {
                flow = null // Consume before callback: never enable/send a test twice.
                closeDialog()
                if (current.shouldEnableReminder) onReady(current.feature)
                return
            }
            ReminderAccessStep.BATTERY -> Unit
        }
        if (dialog != null || capturePending) return

        clearToast() // No stale toast above, or captured inside, this modal prompt.
        capturePending = true
        val requestGeneration = ++generation
        preparePrompt {
            if (requestGeneration != generation || flow !== current ||
                !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@preparePrompt
            captureAfterPageFrame(current, requestGeneration)
        }
    }

    private fun captureAfterPageFrame(current: ReminderBackgroundFlow, requestGeneration: Int) {
        // A recreated Activity has no retained bitmap. Wait for its first page frame before
        // requesting PixelCopy; on ordinary Settings returns the existing dialog is reused.
        host().doOnPreDraw { page ->
            page.post {
                if (requestGeneration == generation && flow === current &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !activity.isFinishing && !activity.isDestroyed) {
                    capturePrompt(current, requestGeneration)
                }
            }
        }
        host().invalidate()
    }

    private fun capturePrompt(current: ReminderBackgroundFlow, requestGeneration: Int) {
        capture { snapshot ->
            if (requestGeneration != generation || flow !== current || activity.isFinishing || activity.isDestroyed) {
                snapshot?.takeUnless(Bitmap::isRecycled)?.recycle()
                return@capture
            }
            capturePending = false
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                snapshot?.takeUnless(Bitmap::isRecycled)?.recycle()
                return@capture
            }
            val view = LiquidConfirmDialogView(
                context = activity,
                pageSnapshot = snapshot,
                title = "检查后台省电策略",
                message = "点击“去设置”按钮将弹出应用设置页面，请在“省电策略”或“电池”中选择“无限制”。如有“电池优化”选项，请设为“不优化”",
                cancelLabel = "取消",
                confirmLabel = "去设置",
                onDismiss = {
                    if (flow === current) cancel(current.feature) // Never continue enabling on cancel/outside tap.
                },
                onConfirm = {
                    if (flow === current && current.awaitingSettings == null) {
                        // Retain the exact same bitmap and glass layer across the external page.
                        scheduleSettingsLaunch(current)
                    }
                },
                showFallbackBackground = false
            )
            dialog = view
            host().addView(view, FrameLayout.LayoutParams(-1, -1))
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun scheduleSettingsLaunch(current: ReminderBackgroundFlow) {
        current.awaitingSettings = ReminderAccessStep.BATTERY
        settingsLaunchPending = true
        val requestGeneration = ++generation
        // Let Compose draw the released button first. No fixed sleep, re-capture, bitmap copy
        // or blocking permission reads in the touch callback; keep the existing dialog intact.
        host().doOnPreDraw { page ->
            page.post {
                if (requestGeneration == generation && flow === current && settingsLaunchPending &&
                    activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    !activity.isFinishing && !activity.isDestroyed) {
                    settingsLaunchPending = false
                    openSettings(current)
                }
            }
        }
        host().invalidate()
    }

    private fun openSettings(current: ReminderBackgroundFlow) {
        // Avoid resolveActivity/getInstalledPackages: Android package visibility varies.
        for (intent in ReminderBackgroundSettings.intents(activity)) {
            try {
                settingsLauncher.launch(intent)
                return
            } catch (_: RuntimeException) {
                // Missing settings activities or SecurityException: try the next safe destination.
            }
        }
        current.awaitingSettings = null
        // No dialog and toast at the same time, even if every Settings destination fails.
        cancel(current.feature)
        notify("无法打开设置，请手动检查省电策略")
    }

    private fun closeDialog() {
        generation++
        capturePending = false
        settingsLaunchPending = false
        dialog?.let { view ->
            view.animate().cancel()
            host().removeView(view)
            view.releaseSnapshot()
        }
        dialog = null
    }
}
