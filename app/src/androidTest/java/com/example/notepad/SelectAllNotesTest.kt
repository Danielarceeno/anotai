package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression/feature test for a user request: bulk-deleting had no way to grab every note at
 * once, only whichever ones got long-pressed by hand. Long-pressing one note to enter selection
 * mode, then picking "Select all" from the overflow menu, should select every note regardless of
 * how many are on screen, and deleting from there should trash all of them.
 */
@RunWith(AndroidJUnit4::class)
class SelectAllNotesTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private lateinit var repository: NoteRepository

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = NoteRepository(AppDatabase.getInstance(context).noteDao(), context)
        runBlocking {
            repository.insert(Note(title = "First note", body = ""))
            repository.insert(Note(title = "Second note", body = ""))
            repository.insert(Note(title = "Third note", body = ""))
        }
    }

    @Test
    fun selectAll_thenBulkDelete_trashesEveryNote() {
        onView(withId(R.id.recyclerView)).perform(actionOnItemAtPosition<androidx.recyclerview.widget.RecyclerView.ViewHolder>(0, longClick()))

        onView(withContentDescription(androidx.appcompat.R.string.abc_action_menu_overflow_description))
            .perform(click())
        onView(withText(R.string.action_select_all)).perform(click())

        onView(withId(R.id.action_bulk_delete)).perform(click())
        onView(withText(R.string.action_remove)).perform(click())

        val deadline = System.currentTimeMillis() + 5000
        var allTrashed = false
        while (System.currentTimeMillis() < deadline && !allTrashed) {
            allTrashed = runBlocking { repository.getAllForExport() }.isEmpty()
            if (!allTrashed) Thread.sleep(100)
        }
        assertTrue("every note should be trashed after select-all + bulk delete", allTrashed)
    }
}
