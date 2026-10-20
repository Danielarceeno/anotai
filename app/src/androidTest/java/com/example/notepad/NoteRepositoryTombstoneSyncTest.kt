package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.drive.DriveBackupHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration-level regression test for the Drive tombstone-sync fix (see
 * DriveBackupHelperTest for the pure-merge-logic unit tests of the same scenario): a note still
 * active in this device's real Room database, with a tombstone that only exists because another
 * device's backup carried it in, must actually end up trashed in the database once
 * [NoteRepository.mergeTombstones] + [DriveBackupHelper.mergeByUuid] + [NoteRepository
 * .trashLocally] run against it -- not just correct on paper.
 */
@RunWith(AndroidJUnit4::class)
class NoteRepositoryTombstoneSyncTest {

    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
    }

    @Test
    fun aRemoteTombstone_trashesAStillActiveLocalNote() = runBlocking {
        val noteId = repository.insert(Note(title = "Deleted on another device", body = "", modifiedDate = 100L))

        // Simulates restoring a Drive backup whose tombstones say this uuid was deleted
        // elsewhere, after this device's own copy was last edited.
        val note = repository.getById(noteId)!!
        val remoteTombstones = mapOf(note.uuid to 200L)

        val mergedTombstones = repository.mergeTombstones(remoteTombstones)
        assertEquals(200L, mergedTombstones[note.uuid])
        // Persisted locally too, not just returned -- a later merge (e.g. from a third device)
        // must still see it even if this particular remote backup is never seen again.
        assertEquals(200L, repository.getTombstones()[note.uuid])

        val local = repository.getAllForExport()
        val result = DriveBackupHelper.mergeByUuid(local, remote = emptyList(), mergedTombstones)
        assertTrue(result.notes.none { it.uuid == note.uuid })
        assertEquals(listOf(noteId), result.locallyDeletedIds)

        result.locallyDeletedIds.forEach { repository.trashLocally(it) }

        val stillActive = repository.getAllForExport()
        assertTrue("note should have been trashed locally", stillActive.none { it.id == noteId })
    }

    @Test
    fun aLocalEditAfterTheRemoteDeletion_isKeptAndNotTrashed() = runBlocking {
        val noteId = repository.insert(Note(title = "Edited after deletion", body = "", modifiedDate = 500L))
        val note = repository.getById(noteId)!!
        val remoteTombstones = mapOf(note.uuid to 100L) // older than this note's own edit

        val mergedTombstones = repository.mergeTombstones(remoteTombstones)
        val local = repository.getAllForExport()
        val result = DriveBackupHelper.mergeByUuid(local, remote = emptyList(), mergedTombstones)

        assertTrue(result.notes.any { it.uuid == note.uuid })
        assertTrue(result.locallyDeletedIds.isEmpty())
    }
}
