@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bookcon.app.ui.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.local.AnnotationDao
import com.bookcon.app.data.local.BookConDatabase
import com.bookcon.app.data.local.DownloadState
import com.bookcon.app.data.local.PositionDao
import com.bookcon.app.data.local.ShelfEntity
import com.bookcon.app.data.local.TagEntity
import com.bookcon.app.data.remote.ApiProvider
import com.bookcon.app.data.sync.toEntity
import com.bookcon.app.ui.annotations.AnnotationsExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Editable fields surfaced by the ModalBottomSheet edit form. */
data class BookEditFields(
    val title: String = "",
    val authorsCsv: String = "",
    val description: String = "",
    val language: String = "",
    val publisher: String = "",
    val publishedDate: String = "",
    val seriesName: String = "",
    val seriesIndex: String = "",
    val tagIds: Set<String> = emptySet(),
    val shelfIds: Set<String> = emptySet(),
)

data class BookDetailsUiState(
    val loading: Boolean = true,
    val book: com.bookcon.app.data.local.BookEntity? = null,
    val serverUrl: String = "",
    val seriesName: String? = null,
    val progressPercent: Double? = null,
    val annotationCount: Int = 0,
    val shelves: List<ShelfEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
)

sealed interface DetailsEvent {
    data class Snackbar(val text: String, val undoBookId: String? = null) : DetailsEvent
    data class ShareAnnotations(val subject: String, val text: String) : DetailsEvent
}

@HiltViewModel
class BookDetailsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    db: BookConDatabase,
    positionDao: PositionDao,
    private val annotationDao: AnnotationDao,
    private val apiProvider: ApiProvider,
    private val sessions: SessionStore,
) : ViewModel() {

    private val bookDao = db.bookDao()
    private val organizeDao = db.organizeDao()

    /** Bound from the screen (nav arg) since Hilt cannot inject a nullable constructor arg. */
    private val bookIdFlow = MutableStateFlow<String?>(null)

    /** Guards the delete→undo race: an undo arriving before the tombstone cancels it. */
    private val undoRequested = mutableSetOf<String>()

    private val _events = Channel<DetailsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun bind(id: String) {
        if (bookIdFlow.value != id) bookIdFlow.value = id
    }

    private val coreFlow: Flow<Triple<com.bookcon.app.data.local.BookEntity?, Double?, Int>> =
        bookIdFlow.flatMapLatest { id ->
            if (id == null) emptyFlow()
            else combine(
                bookDao.observeBook(id),
                positionDao.observe(id),
                annotationDao.observeForBook(id),
            ) { book, position, annotations ->
                Triple(book, position?.progressPercent, annotations.size)
            }
        }

    val state: StateFlow<BookDetailsUiState> = combine(
        coreFlow,
        organizeDao.observeShelves(),
        organizeDao.observeTags(),
    ) { core, shelves, tags ->
        BookDetailsUiState(
            loading = false,
            book = core.first,
            serverUrl = sessions.current()?.serverUrl.orEmpty(),
            seriesName = core.first?.seriesId
                ?.let { sid -> shelves.firstOrNull { it.id == sid }?.name },
            progressPercent = core.second,
            annotationCount = core.third,
            shelves = shelves,
            tags = tags,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailsUiState())

    // --- Read ---------------------------------------------------------------------

    fun markOpened() {
        val id = bookIdFlow.value ?: return
        viewModelScope.launch { bookDao.touchOpened(id, System.currentTimeMillis()) }
    }

    // --- Download / remove-offline toggle -------------------------------------------

    fun download() {
        val id = bookIdFlow.value ?: return
        viewModelScope.launch {
            val book = bookDao.get(id) ?: return@launch
            com.bookcon.app.data.sync.enqueueDownload(appContext, id)
            bookDao.upsert(book.copy(downloadState = DownloadState.DOWNLOADING))
            _events.send(DetailsEvent.Snackbar("Download started"))
        }
    }

    fun removeOffline() {
        val id = bookIdFlow.value ?: return
        viewModelScope.launch {
            val book = bookDao.get(id) ?: return@launch
            book.localFile?.let { path -> runCatching { File(path).delete() } }
            bookDao.upsert(book.copy(localFile = null, pinnedOffline = false, downloadState = DownloadState.NONE))
            _events.send(DetailsEvent.Snackbar("Removed offline copy"))
        }
    }

    // --- Add-to-shelf quick dialog ---------------------------------------------------

    fun addToShelf(shelfId: String) {
        val id = bookIdFlow.value ?: return
        if (shelfId.isBlank()) return
        viewModelScope.launch {
            val book = bookDao.get(id) ?: return@launch
            val newShelves = (book.shelfIds + shelfId).distinct()
            val patch = mapOf<String, kotlinx.serialization.json.JsonElement>(
                "shelf_ids" to kotlinx.serialization.json.JsonArray(
                    newShelves.map { kotlinx.serialization.json.JsonPrimitive(it) },
                ),
            )
            val resp = runCatching { apiProvider.get().patchBook(id, patch) }.getOrNull()
            if (resp?.isSuccessful == true) {
                bookDao.upsert(book.copy(shelfIds = newShelves, dirty = false))
                _events.send(DetailsEvent.Snackbar("Added to shelf"))
            } else {
                bookDao.upsert(book.copy(shelfIds = newShelves, dirty = true))
                _events.send(DetailsEvent.Snackbar("Saved offline — will sync"))
            }
        }
    }

    // --- Edit sheet ------------------------------------------------------------------

    /**
     * PATCH passthrough with JSON keys matching Dtos.kt; on success the pulled row replaces
     * local (server is source of truth, nothing marked dirty). Offline → apply locally with
     * dirty=true so PushWorker/pull reconciliation can resolve later.
     * TODO(api): `series_name` is best-effort — the OpenAPI contract exposes only series_id;
     * the server may ignore it until series resolution lands.
     */
    fun saveEdits(f: BookEditFields) {
        val id = bookIdFlow.value ?: return
        viewModelScope.launch {
            val current = bookDao.get(id) ?: return@launch
            val authors = f.authorsCsv.split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            val index = f.seriesIndex.trim().toDoubleOrNull()

            val patch = linkedMapOf<String, kotlinx.serialization.json.JsonElement>()
            patch["title"] = kotlinx.serialization.json.JsonPrimitive(f.title.trim())
            patch["authors"] = kotlinx.serialization.json.JsonArray(authors.map { kotlinx.serialization.json.JsonPrimitive(it) })
            patch["description"] = kotlinx.serialization.json.JsonPrimitive(f.description)
            patch["language"] = orNull(f.language)
            patch["publisher"] = orNull(f.publisher)
            patch["published_date"] = orNull(f.publishedDate)
            patch["series_index"] = index?.let { kotlinx.serialization.json.JsonPrimitive(it) }
                ?: kotlinx.serialization.json.JsonNull
            patch["series_name"] = kotlinx.serialization.json.JsonPrimitive(f.seriesName.trim())
            patch["tag_ids"] = kotlinx.serialization.json.JsonArray(f.tagIds.map { kotlinx.serialization.json.JsonPrimitive(it) })
            patch["shelf_ids"] = kotlinx.serialization.json.JsonArray(f.shelfIds.map { kotlinx.serialization.json.JsonPrimitive(it) })

            val resp = runCatching { apiProvider.get().patchBook(id, patch) }.getOrNull()
            if (resp?.isSuccessful == true && resp.body() != null) {
                val fresh = requireNotNull(resp.body())
                    .toEntity(sessions.current()?.userId ?: current.userId)
                    .copy(
                        dirty = false,
                        localFile = current.localFile,
                        pinnedOffline = current.pinnedOffline,
                        downloadState = current.downloadState,
                        lastOpenedAt = current.lastOpenedAt,
                    )
                bookDao.upsert(fresh)
                _events.send(DetailsEvent.Snackbar("Saved"))
            } else {
                bookDao.upsert(current.copy(
                    title = f.title.trim().ifBlank { current.title },
                    authors = authors,
                    description = f.description,
                    language = f.language.trim().ifBlank { null } ?: current.language,
                    publisher = f.publisher.trim().ifBlank { null } ?: current.publisher,
                    publishedDate = f.publishedDate.trim().ifBlank { null } ?: current.publishedDate,
                    seriesIndex = index ?: current.seriesIndex,
                    tagIds = f.tagIds.toList(),
                    shelfIds = f.shelfIds.toList(),
                    updatedAt = nowIso(),
                    dirty = true,
                ))
                _events.send(DetailsEvent.Snackbar("Saved offline — will sync"))
            }
        }
    }

    private fun orNull(value: String): kotlinx.serialization.json.JsonElement =
        value.trim().takeIf(String::isNotEmpty)
            ?.let { kotlinx.serialization.json.JsonPrimitive(it) }
            ?: kotlinx.serialization.json.JsonNull

    // --- Export annotations ------------------------------------------------------------

    fun exportAnnotations() {
        val id = bookIdFlow.value ?: return
        val book = state.value.book
        viewModelScope.launch {
            val entities = annotationDao.forBook(id)
            val markdown = AnnotationsExporter.markdown(
                AnnotationsExporter.buildEntries(entities, mapOf(id to (book?.title ?: "Unknown book"))),
            )
            _events.send(
                DetailsEvent.ShareAnnotations(
                    subject = "Annotations — ${book?.title ?: "book"}",
                    text = markdown,
                ),
            )
        }
    }

    // --- Delete with double confirm + 5 s undo (PRD LIB-11) -----------------------------

    /** Second-confirm already happened in the UI when this is called. */
    fun deleteBook() {
        val id = bookIdFlow.value ?: return
        viewModelScope.launch {
            undoRequested.remove(id) // reset guard for this delete attempt
            val book = bookDao.get(id) ?: return@launch
            val ok = runCatching { apiProvider.get().deleteBook(id).isSuccessful }.getOrDefault(false)
            if (undoRequested.remove(id)) return@launch // Undo raced ahead of the tombstone
            bookDao.upsert(book.copy(deletedAt = nowIso(), dirty = !ok))
            _events.send(DetailsEvent.Snackbar(DELETED_MSG, undoBookId = id))
        }
    }

    /** Restores a tombstoned row locally within the snackbar window (~5 s, PRD LIB-11). */
    fun undoDelete(bookId: String) {
        undoRequested.add(bookId)
        viewModelScope.launch {
            bookDao.get(bookId)?.let { book ->
                bookDao.upsert(book.copy(deletedAt = null, dirty = true))
            }
            _events.send(DetailsEvent.Snackbar("Delete undone"))
        }
    }

    companion object {
        const val DELETED_MSG =
            "Deleted. The file and its annotations were removed on the server too."
    }
}

internal fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
