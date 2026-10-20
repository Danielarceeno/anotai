package com.example.notepad

import android.Manifest
import android.os.Build
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.LockActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [LockActivity] -- the PIN gate in front of [CalendarActivity] -- had no automated coverage: a
 * wrong PIN must show the error and stay locked, a correct one must actually unlock
 * ([LockState]). The PIN is set directly via [SecurityPrefs] rather than through Settings' own
 * set-PIN dialog, since that dialog's own UI is a separate concern from whether
 * [SecurityPrefs.checkPin]'s result actually gates this screen.
 *
 * The correct-PIN case ends by starting [CalendarActivity] as a real side effect of unlocking
 * (see [LockActivity.unlock]) -- the notification-permission rule below is granted up front for
 * the same reason [CalendarActivityTest] grants it: so that screen's own `onCreate` can't pop a
 * system permission dialog Espresso has no way to dismiss. That [CalendarActivity] instance isn't
 * managed by any [ActivityScenarioRule] here, so [cleanUp] finishes it explicitly -- left alone,
 * it stayed alive into later tests in the same instrumentation process and broke one of them: a
 * later theme change ([SettingsThemeTest], via `AppCompatDelegate.setDefaultNightMode` ->
 * `Activity.recreate()` on every still-tracked AppCompat activity) hit this orphaned instance and
 * threw `IllegalStateException: Must be called from main thread`, caught on a real CI run.
 */
@RunWith(AndroidJUnit4::class)
class SecurityLockFlowTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @get:Rule
    val activityRule = ActivityScenarioRule(LockActivity::class.java)

    @Before
    fun setPin() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.clearPin(context)
        SecurityPrefs.setPin(context, "1234")
    }

    @After
    fun cleanUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.clearPin(context)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Give the just-started CalendarActivity's own onCreate/onResume a chance to actually
        // run first -- starting it is asynchronous, so checking Stage.RESUMED immediately after
        // perform(click()) returns could still miss it (a real, observed timing gap on CI).
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)
                .flatMap { monitor.getActivitiesInStage(it) }
                .filterIsInstance<CalendarActivity>()
                .distinct()
                .forEach { it.finish() }
        }
    }

    @Test
    fun wrongPin_showsErrorAndStaysLocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        onView(withId(R.id.pinInput)).perform(typeText("0000"))
        onView(withId(R.id.unlockButton)).perform(click())

        onView(withId(R.id.errorView)).check(matches(isDisplayed()))
        assertFalse("0000 should not match the PIN that was actually set", SecurityPrefs.checkPin(context, "0000"))
    }

    @Test
    fun correctPin_unlocksTheApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        onView(withId(R.id.pinInput)).perform(typeText("1234"))
        onView(withId(R.id.unlockButton)).perform(click())

        assertFalse("LockState should record this device as unlocked after a correct PIN", LockState.isLocked(context))
    }
}
