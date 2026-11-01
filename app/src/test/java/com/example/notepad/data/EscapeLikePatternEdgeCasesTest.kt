package com.example.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [NoteRepositoryTest] already covers the core escaping behavior (%, _, \, and the
 * backslash-first ordering). This widens it with the specific edge cases the Fase 2 mission
 * calls out: characters that must pass through *unescaped* (quotes, emoji, accents -- none of
 * them are LIKE wildcards, and quotes are safe here regardless since the query is always bound
 * as a `?` parameter, never string-concatenated into SQL), short terms (the FTS5 trigram index
 * needs at least 3 characters per token -- this function itself doesn't know or care about that,
 * it only escapes wildcards, so 1-2 character terms should pass through just like any other
 * plain text), and a very long input.
 */
class EscapeLikePatternEdgeCasesTest {

    @Test
    fun `single and double quotes pass through unescaped`() {
        // Safe because the escaped pattern is always bound as a ? parameter (see
        // NoteRepository.search), never concatenated into the SQL string -- this function's only
        // job is escaping LIKE wildcards, not SQL-injection characters.
        assertEquals("it's a \"test\"", NoteRepository.escapeLikePattern("it's a \"test\""))
    }

    @Test
    fun `emoji pass through unescaped`() {
        assertEquals("grocery list 😀🛒", NoteRepository.escapeLikePattern("grocery list 😀🛒"))
    }

    @Test
    fun `accented characters, upper and lower case, pass through unescaped`() {
        assertEquals("Café com Açúcar e ÁGUA", NoteRepository.escapeLikePattern("Café com Açúcar e ÁGUA"))
    }

    @Test
    fun `a one-character term passes through unchanged`() {
        assertEquals("a", NoteRepository.escapeLikePattern("a"))
    }

    @Test
    fun `a two-character term passes through unchanged`() {
        assertEquals("ab", NoteRepository.escapeLikePattern("ab"))
    }

    @Test
    fun `a one-character wildcard is still escaped`() {
        assertEquals("\\%", NoteRepository.escapeLikePattern("%"))
        assertEquals("\\_", NoteRepository.escapeLikePattern("_"))
    }

    @Test
    fun `a very long string is escaped in full, nothing truncated`() {
        val longInput = "a%b_c\\d".repeat(1000)
        val expected = "a\\%b\\_c\\\\d".repeat(1000)
        assertEquals(expected, NoteRepository.escapeLikePattern(longInput))
    }
}
