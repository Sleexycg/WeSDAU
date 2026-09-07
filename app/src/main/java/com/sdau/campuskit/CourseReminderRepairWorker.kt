package com.sdau.campuskit

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Local-only repair; actual course reminders still use exact AlarmManager alarms. */
class CourseReminderRepairWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        if (!CourseReminderScheduler.isEnabled(applicationContext)) return Result.success()
        val event = inputData.getString(EVENT)
        val result = if (event != null) CourseReminderScheduler.runBackup(applicationContext, event)
            else CourseReminderScheduler.scheduleNext(applicationContext, source = "repair")
        return if (result.retryable) Result.retry() else Result.success()
    }

    companion object {
        private const val PERIODIC = "course_reminder_repair_periodic"
        private const val RETRY = "course_reminder_repair_retry"
        private const val BACKUP = "course_reminder_event_backup"
        private const val EVENT = "event"

        /** Independent, local-only fallback one minute after the exact alarm. */
        fun ensureBackup(context: Context, event: String, triggerAt: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork("${BACKUP}_$event", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CourseReminderRepairWorker>()
                    .setInputData(workDataOf(EVENT to event))
                    .setInitialDelay((triggerAt + 60_000L - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .addTag(BACKUP).build())
        }

        fun cancelBackup(context: Context, event: String) {
            WorkManager.getInstance(context).cancelUniqueWork("${BACKUP}_$event")
        }

        fun ensurePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CourseReminderRepairWorker>(6, TimeUnit.HOURS)
                    .setInitialDelay(6, TimeUnit.HOURS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build())
        }

        fun enqueueRetry(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(RETRY, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CourseReminderRepairWorker>()
                    .setInitialDelay(1, TimeUnit.MINUTES)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(RETRY)
                cancelAllWorkByTag(BACKUP)
            }
        }
    }
}
