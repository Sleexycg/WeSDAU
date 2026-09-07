package com.sdau.campuskit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScoreUpdateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CHECK_SCORES) return
        val pendingResult = goAsync()
        try {
            val enqueueOperation = ScoreUpdateScheduler.onAlarm(context)
            if (enqueueOperation == null) {
                pendingResult.finish()
                return
            }
            enqueueOperation.whenComplete { _, _ -> pendingResult.finish() }
        } catch (error: Exception) {
            ScoreUpdateDiagnostics.record(context, "receiver_failed", error.javaClass.simpleName)
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_CHECK_SCORES = "com.sdau.campuskit.action.CHECK_SCORE_UPDATES"
    }
}
