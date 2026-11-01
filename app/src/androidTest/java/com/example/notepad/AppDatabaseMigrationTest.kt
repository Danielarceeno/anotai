package com.example.notepad

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Per the Fase 3 mission: Room migration tests, from the oldest version this app's migration
 * chain actually preserves data from (7 -- anything older falls back to
 * [androidx.room.RoomDatabase.Builder.fallbackToDestructiveMigration], i.e. is wiped, not
 * migrated) up through the current version. `AppDatabase`'s `exportSchema` was `false` before
 * this session (see `TESTING.md`), so there are no historical schema snapshots for versions 7-14
 * to validate *against* -- only version 15's (generated now) exists. Each test here therefore
 * builds its own "before" database by hand, with plain SQL matching exactly what that version's
 * migrations expect as input (cross-checked against `AppDatabase`'s own migration history), and
 * validates the *result* against the real, generated v15 schema -- [MigrationTestHelper
 * .runMigrationsAndValidate]'s schema check only needs the *target* version's snapshot, not the
 * starting one.
 *
 * The migration objects themselves ([AppDatabase.MIGRATION_7_8] etc.) were changed from `private`
 * to `@VisibleForTesting` for exactly this -- see `TESTING.md`'s testability-seams list.
 *
 * Uses SQLCipher's own `SQLiteDatabase`/`SupportFactory` throughout, the same as the real
 * [AppDatabase], not the plain framework ones `MigrationTestHelper` defaults to -- the plain
 * Android framework SQLite build on this device doesn't have the FTS5 module compiled in at all
 * ("no such module: fts5"), only SQLCipher's bundled one does, so `MIGRATION_12_13` (which
 * creates `notes_fts`) can't run against the default factory.
 */
class AppDatabaseMigrationTest {

    private val testDbName = "migration-test.db"
    private val passphrase = "migration-test-passphrase"

    @Before
    fun loadSqlCipher() {
        SQLiteDatabase.loadLibs(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportFactory(SQLiteDatabase.getBytes(passphrase.toCharArray()))
    )

    /** Builds a version-7 `notes`/`folders` database by hand (no historical schema snapshot
     *  exists to generate one from, so [MigrationTestHelper.createDatabase] -- which always looks
     *  one up -- can't be used at all here) -- exactly [Note]'s current columns minus everything
     *  a later migration adds (`uuid` in 7->8, `reminderTimeMinutes` in 8->9, `isFormatted` in
     *  9->10, `reminderEndTimeMinutes` in 10->11, `passwordHash`/`passwordSalt`/`customColorArgb`
     *  in 14->15) -- then seeds one active and one trashed note directly with SQL, the same way a
     *  real pre-migration install's data would already be sitting on disk. */
    private fun createV7DatabaseWithSeedData(): SQLiteDatabase {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(testDbName)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(testDbName), passphrase, null)
        db.execSQL(
            """
            CREATE TABLE notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                folderId INTEGER NOT NULL DEFAULT 0,
                activeState INTEGER NOT NULL DEFAULT 0,
                colorIndex INTEGER NOT NULL DEFAULT 0,
                type INTEGER NOT NULL DEFAULT 0,
                dueDate INTEGER NOT NULL DEFAULT 0,
                repetition INTEGER NOT NULL DEFAULT 0,
                repeatUntil INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                completedDates TEXT NOT NULL DEFAULT '',
                pinned INTEGER NOT NULL DEFAULT 0,
                imagePath TEXT NOT NULL DEFAULT '',
                modifiedDate INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE folders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_notes_folderId_activeState ON notes (folderId, activeState)")
        db.execSQL("CREATE INDEX index_notes_dueDate ON notes (dueDate)")
        db.execSQL(
            "INSERT INTO notes (id, title, body, folderId, activeState, colorIndex, type, dueDate, repetition, repeatUntil, completed, completedDates, pinned, imagePath, modifiedDate) " +
                "VALUES (1, 'Active note', 'body text', 0, 0, 3, 0, 0, 0, 0, 0, '', 0, '', 1000)"
        )
        db.execSQL(
            "INSERT INTO notes (id, title, body, folderId, activeState, colorIndex, type, dueDate, repetition, repeatUntil, completed, completedDates, pinned, imagePath, modifiedDate) " +
                "VALUES (2, 'Trashed note', 'old body', 0, 1, 0, 0, 0, 0, 0, 0, '', 0, '', 2000)"
        )
        // Room reads this to know what version a database it's about to migrate is starting
        // from -- without it, runMigrationsAndValidate would see version 0 and not know which of
        // the registered migrations even apply.
        db.version = 7
        return db
    }

    @Test
    fun fullChain_fromV7ToV15_preservesDataAndBackfillsCorrectly() {
        createV7DatabaseWithSeedData().close()

        // validateDroppedTables = false: Room's own check for "unexpected" tables doesn't know
        // about notes_fts (and SQLite's own internal shadow tables for it) since it's a raw
        // virtual table created by migration SQL, not a Room-tracked entity -- see NoteDao's doc
        // on notes_fts for why it's deliberately outside Room's own schema awareness.
        val migrated = helper.runMigrationsAndValidate(
            testDbName, 15, false,
            AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11, AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13,
            AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15
        )

        // The active note's data survived, and every column added along the way has the correct
        // default for a row that predates it.
        migrated.query("SELECT * FROM notes WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Active note", cursor.getString(cursor.getColumnIndexOrThrow("title")))
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("colorIndex")))
            assertTrue("uuid should have been backfilled, not left blank", cursor.getString(cursor.getColumnIndexOrThrow("uuid")).isNotBlank())
            assertEquals(-1, cursor.getInt(cursor.getColumnIndexOrThrow("reminderTimeMinutes")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isFormatted")))
            assertEquals(-1, cursor.getInt(cursor.getColumnIndexOrThrow("reminderEndTimeMinutes")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("passwordHash")))
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("passwordSalt")))
            assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("customColorArgb")))
        }

        // The trashed note got backfilled a tombstone (MIGRATION_11_12), stamped with its own
        // modifiedDate since that's the best available stand-in for "when it was deleted".
        val trashedUuid = migrated.query("SELECT uuid FROM notes WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        migrated.query("SELECT deletedAt FROM note_tombstones WHERE uuid = ?", arrayOf(trashedUuid)).use { cursor ->
            assertTrue("the trashed note should have a backfilled tombstone", cursor.moveToFirst())
            assertEquals(2000L, cursor.getLong(0))
        }
        // The active note must NOT have gotten one.
        migrated.query("SELECT uuid FROM notes WHERE id = 1").use { cursor ->
            cursor.moveToFirst()
            val activeUuid = cursor.getString(0)
            migrated.query("SELECT COUNT(*) FROM note_tombstones WHERE uuid = ?", arrayOf(activeUuid)).use { count ->
                count.moveToFirst()
                assertEquals(0, count.getInt(0))
            }
        }

        // notes_fts (MIGRATION_12_13) exists and is actually in sync with the migrated data.
        migrated.query("SELECT rowid FROM notes_fts WHERE notes_fts MATCH 'Active'").use { cursor ->
            assertTrue("the FTS index should have been backfilled from the migrated notes table", cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }

        // The activeState index (MIGRATION_13_14) exists.
        migrated.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_notes_activeState'").use { cursor ->
            assertTrue("the activeState index should exist after migrating to v14+", cursor.moveToFirst())
        }
    }

}
