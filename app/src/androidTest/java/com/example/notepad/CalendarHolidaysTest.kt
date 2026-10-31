package com.example.notepad

import android.Manifest
import android.content.Context
import android.os.Build
import android.widget.DatePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.PickerActions
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withSubstring
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.CalendarPrefs
import java.util.Calendar
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Feature test for the Settings "Show Brazilian holidays" toggle (off by default, see
 * CalendarPrefs/BrazilianHolidays): with it off, jumping to a known holiday's day shows a plain
 * date title; with it on, the holiday's name is appended to that same dialog's title. Jumps to
 * the date via the month label's DatePickerDialog (CalendarActivity.showMonthPicker) rather than
 * swiping/paging there, since that's the one entry point that opens an arbitrary day's dialog
 * directly, regardless of what day the test actually runs on.
 */
@RunWith(AndroidJUnit4::class)
class CalendarHolidaysTest {

    private val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }
    private val activityRule = ActivityScenarioRule(CalendarActivity::class.java)

    @get:Rule
    val ruleChain: RuleChain =
        RuleChain.outerRule(TestPrereqs.resetStateRule()).around(permissionRule).around(activityRule)

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun restoreDefault() {
        CalendarPrefs.setHolidaysEnabled(context, false)
    }

    @Test
    fun holidaysDisabledByDefault_christmasDayDialogHasNoHolidayName() {
        CalendarPrefs.setHolidaysEnabled(context, false)
        jumpToDate(2026, Calendar.DECEMBER, 25)

        onView(withId(R.id.dayTitle)).check(matches(isDisplayed()))
        onView(withSubstring("Natal")).check(doesNotExist())
    }

    @Test
    fun enablingHolidays_showsTheHolidayNameInTheDayDialogTitle() {
        CalendarPrefs.setHolidaysEnabled(context, true)
        jumpToDate(2026, Calendar.DECEMBER, 25)

        onView(withSubstring("Natal")).check(matches(isDisplayed()))
    }

    @Test
    fun enablingHolidays_aMoveableHolidayIsAlsoRecognized() {
        // Carnaval 2026 is Feb 17 -- only passes if the toggle is actually wired through to
        // BrazilianHolidays' Easter-derived dates, not just its fixed-date ones.
        CalendarPrefs.setHolidaysEnabled(context, true)
        jumpToDate(2026, Calendar.FEBRUARY, 17)

        onView(withSubstring("Carnaval")).check(matches(isDisplayed()))
    }

    @Test
    fun enablingHolidays_anOrdinaryDayStillHasNoHolidayName() {
        CalendarPrefs.setHolidaysEnabled(context, true)
        jumpToDate(2026, Calendar.JANUARY, 2)

        onView(withId(R.id.dayTitle)).check(matches(isDisplayed()))
        onView(withSubstring("Natal")).check(doesNotExist())
        onView(withSubstring("Carnaval")).check(doesNotExist())
    }

    /** [month] is a [Calendar] constant (0-based); [PickerActions.setDate] wants 1-based. */
    private fun jumpToDate(year: Int, month: Int, day: Int) {
        onView(withId(R.id.monthLabel)).perform(click())
        onView(isAssignableFrom(DatePicker::class.java)).perform(PickerActions.setDate(year, month + 1, day))
        onView(withId(android.R.id.button1)).perform(click())
    }
}
