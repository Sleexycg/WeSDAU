package com.sdau.campuskit

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** adb-only diagnostic variant. Uses the REAL worker and portal; never prints login or grades. */
class ScoreMonitorDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                val app = context.applicationContext
                val prefs = app.getSharedPreferences("offline_login", Context.MODE_PRIVATE)
                val manager = WorkManager.getInstance(app)
                when (intent.getStringExtra("command")) {
                    "alarm" -> {
                        check(ScoreUpdateScheduler.isEnabled(app)) { "Reminder must be enabled by user first" }
                        val delay = intent.getIntExtra("seconds", 20).coerceIn(10, 1800) * 1000L
                        val elapsed = SystemClock.elapsedRealtime() + delay
                        val alarm = PendingIntent.getBroadcast(app, 4203,
                            Intent(app, ScoreUpdateAlarmReceiver::class.java)
                                .setAction(ScoreUpdateAlarmReceiver.ACTION_CHECK_SCORES),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                        app.getSystemService(AlarmManager::class.java).setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsed, alarm)
                        prefs.edit().putLong("score_update_monitor_next_alarm_at", System.currentTimeMillis() + delay)
                            .putLong("score_update_monitor_next_alarm_elapsed", elapsed)
                            .putInt("score_update_monitor_alarm_boot", Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT))
                            .commit()
                        Log.i(TAG, "short_alarm_scheduled seconds=${delay / 1000}")
                    }
                    "restore" -> ScoreUpdateScheduler.restoreIfEnabled(app)
                    "legacy" -> {
                        check(ScoreUpdateScheduler.isEnabled(app))
                        check(manager.getWorkInfosForUniqueWork(WORK).get().none { !it.state.isFinished })
                        val request = OneTimeWorkRequestBuilder<ScoreUpdateWorker>()
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .setInitialDelay(30, TimeUnit.MINUTES).addTag("diagnostic_legacy_normal").build()
                        manager.enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request).result.get()
                        Log.i(TAG, "legacy_pending id=${request.id}")
                        ScoreUpdateScheduler.restoreIfEnabled(app)
                    }
                    "watchdog" -> Log.i(TAG, "watchdog_result=${ScoreUpdateScheduler.runWatchdog(app)}")
                }
                Log.i(TAG, "enabled=${ScoreUpdateScheduler.isEnabled(app)} last_success=${ScoreUpdateScheduler.queryStatus(app).lastCheckAt}" +
                    " next_alarm=${prefs.getLong("score_update_monitor_next_alarm_at", 0)}")
                manager.getWorkInfosForUniqueWork(WORK).get().forEach {
                    Log.i(TAG, "work id=${it.id} state=${it.state} attempts=${it.runAttemptCount} generation=${it.generation}" +
                        " expedited_v2=${"score_update_expedited_v2" in it.tags}")
                }
            } catch (error: Exception) {
                Log.e(TAG, "diagnostic_failed ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ScoreMonitorProbe"
        private const val WORK = "score_update_monitor_check"
        private val executor = Executors.newSingleThreadExecutor()
    }
}
