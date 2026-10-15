package com.example.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NoteRepository.escapeLikePattern] is the one piece of code standing between a user typing
 * a literal "%" or "_" in search and that character silently acting as a SQL LIKE wildcard
 * instead (see EVIDENCE.md for why this app uses LIKE instead of full-text search).
 */
class NoteRepositoryTest {

    @Test
    fun `plain text is left untouched`() {
        assertEquals("groceries", NoteRepository.escapeLikePattern("groceries"))
    }

    @Test
    fun `percent sign is escaped so it isn't treated as a wildcard`() {
        assertEquals("50\\% off", NoteRepository.escapeLikePattern("50% off"))
    }

    @Test
    fun `underscore is escaped so it isn't treated as a single-char wildcard`() {
        assertEquals("file\\_name", NoteRepository.escapeLikePattern("file_name"))
    }

    @Test
    fun `backslash is escaped first so a pre-escaped sequence can't be forged`() {
        // If '\' weren't escaped before '%', a user typing "\%" could forge the escape
        // sequence "\%" that LIKE ... ESCAPE '\' treats as a literal percent sign.
        // Input is `\%` (1 backslash + percent); backslash escaping doubles the backslash
        // to `\\%`, then percent escaping prefixes a backslash onto the percent: `\\\%`
        // (3 backslashes + percent).
        assertEquals("\\\\\\%", NoteRepository.escapeLikePattern("\\%"))
    }

    @Test
    fun `mixed special characters are all escaped`() {
        assertEquals("100\\%\\_done\\\\", NoteRepository.escapeLikePattern("100%_done\\"))
    }
}
