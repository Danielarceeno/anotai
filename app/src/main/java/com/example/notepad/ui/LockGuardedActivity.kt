package com.example.notepad.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.example.notepad.security.LockState
import com.example.notepad.security.SecurityPrefs

/**
 * Base class for every Activity that shows note content or any other in-app screen, except
 * [CalendarActivity] and [LockActivity] themselves (see below). Provides [redirectToLockScreenIfLocked],
 * which a subclass calls at the very top of its own `onCreate` (right after `super.onCreate(...)`,
 * before inflating any view or touching the database) and `onResume` (for any Activity that can
 * sit on screen across an auto-lock timeout elapsing -- i.e. all of them).
 *
 * Before this existed, [CalendarActivity] was the *only* place this check ran -- every other
 * Activity trusted that the user could only have reached it by passing through the calendar's
 * lock gate first. That assumption had two real holes (see DEV_LOG.md Session 13):
 * 1. Sitting in any other screen (a note, Trash, Settings, Drive sync, Folders) while the
 *    auto-lock window elapsed never re-prompted for the PIN on returning to it -- only
 *    `CalendarActivity.onResume` checked, and coming back to a *different* screen never passes
 *    through there.
 * 2. [SearchActivity] and [PickedNoteWidgetConfigActivity] are both `exported="true"` (required
 *    so the system search box / widget host can reach them) and had no check at all -- either
 *    one could be launched directly via an explicit `Intent`, with zero permission required, and
 *    both show a list of notes. A complete bypass of the PIN lock from outside this app entirely.
 *
 * Not applied to [CalendarActivity] itself: it already has its own, slightly different version
 * of this same check (passing along a pending launcher-shortcut action on first launch, which
 * none of these other screens need) and is already covered by its own tests -- left as-is to
 * avoid touching verified, working code for no behavioral gain. Not applied to [LockActivity]
 * itself for the obvious reason (it *is* the lock screen).
 */
abstract class LockGuardedActivity : AppCompatActivity() {

    /** Redirects to [LockActivity] and finishes this Activity if the PIN lock is on and
     *  currently locked. Returns true in that case -- the caller must return immediately
     *  afterward without doing anything else (inflating a view, reading from the database,
     *  binding adapters, ...), the same way [CalendarActivity]'s own check already does. */
    protected fun redirectToLockScreenIfLocked(): Boolean {
        if (SecurityPrefs.isPinEnabled(this) && LockState.isLocked(this)) {
            startActivity(Intent(this, LockActivity::class.java))
            finish()
            return true
        }
        return false
    }
}
