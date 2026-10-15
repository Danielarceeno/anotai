package com.example.notepad.ui

import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.notepad.R
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.ChecklistItem
import com.example.notepad.data.Folder
import com.example.notepad.data.FolderRepository
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository
import com.example.notepad.databinding.ActivityNoteEditorBinding
import com.example.notepad.notification.NoteReminderScheduler
import com.example.notepad.notification.ReminderNotifier
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/**
 * Create/edit screen. Autosaves on exit (back press or losing focus),
 * mirroring how ColorNote itself has no explicit "Save" button: a note
 * is written as soon as there's a title or body and discarded if both
 * stay empty.
 */
class NoteEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditorBinding
    private lateinit var repository: NoteRepository
    private lateinit var folderRepository: FolderRepository
    private lateinit var checklistAdapter: ChecklistAdapter
    private val checklistItems = mutableListOf<ChecklistItem>()

    private var note: Note? = null
    private var isDeleting = false
    private var selectedColorIndex = 0
    private var noteType = Note.TYPE_TEXT
    private var selectedDueDate = 0L
    private var selectedRepetition = Note.REPEAT_NONE
    private var selectedRepeatUntil = 0L
    private var selectedReminderTimeMinutes = -1
    private var selectedFolderId = 0L
    private var selectedImagePath = ""
    private val colorSwatches = mutableListOf<ImageView>()
    private val colorRings = mutableListOf<View>()

    private val checklistTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
    ) {
        override fun onMove(
            recyclerView: androidx.recyclerview.widget.RecyclerView,
            viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
            target: androidx.recyclerview.widget.RecyclerView.ViewHolder
        ): Boolean {
            checklistAdapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}
    })

    private val reminderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: return@registerForActivityResult
        selectedDueDate = data.getLongExtra(ReminderActivity.EXTRA_DUE_DATE, 0L)
        selectedRepetition = data.getIntExtra(ReminderActivity.EXTRA_REPETITION, Note.REPEAT_NONE)
        selectedRepeatUntil = data.getLongExtra(ReminderActivity.EXTRA_REPEAT_UNTIL, 0L)
        selectedReminderTimeMinutes = data.getIntExtra(ReminderActivity.EXTRA_REMINDER_TIME_MINUTES, -1)
        updateReminderRowText()
    }

    private val imagePickLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) copyImageIntoStorage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = AppDatabase.getInstance(this)
        repository = NoteRepository(db.noteDao(), applicationContext)
        folderRepository = FolderRepository(db.folderDao(), db.noteDao())

        binding.folderRow.setOnClickListener { showFolderPicker() }
        binding.imageRow.setOnClickListener { imagePickLauncher.launch("image/*") }
        binding.removeImageButton.setOnClickListener { removeImage() }

        binding.doneButton.setOnClickListener {
            saveIfNeeded()
            finish()
        }
        binding.colorToggleContainer.setOnClickListener { toggleColorRow() }
        binding.overflowButton.setOnClickListener { showOverflowMenu() }

        onBackPressedDispatcher.addCallback(this) {
            saveIfNeeded()
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }

        setupChecklist()
        buildColorRow()
        updateReminderRowText()
        binding.reminderRow.setOnClickListener { openReminderScreen() }

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        if (noteId != -1L) {
            lifecycleScope.launch {
                note = repository.getById(noteId)
                binding.titleInput.setText(note?.title.orEmpty())
                noteType = note?.type ?: Note.TYPE_TEXT
                selectedDueDate = note?.dueDate ?: 0L
                selectedRepetition = note?.repetition ?: Note.REPEAT_NONE
                selectedRepeatUntil = note?.repeatUntil ?: 0L
                selectedReminderTimeMinutes = note?.reminderTimeMinutes ?: -1
                selectedFolderId = note?.folderId ?: 0L
                selectedImagePath = note?.imagePath ?: ""
                updateFolderRowText()
                updateImagePreview()
                applyTypeVisibility()
                updateReminderRowText()
                if (noteType == Note.TYPE_CHECKLIST) {
                    checklistAdapter.setItems(ChecklistItem.parse(note?.body.orEmpty()))
                } else {
                    binding.bodyInput.setText(note?.body.orEmpty())
                }
                binding.overflowButton.visibility = View.VISIBLE
                selectColor(note?.colorIndex ?: lastUsedColorIndex())
            }
        } else {
            noteType = intent.getIntExtra(EXTRA_NOTE_TYPE, Note.TYPE_TEXT)
            selectedDueDate = intent.getLongExtra(EXTRA_DUE_DATE, 0L)
            selectedFolderId = intent.getLongExtra(EXTRA_FOLDER_ID, 0L)
            applyTypeVisibility()
            updateReminderRowText()
            updateFolderRowText()
            updateImagePreview()
            // New note: pick up on the color the user picked last time, rather than
            // always resetting to the same default, so a color scheme sticks across notes.
            selectColor(lastUsedColorIndex())
        }
    }

    private fun setupChecklist() {
        checklistAdapter = ChecklistAdapter(
            checklistItems,
            onChanged = { /* items mutate in place, nothing else needed */ },
            onStartDrag = { holder -> checklistTouchHelper.startDrag(holder) }
        )
        binding.checklistRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.checklistRecyclerView.adapter = checklistAdapter
        checklistTouchHelper.attachToRecyclerView(binding.checklistRecyclerView)

        binding.newItemInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                val text = binding.newItemInput.text.toString()
                if (text.isNotBlank()) {
                    checklistAdapter.addItem(text)
                    binding.newItemInput.text?.clear()
                }
                true
            } else {
                false
            }
        }
    }

    private fun applyTypeVisibility() {
        val isChecklist = noteType == Note.TYPE_CHECKLIST
        binding.bodyScroll.visibility = if (isChecklist) View.GONE else View.VISIBLE
        binding.checklistContainer.visibility = if (isChecklist) View.VISIBLE else View.GONE
    }

    private fun openReminderScreen() {
        val intent = android.content.Intent(this, ReminderActivity::class.java)
        intent.putExtra(ReminderActivity.EXTRA_DUE_DATE, selectedDueDate)
        intent.putExtra(ReminderActivity.EXTRA_REPETITION, selectedRepetition)
        intent.putExtra(ReminderActivity.EXTRA_REPEAT_UNTIL, selectedRepeatUntil)
        intent.putExtra(ReminderActivity.EXTRA_REMINDER_TIME_MINUTES, selectedReminderTimeMinutes)
        reminderLauncher.launch(intent)
    }

    private fun updateReminderRowText() {
        val hasDueDate = selectedDueDate != 0L
        binding.reminderRow.text = when {
            !hasDueDate -> getString(R.string.set_reminder)
            selectedReminderTimeMinutes >= 0 -> {
                val dateText = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(selectedDueDate))
                val timeText = String.format(
                    java.util.Locale.getDefault(), "%02d:%02d",
                    selectedReminderTimeMinutes / 60, selectedReminderTimeMinutes % 60
                )
                "$dateText, $timeText"
            }
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(selectedDueDate))
        }
        // Small green dot at the end of the badge marks an active reminder, matching
        // the reference screenshot in imagens/. Set as actual Drawables (rather than by
        // resource id) so only the calendar icon gets the primary-color tint -- the green
        // dot needs to stay green, not get overridden by a blanket drawableTint.
        val calendarIcon = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_my_calendar)?.apply {
            DrawableCompat.setTint(this, binding.reminderRow.currentTextColor)
        }
        val reminderDot = if (hasDueDate) ContextCompat.getDrawable(this, R.drawable.shape_dot_green) else null
        binding.reminderRow.setCompoundDrawablesRelativeWithIntrinsicBounds(calendarIcon, null, reminderDot, null)
    }

    private fun updateFolderRowText() {
        if (selectedFolderId == 0L) {
            binding.folderRow.text = getString(R.string.folder_none)
            return
        }
        lifecycleScope.launch {
            val name = folderRepository.getAll().firstOrNull { it.id == selectedFolderId }?.name
            binding.folderRow.text = name ?: getString(R.string.folder_none)
        }
    }

    private fun copyImageIntoStorage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val dir = File(filesDir, "images").apply { mkdirs() }
                val dest = File(dir, "img_${System.currentTimeMillis()}.jpg")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                removeOldImageFile()
                selectedImagePath = dest.absolutePath
                updateImagePreview()
            } catch (e: Exception) {
                // Picker returned something unreadable (revoked permission, deleted file, etc.);
                // simplest safe fallback is to just leave the note without an image.
            }
        }
    }

    private fun removeImage() {
        removeOldImageFile()
        selectedImagePath = ""
        updateImagePreview()
    }

    private fun removeOldImageFile() {
        if (selectedImagePath.isNotBlank()) File(selectedImagePath).delete()
    }

    private fun updateImagePreview() {
        if (selectedImagePath.isBlank()) {
            binding.imagePreviewContainer.visibility = View.GONE
            binding.imageRow.text = getString(R.string.add_photo)
            return
        }
        binding.imageRow.text = getString(R.string.change_photo)
        binding.imagePreviewContainer.visibility = View.VISIBLE
        val bitmap = decodeSampledBitmap(selectedImagePath, 240, 240)
        if (bitmap != null) {
            binding.imagePreview.setImageBitmap(bitmap)
        } else {
            // File vanished from under us (e.g. cleared app storage); drop the dangling reference.
            selectedImagePath = ""
            binding.imagePreviewContainer.visibility = View.GONE
            binding.imageRow.text = getString(R.string.add_photo)
        }
    }

    private fun decodeSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= reqWidth && bounds.outHeight / (sampleSize * 2) >= reqHeight) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun showFolderPicker() {
        lifecycleScope.launch {
            val folders = folderRepository.getAll()
            val labels = mutableListOf(getString(R.string.folder_none))
            labels.addAll(folders.map { it.name })
            labels.add(getString(R.string.folder_manage))

            androidx.appcompat.app.AlertDialog.Builder(this@NoteEditorActivity)
                .setTitle(R.string.folder_choose)
                .setItems(labels.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> { selectedFolderId = 0L; updateFolderRowText() }
                        labels.lastIndex -> startActivity(
                            android.content.Intent(this@NoteEditorActivity, FoldersActivity::class.java)
                        )
                        else -> {
                            selectedFolderId = folders[which - 1].id
                            updateFolderRowText()
                        }
                    }
                }
                .show()
        }
    }

    /** Builds the row of tappable color circles used to tag the note's color, as seen in ColorNote. */
    private fun buildColorRow() {
        val sizePx = (28 * resources.displayMetrics.density).toInt()
        val ringPaddingPx = (6 * resources.displayMetrics.density).toInt()
        val marginPx = (6 * resources.displayMetrics.density).toInt()

        NoteColors.values.forEachIndexed { index, colorRes ->
            // Each cell stays visible at all times; only the selection ring
            // (a sibling behind the swatch, not a parent) toggles, so hiding
            // it for unselected colors doesn't also hide their swatch.
            val cell = android.widget.FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx + ringPaddingPx, sizePx + ringPaddingPx).apply {
                    marginEnd = marginPx
                }
            }
            val ring = View(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                setBackgroundResource(R.drawable.shape_selection_ring)
                visibility = View.INVISIBLE
            }
            val swatch = ImageView(this).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(sizePx, sizePx, android.view.Gravity.CENTER)
                setBackgroundResource(R.drawable.shape_color_swatch)
                backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
                setOnClickListener { selectColor(index) }
            }
            cell.addView(ring)
            cell.addView(swatch)
            colorSwatches.add(swatch)
            colorRings.add(ring)
            binding.colorRow.addView(cell)
        }
        selectColor(DEFAULT_COLOR_INDEX)
    }

    /** Tints only the toolbar/header to the selected note color, as requested -- the rest of the
     *  editor (body text, checklist) stays on the fixed black background set in the layout, with
     *  fixed light text, regardless of which note color is picked. */
    private fun selectColor(index: Int) {
        selectedColorIndex = index
        colorRings.forEachIndexed { i, ring ->
            ring.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }

        val colorInt = getColor(NoteColors.colorResFor(index))
        val headerTextColor = if (NoteColors.isLight(colorInt)) 0xFF1A1A1A.toInt() else 0xFFF5F5F5.toInt()
        val bodyTextColor = getColor(R.color.note_editor_body_text)

        binding.toolbarRoot.setBackgroundColor(colorInt)
        binding.doneButton.setColorFilter(headerTextColor)
        binding.overflowButton.setColorFilter(headerTextColor)
        binding.colorToggleButton.backgroundTintList = ColorStateList.valueOf(colorInt)

        checklistAdapter.setTextColor(bodyTextColor)
        updateReminderRowText() // re-tints the badge's calendar icon to match the header text color
    }

    private fun toggleColorRow() {
        binding.colorRow.visibility = if (binding.colorRow.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun showOverflowMenu() {
        val popup = androidx.appcompat.widget.PopupMenu(this, binding.overflowButton)
        popup.menuInflater.inflate(R.menu.menu_editor, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> { deleteAndFinish(); true }
                R.id.action_share -> { shareNote(); true }
                R.id.action_share_image -> { shareNoteAsImage(); true }
                else -> false
            }
        }
        popup.show()
    }

    override fun onPause() {
        super.onPause()
        saveIfNeeded()
    }

    private fun saveIfNeeded() {
        if (isDeleting) return
        val title = binding.titleInput.text.toString()
        val body = if (noteType == Note.TYPE_CHECKLIST) {
            ChecklistItem.serialize(checklistItems)
        } else {
            binding.bodyInput.text.toString()
        }
        val isEmpty = title.isBlank() && (if (noteType == Note.TYPE_CHECKLIST) checklistItems.isEmpty() else body.isBlank())
        val existing = note

        if (isEmpty) {
            if (existing != null) {
                lifecycleScope.launch {
                    repository.delete(existing.id)
                    ReminderNotifier.checkAndNotify(applicationContext)
                    NoteReminderScheduler.cancel(applicationContext, existing.id)
                }
            }
            return
        }

        rememberLastUsedColor(selectedColorIndex)

        lifecycleScope.launch {
            val saved: Note
            if (existing != null) {
                saved = existing.copy(
                    title = title,
                    body = body,
                    colorIndex = selectedColorIndex,
                    type = noteType,
                    dueDate = selectedDueDate,
                    repetition = selectedRepetition,
                    repeatUntil = selectedRepeatUntil,
                    reminderTimeMinutes = selectedReminderTimeMinutes,
                    folderId = selectedFolderId,
                    imagePath = selectedImagePath,
                    modifiedDate = System.currentTimeMillis()
                )
                repository.update(saved)
            } else {
                val draft = Note(
                    title = title,
                    body = body,
                    colorIndex = selectedColorIndex,
                    type = noteType,
                    dueDate = selectedDueDate,
                    repetition = selectedRepetition,
                    repeatUntil = selectedRepeatUntil,
                    reminderTimeMinutes = selectedReminderTimeMinutes,
                    folderId = selectedFolderId,
                    imagePath = selectedImagePath
                )
                val id = repository.insert(draft)
                saved = draft.copy(id = id)
                note = saved
            }
            // Deliberately not calling ReminderNotifier.checkAndNotify() here: creating/editing
            // a note shouldn't pop a notification on screen right away, only the scheduled daily
            // check (or the next app open) should surface it. The note's own exact-time alarm
            // (if any) is a separate concern and always needs re-scheduling after a save, though,
            // since the date/time/repetition the alarm depends on may have just changed.
            NoteReminderScheduler.schedule(applicationContext, saved)
        }
    }

    private fun shareNote() {
        val title = binding.titleInput.text.toString()
        val body = if (noteType == Note.TYPE_CHECKLIST) {
            checklistItems.joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text }
        } else {
            binding.bodyInput.text.toString()
        }
        val text = if (title.isBlank()) body else "$title\n\n$body"
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
            if (title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        }
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.action_share)))
    }

    /** Builds a throwaway [Note] from whatever's currently on screen (title/body/color), same as
     *  [shareNote] does for its plain-text share -- lets the user share a render of an edit
     *  they haven't saved yet, not just whatever's already persisted. */
    private fun shareNoteAsImage() {
        val title = binding.titleInput.text.toString()
        val body = if (noteType == Note.TYPE_CHECKLIST) {
            ChecklistItem.serialize(checklistItems)
        } else {
            binding.bodyInput.text.toString()
        }
        val draft = Note(title = title, body = body, colorIndex = selectedColorIndex, type = noteType)
        ShareImageHelper.share(this, draft)
    }

    private fun deleteAndFinish() {
        isDeleting = true
        val existing = note
        if (existing != null) {
            lifecycleScope.launch {
                repository.delete(existing.id)
                ReminderNotifier.checkAndNotify(applicationContext)
                NoteReminderScheduler.cancel(applicationContext, existing.id)
                UndoDeleteState.recordDeletion(existing.id)
                finish()
            }
        } else {
            finish()
        }
    }

    private fun lastUsedColorIndex(): Int =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_LAST_COLOR, DEFAULT_COLOR_INDEX)

    private fun rememberLastUsedColor(index: Int) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(KEY_LAST_COLOR, index).apply()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_NOTE_TYPE = "note_type"
        const val EXTRA_DUE_DATE = "due_date"
        const val EXTRA_FOLDER_ID = "folder_id"

        private const val PREFS_NAME = "note_editor_prefs"
        private const val KEY_LAST_COLOR = "last_color_index"

        /** Index into [NoteColors.values] for the dark/black swatch, the fallback before any note is ever saved. */
        private const val DEFAULT_COLOR_INDEX = 6
    }
}
