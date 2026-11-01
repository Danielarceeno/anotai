package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.BackupHelper
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.drive.DriveBackupHelper
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DriveBackupHelper.syncNow]'s `Mutex`, previously verified only "by code reasoning" (see
 * `TESTING.md`'s "Known gaps" and `DEV_LOG.md` Session 6) -- no test actually raced two calls
 * against each other. [DriveBackupHelper.DriveRemoteStore] exists specifically so this can race
 * two real, concurrent `syncNow` calls against a fake, in-memory remote instead of a real Drive
 * account, against a real Room database (so "no duplicate uuid" is checked against the actual
 * table, not a hand-rolled model of it) -- the one piece this couldn't use a virtual-time
 * `runTest` for, since the guarantee being proven is specifically about two coroutines genuinely
 * overlapping on different threads, which a single-threaded virtual scheduler can't exercise.
 */
@RunWith(AndroidJUnit4::class)
class DriveBackupHelperSyncRaceTest {

    private lateinit var repository: NoteRepository

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
    }

    /** A note already on "Drive" that this device never had locally -- simulates a note another
     *  device already backed up. A tiny artificial delay in both [restore]/[backup] (standing in
     *  for real network latency) widens the race window enough that, without the Mutex, both
     *  concurrent [DriveBackupHelper.syncNow] calls reliably observe the same pre-merge state
     *  instead of just happening not to race by luck. */
    private inner class FakeRemoteStore(private val remoteOnlyNote: Note) : DriveBackupHelper.DriveRemoteStore {
        override suspend fun restore(): BackupHelper.BackupData? {
            delay(50)
            return BackupHelper.BackupData(listOf(remoteOnlyNote), emptyMap())
        }

        override suspend fun backup(notes: List<Note>, tombstones: Map<String, Long>) {
            delay(50)
        }
    }

    @Test
    fun twoConcurrentSyncs_neverDuplicateTheSameRemoteNote() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val remoteOnlyNote = Note(title = "Only on Drive so far", body = "", uuid = "shared-uuid-1")
        val fakeStore = FakeRemoteStore(remoteOnlyNote)

        runBlocking {
            val first = async { DriveBackupHelper.syncNow(context, repository, remoteStore = fakeStore) }
            val second = async { DriveBackupHelper.syncNow(context, repository, remoteStore = fakeStore) }
            first.await()
            second.await()
        }

        val matching = runBlocking { repository.getAllForExport() }.filter { it.uuid == "shared-uuid-1" }
        assertEquals(
            "Two syncs racing to pull in the same remote-only note must still land it exactly once",
            1, matching.size
        )
    }
}
