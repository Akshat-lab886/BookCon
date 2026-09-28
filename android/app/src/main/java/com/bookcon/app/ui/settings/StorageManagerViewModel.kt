package com.bookcon.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.DownloadState
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookmarkDao
import com.bookcon.app.data.local.PositionEntity
import com.bookcon.app.core.ArchiveSink
import com.bookcon.app.core.DataArchive
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.data.remote.StorageStatsDto
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    // --- Local Vault ---
    val storageMode: String = "cloud",
    val vaultBusy: Boolean = false,
)

sealed interface StorageEvent {
    data class Message(val text: String) : StorageEvent
}

@HiltViewModel
class StorageManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    db: BookConDatabase,
    private val apiProvider: ApiProvider,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val bookDao = db.bookDao()
    private val positionDao = db.positionDao()
    private val annotationDao = db.annotationDao()
    private val bookmarkDao = db.bookmarkDao()

    /** Pending SAF target for the export the user just confirmed. */

    private val _state = MutableStateFlow(StorageUiState())
    val state: StateFlow<StorageUiState> = _state

    private val _events = Channel<StorageEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(storageMode = s.storageMode) }
            }
        }
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

    /**
     * Per-book remove-offline: deletes the local file and clears download state.
     *
     * The delete result is now checked. It used to be `runCatching { File(path).delete() }`
     * with the Boolean discarded, and the database row was cleared regardless — so a
     * failed delete (file open elsewhere, storage error) made the book vanish from the
     * Storage list while the file still occupied space, with no way to find or reclaim
     * it and no message that anything went wrong.
     */
    fun removeOffline(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookDao.get(bookId) ?: return@launch
            var failed = false
            book.localFile?.let { path ->
                val f = File(path)
                val gone = !f.exists() || runCatching { f.delete() }.getOrDefault(false)
                if (!gone) failed = true
            }
            if (failed) {
                _events.send(
                    StorageEvent.Message(
                        "Couldn't delete “${book.title}” — the file is still on this device",
                    ),
                )
                // Leave the row intact so the book stays visible in Storage and the
                // file can be retried.
                return@launch
            }
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
            var failed = 0
            dir.listFiles()?.forEach { f ->
                val size = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    freed += size
                } else {
                    failed += 1
                }
            }
            // Recompute rather than hard-setting 0: some files may have survived.
            val remaining = importsDirBytes()
            _state.update { it.copy(importsBytes = remaining) }
            _events.send(
                StorageEvent.Message(
                    if (failed == 0) "Cleared ${humanize(freed)} of import staging"
                    else "Cleared ${humanize(freed)}; $failed file${if (failed == 1) "" else "s"} " +
                        "could not be deleted (${humanize(remaining)} still in use)",
                ),
            )
        }
    }

    private fun importsDirBytes(): Long =
        File(appContext.filesDir, "imports").listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileLength(path: String?): Long =
        if (path == null) 0L else runCatching { File(path).length() }.getOrDefault(0L)

    // ------------------------------------------------------------- Local Vault

    fun setStorageMode(mode: String) {
        viewModelScope.launch { settingsRepo.setStorageMode(mode) }
    }

    /** Streams every local row + book payload into the SAF [uri] as one archive. */
    fun exportVault(uri: Uri) {
        if (_state.value.vaultBusy) return
        _state.update { it.copy(vaultBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepo.settings.value
                val books = bookDao.all().filter { it.deletedAt == null }
                val positions = positionDao.all()
                val annotations = annotationDao.allRaw()
                val bookmarks = bookmarkDao.all()
                val out = appContext.contentResolver.openOutputStream(uri, "wt")
                if (out == null) {
                    _events.send(StorageEvent.Message("Could not open the chosen file"))
                    return@launch
                }
                val stats = out.use {
                    DataArchive.export(appContext, settings, books, positions, annotations, bookmarks, it)
                }
                // stats.books counts every row handed to the export; stats.files is
                // how many actually had local bytes to put in the archive. Reporting
                // the former claimed a complete backup when cloud-only books were
                // silently left out of it.
                val skipped = stats.books - stats.files
                val summary = buildString {
                    append("Saved ${stats.files} book file${if (stats.files == 1) "" else "s"} · ${StorageManagerViewModel.humanize(stats.bytes)} — import this file on any device")
                    if (skipped > 0) {
                        append(" — $skipped not downloaded here, so not included")
                    }
                }
                _events.send(StorageEvent.Message(summary))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // See the import path: a catch-all here would report a spurious
                // failure for a scope the user simply left.
                throw e
            } catch (t: Throwable) {
                _events.send(StorageEvent.Message("Export failed: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                _state.update { it.copy(vaultBusy = false) }
            }
        }
    }

    /** Merges an archive written by [exportVault]; newer rows win, no login needed. */
    fun importVault(uri: Uri) {
        if (_state.value.vaultBusy) return
        _state.update { it.copy(vaultBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                if (input == null) {
                    _events.send(StorageEvent.Message("Could not read the chosen file"))
                    return@launch
                }
                val currentSettings = settingsRepo.settings.value
                val sink = object : ArchiveSink {
                    override suspend fun existingBookUpdatedAt(id: String) =
                        bookDao.get(id)?.updatedAt
                    override suspend fun existingPosition(bookId: String) =
                        positionDao.observe(bookId).firstOrNull()
                            ?: run { positionDao.all().firstOrNull { it.bookId == bookId } }
                    override suspend fun existingAnnotation(id: String) =
                        annotationDao.getById(id)
                    override suspend fun existingBookmark(id: String): com.bookcon.app.data.local.BookmarkEntity? =
                        bookmarkDao.all().firstOrNull { it.id == id }
                    override suspend fun upsertBook(book: BookEntity) = bookDao.upsert(book)
                    override suspend fun upsertPosition(position: PositionEntity) = positionDao.upsert(position)
                    override suspend fun upsertAnnotation(annotation: AnnotationEntity) = annotationDao.upsert(annotation)
                    override suspend fun upsertBookmark(bookmark: com.bookcon.app.data.local.BookmarkEntity) = bookmarkDao.upsert(bookmark)
                }
                val stats = DataArchive.import(appContext, input, currentSettings, { s ->
                    settingsRepo.update { _ -> s }
                }, sink)
                // A book whose payload never arrived lands in the library with no
                // file and cannot be opened. Reporting a bare "Imported 12 books"
                // hides that until the user taps one and gets a confusing error, so
                // the partial result is stated up front.
                val summary = buildString {
                    append("Imported ${stats.books} books · ${stats.annotations} highlights · reading positions restored")
                    if (stats.booksMissingFile > 0) {
                        append(" — ${stats.booksMissingFile} arrived without a file and need downloading again")
                    }
                }
                _events.send(StorageEvent.Message(summary))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // CancellationException IS a Throwable, so the catch-all below would
                // swallow it and report "Import failed" for a scope the user simply
                // navigated away from, then keep writing from a dead coroutine.
                throw e
            } catch (t: Throwable) {
                _events.send(StorageEvent.Message("Import failed: ${t.message ?: t.javaClass.simpleName}"))
            } finally {
                _state.update { it.copy(vaultBusy = false) }
            }
        }
    }

    companion object {
        fun humanize(bytes: Long): String = when {
            bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
            bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
            bytes >= 1 shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
            else -> "$bytes B"
        }
    }
}
