package com.example.notepad.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.RecurrenceUtils
import com.example.notepad.ui.CalendarActivity
import java.util.Calendar

enum class ReminderCheckResult { NOTHING_DUE, NOTIFIED, PERMISSION_DENIED }

/**
 * Shared by [DailyReminderReceiver] (the scheduled 9am check) and
 * [com.example.notepad.ui.CalendarActivity] (an immediate check every time the app is opened,
 * so a reminder doesn't depend solely on AlarmManager actually firing -- OEM battery managers
 * routinely kill or delay background alarms, which is the #1 reason this notification was
 * reported as "never showing up").
 */
object ReminderNotifier {

    suspend fun checkAndNotify(context: Context): ReminderCheckResult {
        val repository = NoteRepository(AppDatabase.getInstance(context).noteDao(), context.applicationContext)
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val notesToday = repository.getAllReminders()
            .filter { RecurrenceUtils.occursOn(it, todayStart) && !RecurrenceUtils.isCompletedOn(it, todayStart) }
        if (notesToday.isEmpty()) {
            // Nothing left to do today: drop the ongoing notification instead of leaving it
            // stuck forever, e.g. right after the last note of the day gets marked done.
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return ReminderCheckResult.NOTHING_DUE
        }

        return showNotification(context, notesToday)
    }

    fun isNotificationPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Matches the reference screenshot's lock-screen notification: a
     * "[count] <first note>" title with a "Today's notes" subtext, expanding
     * to the full text of what's due.
     */
    @Suppress("MissingPermission") // guarded above: isNotificationPermissionGranted() returns early when POST_NOTIFICATIONS isn't granted
    private fun showNotification(context: Context, notes: List<Note>): ReminderCheckResult {
        if (!isNotificationPermissionGranted(context)) return ReminderCheckResult.PERMISSION_DENIED
        ensureChannel(context)

        val openIntent = Intent(context, CalendarActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun label(note: Note) = note.title.ifBlank { note.body.take(60) }

        val first = notes.first()
        val titleText = "[${notes.size}] ${label(first)}"
        val bigText = if (notes.size == 1) {
            first.body.ifBlank { first.title }
        } else {
            notes.joinToString("\n") { label(it) }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(titleText)
            .setContentText(bigText)
            .setSubText(context.getString(R.string.notification_subtext))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            // Stays put (can't be swiped away) until every note due today is marked done,
            // at which point checkAndNotify() above cancels it -- not auto-cancel-on-tap,
            // since the point is to keep nagging until the day's tasks are actually finished.
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                0,
                context.getString(R.string.snooze_action),
                SnoozeReceiver.snoozeActionPendingIntent(context)
            )
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return ReminderCheckResult.NOTIFIED
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    // "_v2": channel importance can't be changed in code once a channel exists on a device,
    // so existing installs need a new channel id to actually get IMPORTANCE_HIGH (needed for the
    // notification to pop up on screen instead of sitting silently in the shade -- that silent
    // delivery, confirmed via dumpsys on a real device, was the actual "notification never
    // shows up" complaint: it WAS arriving, just invisibly).
    private const val CHANNEL_ID = "daily_reminders_v2"
    const val NOTIFICATION_ID = 2001
}
