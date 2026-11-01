package com.example.notepad

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.notepad.data.Note
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs
import com.example.notepad.ui.LockActivity
import com.example.notepad.ui.NoteEditorActivity
import com.example.notepad.ui.SearchActivity
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for the Fase 6 bug (see `DEV_LOG.md` Session 13): the app's PIN lock was only
 * ever checked in `CalendarActivity.onCreate`/`onResume`. Two concrete holes that left open:
 * 1. [SearchActivity] is `exported="true"` (required so the system search box can reach it) and
 *    had zero PIN check -- any other app on the device could launch it directly and see the full
 *    notes list, a complete bypass of the lock from outside this app entirely.
 * 2. The auto-lock timeout elapsing while sitting in any screen *other* than the calendar never
 *    re-prompted for the PIN on returning to it, since only `CalendarActivity.onResume` checked.
 *
 * `LockGuardedActivity` (the new common base class both of the Activities below now extend)
 * closes both. `SecurityPrefs.setAutoLockMinutes(context, 0)` makes [LockState.isLocked] true
 * deterministically (any elapsed time at all exceeds a 0-minute window) without `Thread.sleep`.
 */
@RunWith(AndroidJUnit4::class)
class LockGuardedActivityTest {

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.clearPin(context)
        // Not reset by TestPrereqs.resetAppState() (only the PIN/LockState are) -- left at 0 by
        // either test here, this otherwise leaks into every later test in the same
        // instrumentation run that sets a PIN, making LockState.isLocked() true almost
        // immediately for them too and sending them to the lock screen instead of what they
        // were actually testing.
        SecurityPrefs.setAutoLockMinutes(context, SecurityPrefs.DEFAULT_AUTO_LOCK_MINUTES)
    }

    private fun currentlyResumedActivity(): Activity? {
        var found: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
        }
        return found
    }

    @Test
    fun launchingSearchActivityDirectly_whilePinLocked_opensTheLockScreenInstead() {
        // Simulates the real exploit: an explicit Intent straight at the exported SearchActivity,
        // never going through CalendarActivity's own gate at all -- exactly what another app on
        // the device could do.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.setPin(context, "1234")
        SecurityPrefs.setAutoLockMinutes(context, 0)
        assertTrue(LockState.isLocked(context))

        val intent = Intent(context, SearchActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        ActivityScenario.launch<SearchActivity>(intent).use {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertTrue(
                "SearchActivity must redirect to LockActivity instead of showing notes when locked",
                currentlyResumedActivity() is LockActivity
            )
        }
    }

    @Test
    fun noteEditorActivity_alreadyOpen_whenTheAutoLockWindowElapses_locksOnTheNextResume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SecurityPrefs.setPin(context, "1234")
        LockState.markUnlocked() // starts out unlocked, same as having just unlocked normally
        SecurityPrefs.setAutoLockMinutes(context, 5)

        val intent = Intent(context, NoteEditorActivity::class.java).apply {
            putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
        }
        ActivityScenario.launch<NoteEditorActivity>(intent).use { scenario ->
            assertTrue("should open normally while unlocked", currentlyResumedActivity() is NoteEditorActivity)

            // The auto-lock window elapses while this exact screen is still the one on top -- no
            // recreation, just coming back to the same instance via onResume (standing in for the
            // app being backgrounded and returned to, the same onPause/onResume pair a real
            // background/foreground cycle goes through).
            SecurityPrefs.setAutoLockMinutes(context, 0)
            assertTrue(LockState.isLocked(context))
            scenario.moveToState(Lifecycle.State.STARTED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            assertTrue(
                "returning to an already-open note after the auto-lock window elapsed must show the lock screen",
                currentlyResumedActivity() is LockActivity
            )
        }
    }
}
