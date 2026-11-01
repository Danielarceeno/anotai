package com.example.notepad

import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.security.SecurityPrefs
import com.example.notepad.ui.SettingsActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Both flows here need a PIN already set *before* `SettingsActivity` launches, since its
 * `onCreate` reads [SecurityPrefs.isPinEnabled] exactly once to decide `pinLockSwitch`'s initial
 * state and whether `changePinRow`/`autoLockRow` show at all -- seeding it from a plain `@Before`
 * (which runs *after* `ActivityScenarioRule` has already launched, per this project's own
 * documented lesson) would be too late. [RuleChain] runs the seeding rule, then the launch.
 */
@RunWith(AndroidJUnit4::class)
class SettingsChangeAndRemovePinTest {

    private val seedPinRule = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                TestPrereqs.resetAppState()
                SecurityPrefs.setPin(InstrumentationRegistry.getInstrumentation().targetContext, "1111")
                base.evaluate()
            }
        }
    }

    @get:Rule
    val ruleChain: TestRule = RuleChain.outerRule(seedPinRule).around(ActivityScenarioRule(SettingsActivity::class.java))

    @Test
    fun changingThePin_replacesTheOldOne() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // changePinRow sits inside a plain ScrollView, which click() alone does not auto-scroll
        // to -- see SettingsSetPinDialogTest.openSetPinDialog's identical comment.
        onView(withId(R.id.changePinRow)).perform(scrollTo(), click())
        onView(withId(R.id.newPinInput)).perform(typeText("2222"))
        onView(withId(R.id.confirmPinInput)).perform(typeText("2222"))
        closeSoftKeyboard()
        onView(withId(android.R.id.button1)).perform(click())

        assertFalse("The old PIN must stop working once changed", SecurityPrefs.checkPin(context, "1111"))
        assertTrue("The new PIN must work", SecurityPrefs.checkPin(context, "2222"))
    }

    @Test
    fun turningOffTheSwitch_removesThePin_andHidesDependentRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        onView(withId(R.id.pinLockSwitch)).perform(scrollTo(), click())

        assertFalse(SecurityPrefs.isPinEnabled(context))
        assertFalse(SecurityPrefs.checkPin(context, "1111"))
        onView(withId(R.id.changePinRow)).check(matches(withEffectiveVisibility(Visibility.GONE)))
    }
}
