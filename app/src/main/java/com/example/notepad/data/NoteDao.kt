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
     * Same technique observed in the reference APK's query strings
     * ("... LIKE ? ESCAPE '\'"): a plain substring scan over the text
     * columns, not FTS. The caller is responsible for wrapping [pattern]
     * in '%...%' and escaping any literal '%', '_' or '\' the user typed
     * (see NoteRepository.escapeLikePattern), since Room binds the
     * parameter as-is rather than treating it as SQL.
     */
    @Query(
        """
        SELECT * FROM notes
        WHERE activeState = ${Note.ACTIVE}
        AND (title LIKE :pattern ESCAPE '\' OR body LIKE :pattern ESCAPE '\')
        ORDER BY modifiedDate DESC
        """
    )
    fun search(pattern: String): LiveData<List<Note>>
}
