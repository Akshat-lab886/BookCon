package com.bookcon.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.DownloadState
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.remote.StorageStatsDto
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadedBook(val book: BookEntity, val sizeBytes: Long)

data class StorageUiState(
    val loading: Boolean = true,
    val stats: StorageStatsDto? = null,
    val statsError: String? = null,
    val downloads: List<DownloadedBook> = emptyList(),
    val importsBytes: Long = 0,
)

sealed interface StorageEvent {
    data class Message(val text: String) : StorageEvent
}

@HiltViewModel
class StorageManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    db: BookConDatabase,
    private val apiProvider: ApiProvider,
) : ViewModel() {

    private val bookDao = db.bookDao()

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state

    private val _events = Channel<StorageEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
        // Local downloads mirror the Room table; sizes come from the filesystem.
        viewModelScope.launch {
            bookDao.observeLibrary(q = null, sort = "recent").collect { books ->
                val downloads = books
                    .filter { it.localFile != null }
                    .map { book -> DownloadedBook(book, fileLength(book.localFile)) }
                _state.update { it.copy(downloads = downloads) }
            }
        }
    }

    fun refresh() {
        _state.update { it.copy(loading = true, importsBytes = importsDirBytes()) }
        viewModelScope.launch(Dispatchers.IO) {
            // Server-side usage (PRD SET): total stored bytes + counts.
            val resp = runCatching { apiProvider.get().storageStats() }.getOrNull()
            if (resp?.isSuccessful == true && resp.body() != null) {
                _state.update { it.copy(loading = false, stats = resp.body(), statsError = null) }
            } else {
                _state.update { it.copy(loading = false, statsError = "Could not reach server stats") }
            }
        }
    }

    /** Per-book remove-offline: deletes the local file and clears download state. */
    fun removeOffline(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookDao.get(bookId) ?: return@launch
            book.localFile?.let { path -> runCatching { File(path).delete() } }
            bookDao.upsert(
                book.copy(localFile = null, pinnedOffline = false, downloadState = DownloadState.NONE),
            )
            _events.send(StorageEvent.Message("Removed “${book.title}” from this device"))
        }
    }

    /** Deletes leftover files under filesDir/imports (staging copies already uploaded or abandoned). */
    fun clearImports() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(appContext.filesDir, "imports")
            var freed = 0L
            dir.listFiles()?.forEach { f ->
                freed += f.length()
                runCatching { f.delete() }
            }
            _state.update { it.copy(importsBytes = 0) }
            _events.send(StorageEvent.Message("Cleared ${humanize(freed)} of import staging"))
        }
    }

    private fun importsDirBytes(): Long =
        File(appContext.filesDir, "imports").listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileLength(path: String?): Long =
        if (path == null) 0L else runCatching { File(path).length() }.getOrDefault(0L)

    companion object {
        fun humanize(bytes: Long): String = when {
            bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
            bytes >= 1 shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
            else -> "$bytes B"
        }
    }
}
