package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.ui.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end: create a text note from the notes list, save it, and see it show up in the list. */
@RunWith(AndroidJUnit4::class)
class CreateEditNoteFlowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun creatingATextNote_showsUpInTheList() {
        onView(withId(R.id.addNoteButton)).perform(click())
        onView(withId(R.id.optionText)).perform(click())

        onView(withId(R.id.titleInput)).perform(typeText("Espresso title"))
        onView(withId(R.id.bodyInput)).perform(typeText("Espresso body"))
        onView(withId(R.id.doneButton)).perform(click())

        onView(withId(R.id.recyclerView)).check(
            matches(hasDescendant(withText("Espresso title")))
        )
    }

    @Test
    fun creatingAChecklistNote_showsUpInTheList() {
        onView(withId(R.id.addNoteButton)).perform(click())
        onView(withId(R.id.optionChecklist)).perform(click())

        onView(withId(R.id.titleInput)).perform(typeText("Espresso checklist"))
        // The "add item" row is the checklist RecyclerView's own trailing item
        // (ChecklistAdapter.VIEW_TYPE_ADD) now, not a separate always-visible input field.
        onView(withId(R.id.newItemText)).perform(typeText("first item"))
        onView(withId(R.id.newItemText)).perform(androidx.test.espresso.action.ViewActions.pressImeActionButton())
        onView(withId(R.id.doneButton)).perform(click())

        onView(withId(R.id.recyclerView)).check(
            matches(hasDescendant(withText("Espresso checklist")))
        )
    }
}
