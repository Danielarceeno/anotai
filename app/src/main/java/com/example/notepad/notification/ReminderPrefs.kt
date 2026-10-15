package com.example.notepad.notification

import android.content.Context

/** The configurable time-of-day for the daily reminder, set from the Settings screen. */
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

    fun setTime(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
    }

    /** Master on/off switch for reminder notifications, set from Settings. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
