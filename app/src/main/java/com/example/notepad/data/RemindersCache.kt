package com.example.notepad.data

/**
 * Short-lived memo of [NoteDao.getAllReminders] (every active note with a due date) -- a single
 * note edit/delete fans out to several independent consumers that each re-derive "what's due"
 * from scratch within milliseconds of each other: [com.example.notepad.notification
 * .ReminderNotifier.checkAndNotify] (explicitly called right after the mutation) and the
 * home-screen widget's [com.example.notepad.widget.TodayNotesRemoteViewsService] (triggered by
 * the same mutation's [com.example.notepad.widget.TodayNotesWidgetProvider.notifyNotesChanged]
 * call) both scan the same table for the same thing at nearly the same moment. A short TTL --
 * not Room's own LiveData/Flow invalidation, which neither of those call sites uses -- lets the
 * second caller in that burst reuse the first one's result instead of hitting the database again.
 * [invalidate] is called from every [NoteRepository] write so this never serves data older than
 * the most recent known mutation.
 */
object RemindersCache {
    private const val TTL_MILLIS = 2000L

    @Volatile private var cached: List<Note>? = null
    @Volatile private var cachedAt: Long = 0L

    suspend fun get(fetch: suspend () -> List<Note>): List<Note> {
        val snapshot = cached
        if (snapshot != null && System.currentTimeMillis() - cachedAt < TTL_MILLIS) return snapshot
        val fresh = fetch()
        cached = fresh
        cachedAt = System.currentTimeMillis()
        return fresh
    }

    fun invalidate() {
        cached = null
    }
}
