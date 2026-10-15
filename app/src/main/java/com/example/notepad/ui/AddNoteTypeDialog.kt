package com.example.notepad.ui

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.example.notepad.R
import com.example.notepad.databinding.DialogAddNoteBinding

/** The "Add note: Text / Checklist" picker shown from the ColorNote reference screenshots. */
object AddNoteTypeDialog {

    fun show(context: Context, onText: () -> Unit, onChecklist: () -> Unit) {
        val binding = DialogAddNoteBinding.inflate(android.view.LayoutInflater.from(context))
        binding.dialogTitle.text = context.getString(R.string.add_note_dialog_title)

        val dialog = AlertDialog.Builder(context)
            .setView(binding.root)
            .create()

        binding.optionText.setOnClickListener {
            dialog.dismiss()
            onText()
        }
        binding.optionChecklist.setOnClickListener {
            dialog.dismiss()
            onChecklist()
        }
        dialog.show()
    }
}
