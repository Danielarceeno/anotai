package com.example.notepad.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarms don't survive a reboot, so re-schedule the daily reminder -- and every note's own
 *  exact-time alarm, if it has one (see [NoteReminderScheduler]) -- once the device comes back up. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        ReminderScheduler.scheduleNext(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = NoteRepository(AppDatabase.getInstance(context).noteDao(), context)
                repository.getAllReminders()
                    .filter { it.reminderTimeMinutes >= 0 }
                    .forEach { NoteReminderScheduler.schedule(context, it) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
