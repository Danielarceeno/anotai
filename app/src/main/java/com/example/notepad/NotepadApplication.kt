package com.example.notepad

import android.app.Application
import com.example.notepad.data.AppDatabase
import com.example.notepad.ui.ThemePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Applies the saved light/dark mode choice before any Activity is created, so the first
 *  screen on a cold start already renders in the right theme instead of flashing system
 *  default then switching. */
class NotepadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applyCurrent(this)

        // AppDatabase.getInstance's first call does real I/O synchronously on whatever thread
        // calls it (SQLCipher's native lib load, unwrapping the passphrase from Keystore-backed
        // EncryptedSharedPreferences, opening/migrating the SQLite file) -- every Activity in
        // this app calls it directly in onCreate, which previously meant that work landed on the
        // main thread during the launcher Activity's cold start. Triggering it here instead,
        // off the main thread, means that by the time an Activity's onCreate runs moments later,
        // the singleton is either already built or at least already in progress elsewhere.
        CoroutineScope(Dispatchers.IO).launch { AppDatabase.getInstance(this@NotepadApplication) }
    }
}
