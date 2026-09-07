package com.sdau.campuskit

internal object ScoreUpdateSchedulePolicy {
    /** Elapsed realtime is immune to date/time changes; it is valid only within one boot. */
    fun remainingMillis(now: Long, elapsed: Long, boot: Int, scheduledAt: Long,
                        scheduledElapsed: Long, scheduledBoot: Int): Long = when {
        boot >= 0 && boot == scheduledBoot && scheduledElapsed > 0L -> scheduledElapsed - elapsed
        scheduledAt > 0L -> scheduledAt - now
        else -> 0L
    }
}
