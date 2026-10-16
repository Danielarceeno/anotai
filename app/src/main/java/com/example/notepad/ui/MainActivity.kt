package com.example.notepad.ui

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.FolderRepository
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.databinding.ActivityMainBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Notes list, reached from [CalendarActivity] (the app's launcher) via its "Notes" menu item.
 * Also doubles as a single folder's note list when launched with [EXTRA_FOLDER_ID] (from
 * [FoldersActivity]): new notes created from here are filed into that folder automatically.
 *
 * Long-pressing a note enters selection mode (toolbar swaps to a count + bulk move/delete),
 * which is how multiple notes get moved to a folder or trashed at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: PagedNoteAdapter
    private lateinit var repository: NoteRepository
    private lateinit var folderRepository: FolderRepository
    private var folderId: Long = 0L
    private val selectedIds = mutableSetOf<Long>()
    private var originalTitle: CharSequence = ""
    private val sortModeFlow = MutableStateFlow(0)

    /** Swipe a card left or right to trash it immediately (with the same undo snackbar as the
     *  bulk-delete path), matching the reference app's swipe-to-delete gesture. Disabled while
     *  in selection mode ([getSwipeDirs] returns 0 then) so a swipe can't fight with a long-press
     *  selection drag. Deliberately never calls notifyItemRemoved/notifyItemChanged on [adapter]
     *  itself -- see the itemAnimator/confirmBulkDelete comments above for why a manual notify
     *  racing Room's own PagingData emission was what caused the earlier RecyclerView crash;
     *  the swiped-out row just disappears on its own once that emission lands.
     */
    // by lazy, not a plain field initializer: Activity field initializers run in the
    // constructor, before the base Context is attached, so ContextCompat.getDrawable(this, ...)
    // below would hit a null getResources() -- the exact crash CalendarActivity's
    // monthSlideDistancePx field hit earlier for the same reason. Deferred to first access
    // (onCreate's attachToRecyclerView call), well after the Context is attached.
    private val swipeToDeleteHelper by lazy { ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
    ) {
        private val deleteIcon = ContextCompat.getDrawable(this@MainActivity, android.R.drawable.ic_menu_delete)?.apply {
            setTint(Color.WHITE)
        }
        private val background = ColorDrawable(Color.parseColor("#D32F2F"))

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (selectedIds.isNotEmpty()) 0 else super.getSwipeDirs(recyclerView, viewHolder)

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val position = viewHolder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            adapter.peek(position)?.let { deleteNoteWithUndo(it) }
        }

        override fun onChildDraw(
            c: Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) return
            val item = viewHolder.itemView
            background.setBounds(item.left, item.top, item.right, item.bottom)
            background.draw(c)

            val iconMargin = (item.height - (deleteIcon?.intrinsicHeight ?: 0)) / 2
            val iconTop = item.top + iconMargin
            val iconBottom = iconTop + (deleteIcon?.intrinsicHeight ?: 0)
            deleteIcon?.let { icon ->
                val iconWidth = icon.intrinsicWidth
                if (dX > 0) {
                    icon.setBounds(item.left + iconMargin, iconTop, item.left + iconMargin + iconWidth, iconBottom)
                } else if (dX < 0) {
                    icon.setBounds(item.right - iconMargin - iconWidth, iconTop, item.right - iconMargin, iconBottom)
                }
                if (abs(dX) > iconMargin) icon.draw(c)
            }
        }
    }) }

    private fun deleteNoteWithUndo(note: Note) {
        lifecycleScope.launch {
            repository.delete(note.id)
            com.example.notepad.notification.NoteReminderScheduler.cancel(this@MainActivity, note.id)
        }
        Snackbar.make(binding.root, R.string.note_deleted_message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                lifecycleScope.launch {
                    repository.restore(note.id)
                    repository.getById(note.id)?.let { com.example.notepad.notification.NoteReminderScheduler.schedule(this@MainActivity, it) }
                }
            }
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())
        folderRepository = FolderRepository(db.folderDao(), db.noteDao())
        folderId = intent.getLongExtra(EXTRA_FOLDER_ID, 0L)
        intent.getStringExtra(EXTRA_FOLDER_NAME)?.let { binding.toolbar.title = it }
        originalTitle = binding.toolbar.title ?: ""

        adapter = PagedNoteAdapter(
            onClick = { note -> if (selectedIds.isEmpty()) openEditor(note.id, note.type) else toggleSelection(note) },
            onTogglePin = { note -> togglePin(note) },
            onLongClick = { note -> toggleSelection(note) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        // Paging3's own AsyncPagingDataDiffer dispatches its own granular notify calls as pages
        // load/change; RecyclerView's default item animator doing its predictive-layout pass on
        // top of that was crashing with "Inconsistency detected. Invalid view holder adapter
        // position" right after a delete (a second PagingData generation landing mid-animation).
        // No animations on this list is an acceptable trade-off for not crashing.
        binding.recyclerView.itemAnimator = null
        swipeToDeleteHelper.attachToRecyclerView(binding.recyclerView)

        sortModeFlow.value = NotesSortPrefs.getSortMode(this)
        lifecycleScope.launch {
            sortModeFlow.flatMapLatest { sortMode -> repository.observeAllPaged(folderId, sortMode) }
                .collectLatest { pagingData ->
                    adapter.submitData(pagingData)
                    adapter.setSelection(selectedIds.toSet())
                }
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
                R.id.action_select_all -> { selectAll(); true }
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
        // Forces the paged list to reload right away instead of waiting on Room's own
        // invalidation timing, which is noticeably laggy for this specific path: a note deleted
        // from the editor (NoteEditorActivity.deleteAndFinish) is already gone from the database
        // by the time this screen resumes, but without this it could still render for close to a
        // second before disappearing, looking like the delete hadn't actually happened yet.
        adapter.refresh()
        UndoDeleteState.consumeRecent()?.let { noteId -> showUndoSnackbar(noteId) }
    }

    private fun showUndoSnackbar(noteId: Long) {
        Snackbar.make(binding.root, R.string.note_deleted_message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                lifecycleScope.launch {
                    repository.restore(noteId)
                    repository.getById(noteId)?.let { com.example.notepad.notification.NoteReminderScheduler.schedule(this@MainActivity, it) }
                }
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
        menu.findItem(R.id.action_select_all)?.isVisible = inSelection
        menu.findItem(R.id.action_bulk_delete)?.isVisible = inSelection
        menu.findItem(R.id.action_bulk_move)?.isVisible = inSelection
    }

    /** Grabs every note's id in the current scope (not just whatever a [PagedNoteAdapter] has
     *  loaded so far), so "select all" then bulk delete/move works regardless of list length. */
    private fun selectAll() {
        lifecycleScope.launch {
            selectedIds.clear()
            selectedIds.addAll(repository.getAllIds(folderId))
            updateSelectionUi()
        }
    }

    private fun confirmBulkDelete() {
        val count = selectedIds.size
        AlertDialog.Builder(this)
            .setMessage(resources.getQuantityString(R.plurals.bulk_delete_confirm, count, count))
            .setPositiveButton(R.string.action_remove) { _, _ ->
                val ids = selectedIds.toList()
                lifecycleScope.launch {
                    ids.forEach {
                        repository.delete(it)
                        com.example.notepad.notification.NoteReminderScheduler.cancel(this@MainActivity, it)
                    }
                    // No explicit adapter.refresh() here: Room already invalidates the paged
                    // query on delete, so the list's own collectLatest picks it up. Calling
                    // refresh() too raced a second PagingData generation against that automatic
                    // one and was the proximate cause of the RecyclerView "Inconsistency
                    // detected" crash right after deleting.
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
                    val loaded = adapter.snapshot().items
                    lifecycleScope.launch {
                        ids.forEach { id ->
                            (loaded.firstOrNull { it.id == id } ?: repository.getById(id))?.let { note ->
                                repository.update(note.copy(folderId = targetFolderId))
                            }
                        }
                    }
                    clearSelection()
                }
                .show()
        }
    }

    private fun showSortDialog() {
        val options = arrayOf(
            getString(R.string.sort_recent),
            getString(R.string.sort_color),
            getString(R.string.sort_title),
            getString(R.string.sort_due_date)
        )
        val current = NotesSortPrefs.getSortMode(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sort)
            .setSingleChoiceItems(options, current) { dialog, which ->
                NotesSortPrefs.setSortMode(this, which)
                sortModeFlow.value = which
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
