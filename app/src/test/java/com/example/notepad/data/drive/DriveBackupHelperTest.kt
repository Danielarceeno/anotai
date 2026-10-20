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
        val result = DriveBackupHelper.mergeByUuid(local, remote = emptyList())
        assertEquals(local, result.notes)
        assertTrue(result.locallyDeletedIds.isEmpty())
    }

    @Test
    fun `a note only present remotely is added with no local id yet`() {
        val remote = listOf(note("a", id = 99, modifiedDate = 1))
        val result = DriveBackupHelper.mergeByUuid(local = emptyList(), remote)
        assertEquals(1, result.notes.size)
        assertEquals(0L, result.notes.single().id) // caller inserts this as a brand-new local row
        assertEquals("a", result.notes.single().uuid)
    }

    @Test
    fun `the newer copy wins when the same uuid exists on both sides`() {
        val local = listOf(note("a", id = 5, modifiedDate = 100, title = "old local edit"))
        val remote = listOf(note("a", id = 777, modifiedDate = 200, title = "newer remote edit"))

        val result = DriveBackupHelper.mergeByUuid(local, remote)

        assertEquals(1, result.notes.size)
        val winner = result.notes.single()
        assertEquals("newer remote edit", winner.title)
        // The remote copy's own (different device's) id is never used locally -- it's swapped
        // for the existing local row's id so applying this is an update, not a duplicate insert.
        assertEquals(5L, winner.id)
    }

    @Test
    fun `the local copy wins when it's the newer one`() {
        val local = listOf(note("a", id = 5, modifiedDate = 300, title = "newer local edit"))
        val remote = listOf(note("a", id = 777, modifiedDate = 200, title = "older remote edit"))

        val result = DriveBackupHelper.mergeByUuid(local, remote)

        assertEquals("newer local edit", result.notes.single().title)
        assertEquals(5L, result.notes.single().id)
    }

    @Test
    fun `notes unique to each side are unioned, not dropped`() {
        val local = listOf(note("local-only", id = 1), note("shared", id = 2, modifiedDate = 1))
        val remote = listOf(note("remote-only", id = 50), note("shared", id = 51, modifiedDate = 1))

        val result = DriveBackupHelper.mergeByUuid(local, remote)

        val uuids = result.notes.map { it.uuid }.toSet()
        assertEquals(setOf("local-only", "remote-only", "shared"), uuids)
        assertTrue(result.notes.size == 3)
    }

    // --- Tombstones: a note trashed locally doesn't come back from the next backup/restore. ---
    // `local` only ever contains active notes (see NoteRepository.getAllForExport), so without a
    // tombstone a just-deleted note's uuid looked exactly like a brand new remote-only note.

    @Test
    fun `a tombstoned note is not resurrected from the remote backup`() {
        val remote = listOf(note("deleted-note", id = 99, modifiedDate = 100))
        val tombstones = mapOf("deleted-note" to 200L) // deleted after that remote copy's edit

        val result = DriveBackupHelper.mergeByUuid(local = emptyList(), remote, tombstones)

        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `a remote edit made after the local deletion still wins`() {
        // Someone else edited the note on another device after this device deleted it --
        // that's a real, newer change the user doesn't know happened, so it should come back,
        // same as any other newer-edit-wins conflict.
        val remote = listOf(note("edited-after-deletion", id = 99, modifiedDate = 500))
        val tombstones = mapOf("edited-after-deletion" to 100L)

        val result = DriveBackupHelper.mergeByUuid(local = emptyList(), remote, tombstones)

        assertEquals(1, result.notes.size)
        assertEquals("edited-after-deletion", result.notes.single().uuid)
    }

    @Test
    fun `a tombstone for an unrelated uuid doesn't affect anything`() {
        val remote = listOf(note("still-fine", id = 99, modifiedDate = 100))
        val tombstones = mapOf("some-other-note" to 999L)

        val result = DriveBackupHelper.mergeByUuid(local = emptyList(), remote, tombstones)

        assertEquals(1, result.notes.size)
        assertEquals("still-fine", result.notes.single().uuid)
    }

    // --- Cross-device deletion: a note deleted on another device, that this device never heard
    // about until now, was still sitting active in `local` (this device's own tombstone table
    // never got one -- it never ran the delete itself). A tombstone synced in from the remote
    // backup (see NoteRepository.mergeTombstones) must still drop it here, and tell the caller
    // to actually trash it locally via `locallyDeletedIds` -- that's the fix for the README's
    // former "Known limitations" entry about this exact scenario.

    @Test
    fun `a note still active locally is dropped and flagged for local trashing when another device's tombstone covers it`() {
        val local = listOf(note("deleted-elsewhere", id = 7, modifiedDate = 100))
        // Not present in remote either: the device that deleted it already uploaded a backup
        // without it, this device just hasn't synced since.
        val tombstones = mapOf("deleted-elsewhere" to 200L)

        val result = DriveBackupHelper.mergeByUuid(local, remote = emptyList(), tombstones)

        assertTrue(result.notes.isEmpty())
        assertEquals(listOf(7L), result.locallyDeletedIds)
    }

    @Test
    fun `a local edit made after another device's deletion still wins and isn't flagged`() {
        val local = listOf(note("edited-after-deletion", id = 7, modifiedDate = 500))
        val tombstones = mapOf("edited-after-deletion" to 100L)

        val result = DriveBackupHelper.mergeByUuid(local, remote = emptyList(), tombstones)

        assertEquals(1, result.notes.size)
        assertTrue(result.locallyDeletedIds.isEmpty())
    }

    // --- A remote backup file with a duplicate entry for the same uuid (e.g. written by an
    // older, pre-merge version of the app) must not duplicate that note on every device that
    // restores from it -- reported as "connecting Google Drive pulled everything duplicated".

    @Test
    fun `a remote-only note duplicated within the backup file is only added once`() {
        val remote = listOf(
            note("dup", id = 10, modifiedDate = 100, title = "first copy"),
            note("dup", id = 20, modifiedDate = 100, title = "second copy")
        )

        val result = DriveBackupHelper.mergeByUuid(local = emptyList(), remote)

        assertEquals(1, result.notes.size)
        assertEquals("dup", result.notes.single().uuid)
    }
}
