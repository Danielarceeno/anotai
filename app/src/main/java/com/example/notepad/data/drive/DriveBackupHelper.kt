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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.notepad.data.NoteRepository
import com.example.notepad.notification.NoteReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val IMMEDIATE_WORK_NAME = "drive_immediate_backup"
    private const val PREFS_NAME = "drive_backup_prefs"
    private const val KEY_INTERVAL_HOURS = "interval_hours"
    private const val DEFAULT_INTERVAL_HOURS = 12
    private const val KEY_LAST_SYNC_MILLIS = "last_sync_millis"
    private const val KEY_LAST_SYNC_NOTE_COUNT = "last_sync_note_count"

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

    /** Runs [DriveBackupWorker] once, right away (network permitting) instead of waiting for the
     *  next periodic tick -- called after creating a note so it doesn't sit unsynced for up to
     *  [getIntervalHours]. [ExistingWorkPolicy.REPLACE]: several notes created in quick succession
     *  only need the latest request to actually run, since each one is a full resync anyway. A
     *  no-op (the worker itself checks) if nobody's signed in to Drive. */
    fun triggerImmediateBackup(context: Context) {
        val request = OneTimeWorkRequestBuilder<DriveBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** When [backup] last actually uploaded something, or 0 if never. */
    fun getLastSyncMillis(context: Context): Long = prefs(context).getLong(KEY_LAST_SYNC_MILLIS, 0L)

    /** How many notes were in the merged set as of the last successful [backup]. */
    fun getLastSyncNoteCount(context: Context): Int = prefs(context).getInt(KEY_LAST_SYNC_NOTE_COUNT, 0)

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
     *
     * [local] only ever contains *active* notes ([com.example.notepad.data.NoteRepository
     * .getAllForExport]), so a note this device trashed looks identical here to one it never
     * had at all -- without [tombstones] (uuid -> when it was deleted; pass
     * [com.example.notepad.data.NoteRepository.mergeTombstones]'s result, not just
     * [com.example.notepad.data.NoteRepository.getTombstones], so a deletion made on *another*
     * device is caught too, not only this one's own) a deleted note's remote copy would get
     * merged back in as "remote-only" on every single backup, forever. A tombstoned uuid is
     * dropped unless the surviving copy was modified *after* the deletion (someone is still
     * actively using that note), in which case it still wins through, same as any other
     * newer-edit-wins conflict here. That drop applies both to a remote-only note (never had it
     * tombstoned locally, e.g. deleted on a third device) and to a note still active in [local]
     * (deleted on some *other* device after this one last synced, so this device never got the
     * chance to trash it and record its own tombstone) -- [MergeResult.locallyDeletedIds] is the
     * latter case, for the caller to actually trash locally since this function only computes
     * the merge, it doesn't touch the database.
     */
    fun mergeByUuid(local: List<Note>, remote: List<Note>, tombstones: Map<String, Long> = emptyMap()): MergeResult {
        val remoteByUuid = remote.associateBy { it.uuid }
        val locallyDeletedIds = mutableListOf<Long>()
        val merged = local.mapNotNull { localNote ->
            val remoteNote = remoteByUuid[localNote.uuid]
            val winner = if (remoteNote != null && remoteNote.modifiedDate > localNote.modifiedDate) {
                remoteNote.copy(id = localNote.id)
            } else {
                localNote
            }
            val deletedAt = tombstones[localNote.uuid]
            if (deletedAt != null && deletedAt >= winner.modifiedDate) {
                locallyDeletedIds.add(localNote.id)
                null
            } else {
                winner
            }
        }
        val localUuids = local.map { it.uuid }.toSet()
        // A remote-only note's `id` is some *other* device's local row id -- meaningless (and
        // potentially collision-prone) here, so it's reset to 0 like any other not-yet-inserted
        // Note, consistent with what a remote-wins match above already does via .copy(id = ...).
        //
        // distinctBy uuid: if [remote] itself has more than one entry for the same uuid (e.g. an
        // older, pre-merge backup file that already had a duplicate note in it), every single one
        // not yet matched by a local uuid used to pass this filter and get inserted as its own
        // new note -- restoring onto a device that had neither copy yet duplicated every such
        // note. The main merge loop above never had this problem since it's keyed one-per-local-
        // note already; this is the one place a raw pass over [remote] needed the same guarantee.
        val remoteOnly = remote
            .filter { it.uuid !in localUuids }
            .filterNot { note -> tombstones[note.uuid]?.let { it >= note.modifiedDate } == true }
            .distinctBy { it.uuid }
            .map { it.copy(id = 0L) }
        return MergeResult(merged + remoteOnly, locallyDeletedIds)
    }

    /** [notes] is what the caller should upload/apply as the new merged state; [locallyDeletedIds]
     *  are local row ids (always from [local] in [mergeByUuid], so always a real, already-assigned
     *  id, never 0) that need [com.example.notepad.data.NoteRepository.trashLocally] called on
     *  them to actually apply a deletion this device only just learned about from another device. */
    data class MergeResult(val notes: List<Note>, val locallyDeletedIds: List<Long>)

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

    /** Serializes every call to [syncNow] process-wide -- see its doc for why. */
    private val syncMutex = Mutex()

    data class SyncResult(val notes: List<Note>, val addedCount: Int, val hadRemoteBackup: Boolean)

    /**
     * The one place every Drive sync path goes through: the manual "Sync"/"Restore" buttons in
     * [com.example.notepad.ui.DriveSyncActivity], the first-sign-in backup, and
     * [DriveBackupWorker]'s periodic and immediate (triggered right after creating a note) runs.
     * A single process-wide [Mutex] serializes them.
     *
     * Without it, two syncs running at the same moment -- e.g. the periodic worker firing right
     * as a just-created note's [triggerImmediateBackup] run kicks off, or that happening while
     * the user is also sitting on the sync screen tapping the button -- could each call [restore]
     * before either has called [backup] with its own result, so both independently decide the
     * same remote-only note needs inserting locally and both call [NoteRepository.insert] for it:
     * two local rows, same uuid, genuinely duplicated. [mergeByUuid]'s own `distinctBy` only
     * guards against a single read seeing a duplicate *within* one remote file; it can't do
     * anything about two overlapping reads/writes racing each other.
     */
    suspend fun syncNow(context: Context, repository: NoteRepository, account: GoogleSignInAccount): SyncResult =
        syncMutex.withLock {
            val local = repository.getAllForExport()
            val remoteData = restore(context, account)
            val merged = if (remoteData != null) {
                val tombstones = repository.mergeTombstones(remoteData.tombstones)
                val result = mergeByUuid(local, remoteData.notes, tombstones)
                applyMergeResult(context, repository, result)
                result.notes
            } else {
                local
            }
            backup(context, account, merged, repository.getTombstones())
            SyncResult(merged, merged.size - local.size, hadRemoteBackup = remoteData != null)
        }

    /** Applies a [mergeByUuid] result locally: a merged note that kept an existing local id is an
     *  update (whether its content changed or not, which is cheap and simpler than diffing); one
     *  with `id == 0` is a note this device didn't have yet. [MergeResult.locallyDeletedIds] are
     *  trashed too -- notes this device still had active, but that the merge found a newer
     *  tombstone for from another device. */
    private suspend fun applyMergeResult(context: Context, repository: NoteRepository, result: MergeResult) {
        for (note in result.notes) {
            val saved = if (note.id == 0L) note.copy(id = repository.insert(note)) else note.also { repository.update(it) }
            NoteReminderScheduler.schedule(context, saved)
        }
        for (id in result.locallyDeletedIds) {
            repository.trashLocally(id)
            NoteReminderScheduler.cancel(context, id)
        }
    }

    suspend fun backup(context: Context, account: GoogleSignInAccount, notes: List<Note>, tombstones: Map<String, Long>) {
        withContext(Dispatchers.IO) {
            val service = driveService(context, account)
            val content = ByteArrayContent("application/json", BackupHelper.toJsonWithTombstones(notes, tombstones).toByteArray())
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
        prefs(context).edit()
            .putLong(KEY_LAST_SYNC_MILLIS, System.currentTimeMillis())
            .putInt(KEY_LAST_SYNC_NOTE_COUNT, notes.size)
            .apply()
    }

    /** Null means no backup file exists yet in Drive; distinct from a parse failure, which
     *  throws so the caller can tell "nothing to restore" from "something went wrong". */
    suspend fun restore(context: Context, account: GoogleSignInAccount): BackupHelper.BackupData? {
        return withContext(Dispatchers.IO) {
            val service = driveService(context, account)
            val fileId = findBackupFile(service) ?: return@withContext null
            val text = service.files().get(fileId).executeMediaAsInputStream().use { it.readBytes().decodeToString() }
            BackupHelper.fromJsonWithTombstones(text) ?: throw IllegalStateException("Drive backup file is not valid JSON")
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
