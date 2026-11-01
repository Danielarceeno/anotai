package com.example.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChecklistItemTest] already covers the basic mixed-checklist/empty-body cases. This widens it
 * per the Fase 2 mission: blank lines in various positions, whitespace-only lines, and a line
 * with no recognized `[x] `/`[ ] ` prefix at all (the fallback path for anything that doesn't
 * match either -- e.g. a plain-text note's body read back through this parser, or a line edited
 * by hand outside the app).
 */
class ChecklistItemEdgeCasesTest {

    @Test
    fun `leading, trailing, and consecutive blank lines are all filtered out`() {
        val body = "\n\n[x] first\n\n\n[ ] second\n\n"
        val items = ChecklistItem.parse(body)
        assertEquals(listOf(ChecklistItem("first", true), ChecklistItem("second", false)), items)
        assertEquals(1 to 2, ChecklistItem.countDone(body))
    }

    @Test
    fun `whitespace-only lines count as blank, same as truly empty ones`() {
        val body = "[x] first\n   \n\t\n[ ] second"
        assertEquals(listOf(ChecklistItem("first", true), ChecklistItem("second", false)), ChecklistItem.parse(body))
        assertEquals(1 to 2, ChecklistItem.countDone(body))
    }

    @Test
    fun `a line with no recognized prefix falls back to a plain unchecked item`() {
        // E.g. a body that predates this note being switched to a checklist, or one hand-edited
        // outside the app -- must not crash or silently drop the line.
        val body = "just plain text, no prefix at all"
        assertEquals(listOf(ChecklistItem("just plain text, no prefix at all", false)), ChecklistItem.parse(body))
        assertEquals(0 to 1, ChecklistItem.countDone(body))
    }

    @Test
    fun `a mix of prefixed and unprefixed lines parses each independently`() {
        val body = "[x] done\nno prefix here\n[ ] pending"
        val items = ChecklistItem.parse(body)
        assertEquals(
            listOf(
                ChecklistItem("done", true),
                ChecklistItem("no prefix here", false),
                ChecklistItem("pending", false)
            ),
            items
        )
        assertEquals(1 to 3, ChecklistItem.countDone(body))
    }

    @Test
    fun `serialize then parse round-trips exactly for a mix of checked and unchecked items`() {
        val original = listOf(
            ChecklistItem("milk", false),
            ChecklistItem("eggs", true),
            ChecklistItem("bread", false)
        )
        assertEquals(original, ChecklistItem.parse(ChecklistItem.serialize(original)))
    }

    @Test
    fun `a body that is only blank lines parses to no items and counts 0 of 0`() {
        val body = "\n\n   \n\t\n"
        assertTrue(ChecklistItem.parse(body).isEmpty())
        assertEquals(0 to 0, ChecklistItem.countDone(body))
    }
}
