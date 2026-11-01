package com.example.notepad

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.notepad.data.drive.DriveBackupWorker
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per the Fase 5 mission: [DriveBackupWorker]'s `Result.success()`/`Result.retry()` branches,
 * exercised for real via [TestListenableWorkerBuilder] rather than just read from the source.
 * The "signed in, sync throws/succeeds" branches go through [DriveBackupWorker
 * .syncHookForTesting] (see its own doc) since an instrumented test has no way to provide a real
 * signed-in Drive account; the "nobody signed in" branch needs no seam at all -- this device
 * genuinely isn't signed in to Drive during a test run.
 */
@RunWith(AndroidJUnit4::class)
class DriveBackupWorkerTest {

    @Before
    fun resetState() {
        TestPrereqs.resetAppState()
    }

    @After
    fun clearHook() {
        DriveBackupWorker.syncHookForTesting = null
    }

    @Test
    fun nobodySignedIn_succeedsWithoutAttemptingASync() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val worker = TestListenableWorkerBuilder<DriveBackupWorker>(context).build()

        val result = runBlocking { worker.doWork() }

        assertTrue(result is Result.Success)
    }

    @Test
    fun syncHookSucceeding_succeeds() {
        DriveBackupWorker.syncHookForTesting = { /* no-op: simulates a clean sync */ }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val worker = TestListenableWorkerBuilder<DriveBackupWorker>(context).build()

        val result = runBlocking { worker.doWork() }

        assertTrue(result is Result.Success)
    }

    @Test
    fun syncHookThrowing_retries() {
        // Stands in for a transient failure (network, token refresh, Drive outage) -- the worker
        // must ask WorkManager to retry with backoff rather than giving up outright.
        DriveBackupWorker.syncHookForTesting = { throw java.io.IOException("simulated network failure") }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val worker = TestListenableWorkerBuilder<DriveBackupWorker>(context).build()

        val result = runBlocking { worker.doWork() }

        assertTrue(result is Result.Retry)
    }
}
