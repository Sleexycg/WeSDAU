package com.sdau.campuskit

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

object CourseNotification {
    const val CHANNEL_ID = "course_reminders"
    private const val NOTIFICATION_ID = 4101

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.deleteNotificationChannel("course_reminders_silent_v2")
        manager.deleteNotificationChannel("course_reminders_silent_v3")
        // Existing channel settings belong to the user; never delete/recreate it
        // to override a disabled channel or its sound/vibration preferences.
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "课程提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "下一节课程提醒"
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    fun blockedReason(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return "请开启系统通知权限"
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return "请开启系统通知权限"
        if (manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            return "请在系统通知设置中开启“课程提醒”"
        }
        return null
    }

    fun show(context: Context, name: String, room: String, time: String) {
        check(blockedReason(context) == null) { "Course notifications are blocked" }
        val openApp = PendingIntent.getActivity(
            context,
            4103,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val normalizedRoom = room.replace(Regex("\\s+"), "")
        val text = "${name}丨@${normalizedRoom}丨$time"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logout)
            .setContentTitle("WeSDAU课程表")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setSubText("课程提醒")
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}

class CourseReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CourseReminderDispatch.execute {
            try {
                CourseReminderScheduler.deliver(context.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "course_name"
        const val EXTRA_ROOM = "course_room"
        const val EXTRA_TIME = "course_time"
        const val EXTRA_EVENT = "course_event"
        const val EXTRA_START = "course_start"
        const val EXTRA_TERM = "course_term"
    }
}

class CourseReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CourseReminderDispatch.execute {
            try {
                CourseReminderScheduler.restore(context.applicationContext)
                CourseReminderScheduler.scheduleNext(context.applicationContext, source = "restore")
                ScoreUpdateScheduler.restoreIfEnabled(context.applicationContext, forceAlarm = true)
            } finally {
                pending.finish()
            }
        }
    }
}

private val CourseReminderDispatch = Executors.newSingleThreadExecutor()

object CourseReminderScheduler {
    private const val PREFS_NAME = "offline_login"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_PUSH_ENABLED = "push_enabled"
    private const val KEY_TERM = "term"
    private const val KEY_REMINDER_ACCOUNT = "course_reminder_account"
    private const val REMINDER_REQUEST_CODE = 3002
    private const val NEXT_EVENT = "course_reminder_next_event"
    private const val NEXT_AT = "course_reminder_next_at"
    private const val DELIVERED = "course_reminder_delivered"
    private const val LAST_ERROR = "course_reminder_last_error"
    private const val TAG = "CourseReminder"

    data class ScheduleResult(val scheduled: Boolean, val message: String, val retryable: Boolean = false)

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_PUSH_ENABLED, false)

    /** The explicit enable action is the only place where the reminder account changes. */
    @Synchronized
    fun enable(context: Context): ScheduleResult {
        ReminderBackgroundSettings.blockedMessage(ReminderBackgroundSettings.batteryAccess(context))?.let {
            return ScheduleResult(false, it)
        }
        val prefs = preferences(context)
        val account = prefs.getString(KEY_ACCOUNT, "").orEmpty()
        if (account.isBlank()) return ScheduleResult(false, "请先登录个人账号后开启课程提醒")
        prefs.edit().putString(KEY_REMINDER_ACCOUNT, account).putBoolean(KEY_PUSH_ENABLED, true).commit()
        restore(context)
        return scheduleNext(context)
    }

    @Synchronized
    fun disable(context: Context) {
        preferences(context).edit().putBoolean(KEY_PUSH_ENABLED, false).commit()
        cancel(context)
        CourseReminderRepairWorker.cancel(context)
        CourseNotification.cancel(context)
    }

    private fun boundAccount(prefs: SharedPreferences): String {
        val bound = prefs.getString(KEY_REMINDER_ACCOUNT, null)
        val account = CourseReminderCachePolicy.reminderAccount(bound, prefs.getString(KEY_ACCOUNT, "").orEmpty())
        // Migrate an enabled installation before navigation/account changes can replace it.
        if (bound.isNullOrBlank() && account.isNotBlank()) {
            prefs.edit().putString(KEY_REMINDER_ACCOUNT, account).commit()
        }
        return account
    }

    @Synchronized
    fun restore(context: Context) {
        if (isEnabled(context)) {
            boundAccount(preferences(context))
            runCatching { CourseReminderRepairWorker.ensurePeriodic(context) }
                .onFailure { Log.w(TAG, "Could not enqueue periodic reminder repair", it) }
        }
    }

    fun consumePendingError(context: Context): String? {
        val prefs = preferences(context)
        val errorAt = prefs.getLong("course_reminder_last_error_at", 0L)
        if (errorAt <= prefs.getLong("course_reminder_error_shown_at", 0L)) return null
        val message = prefs.getString(LAST_ERROR, null) ?: return null
        prefs.edit().putLong("course_reminder_error_shown_at", errorAt).apply()
        return message
    }

    private fun readCourses(preferences: SharedPreferences, account: String, term: String): List<ReminderCourse> {
        // The legacy cache belongs to the selected term, not necessarily the current term.
        val caches = CourseReminderCachePolicy.rawCaches(account, preferences.getString(KEY_TERM, "").orEmpty(), term,
            preferences.getString(KEY_ACCOUNT, "").orEmpty()) {
            preferences.getString(it, null)
        }
        // Preserve valid legacy data under the bound account before another login
        // replaces the unscoped cache. An explicit [] remains an intentional deletion.
        if (account.isNotBlank()) {
            val edit = preferences.edit()
            caches.getOrNull(0)?.let { edit.putString(CourseCacheKeys.imported(account, term), it) }
            caches.getOrNull(1)?.let { edit.putString(CourseCacheKeys.custom(account, term), it) }
            edit.apply()
        }
        return loadCourses(*caches)
    }

    private fun delivered(preferences: SharedPreferences, now: Long): JSONObject {
        val saved = runCatching { JSONObject(preferences.getString(DELIVERED, "{}").orEmpty()) }
            .getOrElse { JSONObject() }
        val retained = JSONObject()
        val legacy = preferences.getInt("course_reminder_delivery_schema", 1) < 2
        val lastPostedAt = preferences.getLong("course_reminder_last_posted_at", 0L)
        saved.keys().forEach { key ->
            val startAt = saved.optLong(key)
            if (startAt >= now - 86_400_000L) {
                val deliveryKey = if (legacy && startAt > now) {
                    CourseReminderPolicy.legacyDeliveredKey(key, startAt, lastPostedAt)
                } else key
                retained.put(deliveryKey, startAt)
            }
        }
        if (legacy) preferences.edit().putInt("course_reminder_delivery_schema", 2)
            .putString(DELIVERED, retained.toString()).commit()
        return retained
    }

    @Synchronized
    fun scheduleNext(context: Context, source: String = "reconcile"): ScheduleResult {
        val applicationContext = context.applicationContext
        val preferences = preferences(applicationContext)
        if (!preferences.getBoolean(KEY_PUSH_ENABLED, false)) {
            cancel(applicationContext)
            return ScheduleResult(false, "课程提醒已关闭")
        }
        return try {
            scheduleEnabled(applicationContext, preferences, source)
        } catch (error: Exception) {
            fail(applicationContext, "课程提醒暂时安排失败，将自动重试", error)
        }
    }

    private fun scheduleEnabled(applicationContext: Context, preferences: SharedPreferences, source: String): ScheduleResult {
        CourseNotification.blockedReason(applicationContext)?.let { return fail(applicationContext, it, stop = true) }
        val alarm = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarm.canScheduleExactAlarms()) {
            return fail(applicationContext, "请授予“闹钟和提醒”权限", stop = true)
        }
        val account = boundAccount(preferences)
        val now = Calendar.getInstance()
        val term = AcademicTermCalendar.currentTerm(now)
        val courses = readCourses(preferences, account, term)
        val delivered = delivered(preferences, now.timeInMillis)
        var next = CourseReminderPolicy.findNext(
            courses, account, term, now, delivered.keys().asSequence().toSet()
        )
        // Reconcile due reminders here, not by repeatedly arming a one-second alarm.
        // Alarm callbacks, local workers and foreground recovery share the same lock
        // and durable ledger. No entry point can consume or postpone another's event.
        while (next != null && next.plannedAt <= now.timeInMillis) {
            CourseNotification.show(applicationContext, next.course.name, next.course.room, next.timeLabel)
            delivered.put(next.eventKey, next.startAt)
            check(preferences.edit().putString(DELIVERED, delivered.toString())
                .putLong("course_reminder_last_posted_at", now.timeInMillis)
                .putString("course_reminder_last_posted_event", next.eventKey)
                .putString("course_reminder_last_posted_source", source).commit())
            Log.i(TAG, "posted source=$source evening=${next.eventKey.endsWith(":night")} at=${now.timeInMillis}")
            next = CourseReminderPolicy.findNext(courses, account, term, now,
                delivered.keys().asSequence().toSet())
        }
        if (next == null) {
            cancel(applicationContext)
            val message = if (courses.isEmpty()) "提醒已开启，当前学期暂无本地课程，请先导入课表" else "提醒已开启，暂无待提醒课程"
            preferences.edit().putString("course_reminder_status", message).remove(LAST_ERROR).apply()
            return ScheduleResult(false, message)
        }

        // Re-register the same occurrence without cancelling it first. Different
        // occurrences use separate identities, so a failed replacement cannot
        // overwrite the extras of an alarm that is still valid.
        // A PendingIntent alone isn't proof an Alarm still exists (e.g. after reboot).
        val previousAt = preferences.getLong(NEXT_AT, 0L)
        val previousEvent = preferences.getString(NEXT_EVENT, null)
        val triggerAt = if (previousEvent == next.eventKey &&
            previousAt > now.timeInMillis && previousAt < next.triggerAt
        ) previousAt else next.triggerAt

        val pending = PendingIntent.getBroadcast(
            applicationContext,
            REMINDER_REQUEST_CODE,
            reminderIntent(applicationContext, next.eventKey).apply {
                putExtra(CourseReminderReceiver.EXTRA_NAME, next.course.name)
                putExtra(CourseReminderReceiver.EXTRA_ROOM, next.course.room)
                putExtra(CourseReminderReceiver.EXTRA_TIME, next.timeLabel)
                putExtra(CourseReminderReceiver.EXTRA_EVENT, next.eventKey)
                putExtra(CourseReminderReceiver.EXTRA_START, next.startAt)
                putExtra(CourseReminderReceiver.EXTRA_TERM, term)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        preferences.edit().putString(NEXT_EVENT, next.eventKey).putLong(NEXT_AT, triggerAt)
            .putString(DELIVERED, delivered.toString()).putString("course_reminder_status", "scheduled")
            .putLong("course_reminder_last_scheduled_at", now.timeInMillis).remove(LAST_ERROR).commit()
        CourseReminderRepairWorker.ensureBackup(applicationContext, next.eventKey, triggerAt)
        if (previousEvent != next.eventKey) {
            cancelAlarm(applicationContext, previousEvent)
            previousEvent?.let { CourseReminderRepairWorker.cancelBackup(applicationContext, it) }
        }
        if (previousEvent != null) cancelAlarm(applicationContext, null) // Migrate the old shared identity.
        Log.i(TAG, "scheduled source=$source evening=${next.eventKey.endsWith(":night")} at=$triggerAt")
        return ScheduleResult(true, "课程提醒已开启")
    }

    private fun reminderIntent(context: Context, event: String?): Intent =
        Intent(context, CourseReminderReceiver::class.java).apply {
            // User-visible, time-sensitive delivery; this is broadcast priority,
            // not a foreground service and does not add a persistent notification.
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            if (event != null) action = "com.sdau.campuskit.COURSE_REMINDER.$event"
        }

    @Synchronized
    fun cancel(context: Context) {
        val applicationContext = context.applicationContext
        val previousEvent = preferences(applicationContext).getString(NEXT_EVENT, null)
        preferences(applicationContext).edit().remove(NEXT_EVENT).remove(NEXT_AT).apply()
        cancelAlarm(applicationContext, previousEvent)
        previousEvent?.let { CourseReminderRepairWorker.cancelBackup(applicationContext, it) }
        if (previousEvent != null) cancelAlarm(applicationContext, null)
    }

    private fun cancelAlarm(applicationContext: Context, event: String?) {
        val pending = PendingIntent.getBroadcast(
            applicationContext,
            REMINDER_REQUEST_CODE,
            reminderIntent(applicationContext, event),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        val alarm = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending)
        pending.cancel()
    }

    private fun fail(context: Context, message: String, error: Exception? = null, stop: Boolean = false): ScheduleResult {
        Log.w(TAG, message, error)
        preferences(context).edit()
            .putString(LAST_ERROR, message).putString("course_reminder_status", "error")
            .putLong("course_reminder_last_error_at", System.currentTimeMillis()).commit()
        if (stop) {
            disable(context)
        } else {
            // A temporary parsing/platform error must not cancel an already registered
            // alarm or turn off the user's preference. Repair independently of the UI.
            runCatching { CourseReminderRepairWorker.enqueueRetry(context) }
                .onFailure { Log.w(TAG, "Could not enqueue reminder repair", it) }
        }
        return ScheduleResult(false, message, retryable = !stop)
    }

    @Synchronized
    fun showPreview(context: Context) {
        if (!isEnabled(context)) return
        try {
            val prefs = preferences(context)
            val now = Calendar.getInstance()
            val term = AcademicTermCalendar.currentTerm(now)
            val account = boundAccount(prefs)
            val next = CourseReminderPolicy.findNext(readCourses(prefs, account, term), account, term, now,
                delivered(prefs, now.timeInMillis).keys().asSequence().toSet())
            if (next != null) CourseNotification.show(context, next.course.name, next.course.room, next.timeLabel)
        } catch (error: Exception) {
            fail(context, "课程通知暂时发送失败，将自动重试", error)
        }
    }

    @Synchronized
    fun deliver(context: Context, intent: Intent) {
        val prefs = preferences(context)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("course_reminder_last_received_at", now).commit()
        Log.i(TAG, "alarm_received at=$now current_event=${intent.getStringExtra(CourseReminderReceiver.EXTRA_EVENT) == prefs.getString(NEXT_EVENT, null)}")
        // A delayed old callback is a wake-up signal, not permission to post its old
        // extras. Re-read the bound account's current timetable and delivery ledger.
        scheduleNext(context, source = "alarm")
    }

    @Synchronized
    fun runBackup(context: Context, event: String): ScheduleResult {
        if (preferences(context).getString(NEXT_EVENT, null) != event) {
            return ScheduleResult(false, "提醒事件已更新")
        }
        preferences(context).edit().putLong("course_reminder_last_backup_at", System.currentTimeMillis()).commit()
        val result = scheduleNext(context, source = "backup")
        // If wall time moved backwards or a platform invoked us early, keep this
        // worker retryable instead of losing its backup while the event is pending.
        return if (result.scheduled && preferences(context).getString(NEXT_EVENT, null) == event) {
            result.copy(retryable = true)
        } else result
    }

    private fun loadCourses(vararg rawCaches: String?): List<ReminderCourse> {
        val courses = mutableListOf<ReminderCourse>()
        rawCaches.filterNotNull().forEach { raw ->
            // Let the scheduler report malformed cache data instead of silently
            // treating a parse failure as an empty timetable.
            val rows = JSONArray(raw)
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                courses += ReminderCourse(
                    day = row.optInt("day", -1),
                    startSlot = row.optInt("startSlot", -1),
                    slotCount = row.optInt("slotCount", 0),
                    name = row.optString("name"),
                    room = normalizeClassroomName(row.optString("room")),
                    weeks = row.optString("weeks"),
                    teacher = row.optString("teacher")
                )
            }
        }
        return courses.filter { it.day in 0..6 && it.name.isNotBlank() }
    }

}
