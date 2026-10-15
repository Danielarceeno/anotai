package com.example.notepad.ui

/**
 * [NoteEditorActivity] deletes and finishes in one step, so there's nowhere on screen to put an
 * "Undo" snackbar at the moment of deletion. This just hands the deleted note's id to whichever
 * list screen (Notes, Search) the user lands back on, which shows the snackbar in its own
 * `onResume` and then clears this so it doesn't reappear on a later resume.
 */
object UndoDeleteState {
    private var pendingNoteId: Long? = null
    private var pendingAt: Long = 0L

    fun recordDeletion(noteId: Long) {
        pendingNoteId = noteId
        pendingAt = System.currentTimeMillis()
    }

    /** Returns the deleted note's id once, only if it happened recently, then clears it. */
    fun consumeRecent(withinMillis: Long = 10_000L): Long? {
        val id = pendingNoteId
        val fresh = id != null && System.currentTimeMillis() - pendingAt <= withinMillis
        pendingNoteId = null
        return if (fresh) id else null
    }
}
