package com.example.notepad

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.example.notepad.data.AppDatabase
import com.example.notepad.ui.ThemePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Applies the saved light/dark mode choice before any Activity is created, so the first
 *  screen on a cold start already renders in the right theme instead of flashing system
 *  default then switching. */
class NotepadApplication : Application() {

    companion object {
        /** For work that must finish even if the Activity that started it is destroyed right
         *  after -- see [com.example.notepad.ui.NoteEditorActivity.saveIfNeeded]'s doc. Using
         *  `lifecycleScope` for that save used to be a real risk: it's only ever triggered from
         *  `onPause()`, and the normal exit path (done button / back press) calls `finish()`
         *  *before* `onPause()` runs, so `onDestroy()` -- which cancels `lifecycleScope` -- can
         *  land while the save's suspend DB write is still in flight, silently dropping it (and
         *  the reminder-scheduling/Drive-sync-trigger that was supposed to run right after).
         *  [SupervisorJob] so one failed save can't cancel any other work sharing this scope. */
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onCreate() {
        super.onCreate()
        enableStrictModeIfDebuggable()
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

    /**
     * Per the Fase 7 mission: logs (never crashes, `penaltyLog` not `penaltyDeath`) any disk or
     * network I/O that happens to land on the main thread, in every debug build -- including
     * instrumented test runs, which always use the debug build variant. Checking via
     * [ApplicationInfo.FLAG_DEBUGGABLE] rather than a generated `BuildConfig.DEBUG` field, since
     * `buildFeatures.buildConfig` isn't enabled in this project. `penaltyLog` (not `penaltyDeath`)
     * on purpose: this is an always-on diagnostic aid for every debug run and manual test pass,
     * not a hard gate that could crash an otherwise-fine debug session over an already-accepted
     * I/O path (e.g. SQLCipher's `loadLibs`/migration-check, which genuinely cannot be avoided on
     * first run) -- a real one-off violation test instead builds its own narrower,
     * `penaltyDeath`-scoped policy around just the one path it cares about.
     *
     * Deliberately not `detectDiskReads()`: `Context.getSharedPreferences()` does its own
     * internal `File.exists()` disk stat on *every* call, not just the first -- inherent to the
     * platform API itself (confirmed by actually trying this with it enabled, which flagged
     * `SecurityPrefs.isPinEnabled`'s very first call), not a bug anywhere in this app, and true of
     * virtually any Android app using `SharedPreferences` the normal way. Leaving it on would just
     * drown out genuinely actionable log lines under constant, expected noise from every single
     * `*Prefs` access throughout the app's whole lifetime.
     */
    private fun enableStrictModeIfDebuggable() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
    }
}
