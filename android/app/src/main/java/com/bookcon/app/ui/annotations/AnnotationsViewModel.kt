@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.bookcon.app.ui.annotations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.data.local.AnnotationDao
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookConDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** AND-combined filters across categories; within a category any selected value matches. */
data class AnnotationFilters(
    val search: String = "",
    val colors: Set<String> = emptySet(),
    val types: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val sort: AnnotationSort = AnnotationSort.DATE,
)

enum class AnnotationSort(val label: String) {
    LOCATION("Location"),
    DATE("Newest first"),
    COLOR("Color"),
}

data class AnnotationItem(
    val entity: AnnotationEntity,
    val bookTitle: String,
    val href: String?,
    val progression: Double?,
)

data class AnnotationsUiState(
    val loading: Boolean = true,
    val perBookId: String? = null,
    val items: List<AnnotationItem> = emptyList(),
    val availableTags: List<String> = emptyList(),
    val filters: AnnotationFilters = AnnotationFilters(),
)

sealed interface AnnotationsEvent {
    data class Share(val format: AnnotationsExporter.Format, val subject: String, val text: String) : AnnotationsEvent
}

@HiltViewModel
class AnnotationsViewModel @Inject constructor(
    db: BookConDatabase,
) : ViewModel() {

    private val annotationDao = db.annotationDao()
    private val bookDao = db.bookDao()

    /** Null → global list; non-null → per-book list. Bound from the screen. */
    private val scopeBookId = MutableStateFlow<String?>(null)
    private val filtersFlow = MutableStateFlow(AnnotationFilters())

    private val _events = Channel<AnnotationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun bind(bookId: String?) {
        if (scopeBookId.value != bookId) scopeBookId.value = bookId
    }

    private val annotationsFlow: Flow<List<AnnotationEntity>> =
        scopeBookId.flatMapLatest { id ->
            if (id == null) annotationDao.observeAll() else annotationDao.observeForBook(id)
        }

    private val titlesFlow: Flow<Map<String, String>> =
        bookDao.observeLibrary(q = null, sort = "recent")
            .map { books -> books.associate { it.id to it.title } }

    val state: StateFlow<AnnotationsUiState> = combine(
        annotationsFlow,
        titlesFlow,
        filtersFlow,
        scopeBookId,
    ) { annotations, titles, filters, bookId ->
        val filtered = annotations.filter { a ->
            val searchNeedle = filters.search.trim().lowercase()
            val searchOk = searchNeedle.isEmpty() ||
                a.excerpt.lowercase().contains(searchNeedle) ||
                a.note.lowercase().contains(searchNeedle) ||
                (titles[a.bookId] ?: "").lowercase().contains(searchNeedle)
            val colorOk = filters.colors.isEmpty() || a.color in filters.colors
            val typeOk = filters.types.isEmpty() || a.type in filters.types
            val tagOk = filters.tags.isEmpty() || a.annotationTags.any { it in filters.tags }
            searchOk && colorOk && typeOk && tagOk
        }
        val withRefs = filtered.map { a ->
            val (href, progression) = AnnotationsExporter.locatorRef(a.locatorJson)
            AnnotationItem(a, titles[a.bookId] ?: "Unknown book", href, progression)
        }
        val sorted = when (filters.sort) {
            AnnotationSort.LOCATION -> withRefs.sortedWith(
                compareBy({ it.href.orEmpty() }, { it.progression ?: 0.0 }),
            )
            AnnotationSort.DATE -> withRefs.sortedByDescending { it.entity.updatedAt }
            AnnotationSort.COLOR -> withRefs.sortedWith(
                compareBy({ it.entity.color }, { it.entity.updatedAt }),
            )
        }
        AnnotationsUiState(
            loading = false,
            perBookId = bookId,
            items = sorted,
            availableTags = annotations.flatMap { it.annotationTags }.distinct().sorted(),
            filters = filters,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnnotationsUiState())

    // --- filters -------------------------------------------------------------------

    fun setSearch(value: String) = filtersFlow.update { it.copy(search = value) }

    fun toggleColor(color: String) = filtersFlow.update {
        it.copy(colors = it.colors.toggle(color))
    }

    fun toggleType(type: String) = filtersFlow.update {
        it.copy(types = it.types.toggle(type))
    }

    fun toggleTag(tag: String) = filtersFlow.update {
        it.copy(tags = it.tags.toggle(tag))
    }

    fun setSort(sort: AnnotationSort) = filtersFlow.update { it.copy(sort = sort) }

    fun clearFilters() = filtersFlow.update {
        it.copy(search = "", colors = emptySet(), types = emptySet(), tags = emptySet())
    }

    private fun Set<String>.toggle(v: String): Set<String> =
        if (v in this) this - v else this + v

    // --- export ----------------------------------------------------------------------

    fun export(format: AnnotationsExporter.Format) {
        viewModelScope.launch {
            val snapshot = state.value
            val annotations = snapshot.items.map { it.entity }
            val titles = snapshot.items.associate { it.entity.bookId to it.bookTitle }
            val entries = AnnotationsExporter.buildEntries(annotations, titles)
            val subject = when (val id = snapshot.perBookId) {
                null -> "BookCon annotations (${entries.size})"
                else -> "Annotations — ${entries.firstOrNull()?.bookTitle ?: id}"
            }
            _events.send(AnnotationsEvent.Share(format, subject, AnnotationsExporter.build(format, entries)))
        }
    }
}
