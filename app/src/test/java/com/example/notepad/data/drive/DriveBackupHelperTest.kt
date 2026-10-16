package com.example.notepad.data.drive

import com.example.notepad.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DriveBackupHelper.mergeByUuid] is what stands between two devices syncing and one of them
 * silently losing notes the other doesn't know about -- see its doc comment and the "Known
 * limitations" note in the README for what it does and doesn't guarantee.
 */
class DriveBackupHelperTest {

    private fun note(uuid: String, id: Long = 0L, modifiedDate: Long = 0L, title: String = uuid) =
        Note(id = id, title = title, body = "", modifiedDate = modifiedDate, uuid = uuid)

    @Test
    fun `a note only present locally is kept as-is`() {
        val local = listOf(note("a", id = 1))
        val merged = DriveBackupHelper.mergeByUuid(local, remote = emptyList())
        assertEquals(local, merged)
    }

    @Test
    fun `a note only present remotely is added with no local id yet`() {
        val remote = listOf(note("a", id = 99, modifiedDate = 1))
        val merged = DriveBackupHelper.mergeByUuid(local = emptyList(), remote)
        assertEquals(1, merged.size)
        assertEquals(0L, merged.single().id) // caller inserts this as a brand-new local row
        assertEquals("a", merged.single().uuid)
    }

    @Test
    fun `the newer copy wins when the same uuid exists on both sides`() {
        val local = listOf(note("a", id = 5, modifiedDate = 100, title = "old local edit"))
        val remote = listOf(note("a", id = 777, modifiedDate = 200, title = "newer remote edit"))

        val merged = DriveBackupHelper.mergeByUuid(local, remote)

        assertEquals(1, merged.size)
        val winner = merged.single()
        assertEquals("newer remote edit", winner.title)
        // The remote copy's own (different device's) id is never used locally -- it's swapped
        // for the existing local row's id so applying this is an update, not a duplicate insert.
        assertEquals(5L, winner.id)
    }

    @Test
    fun `the local copy wins when it's the newer one`() {
        val local = listOf(note("a", id = 5, modifiedDate = 300, title = "newer local edit"))
        val remote = listOf(note("a", id = 777, modifiedDate = 200, title = "older remote edit"))

        val merged = DriveBackupHelper.mergeByUuid(local, remote)

        assertEquals("newer local edit", merged.single().title)
        assertEquals(5L, merged.single().id)
    }

    @Test
    fun `notes unique to each side are unioned, not dropped`() {
        val local = listOf(note("local-only", id = 1), note("shared", id = 2, modifiedDate = 1))
        val remote = listOf(note("remote-only", id = 50), note("shared", id = 51, modifiedDate = 1))

        val merged = DriveBackupHelper.mergeByUuid(local, remote)

        val uuids = merged.map { it.uuid }.toSet()
        assertEquals(setOf("local-only", "remote-only", "shared"), uuids)
        assertTrue(merged.size == 3)
    }

    // --- Tombstones: a note trashed locally doesn't come back from the next backup/restore. ---
    // `local` only ever contains active notes (see NoteRepository.getAllForExport), so without a
    // tombstone a just-deleted note's uuid looked exactly like a brand new remote-only note.

    @Test
    fun `a tombstoned note is not resurrected from the remote backup`() {
        val remote = listOf(note("deleted-note", id = 99, modifiedDate = 100))
        val tombstones = mapOf("deleted-note" to 200L) // deleted after that remote copy's edit

        val merged = DriveBackupHelper.mergeByUuid(local = emptyList(), remote, tombstones)

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `a remote edit made after the local deletion still wins`() {
        // Someone else edited the note on another device after this device deleted it --
        // that's a real, newer change the user doesn't know happened, so it should come back,
        // same as any other newer-edit-wins conflict.
        val remote = listOf(note("edited-after-deletion", id = 99, modifiedDate = 500))
        val tombstones = mapOf("edited-after-deletion" to 100L)

        val merged = DriveBackupHelper.mergeByUuid(local = emptyList(), remote, tombstones)

        assertEquals(1, merged.size)
        assertEquals("edited-after-deletion", merged.single().uuid)
    }

    @Test
    fun `a tombstone for an unrelated uuid doesn't affect anything`() {
        val remote = listOf(note("still-fine", id = 99, modifiedDate = 100))
        val tombstones = mapOf("some-other-note" to 999L)

        val merged = DriveBackupHelper.mergeByUuid(local = emptyList(), remote, tombstones)

        assertEquals(1, merged.size)
        assertEquals("still-fine", merged.single().uuid)
    }
}
