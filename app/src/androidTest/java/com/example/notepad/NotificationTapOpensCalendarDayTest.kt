package com.example.notepad

import android.Manifest
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.notification.ReminderNotifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar

/**
 * [ReminderNotifierOpensCalendarDayTest] already covers which Activity the notification's
 * `PendingIntent` *targets* (checked via `PendingIntent.FLAG_NO_CREATE`, since "a posted system
 * notification can't be tapped from Espresso -- it lives outside the app's own view hierarchy",
 * per that class's own doc). This is the other half `TESTING.md`'s "Known gaps" asks for: an
 * actual tap on the real, posted notification, through the real notification shade -- outside
 * this app's own window, which is exactly why Espresso can't reach it but
 * [androidx.test.uiautomator] (a different window entirely) can.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapOpensCalendarDayTest {

    @get:Rule
    val permissionRule = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        GrantPermissionRule.grant()
    }

    private lateinit var repository: NoteRepository
    private lateinit var device: UiDevice

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = AppDatabase.getInstance(context)
        repository = NoteRepository(db.noteDao(), context, db.noteTombstoneDao())
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ReminderNotifier.NOTIFICATION_ID)
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        androidx.core.app.NotificationManagerCompat.from(context).cancel(ReminderNotifier.NOTIFICATION_ID)
        // Collapsing the shade explicitly, not just pressHome(), matters here: a later test's
        // Activity launching while the shade (or its close animation) still has window focus was
        // caught failing on a real full-suite run with RootViewWithoutFocusException, even though
        // this class's own tests always passed in isolation.
        device.executeShellCommand("service call statusbar 2")
        device.pressHome()
        device.waitForIdle(5000)
    }

    private fun todayStart(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun tappingTheRealNotification_opensTodaysDayDialog() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uniqueTitle = "Tap-me note ${System.nanoTime()}"
        runBlocking { repository.insert(Note(title = uniqueTitle, body = "", dueDate = todayStart())) }
        runBlocking { ReminderNotifier.checkAndNotify(context) }

        device.openNotification()
        // The title is "[1] <note title>" (see ReminderNotifier.showNotification) -- matching
        // on the note's own (unique, timestamped) title is enough, no need to also match the count.
        assertTrue(
            "the posted notification never showed up in the shade",
            device.wait(Until.hasObject(By.textContains(uniqueTitle)), 5000)
        )
        device.findObject(By.textContains(uniqueTitle)).click()

        // CalendarActivity's day dialog shows the note's title as one of its rows (see
        // DayNotesDialog) -- same text, now inside the app's own (freshly opened) window instead
        // of the notification shade's.
        assertTrue(
            "tapping the notification should have opened today's day dialog with this note in it",
            device.wait(Until.hasObject(By.textContains(uniqueTitle)), 5000)
        )

        // Unwind the dialog/Activity this test just opened *before* @After's pressHome -- caught
        // on a real full-suite run leaving enough residual shade/window-focus state that a
        // *different*, unrelated test launching shortly after (not always the same one) failed
        // with RootViewWithoutFocusException or a dialog silently not opening. Closing explicitly
        // here, on top of @After's own cleanup, narrows that window as much as this test can.
        device.pressBack()
        device.pressBack()
        device.waitForIdle()
    }
}
