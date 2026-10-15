package com.example.notepad.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires at a single note's exact reminder time (see [NoteReminderScheduler]). Shows the same
 *  bundled "today's notes" notification the daily 9am check would (not a separate one per note),
 *  then reschedules this note's own next occurrence if it repeats, so the alarm keeps chaining
 *  itself one occurrence at a time like [DailyReminderReceiver] already does. */
class NoteReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        if (noteId == -1L) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderNotifier.checkAndNotify(context)
                val repository = NoteRepository(AppDatabase.getInstance(context).noteDao(), context)
                repository.getById(noteId)?.let { NoteReminderScheduler.schedule(context, it) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }
}
