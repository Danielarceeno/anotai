package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isNotChecked
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.ui.CalendarPrefs
import com.example.notepad.ui.SettingsActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings "Highlight overdue tasks" switch: on by default (unlike the holidays/vertical-
 *  swipe toggles, this was the app's existing behavior before the setting existed -- see
 *  CalendarPrefs.isOverdueHighlightEnabled), and flipping it persists immediately -- the one
 *  thing DayNotesDialog actually reads to decide whether to color an overdue task's title red
 *  at all (see CalendarOverdueHighlightTest for that side). */
@RunWith(AndroidJUnit4::class)
class SettingsOverdueHighlightToggleTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        CalendarPrefs.setOverdueHighlightEnabled(context, true)
    }

    @After
    fun restoreDefault() {
        CalendarPrefs.setOverdueHighlightEnabled(context, true)
    }

    @Test
    fun overdueHighlightSwitch_isOnByDefault() {
        onView(withId(R.id.overdueHighlightSwitch)).check(matches(isChecked()))
    }

    @Test
    fun togglingOverdueHighlightSwitch_persistsThroughCalendarPrefs() {
        onView(withId(R.id.overdueHighlightSwitch)).perform(click())
        onView(withId(R.id.overdueHighlightSwitch)).check(matches(isNotChecked()))
        assertFalse(CalendarPrefs.isOverdueHighlightEnabled(context))

        onView(withId(R.id.overdueHighlightSwitch)).perform(click())
        onView(withId(R.id.overdueHighlightSwitch)).check(matches(isChecked()))
        assertTrue(CalendarPrefs.isOverdueHighlightEnabled(context))
    }
}
