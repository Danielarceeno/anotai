package com.example.notepad.data

import java.util.Calendar

/**
 * Brazilian national holidays, computed rather than hand-maintained per year: fixed-date ones are
 * trivial, and the moveable ones (Carnaval, Sexta-feira Santa, Corpus Christi) are derived from
 * Easter Sunday via the standard Gregorian algorithm, since Easter's date shifts every year.
 * Purely optional -- gated behind [com.example.notepad.ui.CalendarPrefs.isHolidaysEnabled], off by
 * default, since not every user wants Brazil-specific dates on their calendar.
 */
object BrazilianHolidays {

    /** Null if [dayStart] (a start-of-day millis value, same convention as [Note.dueDate]) isn't
     *  a recognized Brazilian national holiday. */
    fun nameFor(dayStart: Long): String? {
        val year = Calendar.getInstance().apply { timeInMillis = dayStart }.get(Calendar.YEAR)
        return holidaysForYear(year)[dayStart]
    }

    /** Memoized per year: the calendar grid's 42 cells would otherwise redo [easterSunday]'s
     *  arithmetic (and rebuild this map) up to 42 times for the exact same year. */
    /** Held as one object behind a single volatile field, so a reader can never see one year's
     *  number paired with another year's map. Keyed by time zone as well as year: the map's keys
     *  are start-of-day millis *in the default zone*, so after the device changes zone a map
     *  built under the old one no longer matches any day the calendar asks about. */
    private class Cached(val year: Int, val zoneId: String, val holidays: Map<Long, String>)

    @Volatile private var cached: Cached? = null

    private fun holidaysForYear(year: Int): Map<Long, String> {
        val zoneId = java.util.TimeZone.getDefault().id
        cached?.takeIf { it.year == year && it.zoneId == zoneId }?.let { return it.holidays }
        val easter = easterSunday(year)
        // Easter-relative days go through DayMath.addDays, not `easter - 47 * 24h`: a DST
        // transition between the two dates (routine outside Brazil -- Carnaval and Easter
        // straddle the European/North American spring change) would leave the result an hour off
        // midnight, so it would never match the start-of-day value the calendar looks up.
        val holidays = mapOf(
            dateMillis(year, Calendar.JANUARY, 1) to "Ano Novo",
            DayMath.addDays(easter, -47) to "Carnaval",
            DayMath.addDays(easter, -2) to "Sexta-feira Santa",
            easter to "Páscoa",
            dateMillis(year, Calendar.APRIL, 21) to "Tiradentes",
            dateMillis(year, Calendar.MAY, 1) to "Dia do Trabalho",
            DayMath.addDays(easter, 60) to "Corpus Christi",
            dateMillis(year, Calendar.SEPTEMBER, 7) to "Independência do Brasil",
            dateMillis(year, Calendar.OCTOBER, 12) to "Nossa Senhora Aparecida",
            dateMillis(year, Calendar.NOVEMBER, 2) to "Finados",
            dateMillis(year, Calendar.NOVEMBER, 15) to "Proclamação da República",
            // National since Lei 14.759/2023 (previously a municipal/state holiday only).
            dateMillis(year, Calendar.NOVEMBER, 20) to "Consciência Negra",
            dateMillis(year, Calendar.DECEMBER, 25) to "Natal"
        )
        cached = Cached(year, zoneId, holidays)
        return holidays
    }

    private fun dateMillis(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * The Anonymous Gregorian algorithm (aka Meeus/Jones/Butcher) for the date of Easter Sunday,
     * as start-of-day millis. [month]/[day] come out 1-based (3/4 = March/April); [dateMillis]
     * takes a 0-based [Calendar] month, hence the `month - 1` below.
     */
    private fun easterSunday(year: Int): Long {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return dateMillis(year, month - 1, day)
    }
}
