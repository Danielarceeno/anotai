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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [SearchFtsSubstringMatchTest] already covers mid-word substring matching. This widens FTS5
 * trigram search coverage per the Fase 2 mission: emoji, accented characters (and case),
 * 1-2 character terms (below the trigram tokenizer's own 3-character minimum -- this checks the
 * search still returns *correct* results for them, not that it's accelerated by the index), and
 * a very long query string.
 */
@RunWith(AndroidJUnit4::class)
class SearchFtsEdgeCasesTest {

    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        runBlocking {
            repository.insert(Note(title = "Grocery run 😀🛒", body = "buy milk"))
            repository.insert(Note(title = "Café com Açúcar", body = "receita de bolo"))
            repository.insert(Note(title = "Unrelated note", body = "nothing to see here"))
        }
    }

    @Test
    fun emojiInTitle_isSearchable() {
        val titles = awaitSearch("🛒")
        assertTrue("expected the emoji to match its own note, got: $titles", titles.contains("Grocery run 😀🛒"))
    }

    @Test
    fun accentedQuery_matchesTheSameAccentedTitle() {
        val titles = awaitSearch("Açúcar")
        assertTrue(titles.contains("Café com Açúcar"))
    }

    @Test
    fun unaccentedQuery_doesNotMatchAnAccentedTitle() {
        // Documents current behavior: this is a plain substring match (see EVIDENCE.md), not an
        // accent-folding one -- "Acucar" (no cedilla/accents) must not match "Açúcar".
        val titles = awaitSearch("Acucar")
        assertFalse(titles.contains("Café com Açúcar"))
    }

    @Test
    fun caseInsensitiveMatch_worksForAsciiButNotForAccentedLetters() {
        // Plain ASCII case-folding works (SQLite's LIKE default)...
        assertTrue(awaitSearch("café").contains("Café com Açúcar"))
        // ...but SQLite's default LIKE only case-folds ASCII, not accented Unicode letters, so
        // uppercasing the accented letters themselves (Ç/Ú) breaks the match. This is a real,
        // current limitation (no ICU extension loaded), not a bug in this app's own code --
        // pinned here so a future change to the search query/collation is a deliberate one.
        assertFalse(awaitSearch("AÇÚCAR").contains("Café com Açúcar"))
    }

    @Test
    fun twoCharacterQuery_belowTrigramMinimum_stillMatchesCorrectly() {
        // "ca" is below the trigram tokenizer's 3-character minimum, but the search must still
        // return the right (and only the right) results, however the query planner executes it.
        val titles = awaitSearch("ca")
        assertTrue(titles.contains("Café com Açúcar"))
        assertFalse(titles.contains("Grocery run 😀🛒"))
        assertFalse(titles.contains("Unrelated note"))
    }

    @Test
    fun oneCharacterQuery_stillMatchesCorrectly() {
        val titles = awaitSearch("é")
        assertTrue(titles.contains("Café com Açúcar"))
    }

    @Test
    fun veryLongQuery_doesNotCrash_andFindsNothing() {
        val titles = awaitSearch("x".repeat(2000))
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
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            liveData.observeForever(observer)
        }
        assertTrue("search LiveData never emitted", latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
