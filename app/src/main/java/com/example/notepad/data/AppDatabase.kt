package com.example.notepad.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [Note::class, Folder::class, NoteTombstone::class], version = 13, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun folderDao(): FolderDao
    abstract fun noteTombstoneDao(): NoteTombstoneDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null
        private const val DB_NAME = "notepad.db"

        /** Adds [Note.uuid] (default "") and backfills a random one per existing row, since
         *  SQLite has no built-in UUID generator for a plain column default. Needed so Drive
         *  backup/restore can merge notes per-note (see [Note.uuid]'s doc) instead of only ever
         *  overwriting or restoring the whole set. */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.query("SELECT id FROM notes WHERE uuid = ''").use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE notes SET uuid = ? WHERE id = ?",
                            arrayOf(UUID.randomUUID().toString(), cursor.getLong(idIndex))
                        )
                    }
                }
            }
        }

        /** Exact-time reminders (see [Note.reminderTimeMinutes]): a plain additive column with a
         *  SQL default, no backfill loop needed since -1 ("all day") is the correct value for
         *  every pre-existing row. */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderTimeMinutes INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** Rich text (bold/italic/underline) support: a plain additive column with a SQL
         *  default, no backfill loop needed since every pre-existing row's body is plain text. */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN isFormatted INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Reminder time range (see [Note.reminderEndTimeMinutes]): a plain additive column with
         *  a SQL default, no backfill loop needed since -1 ("no end", i.e. a single-instant
         *  reminder) is the correct value for every pre-existing row. */
        private val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN reminderEndTimeMinutes INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** Adds the tombstone table (see [NoteTombstone]'s doc for why Drive backup needs it),
         *  and backfills one tombstone per note already sitting in the trash -- otherwise a note
         *  trashed before this update, with no tombstone recorded for it, would still get
         *  resurrected by the very next Drive merge. [Note.modifiedDate] is the best available
         *  stand-in for "when it was deleted" for that backfill (trashing a note doesn't bump it
         *  today, but it's never older than the actual deletion, so it only risks losing to a
         *  genuinely newer remote edit -- the same safe-ish fallback a missing timestamp would
         *  get anyway). */
        private val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_tombstones` (`uuid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`uuid`))"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO note_tombstones (uuid, deletedAt)
                    SELECT uuid, modifiedDate FROM notes WHERE activeState = ${Note.TRASHED}
                    """
                )
            }
        }

        /** Adds an FTS5 shadow index over [Note.title]/[Note.body] (see [NoteDao.search]'s doc),
         *  external-content so nothing is duplicated -- `content='notes'`/`content_rowid='id'`
         *  point it at the real `notes` table, kept in sync by the triggers below instead of
         *  Room's own content-entity sync (which only fires for Room-generated insert/update/
         *  delete calls, not the raw `UPDATE notes SET activeState = ...` [NoteDao.delete]/
         *  [NoteDao.restore] use). The `trigram` tokenizer is deliberate, not the FTS5 default:
         *  it's the one tokenizer SQLite can also use to accelerate a plain `LIKE '%...%'` scan,
         *  so this is a drop-in index for the existing substring search (see the README/
         *  EVIDENCE.md on why that's the intended behavior) rather than a switch to whole-word
         *  matching. */
        private val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) = createNotesFtsIndex(db)
        }

        private fun createNotesFtsIndex(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(title, body, content='notes', content_rowid='id', tokenize='trigram')"
            )
            db.execSQL("INSERT INTO notes_fts(rowid, title, body) SELECT id, title, body FROM notes")
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
                  INSERT INTO notes_fts(rowid, title, body) VALUES (new.id, new.title, new.body);
                END
                """
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
                  INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES('delete', old.id, old.title, old.body);
                END
                """
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE ON notes BEGIN
                  INSERT INTO notes_fts(notes_fts, rowid, title, body) VALUES('delete', old.id, old.title, old.body);
                  INSERT INTO notes_fts(rowid, title, body) VALUES (new.id, new.title, new.body);
                END
                """
            )
        }

        /** Creates [createNotesFtsIndex]'s virtual table/triggers for a brand-new database too --
         *  [MIGRATION_12_13] only runs for an existing install being upgraded, not a fresh
         *  `Room.databaseBuilder` create (no prior version to migrate from). */
        private val ftsCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                createNotesFtsIndex(db)
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context.applicationContext).also { instance = it }
            }

        /** Encrypts the local notes database at rest with SQLCipher (see [DatabaseKeyProvider]
         *  for the passphrase). Installs from before this was added have a plaintext `notepad.db`
         *  already on disk; [migratePlaintextDatabaseIfNeeded] detects and migrates it to an
         *  encrypted one, once, before this function returns. */
        private fun buildDatabase(context: Context): AppDatabase {
            SQLiteDatabase.loadLibs(context)
            val passphrase = DatabaseKeyProvider.getOrCreatePassphrase(context)
            migratePlaintextDatabaseIfNeeded(context, passphrase)

            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes(passphrase)))
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .addCallback(ftsCallback)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Reads every row out of the existing plaintext `notepad.db` through Room itself --
         * running it through the exact same [MIGRATION_7_8]/[MIGRATION_8_9] chain any other
         * upgrade already goes through, rather than duplicating schema knowledge in raw SQL --
         * then writes it all into a brand new SQLCipher-encrypted database and swaps it in. A
         * no-op if there's no existing file (fresh install) or it already opens with
         * [passphrase] (already migrated, by this call or a previous one). Any failure here is
         * caught and logged rather than crashing app startup: worst case the plaintext file is
         * left in place and this runs again -- and finishes the job -- on the next launch.
         */
        private fun migratePlaintextDatabaseIfNeeded(context: Context, passphrase: CharArray) {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return
            if (opensWithPassphrase(dbFile, passphrase)) return

            val legacyName = "notepad_legacy_migrate.db"
            val newName = "$DB_NAME.new"
            try {
                deleteDatabaseFiles(context, legacyName)
                deleteDatabaseFiles(context, newName)
                dbFile.copyTo(context.getDatabasePath(legacyName), overwrite = true)

                val legacyDb = Room.databaseBuilder(context, AppDatabase::class.java, legacyName)
                    .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .fallbackToDestructiveMigration()
                    .build()
                val (folders, notes) = runBlocking {
                    legacyDb.folderDao().getAll() to
                        (legacyDb.noteDao().getAllForExport() + legacyDb.noteDao().getTrashedForCleanup())
                }
                legacyDb.close()
                deleteDatabaseFiles(context, legacyName)

                val encryptedDb = Room.databaseBuilder(context, AppDatabase::class.java, newName)
                    .openHelperFactory(SupportFactory(SQLiteDatabase.getBytes(passphrase)))
                    .addCallback(ftsCallback)
                    .build()
                runBlocking {
                    folders.forEach { encryptedDb.folderDao().insert(it) }
                    notes.forEach { encryptedDb.noteDao().insert(it) }
                }
                encryptedDb.close()

                check(dbFile.delete()) { "Could not remove the old plaintext database" }
                check(context.getDatabasePath(newName).renameTo(dbFile)) {
                    "Could not move the newly-encrypted database into place"
                }
            } catch (e: Exception) {
                android.util.Log.e("AppDatabase", "Failed to migrate the plaintext database to encrypted; will retry next launch", e)
                deleteDatabaseFiles(context, legacyName)
                deleteDatabaseFiles(context, newName)
            }
        }

        private fun deleteDatabaseFiles(context: Context, name: String) {
            context.getDatabasePath(name).delete()
            File(context.getDatabasePath(name).path + "-wal").delete()
            File(context.getDatabasePath(name).path + "-shm").delete()
        }

        private fun opensWithPassphrase(dbFile: File, passphrase: CharArray): Boolean = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, String(passphrase), null, SQLiteDatabase.OPEN_READONLY).close()
            true
        } catch (e: Exception) {
            false
        }
    }
}
