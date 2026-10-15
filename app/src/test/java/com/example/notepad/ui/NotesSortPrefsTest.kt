package com.example.notepad.ui

import com.example.notepad.data.Note
import org.junit.Assert.assertEquals
import org.junit.Test

/** [NotesSortPrefs.apply]'s pure sort logic: pinned-first is layered on top of every mode, and
 *  each mode has its own "goes last" rule for notes missing the field it sorts by. */
class NotesSortPrefsTest {

    private fun note(title: String, colorIndex: Int = 0, dueDate: Long = 0L, pinned: Boolean = false) =
        Note(title = title, body = "", colorIndex = colorIndex, dueDate = dueDate, pinned = pinned)

    @Test
    fun `color sort groups by color index`() {
        val notes = listOf(note("a", colorIndex = 2), note("b", colorIndex = 0), note("c", colorIndex = 1))
        val sorted = NotesSortPrefs.apply(notes, NotesSortPrefs.SORT_COLOR)
        assertEquals(listOf("b", "c", "a"), sorted.map { it.title })
    }

    @Test
    fun `title sort is case-insensitive alphabetical`() {
        val notes = listOf(note("banana"), note("Apple"), note("cherry"))
        val sorted = NotesSortPrefs.apply(notes, NotesSortPrefs.SORT_TITLE)
        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun `due date sort puts notes without a due date last`() {
        val notes = listOf(note("no date"), note("later", dueDate = 2000L), note("sooner", dueDate = 1000L))
        val sorted = NotesSortPrefs.apply(notes, NotesSortPrefs.SORT_DUE_DATE)
        assertEquals(listOf("sooner", "later", "no date"), sorted.map { it.title })
    }

    @Test
    fun `pinned notes come first regardless of sort mode`() {
        val notes = listOf(note("zebra"), note("apple", pinned = true))
        val sorted = NotesSortPrefs.apply(notes, NotesSortPrefs.SORT_TITLE)
        assertEquals(listOf("apple", "zebra"), sorted.map { it.title })
    }
}
