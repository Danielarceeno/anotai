package com.example.notepad

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.notification.ReminderNotifier
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.CalendarPrefs
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feature test for the user-requested overdue cue: a day strictly before today with a reminder
 * that's still not marked done gets its title text (not the calendar grid cell -- an earlier
 * version tinted the whole cell background, which the user asked to undo) colored red in the day
 * dialog (DayNotesDialog / R.color.task_overdue_text). Opens straight to the day under test via
 * [CalendarActivity.EXTRA_OPEN_DAY_MILLIS] (the same mechanism the daily notification and the
 * widget's per-note taps now use -- see CalendarActivity.openDayMillisFrom) rather than navigating
 * the grid by hand, which also doubles as coverage for that extra actually landing on the right day.
 */
@RunWith(AndroidJUnit4::class)
class CalendarOverdueHighlightTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private lateinit var context: Context
    private lateinit var repository: NoteRepository

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        // Explicit, not just relying on the true default: a leftover false from
        // SettingsOverdueHighlightToggleTest running first would otherwise leak in here.
        CalendarPrefs.setOverdueHighlightEnabled(context, true)
    }

    /** [noteDueToday_titleIsNotRedEvenIfIncomplete] inserts a note due today and resumes
     *  CalendarActivity, whose onResume always runs an immediate reminder check -- that's a real
     *  system notification this class doesn't otherwise care about, and it outliving this test
     *  class can trip up another test's "nothing posted yet" assumption (see TestPrereqs'
     *  own doc comment for the general version of this). */
    @After
    fun cleanup() {
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ReminderNotifier.NOTIFICATION_ID)
        CalendarPrefs.setOverdueHighlightEnabled(context, true)
    }

    @Test
    fun incompleteOverdueNote_titleIsRedInTheDayDialog() {
        val yesterday = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) })
        runBlocking { repository.insert(Note(title = "Overdue task", body = "", dueDate = yesterday, completed = false)) }

        launchOnDay(yesterday)

        onView(withText("Overdue task")).check(matches(withTextColor(R.color.task_overdue_text)))
    }

    @Test
    fun overdueButAlreadyCompletedNote_titleIsNotRed() {
        val yesterday = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) })
        runBlocking { repository.insert(Note(title = "Finished task", body = "", dueDate = yesterday, completed = true)) }

        launchOnDay(yesterday)

        onView(withText("Finished task")).check(matches(not(withTextColor(R.color.task_overdue_text))))
    }

    @Test
    fun noteDueToday_titleIsNotRedEvenIfIncomplete() {
        val today = startOfDay(Calendar.getInstance())
        runBlocking { repository.insert(Note(title = "Today task", body = "", dueDate = today, completed = false)) }

        launchOnDay(today)

        onView(withText("Today task")).check(matches(not(withTextColor(R.color.task_overdue_text))))
    }

    @Test
    fun overdueHighlightDisabledInSettings_titleIsNotRed() {
        CalendarPrefs.setOverdueHighlightEnabled(context, false)
        val yesterday = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) })
        runBlocking { repository.insert(Note(title = "Overdue but muted", body = "", dueDate = yesterday, completed = false)) }

        launchOnDay(yesterday)

        onView(withText("Overdue but muted")).check(matches(not(withTextColor(R.color.task_overdue_text))))
    }

    /** Launches straight to [dayMillis]'s day dialog via [CalendarActivity.EXTRA_OPEN_DAY_MILLIS]. */
    private fun launchOnDay(dayMillis: Long) {
        val intent = Intent(context, CalendarActivity::class.java)
            .putExtra(CalendarActivity.EXTRA_OPEN_DAY_MILLIS, dayMillis)
        ActivityScenario.launch<CalendarActivity>(intent)
    }

    private fun not(matcher: Matcher<android.view.View>): Matcher<android.view.View> = org.hamcrest.core.IsNot.not(matcher)

    private fun withTextColor(colorRes: Int): Matcher<android.view.View> = object : TypeSafeMatcher<android.view.View>() {
        override fun describeTo(description: Description) {
            description.appendText("with text color resource $colorRes")
        }

        override fun matchesSafely(view: android.view.View): Boolean {
            if (view !is TextView) return false
            return view.currentTextColor == ContextCompat.getColor(view.context, colorRes)
        }
    }

    private fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
