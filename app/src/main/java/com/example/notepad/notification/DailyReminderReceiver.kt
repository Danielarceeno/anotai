package com.example.notepad.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires once a day (see [ReminderScheduler]); notifies only when there's actually a note due today. */
class DailyReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ReminderNotifier.checkAndNotify(context)
            } finally {
                // Chain tomorrow's alarm regardless of whether we notified today, so a
                // missed/declined exact-alarm permission doesn't stop future days either.
                ReminderScheduler.scheduleNext(context)
                pendingResult.finish()
            }
        }
    }
}
