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
}
