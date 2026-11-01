package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.DatabaseKeyProvider
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 6 mission: the SQLCipher-encrypted database file must not be readable without the
 * passphrase (a plain, unencrypted SQLite reader must fail against it), and a note's actual
 * plaintext content must not show up anywhere in the raw file bytes -- only ciphertext. The
 * passphrase itself (see [DatabaseKeyProvider]'s own doc) must never appear in plain text either.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseEncryptionAtRestTest {

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
    }

    @Test
    fun theRawDbFile_cannotBeOpenedByAPlainUnencryptedSqliteReader() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = context.getDatabasePath("notepad.db")
        assertTrue("expected the real database file to already exist by now", dbFile.exists())

        var openedAsPlainSqlite = false
        try {
            // android.database.sqlite's own framework SQLite has no idea how to read a
            // SQLCipher-encrypted file -- it isn't plain SQLite format at all from its point of
            // view. openDatabase() alone can succeed regardless (format validation is lazy,
            // deferred to the first real read), so this forces an actual query -- that must
            // throw, never return real results.
            android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery("SELECT name FROM sqlite_master", null).use { it.moveToFirst() }
            }
            openedAsPlainSqlite = true
        } catch (e: Exception) {
            // Expected: not a valid plain-SQLite file.
        }

        assertFalse("the encrypted database file must not be openable as plain SQLite", openedAsPlainSqlite)
    }

    @Test
    fun aNotesPlaintextTitleAndBody_neverAppearAsRawBytesInTheDbFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        val secretTitle = "UNENCRYPTED_CANARY_TITLE_zx91q"
        val secretBody = "UNENCRYPTED_CANARY_BODY_mq77p"
        runBlocking { repository.insert(Note(title = secretTitle, body = secretBody)) }
        // Room/SQLCipher batch writes through a WAL file before they're checkpointed into the
        // main db file -- force a full checkpoint so the plaintext scan below actually covers
        // where this note's bytes ended up, not just whichever file happens to still hold them.
        db.query("PRAGMA wal_checkpoint(FULL)", emptyArray()).close()

        val dbFile = context.getDatabasePath("notepad.db")
        val rawBytes = dbFile.readBytes().toString(Charsets.ISO_8859_1)

        assertFalse("the note's plaintext title must never appear in the raw encrypted file", rawBytes.contains(secretTitle))
        assertFalse("the note's plaintext body must never appear in the raw encrypted file", rawBytes.contains(secretBody))
    }

    @Test
    fun theDatabasePassphrase_neverAppearsInPlainTextInItsOwnPrefsFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val passphrase = String(DatabaseKeyProvider.getOrCreatePassphrase(context))

        val prefsFile = java.io.File(context.filesDir.parentFile, "shared_prefs/db_key_prefs.xml")
        assertTrue("expected the encrypted prefs file backing the passphrase to exist", prefsFile.exists())
        val rawXml = prefsFile.readText()

        assertFalse(
            "the passphrase must only ever be stored encrypted (via EncryptedSharedPreferences), never as plain text",
            rawXml.contains(passphrase)
        )
    }
}
