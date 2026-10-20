package com.example.notepad.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChecklistItemTest {

    @Test
    fun `countDone matches parse-and-count for a mixed checklist`() {
        val body = ChecklistItem.serialize(
            listOf(
                ChecklistItem("one", checked = true),
                ChecklistItem("two", checked = false),
                ChecklistItem("three", checked = true)
            )
        )

        assertEquals(2 to 3, ChecklistItem.countDone(body))
    }

    @Test
    fun `countDone on an empty body is 0 of 0`() {
        assertEquals(0 to 0, ChecklistItem.countDone(""))
    }

    @Test
    fun `countDone ignores blank lines, same as parse does`() {
        val body = "[x] done item\n\n[ ] pending item\n"
        assertEquals(1 to 2, ChecklistItem.countDone(body))
    }
}
