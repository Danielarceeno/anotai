package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.BackupHelper
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
 * Per the Fase 5 mission: [DriveBackupHelper.syncNow] end-to-end against a real Room database
 * (not just [DriveBackupHelper.mergeByUuid]'s pure logic, already covered by
 * `DriveBackupHelperTest`'s unit tests) -- first sync, a no-op resync, a remote conflict actually
 * landing in the local table, a cross-device tombstone actually trashing a local row, and a
 * remote-side failure (corrupted file / network error, indistinguishable from here) propagating
 * instead of silently corrupting local state.
 */
@RunWith(AndroidJUnit4::class)
class DriveSyncNowIntegrationTest {

    private lateinit var repository: NoteRepository
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
    }

    private class FakeStore(
        private var remote: BackupHelper.BackupData?,
        private val onBackup: (List<Note>, Map<String, Long>) -> Unit = { _, _ -> }
    ) : DriveBackupHelper.DriveRemoteStore {
        var backedUp: List<Note>? = null
        override suspend fun restore(): BackupHelper.BackupData? = remote
        override suspend fun backup(notes: List<Note>, tombstones: Map<String, Long>) {
            backedUp = notes
            onBackup(notes, tombstones)
        }
    }

    private class ThrowingStore(private val failOnRestore: Boolean) : DriveBackupHelper.DriveRemoteStore {
        override suspend fun restore(): BackupHelper.BackupData? {
            if (failOnRestore) throw java.io.IOException("simulated corrupted/unreachable remote")
            return null
        }
        override suspend fun backup(notes: List<Note>, tombstones: Map<String, Long>) {
            throw java.io.IOException("simulated network failure mid-backup")
        }
    }

    @Test
    fun firstSync_withNoRemoteBackupYet_justUploadsWhatsLocal() {
        runBlocking { repository.insert(Note(title = "Local only so far", body = "")) }
        val store = FakeStore(remote = null)

        val result = runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }

        assertEquals(false, result.hadRemoteBackup)
        assertEquals(1, store.backedUp?.size)
        assertEquals("Local only so far", store.backedUp?.single()?.title)
    }

    @Test
    fun syncingTwiceWithNoChanges_isIdempotent_noDuplicatesOrExtraRows() {
        val id = runBlocking { repository.insert(Note(title = "Steady note", body = "")) }
        val existing = runBlocking { repository.getById(id)!! }
        val store = FakeStore(remote = BackupHelper.BackupData(listOf(existing), emptyMap()))

        runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }
        runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }

        val all = runBlocking { repository.getAllForExport() }.filter { it.title == "Steady note" }
        assertEquals(1, all.size)
    }

    @Test
    fun remoteConflict_withANewerRemoteCopy_updatesTheLocalRowForReal() {
        val id = runBlocking { repository.insert(Note(title = "Old local title", body = "", modifiedDate = 100L)) }
        val local = runBlocking { repository.getById(id)!! }
        val newerRemote = local.copy(id = 999L, title = "Newer remote title", modifiedDate = 500L)
        val store = FakeStore(remote = BackupHelper.BackupData(listOf(newerRemote), emptyMap()))

        runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }

        val reloaded = runBlocking { repository.getById(id) }
        assertEquals("Newer remote title", reloaded?.title)
        // Same local row, not a second one inserted alongside it.
        val allWithThatUuid = runBlocking { repository.getAllForExport() }.filter { it.uuid == local.uuid }
        assertEquals(1, allWithThatUuid.size)
    }

    @Test
    fun crossDeviceTombstone_actuallyTrashesTheLocalRow() {
        val id = runBlocking { repository.insert(Note(title = "Deleted on another device", body = "", modifiedDate = 100L)) }
        val local = runBlocking { repository.getById(id)!! }
        // Not present in remote notes at all, but its uuid has a tombstone newer than this
        // device's own copy -- another device deleted it and already synced without it.
        val store = FakeStore(remote = BackupHelper.BackupData(emptyList(), mapOf(local.uuid to 200L)))

        runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }

        // trashLocally flips activeState, so it drops out of getAllForExport (active-only).
        val stillActive = runBlocking { repository.getAllForExport() }.any { it.uuid == local.uuid }
        assertTrue("the note must no longer be active after a newer cross-device tombstone", !stillActive)
    }

    @Test
    fun aFailingRemoteRestore_propagatesInsteadOfSilentlyLosingLocalData() {
        runBlocking { repository.insert(Note(title = "Must survive a failed sync", body = "")) }
        val store = ThrowingStore(failOnRestore = true)

        var threw = false
        try {
            runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }
        } catch (e: java.io.IOException) {
            threw = true
        }

        assertTrue("syncNow must propagate a remote failure, not swallow it", threw)
        val stillThere = runBlocking { repository.getAllForExport() }.any { it.title == "Must survive a failed sync" }
        assertTrue("local data must be untouched after a failed remote restore", stillThere)
    }

    @Test
    fun aFailingRemoteBackup_afterASuccessfulRestore_stillPropagates() {
        val store = ThrowingStore(failOnRestore = false)

        var threw = false
        try {
            runBlocking { DriveBackupHelper.syncNow(context, repository, remoteStore = store) }
        } catch (e: java.io.IOException) {
            threw = true
        }

        assertTrue("syncNow must propagate a failure uploading the merged backup too", threw)
    }
}
