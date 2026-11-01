package com.example.notepad.data.drive

import com.example.notepad.data.Note
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based tests for [DriveBackupHelper.mergeByUuid], complementing
 * [DriveBackupHelperTest]'s example-based cases with randomized inputs run many times each --
 * per the Fase 2 test-strategy mission: idempotency, "never duplicates a uuid", "never loses a
 * newer note", and the tombstone-vs-later-edit interaction, instead of only the handful of
 * hand-picked scenarios above.
 */
class MergeByUuidPropertyTest {

    // A small, fixed pool of uuids so runs actually generate overlapping local/remote/tombstone
    // sets often enough to exercise the interesting conflict paths -- real random UUIDs would
    // almost never collide, and the whole point here is testing what happens when they do.
    private val uuidPool = Arb.int(0..7).map { "uuid-$it" }

    private val noteArb: Arb<Note> = Arb.bind(uuidPool, Arb.long(0L..10_000L), Arb.string(0..10)) { uuid, modifiedDate, title ->
        Note(title = title, body = "", uuid = uuid, modifiedDate = modifiedDate, id = 0L)
    }

    private val noteListArb: Arb<List<Note>> = Arb.list(noteArb, 0..12)
        // Real local/remote note lists never have two rows with the same uuid (that would be a
        // local-database bug, not something mergeByUuid itself needs to tolerate on either input
        // side -- only *within a single remote file* is it a known, separately-tested case).
        .map { notes -> notes.distinctBy { it.uuid } }

    @Test
    fun `merging with an empty remote never changes the local set`() {
        runBlocking {
            checkAll(noteListArb) { local ->
                val result = DriveBackupHelper.mergeByUuid(local, remote = emptyList())
                assertEquals(local.map { it.uuid }.toSet(), result.notes.map { it.uuid }.toSet())
                assertTrue(result.locallyDeletedIds.isEmpty())
            }
        }
    }

    @Test
    fun `merging a set with an identical mirror of itself is idempotent`() {
        // "Idempotent" here means: backing up and immediately restoring the exact same data
        // (same uuids, same modifiedDate, remote's id reset to 0 as a real restore would) must
        // not change the note count or duplicate anything, regardless of which side's row
        // happens to "win" the tie.
        runBlocking {
            checkAll(noteListArb) { local ->
                val remote = local.map { it.copy(id = 0L) }
                val result = DriveBackupHelper.mergeByUuid(local, remote)
                assertEquals(local.size, result.notes.size)
                assertEquals(local.map { it.uuid }.toSet(), result.notes.map { it.uuid }.toSet())
            }
        }
    }

    @Test
    fun `the result never has two notes with the same uuid`() {
        runBlocking {
            checkAll(noteListArb, noteListArb) { local, remote ->
                val result = DriveBackupHelper.mergeByUuid(local, remote)
                val uuids = result.notes.map { it.uuid }
                assertEquals("duplicate uuid in merge result: $uuids", uuids.size, uuids.toSet().size)
            }
        }
    }

    @Test
    fun `every uuid present on either side survives, unless a tombstone covers it`() {
        runBlocking {
            checkAll(noteListArb, noteListArb) { local, remote ->
                val result = DriveBackupHelper.mergeByUuid(local, remote) // no tombstones here
                val expectedUuids = (local.map { it.uuid } + remote.map { it.uuid }).toSet()
                assertEquals(expectedUuids, result.notes.map { it.uuid }.toSet())
            }
        }
    }

    @Test
    fun `the winning copy for a shared uuid always has the max modifiedDate of the two`() {
        runBlocking {
            checkAll(noteListArb, noteListArb) { local, remote ->
                val result = DriveBackupHelper.mergeByUuid(local, remote)
                val remoteByUuid = remote.associateBy { it.uuid }
                for (localNote in local) {
                    val remoteNote = remoteByUuid[localNote.uuid] ?: continue
                    val winner = result.notes.firstOrNull { it.uuid == localNote.uuid } ?: continue // tombstoned, not this test
                    val expectedModifiedDate = maxOf(localNote.modifiedDate, remoteNote.modifiedDate)
                    assertEquals(expectedModifiedDate, winner.modifiedDate)
                }
            }
        }
    }

    @Test
    fun `a tombstone drops a uuid only when the deletion is not older than the winning copy`() {
        runBlocking {
            checkAll(noteListArb, noteListArb, Arb.long(0L..10_000L)) { local, remote, deletedAt ->
                val targetUuid = (local + remote).map { it.uuid }.firstOrNull() ?: return@checkAll
                val tombstones = mapOf(targetUuid to deletedAt)

                val result = DriveBackupHelper.mergeByUuid(local, remote, tombstones)
                val localNote = local.firstOrNull { it.uuid == targetUuid }
                val remoteNote = remote.firstOrNull { it.uuid == targetUuid }
                val winnerModifiedDate = when {
                    localNote != null && remoteNote != null -> maxOf(localNote.modifiedDate, remoteNote.modifiedDate)
                    localNote != null -> localNote.modifiedDate
                    else -> remoteNote!!.modifiedDate
                }
                val isPresent = result.notes.any { it.uuid == targetUuid }
                if (deletedAt >= winnerModifiedDate) {
                    assertTrue("tombstone at $deletedAt should have dropped a copy modified at $winnerModifiedDate", !isPresent)
                } else {
                    assertTrue("an edit at $winnerModifiedDate after the tombstone at $deletedAt should survive", isPresent)
                }
            }
        }
    }
}
