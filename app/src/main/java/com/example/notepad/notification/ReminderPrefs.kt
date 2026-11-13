package com.example.notepad.notification

import android.content.Context

/** The daily reminder's time of day and its on/off switch. The time is no longer user-
 *  configurable (the Settings row for it was removed), so [getHour]/[getMinute] only ever return
 *  a value other than the 9:00 default on an install that changed it back when that row existed. */
object ReminderPrefs {
    private const val PREFS_NAME = "reminder_prefs"
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_ENABLED = "enabled"
    private const val DEFAULT_HOUR = 9
    private const val DEFAULT_MINUTE = 0

    fun getHour(context: Context): Int =
        prefs(context).getInt(KEY_HOUR, DEFAULT_HOUR)

    fun getMinute(context: Context): Int =
        prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE)

    /** Master on/off switch for reminder notifications, set from Settings. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
