package com.example.notepad.ui

import android.content.Context
import com.example.notepad.data.Note

/** Remembers how the notes list (pinned notes always first) is sorted within each group. */
object NotesSortPrefs {
    private const val PREFS_NAME = "notes_prefs"
    private const val KEY_SORT_MODE = "sort_mode"

    const val SORT_RECENT = 0
    const val SORT_COLOR = 1
    const val SORT_TITLE = 2
    const val SORT_DUE_DATE = 3

    fun getSortMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_SORT_MODE, SORT_RECENT)

    fun setSortMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_SORT_MODE, mode).apply()
    }

    /** [notes] is assumed already ordered by modifiedDate DESC (the DAO's base order). */
    fun apply(notes: List<Note>, mode: Int): List<Note> {
        val sorted = when (mode) {
            SORT_COLOR -> notes.sortedBy { it.colorIndex }
            SORT_TITLE -> notes.sortedBy { it.title.lowercase() }
            // Notes with no due date (0L) sort last, not first, since "someday" is less
            // actionable than anything with an actual date.
            SORT_DUE_DATE -> notes.sortedBy { if (it.dueDate == 0L) Long.MAX_VALUE else it.dueDate }
            else -> notes
        }
        return sorted.sortedByDescending { it.pinned }
    }
}
