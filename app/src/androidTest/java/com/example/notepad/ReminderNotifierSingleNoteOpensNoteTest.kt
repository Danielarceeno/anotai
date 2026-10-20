package com.example.notepad

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.notification.ReminderNotifier
import com.example.notepad.ui.CalendarActivity
import com.example.notepad.ui.NoteEditorActivity
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for a behavior change: tapping the reminder notification used to always open
 * the calendar's today view, even when exactly one note was due. It now opens that note
 * directly ([ReminderNotifier]'s `showNotification`); with more than one note due there's still
 * no single note to deep-link to, so it keeps falling back to the calendar.
 *
 * A posted system notification can't be tapped from Espresso (it lives outside the app's own
 * view hierarchy), so this checks which Activity its content intent actually targets via
 * [PendingIntent.FLAG_NO_CREATE]: that flag returns an existing PendingIntent only if one was
 * already created with the same request code and a matching [Intent.filterEquals] (which compares
 * the target component, not extras) -- a lookup built against the *other* screen's class
 * reliably comes back null.
 *
 * A PendingIntent stays registered with the system until explicitly cancelled -- it isn't reset
 * by [TestPrereqs.resetAppState] (that only touches this app's own DB/prefs), so without
 * [resetState]'s explicit [PendingIntent.cancel] calls below, a PendingIntent created by an
 * *earlier* test method here (same request code, same target) would still satisfy a
 * [PendingIntent.FLAG_NO_CREATE] lookup in a *later* method regardless of that method's own due
 * notes -- caught on CI as `severalNotesDue_fallsBackToTheCalendar` finding a stale
 * [NoteEditorActivity]-targeting match left over from `exactlyOneNoteDue_opensThatNoteDirectly`.
 */
@RunWith(AndroidJUnit4::class)
class ReminderNotifierSingleNoteOpensNoteTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private lateinit var repository: NoteRepository

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        // See this class's doc comment: a PendingIntent from a previous test method otherwise
        // outlives it and leaks into this one.
        pendingIntentTargeting(context, NoteEditorActivity::class.java)?.cancel()
        pendingIntentTargeting(context, CalendarActivity::class.java)?.cancel()
    }

    private fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun pendingIntentTargeting(context: android.content.Context, target: Class<*>): PendingIntent? =
        PendingIntent.getActivity(
            context, 0, Intent(context, target),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    @Test
    fun exactlyOneNoteDue_opensThatNoteDirectly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository.insert(Note(title = "Only one due", body = "", dueDate = todayStart()))

        ReminderNotifier.checkAndNotify(context)

        assertNotNull(
            "a single due note should deep-link straight to it",
            pendingIntentTargeting(context, NoteEditorActivity::class.java)
        )
    }

    @Test
    fun severalNotesDue_fallsBackToTheCalendar() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository.insert(Note(title = "First", body = "", dueDate = todayStart()))
        repository.insert(Note(title = "Second", body = "", dueDate = todayStart()))

        ReminderNotifier.checkAndNotify(context)

        assertNull(
            "with more than one note due there's no single note to deep-link to",
            pendingIntentTargeting(context, NoteEditorActivity::class.java)
        )
        assertNotNull(
            "multiple due notes should still fall back to the calendar's today view",
            pendingIntentTargeting(context, CalendarActivity::class.java)
        )
    }
}
