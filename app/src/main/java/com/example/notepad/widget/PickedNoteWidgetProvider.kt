package com.example.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.ui.NoteColors
import com.example.notepad.ui.NoteEditorActivity
import kotlinx.coroutines.runBlocking

/**
 * A 1x1 widget pinned to one specific note, chosen per placed instance via
 * [com.example.notepad.ui.PickedNoteWidgetConfigActivity] -- unlike the "today" widgets, which
 * always show whatever's due today, every instance of this one can point at a different note,
 * password-protected or not (see [PickedNoteWidgetPrefs]).
 */
class PickedNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    /** Forgets the removed widget's note mapping -- otherwise it lingers forever under an
     *  `appWidgetId` the launcher will never reuse. */
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { PickedNoteWidgetPrefs.remove(context, it) }
    }

    companion object {
        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val noteId = PickedNoteWidgetPrefs.getNoteId(context, widgetId)
            val db = AppDatabase.getInstance(context)
            val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
            val note = if (noteId == -1L) null else runBlocking { repository.getById(noteId) }

            val views = RemoteViews(context.packageName, R.layout.widget_picked_note)
            if (note == null) {
                views.setTextViewText(R.id.widgetPickedTitle, context.getString(R.string.widget_picked_note_missing))
                views.setViewVisibility(R.id.widgetPickedLock, View.GONE)
                views.setOnClickPendingIntent(R.id.widgetPickedRoot, null)
            } else {
                val isLocked = note.passwordHash.isNotEmpty()
                val label = note.title.ifBlank {
                    when {
                        isLocked -> context.getString(R.string.note_locked_preview)
                        note.type == Note.TYPE_CHECKLIST -> context.getString(R.string.note_type_checklist)
                        else -> com.example.notepad.ui.NoteFormatting.plainText(note.body, note.isFormatted).take(40)
                    }
                }
                views.setInt(R.id.widgetPickedRoot, "setBackgroundColor", NoteColors.resolveArgb(context, note))
                views.setTextViewText(R.id.widgetPickedTitle, label)
                views.setViewVisibility(R.id.widgetPickedLock, if (isLocked) View.VISIBLE else View.GONE)

                val openIntent = PendingIntent.getActivity(
                    context, 20_000 + widgetId,
                    Intent(context, NoteEditorActivity::class.java)
                        .putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widgetPickedRoot, openIntent)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }

        /** Called from [TodayNotesWidgetProvider.notifyNotesChanged] after any note mutation, so
         *  a pinned note's title/color/lock state here stays current without waiting for the
         *  next periodic update. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PickedNoteWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }
    }
}
