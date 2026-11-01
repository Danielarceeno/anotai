package com.example.notepad.widget

import android.content.Context

/** Which note each placed [PickedNoteWidgetProvider] instance points at, keyed by its own
 *  `appWidgetId` -- unlike the "today" widgets, every instance of this one can be configured to
 *  show a different, specific note (password-protected or not). */
object PickedNoteWidgetPrefs {
    private const val PREFS_NAME = "picked_note_widget_prefs"
    private const val NO_NOTE_ID = -1L

    fun getNoteId(context: Context, appWidgetId: Int): Long =
        prefs(context).getLong(key(appWidgetId), NO_NOTE_ID)

    fun setNoteId(context: Context, appWidgetId: Int, noteId: Long) {
        prefs(context).edit().putLong(key(appWidgetId), noteId).apply()
    }

    /** Called from [PickedNoteWidgetProvider.onDeleted] so a removed widget's mapping doesn't
     *  linger forever under a never-reused `appWidgetId`. */
    fun remove(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(key(appWidgetId)).apply()
    }

    private fun key(appWidgetId: Int) = "note_id_$appWidgetId"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
