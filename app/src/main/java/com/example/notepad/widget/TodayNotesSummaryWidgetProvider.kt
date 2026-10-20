package com.example.notepad.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.notepad.NotepadApplication
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.data.RecurrenceUtils
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.NoteEditorActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * A "today at a glance" alternative to [TodayNotes2x2WidgetProvider]'s scrollable list, same 2x2
 * footprint: day number, a plain "X due, Y done" count, and a small progress bar -- for someone
 * who wants a glance rather than a list to scroll in a home-screen tile.
 *
 * Unlike the other widgets here, this one needs an actual DB read (today's due/done counts) to
 * render anything, so [onUpdate] -- a [android.content.BroadcastReceiver] callback, same as any
 * [AppWidgetProvider] method -- holds a [goAsync] lease while that read happens off the main
 * thread, the same pattern [com.example.notepad.notification.BootReceiver] already uses, instead
 * of risking the process being torn down mid-read.
 */
class TodayNotesSummaryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pendingResult = goAsync()
        NotepadApplication.applicationScope.launch {
            try {
                appWidgetIds.forEach { widgetId ->
                    appWidgetManager.updateAppWidget(widgetId, buildViews(context))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private suspend fun buildViews(context: Context): RemoteViews {
            val db = AppDatabase.getInstance(context)
            val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
            val todayStart = startOfToday()
            val dueToday = repository.getAllReminders().filter { RecurrenceUtils.occursOn(it, todayStart) }
            val doneToday = dueToday.count { RecurrenceUtils.isCompletedOn(it, todayStart) }

            val views = RemoteViews(context.packageName, R.layout.widget_today_summary)
            val today = Calendar.getInstance()
            views.setTextViewText(R.id.widgetSummaryDay, today.get(Calendar.DAY_OF_MONTH).toString())
            views.setTextViewText(
                R.id.widgetSummaryWeekday,
                SimpleDateFormat("EEE", Locale.getDefault()).format(today.time).uppercase(Locale.getDefault())
            )
            views.setTextViewText(
                R.id.widgetSummaryCount,
                if (dueToday.isEmpty()) {
                    context.getString(R.string.widget_empty)
                } else {
                    context.getString(R.string.widget_summary_progress, doneToday, dueToday.size)
                }
            )
            views.setViewVisibility(R.id.widgetSummaryProgress, if (dueToday.isEmpty()) View.GONE else View.VISIBLE)
            views.setProgressBar(R.id.widgetSummaryProgress, dueToday.size.coerceAtLeast(1), doneToday, false)

            val openAppIntent = PendingIntent.getActivity(
                context, 30,
                Intent(context, CalendarActivity::class.java)
                    .putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val addPendingIntent = PendingIntent.getActivity(
                context, 31,
                Intent(context, NoteEditorActivity::class.java).apply {
                    putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
                    putExtra(NoteEditorActivity.EXTRA_DUE_DATE, todayStart)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetSummaryRoot, openAppIntent)
            views.setOnClickPendingIntent(R.id.widgetSummaryAddButton, addPendingIntent)
            return views
        }

        private fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Called from [TodayNotesWidgetProvider.notifyNotesChanged] after any note mutation.
         *  Not inside a [android.content.BroadcastReceiver] callback here, so no [goAsync] lease
         *  is needed -- [NotepadApplication.applicationScope] already outlives whatever caller
         *  (a repository write) triggered this. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayNotesSummaryWidgetProvider::class.java))
            if (ids.isEmpty()) return
            NotepadApplication.applicationScope.launch {
                val views = buildViews(context)
                ids.forEach { manager.updateAppWidget(it, views) }
            }
        }
    }
}
