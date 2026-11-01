package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.notification.ReminderPrefs
import com.example.notepad.security.SecurityPrefs
import com.example.notepad.ui.SettingsActivity
import com.example.notepad.widget.WidgetPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per `TESTING.md`'s "Known gaps": "most of SettingsActivity beyond theme (notifications toggle,
 * widget range, auto-lock picker...) have no automated coverage". Backup frequency is left out
 * on purpose -- its row only shows up signed in to Google Drive, which needs a real account (see
 * TESTING.md's separate, already-documented Drive gap); [DriveBackupHelperIntervalTest] covers
 * its underlying persistence (`DriveBackupHelper.getIntervalHours`/`setIntervalHours`) directly
 * instead, without going through that gated UI.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTogglesTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun turningOffNotifications_persistsAndReflectsInTheSwitch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(true, ReminderPrefs.isEnabled(context)) // on by default

        onView(withId(R.id.notificationsSwitch)).perform(scrollTo(), click())

        assertFalse(ReminderPrefs.isEnabled(context))
    }

    @Test
    fun pickingA3DayWidgetRange_persistsAndUpdatesTheSummaryRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        onView(withId(R.id.widgetRangeRow)).perform(scrollTo(), click())
        onView(withText(R.string.widget_range_3_days)).perform(click())

        assertEquals(3, WidgetPrefs.getRangeDays(context))
        onView(withId(R.id.widgetRangeValue)).check(matches(withText(R.string.widget_range_3_days)))
    }

    @Test
    fun pickingA7DayWidgetRange_persists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        onView(withId(R.id.widgetRangeRow)).perform(scrollTo(), click())
        onView(withText(R.string.widget_range_7_days)).perform(click())

        assertEquals(7, WidgetPrefs.getRangeDays(context))
    }
}

/** Separate class: needs a PIN already set *before* [SettingsActivity] launches, since
 *  `autoLockRow`'s visibility is decided once in `onCreate` from [SecurityPrefs.isPinEnabled] --
 *  see `SettingsChangeAndRemovePinTest`'s identical reasoning for why a plain `@Before` is too
 *  late for that. */
@RunWith(AndroidJUnit4::class)
class SettingsAutoLockTest {

    private val seedPinRule = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                TestPrereqs.resetAppState()
                SecurityPrefs.setPin(InstrumentationRegistry.getInstrumentation().targetContext, "1111")
                base.evaluate()
            }
        }
    }

    @get:Rule
    val ruleChain: org.junit.rules.TestRule =
        org.junit.rules.RuleChain.outerRule(seedPinRule).around(ActivityScenarioRule(SettingsActivity::class.java))

    @Test
    fun picking15Minutes_persistsAndUpdatesTheSummaryRow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label15 = context.getString(R.string.auto_lock_minutes, 15)

        onView(withId(R.id.autoLockRow)).perform(scrollTo(), click())
        onView(withText(label15)).perform(click())

        assertEquals(15, SecurityPrefs.getAutoLockMinutes(context))
        onView(withId(R.id.autoLockValue)).check(matches(withText(label15)))
    }

    @Test
    fun pickingNever_persists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        onView(withId(R.id.autoLockRow)).perform(scrollTo(), click())
        onView(withText(R.string.auto_lock_never)).perform(click())

        assertEquals(SecurityPrefs.AUTO_LOCK_NEVER, SecurityPrefs.getAutoLockMinutes(context))
    }
}
