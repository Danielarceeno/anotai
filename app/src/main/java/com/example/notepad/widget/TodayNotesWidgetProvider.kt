package com.example.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.notepad.R
import com.example.notepad.data.Note
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.NoteEditorActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Home-screen widget showing the notes due today (recurrence-aware, same
 * logic as the calendar dots and the daily notification). Tapping a note
 * opens it; the "+" button starts a new text note due today; the title
 * opens the app's calendar.
 */
class TodayNotesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    companion object {
        /** Call after any note insert/update/delete so every placed widget refreshes. */
        fun notifyNotesChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayNotesWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetListView)
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_today_notes)

            val dateLabel = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Calendar.getInstance().time)
            views.setTextViewText(R.id.widgetTitle, context.getString(R.string.widget_title, dateLabel))

            val serviceIntent = Intent(context, TodayNotesRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("widget://com.example.notepad/today/$widgetId")
            }
            views.setRemoteAdapter(R.id.widgetListView, serviceIntent)
            views.setEmptyView(R.id.widgetListView, R.id.widgetEmptyView)

            val openAppIntent = PendingIntent.getActivity(
                context, 0, Intent(context, CalendarActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, openAppIntent)
            // Tapping anywhere else on the widget's background (or the "no notes" empty
            // state) opens the app too, not just the title text itself.
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent)
            views.setOnClickPendingIntent(R.id.widgetEmptyView, openAppIntent)

            val addIntent = Intent(context, NoteEditorActivity::class.java).apply {
                putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
                putExtra(NoteEditorActivity.EXTRA_DUE_DATE, todayStartMillis())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val addPendingIntent = PendingIntent.getActivity(
                context, 1, addIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetAddButton, addPendingIntent)

            // Items fill in their own note id/type on top of this template (see
            // TodayNotesRemoteViewsFactory.getViewAt), since a ListView's rows
            // can't each carry a fully distinct PendingIntent.
            val itemTemplateIntent = Intent(context, NoteEditorActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val itemTemplatePendingIntent = PendingIntent.getActivity(
                context, 2, itemTemplateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.widgetListView, itemTemplatePendingIntent)

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun todayStartMillis(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
