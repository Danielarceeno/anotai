package com.example.notepad

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.notepad.data.drive.DriveBackupHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 5 mission: [DriveBackupHelper.schedulePeriodicBackup]/[DriveBackupHelper
 * .triggerImmediateBackup] actually enqueue the request they document enqueuing -- a real
 * [WorkManager] (the test variant, so this doesn't touch the device's real scheduler), not just
 * read from the source.
 */
@RunWith(AndroidJUnit4::class)
class DriveBackupScheduleTest {

    private lateinit var context: android.content.Context
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        TestPrereqs.resetAppState()
        context = ApplicationProvider.getApplicationContext()
        val config = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
    }

    @Test
    fun schedulePeriodicBackup_enqueuesAUniquePeriodicRequestOnANetworkConstraint() {
        DriveBackupHelper.schedulePeriodicBackup(context)

        val infos = workManager.getWorkInfosForUniqueWork("drive_periodic_backup").get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING)
        assertEquals(setOf(NetworkType.CONNECTED), info.constraints.requiredNetworkType.let { setOf(it) })
    }

    @Test
    fun triggerImmediateBackup_enqueuesAUniqueOneTimeRequest() {
        DriveBackupHelper.triggerImmediateBackup(context)

        val infos = workManager.getWorkInfosForUniqueWork("drive_immediate_backup").get()
        assertEquals(1, infos.size)
    }

    @Test
    fun triggerImmediateBackup_calledTwice_replacesRatherThanStacking() {
        // ExistingWorkPolicy.REPLACE: several notes created in quick succession should only
        // leave the latest request queued, not one per note created.
        DriveBackupHelper.triggerImmediateBackup(context)
        DriveBackupHelper.triggerImmediateBackup(context)

        val infos = workManager.getWorkInfosForUniqueWork("drive_immediate_backup").get()
        assertEquals(1, infos.size)
    }

    @Test
    fun cancelPeriodicBackup_removesTheScheduledWork() {
        DriveBackupHelper.schedulePeriodicBackup(context)
        DriveBackupHelper.cancelPeriodicBackup(context)

        val infos = workManager.getWorkInfosForUniqueWork("drive_periodic_backup").get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }
}
