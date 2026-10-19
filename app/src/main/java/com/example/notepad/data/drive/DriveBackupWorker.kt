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
            val db = AppDatabase.getInstance(applicationContext)
            val repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())
            // Merge (by Note.uuid, newer modifiedDate wins per note) rather than blindly
            // overwrite: another device may have added or edited notes since this device's last
            // sync, and this runs unattended with no UI to ask the user which version should win.
            val local = repository.getAllForExport()
            val remoteData = DriveBackupHelper.restore(applicationContext, account)
            val merged = if (remoteData != null) {
                val tombstones = repository.mergeTombstones(remoteData.tombstones)
                val result = DriveBackupHelper.mergeByUuid(local, remoteData.notes, tombstones)
                for (id in result.locallyDeletedIds) {
                    repository.trashLocally(id)
                    com.example.notepad.notification.NoteReminderScheduler.cancel(applicationContext, id)
                }
                result.notes
            } else {
                local
            }
            for (note in merged) {
                if (note.id == 0L) repository.insert(note) else repository.update(note)
            }
            DriveBackupHelper.backup(applicationContext, account, merged, repository.getTombstones())
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
