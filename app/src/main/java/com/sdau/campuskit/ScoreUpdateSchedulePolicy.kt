package com.sdau.campuskit

internal object ScoreUpdateSchedulePolicy {
    /**
     * setExactAndAllowWhileIdle is throttled by the system to roughly one delivery
     * per 9 minutes per app; anything shorter is silently deferred. 10 minutes is
     * therefore the practical floor, and the 6-hour watchdog bounds the useful max.
     */
    const val DEFAULT_INTERVAL_MINUTES = 30
    const val MIN_INTERVAL_MINUTES = 10
    const val MAX_INTERVAL_MINUTES = 360

    fun clampIntervalMinutes(minutes: Int): Int =
        minutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    fun intervalMillis(minutes: Int): Long = clampIntervalMinutes(minutes) * 60_000L

    /** A query older than three intervals is treated as lost by the watchdog. */
    fun successStaleMillis(minutes: Int): Long = intervalMillis(minutes) * 3L

    /** A foreground resume after 1.5 intervals requests a catch-up query. */
    fun foregroundCatchUpMillis(minutes: Int): Long = intervalMillis(minutes) * 3L / 2L

    /** Elapsed realtime is immune to date/time changes; it is valid only within one boot. */
    fun remainingMillis(now: Long, elapsed: Long, boot: Int, scheduledAt: Long,
                        scheduledElapsed: Long, scheduledBoot: Int): Long = when {
        boot >= 0 && boot == scheduledBoot && scheduledElapsed > 0L -> scheduledElapsed - elapsed
        scheduledAt > 0L -> scheduledAt - now
        else -> 0L
    }
}
