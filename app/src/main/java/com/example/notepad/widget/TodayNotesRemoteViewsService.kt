package com.example.notepad.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.RecurrenceUtils
import com.example.notepad.ui.NoteEditorActivity
import java.util.Calendar
import kotlinx.coroutines.runBlocking

class TodayNotesRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayNotesRemoteViewsFactory(applicationContext)
}

/** Builds the widget's note rows. RemoteViewsService re-creates this per bind, so it stays stateless aside from its own cached [notes]. */
private class TodayNotesRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var notes: List<Note> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val dao = AppDatabase.getInstance(context).noteDao()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        notes = runBlocking { dao.getAllReminders() }
            .filter { RecurrenceUtils.occursOn(it, todayStart) && !RecurrenceUtils.isCompletedOn(it, todayStart) }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getViewAt(position: Int): RemoteViews {
        val note = notes[position]
        val views = RemoteViews(context.packageName, R.layout.widget_today_notes_item)

        val label = note.title.ifBlank {
            if (note.type == Note.TYPE_CHECKLIST) {
                context.getString(R.string.note_type_checklist)
            } else {
                com.example.notepad.ui.NoteFormatting.plainText(note.body, note.isFormatted).take(40)
            }
        }
        views.setTextViewText(R.id.itemTitle, label)
        views.setInt(
            R.id.itemDot,
            "setColorFilter",
            context.getColor(com.example.notepad.ui.NoteColors.colorResFor(note.colorIndex))
        )

        val fillInIntent = Intent().apply {
            putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id)
            putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, note.type)
        }
        views.setOnClickFillInIntent(R.id.itemRoot, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = notes[position].id
    override fun hasStableIds(): Boolean = true
}
