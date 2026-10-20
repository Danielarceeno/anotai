package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.notepad.data.BackupHelper
import com.example.notepad.data.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [BackupHelper] had no test coverage at all before this -- [com.example.notepad.data.drive
 * .DriveBackupHelperTest] only covers the merge logic downstream of it. Runs as an
 * instrumented test (not a plain JUnit unit test) because it exercises the real on-device
 * org.json implementation, not a host-JVM stub.
 */
@RunWith(AndroidJUnit4::class)
class BackupJsonRoundTripTest {

    @Test
    fun notesSurviveAPlainJsonRoundTrip() {
        val notes = listOf(
            Note(id = 1, title = "First", body = "Body one", colorIndex = 2, pinned = true, modifiedDate = 111L),
            Note(id = 2, title = "Second", body = "Body two", type = Note.TYPE_CHECKLIST, modifiedDate = 222L)
        )

        val json = BackupHelper.toJson(notes)
        val restored = BackupHelper.fromJson(json)

        assertEquals(notes.map { it.title }, restored?.map { it.title })
        assertEquals(notes.map { it.body }, restored?.map { it.body })
        assertEquals(notes.map { it.uuid }, restored?.map { it.uuid })
        assertEquals(notes.map { it.pinned }, restored?.map { it.pinned })
    }

    @Test
    fun notesAndTombstonesSurviveTheDriveFormatRoundTrip() {
        val notes = listOf(Note(id = 1, title = "Synced note", body = "", modifiedDate = 50L))
        val tombstones = mapOf("some-deleted-uuid" to 999L)

        val json = BackupHelper.toJsonWithTombstones(notes, tombstones)
        val restored = BackupHelper.fromJsonWithTombstones(json)

        assertEquals(1, restored?.notes?.size)
        assertEquals("Synced note", restored?.notes?.single()?.title)
        assertEquals(tombstones, restored?.tombstones)
    }

    @Test
    fun aPreExistingPlainArrayBackup_stillParsesWithNoTombstones() {
        // A Drive backup file written before tombstone syncing was added was a bare notes array.
        val legacyJson = BackupHelper.toJson(listOf(Note(id = 1, title = "Old backup note", body = "")))

        val restored = BackupHelper.fromJsonWithTombstones(legacyJson)

        assertEquals(1, restored?.notes?.size)
        assertEquals(emptyMap<String, Long>(), restored?.tombstones)
    }

    @Test
    fun garbageInput_returnsNullRatherThanThrowing() {
        assertNull(BackupHelper.fromJson("not json at all"))
        assertNull(BackupHelper.fromJsonWithTombstones("not json at all"))
    }
}
