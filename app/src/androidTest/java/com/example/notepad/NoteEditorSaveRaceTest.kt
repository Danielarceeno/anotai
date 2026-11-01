package com.example.notepad

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.ui.NoteEditorActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression guard for the historical duplicate/lost-note bug `TESTING.md`'s "Known gaps"
 * section flags as untested: `saveIfNeeded()`'s background save runs on
 * [com.example.notepad.NotepadApplication.applicationScope], deliberately *not* this Activity's
 * own `lifecycleScope`, specifically so the Activity being destroyed mid-save (back press, home
 * button, app switch) can't cancel the write. This forces exactly that race with a testability
 * seam ([NoteEditorActivity.saveRaceHookForTesting]) and proves the note lands exactly once --
 * confirmed as a real regression test: temporarily switching that `applicationScope.launch` to
 * `lifecycleScope.launch` makes this test fail (0 notes saved instead of 1), then restoring it
 * makes it pass again (see the commit message for that before/after).
 */
@RunWith(AndroidJUnit4::class)
class NoteEditorSaveRaceTest {

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @After
    fun clearHook() {
        NoteEditorActivity.saveRaceHookForTesting = null
    }

    @Test
    fun savingSurvives_activityDestroyedWhileTheWriteIsStillInFlight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = AppDatabase.getInstance(context).noteDao()
        val title = "Race test note ${System.nanoTime()}"

        val reachedHook = CompletableDeferred<Unit>()
        val releaseHook = CompletableDeferred<Unit>()
        NoteEditorActivity.saveRaceHookForTesting = {
            reachedHook.complete(Unit)
            releaseHook.await()
        }

        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
        }
        val scenario = ActivityScenario.launch<NoteEditorActivity>(intent)
        try {
            onView(withId(R.id.titleInput)).perform(typeText(title), closeSoftKeyboard())
            // NoteEditorActivity.onPause() always calls saveIfNeeded(), finishing or not -- no
            // need to actually finish()/press back (which, with no other Activity in this test's
            // task, throws Espresso's own NoActivityResumedException and tears down the whole
            // app process, taking later tests in the same instrumentation run down with it).
            // Moving the ActivityScenario's own lifecycle state down triggers onPause() the same
            // way, entirely through ActivityScenario's API instead of a real back-stack change.
            scenario.moveToState(Lifecycle.State.CREATED)

            runBlocking { withTimeout(5000) { reachedHook.await() } }

            // The coroutine is now parked mid-save, on applicationScope. Force the Activity all
            // the way through onStop/onDestroy while it's still parked there -- if the save were
            // on lifecycleScope instead, this would cancel it.
            scenario.moveToState(Lifecycle.State.DESTROYED)

            releaseHook.complete(Unit)

            val deadline = System.currentTimeMillis() + 10000
            var matches: List<Note> = emptyList()
            while (System.currentTimeMillis() < deadline) {
                matches = runBlocking { dao.getAllForExport() }.filter { it.title == title }
                if (matches.isNotEmpty()) break
                runBlocking { kotlinx.coroutines.delay(100) }
            }
            org.junit.Assert.assertEquals(
                "The note must be saved exactly once despite the Activity being destroyed mid-save",
                1, matches.size
            )
        } finally {
            // Already DESTROYED above; close() on top of that is a documented no-op, not a double-destroy.
            scenario.close()
        }
    }
}
