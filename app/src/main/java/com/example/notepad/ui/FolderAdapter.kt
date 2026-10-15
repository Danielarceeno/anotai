package com.example.notepad.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.notepad.data.Folder
import com.example.notepad.databinding.ItemFolderBinding

class FolderAdapter(
    private val onClick: (Folder) -> Unit,
    private val onRename: (Folder) -> Unit,
    private val onDelete: (Folder) -> Unit
) : RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    private var folders: List<Folder> = emptyList()

    fun submitList(items: List<Folder>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = folders.size
            override fun getNewListSize() = items.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                folders[oldItemPosition].id == items[newItemPosition].id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                folders[oldItemPosition] == items[newItemPosition]
        })
        folders = items
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(folders[position])

    override fun getItemCount(): Int = folders.size

    inner class ViewHolder(private val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(folder: Folder) {
            binding.nameView.text = folder.name
            binding.root.setOnClickListener { onClick(folder) }
            binding.renameButton.setOnClickListener { onRename(folder) }
            binding.deleteButton.setOnClickListener { onDelete(folder) }
        }
    }
}
