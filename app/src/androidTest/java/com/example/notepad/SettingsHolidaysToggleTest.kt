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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings "Show Brazilian holidays" switch: off by default, and flipping it persists
 *  through [CalendarPrefs] immediately -- the one thing [CalendarActivity] actually reads to
 *  decide whether to show holidays at all (see CalendarHolidaysTest for that side). */
@RunWith(AndroidJUnit4::class)
class SettingsHolidaysToggleTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        CalendarPrefs.setHolidaysEnabled(context, false)
    }

    @After
    fun restoreDefault() {
        CalendarPrefs.setHolidaysEnabled(context, false)
    }

    @Test
    fun holidaysSwitch_isOffByDefault() {
        onView(withId(R.id.holidaysSwitch)).check(matches(isNotChecked()))
    }

    @Test
    fun togglingHolidaysSwitch_persistsThroughCalendarPrefs() {
        onView(withId(R.id.holidaysSwitch)).perform(click())
        onView(withId(R.id.holidaysSwitch)).check(matches(isChecked()))
        org.junit.Assert.assertTrue(CalendarPrefs.isHolidaysEnabled(context))

        onView(withId(R.id.holidaysSwitch)).perform(click())
        onView(withId(R.id.holidaysSwitch)).check(matches(isNotChecked()))
        org.junit.Assert.assertFalse(CalendarPrefs.isHolidaysEnabled(context))
    }
}
