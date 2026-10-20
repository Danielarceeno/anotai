package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.ui.MainActivity
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression coverage for the checklist "add item" row restructuring: it used to be a separate,
 * always-visible input bar below the RecyclerView; now it's the RecyclerView's own trailing item
 * (ChecklistAdapter.VIEW_TYPE_ADD), re-created after every add so the *same* R.id.newItemText
 * stays usable for the next item. If that row ever stopped being recreated (or got left
 * mid-list instead of trailing), adding a second or third item in one sitting -- exactly what
 * this test does -- would be the first thing to break.
 */
@RunWith(AndroidJUnit4::class)
class ChecklistAddRowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun addingSeveralItemsInARow_allShowUpInOrder() {
        onView(withId(R.id.addNoteButton)).perform(click())
        onView(withId(R.id.optionChecklist)).perform(click())

        onView(withId(R.id.titleInput)).perform(typeText("Grocery list"))

        // Same row (R.id.newItemText), used three times in a row -- it must come back empty and
        // ready after each submit, not get stuck on the item that was just added.
        listOf("Milk", "Eggs", "Bread").forEach { item ->
            onView(withId(R.id.newItemText)).perform(typeText(item))
            onView(withId(R.id.newItemText)).perform(pressImeActionButton())
        }

        onView(withId(R.id.doneButton)).perform(click())

        // Back on the main list: the card's "n/m done" subtitle is the cheapest signal that all
        // three items actually got persisted (not just visible mid-edit), and in order (title
        // match already confirms the note itself saved).
        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Grocery list"))))
        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("0/3 done"))))
    }
}
