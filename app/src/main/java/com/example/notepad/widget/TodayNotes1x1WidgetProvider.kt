package com.example.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.example.notepad.data.Note
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.NoteEditorActivity
import java.util.Calendar

/**
 * A fixed 1x1 widget, offered as its own pickable entry in the launcher's widget picker instead
 * of only being reachable by resizing [TodayNotesWidgetProvider]'s adaptive widget all the way
 * down. Same tile as that one's own small-size fallback -- today's weekday/day-of-month, tap to
 * open today, a small "+" to add a note -- built by the exact same [TodayNotesWidgetProvider
 * .buildCompactViews], not a duplicated copy of that layout logic.
 */
class TodayNotes1x1WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val openAppIntent = PendingIntent.getActivity(
                context, 10,
                Intent(context, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val addPendingIntent = PendingIntent.getActivity(
                context, 11,
                Intent(context, NoteEditorActivity::class.java).apply {
                    putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
                    putExtra(NoteEditorActivity.EXTRA_DUE_DATE, todayStartMillis())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            appWidgetManager.updateAppWidget(
                widgetId,
                TodayNotesWidgetProvider.buildCompactViews(context, openAppIntent, addPendingIntent)
            )
        }

        private fun todayStartMillis(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Called from [TodayNotesWidgetProvider.notifyNotesChanged] after any note mutation. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayNotes1x1WidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }
    }
}
