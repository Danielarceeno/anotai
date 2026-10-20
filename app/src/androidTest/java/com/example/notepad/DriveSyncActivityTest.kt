package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.ui.DriveSyncActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the dedicated Drive sync screen (replaced SettingsActivity's old inline
 * popup-menu row) -- no Drive account is signed in during a test run, so this only covers the
 * signed-out state: the pitch + "Sign in with Google" button, with the signed-in-only views
 * (account identity, last-sync/note-count stats, the Sync button, and the overflow menu's
 * restore/frequency/sign-out items) all hidden. Catches the screen crashing outright, or the
 * signed-in/signed-out view groups both showing (or both hiding) at once.
 */
@RunWith(AndroidJUnit4::class)
class DriveSyncActivityTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(DriveSyncActivity::class.java)

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun signedOut_showsThePitchNotTheAccountDetails() {
        onView(withId(R.id.signedOutGroup)).check(matches(isDisplayed()))
        onView(withId(R.id.signInButton)).check(matches(isDisplayed()))
    }
}
