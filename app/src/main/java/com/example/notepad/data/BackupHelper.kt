package com.example.notepad.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Plain-JSON export/import of every active note, for manual backups and Google Drive sync
 *  (see [com.example.notepad.data.drive.DriveBackupHelper]) -- no sync server involved either way. */
object BackupHelper {

    fun toJson(notes: List<Note>): String {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject().apply {
                    put("title", note.title)
                    put("body", note.body)
                    put("isFormatted", note.isFormatted)
                    put("folderId", note.folderId)
                    put("colorIndex", note.colorIndex)
                    put("type", note.type)
                    put("dueDate", note.dueDate)
                    put("repetition", note.repetition)
                    put("repeatUntil", note.repeatUntil)
                    put("reminderTimeMinutes", note.reminderTimeMinutes)
                    put("reminderEndTimeMinutes", note.reminderEndTimeMinutes)
                    put("completed", note.completed)
                    put("completedDates", note.completedDates)
                    put("pinned", note.pinned)
                    put("imagePath", note.imagePath)
                    put("modifiedDate", note.modifiedDate)
                    put("uuid", note.uuid)
                }
            )
        }
        return array.toString(2)
    }

    /** Returns null if [text] isn't JSON this function could have produced. */
    fun fromJson(text: String): List<Note>? = try {
        val array = JSONArray(text)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            Note(
                title = obj.getString("title"),
                body = obj.getString("body"),
                isFormatted = obj.optBoolean("isFormatted", false),
                folderId = obj.optLong("folderId", 0L),
                colorIndex = obj.optInt("colorIndex", 0),
                type = obj.optInt("type", Note.TYPE_TEXT),
                dueDate = obj.optLong("dueDate", 0L),
                repetition = obj.optInt("repetition", Note.REPEAT_NONE),
                repeatUntil = obj.optLong("repeatUntil", 0L),
                reminderTimeMinutes = obj.optInt("reminderTimeMinutes", -1),
                reminderEndTimeMinutes = obj.optInt("reminderEndTimeMinutes", -1),
                completed = obj.optBoolean("completed", false),
                completedDates = obj.optString("completedDates", ""),
                pinned = obj.optBoolean("pinned", false),
                imagePath = obj.optString("imagePath", ""),
                modifiedDate = obj.optLong("modifiedDate", System.currentTimeMillis()),
                // Older backups (pre-merge support) won't have a uuid; give each such note its
                // own random one rather than leaving it blank, so it merges as "new" instead of
                // every pre-upgrade note colliding on the same empty-string uuid.
                uuid = obj.optString("uuid").ifBlank { java.util.UUID.randomUUID().toString() }
            )
        }
    } catch (e: Exception) {
        null
    }

    data class BackupData(val notes: List<Note>, val tombstones: Map<String, Long>)

    /** Same note fields as [toJson], plus each device's deletion tombstones (uuid ->
     *  [NoteTombstone.deletedAt]) -- used only for the Google Drive backup file (see
     *  [com.example.notepad.data.drive.DriveBackupHelper]), so a note deleted on one device can
     *  be recognized as deleted by another device that still has it locally and never synced
     *  while the deletion happened (see [NoteTombstone]'s doc for why a local-only tombstone
     *  wasn't enough for that case). Local manual export/import still use the plain array
     *  [toJson]/[fromJson] above. */
    fun toJsonWithTombstones(notes: List<Note>, tombstones: Map<String, Long>): String {
        val tombstoneArray = JSONArray()
        tombstones.forEach { (uuid, deletedAt) ->
            tombstoneArray.put(JSONObject().apply { put("uuid", uuid); put("deletedAt", deletedAt) })
        }
        return JSONObject().apply {
            put("notes", JSONArray(toJson(notes)))
            put("tombstones", tombstoneArray)
        }.toString(2)
    }

    /** Returns null if [text] isn't JSON this function (or legacy [toJson]) could have produced.
     *  A Drive backup file written before tombstone syncing was added is still a plain notes
     *  array -- parsed fine here too, just with no tombstones. */
    fun fromJsonWithTombstones(text: String): BackupData? {
        return try {
            if (text.trimStart().startsWith("[")) {
                fromJson(text)?.let { BackupData(it, emptyMap()) }
            } else {
                val obj = JSONObject(text)
                val notes = fromJson(obj.getJSONArray("notes").toString()) ?: return null
                val tombstones = mutableMapOf<String, Long>()
                val tombstoneArray = obj.optJSONArray("tombstones")
                if (tombstoneArray != null) {
                    for (i in 0 until tombstoneArray.length()) {
                        val t = tombstoneArray.getJSONObject(i)
                        tombstones[t.getString("uuid")] = t.getLong("deletedAt")
                    }
                }
                BackupData(notes, tombstones)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun export(context: Context, uri: Uri, notes: List<Note>) {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(toJson(notes).toByteArray())
        }
    }

    /** Returns null if [uri] isn't a file this function could have produced. */
    fun import(context: Context, uri: Uri): List<Note>? {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: return null
        return fromJson(text)
    }
}
