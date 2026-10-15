package com.example.notepad.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules [DailyReminderReceiver] to run once a day at [HOUR_OF_DAY]:00.
 *
 * Uses an exact, idle-allowed one-shot alarm instead of [AlarmManager.setInexactRepeating]:
 * the old repeating-inexact alarm is notoriously unreliable across Doze/App Standby (it can
 * silently slip by hours or get skipped), which is why the daily notification wasn't showing
 * up. [DailyReminderReceiver] re-calls [scheduleNext] after it fires so the alarm keeps
 * chaining itself one day at a time, same effect as "repeating" but reliable.
 */
object ReminderScheduler {

    private const val REQUEST_CODE = 1001

    fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context)
        val trigger = nextTriggerMillis(context)

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        } else {
            // No permission to schedule exact alarms (user hasn't granted "Alarms & reminders"):
            // still far more reliable than setInexactRepeating since it's allowed to fire in Doze.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        }
    }

    private fun nextTriggerMillis(context: Context): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, ReminderPrefs.getHour(context))
        set(Calendar.MINUTE, ReminderPrefs.getMinute(context))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DailyReminderReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
