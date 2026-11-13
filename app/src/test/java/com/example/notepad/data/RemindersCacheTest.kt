package com.example.notepad.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RemindersCacheTest {

    @Before
    fun resetCache() {
        RemindersCache.invalidate()
    }

    private fun note(title: String) = Note(title = title, body = "")

    @Test
    fun `a second call within the TTL reuses the first result without calling fetch again`() = runBlocking {
        var fetchCount = 0
        val fetch: suspend () -> List<Note> = { fetchCount++; listOf(note("a")) }

        val first = RemindersCache.get(fetch)
        val second = RemindersCache.get(fetch)

        assertEquals(1, fetchCount)
        assertEquals(first, second)
    }

    @Test
    fun `invalidate forces the next call to fetch again`() = runBlocking {
        var fetchCount = 0
        val fetch: suspend () -> List<Note> = { fetchCount++; listOf(note("call $fetchCount")) }

        RemindersCache.get(fetch)
        RemindersCache.invalidate()
        RemindersCache.get(fetch)

        assertEquals(2, fetchCount)
    }

    @Test
    fun `a fetch that a write raced is returned to its caller but never cached`() = runBlocking {
        // A note write (-> invalidate) landing while a fetch is still reading the table: that
        // fetch's rows predate the write. It used to be stored anyway, after the invalidate had
        // already run, and then served as fresh to the very consumers the write notified.
        var fetchCount = 0
        RemindersCache.get {
            fetchCount++
            RemindersCache.invalidate()
            listOf(note("pre-write"))
        }

        val afterWrite = RemindersCache.get { fetchCount++; listOf(note("post-write")) }

        assertEquals(2, fetchCount)
        assertEquals("post-write", afterWrite.single().title)
    }
}
