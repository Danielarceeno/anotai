package com.example.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
 *
 * Responsive to the placed size (see [widget_today_notes_info.xml]'s `resizeMode`): below
 * [COMPACT_MAX_WIDTH_DP]x[COMPACT_MAX_HEIGHT_DP] it falls back to a compact "today's date, tap
 * to open" tile ([R.layout.widget_today_notes_compact]) instead of a list too cramped to read,
 * rather than offering only a single fixed-size layout.
 */
class TodayNotesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    /** Fired as the user drags a resize handle (and once right after placement), so the
     *  compact/full layout swap happens live instead of only on the next periodic update. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateWidget(context, appWidgetManager, appWidgetId)
    }

    companion object {
        /** Below this width OR height, a note list would show at most a sliver of one row, so
         *  the compact tile reads better than a barely-there list. */
        private const val COMPACT_MAX_WIDTH_DP = 180
        private const val COMPACT_MAX_HEIGHT_DP = 100
        /** Call after any note insert/update/delete so every placed widget refreshes. */
        fun notifyNotesChanged(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayNotesWidgetProvider::class.java))
            if (ids.isEmpty()) return
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widgetListView)
            ids.forEach { updateWidget(context, manager, it) }
        }

        private fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
            // Opens straight into today's day dialog (CalendarActivity.EXTRA_OPEN_TODAY) rather
            // than just the bare month view, so tapping the widget goes directly to today's list
            // of notes instead of one extra tap away. Shared by both layouts below.
            val openAppIntent = PendingIntent.getActivity(
                context, 0,
                Intent(context, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val addIntent = Intent(context, NoteEditorActivity::class.java).apply {
                putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
                putExtra(NoteEditorActivity.EXTRA_DUE_DATE, todayStartMillis())
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val addPendingIntent = PendingIntent.getActivity(
                context, 1, addIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val views = if (isCompactSize(appWidgetManager, widgetId)) {
                buildCompactViews(context, openAppIntent, addPendingIntent)
            } else {
                buildFullViews(context, widgetId, openAppIntent, addPendingIntent)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        /** True once the placed/resized widget is too small on either axis to show a readable
         *  list -- see [COMPACT_MAX_WIDTH_DP]/[COMPACT_MAX_HEIGHT_DP]. Falls back to treating a
         *  widget as non-compact if the launcher hasn't reported its size yet (option absent, 0). */
        private fun isCompactSize(appWidgetManager: AppWidgetManager, widgetId: Int): Boolean {
            val options = appWidgetManager.getAppWidgetOptions(widgetId)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
            return (width in 1 until COMPACT_MAX_WIDTH_DP) || (height in 1 until COMPACT_MAX_HEIGHT_DP)
        }

        private fun buildFullViews(
            context: Context,
            widgetId: Int,
            openAppIntent: PendingIntent,
            addPendingIntent: PendingIntent
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today_notes)

            val rangeDays = WidgetPrefs.getRangeDays(context)
            val titleText = if (rangeDays <= 1) {
                val dateLabel = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Calendar.getInstance().time)
                context.getString(R.string.widget_title, dateLabel)
            } else {
                context.resources.getQuantityString(R.plurals.widget_title_range_days, rangeDays, rangeDays)
            }
            views.setTextViewText(R.id.widgetTitle, titleText)

            val serviceIntent = Intent(context, TodayNotesRemoteViewsService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                data = Uri.parse("widget://com.example.notepad/today/$widgetId")
            }
            views.setRemoteAdapter(R.id.widgetListView, serviceIntent)
            views.setEmptyView(R.id.widgetListView, R.id.widgetEmptyView)

            views.setOnClickPendingIntent(R.id.widgetTitle, openAppIntent)
            // Tapping anywhere else on the widget's background (or the "no notes" empty
            // state) opens the app too, not just the title text itself.
            views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent)
            views.setOnClickPendingIntent(R.id.widgetEmptyView, openAppIntent)
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
            return views
        }

        /** A shortcut-style tile for a widget too small to usefully show a list: today's weekday
         *  + day-of-month, tapping anywhere opens today's day dialog, plus a small "+" corner
         *  button when there's room for one. No live note count here -- that would need a
         *  blocking DB read off this (possibly main-thread) call path; the full-size layout above
         *  already covers that via its RemoteViewsService, which runs off-thread by design. */
        private fun buildCompactViews(
            context: Context,
            openAppIntent: PendingIntent,
            addPendingIntent: PendingIntent
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_today_notes_compact)
            val today = Calendar.getInstance()
            views.setTextViewText(R.id.widgetCompactDay, today.get(Calendar.DAY_OF_MONTH).toString())
            views.setTextViewText(
                R.id.widgetCompactWeekday,
                SimpleDateFormat("EEE", Locale.getDefault()).format(today.time).uppercase(Locale.getDefault())
            )
            views.setOnClickPendingIntent(R.id.widgetCompactRoot, openAppIntent)
            views.setOnClickPendingIntent(R.id.widgetCompactAddButton, addPendingIntent)
            return views
        }

        private fun todayStartMillis(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
