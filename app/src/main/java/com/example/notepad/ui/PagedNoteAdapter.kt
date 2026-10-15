package com.example.notepad.ui

import android.content.res.ColorStateList
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.ViewGroup
import com.example.notepad.R
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.databinding.ItemNoteBinding

/**
 * [MainActivity]'s paged counterpart to [NoteAdapter] (which [SearchActivity] still uses
 * unpaged, since a search result set is small enough not to need it). Same card rendering as
 * [NoteAdapter.ViewHolder.bind], just driven by [androidx.paging.PagingData] instead of a plain
 * `List<Note>`, so it can't share that class directly -- [PagingDataAdapter] and
 * [RecyclerView.Adapter] have incompatible submission APIs (`submitData` vs `submitList`).
 */
class PagedNoteAdapter(
    private val onClick: (Note) -> Unit,
    private val onTogglePin: (Note) -> Unit,
    private val onLongClick: (Note) -> Unit
) : PagingDataAdapter<Note, PagedNoteAdapter.ViewHolder>(DIFF_CALLBACK) {

    private var selectedIds: Set<Long> = emptySet()

    /** Empty set means "not in selection mode" -- every card renders normally. */
    fun setSelection(ids: Set<Long>) {
        selectedIds = ids
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val note = getItem(position) ?: return
        holder.bind(note, selectedIds.contains(note.id), selectedIds.isNotEmpty(), onClick, onTogglePin, onLongClick)
    }

    class ViewHolder(private val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            note: Note,
            isSelected: Boolean,
            inSelectionMode: Boolean,
            onClick: (Note) -> Unit,
            onTogglePin: (Note) -> Unit,
            onLongClick: (Note) -> Unit
        ) {
            binding.titleView.text = note.title
            binding.bodyView.text = if (note.type == Note.TYPE_CHECKLIST) {
                val items = ChecklistItem.parse(note.body)
                val done = items.count { it.checked }
                "$done/${items.size} done"
            } else {
                NoteFormatting.plainText(note.body, note.isFormatted)
            }
            val cardColor = binding.root.context.getColor(NoteColors.colorResFor(note.colorIndex))
            binding.root.setCardBackgroundColor(cardColor)
            val textColor = if (NoteColors.isLight(cardColor)) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
            binding.titleView.setTextColor(textColor)
            binding.bodyView.setTextColor(textColor)
            binding.repeatIcon.visibility = if (note.repetition != Note.REPEAT_NONE) android.view.View.VISIBLE else android.view.View.GONE
            binding.repeatIcon.imageTintList = ColorStateList.valueOf(textColor)
            binding.root.setOnClickListener { onClick(note) }
            binding.root.setOnLongClickListener { onLongClick(note); true }

            val strokeWidthPx = (3 * binding.root.context.resources.displayMetrics.density).toInt()
            binding.root.strokeWidth = if (isSelected) strokeWidthPx else 0
            binding.root.strokeColor = binding.root.context.getColor(R.color.accent_teal)
            binding.root.alpha = if (inSelectionMode && !isSelected) 0.6f else 1f

            binding.pinButton.setImageResource(if (note.pinned) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            binding.pinButton.imageTintList = ColorStateList.valueOf(textColor)
            binding.pinButton.setOnClickListener {
                if (inSelectionMode) onLongClick(note) else onTogglePin(note)
            }
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
        }
    }
}
