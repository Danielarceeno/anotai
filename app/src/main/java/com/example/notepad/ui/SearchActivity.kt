package com.example.notepad.ui

import android.app.SearchManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.notepad.data.AppDatabase
import com.example.notepad.data.NoteRepository
import com.example.notepad.databinding.ActivitySearchBinding
import com.example.notepad.R
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

/**
 * Mirrors the reference app's dedicated Search activity: a single text
 * field that filters notes as the user types, rather than requiring an
 * explicit submit. Typing is debounced (300ms) before it reaches the
 * database so fast typing doesn't fire a query per keystroke.
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var adapter: NoteAdapter
    private lateinit var viewModel: SearchViewModel
    private lateinit var repository: NoteRepository

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null
    private val debounceMs = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = NoteRepository(AppDatabase.getInstance(this).noteDao(), applicationContext)
        viewModel = ViewModelProvider(this, SearchViewModel.Factory(repository))[SearchViewModel::class.java]

        adapter = NoteAdapter(
            onClick = { note ->
                val intent = android.content.Intent(this, NoteEditorActivity::class.java)
                intent.putExtra(NoteEditorActivity.EXTRA_NOTE_ID, note.id)
                startActivity(intent)
            },
            onTogglePin = { note -> lifecycleScope.launch { repository.update(note.copy(pinned = !note.pinned)) } }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.searchView.isIconified = false
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = true

            override fun onQueryTextChange(newText: String?): Boolean {
                scheduleSearch(newText.orEmpty())
                return true
            }
        })

        viewModel.results.observe(this) { notes ->
            adapter.highlight = viewModel.currentQuery
            adapter.submitList(notes)
            val query = viewModel.currentQuery
            binding.emptyView.visibility = if (query.isNotBlank() && notes.isEmpty()) {
                binding.emptyView.text = getString(com.example.notepad.R.string.empty_search_results, query)
                android.view.View.VISIBLE
            } else {
                android.view.View.GONE
            }
        }

        handleIncomingQuery()
    }

    override fun onResume() {
        super.onResume()
        UndoDeleteState.consumeRecent()?.let { noteId ->
            Snackbar.make(binding.root, R.string.note_deleted_message, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_undo) { lifecycleScope.launch { repository.restore(noteId) } }
                .show()
        }
    }

    /** Honors a query delivered via the system SEARCH intent (Quick Search Box / voice search). */
    private fun handleIncomingQuery() {
        if (intent?.action == android.content.Intent.ACTION_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            binding.searchView.setQuery(query, true)
        }
    }

    private fun scheduleSearch(text: String) {
        pendingSearch?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable { viewModel.setQuery(text) }
        pendingSearch = runnable
        debounceHandler.postDelayed(runnable, debounceMs)
    }

    override fun onDestroy() {
        pendingSearch?.let { debounceHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
