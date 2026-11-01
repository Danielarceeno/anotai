package com.example.notepad

import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.security.SecurityPrefs
import com.example.notepad.ui.SettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * `SettingsActivity.showSetPinDialog`'s own validation, previously untested (see `TESTING.md`'s
 * "Known gaps": `SecurityLockFlowTest` only exercises [LockActivity] by setting a PIN directly
 * via [SecurityPrefs], never through this dialog). No pre-existing PIN here -- see
 * `SettingsChangeAndRemovePinTest` for the change/remove flows, which need one already set
 * *before* `SettingsActivity` launches (its `onCreate` reads [SecurityPrefs.isPinEnabled] once,
 * to decide `pinLockSwitch`'s initial state and whether `changePinRow`/`autoLockRow` show at all).
 *
 * [TestPrereqs.resetAppState] runs via [RuleChain], *before* the Activity launches, not from a
 * plain `@Before` -- caught on a real full-suite run: a `@Before` runs after `onCreate` has
 * already read [SecurityPrefs.isPinEnabled] into `pinLockSwitch`'s initial state, so a PIN left
 * over from a *different* test class that ran earlier in the same instrumentation process (e.g.
 * `SettingsChangeAndRemovePinTest`, which sets one directly and never clears it) made the switch
 * launch already checked -- `openSetPinDialog`'s click then turned it *off* instead of opening
 * the dialog, and every assertion after that failed for an unrelated reason.
 */
@RunWith(AndroidJUnit4::class)
class SettingsSetPinDialogTest {

    @get:Rule
    val ruleChain: TestRule = RuleChain.outerRule(TestPrereqs.resetStateRule()).around(ActivityScenarioRule(SettingsActivity::class.java))

    private fun openSetPinDialog() {
        // pinLockSwitch sits inside a plain ScrollView, which (unlike an AdapterView-backed
        // list) Espresso's click() does not auto-scroll to -- without this, the row is still
        // below the fold and the click fails the "displays at least 90%" precondition.
        onView(withId(R.id.pinLockSwitch)).perform(scrollTo(), click())
    }

    @Test
    fun shortPin_showsError_andDoesNotEnablePinLock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        openSetPinDialog()
        onView(withId(R.id.newPinInput)).perform(typeText("123"))
        onView(withId(R.id.confirmPinInput)).perform(typeText("123"))
        closeSoftKeyboard()
        onView(withId(android.R.id.button1)).perform(click())

        onView(withId(R.id.pinErrorView)).check(matches(isDisplayed()))
        onView(withId(R.id.pinErrorView)).check(matches(withText(R.string.pin_too_short)))
        assertFalse("A too-short PIN must not actually get saved", SecurityPrefs.isPinEnabled(context))
    }

    @Test
    fun mismatchedConfirmation_showsError_andDoesNotEnablePinLock() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        openSetPinDialog()
        onView(withId(R.id.newPinInput)).perform(typeText("1234"))
        onView(withId(R.id.confirmPinInput)).perform(typeText("5678"))
        closeSoftKeyboard()
        onView(withId(android.R.id.button1)).perform(click())

        onView(withId(R.id.pinErrorView)).check(matches(isDisplayed()))
        onView(withId(R.id.pinErrorView)).check(matches(withText(R.string.pin_mismatch)))
        assertFalse("A mismatched confirmation must not actually get saved", SecurityPrefs.isPinEnabled(context))
    }

    @Test
    fun validPin_enablesLock_andPersistsASaltedHash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        openSetPinDialog()
        onView(withId(R.id.newPinInput)).perform(typeText("1234"))
        onView(withId(R.id.confirmPinInput)).perform(typeText("1234"))
        closeSoftKeyboard()
        onView(withId(android.R.id.button1)).perform(click())

        assertTrue(SecurityPrefs.isPinEnabled(context))
        assertTrue("The PIN just set through the dialog must actually verify", SecurityPrefs.checkPin(context, "1234"))
        assertFalse(SecurityPrefs.checkPin(context, "0000"))

        // A per-install random salt (see SecurityPrefs/PasswordHasher's own doc) means setting
        // the exact same PIN twice must still produce two different stored hashes -- the one
        // concrete, black-box way to tell "really salted" apart from "the same PIN always hashes
        // the same", without reaching into SecurityPrefs' private SharedPreferences keys.
        val firstHash = rawPinHash(context)
        SecurityPrefs.setPin(context, "1234")
        val secondHash = rawPinHash(context)
        assertNotEquals("Same PIN, two different per-install salts, must not hash the same", firstHash, secondHash)
    }

    /** Reads the exact SharedPreferences file/key [SecurityPrefs] itself persists to, mirroring
     *  its own private constants -- there's no public accessor for the raw hash, and adding one
     *  only for this test would be a bigger production-code change than just reading the file
     *  Settings already writes to disk. */
    private fun rawPinHash(context: android.content.Context): String? =
        context.getSharedPreferences("security_prefs", android.content.Context.MODE_PRIVATE).getString("pin_hash", null)
}
