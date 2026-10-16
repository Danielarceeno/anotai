package com.example.notepad.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.notepad.data.Note
import com.example.notepad.data.RecurrenceUtils
import java.util.Calendar

/**
 * On top of the existing once-a-day 9am catch-all ([ReminderScheduler]/[DailyReminderReceiver]),
 * schedules an exact-time alarm for a single note's next occurrence (see
 * [Note.reminderTimeMinutes]). Each note gets its own [PendingIntent] keyed by its id, so
 * scheduling or cancelling one note's alarm never touches another's.
 */
object NoteReminderScheduler {

    /** Cancels any previous alarm for this note, then schedules its next occurrence if it has an
     *  exact reminder time. Safe to call every time a note is saved, whether or not it actually
     *  has one -- the no-op case (no due date, or "all day") just leaves it cancelled. */
    fun schedule(context: Context, note: Note) {
        cancel(context, note.id)
        if (note.reminderTimeMinutes < 0 || note.dueDate == 0L) return

        val todayStart = startOfToday()
        var day = RecurrenceUtils.nextOccurrenceOnOrAfter(note, todayStart) ?: return
        var trigger = day + note.reminderTimeMinutes * 60_000L
        val now = System.currentTimeMillis()
        if (trigger <= now) {
            // Start time has already passed (or this is today's occurrence being saved after
            // that time). If an end time was set and it hasn't passed yet, the reminder window
            // is still open today -- fire right away instead of waiting for tomorrow. Otherwise
            // jump to the occurrence after today rather than firing for a moment that's gone.
            val endMinutes = if (note.reminderEndTimeMinutes >= 0) note.reminderEndTimeMinutes else note.reminderTimeMinutes
            val endTrigger = day + endMinutes * 60_000L
            if (endTrigger > now) {
                trigger = now
            } else {
                val oneDayMillis = 24 * 60 * 60 * 1000L
                day = RecurrenceUtils.nextOccurrenceOnOrAfter(note, todayStart + oneDayMillis) ?: return
                trigger = day + note.reminderTimeMinutes * 60_000L
            }
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, note.id)
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (canUseExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        } else {
            // No "Alarms & reminders" permission: still far more reliable than an inexact
            // repeating alarm since it's allowed to fire during Doze.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent)
        }
    }

    fun cancel(context: Context, noteId: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, noteId))
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pendingIntent(context: Context, noteId: Long): PendingIntent {
        val intent = Intent(context, NoteReminderReceiver::class.java)
            .putExtra(NoteReminderReceiver.EXTRA_NOTE_ID, noteId)
        return PendingIntent.getBroadcast(
            context,
            requestCode(noteId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // PendingIntent request codes are Ints; Room's autoincrement row ids fit comfortably within
    // that range for any realistic personal notes database, so a direct truncation is fine here.
    private fun requestCode(noteId: Long): Int = noteId.toInt()
}
