package com.example.notepad.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.switchMap
import com.example.notepad.data.Note
import com.example.notepad.data.NoteRepository

class SearchViewModel(private val repository: NoteRepository) : ViewModel() {

    private val query = MutableLiveData("")

    val results: LiveData<List<Note>> = query.switchMap { q ->
        if (q.isBlank()) MutableLiveData(emptyList()) else repository.search(q)
    }

    val currentQuery: String get() = query.value ?: ""

    fun setQuery(text: String) {
        if (text != query.value) query.value = text
    }

    class Factory(private val repository: NoteRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(repository) as T
    }
}
