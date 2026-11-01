package com.example.notepad.data.drive

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository

/**
 * Runs in the background on whatever schedule [DriveBackupHelper.schedulePeriodicBackup] sets
 * up. A no-op (success) if nobody's signed in to Drive -- this worker stays scheduled
 * regardless of sign-in state, so there's nothing else to gate it on, and succeeding keeps
 * WorkManager from retrying something there's nothing to do for.
 */
class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val hook = syncHookForTesting
            if (hook != null) {
                hook.invoke()
            } else {
                val account = DriveBackupHelper.lastSignedInAccount(applicationContext) ?: return Result.success()
                val db = AppDatabase.getInstance(applicationContext)
                val repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())
                // Merge (by Note.uuid, newer modifiedDate wins per note) rather than blindly
                // overwrite: another device may have added or edited notes since this device's
                // last sync, and this runs unattended with no UI to ask the user which version
                // should win. syncNow's own Mutex also keeps this from racing a concurrent in-app
                // sync (or the periodic and immediate work requests racing each other) into
                // duplicating notes.
                DriveBackupHelper.syncNow(applicationContext, repository, account)
            }
            Result.success()
        } catch (e: Exception) {
            // Transient (network, token refresh, Drive outage) -- WorkManager will retry with
            // backoff. A permanent failure (revoked scope, deleted account) just keeps failing
            // quietly in the background until the user opens Settings and re-authenticates,
            // which is the same thing they'd have to do to fix it either way.
            Result.retry()
        }
    }

    companion object {
        /** Testability seam: when set, replaces the real sign-in check + [DriveBackupHelper
         *  .syncNow] call entirely -- lets [com.example.notepad.DriveBackupWorkerTest] force the
         *  success and retry paths without a real signed-in Drive account (which an instrumented
         *  test has no way to provide). Null (the real path) outside that one test. */
        @androidx.annotation.VisibleForTesting
        var syncHookForTesting: (suspend () -> Unit)? = null
    }
}
