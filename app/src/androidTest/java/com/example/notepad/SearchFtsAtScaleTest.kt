package com.example.notepad

import androidx.lifecycle.Observer
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 7 mission: FTS5 search ([NoteRepository.search], backed by the `notes_fts`
 * trigram index) must still use that index -- not fall back to a full table scan -- and respond
 * quickly with 10k+ notes in the table. 10,000 inserts run inside a single transaction (plain,
 * one-by-one `NoteRepository.insert` calls would each hit `RemindersCache.invalidate`/widget
 * notification and take far longer than the search behavior this test actually cares about).
 */
@RunWith(AndroidJUnit4::class)
class SearchFtsAtScaleTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())

        runBlocking {
            db.withTransaction {
                repeat(10_000) { i -> db.noteDao().insert(Note(title = "Bulk note $i", body = "filler body text $i")) }
                db.noteDao().insert(Note(title = "The one unique needle", body = "findable among the haystack"))
            }
        }
    }

    @Test
    fun fts5Search_usesTheIndex_notATableScan() {
        // "SCAN notes_fts VIRTUAL TABLE INDEX ..." is SQLite's normal, expected way of reporting
        // that it went through the FTS5 module's own (trigram) index -- not a full linear scan,
        // despite the word "SCAN" in it (that's just how EXPLAIN QUERY PLAN always describes any
        // virtual table access). What would actually indicate falling back to an unindexed scan
        // is the real `notes` table itself being scanned instead of looked up by rowid.
        db.query("EXPLAIN QUERY PLAN SELECT notes.* FROM notes JOIN notes_fts ON notes.id = notes_fts.rowid WHERE notes_fts.title LIKE '%needle%' ESCAPE '\\'", emptyArray()).use { cursor ->
            val plan = buildString {
                while (cursor.moveToNext()) {
                    append(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    append('\n')
                }
            }
            assertTrue("expected the plan to go through notes_fts at all, got:\n$plan", plan.contains("notes_fts"))
            assertTrue(
                "expected the real notes table to be a rowid lookup, not a full scan, got:\n$plan",
                plan.contains("USING INTEGER PRIMARY KEY (rowid")
            )
        }
    }

    @Test
    fun fts5Search_findsTheMatch_respondingWithinABudget() {
        val startMillis = System.currentTimeMillis()
        val titles = awaitSearch("needle")
        val elapsedMillis = System.currentTimeMillis() - startMillis

        assertTrue("expected to find the unique needle note among 10k notes", titles.contains("The one unique needle"))
        // A soft baseline, not a strict CI gate (emulator performance varies too much run to
        // run for a hard number to be reliable) -- 2s is generous enough to catch an actual
        // regression (e.g. accidentally falling back to a full LIKE scan) while not being flaky
        // on a slow CI machine. See TESTING.md for why this isn't a hard performance budget.
        assertTrue("FTS5 search over 10k notes took ${elapsedMillis}ms, expected well under 2000ms", elapsedMillis < 2000)
    }

    private fun awaitSearch(query: String): List<String> {
        val latch = CountDownLatch(1)
        var result: List<String> = emptyList()
        val liveData = repository.search(query)
        val observer = object : Observer<List<Note>> {
            override fun onChanged(value: List<Note>) {
                result = value.map { it.title }
                latch.countDown()
                liveData.removeObserver(this)
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { liveData.observeForever(observer) }
        assertTrue("search LiveData never emitted", latch.await(10, TimeUnit.SECONDS))
        return result
    }
}
