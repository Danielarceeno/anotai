package com.example.notepad

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.TrashActivity
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TrashActivity had no automated coverage before this: restoring a note must bring it back
 * active (and clear its tombstone, so a later Drive merge doesn't re-trash it -- see
 * [NoteRepository.restore]'s doc), and "Delete forever" must actually remove the row, not just
 * hide it from this list.
 *
 * Both confirmation dialogs set their title and positive button to the *same* string resource
 * ("Delete forever" is both), so a plain `withText(...)` match would be ambiguous -- same fix as
 * [FoldersFlowTest].
 *
 * Each test seeds its own trashed note *before* launching [TrashActivity] via a plain
 * [ActivityScenario.launch] call, not [androidx.test.ext.junit.rules.ActivityScenarioRule]:
 * [TrashActivity]'s `onCreate` subscribes to `observeTrashed()` immediately, so inserting+trashing
 * *after* that subscription races Room's background invalidation-tracker requery against the very
 * next Espresso assertion, which doesn't wait for it -- caught on a real CI run as the expected
 * note's row simply not being found at all. Seeding first means the Activity's very first
 * emission already has the right data, the same fix [FoldersFlowTest]/[CalendarDayDialogShowsNotesTest]
 * use for the identical reason.
 *
 * Each launch is wrapped in [ActivityScenario.use] -- a bare, unstored [ActivityScenario.launch]
 * call never gets `.close()`'d, leaking that Activity instance into later tests in the same
 * instrumentation process (see [FoldersFlowTest]'s doc comment for the CI failure this caused).
 */
@RunWith(AndroidJUnit4::class)
class TrashFlowTest {

    private lateinit var repository: NoteRepository

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
    }

    @Test
    fun restoringATrashedNote_makesItActiveAgainAndClearsItsTombstone() {
        val noteId = runBlocking {
            val id = repository.insert(Note(title = "Trashed note", body = ""))
            repository.delete(id)
            id
        }
        ActivityScenario.launch(TrashActivity::class.java).use {
            onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Trashed note"))))
            onView(withId(R.id.restoreButton)).perform(click())
        }

        val restored = awaitUntil { runBlocking { repository.getById(noteId) }?.takeIf { it.activeState == Note.ACTIVE } }
        assertEquals(Note.ACTIVE, restored?.activeState)
        val tombstones = runBlocking { repository.getTombstones() }
        assertNull("restoring should clear this note's tombstone", tombstones[restored?.uuid])
    }

    @Test
    fun deletingForever_actuallyRemovesTheRow() {
        val noteId = runBlocking {
            val id = repository.insert(Note(title = "Gone for good", body = ""))
            repository.delete(id)
            id
        }
        ActivityScenario.launch(TrashActivity::class.java).use {
            onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Gone for good"))))
            onView(withId(R.id.deleteForeverButton)).perform(click())
            onView(allOf(withText(R.string.trash_delete_forever), isAssignableFrom(android.widget.Button::class.java)))
                .perform(click())
        }

        val deadline = System.currentTimeMillis() + 5000
        var stillExists = true
        while (stillExists && System.currentTimeMillis() < deadline) {
            stillExists = runBlocking { repository.getById(noteId) } != null
            if (stillExists) Thread.sleep(100)
        }
        assertNull("the note should no longer exist at all after Delete forever", runBlocking { repository.getById(noteId) })
    }

    private fun <T> awaitUntil(timeoutMillis: Long = 5000, poll: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var result = poll()
        while (result == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            result = poll()
        }
        return result
    }
}
