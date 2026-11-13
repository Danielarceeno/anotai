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

    private class Entry(val notes: List<Note>, val at: Long)

    @Volatile private var cached: Entry? = null

    /** Bumped by every [invalidate]. A fetch that started before a write and finished after it
     *  holds pre-write data; without this check it would be stored *after* that write's
     *  [invalidate] ran, and then served as fresh for the rest of the TTL. */
    private val generation = java.util.concurrent.atomic.AtomicLong()

    suspend fun get(fetch: suspend () -> List<Note>): List<Note> {
        val snapshot = cached
        if (snapshot != null && System.currentTimeMillis() - snapshot.at < TTL_MILLIS) return snapshot.notes
        val startedAt = generation.get()
        val fresh = fetch()
        if (generation.get() == startedAt) cached = Entry(fresh, System.currentTimeMillis())
        return fresh
    }

    fun invalidate() {
        generation.incrementAndGet()
        cached = null
    }
}
