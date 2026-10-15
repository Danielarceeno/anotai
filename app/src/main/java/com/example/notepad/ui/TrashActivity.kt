package com.example.notepad.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository
import com.example.notepad.databinding.ActivityTrashBinding
import kotlinx.coroutines.launch

/** Notes that were deleted stay here (not gone for good) until restored or emptied. */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var repository: NoteRepository
    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = NoteRepository(AppDatabase.getInstance(this).noteDao(), applicationContext)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_empty_trash) {
                confirmEmptyTrash()
                true
            } else {
                false
            }
        }

        adapter = TrashAdapter(
            onRestore = { note -> lifecycleScope.launch { repository.restore(note.id) } },
            onDeleteForever = { note -> confirmDeleteForever(note.id) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        repository.observeTrashed().observe(this) { notes ->
            adapter.submitList(notes)
            binding.emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteForever(noteId: Long) {
        AlertDialog.Builder(this)
            .setTitle(R.string.trash_delete_forever)
            .setMessage(R.string.trash_delete_forever_confirm)
            .setPositiveButton(R.string.trash_delete_forever) { _, _ ->
                lifecycleScope.launch { repository.deleteForever(noteId) }
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun confirmEmptyTrash() {
        AlertDialog.Builder(this)
            .setTitle(R.string.trash_empty_action)
            .setMessage(R.string.trash_empty_confirm)
            .setPositiveButton(R.string.trash_empty_action) { _, _ ->
                lifecycleScope.launch { repository.emptyTrash() }
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }
}
