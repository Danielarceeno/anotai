package com.example.notepad.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Backs the ongoing reminder notification's "Snooze 1h" action: hides it immediately and
 * schedules a one-shot alarm to re-check and repost an hour later, via the same receiver
 * (distinguished by [ACTION_SNOOZE] vs [ACTION_SNOOZE_FIRE]) so there's only one class to wire up.
 */
class SnoozeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SNOOZE_FIRE -> {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ReminderNotifier.checkAndNotify(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            else -> {
                NotificationManagerCompat.from(context).cancel(ReminderNotifier.NOTIFICATION_ID)
                scheduleSnoozeFire(context)
            }
        }
    }

    private fun scheduleSnoozeFire(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = System.currentTimeMillis() + SNOOZE_MILLIS
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_FIRE,
            Intent(context, SnoozeReceiver::class.java).setAction(ACTION_SNOOZE_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.example.notepad.action.SNOOZE"
        const val ACTION_SNOOZE_FIRE = "com.example.notepad.action.SNOOZE_FIRE"
        private const val REQUEST_CODE_FIRE = 1002
        private const val SNOOZE_MILLIS = 60 * 60 * 1000L

        fun snoozeActionPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            1003,
            Intent(context, SnoozeReceiver::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
