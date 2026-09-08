package com.sdau.campuskit

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.provider.Settings

/** Read-only checks. Neither opening Settings nor user confirmation grants background access. */
internal object ReminderBackgroundSettings {
    fun batteryAccess(context: Context): ReminderBatteryAccess {
        val ignoringOptimizations = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrNull()
        val backgroundRestricted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
            } else false // Android 8 has no separate user background-restriction API.
        }.getOrNull()
        val xiaomi = ReminderBatteryPolicy.isXiaomi(Build.MANUFACTURER, Build.BRAND)
        val vendorAccess = if (xiaomi) readXiaomiBatteryAccess(context) else ReminderBatteryAccess.UNKNOWN
        return ReminderBatteryPolicy.evaluate(ignoringOptimizations, backgroundRestricted, xiaomi, vendorAccess)
    }

    private fun readXiaomiBatteryAccess(context: Context): ReminderBatteryAccess = runCatching {
        // PowerKeeper projects user-0 bgControl=noRestrict packages to this per-user setting.
        // Doze exemption alone is NOT Xiaomi's unrestricted policy. Do not read another user's
        // state or query/write the private PowerKeeper provider. Unsupported ROMs fail closed.
        // Evidence and device-verification limits: tests/ReminderBackgroundGuidanceVerification.md.
        if (context.getSystemService(UserManager::class.java)?.isSystemUser != true) {
            return@runCatching ReminderBatteryAccess.UNKNOWN
        }
        ReminderBatteryPolicy.xiaomiAccess(
            Settings.System.getString(context.contentResolver, "MILLET_NO_RESTRICT_APP"),
            context.packageName
        )
    }.getOrDefault(ReminderBatteryAccess.UNKNOWN)

    fun blockedMessage(access: ReminderBatteryAccess): String? = when (access) {
        ReminderBatteryAccess.ALLOWED -> null
        ReminderBatteryAccess.RESTRICTED -> "请先将省电策略设为“无限制”，提醒未开启"
        ReminderBatteryAccess.UNKNOWN -> "无法确认省电策略，提醒未开启，请检查后台设置"
    }

    fun intents(context: Context): List<Intent> = listOf(
        // Keep the requested app-info landing page. Never silently change a system setting.
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
}
