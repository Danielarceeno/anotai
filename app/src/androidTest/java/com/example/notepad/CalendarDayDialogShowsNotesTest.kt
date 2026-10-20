package com.example.notepad

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.CalendarActivity
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end coverage for the calendar's day dialog: a plain note due today, a *recurring* note
 * due on an earlier day (so this only passes if RecurrenceUtils.occursOn is actually wired up
 * through CalendarActivity.buildDays, not just correct in isolation -- see RecurrenceUtilsTest
 * for the pure-function version of that), and a pinned note (regression guard for the pinned
 * star added to DayNotesDialog).
 */
@RunWith(AndroidJUnit4::class)
class CalendarDayDialogShowsNotesTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @Before
    fun seedNotes() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        val todayStart = startOfDay(Calendar.getInstance())
        val threeDaysAgo = startOfDay(Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -3) })

        runBlocking {
            repository.insert(Note(title = "Due today", body = "", dueDate = todayStart))
            repository.insert(
                Note(
                    title = "Daily recurring",
                    body = "",
                    dueDate = threeDaysAgo,
                    repetition = Note.REPEAT_DAILY
                )
            )
            repository.insert(Note(title = "Pinned today", body = "", dueDate = todayStart, pinned = true))
        }
    }

    @Test
    fun openingTheCalendarOnToday_showsDueAndRecurringAndPinnedNotes() {
        val intent = Intent(InstrumentationRegistry.getInstrumentation().targetContext, CalendarActivity::class.java)
            .putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
        ActivityScenario.launch<CalendarActivity>(intent)

        onView(withId(R.id.notesContainer)).check(matches(hasDescendant(withText("Due today"))))
        onView(withId(R.id.notesContainer)).check(matches(hasDescendant(withText("Daily recurring"))))
        onView(withId(R.id.notesContainer)).check(matches(hasDescendant(withText("Pinned today"))))
        onView(withContentDescription(R.string.note_pinned)).check(matches(isDisplayed()))
    }

    private fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
