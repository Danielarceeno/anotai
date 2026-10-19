package com.example.notepad.data

import androidx.lifecycle.LiveData
import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface NoteDao {

    /** Backs [NoteRepository.observeAllPaged]: the WHERE/ORDER BY depends on the current folder
     *  filter and sort mode, built as SQL text in [NoteRepository] rather than as several
     *  near-duplicate `@Query` methods (one per sort mode x folder-or-not combination). Room
     *  still tracks invalidation correctly via [observedEntities] despite the query itself being
     *  opaque to it at compile time. */
    @RawQuery(observedEntities = [Note::class])
    fun observeAllPagedRaw(query: SupportSQLiteQuery): PagingSource<Int, Note>

    @Insert
    suspend fun insert(note: Note): Long

    @Update
    suspend fun update(note: Note)

    @Query("UPDATE notes SET activeState = ${Note.TRASHED} WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE notes SET activeState = ${Note.ACTIVE} WHERE id = :id")
    suspend fun restore(id: Long)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteForever(id: Long)

    @Query("DELETE FROM notes WHERE activeState = ${Note.TRASHED}")
    suspend fun emptyTrash()

    @Query("SELECT * FROM notes WHERE activeState = ${Note.TRASHED} ORDER BY modifiedDate DESC")
    fun observeTrashed(): LiveData<List<Note>>

    /** One-shot read of everything in the trash, used only to clean up attached image files before [emptyTrash]. */
    @Query("SELECT * FROM notes WHERE activeState = ${Note.TRASHED}")
    suspend fun getTrashedForCleanup(): List<Note>

    @Query("SELECT * FROM notes WHERE activeState = ${Note.ACTIVE} AND folderId = :folderId ORDER BY modifiedDate DESC")
    fun observeByFolder(folderId: Long): LiveData<List<Note>>

    /** Backs "select all" in [NoteRepository.getAllIds]: every active note's id, regardless of
     *  which page a [androidx.paging.PagingSource] has actually loaded into the adapter. */
    @Query("SELECT id FROM notes WHERE activeState = ${Note.ACTIVE}")
    suspend fun getAllActiveIds(): List<Long>

    @Query("SELECT id FROM notes WHERE activeState = ${Note.ACTIVE} AND folderId = :folderId")
    suspend fun getActiveIdsInFolder(folderId: Long): List<Long>

    @Query("UPDATE notes SET folderId = 0 WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    /** For the JSON export/backup: every non-trashed note, in full. */
    @Query("SELECT * FROM notes WHERE activeState = ${Note.ACTIVE}")
    suspend fun getAllForExport(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getById(id: Long): Note?

    /**
     * All active notes that have a reminder date set, regardless of when it falls.
     * Recurring notes (weekly/monthly/...) only store their first occurrence in
     * [Note.dueDate], so callers must run [com.example.notepad.data.RecurrenceUtils]
     * over this list instead of filtering by date range in SQL.
     */
    @Query("SELECT * FROM notes WHERE activeState = ${Note.ACTIVE} AND dueDate != 0 ORDER BY dueDate ASC")
    suspend fun getAllReminders(): List<Note>

    @Query("SELECT * FROM notes WHERE activeState = ${Note.ACTIVE} ORDER BY modifiedDate DESC")
    fun observeAll(): LiveData<List<Note>>

    /** Same as [getAllReminders], as LiveData, for painting dots on the month grid. */
    @Query("SELECT * FROM notes WHERE activeState = ${Note.ACTIVE} AND dueDate != 0 ORDER BY dueDate ASC")
    fun observeAllReminders(): LiveData<List<Note>>

    /**
     * Same substring-match semantics observed in the reference APK's query strings
     * ("... LIKE ? ESCAPE '\'") -- a trigram-tokenized FTS5 index (see
     * [com.example.notepad.data.AppDatabase]'s `notes_fts`/`MIGRATION_12_13`) is what's doing the
     * actual scan now instead of a full `notes` table scan, but SQLite can use that same index to
     * accelerate a plain `LIKE '%...%'` query, so the query built in
     * [NoteRepository.search] still wraps its pattern in '%...%' and escapes any literal '%',
     * '_' or '\' the user typed exactly as before. A `@RawQuery`, not `@Query`, because `notes_fts`
     * isn't a Room-managed entity (see `notes_fts`'s doc) -- Room's compile-time query
     * verification only knows about declared entities/views. [observedEntities] still ties this
     * to [Note] so LiveData invalidation keeps working: every write to `notes_fts` happens inside
     * the same transaction as the `notes` write that triggered it.
     */
    @RawQuery(observedEntities = [Note::class])
    fun search(query: SupportSQLiteQuery): LiveData<List<Note>>
}
