package com.example.notepad

import androidx.test.espresso.Espresso.onView
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A note's own password (see [Note.passwordHash]) is independent of the app-wide PIN: opening a
 * protected note must hide its body until the right password is typed, and keep it hidden on a
 * wrong one. The password-check itself runs off the main thread (PBKDF2 at 120k iterations), so
 * assertions after typing the password poll for a short window instead of checking immediately.
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
    fun unused() {
        // Reset already happened above, before the note was inserted -- ActivityScenarioRule
        // builds its launch Intent (and therefore this block) before any @Before method runs.
    }

    @Test
    fun wrongPassword_keepsBodyHidden() {
        onView(withId(R.id.lockOverlay)).check(matches(isDisplayed()))
        onView(withId(R.id.notePasswordInput)).perform(typeText("nope"))
        onView(withId(R.id.unlockNoteButton)).perform(click())

        pollUntil { isDisplayed_safe(R.id.notePasswordError) }
        onView(withId(R.id.lockOverlay)).check(matches(isDisplayed()))
    }

    @Test
    fun correctPassword_revealsBody() {
        onView(withId(R.id.lockOverlay)).check(matches(isDisplayed()))
        onView(withId(R.id.notePasswordInput)).perform(typeText("letmein"))
        onView(withId(R.id.unlockNoteButton)).perform(click())

        pollUntil { isDisplayed_safe(R.id.bodyScroll) }
        onView(withId(R.id.bodyInput)).check(matches(withText("secret body")))
    }

    private fun isDisplayed_safe(id: Int): Boolean = try {
        onView(withId(id)).check(matches(isDisplayed()))
        true
    } catch (e: Throwable) {
        false
    }

    private fun pollUntil(timeoutMs: Long = 3000, intervalMs: Long = 100, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
    }
}
