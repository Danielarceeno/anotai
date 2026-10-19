package com.example.notepad.widget

import android.content.Context

/** How many days ahead the home-screen widget's note list covers -- default 1 (today only,
 *  the widget's original fixed behavior). Settings lets this widen to 3 or 7 days, so an
 *  upcoming due date doesn't need a trip to the calendar just to be seen. */
object WidgetPrefs {
    private const val PREFS_NAME = "widget_prefs"
    private const val KEY_RANGE_DAYS = "range_days"
    const val DEFAULT_RANGE_DAYS = 1

    fun getRangeDays(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_RANGE_DAYS, DEFAULT_RANGE_DAYS)

    fun setRangeDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_RANGE_DAYS, days).apply()
    }
}
