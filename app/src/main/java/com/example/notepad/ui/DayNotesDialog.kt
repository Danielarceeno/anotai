package com.example.notepad.ui

import android.content.Context
import android.graphics.Paint
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.notepad.R
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.data.RecurrenceUtils
import com.example.notepad.databinding.DialogDayNotesBinding

/**
 * Tapping a calendar day shows this: the notes already due that day (reference
 * screenshot's "Wed, October 14" popup), each with a "done" checkbox, plus an
 * "Add" button that opens the usual Text/Checklist picker for a new note on
 * that day. Long-pressing a note opens a quick-action menu (mark done, open
 * reminder, remove), mirroring the reference app's long-press context menu.
 */
object DayNotesDialog {

    fun show(
        context: Context,
        dayLabel: String,
        dayMillis: Long,
        notes: List<Note>,
        onNoteClick: (Note) -> Unit,
        onToggleComplete: (Note, Boolean) -> Unit,
        onRemove: (Note) -> Unit,
        onAddText: () -> Unit,
        onAddChecklist: () -> Unit,
        onPrevDay: () -> Unit = {},
        onNextDay: () -> Unit = {},
        /** [itemIndex]'s new checked state for a [Note.TYPE_CHECKLIST] note, so the caller can
         *  persist it (see the per-item checkboxes added under each checklist note below). */
        onToggleChecklistItem: (Note, Int, Boolean) -> Unit = { _, _, _ -> }
    ) {
        val binding = DialogDayNotesBinding.inflate(LayoutInflater.from(context))
        binding.dayTitle.text = dayLabel

        val dialog = AlertDialog.Builder(context).setView(binding.root).create()

        binding.prevDayButton.setOnClickListener {
            dialog.dismiss()
            onPrevDay()
        }
        binding.nextDayButton.setOnClickListener {
            dialog.dismiss()
            onNextDay()
        }

        val rowPadding = (10 * context.resources.displayMetrics.density).toInt()
        val dotSize = (14 * context.resources.displayMetrics.density).toInt()
        val toggleSize = (22 * context.resources.displayMetrics.density).toInt()
        val barWidth = (4 * context.resources.displayMetrics.density).toInt()

        notes.forEach { note ->
            // Reference screenshots (imagens/imagem1.jpg, imagem2.jpg): a color bar flush
            // against the dialog's left edge for the note's own color, instead of a small
            // swatch inline with the title.
            val rowOuter = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val colorBar = android.view.View(context).apply {
                layoutParams = LinearLayout.LayoutParams(barWidth, LinearLayout.LayoutParams.MATCH_PARENT)
                setBackgroundColor(context.getColor(NoteColors.colorResFor(note.colorIndex)))
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(rowPadding, rowPadding, 0, rowPadding)
            }

            val isDoneToday = RecurrenceUtils.isCompletedOn(note, dayMillis)

            val title = TextView(context).apply {
                // A blank-titled checklist used to fall back to its raw serialized body here
                // (literal "[ ] " / "[x] " markers), which is what showed up as stray "[]"
                // text -- fall back to a plain label instead, since each item now gets its
                // own checkbox row below.
                text = note.title.ifBlank {
                    if (note.type == Note.TYPE_CHECKLIST) {
                        context.getString(R.string.note_type_checklist)
                    } else {
                        NoteFormatting.plainText(note.body, note.isFormatted).take(40)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                applyCompletedStyle(this, isDoneToday)
            }

            // A plain round toggle (filled = pending, hollow = done) instead of the default
            // square CheckBox widget, matching the reference screenshots' lime-green circles.
            val doneCheckBox = CheckBox(context).apply {
                layoutParams = LinearLayout.LayoutParams(toggleSize, toggleSize).apply { marginEnd = rowPadding }
                text = null
                background = null
                buttonDrawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.selector_note_done_toggle)
                isChecked = isDoneToday
                contentDescription = context.getString(R.string.mark_done)
                setOnCheckedChangeListener { button, isChecked ->
                    button.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    applyCompletedStyle(title, isChecked)
                    onToggleComplete(note, isChecked)
                }
            }

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    dialog.dismiss()
                    onNoteClick(note)
                }
                setOnLongClickListener { anchor ->
                    showQuickActions(context, anchor, note, dayMillis, dialog, doneCheckBox, onNoteClick, onRemove)
                    true
                }
            }

            content.addView(title)
            if (note.pinned) {
                // Same star used for "pinned" in the main notes list (NoteAdapter) -- pinned
                // notes used to show no distinction at all here, only in that list.
                val pinIcon = android.widget.ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginStart = rowPadding / 2 }
                    setImageResource(R.drawable.ic_star_filled)
                    contentDescription = context.getString(R.string.note_pinned)
                    setColorFilter(context.getColor(R.color.on_surface_variant))
                }
                content.addView(pinIcon)
            }
            if (note.repetition != Note.REPEAT_NONE) {
                val repeatIcon = android.widget.ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply { marginStart = rowPadding / 2 }
                    setImageResource(R.drawable.ic_repeat)
                    contentDescription = context.getString(R.string.note_repeats)
                    alpha = 0.6f
                }
                content.addView(repeatIcon)
            }
            row.addView(content)
            row.addView(doneCheckBox)
            rowOuter.addView(colorBar)
            rowOuter.addView(row)
            binding.notesContainer.addView(rowOuter)

            if (note.type == Note.TYPE_CHECKLIST) {
                addChecklistItemRows(context, binding.notesContainer, note, rowPadding, dotSize, onToggleChecklistItem)
            }
        }

        binding.addButton.setOnClickListener {
            dialog.dismiss()
            AddNoteTypeDialog.show(context, onText = onAddText, onChecklist = onAddChecklist)
        }

        dialog.show()
    }

    /** One small checkbox + text row per checklist item, indented under the note's title row
     *  (matching the "a checkbox for each item" request -- a blank-titled checklist used to
     *  just show its raw "[ ]"-prefixed body instead). Each checkbox is independently
     *  interactive and reported back through [onToggleChecklistItem] for the caller to persist. */
    private fun addChecklistItemRows(
        context: Context,
        container: LinearLayout,
        note: Note,
        rowPadding: Int,
        dotSize: Int,
        onToggleChecklistItem: (Note, Int, Boolean) -> Unit
    ) {
        val items = ChecklistItem.parse(note.body)
        items.forEachIndexed { index, item ->
            val itemRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dotSize + rowPadding, 0, 0, rowPadding / 2)
            }

            val itemText = TextView(context).apply {
                text = item.text
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = rowPadding
                }
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                applyCompletedStyle(this, item.checked)
            }

            val itemCheckBox = CheckBox(context).apply {
                isChecked = item.checked
                contentDescription = item.text
                setOnCheckedChangeListener { button, isChecked ->
                    button.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                    applyCompletedStyle(itemText, isChecked)
                    onToggleChecklistItem(note, index, isChecked)
                }
            }

            itemRow.addView(itemCheckBox)
            itemRow.addView(itemText)
            container.addView(itemRow)
        }
    }

    private fun showQuickActions(
        context: Context,
        anchor: android.view.View,
        note: Note,
        dayMillis: Long,
        dialog: AlertDialog,
        doneCheckBox: CheckBox,
        onNoteClick: (Note) -> Unit,
        onRemove: (Note) -> Unit
    ) {
        val isDoneToday = RecurrenceUtils.isCompletedOn(note, dayMillis)
        val popup = PopupMenu(context, anchor)
        popup.menu.add(
            if (isDoneToday) context.getString(R.string.action_mark_undone) else context.getString(R.string.action_mark_done)
        ).setOnMenuItemClickListener {
            // Toggling the checkbox itself (rather than calling onToggleComplete directly) reuses
            // its existing listener, so the strikethrough/checkmark update immediately in this
            // already-open dialog instead of only on the next time it's opened.
            doneCheckBox.isChecked = !isDoneToday
            true
        }
        popup.menu.add(context.getString(R.string.action_reminder)).setOnMenuItemClickListener {
            dialog.dismiss()
            onNoteClick(note)
            true
        }
        popup.menu.add(context.getString(R.string.action_remove)).setOnMenuItemClickListener {
            onRemove(note)
            dialog.dismiss()
            true
        }
        popup.show()
    }

    private fun applyCompletedStyle(title: TextView, completed: Boolean) {
        title.paintFlags = if (completed) {
            title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        title.alpha = if (completed) 0.5f else 1f
    }
}
