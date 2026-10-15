package com.example.notepad.ui

import android.content.Context
import com.example.notepad.data.Note

/** Remembers how the notes list (pinned notes always first) is sorted within each group. */
object NotesSortPrefs {
    private const val PREFS_NAME = "notes_prefs"
    private const val KEY_SORT_MODE = "sort_mode"

    const val SORT_RECENT = 0
    const val SORT_COLOR = 1

    fun getSortMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_SORT_MODE, SORT_RECENT)

    fun setSortMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_SORT_MODE, mode).apply()
    }

    /** [notes] is assumed already ordered by modifiedDate DESC (the DAO's base order). */
    fun apply(notes: List<Note>, mode: Int): List<Note> {
        val sorted = when (mode) {
            SORT_COLOR -> notes.sortedBy { it.colorIndex }
            else -> notes
        }
        return sorted.sortedByDescending { it.pinned }
    }
}
