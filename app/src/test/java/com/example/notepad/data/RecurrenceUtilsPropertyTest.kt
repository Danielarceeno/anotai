package com.example.notepad.data

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Property-based tests for [RecurrenceUtils.occursOn], complementing [RecurrenceUtilsTest]'s
 * (and [RecurrenceUtilsEdgeCasesTest]'s) example-based cases: these run the same invariant
 * against many randomly generated offsets instead of a handful of hand-picked ones.
 */
class RecurrenceUtilsPropertyTest {

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun plusDays(dayStart: Long, days: Int): Long =
        (Calendar.getInstance().apply { timeInMillis = dayStart }).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    @Test
    fun `daily always occurs on any day on or after the due date`() {
        runBlocking {
            checkAll(Arb.int(0..1000)) { offset ->
                val due = dayMillis(2026, Calendar.JANUARY, 1)
                val note = Note(title = "t", body = "", dueDate = due, repetition = Note.REPEAT_DAILY)
                assertTrue(RecurrenceUtils.occursOn(note, plusDays(due, offset)))
            }
        }
    }

    @Test
    fun `weekly occurs iff the day offset from the due date is an exact multiple of 7`() {
        runBlocking {
            checkAll(Arb.int(0..500)) { offset ->
                val due = dayMillis(2026, Calendar.JANUARY, 1)
                val note = Note(title = "t", body = "", dueDate = due, repetition = Note.REPEAT_WEEKLY)
                val target = plusDays(due, offset)
                assertEquals(offset % 7 == 0, RecurrenceUtils.occursOn(note, target))
            }
        }
    }

    @Test
    fun `biweekly occurs iff the day offset from the due date is an exact multiple of 14`() {
        runBlocking {
            checkAll(Arb.int(0..500)) { offset ->
                val due = dayMillis(2026, Calendar.JANUARY, 1)
                val note = Note(title = "t", body = "", dueDate = due, repetition = Note.REPEAT_BIWEEKLY)
                val target = plusDays(due, offset)
                assertEquals(offset % 14 == 0, RecurrenceUtils.occursOn(note, target))
            }
        }
    }

    @Test
    fun `every weekday never occurs on a Saturday or Sunday, and always on any other day on or after due`() {
        runBlocking {
            checkAll(Arb.int(0..500)) { offset ->
                val due = dayMillis(2026, Calendar.JANUARY, 1)
                val note = Note(title = "t", body = "", dueDate = due, repetition = Note.REPEAT_EVERY_WEEKDAY)
                val target = plusDays(due, offset)
                val dow = Calendar.getInstance().apply { timeInMillis = target }.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
                assertEquals(!isWeekend, RecurrenceUtils.occursOn(note, target))
            }
        }
    }

    @Test
    fun `occursOn is always false for any day strictly before the due date, regardless of mode`() {
        val modes = intArrayOf(
            Note.REPEAT_NONE, Note.REPEAT_DAILY, Note.REPEAT_EVERY_WEEKDAY, Note.REPEAT_WEEKLY,
            Note.REPEAT_BIWEEKLY, Note.REPEAT_MONTHLY, Note.REPEAT_YEARLY,
            Note.REPEAT_MONTHLY_NTH_WEEKDAY, Note.REPEAT_FIRST_BUSINESS_DAY_OF_MONTH
        )
        runBlocking {
            checkAll(Arb.int(1..365)) { offset ->
                val due = dayMillis(2026, Calendar.JUNE, 15)
                val before = plusDays(due, -offset)
                for (mode in modes) {
                    val note = Note(title = "t", body = "", dueDate = due, repetition = mode)
                    assertFalse("mode $mode should never occur $offset day(s) before its due date", RecurrenceUtils.occursOn(note, before))
                }
            }
        }
    }
}
