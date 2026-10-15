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
        val account = DriveBackupHelper.lastSignedInAccount(applicationContext) ?: return Result.success()
        return try {
            val remoteTime = DriveBackupHelper.remoteBackupModifiedTime(applicationContext, account)
            if (remoteTime != null && remoteTime > DriveBackupHelper.lastSyncedTime(applicationContext)) {
                // Another device wrote a newer backup since this device last synced. There's no
                // UI to ask the user which version wins from the background, so skip this round
                // rather than guessing (silently overwriting would throw that device's notes
                // away) -- the next manual "back up now" in Settings detects the same conflict
                // and asks.
                return Result.success()
            }
            val repository = NoteRepository(AppDatabase.getInstance(applicationContext).noteDao(), applicationContext)
            DriveBackupHelper.backup(applicationContext, account, repository.getAllForExport())
            Result.success()
        } catch (e: Exception) {
            // Transient (network, token refresh, Drive outage) -- WorkManager will retry with
            // backoff. A permanent failure (revoked scope, deleted account) just keeps failing
            // quietly in the background until the user opens Settings and re-authenticates,
            // which is the same thing they'd have to do to fix it either way.
            Result.retry()
        }
    }
}
