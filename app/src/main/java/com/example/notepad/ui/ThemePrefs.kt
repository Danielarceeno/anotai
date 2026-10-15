package com.example.notepad.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** The user's light/dark mode choice from Settings, applied on every process start
 *  (see [com.example.notepad.NotepadApplication]) and whenever it's changed. */
object ThemePrefs {
    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    private const val PREFS_NAME = "theme_prefs"
    private const val KEY_MODE = "mode"

    fun getMode(context: Context): Int =
        prefs(context).getInt(KEY_MODE, MODE_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
        applyMode(mode)
    }

    fun applyCurrent(context: Context) {
        applyMode(getMode(context))
    }

    private fun applyMode(mode: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
