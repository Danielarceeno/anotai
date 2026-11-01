package com.example.notepad.data

import java.util.Calendar
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Edge cases beyond [RecurrenceUtilsTest]'s basic per-mode coverage: leap years, short months,
 * a weekday occurrence that doesn't exist in every month, the year boundary, and timezone/DST
 * correctness -- [RecurrenceUtils] runs on plain [java.util.Calendar], which is timezone-
 * sensitive via [TimeZone.getDefault], so every test here restores the original default in
 * [tearDown] rather than leaking it into other tests in the same JVM.
 */
class RecurrenceUtilsEdgeCasesTest {

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

    // --- Leap years -----------------------------------------------------------------------

    @Test
    fun `yearly reminder anchored on Feb 29 only matches again on the next leap year`() {
        // 2028 is the next leap year after 2024.
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2024, Calendar.FEBRUARY, 29),
            repetition = Note.REPEAT_YEARLY
        )
        // 2025, 2026, 2027 have no Feb 29 at all -- Calendar.DAY_OF_MONTH never reaches 29 in a
        // non-leap February, so this can never match any day in those years' Februaries.
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2025, Calendar.FEBRUARY, 28)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.FEBRUARY, 28)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.FEBRUARY, 28)))
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2028, Calendar.FEBRUARY, 29)))
    }

    @Test
    fun `monthly reminder anchored on day 31 only matches months that actually have one`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.JANUARY, 31),
            repetition = Note.REPEAT_MONTHLY
        )
        // February, April, June, September, November never have a 31st -- Calendar.DAY_OF_MONTH
        // can never equal 31 on any day within them, so this simply never fires those months.
        // Documents the existing behavior (skip, not "fire on the last day instead") rather than
        // assuming it -- this is the kind of thing an accidental future "fix" could silently flip.
        for (month in intArrayOf(Calendar.FEBRUARY, Calendar.APRIL, Calendar.JUNE, Calendar.SEPTEMBER, Calendar.NOVEMBER)) {
            val daysInMonth = Calendar.getInstance().apply { set(2026, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)
            for (day in 1..daysInMonth) {
                assertFalse(
                    "day $day of month $month should never match a day-31 monthly anchor",
                    RecurrenceUtils.occursOn(note, dayMillis(2026, month, day))
                )
            }
        }
        // But it does fire on every month that actually has a 31st.
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.MARCH, 31)))
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.MAY, 31)))
    }

    // --- An nth-weekday-of-month anchor whose occurrence doesn't exist every month -----------

    @Test
    fun `monthly nth weekday anchored on a 5th occurrence skips a month with only 4`() {
        // Find a real "5th occurrence of its weekday in its own month" day, programmatically --
        // no hardcoded day-of-week trivia to get wrong. January 2026 has 31 days; walk it to find one.
        var anchor: Calendar? = null
        val probe = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 1) }
        val daysInJan = probe.getActualMaximum(Calendar.DAY_OF_MONTH)
        for (day in 1..daysInJan) {
            val cal = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, day, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
            if ((day - 1) / 7 + 1 == 5) { anchor = cal; break }
        }
        val anchorCal = requireNotNull(anchor) { "January 2026 should have at least one weekday with a 5th occurrence" }
        val note = Note(title = "t", body = "", dueDate = anchorCal.timeInMillis, repetition = Note.REPEAT_MONTHLY_NTH_WEEKDAY)
        val anchorWeekday = anchorCal.get(Calendar.DAY_OF_WEEK)

        // February 2026 (28 days, not a leap year) has exactly 4 of every weekday -- never a 5th
        // occurrence of anything -- so this must not fire on any day in it.
        val febCal = Calendar.getInstance().apply { set(2026, Calendar.FEBRUARY, 1) }
        val daysInFeb = febCal.getActualMaximum(Calendar.DAY_OF_MONTH)
        assertEquals(28, daysInFeb)
        for (day in 1..daysInFeb) {
            assertFalse(
                "Feb $day 2026 can't be a 5th occurrence of anything in a 28-day month",
                RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.FEBRUARY, day))
            )
        }
        // Sanity: the anchor's own weekday really does exist (just not as a 5th occurrence) in February.
        assertTrue((1..daysInFeb).any { day ->
            Calendar.getInstance().apply { set(2026, Calendar.FEBRUARY, day) }.get(Calendar.DAY_OF_WEEK) == anchorWeekday
        })
    }

    // --- Year boundary ----------------------------------------------------------------------

    @Test
    fun `weekly reminder crosses the year boundary correctly`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.DECEMBER, 28),
            repetition = Note.REPEAT_WEEKLY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.JANUARY, 4)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.JANUARY, 3)))
        assertFalse(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.JANUARY, 5)))
    }

    @Test
    fun `daily reminder keeps firing across the year boundary`() {
        val note = Note(
            title = "t", body = "",
            dueDate = dayMillis(2026, Calendar.DECEMBER, 30),
            repetition = Note.REPEAT_DAILY
        )
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.DECEMBER, 31)))
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.JANUARY, 1)))
        assertTrue(RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.JANUARY, 2)))
    }

    // --- Timezones ----------------------------------------------------------------------------

    @Test
    fun `weekly recurrence is correct in Sao Paulo, UTC, and Auckland`() {
        for (zoneId in listOf("America/Sao_Paulo", "UTC", "Pacific/Auckland")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            val note = Note(
                title = "t", body = "",
                dueDate = dayMillis(2026, Calendar.OCTOBER, 14),
                repetition = Note.REPEAT_WEEKLY
            )
            assertTrue("zone $zoneId", RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 21)))
            assertFalse("zone $zoneId", RecurrenceUtils.occursOn(note, dayMillis(2026, Calendar.OCTOBER, 20)))
        }
    }

    @Test
    fun `weekly recurrence survives Auckland's spring-forward DST transition`() {
        // New Zealand's clocks spring forward on the last Sunday of September. daysBetween() is
        // documented as "DST-safe (diffed at local noon)" specifically for this -- a naive
        // midnight-to-midnight epoch-millis diff would be off by the one hour the clocks skipped,
        // so a diff that should land on an exact 7-day multiple wouldn't. Finds the real
        // transition date for 2026 programmatically (last Sunday of September) instead of relying
        // on manually worked-out day-of-week arithmetic, which is exactly the kind of thing to
        // get wrong by hand in a test about date arithmetic.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
        val lastDayOfSept = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 1); set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        }
        val transitionDay = (lastDayOfSept.clone() as Calendar).apply {
            while (get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) add(Calendar.DAY_OF_MONTH, -1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }

        val anchor = (transitionDay.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -7) }
        val note = Note(title = "t", body = "", dueDate = anchor.timeInMillis, repetition = Note.REPEAT_WEEKLY)

        val oneWeekAfter = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 7) }
        val twoWeeksAfter = (anchor.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 14) }
        assertEquals("sanity: +7 days should be the transition day itself", transitionDay.timeInMillis, oneWeekAfter.timeInMillis)

        assertTrue(
            "a week after the anchor, which is the DST transition day itself, should still match",
            RecurrenceUtils.occursOn(note, oneWeekAfter.timeInMillis)
        )
        assertTrue(
            "two weeks after, past the transition, should still match",
            RecurrenceUtils.occursOn(note, twoWeeksAfter.timeInMillis)
        )
    }

    // --- First business day of the month vs. holidays ----------------------------------------

    @Test
    fun `first business day of the month does not know about holidays`() {
        // Documents a real, current limitation (see RecurrenceUtils.isFirstBusinessDayOfMonth's
        // own doc: "no holiday calendar -- business day here just means not a weekend"): Jan 1 is
        // a national holiday every year, but if it falls on a weekday, this still treats it as
        // the first business day of January.
        val jan1 = Calendar.getInstance().apply { set(2027, Calendar.JANUARY, 1) }
        val jan1Dow = jan1.get(Calendar.DAY_OF_WEEK)
        if (jan1Dow != Calendar.SATURDAY && jan1Dow != Calendar.SUNDAY) {
            val note = Note(
                title = "t", body = "",
                dueDate = dayMillis(2026, Calendar.OCTOBER, 1),
                repetition = Note.REPEAT_FIRST_BUSINESS_DAY_OF_MONTH
            )
            assertTrue(
                "Jan 1 2027 is a weekday and a holiday, but this function only excludes weekends",
                RecurrenceUtils.occursOn(note, dayMillis(2027, Calendar.JANUARY, 1))
            )
        }
    }
}
