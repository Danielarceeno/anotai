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
 * One JSON file, same format as the manual export in [BackupHelper]. SettingsActivity's "back up
 * now" / "restore backup" flows both call [mergeByUuid] against whatever's currently in Drive
 * before writing anything, so two devices that each added different notes since the last sync
 * both end up with the union instead of one silently overwriting the other.
 */
object DriveBackupHelper {
    private const val BACKUP_FILE_NAME = "anotai-backup.json"
    private const val APP_NAME = "Anotaí"
    private const val PERIODIC_WORK_NAME = "drive_periodic_backup"
    private const val PREFS_NAME = "drive_backup_prefs"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val DEFAULT_INTERVAL_HOURS = 12

    /** The user's chosen auto-backup interval in hours, set from Settings. WorkManager's own
     *  minimum for periodic work is 15 minutes, well under any of the offered choices. */
    fun getIntervalHours(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL_HOURS, DEFAULT_INTERVAL_HOURS)

    fun setIntervalHours(context: Context, hours: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL_HOURS, hours).apply()
    }

    /** Every [getIntervalHours] hours, only on a network connection, via [DriveBackupWorker].
     *  Safe to call repeatedly (e.g. every app launch, or right after the interval is changed in
     *  Settings) -- [ExistingPeriodicWorkPolicy.UPDATE] re-applies these params to an
     *  already-scheduled run without resetting its next run time from scratch. */
    fun schedulePeriodicBackup(context: Context) {
        val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(getIntervalHours(context).toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPeriodicBackup(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /**
     * Merges a remote backup's notes against this device's own, matched by [Note.uuid] (stable
     * across devices, unlike the local autoincrement [Note.id]) instead of replacing one side
     * wholesale: for a uuid present on both sides, whichever copy has the newer [Note.modifiedDate]
     * wins; a uuid present on only one side is just kept. The winning remote copy is returned with
     * the matching local row's [Note.id] substituted in, so applying the result locally is a plain
     * update instead of a delete-and-reinsert; a uuid that doesn't exist locally yet keeps its
     * (local-storage-absent) `id = 0`, which the caller inserts as a new row.
     */
    fun mergeByUuid(local: List<Note>, remote: List<Note>): List<Note> {
        val remoteByUuid = remote.associateBy { it.uuid }
        val merged = local.map { localNote ->
            val remoteNote = remoteByUuid[localNote.uuid] ?: return@map localNote
            if (remoteNote.modifiedDate > localNote.modifiedDate) remoteNote.copy(id = localNote.id) else localNote
        }
        val localUuids = local.map { it.uuid }.toSet()
        // A remote-only note's `id` is some *other* device's local row id -- meaningless (and
        // potentially collision-prone) here, so it's reset to 0 like any other not-yet-inserted
        // Note, consistent with what a remote-wins match above already does via .copy(id = ...).
        val remoteOnly = remote.filter { it.uuid !in localUuids }.map { it.copy(id = 0L) }
        return merged + remoteOnly
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun backup(context: Context, account: GoogleSignInAccount, notes: List<Note>) {
        withContext(Dispatchers.IO) {
            val service = driveService(context, account)
            val content = ByteArrayContent("application/json", BackupHelper.toJson(notes).toByteArray())
            val existingId = findBackupFile(service)
            if (existingId != null) {
                service.files().update(existingId, null, content).execute()
            } else {
                val metadata = com.google.api.services.drive.model.File().apply {
                    name = BACKUP_FILE_NAME
                    parents = listOf("appDataFolder")
                }
                service.files().create(metadata, content).execute()
            }
        }
    }

    /** Null means no backup file exists yet in Drive; distinct from a parse failure, which
     *  throws so the caller can tell "nothing to restore" from "something went wrong". */
    suspend fun restore(context: Context, account: GoogleSignInAccount): List<Note>? {
        return withContext(Dispatchers.IO) {
            val service = driveService(context, account)
            val fileId = findBackupFile(service) ?: return@withContext null
            val text = service.files().get(fileId).executeMediaAsInputStream().use { it.readBytes().decodeToString() }
            BackupHelper.fromJson(text) ?: throw IllegalStateException("Drive backup file is not valid JSON")
        }
    }

    /** The backup file's id, or null if it doesn't exist yet. */
    private fun findBackupFile(service: Drive): String? {
        val result = service.files().list()
            .setSpaces("appDataFolder")
            .setQ("name = '$BACKUP_FILE_NAME'")
            .setFields("files(id)")
            .execute()
        return result.files?.firstOrNull()?.id
    }

    private fun driveService(context: Context, account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_APPDATA))
        credential.selectedAccount = account.account
        return Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), credential)
            .setApplicationName(APP_NAME)
            .build()
    }
}
