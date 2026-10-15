package com.example.notepad.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.Folder
import com.example.notepad.data.FolderRepository
import com.example.notepad.databinding.ActivityFoldersBinding
import com.example.notepad.databinding.DialogTextInputBinding
import kotlinx.coroutines.launch

/** Create/rename/delete folders, and tap one to see the notes filed under it. */
class FoldersActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFoldersBinding
    private lateinit var repository: FolderRepository
    private lateinit var adapter: FolderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFoldersBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = FolderRepository(db.folderDao(), db.noteDao())

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FolderAdapter(
            onClick = { folder -> openFolder(folder) },
            onRename = { folder -> showRenameDialog(folder) },
            onDelete = { folder -> confirmDelete(folder) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        repository.observeAll().observe(this) { folders ->
            adapter.submitList(folders)
            binding.emptyView.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
        }

        binding.addFolderButton.setOnClickListener { showAddDialog() }
    }

    private fun openFolder(folder: Folder) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_FOLDER_ID, folder.id)
                .putExtra(MainActivity.EXTRA_FOLDER_NAME, folder.name)
        )
    }

    private fun showAddDialog() {
        val inputBinding = DialogTextInputBinding.inflate(layoutInflater)
        inputBinding.textInput.hint = getString(R.string.folder_name_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_add)
            .setView(inputBinding.root)
            .setPositiveButton(R.string.folder_add) { _, _ ->
                val name = inputBinding.textInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch { repository.insert(name) }
                }
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun showRenameDialog(folder: Folder) {
        val inputBinding = DialogTextInputBinding.inflate(layoutInflater)
        inputBinding.textInput.setText(folder.name)
        AlertDialog.Builder(this)
            .setTitle(R.string.folder_rename)
            .setView(inputBinding.root)
            .setPositiveButton(R.string.folder_rename) { _, _ ->
                val name = inputBinding.textInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch { repository.rename(folder, name) }
                }
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun confirmDelete(folder: Folder) {
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setMessage(R.string.folder_delete_confirm)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                lifecycleScope.launch { repository.delete(folder) }
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }
}
