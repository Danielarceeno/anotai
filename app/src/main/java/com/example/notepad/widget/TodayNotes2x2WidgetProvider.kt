package com.example.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.notepad.R
import com.example.notepad.data.Note
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.NoteEditorActivity
import java.util.Calendar

/**
 * A fixed 2x2 widget showing today's notes as a small scrollable list, offered as its own
 * pickable entry rather than only reachable by resizing [TodayNotesWidgetProvider]'s adaptive
 * widget. Built by that class's own [TodayNotesWidgetProvider.buildFullViews] -- same layout,
 * same [TodayNotesRemoteViewsService]-backed list -- just always at this fixed size, with no
 * compact-tile fallback to switch to (see [TodayNotesSummaryWidgetProvider] for a visually
 * different 2x2 alternative).
 */
class TodayNotes2x2WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val openAppIntent = PendingIntent.getActivity(
                context, 20,
                Intent(context, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val addPendingIntent = PendingIntent.getActivity(
                context, 21,
                Intent(context, NoteEditorActivity::class.java).apply {
                    putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
                    putExtra(NoteEditorActivity.EXTRA_DUE_DATE, todayStartMillis())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetManager.updateAppWidget(
                widgetId,
                TodayNotesWidgetProvider.buildFullViews(context, widgetId, openAppIntent, addPendingIntent)
            )
        }

        private fun todayStartMillis(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Called from [TodayNotesWidgetProvider.notifyNotesChanged] after any note mutation. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayNotes2x2WidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetListView)
            ids.forEach { updateWidget(context, manager, it) }
        }
    }
}
