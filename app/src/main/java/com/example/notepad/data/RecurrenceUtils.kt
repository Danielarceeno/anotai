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
            else -> dayStart == note.dueDate
        }
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
