package com.example.notepad

import androidx.core.app.NotificationManagerCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.notification.ReminderNotifier
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Shared setup so every test starts from a clean, unlocked app state, regardless of what a
 *  previous manual run or test left behind on the device. */
object TestPrereqs {
    fun resetAppState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.clearPin(context)
        // Not implied by clearPin: a test that changes this (e.g. to make LockState.isLocked()
        // deterministic without Thread.sleep) would otherwise leak a non-default value into every
        // later test in the same instrumentation run that sets a PIN, sending them to the lock
        // screen instead of whatever they were actually testing.
        SecurityPrefs.setAutoLockMinutes(context, SecurityPrefs.DEFAULT_AUTO_LOCK_MINUTES)
        LockState.markUnlocked()
        AppDatabase.getInstance(context).clearAllTables()
        // The reminder notification is real OS state, not reset by clearing this app's own DB/
        // prefs above -- any test that inserts a note due today and resumes CalendarActivity
        // (its onResume always runs an immediate reminder check) can post one for real, and it
        // then outlives that test. Cancelling it here, for every test, keeps a notification
        // posted by one test from tripping up a later one's "nothing showing yet" assumption.
        NotificationManagerCompat.from(context).cancel(ReminderNotifier.NOTIFICATION_ID)
    }

    /** Same reset as [resetAppState], but as a [TestRule] so it can be chained to run *before* an
     *  [androidx.test.ext.junit.rules.ActivityScenarioRule] launches its Activity -- a plain
     *  `@Before` method runs too late for that, since rules wrap `@Before`/`@Test` together. */
    fun resetStateRule(): TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                resetAppState()
                base.evaluate()
            }
        }
    }
}
