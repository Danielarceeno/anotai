package com.example.notepad

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.StrictMode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.data.AppDatabase
import com.example.notepad.ui.CalendarActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 7 mission: no disk or network I/O should land on the main thread, especially
 * around opening the database and the first frame. [AppDatabase]'s singleton is deliberately
 * pre-warmed here (see [TestPrereqs]) *before* the strict policy is installed -- that one-time
 * build (SQLCipher's native lib load, unwrapping the Keystore-backed passphrase, opening/
 * migrating the file) is real, synchronous I/O that genuinely has to happen somewhere and is
 * already a known, accepted cost ([com.example.notepad.NotepadApplication]'s own doc); this test
 * is about everything *after* that -- a normal `CalendarActivity` launch and interaction against
 * an already-open database -- staying off the main thread, with a hard `penaltyDeath` instead of
 * just a log line, since that's the only way a CI run would actually fail on a regression here.
 * The policy is installed on the main thread itself ([StrictMode.ThreadPolicy] is per-thread, so
 * setting it from this test's own instrumentation thread would check nothing real).
 *
 * Deliberately not `detectDiskReads()`: `Context.getSharedPreferences()` does its own internal
 * `File.exists()` disk stat on *every* call (not just the first -- confirmed by actually running
 * this with it enabled, which crashed inside `SecurityPrefs.isPinEnabled`'s very first call). That
 * isn't a bug introduced anywhere in this app; it's inherent to the platform API itself, and true
 * of virtually any Android app using `SharedPreferences` the normal way -- fixing it would mean
 * every `*Prefs` object in this app caching its own `SharedPreferences` instance instead of
 * fetching it fresh each call, a real but separate refactor, not a performance regression this
 * test should treat as one. `detectDiskWrites()`/`detectNetwork()` stay on: those are far less
 * expected on the main thread and would catch something actually worth fixing.
 */
@RunWith(AndroidJUnit4::class)
class StrictModeMainThreadIOTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private var originalPolicy: StrictMode.ThreadPolicy? = null

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { AppDatabase.getInstance(context) }
        // StrictMode.ThreadPolicy is per-thread -- setting it from this (instrumentation) thread
        // would do nothing for the app's actual main thread, where Activity lifecycle and all UI
        // code runs. Has to be installed *on* the main thread to mean anything here.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            originalPolicy = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyDeath()
                    .build()
            )
        }
    }

    @After
    fun tearDown() {
        // Not scoped per-test otherwise: a policy left in place here would make some *other*,
        // unrelated test's main-thread I/O crash it too, for a reason that test has no way to see.
        val toRestore = originalPolicy
        if (toRestore != null) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                StrictMode.setThreadPolicy(toRestore)
            }
        }
    }

    @Test
    fun openingTheCalendarAndTodaysDayDialog_doesNoMainThreadIO() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, CalendarActivity::class.java).putExtra(CalendarActivity.EXTRA_OPEN_TODAY, true)
        ActivityScenario.launch<CalendarActivity>(intent).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }
}
