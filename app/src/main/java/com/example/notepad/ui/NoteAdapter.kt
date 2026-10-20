package com.example.notepad.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Note
import com.example.notepad.databinding.ItemNoteBinding

class NoteAdapter(
    private val onClick: (Note) -> Unit,
    private val onTogglePin: (Note) -> Unit = {},
    private val onLongClick: (Note) -> Unit = {}
) : RecyclerView.Adapter<NoteAdapter.ViewHolder>() {

    private var notes: List<Note> = emptyList()
    private var selectedIds: Set<Long> = emptySet()

    /** Query currently highlighted in results, or blank outside search. */
    var highlight: String = ""
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

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
        val note = notes[position]
        holder.bind(note, highlight, selectedIds.contains(note.id), selectedIds.isNotEmpty(), onClick, onTogglePin, onLongClick)
    }

    override fun getItemCount(): Int = notes.size

    class ViewHolder(private val binding: ItemNoteBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            note: Note,
            highlight: String,
            isSelected: Boolean,
            inSelectionMode: Boolean,
            onClick: (Note) -> Unit,
            onTogglePin: (Note) -> Unit,
            onLongClick: (Note) -> Unit
        ) {
            binding.titleView.text = highlighted(note.title, highlight)
            binding.bodyView.text = if (note.type == Note.TYPE_CHECKLIST) {
                val items = ChecklistItem.parse(note.body)
                val done = items.count { it.checked }
                "$done/${items.size} done"
            } else {
                highlighted(NoteFormatting.plainText(note.body, note.isFormatted), highlight)
            }
            val cardColor = binding.root.context.getColor(NoteColors.colorResFor(note.colorIndex))
            binding.root.setCardBackgroundColor(cardColor)
            // Note cards keep the same vivid palette regardless of app theme (see
            // NoteColors), so text color must adapt to each card's own color
            // (e.g. white text on the black/blue/purple swatches) rather than
            // inheriting the theme's text color or staying fixed dark.
            val textColor = if (NoteColors.isLight(cardColor)) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
            binding.titleView.setTextColor(textColor)
            binding.bodyView.setTextColor(textColor)
            binding.repeatIcon.visibility = if (note.repetition != Note.REPEAT_NONE) View.VISIBLE else View.GONE
            binding.repeatIcon.imageTintList = ColorStateList.valueOf(textColor)
            binding.root.setOnClickListener { onClick(note) }
            binding.root.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongClick(note)
                true
            }
            // Lightens the card's own color on touch-down, back to normal on release -- see
            // NoteColors.pressColor's doc for why a plain fixed-color ripple doesn't work well
            // here. Returns false so the click/long-click listeners above still fire normally.
            binding.root.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        (view as com.google.android.material.card.MaterialCardView).setCardBackgroundColor(NoteColors.pressColor(cardColor))
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        (view as com.google.android.material.card.MaterialCardView).setCardBackgroundColor(cardColor)
                }
                false
            }

            val strokeWidthPx = (3 * binding.root.context.resources.displayMetrics.density).toInt()
            binding.root.strokeWidth = if (isSelected) strokeWidthPx else 0
            binding.root.strokeColor = binding.root.context.getColor(com.example.notepad.R.color.accent_teal)
            binding.root.alpha = if (inSelectionMode && !isSelected) 0.6f else 1f

            binding.pinButton.setImageResource(
                if (note.pinned) com.example.notepad.R.drawable.ic_star_filled else com.example.notepad.R.drawable.ic_star_outline
            )
            binding.pinButton.imageTintList = ColorStateList.valueOf(textColor)
            binding.pinButton.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                if (inSelectionMode) onLongClick(note) else onTogglePin(note)
            }
        }

        private fun highlighted(text: String, query: String): CharSequence {
            if (query.isBlank()) return text
            val start = text.indexOf(query, ignoreCase = true)
            if (start == -1) return text
            return SpannableString(text).apply {
                setSpan(
                    BackgroundColorSpan(Color.YELLOW),
                    start,
                    start + query.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }
}
