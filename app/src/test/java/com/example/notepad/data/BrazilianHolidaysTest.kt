package com.example.notepad.data

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers both the fixed-date holidays and the Easter-derived moveable ones (Carnaval,
 * Sexta-feira Santa, Corpus Christi) behind the Settings "Show Brazilian holidays" toggle --
 * the expected moveable dates below are the actual public dates for each year, not re-derived
 * from the same algorithm under test, so a wrong Easter calculation would actually be caught.
 */
class BrazilianHolidaysTest {

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `fixed-date national holidays are recognized`() {
        assertEquals("Ano Novo", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.JANUARY, 1)))
        assertEquals("Tiradentes", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.APRIL, 21)))
        assertEquals("Dia do Trabalho", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.MAY, 1)))
        assertEquals("Independência do Brasil", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.SEPTEMBER, 7)))
        assertEquals("Nossa Senhora Aparecida", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.OCTOBER, 12)))
        assertEquals("Finados", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.NOVEMBER, 2)))
        assertEquals("Proclamação da República", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.NOVEMBER, 15)))
        assertEquals("Consciência Negra", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.NOVEMBER, 20)))
        assertEquals("Natal", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.DECEMBER, 25)))
    }

    @Test
    fun `moveable holidays match the real 2026 Easter calendar`() {
        // Easter Sunday 2026 is April 5.
        assertEquals("Páscoa", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.APRIL, 5)))
        assertEquals("Sexta-feira Santa", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.APRIL, 3)))
        assertEquals("Carnaval", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.FEBRUARY, 17)))
        assertEquals("Corpus Christi", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.JUNE, 4)))
    }

    @Test
    fun `moveable holidays are recomputed for a different year, not cached across years`() {
        // Easter Sunday 2025 is April 20 -- a different year than the other tests here, so this
        // only passes if the per-year memoization in BrazilianHolidays actually keys on the year.
        assertEquals("Páscoa", BrazilianHolidays.nameFor(dayMillis(2025, Calendar.APRIL, 20)))
        assertEquals("Corpus Christi", BrazilianHolidays.nameFor(dayMillis(2025, Calendar.JUNE, 19)))
        // 2026's Páscoa should still be its own date, not 2025's leaking through the cache.
        assertEquals("Páscoa", BrazilianHolidays.nameFor(dayMillis(2026, Calendar.APRIL, 5)))
    }

    @Test
    fun `an ordinary weekday is not a holiday`() {
        assertNull(BrazilianHolidays.nameFor(dayMillis(2026, Calendar.JANUARY, 2)))
    }

    @Test
    fun `the day right after a holiday is not also flagged`() {
        assertNull(BrazilianHolidays.nameFor(dayMillis(2026, Calendar.DECEMBER, 26)))
    }
}
