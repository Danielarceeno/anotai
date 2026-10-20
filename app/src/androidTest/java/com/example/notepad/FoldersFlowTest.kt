package com.example.notepad

import androidx.test.core.app.ActivityScenario
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
import org.junit.Test
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
 * The rename/delete tests seed their starting folder (and, for delete, a note filed under it)
 * *before* launching [FoldersActivity] via a plain [ActivityScenario.launch] call, not
 * [androidx.test.ext.junit.rules.ActivityScenarioRule]: [FoldersActivity]'s `onCreate` subscribes
 * to `FolderRepository.observeAll()` immediately, so inserting *after* that subscription races
 * Room's background invalidation-tracker requery against the very next Espresso assertion, which
 * doesn't wait for it -- caught on a real CI run as an `AmbiguousViewMatcherException` (two
 * `deleteButton` views, one of them a stale row Espresso's own idle-sync hadn't yet replaced).
 * Seeding first means the Activity's very first emission already has the right data, the same
 * fix [CalendarDayDialogShowsNotesTest] already uses for an identical reason. `creatingAFolder`
 * doesn't need this: nothing exists yet to race, since the folder itself is created through a
 * genuine Espresso-driven UI interaction after the Activity is already up.
 *
 * Each launch is wrapped in [ActivityScenario.use] -- a bare, unstored [ActivityScenario.launch]
 * call never gets `.close()`'d, leaking that Activity instance into later tests in the same
 * instrumentation process (caught on a real CI run: [SettingsThemeTest]'s theme change tried to
 * `recreate()` every still-tracked AppCompat activity, including these leaked ones, and that hit
 * one in a bad enough state to throw off the main thread).
 */
@RunWith(AndroidJUnit4::class)
class FoldersFlowTest {

    private lateinit var noteRepository: NoteRepository
    private lateinit var folderRepository: FolderRepository

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        noteRepository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        folderRepository = FolderRepository(db.folderDao(), db.noteDao())
    }

    @Test
    fun creatingAFolder_showsUpInTheList() {
        ActivityScenario.launch(FoldersActivity::class.java).use {
            onView(withId(R.id.addFolderButton)).perform(click())
            onView(withId(R.id.textInput)).perform(typeText("Groceries"))
            onView(allOf(withText(R.string.folder_add), isAssignableFrom(android.widget.Button::class.java)))
                .perform(click())

            onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Groceries"))))
        }
    }

    @Test
    fun renamingAFolder_updatesItsRow() {
        runBlocking { folderRepository.insert("Old name") }
        ActivityScenario.launch(FoldersActivity::class.java).use {
            onView(withId(R.id.renameButton)).perform(click())
            onView(withId(R.id.textInput)).perform(clearText(), typeText("Updated name"))
            onView(allOf(withText(R.string.folder_rename), isAssignableFrom(android.widget.Button::class.java)))
                .perform(click())

            onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Updated name"))))
        }
    }

    @Test
    fun deletingAFolder_keepsItsNotesButClearsTheirFolder() {
        val folderId = runBlocking { folderRepository.insert("Temporary") }
        val noteId = runBlocking {
            noteRepository.insert(Note(title = "Filed note", body = "", folderId = folderId))
        }
        ActivityScenario.launch(FoldersActivity::class.java).use {
            onView(withId(R.id.deleteButton)).perform(click())
            onView(withText(R.string.menu_delete)).perform(click())

            onView(withId(R.id.emptyView)).check(matches(isDisplayed()))
        }
        val stillThere = runBlocking { noteRepository.getById(noteId) }
        assertEquals(0L, stillThere?.folderId)
    }
}
