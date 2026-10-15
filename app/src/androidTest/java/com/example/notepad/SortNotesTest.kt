package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.ui.MainActivity
import com.example.notepad.ui.NotesSortPrefs
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The notes-list sort menu (Most recent / Color): picking an option persists it and is picked
 *  back up by [com.example.notepad.ui.NotesSortPrefs] on the next read. */
@RunWith(AndroidJUnit4::class)
class SortNotesTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NotesSortPrefs.setSortMode(context, NotesSortPrefs.SORT_RECENT)
    }

    @Test
    fun pickingColorSort_persistsTheChoice() {
        // "Sort" lives in the plain Toolbar's own overflow menu (this screen doesn't use the
        // framework options-menu system), so open it by its standard "More options" content
        // description rather than Espresso's ActionBar-only overflow helper.
        onView(withContentDescription(androidx.appcompat.R.string.abc_action_menu_overflow_description))
            .perform(click())
        onView(withText(R.string.menu_sort)).perform(click())
        onView(withText(R.string.sort_color)).perform(click())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(NotesSortPrefs.SORT_COLOR, NotesSortPrefs.getSortMode(context))
    }
}
