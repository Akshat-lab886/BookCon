@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bookcon.app.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.DownloadState
import com.bookcon.app.data.local.PositionDao
import com.bookcon.app.data.local.ShelfEntity
import com.bookcon.app.data.local.SeriesEntity
import com.bookcon.app.data.local.TagEntity
import com.bookcon.app.data.local.UploadQueueItem
import com.bookcon.app.data.local.UploadState
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.remote.NameRequest
import com.bookcon.app.data.repo.AuthRepository
import com.bookcon.app.data.sync.enqueueDownload
import com.bookcon.app.data.sync.enqueueUpload
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Bottom tabs are filter states within this screen (PRD FR-LIB). */
enum class LibTab(val label: String) {
    LIBRARY("Library"),
    SHELVES("Shelves"),
    SERIES("Series"),
    TAGS("Tags"),
}

/** recent/added/title come from Room's observeLibrary; progress re-sorts client-side. */
enum class SortMode(val key: String, val label: String) {
    RECENT("recent", "Recently updated"),
    ADDED("added", "Recently added"),
    TITLE("title", "Title A–Z"),
    PROGRESS("progress", "Reading progress"),
}

data class LibraryUiState(
    val loading: Boolean = true,
    val tab: LibTab = LibTab.LIBRARY,
    val searchText: String = "",
    val sort: SortMode = SortMode.RECENT,
    val gridView: Boolean = true,
    val books: List<BookEntity> = emptyList(),
    val totalCount: Int = 0,
    val serverUrl: String = "",
    val progressPercent: Map<String, Double> = emptyMap(),
    val continueReading: List<BookEntity> = emptyList(),
    val shelves: List<ShelfEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
    val authors: List<String> = emptyList(),
    val countByShelf: Map<String, Int> = emptyMap(),
    val countByTag: Map<String, Int> = emptyMap(),
    val countBySeries: Map<String, Int> = emptyMap(),
    // AND-combined client-side filters (fine for v1 per PRD FR-LIB).
    val filterFormats: Set<String> = emptySet(),
    val filterTagId: String? = null,
    val filterShelfId: String? = null,
    val filterAuthor: String? = null,
    // Bulk-select mode (PRD LIB-12).
    val selectionActive: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

sealed interface LibraryEvent {
    data class Snackbar(val text: String) : LibraryEvent
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    db: BookConDatabase,
    positionDao: PositionDao,
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
) : ViewModel() {

    private val bookDao = db.bookDao()
    private val organizeDao = db.organizeDao()
    private val uploadQueueDao = db.uploadQueueDao()

    /** Debounced search query feeding Room (<200 ms → instant local results, PRD LIB-2). */
    private val queryFlow = MutableStateFlow("")
    private var queryJob: Job? = null

    private val _controls = MutableStateFlow(LibraryUiState())

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Room query keyed on (debounced query, sort); everything else filters/sorts client-side. */
    private val booksRaw: Flow<List<BookEntity>> =
        combine(queryFlow, _controls) { q, c ->
            // PROGRESS sort has no server-side equivalent → fall back to RECENT key.
            val sortKey = if (c.sort == SortMode.PROGRESS) SortMode.RECENT.key else c.sort.key
            q to sortKey
        }
            .distinctUntilChanged()
            .flatMapLatest { (q, sortKey) ->
                bookDao.observeLibrary(
                    q = q.trim().takeIf(String::isNotBlank)?.let(::escapeLike),
                    sort = sortKey,
                )
            }

    /**
     * Progress percent per book from the positions table. PositionDao only exposes a suspend
     * all() plus per-book Flow, so poll cheaply every 4 s (contract-allowed v1 shim).
     */
    private val progressFlow: Flow<Map<String, Double>> = flow {
        while (currentCoroutineContext().isActive) {
            val map = positionDao.all()
                .mapNotNull { p -> p.progressPercent?.let { p.bookId to it } }
                .toMap()
            emit(map)
            delay(4_000)
        }
    }

    val state: StateFlow<LibraryUiState> = combine(
        combine(booksRaw, progressFlow, bookDao.observeContinueReading()) { books, progress, cont ->
            Triple(books, progress, cont)
        },
        combine(organizeDao.observeShelves(), organizeDao.observeTags(), organizeDao.observeSeries()) { s, t, sr ->
            OrgData(s, t, sr)
        },
        _controls,
        queryFlow,
    ) { core, org, controls, q ->
        buildState(core.first, core.second, core.third, org, controls, q)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private data class OrgData(
        val shelves: List<ShelfEntity>,
        val tags: List<TagEntity>,
        val series: List<SeriesEntity>,
    )

    private fun buildState(
        books: List<BookEntity>,
        progress: Map<String, Double>,
        continueReading: List<BookEntity>,
        org: OrgData,
        controls: LibraryUiState,
        query: String,
    ): LibraryUiState {
        val filtered = books.filter { b ->
            val formatOk = controls.filterFormats.isEmpty() || b.format.lowercase() in controls.filterFormats
            val tagOk = controls.filterTagId == null || controls.filterTagId in b.tagIds
            val shelfOk = controls.filterShelfId == null || controls.filterShelfId in b.shelfIds
            val authorOk = controls.filterAuthor == null || controls.filterAuthor in b.authors
            formatOk && tagOk && shelfOk && authorOk
        }
        val sorted = when (controls.sort) {
            SortMode.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortMode.ADDED -> filtered.sortedByDescending { it.addedAt }
            SortMode.PROGRESS -> filtered.sortedByDescending { progress[it.id] ?: -1.0 }
            SortMode.RECENT -> filtered.sortedByDescending { it.updatedAt }
        }
        return controls.copy(
            loading = false,
            searchText = query,
            books = sorted,
            totalCount = books.size,
            serverUrl = sessions.current()?.serverUrl.orEmpty(),
            progressPercent = progress,
            continueReading = continueReading.sortedByDescending { it.lastOpenedAt },
            shelves = org.shelves,
            tags = org.tags,
            series = org.series,
            authors = books.flatMap { it.authors }.distinct().sortedBy(String::lowercase),
            countByShelf = countEach(books) { it.shelfIds },
            countByTag = countEach(books) { it.tagIds },
            countBySeries = books.groupingBy { it.seriesId.orEmpty() }.eachCount().filterKeys(String::isNotBlank),
        )
    }

    private inline fun countEach(books: List<BookEntity>, ids: (BookEntity) -> List<String>): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (b in books) for (id in ids(b)) counts[id] = (counts[id] ?: 0) + 1
        return counts
    }

    // --- Controls -----------------------------------------------------------------

    fun setSearchText(value: String) {
        _controls.update { it.copy(searchText = value) }
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(180) // instant-feel local debounce (PRD LIB-2 <200 ms)
            queryFlow.value = value
        }
    }

    fun setSort(mode: SortMode) = _controls.update { it.copy(sort = mode) }

    fun toggleViewMode() = _controls.update { it.copy(gridView = !it.gridView) }

    fun setTab(tab: LibTab) = _controls.update { it.copy(tab = tab) }

    fun toggleFormat(format: String) = _controls.update {
        val f = format.lowercase()
        it.copy(filterFormats = if (f in it.filterFormats) it.filterFormats - f else it.filterFormats + f)
    }

    fun setTagFilter(tagId: String?) = _controls.update { it.copy(filterTagId = tagId) }

    /** Tapping a shelf in the Shelves tab filters the library grid by that shelf. */
    fun setShelfFilter(shelfId: String?) =
        _controls.update { it.copy(filterShelfId = shelfId, tab = LibTab.LIBRARY) }

    fun setAuthorFilter(author: String?) = _controls.update { it.copy(filterAuthor = author) }

    fun clearFilters() = _controls.update {
        it.copy(filterFormats = emptySet(), filterTagId = null, filterShelfId = null, filterAuthor = null)
    }

    // --- Selection mode (PRD LIB-12) -------------------------------------------------

    fun onLongPress(bookId: String) {
        _controls.update {
            when {
                !it.selectionActive -> it.copy(selectionActive = true, selectedIds = setOf(bookId))
                else -> {
                    val next = if (bookId in it.selectedIds) it.selectedIds - bookId else it.selectedIds + bookId
                    it.copy(selectedIds = next, selectionActive = next.isNotEmpty())
                }
            }
        }
    }

    fun selectAllVisible() = _controls.update {
        it.copy(selectionActive = true, selectedIds = state.value.books.map(BookEntity::id).toSet())
    }

    fun clearSelection() = _controls.update { it.copy(selectionActive = false, selectedIds = emptySet()) }

    /** Move each selected book onto [shelfId]: PATCH passthrough + local row update. */
    fun moveToShelf(shelfId: String) {
        val ids = _controls.value.selectedIds.toList()
        if (ids.isEmpty() || shelfId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            var moved = 0
            for (id in ids) {
                val book = bookDao.get(id) ?: continue
                val newShelves = (book.shelfIds + shelfId).distinct()
                val patch = mapOf<String, kotlinx.serialization.json.JsonElement>(
                    "shelf_ids" to kotlinx.serialization.json.JsonArray(
                        newShelves.map { kotlinx.serialization.json.JsonPrimitive(it) },
                    ),
                )
                val resp = runCatching { apiProvider.get().patchBook(id, patch) }.getOrNull()
                if (resp?.isSuccessful == true) {
                    bookDao.upsert(
                        book.copy(
                            shelfIds = newShelves,
                            updatedAt = resp.body()?.updatedAt ?: book.updatedAt,
                            dirty = false,
                        ),
                    )
                    moved++
                } else {
                    // Offline-first: keep the edit locally flagged dirty.
                    bookDao.upsert(book.copy(shelfIds = newShelves, dirty = true))
                }
            }
            clearSelection()
            _events.send(LibraryEvent.Snackbar("Moved $moved book${plural(moved)} to shelf"))
        }
    }

    fun downloadSelected() {
        val ids = _controls.value.selectedIds.toList()
        viewModelScope.launch(Dispatchers.IO) {
            var queued = 0
            for (id in ids) {
                val book = bookDao.get(id) ?: continue
                if (book.downloadState == DownloadState.READY && book.localFile != null) continue
                enqueueDownload(appContext, id)
                bookDao.upsert(book.copy(downloadState = DownloadState.DOWNLOADING))
                queued++
            }
            clearSelection()
            _events.send(LibraryEvent.Snackbar("Downloading $queued book${plural(queued)}"))
        }
    }

    /**
     * DELETE passthrough + local tombstone. When the network delete fails the row stays
     * dirty so applyPulled cannot resurrect it until a later sync resolves the delete.
     * TODO(sync): books are not part of sync/push — a dedicated tombstone push is needed.
     */
    fun deleteSelected() {
        val ids = _controls.value.selectedIds.toList()
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = 0
            for (id in ids) {
                val book = bookDao.get(id) ?: continue
                val ok = runCatching { apiProvider.get().deleteBook(id).isSuccessful }.getOrDefault(false)
                bookDao.upsert(book.copy(deletedAt = nowIso(), dirty = !ok))
                deleted++
            }
            clearSelection()
            _events.send(LibraryEvent.Snackbar("Deleted $deleted book${plural(deleted)}"))
        }
    }

    // --- Import (PRD LIB-1): SAF pick → staged copy + sha256 → upload queue ----------

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            var queued = 0
            for (uri in uris) {
                try {
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val name = queryDisplayName(uri)
                        ?: uri.lastPathSegment
                        ?: "import-${System.currentTimeMillis()}"
                    val stagingDir = File(appContext.filesDir, IMPORTS_DIR).apply { mkdirs() }
                    val staging = File(stagingDir, "${System.currentTimeMillis()}-$name")

                    // Copy bytes ONCE into staging while streaming the sha256.
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    val input = appContext.contentResolver.openInputStream(uri) ?: continue
                    input.use { stream ->
                        staging.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                digest.update(buffer, 0, read)
                                out.write(buffer, 0, read)
                                size += read
                            }
                        }
                    }
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                    uploadQueueDao.enqueue(
                        UploadQueueItem(
                            pendingUri = uri.toString(),
                            filename = name,
                            sizeBytes = size,
                            sha256 = sha256,
                            contentType = mimeFor(name),
                            state = UploadState.PENDING,
                        ),
                    )
                    queued++
                } catch (_: Exception) {
                    // Skip unreadable picks; remaining files still import.
                }
            }
            if (queued > 0) enqueueUpload(appContext)
            _events.send(LibraryEvent.Snackbar("$queued book${plural(queued)} importing"))
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    // --- Organize tabs: create shelf/tag/series (POST then pull-refresh local) ---------

    fun createShelf(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val resp = runCatching { apiProvider.get().createShelf(NameRequest(trimmed)) }.getOrNull()
            if (resp?.isSuccessful == true && resp.body() != null) {
                val dto = requireNotNull(resp.body())
                organizeDao.upsertShelf(ShelfEntity(dto.id, dto.name, dto.sortPosition, dto.updatedAt, dto.deletedAt))
                _events.send(LibraryEvent.Snackbar("Shelf created"))
            } else {
                // Offline-first: PushWorker drains dirty shelves via /sync/push.
                organizeDao.upsertShelf(
                    ShelfEntity(AuthRepository.newId(), trimmed, System.currentTimeMillis(), nowIso(), dirty = true),
                )
                _events.send(LibraryEvent.Snackbar("Shelf saved offline — will sync"))
            }
        }
    }

    fun createTag(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val resp = runCatching { apiProvider.get().createTag(NameRequest(trimmed)) }.getOrNull()
            if (resp?.isSuccessful == true && resp.body() != null) {
                val dto = requireNotNull(resp.body())
                organizeDao.upsertTag(TagEntity(dto.id, dto.name, dto.updatedAt, dto.deletedAt))
                _events.send(LibraryEvent.Snackbar("Tag created"))
            } else {
                organizeDao.upsertTag(TagEntity(AuthRepository.newId(), trimmed, nowIso(), dirty = true))
                _events.send(LibraryEvent.Snackbar("Tag saved offline — will sync"))
            }
        }
    }

    fun createSeries(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val resp = runCatching { apiProvider.get().createSeries(NameRequest(trimmed)) }.getOrNull()
            if (resp?.isSuccessful == true && resp.body() != null) {
                val dto = requireNotNull(resp.body())
                organizeDao.upsertSeries(SeriesEntity(dto.id, dto.name, dto.updatedAt, dto.deletedAt))
                _events.send(LibraryEvent.Snackbar("Series created"))
            } else {
                organizeDao.upsertSeries(SeriesEntity(AuthRepository.newId(), trimmed, nowIso(), dirty = true))
                _events.send(LibraryEvent.Snackbar("Series saved offline — will sync"))
            }
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    companion object {
        const val IMPORTS_DIR = "imports"

        fun mimeFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "cbz" -> "application/vnd.comicbook+zip"
            "cbr" -> "application/vnd.comicbook-rar"
            else -> "application/octet-stream"
        }

        fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
    }
}


/** Escape SQL LIKE wildcards so user-typed % and _ match literally. */
private fun escapeLike(raw: String): String =
    raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
