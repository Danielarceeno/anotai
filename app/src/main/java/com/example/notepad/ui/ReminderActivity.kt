package com.example.notepad.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.notepad.R
import com.example.notepad.data.Note
import com.example.notepad.databinding.ActivityReminderBinding
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Sets a note's reminder/calendar date and repetition, styled after the
 * reference screenshot's "Calendar" date-entry screen (Dismiss / Type /
 * When / Repetition / Cancel-Done). When a repetition is chosen, a
 * "Repeat until" row appears so a recurring task can have an end date
 * instead of repeating forever.
 *
 * The "When" field is a Google Tasks-style quick pick (No date / Today / Tomorrow / the next
 * 5 weekdays by name / Specific date), rather than a bare date button -- see [showWhenOptions].
 */
class ReminderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderBinding
    private var selectedDate: Long = 0L
    private var repeatUntil: Long = 0L
    private var reminderTimeMinutes: Int = -1
    private var reminderEndTimeMinutes: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        selectedDate = intent.getLongExtra(EXTRA_DUE_DATE, 0L).takeIf { it != 0L } ?: startOfToday()
        repeatUntil = intent.getLongExtra(EXTRA_REPEAT_UNTIL, 0L)
        reminderTimeMinutes = intent.getIntExtra(EXTRA_REMINDER_TIME_MINUTES, -1)
        reminderEndTimeMinutes = intent.getIntExtra(EXTRA_REMINDER_END_TIME_MINUTES, -1)
        binding.repetitionSpinner.setSelection(intent.getIntExtra(EXTRA_REPETITION, Note.REPEAT_NONE))
        updateDateButtonText()
        updateTimeButtonText()
        updateRepeatUntilVisibility()
        updateRepeatUntilButtonText()

        binding.dismissButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_DUE_DATE, 0L)
                    .putExtra(EXTRA_REPETITION, Note.REPEAT_NONE)
                    .putExtra(EXTRA_REPEAT_UNTIL, 0L)
                    .putExtra(EXTRA_REMINDER_TIME_MINUTES, -1)
                    .putExtra(EXTRA_REMINDER_END_TIME_MINUTES, -1)
            )
            finish()
        }
        binding.whenButton.setOnClickListener { showWhenOptions() }
        binding.timeButton.setOnClickListener { showTimeOptions() }
        binding.repetitionSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateRepeatUntilVisibility()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
        binding.repeatUntilButton.setOnClickListener { showRepeatUntilOptions() }
        binding.cancelButton.setOnClickListener { finish() }
        binding.doneButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_DUE_DATE, selectedDate)
                    .putExtra(EXTRA_REPETITION, binding.repetitionSpinner.selectedItemPosition)
                    .putExtra(EXTRA_REPEAT_UNTIL, repeatUntil)
                    .putExtra(EXTRA_REMINDER_TIME_MINUTES, if (selectedDate == 0L) -1 else reminderTimeMinutes)
                    .putExtra(EXTRA_REMINDER_END_TIME_MINUTES, if (selectedDate == 0L) -1 else reminderEndTimeMinutes)
            )
            finish()
        }
    }

    /** All day / an exact time for the alarm set in [NoteReminderScheduler], same two-item quick
     *  pick pattern as [showRepeatUntilOptions]. */
    private fun showTimeOptions() {
        val options = arrayOf(getString(R.string.reminder_time_all_day), getString(R.string.reminder_time_pick))
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                if (which == 0) {
                    reminderTimeMinutes = -1
                    reminderEndTimeMinutes = -1
                    updateTimeButtonText()
                } else {
                    openTimePicker()
                }
            }
            .show()
    }

    /** Picks the window's start time, then immediately chains into [openEndTimePicker] for its
     *  end -- e.g. "9 until 5pm" so the notification only fires while the time of day falls
     *  inside that range (see [com.example.notepad.notification.ReminderNotifier]). */
    private fun openTimePicker() {
        val initialHour = if (reminderTimeMinutes >= 0) reminderTimeMinutes / 60 else 9
        val initialMinute = if (reminderTimeMinutes >= 0) reminderTimeMinutes % 60 else 0
        TimePickerDialog(
            this,
            { _, hour, minute ->
                reminderTimeMinutes = hour * 60 + minute
                openEndTimePicker()
            },
            initialHour,
            initialMinute,
            true
        ).show()
    }

    private fun openEndTimePicker() {
        val initialHour = if (reminderEndTimeMinutes >= 0) reminderEndTimeMinutes / 60 else 17
        val initialMinute = if (reminderEndTimeMinutes >= 0) reminderEndTimeMinutes % 60 else 0
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val end = hour * 60 + minute
                // An end time at/before the start isn't a valid window -- treat it as "no end"
                // (a single-instant reminder) instead of silently never firing.
                reminderEndTimeMinutes = if (end > reminderTimeMinutes) end else -1
                updateTimeButtonText()
            },
            initialHour,
            initialMinute,
            true
        ).apply { setTitle(getString(R.string.reminder_time_end_prompt)) }.show()
    }

    private fun updateTimeButtonText() {
        binding.timeButton.text = when {
            reminderTimeMinutes < 0 -> getString(R.string.reminder_time_all_day)
            reminderEndTimeMinutes >= 0 -> String.format(
                Locale.getDefault(), "%02d:%02d – %02d:%02d",
                reminderTimeMinutes / 60, reminderTimeMinutes % 60,
                reminderEndTimeMinutes / 60, reminderEndTimeMinutes % 60
            )
            else -> String.format(Locale.getDefault(), "%02d:%02d", reminderTimeMinutes / 60, reminderTimeMinutes % 60)
        }
    }

    /** Google Tasks-style quick pick: No date / Today / Tomorrow / the next 5 calendar days (by
     *  weekday name, covering out to a week from today) / Specific date for anything further out. */
    private fun showWhenOptions() {
        val today = startOfToday()
        val oneDayMillis = 24 * 60 * 60 * 1000L
        val weekdayFormat = SimpleDateFormat("EEEE", Locale.getDefault())

        val labels = mutableListOf(getString(R.string.reminder_when_no_date), getString(R.string.reminder_when_today), getString(R.string.reminder_when_tomorrow))
        val dates = mutableListOf(0L, today, today + oneDayMillis)
        for (daysAhead in 2..6) {
            val millis = today + daysAhead * oneDayMillis
            labels.add(weekdayFormat.format(Date(millis)).replaceFirstChar { it.uppercase(Locale.getDefault()) })
            dates.add(millis)
        }
        labels.add(getString(R.string.reminder_when_specific_date))

        AlertDialog.Builder(this)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == labels.lastIndex) {
                    openDatePicker()
                } else {
                    selectedDate = dates[which]
                    updateDateButtonText()
                }
            }
            .show()
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate.takeIf { it != 0L } ?: startOfToday() }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                selectedDate = picked.timeInMillis
                updateDateButtonText()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun updateDateButtonText() {
        binding.whenButton.text = if (selectedDate == 0L) {
            getString(R.string.reminder_when_no_date)
        } else {
            DateFormat.getDateInstance(DateFormat.FULL).format(Date(selectedDate))
        }
    }

    private fun updateRepeatUntilVisibility() {
        val repeats = binding.repetitionSpinner.selectedItemPosition != Note.REPEAT_NONE
        binding.repeatUntilLabel.visibility = if (repeats) View.VISIBLE else View.GONE
        binding.repeatUntilButton.visibility = if (repeats) View.VISIBLE else View.GONE
    }

    private fun updateRepeatUntilButtonText() {
        binding.repeatUntilButton.text = if (repeatUntil == 0L) {
            getString(R.string.reminder_repeat_until_never)
        } else {
            DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(repeatUntil))
        }
    }

    private fun showRepeatUntilOptions() {
        val options = arrayOf(
            getString(R.string.reminder_repeat_until_never),
            getString(R.string.reminder_repeat_until_pick_date)
        )
        AlertDialog.Builder(this)
            .setItems(options) { _, which ->
                if (which == 0) {
                    repeatUntil = 0L
                    updateRepeatUntilButtonText()
                } else {
                    openRepeatUntilDatePicker()
                }
            }
            .show()
    }

    private fun openRepeatUntilDatePicker() {
        val base = if (repeatUntil != 0L) repeatUntil else selectedDate
        val cal = Calendar.getInstance().apply { timeInMillis = base }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply { set(year, month, day, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
                repeatUntil = picked.timeInMillis
                updateRepeatUntilButtonText()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun startOfToday(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    companion object {
        const val EXTRA_DUE_DATE = "due_date"
        const val EXTRA_REPETITION = "repetition"
        const val EXTRA_REPEAT_UNTIL = "repeat_until"
        const val EXTRA_REMINDER_TIME_MINUTES = "reminder_time_minutes"
        const val EXTRA_REMINDER_END_TIME_MINUTES = "reminder_end_time_minutes"
    }
}
