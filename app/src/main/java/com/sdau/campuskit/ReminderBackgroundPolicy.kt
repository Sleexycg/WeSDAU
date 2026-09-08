package com.sdau.campuskit

import java.util.Locale

internal enum class ReminderFeature(val displayName: String) {
    COURSE("课程提醒"), SCORE("成绩更新提醒")
}
internal enum class ReminderAccessAction { ENABLE, DISABLE }
internal enum class ReminderBatteryAccess { ALLOWED, RESTRICTED, UNKNOWN }
internal enum class ReminderAccessStep { BATTERY, WAITING, DONE }

/** Only a fresh, positive system check may complete an enable flow. There is no skip grant. */
internal data class ReminderBackgroundFlow(
    val feature: ReminderFeature,
    val action: ReminderAccessAction = ReminderAccessAction.ENABLE,
    var batterySettingsVisited: Boolean = false,
    var awaitingSettings: ReminderAccessStep? = null
) {
    // Finishing/restoring a closing flow must never re-enable reminders.
    val shouldEnableReminder: Boolean get() = action == ReminderAccessAction.ENABLE

    fun nextStep(battery: ReminderBatteryAccess): ReminderAccessStep = when {
        awaitingSettings != null -> ReminderAccessStep.WAITING
        battery != ReminderBatteryAccess.ALLOWED -> ReminderAccessStep.BATTERY
        else -> ReminderAccessStep.DONE
    }

    fun settingsReturned() {
        // Visiting settings, including a successful activity result, is never itself a grant.
        if (awaitingSettings == ReminderAccessStep.BATTERY) batterySettingsVisited = true
        awaitingSettings = null
    }
}

internal object ReminderBatteryPolicy {
    fun isXiaomi(manufacturer: String, brand: String): Boolean =
        listOf(manufacturer, brand).any { it.lowercase(Locale.ROOT) in setOf("xiaomi", "redmi", "poco") }

    fun evaluate(
        ignoringOptimizations: Boolean?,
        backgroundRestricted: Boolean?,
        requiresVendorCheck: Boolean,
        vendorAccess: ReminderBatteryAccess = ReminderBatteryAccess.UNKNOWN
    ): ReminderBatteryAccess = when {
        ignoringOptimizations == false || backgroundRestricted == true ||
            (requiresVendorCheck && vendorAccess == ReminderBatteryAccess.RESTRICTED) -> ReminderBatteryAccess.RESTRICTED
        ignoringOptimizations != true || backgroundRestricted != false ||
            (requiresVendorCheck && vendorAccess != ReminderBatteryAccess.ALLOWED) -> ReminderBatteryAccess.UNKNOWN
        else -> ReminderBatteryAccess.ALLOWED
    }

    /** Missing/unsupported OEM data is unknown, not an exemption. Match the exact package only. */
    fun xiaomiAccess(packages: String?, packageName: String): ReminderBatteryAccess = when {
        packages == null || packages.trim() == "null" || packageName.isBlank() -> ReminderBatteryAccess.UNKNOWN
        packages.split(',').any { it.trim() == packageName } -> ReminderBatteryAccess.ALLOWED
        else -> ReminderBatteryAccess.RESTRICTED
    }
}
