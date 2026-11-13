package com.example.notepad.data

import java.util.Calendar

/**
 * Start-of-day arithmetic shared by everything that works in "start-of-day millis" (the
 * convention [Note.dueDate] uses). Stepping a day forward/back goes through [Calendar.add], never
 * `+ 24 * 60 * 60 * 1000`: a day that contains a DST transition is 23 or 25 hours long, so adding
 * a fixed 24h lands an hour off midnight from then on -- and every exact-equality comparison
 * against a real start-of-day value ([RecurrenceUtils.occursOn], the holiday lookup, completion
 * tracking) silently stops matching.
 */
object DayMath {

    fun startOfToday(): Long = startOfDay(System.currentTimeMillis())

    fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** [dayStart] moved by [days] calendar days (negative goes back), still at start-of-day. */
    fun addDays(dayStart: Long, days: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_MONTH, days)
    }.timeInMillis
}
