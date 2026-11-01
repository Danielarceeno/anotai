package com.example.notepad.data

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BrazilianHolidaysTest] already covers 2025/2026; this widens the years checked against real,
 * publicly known Easter Sunday dates (not re-derived from [BrazilianHolidays.easterSunday] under
 * test) to a leap year with Easter in March (2024), a non-leap year with Easter also in March
 * (2027), and a leap year with Easter in April (2028) -- the three combinations
 * [BrazilianHolidaysTest] alone doesn't exercise.
 *
 * The three Easter-derived holidays (Carnaval/Sexta-feira Santa/Corpus Christi) are computed
 * *in this test*, via [Calendar.add] from each year's known real Easter date, rather than by
 * hand-working-out and hardcoding their resulting calendar dates -- doing that arithmetic by hand
 * for several different years is exactly the kind of place to introduce a silent off-by-one in
 * the test itself. This still independently checks [BrazilianHolidays.easterSunday]: if its
 * Gregorian-Easter algorithm computed the wrong date for any of these years, its own moveable
 * holidays would be offset from *its own* (wrong) Easter the same way this test's are offset from
 * the *real* one, so they'd disagree.
 */
class BrazilianHolidaysMultiYearTest {

    private fun dayMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun offsetFromEaster(easter: Calendar, days: Int): Long =
        (easter.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, days) }.timeInMillis

    private fun checkYear(year: Int, realEasterMonth: Int, realEasterDay: Int) {
        val easter = Calendar.getInstance().apply {
            set(year, realEasterMonth, realEasterDay, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }

        assertEquals("Páscoa $year", "Páscoa", BrazilianHolidays.nameFor(easter.timeInMillis))
        assertEquals("Sexta-feira Santa $year", "Sexta-feira Santa", BrazilianHolidays.nameFor(offsetFromEaster(easter, -2)))
        assertEquals("Carnaval $year", "Carnaval", BrazilianHolidays.nameFor(offsetFromEaster(easter, -47)))
        assertEquals("Corpus Christi $year", "Corpus Christi", BrazilianHolidays.nameFor(offsetFromEaster(easter, 60)))

        // Fixed-date holidays, same every year, cheap to re-check per year too.
        assertEquals("Ano Novo", BrazilianHolidays.nameFor(dayMillis(year, Calendar.JANUARY, 1)))
        assertEquals("Natal", BrazilianHolidays.nameFor(dayMillis(year, Calendar.DECEMBER, 25)))
    }

    @Test
    fun `2024 -- leap year, Easter in March`() {
        // Real Easter Sunday 2024: March 31.
        checkYear(2024, Calendar.MARCH, 31)
    }

    @Test
    fun `2027 -- non-leap year, Easter also in March`() {
        // Real Easter Sunday 2027: March 28.
        checkYear(2027, Calendar.MARCH, 28)
    }

    @Test
    fun `2028 -- leap year, Easter in April`() {
        // Real Easter Sunday 2028: April 16.
        checkYear(2028, Calendar.APRIL, 16)
    }

    @Test
    fun `leap year Feb 29 itself is never mistaken for a holiday`() {
        // 2028's Carnaval happens to fall very close to the end of February -- make sure the
        // leap day itself isn't accidentally flagged unless it genuinely is Carnaval that year.
        val feb29 = dayMillis(2028, Calendar.FEBRUARY, 29)
        val carnaval2028 = offsetFromEaster(
            Calendar.getInstance().apply { set(2028, Calendar.APRIL, 16, 0, 0, 0); set(Calendar.MILLISECOND, 0) }, -47
        )
        if (feb29 != carnaval2028) {
            org.junit.Assert.assertNull(BrazilianHolidays.nameFor(feb29))
        }
    }
}
