package com.example.notepad.ui

import android.content.Context

/** Whether swiping up/down on the calendar grid also changes month, on top of the always-on
 *  left/right swipe. Off by default: a vertical swipe used to double as a month-change gesture,
 *  which fought with the (non-existent, but still expected) instinct to scroll -- left/right only
 *  matches the reference app and most calendar UIs, so this is opt-in from Settings. */
object CalendarPrefs {
    private const val PREFS_NAME = "calendar_prefs"
    private const val KEY_VERTICAL_SWIPE_ENABLED = "vertical_swipe_enabled"

    fun isVerticalSwipeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERTICAL_SWIPE_ENABLED, false)

    fun setVerticalSwipeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERTICAL_SWIPE_ENABLED, enabled).apply()
    }
}
