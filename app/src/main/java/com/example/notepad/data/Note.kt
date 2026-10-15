package com.example.notepad.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Mirrors the "notes" table observed in the reference ColorNote APK
 * (columns: title, note, folder_id, active_state, modified_date, color_index),
 * simplified to what this app actually needs.
 */
@Entity(
    tableName = "notes",
    indices = [Index(value = ["folderId", "activeState"]), Index(value = ["dueDate"])]
)
data class Note(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val body: String,
    val folderId: Long = 0,
    val activeState: Int = ACTIVE,
    val colorIndex: Int = 0,
    val type: Int = TYPE_TEXT,
    /** Epoch millis of the reminder/calendar date, or 0 when the note has none. */
    val dueDate: Long = 0L,
    val repetition: Int = REPEAT_NONE,
    /** Minutes since midnight for an exact-time reminder (e.g. 870 = 14:30), or -1 for "all day"
     *  (the default daily 9am catch-all notification is the only thing that covers it). Kept
     *  separate from [dueDate], which must always stay start-of-day: [RecurrenceUtils] and the
     *  calendar's day matching both compare [dueDate] for exact equality against another
     *  start-of-day value. */
    val reminderTimeMinutes: Int = -1,
    /** Start-of-day millis after which a repeating note stops recurring, or 0 to repeat forever. */
    val repeatUntil: Long = 0L,
    /** Marked done from the calendar day popup; independent of per-item checklist checks.
     *  Only meaningful for non-repeating notes -- repeating ones use [completedDates]. */
    val completed: Boolean = false,
    /** For repeating notes: comma-separated start-of-day millis of occurrences marked done,
     *  so completing one occurrence doesn't complete every future/past one. */
    val completedDates: String = "",
    /** Pinned notes sort first in the notes list. */
    val pinned: Boolean = false,
    /** Absolute path to an attached photo, copied into internal storage, or "" for none. */
    val imagePath: String = "",
    val modifiedDate: Long = System.currentTimeMillis(),
    /** Whether [body] is HTML (bold/italic/underline spans, via android.text.Html) rather than
     *  plain text. Kept as an explicit flag instead of sniffing the content for tags so a plain
     *  note whose text happens to contain a literal '<' or '&' is never misread as markup -- see
     *  [com.example.notepad.ui.NoteFormatting]. Checklist items are always plain text. */
    val isFormatted: Boolean = false,
    /** Stable across devices/reinstalls, unlike [id] (which is a local autoincrement and can
     *  collide between two independently-installed devices). Used to match "the same note" when
     *  merging a Google Drive backup against this device's own notes -- see
     *  [com.example.notepad.data.drive.DriveBackupHelper.mergeByUuid]. */
    val uuid: String = java.util.UUID.randomUUID().toString()
) {
    companion object {
        const val ACTIVE = 0
        const val TRASHED = 1

        const val TYPE_TEXT = 0
        const val TYPE_CHECKLIST = 1

        const val REPEAT_NONE = 0
        const val REPEAT_DAILY = 1
        const val REPEAT_EVERY_WEEKDAY = 2
        const val REPEAT_WEEKLY = 3
        const val REPEAT_BIWEEKLY = 4
        const val REPEAT_MONTHLY = 5
        const val REPEAT_YEARLY = 6
    }
}
