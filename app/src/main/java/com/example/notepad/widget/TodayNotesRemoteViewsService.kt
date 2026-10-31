package com.example.notepad.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.RecurrenceUtils
import com.example.notepad.ui.CalendarActivity
import java.util.Calendar
import kotlinx.coroutines.runBlocking

class TodayNotesRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodayNotesRemoteViewsFactory(applicationContext)
}

/** One row's note plus the specific day (within the widget's range) it's shown under -- tapping
 *  it opens that day's dialog, so a note that recurs across more than one day in range still
 *  opens on the particular occurrence this row actually represents. */
private data class WidgetEntry(val note: Note, val dayStart: Long)

/** Builds the widget's note rows. RemoteViewsService re-creates this per bind, so it stays stateless aside from its own cached [entries]. */
private class TodayNotesRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var entries: List<WidgetEntry> = emptyList()

    override fun onCreate() {}

    /** Walks [WidgetPrefs.getRangeDays] days forward from today (1 by default, i.e. just today),
     *  unioning each day's due notes rather than only checking today -- a note recurring on
     *  several of those days (or due on more than one, which can't happen, but a note due on day
     *  2 of a 3-day range still should) is kept only once, in the order its first occurrence in
     *  the range falls. */
    override fun onDataSetChanged() {
        val db = AppDatabase.getInstance(context)
        // Goes through NoteRepository (RemindersCache-backed), not a raw dao.getAllReminders()
        // call, so this doesn't redundantly re-scan the table when ReminderNotifier.checkAndNotify
        // already queried the exact same thing moments ago for the same note mutation that
        // triggered this widget refresh in the first place -- see RemindersCache's doc.
        val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val oneDayMillis = 24 * 60 * 60 * 1000L
        val rangeDays = WidgetPrefs.getRangeDays(context)

        val allReminders = runBlocking { repository.getAllReminders() }
        val seenIds = mutableSetOf<Long>()
        val result = mutableListOf<WidgetEntry>()
        for (offset in 0 until rangeDays) {
            val dayStart = todayStart + offset * oneDayMillis
            allReminders
                .filter { RecurrenceUtils.occursOn(it, dayStart) && !RecurrenceUtils.isCompletedOn(it, dayStart) }
                .forEach { note -> if (seenIds.add(note.id)) result.add(WidgetEntry(note, dayStart)) }
        }
        entries = result
    }

    override fun onDestroy() {
        entries = emptyList()
    }

    override fun getCount(): Int = entries.size

    override fun getViewAt(position: Int): RemoteViews {
        val (note, dayStart) = entries[position]
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

        // Opens that day's dialog (same as tapping it on the calendar grid), not straight into
        // the note's own editor -- see TodayNotesWidgetProvider.buildFullViews' item template.
        val fillInIntent = Intent().apply {
            putExtra(CalendarActivity.EXTRA_OPEN_DAY_MILLIS, dayStart)
        }
        views.setOnClickFillInIntent(R.id.itemRoot, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = entries[position].note.id
    override fun hasStableIds(): Boolean = true
}
