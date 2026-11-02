package com.example.notepad

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 8 mission: Espresso's built-in accessibility validator
 * ([androidx.test.espresso.accessibility.AccessibilityChecks], see [AccessibilityChecksRule]'s
 * own doc), run against the app's main flows -- opening the calendar and today's day dialog,
 * creating a text note, creating a checklist note.
 *
 * **Deliberately kept separate from `CalendarActivityTest`/`CreateEditNoteFlowTest` and run in
 * isolation, not as part of a routine full-suite `connectedDebugAndroidTest` run**:
 * `AccessibilityChecks` has no way to turn itself back off once enabled, so mixing it into the
 * main suite makes it silently start checking every *other* test that happens to run afterward
 * in the same process too -- including screens never audited for this (confirmed: doing so once
 * surfaced 13 failures across holidays/folders/note-password/PIN-dialog screens in one run,
 * which is real signal but a much bigger separate audit, not this one check). Run this class on
 * its own:
 * `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.notepad.AccessibilityChecksTest`
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityChecksTest {

    @get:Rule
    val accessibilityRule = AccessibilityChecksRule()

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun openingTheCalendarAndTodaysDayDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, CalendarActivity::class.java).putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
        ActivityScenario.launch<CalendarActivity>(intent).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test
    fun creatingATextNote() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.addNoteButton)).perform(click())
            onView(withId(R.id.optionText)).perform(click())
            onView(withId(R.id.titleInput)).perform(typeText("Accessibility check"))
            onView(withId(R.id.bodyInput)).perform(typeText("Body"))
            onView(withId(R.id.doneButton)).perform(click())
        }
    }

    @Test
    fun creatingAChecklistNote() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.addNoteButton)).perform(click())
            onView(withId(R.id.optionChecklist)).perform(click())
            onView(withId(R.id.titleInput)).perform(typeText("Accessibility checklist"))
            onView(withId(R.id.newItemText)).perform(typeText("first item"))
            onView(withId(R.id.newItemText)).perform(androidx.test.espresso.action.ViewActions.pressImeActionButton())
            onView(withId(R.id.doneButton)).perform(click())
        }
    }
}
