package com.bookcon.app.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.data.local.BookDao
import com.bookcon.app.data.local.BookmarkDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A bookmark joined with its book, so the row can show a real title. */
data class BookmarkRow(
    val id: String,
    val bookId: String,
    val bookTitle: String,
    val label: String,
    val locatorJson: String,
)

data class BookmarksUiState(
    val bookmarks: List<BookmarkRow> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarkDao: BookmarkDao,
    private val bookDao: BookDao,
) : ViewModel() {

    /**
     * Driven by a Flow, not a one-shot read in `init`.
     *
     * Navigating to the reader and back keeps this ViewModel alive on the back
     * stack, so a suspend query loaded in `init` showed a list that never learned
     * about bookmarks added or deleted in the reader.
     */
    private val rowsFlow = combine(bookmarkDao.observeAll(), bookDao.observeAll()) { bookmarks, books ->
        val titles = books.associate { it.id to it.title }
        bookmarks.map { bm ->
            BookmarkRow(
                id = bm.id,
                bookId = bm.bookId,
                bookTitle = titles[bm.bookId] ?: "Unknown book",
                label = com.bookcon.app.ui.reader.humanBookmarkLabel(bm.label, bm.locatorJson),
                locatorJson = bm.locatorJson,
            )
        }
    }

    val uiState: StateFlow<BookmarksUiState> =
        rowsFlow.map { BookmarksUiState(bookmarks = it, isLoading = false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksUiState())

    fun delete(id: String) {
        viewModelScope.launch {
            val bm = runCatching { bookmarkDao.byId(id) }.getOrNull() ?: return@launch
            val now = java.time.Instant.now().toString()
            // The observeAll() Flow re-emits on its own, so there is no reload to
            // do here — and that is also what stops a delete from being undone.
            bookmarkDao.tombstone(id, now, now)
        }
    }
}
