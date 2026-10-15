package com.example.notepad.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.databinding.ItemTrashNoteBinding

class TrashAdapter(
    private val onRestore: (Note) -> Unit,
    private val onDeleteForever: (Note) -> Unit
) : RecyclerView.Adapter<TrashAdapter.ViewHolder>() {

    private var notes: List<Note> = emptyList()

    fun submitList(items: List<Note>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = notes.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                notes[oldItemPosition].id == items[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                notes[oldItemPosition] == items[newItemPosition]
        })
        notes = items
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTrashNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(notes[position], onRestore, onDeleteForever)

    override fun getItemCount(): Int = notes.size

    class ViewHolder(private val binding: ItemTrashNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(note: Note, onRestore: (Note) -> Unit, onDeleteForever: (Note) -> Unit) {
            binding.titleView.text = note.title.ifBlank { "(" + binding.root.context.getString(com.example.notepad.R.string.title_hint) + ")" }
            binding.bodyView.text = if (note.type == Note.TYPE_CHECKLIST) {
                val items = ChecklistItem.parse(note.body)
                "${items.count { it.checked }}/${items.size} done"
            } else {
                NoteFormatting.plainText(note.body, note.isFormatted)
            }
            binding.restoreButton.setOnClickListener { onRestore(note) }
            binding.deleteForeverButton.setOnClickListener { onDeleteForever(note) }
        }
    }
}
