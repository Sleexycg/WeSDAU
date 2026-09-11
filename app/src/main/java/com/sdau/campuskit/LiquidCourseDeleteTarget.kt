package com.sdau.campuskit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.view.DragEvent
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.shapes.RoundedRectangle
import kotlin.math.roundToInt

internal data class CourseDragPreview(val bitmap: Bitmap, val touch: Point)

/** Local native drag target: neither hovering nor tapping can delete a course. */
internal class LiquidCourseDeleteTargetView(
    context: Context,
    private var snapshot: Bitmap?,
    private val preview: CourseDragPreview,
    private val onDelete: () -> Boolean,
    private val onFinished: () -> Unit
) : FrameLayout(context) {
    private val shown = mutableStateOf(false)
    private val hovered = mutableStateOf(false)
    private var target: CourseDropTarget? = null
    private var cardOffsetY: () -> Float = { Float.POSITIVE_INFINITY }
    private var pointerX = 0f
    private var pointerY = 0f
    private var hasPointer = false
    private var acceptingDrop = true
    private var dismissing = false
    private var released = false

    val shadowBuilder = object : View.DragShadowBuilder() {
        private val margin = 8f * resources.displayMetrics.density
        private val scale = minOf(1.06f, 200f * resources.displayMetrics.density / preview.bitmap.height)
        private val paint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
        ).apply { alpha = 238 }

        override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
            outShadowSize.set((preview.bitmap.width * scale + margin * 2).roundToInt(),
                (preview.bitmap.height * scale + margin * 2).roundToInt())
            outShadowTouchPoint.set((preview.touch.x * scale + margin).roundToInt().coerceIn(0, outShadowSize.x - 1),
                (preview.touch.y * scale + margin).roundToInt().coerceIn(0, outShadowSize.y - 1))
        }
        override fun onDrawShadow(canvas: android.graphics.Canvas) {
            if (preview.bitmap.isRecycled) return
            canvas.save()
            canvas.translate(margin, margin)
            canvas.scale(scale, scale)
            canvas.drawBitmap(preview.bitmap, 0f, 0f, paint)
            canvas.restore()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        addView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setCampusContent {
                val colors = CampusComposeTheme.colors
                val image = remember(snapshot) { snapshot?.asImageBitmap() }
                val backdrop = remember(image, colors.isDark) { PageSnapshotBackdrop(image, colors.pageGradient) }
                val reveal = rememberFloatingBottomPanelMotion(shown.value)
                val panelHeight = backgroundControlsPanelHeightPx()
                val density = LocalDensity.current
                LaunchedEffect(reveal) {
                    // A stationary pointer can be reached by the opening card.
                    // Sample the layer animation, without remeasuring or rebuilding blur.
                    snapshotFlow { reveal.value }.collect {
                        if (hasPointer) updateHover(pointerX, pointerY)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val card = remember(constraints.maxWidth, constraints.maxHeight, density.density, panelHeight) {
                        CourseDeleteCardLayout.bounds(constraints.maxWidth.toFloat(),
                            constraints.maxHeight.toFloat(), density.density, panelHeight.toFloat())
                    }
                    val zoneShape = remember(density, card.radius) {
                        RoundedRectangle(with(density) { card.radius.toDp() })
                    }
                    SideEffect {
                        target = card
                        cardOffsetY = {
                            reveal.value * (card.bottom - card.top +
                                FloatingBottomPanelMetrics.SlideClearanceDp * density.density)
                        }
                    }
                    Box(
                        Modifier.offset { IntOffset(card.left.roundToInt(), card.top.roundToInt()) }
                            .size(with(density) { (card.right - card.left).toDp() },
                                with(density) { (card.bottom - card.top).toDp() })
                            .floatingBottomPanelMotion(reveal),
                        contentAlignment = Alignment.Center
                    ) {
                        // Keep the expensive, static glass on its own render layer.
                        // Hover state is read ONLY by CourseDeleteIcon, never this surface.
                        Box(Modifier.matchParentSize()
                            .drawBackdrop(backdrop = backdrop, shape = { zoneShape },
                                effects = { blur(24.dp.toPx()) },
                                highlight = null,
                                shadow = null,
                                onDrawSurface = {
                                    drawRect(colors.glassSurface.copy(alpha = if (colors.isDark) .18f else .22f))
                                }))
                        CourseDeleteIcon(hovered)
                    }
                }
            }
        }, LayoutParams(-1, -1))
        post { if (!dismissing) shown.value = true }
    }

    private fun containsPointer(x: Float, y: Float): Boolean =
        target?.contains(x, y - cardOffsetY()) == true

    private fun updateHover(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        hasPointer = true
        val inside = acceptingDrop && containsPointer(x, y)
        if (inside == hovered.value) return
        hovered.value = inside
        if (inside) {
            // Do not make the native drag callback wait for a haptic system call.
            post {
                if (acceptingDrop && hovered.value && isAttachedToWindow) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    override fun onDragEvent(event: DragEvent): Boolean {
        if (event.localState !== this) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return acceptingDrop
            DragEvent.ACTION_DRAG_LOCATION -> updateHover(event.x, event.y)
            DragEvent.ACTION_DRAG_EXITED -> { hasPointer = false; hovered.value = false }
            DragEvent.ACTION_DROP -> {
                val accepted = acceptingDrop && shown.value && containsPointer(event.x, event.y)
                acceptingDrop = false // At most one deletion for this gesture.
                return accepted && onDelete()
            }
            DragEvent.ACTION_DRAG_ENDED -> { acceptingDrop = false; hasPointer = false; onFinished() }
        }
        return true
    }

    fun dismiss(onDismissed: () -> Unit) {
        if (dismissing) return
        dismissing = true
        acceptingDrop = false
        hasPointer = false
        shown.value = false
        postDelayed({ onDismissed(); release() }, FloatingBottomPanelMetrics.RemovalDelayMillis)
    }

    private fun release() {
        if (released) return
        released = true
        releaseDialogSnapshot(snapshot) { snapshot = null }
        if (!preview.bitmap.isRecycled) preview.bitmap.recycle()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }
}

@Composable
private fun CourseDeleteIcon(hovered: State<Boolean>) {
    val colors = CampusComposeTheme.colors
    val hover by animateFloatAsState(if (hovered.value) 1f else 0f,
        tween(120), label = "courseDeleteTargetHover")
    val red = if (colors.isDark) Color(0xFFFF6B76) else Color(0xFFDB3D4A)
    Canvas(Modifier.size(48.dp)
        .graphicsLayer {
            scaleX = 1f + hover * .18f
            scaleY = 1f + hover * .18f
            alpha = .86f + hover * .14f
        }
        .semantics {
            contentDescription = "删除课程"
            stateDescription = if (hovered.value) "松手即可删除" else "拖入后松手删除"
        }) {
        val stroke = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(red, Offset(size.width * .18f, size.height * .28f),
            Offset(size.width * .82f, size.height * .28f), stroke.width, StrokeCap.Round)
        drawRoundRect(red, Offset(size.width * .28f, size.height * .29f),
            androidx.compose.ui.geometry.Size(size.width * .44f, size.height * .55f),
            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
        drawLine(red, Offset(size.width * .40f, size.height * .15f),
            Offset(size.width * .60f, size.height * .15f), stroke.width, StrokeCap.Round)
        for (x in floatArrayOf(.44f, .56f)) drawLine(red,
            Offset(size.width * x, size.height * .42f), Offset(size.width * x, size.height * .69f),
            stroke.width, StrokeCap.Round)
    }
}
