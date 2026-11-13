package com.example.notepad.data

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [DayMath] exists so nothing steps between days with `+ 24 * 60 * 60 * 1000`: across a DST
 * transition that lands an hour off midnight, and every exact start-of-day comparison in the app
 * then stops matching. Both tests below fail against that fixed-millis arithmetic.
 */
class DayMathTest {

    private lateinit var originalDefaultTimeZone: TimeZone

    @Before
    fun saveTimeZone() {
        originalDefaultTimeZone = TimeZone.getDefault()
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalDefaultTimeZone)
    }

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `addDays stays on start-of-day across a 23-hour day`() {
        // Auckland springs forward on Sunday 27 September 2026.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        val before = dayMillis(2026, Calendar.SEPTEMBER, 26)

        assertEquals(dayMillis(2026, Calendar.SEPTEMBER, 28), DayMath.addDays(before, 2))
        assertEquals(before, DayMath.addDays(dayMillis(2026, Calendar.SEPTEMBER, 28), -2))
    }

    @Test
    fun `Easter-derived holidays are found in a zone whose DST change falls between them and Easter`() {
        // Lisbon springs forward on 29 March 2026; Easter is 5 April, Carnaval 17 February. The
        // old `easter - 47 * 24h` put Carnaval at 23:00 on the 16th there, so the calendar's
        // lookup for the 17th (a start-of-day value) never found it.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))

        assertEquals("Carnaval", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.FEBRUARY, 17)))
        assertEquals("Sexta-feira Santa", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.APRIL, 3)))
        assertEquals("Páscoa", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.APRIL, 5)))
        assertEquals("Corpus Christi", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.JUNE, 4)))
    }
}
