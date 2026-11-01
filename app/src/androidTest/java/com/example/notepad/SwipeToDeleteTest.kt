package com.example.notepad

import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItem
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteDao
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * [MainActivity]'s swipe-to-delete (the `swipeToDeleteHelper` `ItemTouchHelper`), previously
 * untested -- see `TESTING.md`'s "Known gaps". Notes are seeded *before* the Activity launches,
 * via [RuleChain], not from a plain `@Before` -- caught on a real full-suite run (not when this
 * class ran alone): a `@Before` runs after `MainActivity.onCreate` has already subscribed to its
 * `PagingData` flow, and under the extra load of the other ~80 tests running first, that race
 * against Room's own invalidation round-trip sometimes lost -- `actionOnItem` couldn't find a row
 * that hadn't loaded into the adapter yet. Same precaution `CalendarDayDialogShowsNotesTest`/
 * `SearchShowsAllNotesTest` already document for the exact same reason.
 */
@RunWith(AndroidJUnit4::class)
class SwipeToDeleteTest {

    private lateinit var repository: NoteRepository
    private lateinit var dao: NoteDao

    private val seedNotesRule = TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                TestPrereqs.resetAppState()
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val db = AppDatabase.getInstance(context)
                dao = db.noteDao()
                repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
                runBlocking {
                    repository.insert(Note(title = "Swipe me left", body = ""))
                    repository.insert(Note(title = "Swipe me right", body = ""))
                    repository.insert(Note(title = "Leave me alone", body = ""))
                }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val ruleChain: TestRule = RuleChain.outerRule(seedNotesRule).around(ActivityScenarioRule(MainActivity::class.java))

    @Test
    fun swipeLeft_movesTheNoteToTrash_andShowsUndo() {
        onView(withId(R.id.recyclerView)).perform(
            actionOnItem<RecyclerView.ViewHolder>(hasDescendant(withText("Swipe me left")), swipeLeft())
        )

        pollUntilTrue { trashedTitles().contains("Swipe me left") }
        onView(withText(R.string.note_deleted_message)).check(matches(isDisplayed()))
    }

    @Test
    fun swipeRight_alsoMovesTheNoteToTrash() {
        onView(withId(R.id.recyclerView)).perform(
            actionOnItem<RecyclerView.ViewHolder>(hasDescendant(withText("Swipe me right")), swipeRight())
        )

        pollUntilTrue { trashedTitles().contains("Swipe me right") }
    }

    @Test
    fun undoSnackbar_restoresTheSwipedNote() {
        onView(withId(R.id.recyclerView)).perform(
            actionOnItem<RecyclerView.ViewHolder>(hasDescendant(withText("Swipe me left")), swipeLeft())
        )
        pollUntilTrue { trashedTitles().contains("Swipe me left") }

        onView(withText(R.string.action_undo)).perform(click())

        pollUntilTrue { !trashedTitles().contains("Swipe me left") }
        assertTrue("The restored note must be active again", activeTitles().contains("Swipe me left"))
    }

    @Test
    fun swipingIsDisabled_whileInSelectionMode() {
        // Long-press any row to enter selection mode first.
        onView(withId(R.id.recyclerView)).perform(actionOnItemAtPosition<RecyclerView.ViewHolder>(0, longClick()))

        onView(withId(R.id.recyclerView)).perform(
            actionOnItem<RecyclerView.ViewHolder>(hasDescendant(withText("Leave me alone")), swipeLeft())
        )

        // No polling needed for a negative assertion the way the positive ones above need it --
        // there's nothing async to wait for if the swipe never fires onSwiped in the first place.
        assertFalse("A swipe must not trash anything while a bulk selection is in progress", trashedTitles().contains("Leave me alone"))
    }

    private fun trashedTitles(): List<String> = runBlocking { dao.getTrashedForCleanup() }.map { it.title }

    private fun activeTitles(): List<String> = runBlocking { repository.getAllForExport() }.map { it.title }

    private fun pollUntilTrue(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            runBlocking { kotlinx.coroutines.delay(100) }
        }
        assertTrue(
            "Condition never became true within ${timeoutMs}ms " +
                "(active=${activeTitles()}, trashed=${trashedTitles()})",
            condition()
        )
    }
}
