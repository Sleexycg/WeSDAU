package com.sdau.campuskit

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Calendar

internal data class ReminderCourse(
    val day: Int,
    val startSlot: Int,
    val slotCount: Int,
    val name: String,
    val room: String,
    val weeks: String,
    val teacher: String = ""
)

internal data class PlannedCourseReminder(
    val course: ReminderCourse,
    val eventKey: String,
    val startAt: Long,
    val triggerAt: Long,
    val plannedAt: Long,
    val timeLabel: String
)

internal object CourseReminderCachePolicy {
    fun reminderAccount(boundAccount: String?, activeAccount: String): String =
        boundAccount?.takeIf { it.isNotBlank() } ?: activeAccount

    fun rawCaches(account: String, selectedTerm: String, currentTerm: String,
        activeAccount: String = account, read: (String) -> String?): Array<String?> {
        if (account.isBlank()) return emptyArray()
        val legacyMatches = selectedTerm == currentTerm && account == activeAccount
        val imported = read(CourseCacheKeys.imported(account, currentTerm))
            ?: if (legacyMatches) read("courses_cache") else null
        val custom = read(CourseCacheKeys.custom(account, currentTerm))
            ?: if (legacyMatches && read(CourseCacheKeys.customOwner(currentTerm)) == account) {
                read(CourseCacheKeys.legacyCustom(currentTerm))
            } else null
        return arrayOf(imported, custom)
    }
}

/** Pure planning logic with separate evening and pre-class delivery identities. */
internal object CourseReminderPolicy {
    fun nightEventKey(courseEventKey: String): String = "$courseEventKey:night"

    fun legacyDeliveredKey(key: String, startAt: Long, lastPostedAt: Long): String {
        val start = Calendar.getInstance().apply { timeInMillis = startAt }
        val posted = Calendar.getInstance().apply { timeInMillis = lastPostedAt }
        return if (lastPostedAt > 0 && start.epochDay() > posted.epochDay()) nightEventKey(key) else key
    }

    fun eventKey(account: String, term: String, course: ReminderCourse, startAt: Long): String {
        val fields = listOf(account, term, startAt.toString(), course.startSlot.toString(),
            course.slotCount.toString(), course.name, course.room, course.teacher)
        val identity = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun findNext(
        courses: List<ReminderCourse>,
        account: String,
        term: String,
        now: Calendar,
        delivered: Set<String> = emptySet()
    ): PlannedCourseReminder? {
        val validCourses = courses.filter {
            it.day in 0..6 && it.startSlot in 0..9 && it.slotCount > 0 &&
                it.slotCount <= 10 - it.startSlot && it.name.isNotBlank()
        }
        if (validCourses.isEmpty()) return null
        val startDay = AcademicTermCalendar.startDate(term).epochDay()
        fun week(date: Calendar): Int {
            val days = date.epochDay() - startDay
            return if (days < 0) 0 else (days / 7 + 1).toInt()
        }
        fun coursesOn(date: Calendar): List<ReminderCourse> {
            val week = week(date)
            if (week !in 1..20 || CampusHolidayCalendar.isHoliday(date)) return emptyList()
            val day = (date.get(Calendar.DAY_OF_WEEK) + 5) % 7
            return validCourses.filter { it.day == day && CourseWeekRule.isVisible(it.weeks, week) }
                .sortedBy { it.startSlot }
        }
        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var next: PlannedCourseReminder? = null
        for (offset in 0..147) {
            val date = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            val starts = ScheduleTimePolicy.startMinutes(ScheduleTimePolicy.modeFor(date))
            val previousDay = (date.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -1) }
            coursesOn(date).forEachIndexed { index, course ->
                val start = (date.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, starts[course.startSlot] / 60)
                    set(Calendar.MINUTE, starts[course.startSlot] % 60)
                }
                val startAt = start.timeInMillis
                if (startAt <= now.timeInMillis) return@forEachIndexed
                val key = eventKey(account, term, course, startAt)
                val normalTime = startAt - (if (index == 0) 60L else 20L) * 60_000L
                val nightTime = (previousDay.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, 22)
                }.timeInMillis
                val lastSlot = course.startSlot + course.slotCount - 1
                fun consider(deliveryKey: String, plannedTime: Long) {
                    if (deliveryKey in delivered) return
                    val triggerAt = maxOf(plannedTime, now.timeInMillis + 1_000L)
                    if (triggerAt >= startAt) return
                    val candidate = PlannedCourseReminder(course, deliveryKey, startAt, triggerAt, plannedTime,
                        "${format(starts[course.startSlot])}-${format(starts[lastSlot] + 45)}")
                    if (candidate.triggerAt < (next?.triggerAt ?: Long.MAX_VALUE)) next = candidate
                }
                // The 22:00 preview must not consume the separate one-hour reminder.
                // Keep a late evening preview eligible until midnight. Replanning after
                // 22:00 must not discard it, but never replay it the next morning.
                if (index == 0 && now.timeInMillis < date.timeInMillis && key !in delivered) {
                    consider(nightEventKey(key), nightTime)
                }
                consider(key, normalTime)
            }
            // Today's reminders precede the 22:00 advance reminder for tomorrow.
            // Once this day's earliest candidate exists, later days cannot precede it.
            if (next != null) return next
        }
        return next
    }

    private fun Calendar.epochDay(): Long = LocalDate.of(
        get(Calendar.YEAR), get(Calendar.MONTH) + 1, get(Calendar.DAY_OF_MONTH)
    ).toEpochDay()

    private fun format(minutes: Int): String = "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}
