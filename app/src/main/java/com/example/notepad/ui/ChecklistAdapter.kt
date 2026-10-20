package com.example.notepad.ui

import android.annotation.SuppressLint
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.RecyclerView
import com.example.notepad.data.ChecklistItem
import com.example.notepad.databinding.ItemChecklistAddBinding
import com.example.notepad.databinding.ItemChecklistBinding

/**
 * The trailing row (position == [items].size, [VIEW_TYPE_ADD]) is always an "Add item" row
 * rendered in the same list as the real items -- not a separate input bar below the
 * RecyclerView -- so the whole checklist reads as one continuous list, matching the reference
 * screenshot's style: every row looks the same, an empty one just shows a muted "+ Add item"
 * placeholder instead of a checkbox and typed text. Submitting it (IME "Next") calls [addItem],
 * which inserts a real row just above this one -- this one stays last, cleared and ready for the
 * next item, no manual refocus needed, so adding several items in a row is one continuous flow.
 */
class ChecklistAdapter(
    private val items: MutableList<ChecklistItem>,
    private val onChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var textColor: Int? = null

    override fun getItemViewType(position: Int): Int =
        if (position == items.size) VIEW_TYPE_ADD else VIEW_TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_ADD) {
            AddViewHolder(ItemChecklistAddBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            ViewHolder(ItemChecklistBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ViewHolder -> holder.bind(items[position])
            is AddViewHolder -> holder.bind()
        }
    }

    /** +1 for the always-present trailing [VIEW_TYPE_ADD] row. */
    override fun getItemCount(): Int = items.size + 1

    fun addItem(text: String) {
        items.add(ChecklistItem(text, checked = false))
        // The add row's own position shifts from items.size to the new items.size automatically
        // once RecyclerView sees the new item count; this only needs to announce the inserted
        // real row, not touch the add row itself.
        notifyItemInserted(items.size - 1)
        onChanged()
    }

    /** Only ever called once, to load a note's items when the editor first opens, so a full
     *  reset is correct here -- there's no previous content on screen to diff against. */
    @SuppressLint("NotifyDataSetChanged")
    fun setItems(newItems: List<ChecklistItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    /** Called by the [androidx.recyclerview.widget.ItemTouchHelper] while a drag is in progress. */
    fun moveItem(fromPosition: Int, toPosition: Int) {
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        onChanged()
    }

    /** Matches the checklist rows' text to the editor's current note-color tint. */
    fun setTextColor(color: Int) {
        textColor = color
        notifyItemRangeChanged(0, itemCount)
    }

    inner class ViewHolder(private val binding: ItemChecklistBinding) : RecyclerView.ViewHolder(binding.root) {
        private var watcher: TextWatcher? = null

        @SuppressLint("ClickableViewAccessibility")
        fun bind(item: ChecklistItem) {
            watcher?.let { binding.itemText.removeTextChangedListener(it) }

            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }

            binding.checkBox.isChecked = item.checked
            binding.itemText.setText(item.text)
            applyStrikethrough(item.checked)
            textColor?.let { binding.itemText.setTextColor(it) }

            binding.checkBox.setOnCheckedChangeListener { _, isChecked ->
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                items[pos] = items[pos].copy(checked = isChecked)
                applyStrikethrough(isChecked)
                onChanged()
            }

            binding.removeButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                items.removeAt(pos)
                notifyItemRemoved(pos)
                onChanged()
            }

            val newWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return
                    items[pos] = items[pos].copy(text = s.toString())
                    onChanged()
                }
            }
            binding.itemText.addTextChangedListener(newWatcher)
            watcher = newWatcher
        }

        private fun applyStrikethrough(checked: Boolean) {
            binding.itemText.paintFlags = if (checked) {
                binding.itemText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                binding.itemText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }
    }

    inner class AddViewHolder(private val binding: ItemChecklistAddBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            // Never carries a draft across rebinds -- this row is always "empty, ready to type
            // into", never a persisted item (those live in the ViewHolder rows above it).
            binding.newItemText.setText("")
            binding.newItemText.setOnEditorActionListener { textView, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_NEXT) {
                    val text = textView.text.toString()
                    if (text.isNotBlank()) addItem(text)
                    true
                } else {
                    false
                }
            }
        }
    }

    companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_ADD = 1
    }
}
