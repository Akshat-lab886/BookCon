package com.bookcon.app.ui.notebooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.data.local.BookDao
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.NoteDao
import com.bookcon.app.data.local.NotebookDao
import com.bookcon.app.data.local.NotebookEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NotebooksViewModel @Inject constructor(
    notebookDao: NotebookDao,
    private val noteDao: NoteDao,
    private val bookDao: BookDao,
) : ViewModel() {

    /** One row per notebook: entity + live note count + book title. */
    data class NotebookRow(
        val notebook: NotebookEntity,
        val noteCount: Int,
        val bookTitle: String,
    )

    private val booksFlow: Flow<List<BookEntity>> = bookDao.observeLibrary("", "recent")

    private val rows: StateFlow<List<NotebookRow>> =
        combine(notebookDao.observeAll(), booksFlow) { nbs, books ->
            val titles = books.associate { it.id to it.title }
            nbs.map { nb -> NotebookRow(nb, 0, titles[nb.bookId] ?: "Deleted book") }
        }
            .flatMapLatest { rows ->
                if (rows.isEmpty()) {
                    kotlinx.coroutines.flow.flowOf(emptyList())
                } else {
                    val countFlows = rows.map { row ->
                        noteDao.observeForNotebook(row.notebook.id).map { notes ->
                            row.copy(noteCount = notes.size)
                        }
                    }
                    combine(countFlows) { it.toList() }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notebooks: StateFlow<List<NotebookRow>> = rows

    val bookTitles: Map<String, String> get() = emptyMap()
}
