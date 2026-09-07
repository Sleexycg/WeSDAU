package com.sdau.campuskit

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in adb probe only. Does not modify course data, the delivery ledger or production alarm. */
class CourseReminderDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences("offline_login", Context.MODE_PRIVATE)
                when (intent.getStringExtra("command")) {
                    "alarm_test" -> {
                        check(CourseNotification.blockedReason(app) == null)
                        val delay = intent.getIntExtra("seconds", 20).coerceIn(10, 120) * 1000L
                        val at = System.currentTimeMillis() + delay
                        app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, at, testIntent(app))
                        Log.i(TAG, "test_alarm_scheduled at=$at; production_alarm_unchanged=true")
                    }
                    "test_delivery" -> {
                        CourseReminderAlarmProbeReceiver.postTest(app, viaAlarm = false)
                    }
                    "reconcile" -> CourseReminderScheduler.scheduleNext(app, source = "diagnostic_reconcile")
                    "production_alarm_test" -> {
                        val at = System.currentTimeMillis() + 20_000L
                        app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, at, productionTestIntent(app))
                        Log.i(TAG, "production_alarm_test_scheduled at=$at; production_deadline_unchanged=true")
                    }
                    "backup_test" -> {
                        val event = requireNotNull(prefs.getString("course_reminder_next_event", null))
                        WorkManager.getInstance(app).enqueueUniqueWork("course_reminder_backup_probe", ExistingWorkPolicy.KEEP,
                            OneTimeWorkRequestBuilder<CourseReminderRepairWorker>()
                                .setInputData(workDataOf("event" to event))
                                .setInitialDelay(20, TimeUnit.SECONDS).build()).result.get()
                        Log.i(TAG, "production_backup_test_scheduled")
                    }
                    "cancel_test" -> {
                        val operation = testIntent(app)
                        app.getSystemService(AlarmManager::class.java).cancel(operation)
                        operation.cancel()
                        val production = productionTestIntent(app)
                        app.getSystemService(AlarmManager::class.java).cancel(production)
                        production.cancel()
                        WorkManager.getInstance(app).cancelUniqueWork("course_reminder_backup_probe").result.get()
                    }
                }
                val event = prefs.getString("course_reminder_next_event", null)
                val ledger = runCatching { JSONObject(prefs.getString("course_reminder_delivered", "{}").orEmpty()) }
                    .getOrElse { JSONObject() }
                Log.i(TAG, "enabled=${CourseReminderScheduler.isEnabled(app)}" +
                    " next_at=${prefs.getLong("course_reminder_next_at", 0L)} next_evening=${event?.endsWith(":night")}" +
                    " received_at=${prefs.getLong("course_reminder_last_received_at", 0L)}" +
                    " posted_at=${prefs.getLong("course_reminder_last_posted_at", 0L)}" +
                    " scheduled_at=${prefs.getLong("course_reminder_last_scheduled_at", 0L)}" +
                    " error_at=${prefs.getLong("course_reminder_last_error_at", 0L)}" +
                    " posted_source=${prefs.getString("course_reminder_last_posted_source", null)}" +
                    " backup_at=${prefs.getLong("course_reminder_last_backup_at", 0L)}" +
                    " delivered_count=${ledger.length()}" +
                    " evening_for_next_delivered=${event != null && ledger.has("$event:night")}")
            } catch (error: Exception) {
                Log.e(TAG, "diagnostic_failed ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun testIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 3009,
        Intent(context, CourseReminderAlarmProbeReceiver::class.java)
            .setAction("com.sdau.campuskit.COURSE_REMINDER_DIAGNOSTIC")
            .putExtra("command", "test_delivery"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun productionTestIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 3010,
        Intent(context, CourseReminderReceiver::class.java)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .setAction("com.sdau.campuskit.COURSE_REMINDER_DIAGNOSTIC_WAKE"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        private const val TAG = "CourseReminderProbe"
        private val executor = Executors.newSingleThreadExecutor()
    }
}

/** Like the production target: private and not protected by the adb-only DUMP permission. */
class CourseReminderAlarmProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.sdau.campuskit.COURSE_REMINDER_DIAGNOSTIC") return
        postTest(context, viaAlarm = true)
    }

    companion object {
        fun postTest(context: Context, viaAlarm: Boolean) {
            Log.i("CourseReminderProbe", "test_delivery_invoked via_alarm=$viaAlarm at=${System.currentTimeMillis()} pid=${Process.myPid()}")
            CourseNotification.show(context, "课程提醒测试", "后台通知链路测试", "不影响正式课程提醒")
            val active = context.getSystemService(NotificationManager::class.java).activeNotifications
                .any { it.id == 4101 && it.notification.channelId == CourseNotification.CHANNEL_ID }
            Log.i("CourseReminderProbe", "test_notification_active=$active")
        }
    }
}
