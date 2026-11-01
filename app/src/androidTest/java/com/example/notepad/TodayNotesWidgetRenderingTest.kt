package com.example.notepad

import android.content.Intent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.security.PasswordHasher
import com.example.notepad.security.PasswordHasher.toHex
import com.example.notepad.widget.TodayNotesRemoteViewsService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * Renders [TodayNotesRemoteViewsService]'s per-row [android.widget.RemoteViews] for real (via
 * `RemoteViews.apply`, one of the two approaches `TESTING.md`'s "Known gaps" names as an
 * alternative to a full `AppWidgetHost` placement) and inspects the actual inflated row views --
 * title/color logic that was previously verified only by code review.
 *
 * Deliberately out of scope here (left as a known gap, see `TESTING.md`): the per-row
 * `fillInIntent` itself (`CalendarActivity.EXTRA_OPEN_DAY_MILLIS`) isn't inspectable this way --
 * `setOnClickFillInIntent` records an action the *system's* widget host combines with the
 * collection's `PendingIntentTemplate` at click time; a plain `apply()` call never does that, and
 * extracting it would mean reflecting into `RemoteViews`' own private action list, which is
 * exactly the kind of brittle, AOSP-internals-dependent test this project avoids elsewhere (no
 * Robolectric/mocking). Likewise the compact/full layout switch
 * (`TodayNotesWidgetProvider.isCompactSize`/`onAppWidgetOptionsChanged`) needs a widget size the
 * system actually reports for a *bound* widget id -- `AppWidgetManager.updateAppWidgetOptions` on
 * an unbound id (no real `AppWidgetHost` placement) is a no-op.
 */
@RunWith(AndroidJUnit4::class)
class TodayNotesWidgetRenderingTest {

    private lateinit var repository: NoteRepository
    private lateinit var context: android.content.Context

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
    }

    private fun todayStartMillis(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun blankTitleTextNote_fallsBackToItsBodyPreview() {
        val note = seedNoteDueToday(title = "", body = "A plain text body", type = Note.TYPE_TEXT)
        assertRowTitle(note.id, "A plain text body")
    }

    @Test
    fun blankTitleChecklist_fallsBackToTheChecklistTypeLabel() {
        val note = seedNoteDueToday(
            title = "", body = ChecklistItem.serialize(listOf(ChecklistItem("milk", false))), type = Note.TYPE_CHECKLIST
        )
        assertRowTitle(note.id, context.getString(R.string.note_type_checklist))
    }

    @Test
    fun blankTitleLockedNote_showsTheLockedPlaceholder_neverTheRealBody() {
        val salt = PasswordHasher.randomSalt()
        val note = seedNoteDueToday(
            title = "", body = "this must never show up on the home screen", type = Note.TYPE_TEXT,
            passwordHash = PasswordHasher.hash("secret", salt), passwordSalt = salt.toHex()
        )
        assertRowTitle(note.id, context.getString(R.string.note_locked_preview))
    }

    @Test
    fun titledNote_showsItsOwnTitle_notAFallback() {
        val note = seedNoteDueToday(title = "A real title", body = "irrelevant body", type = Note.TYPE_TEXT)
        assertRowTitle(note.id, "A real title")
    }

    @Test
    fun rowDotColor_isSetForACustomColoredNote() {
        // PorterDuffColorFilter (what setColorFilter(Int) produces) has no public color accessor
        // on this compileSdk, so this only confirms *a* filter actually got applied -- resolveArgb
        // itself is already covered directly, by real android.graphics.Color math, in NoteColorsTest.
        val customColor = android.graphics.Color.rgb(0x11, 0x22, 0x33)
        val note = seedNoteDueToday(title = "Custom color note", body = "", customColorArgb = customColor)

        val (row, _) = rowFor(note.id)
        val dot = row.findViewById<ImageView>(R.id.itemDot)
        assertTrue(dot.colorFilter != null)
    }

    @Test
    fun hasStableIds_andCountMatchesSeededNotes() {
        val factory = newFactory()
        seedNoteDueToday(title = "One", body = "")
        seedNoteDueToday(title = "Two", body = "")
        factory.onDataSetChanged()

        assertTrue(factory.hasStableIds())
        assertEquals(2, factory.count)
    }

    private fun seedNoteDueToday(
        title: String,
        body: String,
        type: Int = Note.TYPE_TEXT,
        passwordHash: String = "",
        passwordSalt: String = "",
        customColorArgb: Int? = null
    ): Note {
        val id = runBlocking {
            repository.insert(
                Note(
                    title = title, body = body, type = type, dueDate = todayStartMillis(),
                    passwordHash = passwordHash, passwordSalt = passwordSalt, customColorArgb = customColorArgb
                )
            )
        }
        return runBlocking { repository.getById(id) }!!
    }

    private fun newFactory(): android.widget.RemoteViewsService.RemoteViewsFactory {
        // A plain `TodayNotesRemoteViewsService()` was never handed a Context by the system
        // (that normally happens via attachBaseContext() right before onCreate(), which only the
        // real Service lifecycle -- never exercised here -- triggers), so getApplicationContext()
        // would NPE. Reflection onto the (protected) ContextWrapper method is the only way to
        // supply one without a full ServiceTestRule bind, which this Service isn't designed to be
        // bound as anyway (it only exists for RemoteViewsService's own system-driven contract).
        val service = TodayNotesRemoteViewsService()
        val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", android.content.Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)

        val factory = service.onGetViewFactory(Intent())
        factory.onCreate()
        return factory
    }

    /** Finds [noteId]'s row by scanning every position -- entries aren't guaranteed to come back
     *  in insertion order, and asserting on a specific index would overfit to that ordering. */
    private fun rowFor(noteId: Long): Pair<android.view.View, android.widget.RemoteViewsService.RemoteViewsFactory> {
        val factory = newFactory()
        factory.onDataSetChanged()
        for (position in 0 until factory.count) {
            if (factory.getItemId(position) == noteId) {
                val remoteViews = factory.getViewAt(position)
                val row = remoteViews.apply(context, FrameLayout(context))
                return row to factory
            }
        }
        throw AssertionError("No widget row found for note id $noteId among ${factory.count} entries")
    }

    private fun assertRowTitle(noteId: Long, expectedTitle: String) {
        val (row, _) = rowFor(noteId)
        assertEquals(expectedTitle, row.findViewById<TextView>(R.id.itemTitle).text.toString())
    }
}
