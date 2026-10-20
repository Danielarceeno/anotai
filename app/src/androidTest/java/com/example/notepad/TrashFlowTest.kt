package com.example.notepad

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
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
 * [TestPrereqs.resetStateRule] (not a plain `@Before`) is chained to run *before*
 * [ActivityScenarioRule] launches the Activity, for the same reason [FoldersFlowTest] needs it --
 * see that class's doc comment (caught there as a real `AmbiguousViewMatcherException` on a CI
 * run; applied here too since this test has the identical structure/risk).
 */
@RunWith(AndroidJUnit4::class)
class TrashFlowTest {

    private val activityRule = ActivityScenarioRule(TrashActivity::class.java)

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(TestPrereqs.resetStateRule()).around(activityRule)

    private lateinit var repository: NoteRepository

    @Before
    fun setUpRepository() {
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

        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Trashed note"))))
        onView(withId(R.id.restoreButton)).perform(click())

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

        onView(withId(R.id.recyclerView)).check(matches(hasDescendant(withText("Gone for good"))))
        onView(withId(R.id.deleteForeverButton)).perform(click())
        onView(allOf(withText(R.string.trash_delete_forever), isAssignableFrom(android.widget.Button::class.java)))
            .perform(click())

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
