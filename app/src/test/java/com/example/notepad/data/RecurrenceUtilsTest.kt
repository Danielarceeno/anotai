package com.example.notepad.data

import java.util.Calendar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the recurrence math behind the calendar dots, the day popup, the
 * widget and the daily notification, since a quiet off-by-one here means a
 * reminder silently stops showing up (exactly the kind of bug the color-row
 * visibility issue during manual testing showed this app badly needs).
 */
class RecurrenceUtilsTest {

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `no reminder date never occurs`() {
        val note = Note(title = "t", body = "", dueDate = 0L)
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 14)))
    }

    @Test
    fun `before due date never occurs`() {
        val note = Note(title = "t", body = "", dueDate = dayMillis(2026, Calendar.OCTOBER, 14))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 13)))
    }

    @Test
    fun `one-time event matches only its own day`() {
        val due = dayMillis(2026, Calendar.OCTOBER, 14)
        val note = Note(title = "t", body = "", dueDate = due, repetition = Note.REPEAT_NONE)
        assertTrue(RecurrenceUtils.occursOn(note, due))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 15)))
    }

    @Test
    fun `daily repeats every day after the due date`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
            repetition = Note.REPEAT_DAILY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 20)))
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.NOVEMBER, 3)))
    }

    @Test
    fun `every weekday skips Saturday and Sunday`() {
        // Oct 14 2026 is a Wednesday.
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
            repetition = Note.REPEAT_EVERY_WEEKDAY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 16))) // Fri
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 17))) // Sat
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 18))) // Sun
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 19))) // Mon
    }

    @Test
    fun `weekly only matches exact 7-day multiples`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
            repetition = Note.REPEAT_WEEKLY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 21)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 20)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 22)))
    }

    @Test
    fun `bi-weekly only matches exact 14-day multiples`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
            repetition = Note.REPEAT_BIWEEKLY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 28)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 21)))
    }

    @Test
    fun `monthly matches same day-of-month in a later month`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
            repetition = Note.REPEAT_MONTHLY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.DECEMBER, 14)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.DECEMBER, 15)))
    }

    @Test
    fun `yearly matches same month and day a year later`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
            repetition = Note.REPEAT_YEARLY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.OCTOBER, 14)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.NOVEMBER, 14)))
    }
}
