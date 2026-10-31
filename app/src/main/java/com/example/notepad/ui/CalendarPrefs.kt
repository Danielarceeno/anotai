package com.example.notepad.ui

import android.content.Context

/** Whether swiping up/down on the calendar grid also changes month, on top of the always-on
 *  left/right swipe. Off by default: a vertical swipe used to double as a month-change gesture,
 *  which fought with the (non-existent, but still expected) instinct to scroll -- left/right only
 *  matches the reference app and most calendar UIs, so this is opt-in from Settings. */
object CalendarPrefs {
    private const val PREFS_NAME = "calendar_prefs"
    private const val KEY_VERTICAL_SWIPE_ENABLED = "vertical_swipe_enabled"
    private const val KEY_HOLIDAYS_ENABLED = "holidays_enabled"
    private const val KEY_OVERDUE_HIGHLIGHT_ENABLED = "overdue_highlight_enabled"

    fun isVerticalSwipeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERTICAL_SWIPE_ENABLED, false)

    fun setVerticalSwipeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERTICAL_SWIPE_ENABLED, enabled).apply()
    }

    /** Shows Brazilian national holidays (see [com.example.notepad.data.BrazilianHolidays]) on
     *  the calendar grid and day dialog -- off by default, since not every user wants them. */
    fun isHolidaysEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HOLIDAYS_ENABLED, false)

    fun setHolidaysEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HOLIDAYS_ENABLED, enabled).apply()
    }

    /** Colors an overdue, not-yet-done task's title red in the day dialog (see
     *  [com.example.notepad.ui.DayNotesDialog]'s `isOverdue`) -- on by default, unlike the other
     *  toggles here, since it was the app's existing behavior before this setting existed. */
    fun isOverdueHighlightEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OVERDUE_HIGHLIGHT_ENABLED, true)

    fun setOverdueHighlightEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_OVERDUE_HIGHLIGHT_ENABLED, enabled).apply()
    }
}
