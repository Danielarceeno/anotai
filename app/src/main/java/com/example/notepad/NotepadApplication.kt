package com.example.notepad

import android.app.Application
import com.example.notepad.ui.ThemePrefs

/** Applies the saved light/dark mode choice before any Activity is created, so the first
 *  screen on a cold start already renders in the right theme instead of flashing system
 *  default then switching. */
class NotepadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applyCurrent(this)
    }
}
