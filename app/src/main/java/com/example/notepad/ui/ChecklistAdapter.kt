package com.example.notepad.ui

import android.annotation.SuppressLint
import android.graphics.Paint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.notepad.data.ChecklistItem
import com.example.notepad.databinding.ItemChecklistBinding

class ChecklistAdapter(
    private val items: MutableList<ChecklistItem>,
    private val onChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

    private var textColor: Int? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChecklistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    fun addItem(text: String) {
        items.add(ChecklistItem(text, checked = false))
        notifyItemInserted(items.lastIndex)
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
}
