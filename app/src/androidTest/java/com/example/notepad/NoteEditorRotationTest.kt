package com.example.notepad

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.ui.NoteEditorActivity
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 4 mission: a screen rotation (or any other config change) must not lose what the
 * user was typing. This works *not* because any view state is explicitly saved/restored here --
 * [NoteEditorActivity] has no `onSaveInstanceState` override at all -- but because
 * `onPause()` (which a config change triggers before destroying the Activity, same as actually
 * leaving the screen) already calls `saveIfNeeded()` unconditionally. This is the regression test
 * for that behavior actually holding, from the typing side, not just the already-covered "the
 * note eventually lands in the database" side.
 */
@RunWith(AndroidJUnit4::class)
class NoteEditorRotationTest {

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun rotating_whileTypingANewTextNote_keepsTheTitleAndBody_andDoesNotDuplicateIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
        }
        ActivityScenario.launch<NoteEditorActivity>(intent).use { scenario ->
            onView(withId(R.id.titleInput)).perform(typeText("Rotation survives"), closeSoftKeyboard())
            onView(withId(R.id.bodyInput)).perform(typeText("so does this body"), closeSoftKeyboard())

            scenario.recreate()

            // Case-insensitive on the body: auto-capitalization of the first letter (the
            // keyboard's own behavior, unrelated to rotation/duplication) isn't what this is
            // testing for.
            onView(withId(R.id.titleInput)).check(matches(withText("Rotation survives")))
            onView(withId(R.id.bodyInput)).check(matches(withText(org.hamcrest.Matchers.equalToIgnoringCase("so does this body"))))
        }
        // The real check: however many times the Activity paused/resumed/recreated along the
        // way, exactly one row should exist for it afterward.
        val matches = kotlinx.coroutines.runBlocking { db.noteDao().getAllForExport() }.filter { it.title == "Rotation survives" }
        org.junit.Assert.assertEquals(
            "rotating a brand-new, not-yet-identified note must not duplicate it: found ${matches.size} rows",
            1, matches.size
        )
    }

    @Test
    fun rotating_afterAddingAChecklistItem_keepsIt_andDoesNotDuplicateTheNote() {
        // A single item, deliberately: adding a second one right after the first races the add
        // row being recreated (ChecklistAdapter's own doc -- a separate, pre-existing Espresso/
        // RecyclerView timing quirk, unrelated to what this test is actually about) often enough
        // to make this flaky for a reason that has nothing to do with the rotation/duplication
        // behavior under test.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_CHECKLIST)
        }
        ActivityScenario.launch<NoteEditorActivity>(intent).use { scenario ->
            onView(withId(R.id.titleInput)).perform(typeText("Checklist survives rotation"), closeSoftKeyboard())
            // Separate perform() calls (the pattern ChecklistAddRowTest/CreateEditNoteFlowTest
            // already use) rather than one chained typeText(...), pressImeActionButton(): chaining
            // them sometimes fires the IME action before the EditText's last character actually
            // commits, silently truncating the item -- unrelated to rotation/duplication.
            onView(withId(R.id.newItemText)).perform(typeText("the only item"))
            onView(withId(R.id.newItemText)).perform(pressImeActionButton())

            scenario.recreate()
        }
        // The real checks: exactly one row, with the item intact, however many times the
        // Activity paused/resumed/recreated along the way.
        val matches = kotlinx.coroutines.runBlocking { db.noteDao().getAllForExport() }.filter { it.title == "Checklist survives rotation" }
        org.junit.Assert.assertEquals(
            "rotating a brand-new checklist note must not duplicate it: found ${matches.size} rows",
            1, matches.size
        )
        org.junit.Assert.assertTrue(
            "expected the item in: ${matches.single().body}",
            matches.single().body.contains("the only item", ignoreCase = true)
        )
    }

    @Test
    fun recreatingTwice_inARowOnAnAlreadySavedNote_doesNotDuplicateIt() {
        // A brand-new, never-saved note recreated once already covers "typing survives" above;
        // this covers the other historical risk this project has specifically guarded against
        // before (see NoteEditorSaveRaceTest/DEV_LOG) -- recreating an *already-persisted* note
        // repeatedly must never insert a second row for it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val noteId = kotlinx.coroutines.runBlocking {
            com.example.notepad.data.NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
                .insert(Note(title = "Already saved", body = "original body"))
        }
        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        }
        ActivityScenario.launch<NoteEditorActivity>(intent).use { scenario ->
            scenario.recreate()
            scenario.recreate()

            val matches = kotlinx.coroutines.runBlocking { db.noteDao().getAllForExport() }.filter { it.title == "Already saved" }
            org.junit.Assert.assertEquals("recreating an existing note twice must not duplicate it", 1, matches.size)
        }
    }
}
