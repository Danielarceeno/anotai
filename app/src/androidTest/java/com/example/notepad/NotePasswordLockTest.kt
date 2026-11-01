package com.example.notepad

import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.IdlingRegistry
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.security.PasswordHasher
import com.example.notepad.security.PasswordHasher.toHex
import com.example.notepad.ui.NoteEditorActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A note's own password (see [Note.passwordHash]) is independent of the app-wide PIN: opening a
 * protected note must hide its body until the right password is typed, and keep it hidden on a
 * wrong one. The password-check itself runs off the main thread (PBKDF2 at 120k iterations, see
 * [NoteEditorActivity.tryUnlockNote]), so this registers [NoteEditorActivity.unlockIdlingResource]
 * (a no-op outside this registration -- see that field's doc) instead of polling or sleeping for
 * however long PBKDF2 happens to take on the test device: Espresso then waits for it on its own,
 * the same way it already waits for the main thread to go idle.
 */
@RunWith(AndroidJUnit4::class)
class NotePasswordLockTest {

    private var noteId: Long = 0

    @get:Rule
    val activityRule = ActivityScenarioRule<NoteEditorActivity>(
        run {
            TestPrereqs.resetAppState()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = AppDatabase.getInstance(context)
            val repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
            val salt = PasswordHasher.randomSalt()
            noteId = runBlocking {
                repository.insert(
                    Note(
                        title = "Protected note",
                        body = "secret body",
                        passwordHash = PasswordHasher.hash("letmein", salt),
                        passwordSalt = salt.toHex()
                    )
                )
            }
            android.content.Intent(context, NoteEditorActivity::class.java).apply {
                putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
            }
        }
    )

    @Before
    fun registerIdlingResource() {
        IdlingRegistry.getInstance().register(NoteEditorActivity.unlockIdlingResource)
    }

    @After
    fun unregisterIdlingResource() {
        IdlingRegistry.getInstance().unregister(NoteEditorActivity.unlockIdlingResource)
    }

    @Test
    fun wrongPassword_keepsBodyHidden() {
        onView(withId(R.id.lockOverlay)).check(matches(isDisplayed()))
        onView(withId(R.id.notePasswordInput)).perform(typeText("nope"))
        // Otherwise the still-open IME covers unlockNoteButton and silently swallows the next
        // click (Espresso reports the click as "performed" either way -- it has no feedback that
        // a different window intercepted the touch -- this cost a lot of debugging time).
        closeSoftKeyboard()
        onView(withId(R.id.unlockNoteButton)).perform(click())

        // No manual wait needed here: Espresso's next action/assertion already blocks until
        // unlockIdlingResource (registered above) reports idle, i.e. the background password
        // check has finished and the UI update it drives has actually landed.
        onView(withId(R.id.notePasswordError)).check(matches(isDisplayed()))
        onView(withId(R.id.lockOverlay)).check(matches(isDisplayed()))
    }

    @Test
    fun correctPassword_revealsBody() {
        onView(withId(R.id.lockOverlay)).check(matches(isDisplayed()))
        onView(withId(R.id.notePasswordInput)).perform(typeText("letmein"))
        closeSoftKeyboard()
        onView(withId(R.id.unlockNoteButton)).perform(click())

        onView(withId(R.id.bodyScroll)).check(matches(isDisplayed()))
        onView(withId(R.id.bodyInput)).check(matches(withText("secret body")))
    }
}
