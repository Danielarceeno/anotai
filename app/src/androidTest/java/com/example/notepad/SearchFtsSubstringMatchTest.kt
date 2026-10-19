package com.example.notepad

import androidx.lifecycle.Observer
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
 * [NoteRepository.search] moved from a plain `LIKE` scan onto a trigram-tokenized FTS5 index
 * (`notes_fts`, see [AppDatabase]'s `MIGRATION_12_13`/`createNotesFtsIndex`) for speed, but the
 * trigram tokenizer was specifically chosen so SQLite can still use it to accelerate a `LIKE
 * '%...%'` scan -- unlike FTS5's default tokenizers, which only match whole tokens/prefixes. This
 * is the regression guard for that: a query matching only the *middle* of a word (not a token
 * boundary) must still find it, exactly like the original `LIKE`-based search documented in
 * EVIDENCE.md/README.
 */
@RunWith(AndroidJUnit4::class)
class SearchFtsSubstringMatchTest {

    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        runBlocking {
            repository.insert(Note(title = "Balloon festival", body = "Bring sunscreen"))
            repository.insert(Note(title = "Unrelated note", body = "Nothing to see here"))
        }
    }

    @Test
    fun midWordSubstring_stillMatches() {
        val titles = awaitSearch("loon") // middle of "Balloon", not a token start
        assertTrue("expected a mid-word substring match, got: $titles", titles.contains("Balloon festival"))
    }

    @Test
    fun matchInBody_alsoFound() {
        val titles = awaitSearch("sunscreen")
        assertTrue(titles.contains("Balloon festival"))
    }

    @Test
    fun nonMatchingQuery_returnsNothing() {
        val titles = awaitSearch("xyzabc123")
        assertTrue(titles.isEmpty())
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
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            liveData.observeForever(observer)
        }
        assertTrue("search LiveData never emitted", latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
