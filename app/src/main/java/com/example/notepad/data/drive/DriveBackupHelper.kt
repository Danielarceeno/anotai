package com.example.notepad.data.drive

import android.content.Context
import com.example.notepad.data.BackupHelper
import com.example.notepad.data.Note
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Backs the notes up to the signed-in Google account's hidden Drive "app data" folder --
 * invisible in the user's regular Drive UI, scoped so this app can only ever see files it
 * created itself. Requires an OAuth client (Android type) for this app's package name + signing
 * certificate to exist in Google Cloud Console with the Drive API enabled; without that, sign-in
 * will fail with a developer-facing error (not something end users can fix on their own).
 *
 * One JSON file, same format as the manual export in [BackupHelper]. Each device remembers (in
 * [lastSyncedTime]) the Drive-reported modified time of the version it last backed up or
 * restored. Before overwriting, [backup] compares that against the backup file's *current*
 * modified time in Drive: if some other device wrote a newer version since this device last
 * synced, that's a conflict -- blindly overwriting would silently throw away that other device's
 * notes. The manual "back up now" flow in SettingsActivity surfaces this as a choice (restore
 * the newer cloud version / overwrite it anyway); the automatic periodic worker just skips that
 * round rather than guessing, leaving the conflict to be resolved next time Settings is opened.
 */
object DriveBackupHelper {
    private const val BACKUP_FILE_NAME = "anotai-backup.json"
    private const val APP_NAME = "Anotaí"
    private const val PERIODIC_WORK_NAME = "drive_periodic_backup"
    private const val PREFS_NAME = "drive_backup_prefs"
    private const val KEY_LAST_SYNCED_MILLIS = "last_synced_millis"

    /** Every 12h, only on a network connection, via [DriveBackupWorker]. Safe to call repeatedly
     *  (e.g. every app launch) -- [ExistingPeriodicWorkPolicy.UPDATE] re-applies these params to
     *  an already-scheduled run (so changing the interval here takes effect for users who had
     *  the previous interval scheduled) without resetting its next run time from scratch. */
    fun schedulePeriodicBackup(context: Context) {
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodicBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    fun signInClient(context: Context): GoogleSignInClient {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE_APPDATA))
            .build()
        return GoogleSignIn.getClient(context, options)
    }

    /** The account last signed in with Drive-appdata consent already granted, or null. */
    fun lastSignedInAccount(context: Context): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        val hasScope = GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE_APPDATA))
        return if (hasScope) account else null
    }

    fun signOut(context: Context, onComplete: () -> Unit) {
        signInClient(context).signOut().addOnCompleteListener { onComplete() }
    }

    /** Epoch millis this device last successfully backed up or restored, 0 if never. Compared
     *  against the Drive file's current modified time to detect another device's newer backup. */
    fun lastSyncedTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNCED_MILLIS, 0L)

    private fun recordSynced(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNCED_MILLIS, millis).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The backup file's modified time in Drive right now, or null if no backup exists yet.
     *  [backup] callers compare this against [lastSyncedTime] to detect a conflict first. */
    suspend fun remoteBackupModifiedTime(context: Context, account: GoogleSignInAccount): Long? {
        return withContext(Dispatchers.IO) {
            findBackupFile(driveService(context, account))?.second
        }
    }

    suspend fun backup(context: Context, account: GoogleSignInAccount, notes: List<Note>) {
        withContext(Dispatchers.IO) {
            val service = driveService(context, account)
            val content = ByteArrayContent("application/json", BackupHelper.toJson(notes).toByteArray())
            val existingId = findBackupFile(service)?.first
            val resultFile = if (existingId != null) {
                service.files().update(existingId, null, content).setFields("modifiedTime").execute()
            } else {
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                service.files().create(metadata, content).setFields("id, modifiedTime").execute()
            }
            resultFile.modifiedTime?.value?.let { recordSynced(context, it) }
        }
    }

    /** Null means no backup file exists yet in Drive; distinct from a parse failure, which
     *  throws so the caller can tell "nothing to restore" from "something went wrong". */
    suspend fun restore(context: Context, account: GoogleSignInAccount): List<Note>? {
        return withContext(Dispatchers.IO) {
            val service = driveService(context, account)
            val (fileId, modifiedTime) = findBackupFile(service) ?: return@withContext null
            val text = service.files().get(fileId).executeMediaAsInputStream().use { it.readBytes().decodeToString() }
            val notes = BackupHelper.fromJson(text) ?: throw IllegalStateException("Drive backup file is not valid JSON")
            recordSynced(context, modifiedTime)
            notes
        }
    }

    /** File id + modified-time (epoch millis) of the backup file, or null if it doesn't exist yet. */
    private fun findBackupFile(service: Drive): Pair<String, Long>? {
        val result = service.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id, modifiedTime)")
            .execute()
        val file = result.files?.firstOrNull() ?: return null
        return file.id to (file.modifiedTime?.value ?: 0L)
    }

    private fun driveService(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_APPDATA))
        credential.selectedAccount = account.account
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()
    }
}
