package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.ui.SettingsActivity
import com.example.notepad.ui.ThemePrefs
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Picking a theme in Settings updates the row's summary text and [ThemePrefs] immediately. */
@RunWith(AndroidJUnit4::class)
class SettingsThemeTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(SettingsActivity::class.java)

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun pickingDarkTheme_updatesTheSummaryRow() {
        onView(withId(R.id.themeRow)).perform(click())
        onView(withText(R.string.theme_dark)).perform(click())

        onView(withId(R.id.themeValue)).check(matches(withText(R.string.theme_dark)))
    }

    @After
    fun restoreSystemTheme() {
        // ThemePrefs.setMode -> AppCompatDelegate.setDefaultNightMode() can recreate() any
        // currently-active AppCompat activity (here, this test's own still-open SettingsActivity,
        // since this @After runs before ActivityScenarioRule's own teardown) -- Activity.recreate
        // asserts it's called on the main thread, which a plain JUnit @After is not. Caught on a
        // real CI run as `IllegalStateException: Must be called from main thread`.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ThemePrefs.setMode(InstrumentationRegistry.getInstrumentation().targetContext, ThemePrefs.MODE_SYSTEM)
        }
    }
}
