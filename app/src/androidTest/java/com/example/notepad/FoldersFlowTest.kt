package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.FolderRepository
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.FoldersActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * End-to-end coverage for folder CRUD, which FoldersActivity had none of before this: creating
 * one shows it in the list, renaming updates the row in place, and deleting one doesn't delete
 * the notes filed under it -- FolderRepository.delete's documented contract is that they fall
 * back to "no folder" (folderId = 0), not get silently removed along with the folder.
 *
 * The add/rename dialogs set their title and positive button to the *same* string resource
 * (e.g. "Add folder" is both), so a plain `withText(...)` match would be ambiguous between the
 * title TextView and the button -- disambiguated below by also requiring the view to be a
 * [android.widget.Button].
 *
 * [TestPrereqs.resetStateRule] (not a plain `@Before`) is chained to run *before*
 * [ActivityScenarioRule] launches the Activity -- a plain `@Before` runs too late for that (see
 * [CalendarActivityTest]'s own doc comment for the same fix): FoldersActivity's `onCreate`
 * registers `repository.observeAll().observe(...)` immediately, and a leftover folder from a
 * previous test method in this same class (the DB is a process-wide singleton, not reset between
 * methods on its own) would render its row once before the clear, racing the then-fresh row's
 * own DiffUtil update and occasionally leaving both rows' `deleteButton` attached at once --
 * exactly the `AmbiguousViewMatcherException` this was caught by.
 */
@RunWith(AndroidJUnit4::class)
class FoldersFlowTest {

    private val activityRule = ActivityScenarioRule(FoldersActivity::class.java)

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(TestPrereqs.resetStateRule()).around(activityRule)

    private lateinit var noteRepository: NoteRepository
    private lateinit var folderRepository: FolderRepository

    @Before
    fun setUpRepositories() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        noteRepository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        folderRepository = FolderRepository(db.folderDao(), db.noteDao())
    }

    @Test
    fun creatingAFolder_showsUpInTheList() {
        onView(withId(R.id.addFolderButton)).perform(click())
        onView(withId(R.id.textInput)).perform(typeText("Groceries"))
        onView(allOf(withText(R.string.folder_add), isAssignableFrom(android.widget.Button::class.java)))
            .perform(click())

        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Groceries"))))
    }

    @Test
    fun renamingAFolder_updatesItsRow() {
        runBlocking { folderRepository.insert("Old name") }

        onView(withId(R.id.renameButton)).perform(click())
        onView(withId(R.id.textInput)).perform(clearText(), typeText("Updated name"))
        onView(allOf(withText(R.string.folder_rename), isAssignableFrom(android.widget.Button::class.java)))
            .perform(click())

        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Updated name"))))
    }

    @Test
    fun deletingAFolder_keepsItsNotesButClearsTheirFolder() {
        val folderId = runBlocking { folderRepository.insert("Temporary") }
        val noteId = runBlocking {
            noteRepository.insert(Note(title = "Filed note", body = "", folderId = folderId))
        }

        onView(withId(R.id.deleteButton)).perform(click())
        onView(withText(R.string.menu_delete)).perform(click())

        onView(withId(R.id.emptyView)).check(matches(isDisplayed()))
        val stillThere = runBlocking { noteRepository.getById(noteId) }
        assertEquals(0L, stillThere?.folderId)
    }
}
