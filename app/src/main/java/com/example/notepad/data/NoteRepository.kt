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
class NoteRepository(private val dao: NoteDao, private val appContext: Context) {

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

    suspend fun delete(id: Long) {
        dao.delete(id)
        TodayNotesWidgetProvider.notifyNotesChanged(appContext)
    }

    suspend fun restore(id: Long) {
        dao.restore(id)
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
