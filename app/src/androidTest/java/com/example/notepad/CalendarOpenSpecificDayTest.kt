package com.example.notepad

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
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
 * Feature test for [CalendarActivity.EXTRA_OPEN_DAY_MILLIS] -- the generalized "open straight to
 * this day" extra that replaced the old always-today-only behavior, so the daily notification and
 * the widget's per-note taps can each land on the day a task actually appears under (same as
 * tapping that day on the calendar grid) instead of always today or straight into the note's own
 * editor. A day a couple of months out exercises [CalendarActivity.navigateCalendarToMonthOf]:
 * the grid itself must jump to that month too, not just the dialog show the right notes.
 */
@RunWith(AndroidJUnit4::class)
class CalendarOpenSpecificDayTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun openDayMillisExtra_jumpsTheGridAndOpensThatDaysDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())

        val futureDay = startOfDay(Calendar.getInstance().apply { add(Calendar.MONTH, 2); set(Calendar.DAY_OF_MONTH, 10) })
        val expectedMonthLabel = java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(futureDay))
        runBlocking { repository.insert(Note(title = "Two months out", body = "", dueDate = futureDay)) }

        val intent = Intent(context, CalendarActivity::class.java)
            .putExtra(CalendarActivity.EXTRA_OPEN_DAY_MILLIS, futureDay)
        ActivityScenario.launch<CalendarActivity>(intent)

        // The dialog shows the right note...
        onView(withId(R.id.notesContainer)).check(matches(hasDescendant(withText("Two months out"))))
        // ...and the grid underneath actually jumped to that note's month, not just the dialog
        // happening to know the right date independent of what the grid displays. Dismiss the
        // (cancelable) day dialog first -- it's a separate window on top, so Espresso's default
        // root resolution won't find a view from the main Activity's window behind it otherwise.
        pressBack()
        onView(withId(R.id.monthLabel)).check(matches(withSubstring(expectedMonthLabel)))
    }

    private fun startOfDay(cal: Calendar): Long = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
