package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 3 mission: the `notes_fts` shadow index (see `NoteDao`'s own doc -- it's a raw
 * virtual table kept in sync by triggers, not a Room-managed entity) must stay consistent with
 * the real `notes` table through insert/edit/trash/restore/delete, and a heavy sequence of those
 * must still leave the database passing `PRAGMA integrity_check`.
 */
@RunWith(AndroidJUnit4::class)
class NotesFtsConsistencyTest {

    private lateinit var repository: NoteRepository
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
    }

    private fun ftsMatches(term: String): List<Long> {
        val cursor = db.query("SELECT rowid FROM notes_fts WHERE notes_fts MATCH ?", arrayOf(term))
        val ids = mutableListOf<Long>()
        cursor.use { while (it.moveToNext()) ids.add(it.getLong(0)) }
        return ids
    }

    @Test
    fun insert_isImmediatelyFindableInTheFtsIndex() {
        val id = runBlocking { repository.insert(Note(title = "Findable title", body = "")) }
        assertTrue(ftsMatches("Findable").contains(id))
    }

    @Test
    fun updatingTheTitle_updatesTheFtsIndexToo_andDropsTheOldMatch() {
        val id = runBlocking { repository.insert(Note(title = "Original title", body = "")) }
        runBlocking { repository.update(repository.getById(id)!!.copy(title = "Renamed title")) }

        assertTrue(ftsMatches("Renamed").contains(id))
        assertTrue("the old title must no longer match after being renamed", !ftsMatches("Original").contains(id))
    }

    @Test
    fun trashingAndRestoring_keepsTheFtsIndexInSync() {
        // The notes table itself never actually loses a trashed row (only its activeState
        // flips), so the FTS index must still find it throughout -- only a hard delete should
        // ever remove it from notes_fts.
        val id = runBlocking { repository.insert(Note(title = "Trash cycle title", body = "")) }
        runBlocking { repository.delete(id) }
        assertTrue("a trashed note's row (and its FTS entry) is still physically present", ftsMatches("Trash").contains(id))

        runBlocking { repository.restore(id) }
        assertTrue(ftsMatches("Trash").contains(id))
    }

    @Test
    fun permanentlyDeleting_removesItFromTheFtsIndex() {
        val id = runBlocking { repository.insert(Note(title = "Doomed title", body = "")) }
        runBlocking { repository.delete(id) }
        runBlocking { repository.deleteForever(id) }

        assertTrue("a permanently-deleted note must not linger in the FTS index", ftsMatches("Doomed").isEmpty())
    }

    @Test
    fun integrityCheck_passesAfterAHeavySequenceOfMutations() {
        runBlocking {
            val ids = (1..30).map { i -> repository.insert(Note(title = "Note $i", body = "body $i")) }
            ids.filterIndexed { index, _ -> index % 2 == 0 }.forEach { id ->
                repository.update(repository.getById(id)!!.copy(title = "Updated $id"))
            }
            ids.filterIndexed { index, _ -> index % 3 == 0 }.forEach { repository.delete(it) }
            ids.filterIndexed { index, _ -> index % 5 == 0 }.forEach { repository.restore(it) }
            ids.filterIndexed { index, _ -> index % 7 == 0 }.forEach {
                repository.delete(it)
                repository.deleteForever(it)
            }
        }

        db.query("PRAGMA integrity_check", emptyArray()).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
        }
    }
}
