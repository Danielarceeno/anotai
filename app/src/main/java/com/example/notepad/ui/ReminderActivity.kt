package com.example.notepad.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.notepad.R
import com.example.notepad.data.Note
import com.example.notepad.databinding.ActivityReminderBinding
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Sets a note's reminder/calendar date and repetition, styled after the
 * reference screenshot's "Calendar" date-entry screen (Dismiss / Type /
 * When / Repetition / Cancel-Done). When a repetition is chosen, a
 * "Repeat until" row appears so a recurring task can have an end date
 * instead of repeating forever.
 */
class ReminderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReminderBinding
    private var selectedDate: Long = 0L
    private var repeatUntil: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        selectedDate = intent.getLongExtra(EXTRA_DUE_DATE, 0L).takeIf { it != 0L } ?: startOfToday()
        repeatUntil = intent.getLongExtra(EXTRA_REPEAT_UNTIL, 0L)
        binding.repetitionSpinner.setSelection(intent.getIntExtra(EXTRA_REPETITION, Note.REPEAT_NONE))
        updateDateButtonText()
        updateRepeatUntilVisibility()
        updateRepeatUntilButtonText()

        binding.dismissButton.setOnClickListener {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_DUE_DATE, 0L)
                    .putExtra(EXTRA_REPETITION, Note.REPEAT_NONE)
                    .putExtra(EXTRA_REPEAT_UNTIL, 0L)
            )
            finish()
        }
        binding.todayButton.setOnClickListener {
            selectedDate = startOfToday()
            updateDateButtonText()
        }
        binding.dateButton.setOnClickListener { openDatePicker() }
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
            )
            finish()
        }
    }

    private fun openDatePicker() {
        val cal = Calendar.getInstance().apply { timeInMillis = selectedDate }
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
        binding.dateButton.text = DateFormat.getDateInstance(DateFormat.FULL).format(Date(selectedDate))
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
    }
}
