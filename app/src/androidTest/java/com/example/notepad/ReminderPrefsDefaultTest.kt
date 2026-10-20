package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.notification.ReminderPrefs
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Settings row that let the daily reminder time be customized was removed (the check itself
 * still runs, just no longer configurable from the UI), which makes ReminderPrefs's *default*
 * the only time it'll ever fire for anyone who hasn't already set a custom one from before that
 * removal. Locks in that it's still 9:00am, since nothing would catch a change to that default
 * other than a user noticing the notification moved.
 */
@RunWith(AndroidJUnit4::class)
class ReminderPrefsDefaultTest {

    @Test
    fun defaultDailyReminderTime_isNineAM() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("reminder_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        assertEquals(9, ReminderPrefs.getHour(context))
        assertEquals(0, ReminderPrefs.getMinute(context))
    }
}
