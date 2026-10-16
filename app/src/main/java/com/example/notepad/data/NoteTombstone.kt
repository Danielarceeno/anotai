package com.example.notepad.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Records that a note (by its stable [com.example.notepad.data.Note.uuid]) was deleted on this
 * device, so a Google Drive backup merge knows the difference between "never had this note" and
 * "had it, deleted it on purpose" -- without this, [getAllForExport] -- used as the "local" side
 * of [com.example.notepad.data.drive.DriveBackupHelper.mergeByUuid] -- only sees active notes, so
 * a trashed note's uuid looked exactly like a brand new remote-only note and kept getting merged
 * back in (and then re-uploaded to Drive) on every backup/restore.
 *
 * [deletedAt] is compared against the remote copy's `modifiedDate`: a remote edit made *after*
 * this device's deletion still wins (someone else is still using that note), same as any other
 * per-note conflict in the merge.
 */
@Entity(tableName = "note_tombstones")
data class NoteTombstone(
    @PrimaryKey val uuid: String,
    val deletedAt: Long
)
