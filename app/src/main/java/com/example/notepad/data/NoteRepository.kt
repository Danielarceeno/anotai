package com.example.notepad.data

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.notepad.widget.TodayNotesWidgetProvider
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * [appContext] should always be an application context (not an Activity one):
 * it's only used to refresh the home-screen widget after a mutation, so every
 * caller that changes a note gets that refresh for free instead of having to
 * remember to call [TodayNotesWidgetProvider.notifyNotesChanged] itself.
 */
class NoteRepository(
    private val dao: NoteDao,
    private val appContext: Context,
    private val tombstoneDao: NoteTombstoneDao
) {

    fun observeAll(): LiveData<List<Note>> = dao.observeAll()

    /**
     * Pages the main notes list from Room instead of loading every active note into memory at
     * once. [sortMode] matches [com.example.notepad.ui.NotesSortPrefs]'s `SORT_*` constants
     * (duplicated here as plain ints rather than a compile-time dependency, since those live in
     * the `ui` package and this is `data`); pinned notes always sort first, same as
     * [com.example.notepad.ui.NotesSortPrefs.apply] does for the non-paged lists. [folderId] of
     * 0 means "every folder", matching the rest of the app's convention for "no folder".
     */
    fun observeAllPaged(folderId: Long, sortMode: Int): Flow<PagingData<Note>> {
        val orderBy = when (sortMode) {
            1 -> "pinned DESC, colorIndex ASC" // SORT_COLOR
            2 -> "pinned DESC, title COLLATE NOCASE ASC" // SORT_TITLE
            3 -> "pinned DESC, CASE WHEN dueDate = 0 THEN 1 ELSE 0 END ASC, dueDate ASC" // SORT_DUE_DATE
            else -> "pinned DESC, modifiedDate DESC" // SORT_RECENT
        }
        val folderClause = if (folderId != 0L) "AND folderId = $folderId" else ""
        val sql = "SELECT * FROM notes WHERE activeState = ${Note.ACTIVE} $folderClause ORDER BY $orderBy"
        return Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) {
            dao.observeAllPagedRaw(SimpleSQLiteQuery(sql))
        }.flow
    }

    suspend fun insert(note: Note): Long {
        val id = dao.insert(note)
        TodayNotesWidgetProvider.notifyNotesChanged(appContext)
        return id
    }

    suspend fun update(note: Note) {
        dao.update(note)
        TodayNotesWidgetProvider.notifyNotesChanged(appContext)
    }

    /** Also records a tombstone for this note's uuid -- see [NoteTombstone]'s doc -- so a
     *  Drive backup merge doesn't resurrect it: [getAllForExport] (the merge's "local" side)
     *  only returns active notes, so without a tombstone a just-trashed note's uuid looks
     *  exactly like a brand new note only Drive has. */
    suspend fun delete(id: Long) {
        val uuid = dao.getById(id)?.uuid
        dao.delete(id)
        if (uuid != null) tombstoneDao.insert(NoteTombstone(uuid, System.currentTimeMillis()))
        TodayNotesWidgetProvider.notifyNotesChanged(appContext)
    }

    /** Clears the tombstone (if any) too: an explicit restore means the user wants this note
     *  back, so it should sync normally again instead of the next Drive merge treating it as
     *  still-deleted. */
    suspend fun restore(id: Long) {
        val uuid = dao.getById(id)?.uuid
        dao.restore(id)
        if (uuid != null) tombstoneDao.delete(uuid)
        TodayNotesWidgetProvider.notifyNotesChanged(appContext)
    }

    /** uuid -> when it was deleted, for [com.example.notepad.data.drive.DriveBackupHelper
     *  .mergeByUuid] to tell "locally deleted on purpose" apart from "a genuinely new note only
     *  the remote backup has". */
    suspend fun getTombstones(): Map<String, Long> =
        tombstoneDao.getAll().associate { it.uuid to it.deletedAt }

    /** Unions this device's own tombstones with a remote backup's (the newer [NoteTombstone
     *  .deletedAt] wins per uuid), persists the result locally, and returns it for
     *  [com.example.notepad.data.drive.DriveBackupHelper.mergeByUuid] to use. Without persisting
     *  a remote-only tombstone here, this device would forget about it the moment it's no longer
     *  included in whichever other device's backup it first saw it in -- it needs to keep
     *  rejecting that uuid on every future merge, not just this one. */
    suspend fun mergeTombstones(remote: Map<String, Long>): Map<String, Long> {
        val local = getTombstones()
        val merged = (local.keys + remote.keys).associateWith { uuid ->
            maxOf(local[uuid] ?: 0L, remote[uuid] ?: 0L)
        }
        merged.forEach { (uuid, deletedAt) -> tombstoneDao.insert(NoteTombstone(uuid, deletedAt)) }
        return merged
    }

    /** Trashes a note without stamping a fresh tombstone over it, for when a Drive merge (see
     *  [com.example.notepad.data.drive.DriveBackupHelper.mergeByUuid]) already determined --
     *  using a tombstone [mergeTombstones] already persisted -- that this note was deleted on
     *  another device. [delete] would otherwise record "deleted just now" here, clobbering the
     *  real cross-device deletion time that other devices still need to compare against. */
    suspend fun trashLocally(id: Long) {
        dao.delete(id)
        TodayNotesWidgetProvider.notifyNotesChanged(appContext)
    }

    suspend fun deleteForever(id: Long) {
        deleteImageFile(dao.getById(id))
        dao.deleteForever(id)
    }

    suspend fun emptyTrash() {
        dao.getTrashedForCleanup()
            .filter { it.imagePath.isNotBlank() }
            .forEach { File(it.imagePath).delete() }
        dao.emptyTrash()
    }

    private fun deleteImageFile(note: Note?) {
        if (note != null && note.imagePath.isNotBlank()) File(note.imagePath).delete()
    }

    fun observeTrashed(): LiveData<List<Note>> = dao.observeTrashed()

    fun observeByFolder(folderId: Long): LiveData<List<Note>> = dao.observeByFolder(folderId)

    /** Every active note's id in the given scope ([folderId] of 0 meaning "every folder"), for
     *  "select all" -- independent of how much of a paged list has actually loaded on screen. */
    suspend fun getAllIds(folderId: Long): List<Long> =
        if (folderId != 0L) dao.getActiveIdsInFolder(folderId) else dao.getAllActiveIds()

    suspend fun getAllForExport(): List<Note> = dao.getAllForExport()

    suspend fun getById(id: Long): Note? = dao.getById(id)

    fun observeAllReminders(): LiveData<List<Note>> = dao.observeAllReminders()

    suspend fun getAllReminders(): List<Note> = dao.getAllReminders()

    /**
     * [query] is the raw text the user typed; this builds the LIKE
     * pattern and escapes '%', '_' and '\' so a user searching for an
     * actual percent sign or underscore doesn't get treated as a wildcard.
     */
    fun search(query: String): LiveData<List<Note>> =
        dao.search("%${escapeLikePattern(query)}%")

    companion object {
        fun escapeLikePattern(raw: String): String =
            raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_")
    }
}
