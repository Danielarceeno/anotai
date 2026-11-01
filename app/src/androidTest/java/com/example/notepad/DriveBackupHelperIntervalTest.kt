package com.example.notepad

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.notepad.data.drive.DriveBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [DriveBackupHelper.getIntervalHours]/[setIntervalHours] persistence, independent of the Settings
 * UI row that picks it (`DriveSyncActivity.showBackupFrequencyPicker`) -- that row only shows up
 * signed in to Google Drive, which needs a real account (`TESTING.md`'s separate, already-
 * documented Drive gap), so its own click-through isn't covered here, only the prefs it writes to.
 */
@RunWith(AndroidJUnit4::class)
class DriveBackupHelperIntervalTest {

    @Test
    fun defaultInterval_is12Hours() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("drive_backup_prefs", android.content.Context.MODE_PRIVATE).edit().clear().commit()

        assertEquals(12, DriveBackupHelper.getIntervalHours(context))
    }

    @Test
    fun settingEachOfferedInterval_persists() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (hours in intArrayOf(6, 12, 24)) {
            DriveBackupHelper.setIntervalHours(context, hours)
            assertEquals(hours, DriveBackupHelper.getIntervalHours(context))
        }
    }
}
