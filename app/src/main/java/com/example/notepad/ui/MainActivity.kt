package com.example.notepad.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.FolderRepository
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

/**
 * Notes list, reached from [CalendarActivity] (the app's launcher) via its "Notes" menu item.
 * Also doubles as a single folder's note list when launched with [EXTRA_FOLDER_ID] (from
 * [FoldersActivity]): new notes created from here are filed into that folder automatically.
 *
 * Long-pressing a note enters selection mode (toolbar swaps to a count + bulk move/delete),
 * which is how multiple notes get moved to a folder or trashed at once.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: NoteAdapter
    private lateinit var repository: NoteRepository
    private lateinit var folderRepository: FolderRepository
    private var folderId: Long = 0L
    private var latestNotes: List<Note> = emptyList()
    private val selectedIds = mutableSetOf<Long>()
    private var originalTitle: CharSequence = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = NoteRepository(db.noteDao(), applicationContext)
        folderRepository = FolderRepository(db.folderDao(), db.noteDao())
        folderId = intent.getLongExtra(EXTRA_FOLDER_ID, 0L)
        intent.getStringExtra(EXTRA_FOLDER_NAME)?.let { binding.toolbar.title = it }
        originalTitle = binding.toolbar.title ?: ""

        adapter = NoteAdapter(
            onClick = { note -> if (selectedIds.isEmpty()) openEditor(note.id, note.type) else toggleSelection(note) },
            onTogglePin = { note -> togglePin(note) },
            onLongClick = { note -> toggleSelection(note) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        val notes = if (folderId != 0L) db.noteDao().observeByFolder(folderId) else db.noteDao().observeAll()
        notes.observe(this) { list ->
            latestNotes = list
            renderSortedList()
        }

        binding.addNoteButton.setOnClickListener {
            AddNoteTypeDialog.show(
                this,
                onText = { openEditor(noteId = null, type = Note.TYPE_TEXT) },
                onChecklist = { openEditor(noteId = null, type = Note.TYPE_CHECKLIST) }
            )
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_search -> { startActivity(Intent(this, SearchActivity::class.java)); true }
                R.id.action_calendar -> { startActivity(Intent(this, CalendarActivity::class.java)); true }
                R.id.action_folders -> { startActivity(Intent(this, FoldersActivity::class.java)); true }
                R.id.action_trash -> { startActivity(Intent(this, TrashActivity::class.java)); true }
                R.id.action_sort -> { showSortDialog(); true }
                R.id.action_bulk_delete -> { confirmBulkDelete(); true }
                R.id.action_bulk_move -> { showBulkMoveDialog(); true }
                else -> false
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (selectedIds.isNotEmpty()) {
                clearSelection()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        UndoDeleteState.consumeRecent()?.let { noteId -> showUndoSnackbar(noteId) }
    }

    private fun showUndoSnackbar(noteId: Long) {
        Snackbar.make(binding.root, R.string.note_deleted_message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                lifecycleScope.launch { repository.restore(noteId) }
            }
            .show()
    }

    private fun toggleSelection(note: Note) {
        if (!selectedIds.remove(note.id)) selectedIds.add(note.id)
        updateSelectionUi()
    }

    private fun clearSelection() {
        selectedIds.clear()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        adapter.setSelection(selectedIds.toSet())
        val inSelection = selectedIds.isNotEmpty()
        binding.toolbar.title = if (inSelection) {
            resources.getQuantityString(R.plurals.selected_count, selectedIds.size, selectedIds.size)
        } else {
            originalTitle
        }

        val menu = binding.toolbar.menu
        for (id in intArrayOf(R.id.action_calendar, R.id.action_search, R.id.action_folders, R.id.action_trash, R.id.action_sort)) {
            menu.findItem(id)?.isVisible = !inSelection
        }
        menu.findItem(R.id.action_bulk_delete)?.isVisible = inSelection
        menu.findItem(R.id.action_bulk_move)?.isVisible = inSelection
    }

    private fun confirmBulkDelete() {
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setMessage(resources.getQuantityString(R.plurals.bulk_delete_confirm, count, count))
            .setPositiveButton(R.string.action_remove) { _, _ ->
                val ids = selectedIds.toList()
                lifecycleScope.launch {
                    ids.forEach { repository.delete(it) }
                }
                clearSelection()
            }
            .setNegativeButton(R.string.reminder_cancel, null)
            .show()
    }

    private fun showBulkMoveDialog() {
        lifecycleScope.launch {
            val folders = folderRepository.getAll()
            val labels = mutableListOf(getString(R.string.folder_none))
            labels.addAll(folders.map { it.name })

            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.folder_choose)
                .setItems(labels.toTypedArray()) { _, which ->
                    val targetFolderId = if (which == 0) 0L else folders[which - 1].id
                    val ids = selectedIds.toList()
                    lifecycleScope.launch {
                        ids.forEach { id ->
                            latestNotes.firstOrNull { it.id == id }?.let { note ->
                                repository.update(note.copy(folderId = targetFolderId))
                            }
                        }
                    }
                    clearSelection()
                }
                .show()
        }
    }

    private fun renderSortedList() {
        adapter.submitList(NotesSortPrefs.apply(latestNotes, NotesSortPrefs.getSortMode(this)))
        adapter.setSelection(selectedIds.toSet())
    }

    private fun showSortDialog() {
        val options = arrayOf(getString(R.string.sort_recent), getString(R.string.sort_color))
        val current = NotesSortPrefs.getSortMode(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(options, current) { dialog, which ->
                NotesSortPrefs.setSortMode(this, which)
                renderSortedList()
                dialog.dismiss()
            }
            .show()
    }

    private fun togglePin(note: Note) {
        lifecycleScope.launch { repository.update(note.copy(pinned = !note.pinned)) }
    }

    private fun openEditor(noteId: Long?, type: Int) {
        val intent = Intent(this, NoteEditorActivity::class.java)
        if (noteId != null) intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, noteId)
        intent.putExtra(NoteEditorActivity.EXTRA_NOTE_TYPE, type)
        if (noteId == null && folderId != 0L) intent.putExtra(NoteEditorActivity.EXTRA_FOLDER_ID, folderId)
        startActivity(intent)
    }

    companion object {
        const val EXTRA_FOLDER_ID = "folder_id"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }
}
