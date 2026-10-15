package com.example.notepad

import android.Manifest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.notification.ReminderNotifier
import com.example.notepad.ui.CalendarActivity
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Regression test for a bug reported by the user: creating a note due today and returning to the
 * calendar didn't surface the reminder notification. The cause was that the daily-reminder check
 * only ran from [CalendarActivity.onCreate], not [CalendarActivity.onResume] -- so coming back to
 * this screen without a fresh `onCreate` (the normal case after creating/editing a note elsewhere,
 * since the calendar is `singleTop`) never re-ran it. This drives the Activity through onCreate,
 * inserts a note due today *after* that initial check already ran (so nothing is showing yet,
 * mirroring the real bug), then cycles it through onResume the way coming back from another
 * screen does, and checks the ongoing notification is now up.
 */
@RunWith(AndroidJUnit4::class)
class CalendarResumeRefreshesReminderTest {

    private val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }
    private val activityRule = ActivityScenarioRule(CalendarActivity::class.java)

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain.outerRule(TestPrereqs.resetStateRule()).around(permissionRule).around(activityRule)

    @Test
    fun returningToTheCalendar_refreshesTodaysReminderNotification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notificationManager = androidx.core.app.NotificationManagerCompat.from(context)

        // Nothing was due when the screen first opened (resetAppState leaves no notes behind).
        assertFalse(
            "no reminder notification should be showing before any note is due",
            notificationManager.activeNotifications.any { it.id == ReminderNotifier.NOTIFICATION_ID }
        )

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val repository = NoteRepository(AppDatabase.getInstance(context).noteDao(), context)
        runBlocking { repository.insert(Note(title = "Due today", body = "", dueDate = todayStart)) }

        // Simulates leaving this (singleTop) screen and coming back, e.g. after creating that
        // note in NoteEditorActivity and pressing back -- onPause then onResume, no fresh onCreate.
        activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        val deadline = System.currentTimeMillis() + 5000
        var notified = false
        while (System.currentTimeMillis() < deadline && !notified) {
            notified = notificationManager.activeNotifications.any { it.id == ReminderNotifier.NOTIFICATION_ID }
            if (!notified) Thread.sleep(100)
        }
        assertTrue("reminder notification should appear after resuming the calendar", notified)
    }
}
