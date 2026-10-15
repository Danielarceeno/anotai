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
                    put("folderId", note.folderId)
                    put("colorIndex", note.colorIndex)
                    put("type", note.type)
                    put("dueDate", note.dueDate)
                    put("repetition", note.repetition)
                    put("repeatUntil", note.repeatUntil)
                    put("completed", note.completed)
                    put("completedDates", note.completedDates)
                    put("pinned", note.pinned)
                    put("imagePath", note.imagePath)
                    put("modifiedDate", note.modifiedDate)
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
                folderId = obj.optLong("folderId", 0L),
                colorIndex = obj.optInt("colorIndex", 0),
                type = obj.optInt("type", Note.TYPE_TEXT),
                dueDate = obj.optLong("dueDate", 0L),
                repetition = obj.optInt("repetition", Note.REPEAT_NONE),
                repeatUntil = obj.optLong("repeatUntil", 0L),
                completed = obj.optBoolean("completed", false),
                completedDates = obj.optString("completedDates", ""),
                pinned = obj.optBoolean("pinned", false),
                imagePath = obj.optString("imagePath", ""),
                modifiedDate = obj.optLong("modifiedDate", System.currentTimeMillis())
            )
        }
    } catch (e: Exception) {
        null
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
