package com.example.notepad.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository
import com.example.notepad.databinding.ActivityPickedNoteWidgetConfigBinding
import com.example.notepad.widget.PickedNoteWidgetPrefs
import com.example.notepad.widget.PickedNoteWidgetProvider

/**
 * Launched by the home screen's widget host when placing a [PickedNoteWidgetProvider] tile (see
 * `widget_picked_note_info.xml`'s `android:configure`): picks which single note -- password-
 * protected or not -- that particular widget instance should show. [RESULT_CANCELED] is the
 * default outcome (set immediately, before any note is picked) per the platform's own
 * contract for widget configuration activities: if the user backs out without choosing a note,
 * the host drops the not-yet-placed widget instead of leaving it stuck unconfigured.
 */
class PickedNoteWidgetConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPickedNoteWidgetConfigBinding
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPickedNoteWidgetConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        binding.toolbar.setNavigationOnClickListener { finish() }

        val db = AppDatabase.getInstance(this)
        val repository = NoteRepository(db.noteDao(), applicationContext, db.noteTombstoneDao())

        val adapter = NoteAdapter(onClick = { note -> pickNote(note.id) })
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        repository.observeAll().observe(this) { notes ->
            adapter.submitList(notes)
            binding.emptyView.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun pickNote(noteId: Long) {
        PickedNoteWidgetPrefs.setNoteId(this, appWidgetId, noteId)
        val manager = AppWidgetManager.getInstance(this)
        PickedNoteWidgetProvider.updateWidget(this, manager, appWidgetId)

        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        )
        finish()
    }
}
