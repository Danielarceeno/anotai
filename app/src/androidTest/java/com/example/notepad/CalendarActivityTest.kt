package com.example.notepad

import android.Manifest
import android.os.Build
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.ui.CalendarActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/** The app's actual launcher screen. Mainly guards against a crash on cold start -- this is the
 *  one Activity every single user hits first, PIN-gate included. */
@RunWith(AndroidJUnit4::class)
class CalendarActivityTest {

    // Granted up front so the POST_NOTIFICATIONS system dialog CalendarActivity.onCreate can
    // trigger on API 33+ never has a chance to appear and block Espresso, which can't interact
    // with permission dialogs outside the app's own view hierarchy.
    private val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }
    private val activityRule = ActivityScenarioRule(CalendarActivity::class.java)

    // Order matters: state must be reset, then permission granted, *then* the Activity launches
    // -- a plain @Before runs after ActivityScenarioRule has already started the Activity.
    @get:Rule
    val ruleChain: RuleChain =
        RuleChain.outerRule(TestPrereqs.resetStateRule()).around(permissionRule).around(activityRule)

    @Test
    fun opensToTheMonthlyGrid() {
        onView(withId(R.id.monthLabel)).check(matches(isDisplayed()))
        onView(withId(R.id.calendarGrid)).check(matches(isDisplayed()))
        onView(withId(R.id.addNoteButton)).check(matches(isDisplayed()))
    }
}
