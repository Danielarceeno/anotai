package com.example.notepad.data

import java.util.Calendar

/**
 * Decides whether a note's reminder "lands" on a given day, taking its
 * [Note.repetition] into account instead of just comparing [Note.dueDate]
 * for equality. Used by both the calendar dots and the daily notification
 * check so a recurring reminder actually recurs.
 */
object RecurrenceUtils {

    fun occursOn(note: Note, dayStart: Long): Boolean {
        if (note.dueDate == 0L || dayStart < note.dueDate) return false
        if (note.repeatUntil != 0L && dayStart > note.repeatUntil) return false

        val due = Calendar.getInstance().apply { timeInMillis = note.dueDate }
        val target = Calendar.getInstance().apply { timeInMillis = dayStart }

        return when (note.repetition) {
            Note.REPEAT_NONE -> dayStart == note.dueDate
            Note.REPEAT_DAILY -> true
            Note.REPEAT_EVERY_WEEKDAY -> {
                val dow = target.get(Calendar.DAY_OF_WEEK)
                dow != Calendar.SUNDAY && dow != Calendar.SATURDAY
            }
            Note.REPEAT_WEEKLY -> daysBetween(note.dueDate, dayStart) % 7 == 0L
            Note.REPEAT_BIWEEKLY -> daysBetween(note.dueDate, dayStart) % 14 == 0L
            Note.REPEAT_MONTHLY -> due.get(Calendar.DAY_OF_MONTH) == target.get(Calendar.DAY_OF_MONTH)
            Note.REPEAT_YEARLY ->
                due.get(Calendar.DAY_OF_MONTH) == target.get(Calendar.DAY_OF_MONTH) &&
                    due.get(Calendar.MONTH) == target.get(Calendar.MONTH)
            Note.REPEAT_MONTHLY_NTH_WEEKDAY ->
                target.get(Calendar.DAY_OF_WEEK) == due.get(Calendar.DAY_OF_WEEK) &&
                    weekOfMonth(target) == weekOfMonth(due)
            Note.REPEAT_FIRST_BUSINESS_DAY_OF_MONTH -> isFirstBusinessDayOfMonth(target)
            else -> dayStart == note.dueDate
        }
    }

    /** Which occurrence of its weekday a day is within its month -- the 14th is always the
     *  2nd occurrence of whatever weekday it falls on, the 21st the 3rd, etc. Used by
     *  [Note.REPEAT_MONTHLY_NTH_WEEKDAY] to turn a single anchor date (e.g. "the 2nd Friday this
     *  note was created on") into "the 2nd Friday of every month" without storing that rule as
     *  anything other than the plain due date it already is. */
    private fun weekOfMonth(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_MONTH) - 1) / 7 + 1

    private fun isFirstBusinessDayOfMonth(cal: Calendar): Boolean {
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        if (dow == Calendar.SUNDAY || dow == Calendar.SATURDAY) return false
        // Walk back to day 1 of the same month; if every day strictly before this one was a
        // weekend, this is the first weekday -- cheaper than re-deriving "day 1's weekday" via
        // modular arithmetic, and this only ever walks at most 3 days back (Mon can follow a
        // Sat/Sun day 1).
        val probe = cal.clone() as Calendar
        while (probe.get(Calendar.DAY_OF_MONTH) > 1) {
            probe.add(Calendar.DAY_OF_MONTH, -1)
            val probeDow = probe.get(Calendar.DAY_OF_WEEK)
            if (probeDow != Calendar.SUNDAY && probeDow != Calendar.SATURDAY) return false
        }
        return true
    }

    /** The first start-of-day millis on or after [fromDayStart] that [note] occurs on, within
     *  [maxDaysAhead] (a year by default -- enough to always find the next yearly occurrence,
     *  while still bounding the scan), or null if there isn't one (e.g. [Note.repeatUntil] has
     *  already passed). Used by [com.example.notepad.notification.NoteReminderScheduler] to know
     *  when to fire a recurring note's next exact-time alarm. */
    fun nextOccurrenceOnOrAfter(note: Note, fromDayStart: Long, maxDaysAhead: Int = 370): Long? {
        val oneDayMillis = 24 * 60 * 60 * 1000L
        var day = fromDayStart
        repeat(maxDaysAhead) {
            if (occursOn(note, day)) return day
            day += oneDayMillis
        }
        return null
    }

    /** Whole-day difference between two start-of-day millis values, DST-safe (diffed at local noon). */
    private fun daysBetween(fromStartOfDay: Long, toStartOfDay: Long): Long {
        val noon = 12 * 60 * 60 * 1000L
        val dayMs = 24 * 60 * 60 * 1000L
        return ((toStartOfDay + noon) - (fromStartOfDay + noon)) / dayMs
    }

    /** Whether [note] is marked done specifically on [dayStart] (not just done "at some point"). */
    fun isCompletedOn(note: Note, dayStart: Long): Boolean {
        return if (note.repetition == Note.REPEAT_NONE) {
            note.completed
        } else {
            completedDaySet(note).contains(dayStart)
        }
    }

    /** Returns [note] with its completion on [dayStart] set, without touching other occurrences. */
    fun withCompletion(note: Note, dayStart: Long, completed: Boolean): Note {
        if (note.repetition == Note.REPEAT_NONE) {
            return note.copy(completed = completed)
        }
        val days = completedDaySet(note).toMutableSet()
        if (completed) days.add(dayStart) else days.remove(dayStart)
        return note.copy(completedDates = days.sorted().joinToString(","))
    }

    private fun completedDaySet(note: Note): Set<Long> =
        note.completedDates.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
}
