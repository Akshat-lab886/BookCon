package com.bookcon.app.ui.reader

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.AppSettings
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.data.local.AnnotationDao
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookDao
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.BookmarkDao
import com.bookcon.app.data.local.BookmarkEntity
import com.bookcon.app.data.local.PositionDao
import com.bookcon.app.data.local.PositionEntity
import com.bookcon.app.data.sync.enqueueDownload
import com.bookcon.app.reader.EngineSearchHit
import com.bookcon.app.reader.EngineSettings
import com.bookcon.app.reader.Locators
import com.bookcon.app.reader.ReaderEngine
import com.bookcon.app.reader.ReaderEngineFactory
import com.bookcon.app.reader.TapZoneGrid
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator

/** Which overlay/panel (if any) is open on top of the reading surface. */
enum class ReaderPanel { NONE, TABLE_OF_CONTENTS, BOOKMARKS, SETTINGS, SEARCH }

enum class ReaderPhase { LOADING, DOWNLOADING, OPENING, READY, ERROR }

/** Search hits grouped by chapter (RD-11: group by locator.href). */
data class SearchChapterGroup(
    val chapterTitle: String,
    val href: String,
    val hits: List<EngineSearchHit>,
)

data class ReaderUiState(
    val phase: ReaderPhase = ReaderPhase.LOADING,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val book: BookEntity? = null,
    val engine: ReaderEngine? = null,
    val tableOfContents: List<Link> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val chromeVisible: Boolean = true,
    val panel: ReaderPanel = ReaderPanel.NONE,
    val chapterTitle: String = "",
    val remainingPercent: Float? = null, // 1 - totalProgression (RD-13)
    val searchQuery: String = "",
    val searchGroups: List<SearchChapterGroup> = emptyList(),
    val searching: Boolean = false,
    /** Incremented whenever a search hit is opened → UI flashes the match box (~800ms, RD-11). */
    val flashTick: Int = 0,
)

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bookDao: BookDao,
    private val annotationDao: AnnotationDao,
    private val bookmarkDao: BookmarkDao,
    private val positionDao: PositionDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private var initialized = false
    private var bookId = ""
    private var downloadEnqueued = false
    private var engineOpening = false
    private var openedAtTouched = false

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /**
     * Outlives [viewModelScope] so the RD-12 "save position immediately on close" flush and the
     * final navigator teardown still run after onCleared() cancels the UI scope.
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun init(bookId: String) {
        if (initialized) return
        initialized = true
        this.bookId = bookId
        observeBook()
        observeAnnotations()
        observeBookmarks()
        applySettingsToEngine()
    }

    // --------------------------------------------------------------------- loading & opening

    private fun observeBook() {
        viewModelScope.launch {
            bookDao.observeBook(bookId).collect { book ->
                if (book == null) {
                    _state.update {
                        it.copy(phase = ReaderPhase.ERROR, errorMessage = "Book not found.")
                    }
                    return@collect
                }
                _state.update { it.copy(book = book) }
                if (!openedAtTouched) {
                    openedAtTouched = true
                    bookDao.touchOpened(book.id, System.currentTimeMillis())
                }
                val file = book.localFile?.let(::File)
                when {
                    // Offline-first: a present local file opens instantly, network or not.
                    file != null && file.exists() -> openEngine(file)

                    // No file yet → kick the download worker and keep the reader in a
                    // non-blocking "Downloading…" state until the row updates (SYN-6).
                    !downloadEnqueued -> {
                        downloadEnqueued = true
                        enqueueDownload(appContext, book.id)
                        _state.update {
                            it.copy(
                                phase = ReaderPhase.DOWNLOADING,
                                statusMessage = "Downloading…",
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun openEngine(file: File) {
        if (engineOpening) return
        engineOpening = true
        _state.update { it.copy(phase = ReaderPhase.OPENING, statusMessage = null) }
        try {
            // Restore last position (RD-12) into the navigator's initial locator.
            val saved = positionDao.observe(bookId).firstOrNull()
            val initialLocator = Locators.fromJsonString(saved?.locatorJson)

            // Navigator fragments must be created on the main thread; viewModelScope is
            // Dispatchers.Main.immediate by default.
            val engine = ReaderEngineFactory.open(
                context = appContext,
                publicationFile = file,
                initialLocator = initialLocator,
                settings = settings.value.toEngineSettings(),
            )
            _state.update {
                it.copy(
                    engine = engine,
                    phase = ReaderPhase.READY,
                    tableOfContents = engine.publication?.tableOfContents.orEmpty(),
                )
            }
            startPositionSaving(engine)
            observeChromeData(engine)
        } catch (t: Throwable) {
            engineOpening = false
            Log.e(TAG, "Opening publication failed", t)
            _state.update {
                it.copy(
                    phase = ReaderPhase.ERROR,
                    errorMessage = t.message ?: "Could not open this book.",
                )
            }
        }
    }

    // --------------------------------------------------------------------- position (RD-12)

    @OptIn(FlowPreview::class)
    private fun startPositionSaving(engine: ReaderEngine) {
        viewModelScope.launch {
            engine.currentLocator
                .drop(1) // skip the restored locator
                .debounce(POSITION_SAVE_DEBOUNCE_MS) // RD-12: debounced ≤3s
                .collect { persistPosition(it) }
        }
    }

    private fun observeChromeData(engine: ReaderEngine) {
        viewModelScope.launch {
            engine.currentLocator.collect { locator ->
                _state.update {
                    it.copy(
                        chapterTitle = locator.title.orEmpty(),
                        remainingPercent = locator.locations.totalProgression
                            ?.let { p -> (1.0 - p).toFloat() },
                    )
                }
            }
        }
    }

    private suspend fun persistPosition(locator: Locator) {
        val json = Locators.toJsonString(locator) ?: return
        positionDao.upsert(
            PositionEntity(
                bookId = bookId,
                locatorJson = json,
                progressPercent = locator.locations.totalProgression?.times(100.0),
                updatedAt = nowIso(),
                // dirty=true → PushWorker pushes it on next sync (TRD §3.2).
                dirty = true,
            ),
        )
    }

    /** Called by the UI immediately before navigating away (RD-12: flush on close). */
    fun notifyClosing() {
        val locator = _state.value.engine?.currentLocator?.value ?: return
        closeScope.launch {
            runCatching { persistPosition(locator) }
        }
    }

    override fun onCleared() {
        val engine = _state.value.engine
        if (engine != null) {
            val locator = engine.currentLocator.value
            closeScope.launch {
                runCatching { persistPosition(locator) }
                withContext(Dispatchers.Main.immediate) {
                    runCatching { engine.close() }
                }
                closeScope.cancel()
            }
        }
        super.onCleared()
    }

    // --------------------------------------------------------------------- chrome & panels

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    fun setChromeVisible(visible: Boolean) = _state.update { it.copy(chromeVisible = visible) }

    fun setPanel(panel: ReaderPanel) = _state.update { it.copy(panel = panel) }

    fun closePanel() = _state.update { it.copy(panel = ReaderPanel.NONE) }

    // --------------------------------------------------------------------- navigation

    fun turnPage(forward: Boolean) {
        val engine = _state.value.engine ?: return
        viewModelScope.launch {
            runCatching { if (forward) engine.next() else engine.previous() }
                .onFailure { Log.w(TAG, "page turn failed", it) }
        }
    }

    fun jumpTo(locator: Locator) {
        val engine = _state.value.engine ?: return
        viewModelScope.launch {
            runCatching { engine.go(locator) }
                .onFailure { Log.w(TAG, "jump failed", it) }
        }
    }

    /** TOC / chapter navigation: jump to the start of an href. */
    fun jumpToHref(href: String, title: String? = null) {
        val locator = Locators.forHref(href.substringBefore('#'), title) ?: return
        jumpTo(locator)
    }

    // --------------------------------------------------------------------- search (RD-11)

    fun setSearchQuery(query: String) {
        _state.update {
            it.copy(
                searchQuery = query,
                searchGroups = if (query.isBlank()) emptyList() else it.searchGroups,
            )
        }
    }

    fun search(query: String) {
        val engine = _state.value.engine ?: return
        if (query.isBlank()) {
            _state.update { it.copy(searchGroups = emptyList(), searching = false) }
            return
        }
        _state.update { it.copy(searching = true, searchQuery = query) }
        viewModelScope.launch {
            val hits = runCatching { engine.search(query) }
                .getOrDefault(emptyList())
            _state.update { it.copy(searching = false, searchGroups = groupByChapter(hits)) }
        }
    }

    fun selectSearchHit(hit: EngineSearchHit) {
        jumpTo(hit.locator)
        closePanel()
        // Flash the match box overlay for ~800ms (approximation of in-page highlight).
        _state.update { it.copy(flashTick = it.flashTick + 1) }
    }

    private fun groupByChapter(hits: List<EngineSearchHit>): List<SearchChapterGroup> =
        hits.groupBy { Locators.normalizeHref(it.locator.href.toString()).orEmpty() }
            .map { (href, groupHits) ->
                SearchChapterGroup(
                    chapterTitle = tocTitleFor(href) ?: href.substringAfterLast('/'),
                    href = href,
                    hits = groupHits,
                )
            }

    private fun tocTitleFor(href: String): String? =
        _state.value.tableOfContents
            .firstOrNull { Locators.normalizeHref(it.href.toString()) == href }
            ?.title

    // --------------------------------------------------------------------- bookmarks (RD-10)

    fun toggleBookmark() {
        val locator = _state.value.engine?.currentLocator?.value ?: return
        viewModelScope.launch {
            val json = Locators.toJsonString(locator) ?: return@launch
            val currentHref = Locators.normalizeHref(Locators.hrefOfJson(json))
            val twin = _state.value.bookmarks.lastOrNull {
                Locators.normalizeHref(Locators.hrefOfJson(it.locatorJson)) == currentHref
            }
            if (twin != null) {
                // Same spot bookmarked already → remove (tombstone, SYN-3).
                bookmarkDao.tombstone(twin.id, nowIso(), nowIso())
            } else {
                val now = nowIso()
                bookmarkDao.upsert(
                    BookmarkEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = bookId,
                        locatorJson = json,
                        label = locator.title.orEmpty(), // RD-10: chapter title label
                        createdAt = now,
                        updatedAt = now,
                        dirty = true,
                    ),
                )
            }
        }
    }

    fun deleteBookmark(id: String) {
        viewModelScope.launch { bookmarkDao.tombstone(id, nowIso(), nowIso()) }
    }

    // --------------------------------------------------------------------- annotations (ANN-1/2/4)

    fun addAnnotation(
        color: String,
        note: String,
        excerpt: String,
        locatorJson: String,
        type: String = "highlight",
    ) {
        if (locatorJson.isBlank()) return
        viewModelScope.launch {
            val now = nowIso()
            annotationDao.upsert(
                AnnotationEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    type = type,
                    locatorJson = locatorJson,
                    color = color,
                    note = note,
                    excerpt = excerpt.take(MAX_EXCERPT_CHARS),
                    createdAt = now,
                    updatedAt = now,
                    dirty = true,
                ),
            )
            _state.value.engine?.clearSelection()
        }
    }

    fun updateNote(id: String, note: String) {
        val current = _state.value.annotations.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            annotationDao.upsert(
                current.copy(note = note, updatedAt = nowIso(), dirty = true),
            )
        }
    }

    /** ANN-3 delete → tombstone + dirty so PushWorker syncs the deletion (SYN-3). */
    fun deleteAnnotation(id: String) {
        viewModelScope.launch { annotationDao.tombstone(id, nowIso(), nowIso()) }
    }

    /** ANN-4 jump-back: navigate to a stored annotation locator. */
    fun jumpToAnnotation(item: AnnotationEntity) {
        val locator = Locators.fromJsonString(item.locatorJson) ?: return
        jumpTo(locator)
    }

    // --------------------------------------------------------------------- settings setters

    fun setPaginationMode(mode: String) = updateSettings { it.copy(readerPaginationMode = mode) }

    fun setPageTurnAnimation(animation: String) =
        updateSettings { it.copy(readerPageTurnAnimation = animation) }

    fun setFontSizeSp(value: Float) =
        updateSettings { it.copy(readerFontSizeSp = value.coerceIn(12f, 36f)) }

    fun setFontFamily(cssFamily: String) =
        updateSettings { it.copy(readerFontFamily = cssFamily) }

    fun setFontWeight(weight: Float) =
        updateSettings { it.copy(readerFontWeight = weight.coerceIn(300f, 800f)) }

    fun setLineHeight(value: Float) =
        updateSettings { it.copy(readerLineHeight = value.coerceIn(1f, 2f)) }

    fun setParagraphSpacing(value: Float) =
        updateSettings { it.copy(readerParagraphSpacing = value.coerceIn(0f, 2f)) }

    fun setLetterSpacing(value: Float) =
        updateSettings { it.copy(readerLetterSpacing = value.coerceIn(-0.05f, 0.3f)) }

    fun setTextAlignment(alignment: String) =
        updateSettings { it.copy(readerTextAlignment = alignment) }

    fun setPublisherDefaults(enabled: Boolean) =
        updateSettings { it.copy(readerPublisherDefaults = enabled) }

    fun setMargins(horizontal: Float? = null, vertical: Float? = null) = updateSettings { s ->
        s.copy(
            readerMarginsHorizontal = (horizontal ?: s.readerMarginsHorizontal).coerceIn(0f, 64f),
            readerMarginsVertical = (vertical ?: s.readerMarginsVertical).coerceIn(0f, 96f),
        )
    }

    fun setReaderTheme(theme: String) = updateSettings { it.copy(readerTheme = theme) }

    /** null → follow system (RD-14); slider maps 0 to null. */
    fun setBrightness(value: Float?) = updateSettings { it.copy(readerBrightness = value) }

    fun setKeepScreenOn(enabled: Boolean) =
        updateSettings { it.copy(readerKeepScreenOn = enabled) }

    fun setVolumeKeyTurns(enabled: Boolean) =
        updateSettings { it.copy(volumeKeyTurns = enabled) }

    fun setOrientationLock(mode: String) =
        updateSettings { it.copy(orientationLock = mode) }

    fun setTapZones(grid: TapZoneGrid) =
        updateSettings { it.copy(tapZonesJson = grid.toJson()) }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    /** Re-applies settings to the live navigator whenever they change. */
    private fun applySettingsToEngine() {
        viewModelScope.launch {
            settingsRepository.settings.collect { s ->
                _state.value.engine?.applySettings(s.toEngineSettings())
            }
        }
    }

    // --------------------------------------------------------------------- observers

    private fun observeAnnotations() {
        viewModelScope.launch {
            annotationDao.observeForBook(bookId).collect { items ->
                _state.update { it.copy(annotations = items) }
            }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkDao.observeForBook(bookId).collect { items ->
                _state.update { it.copy(bookmarks = items) }
            }
        }
    }

    private fun AppSettings.toEngineSettings() = EngineSettings(
        // Stored value is a bundled open-font name; engines receive the CSS family it maps to.
        fontFamily = ReaderFonts.cssFor(readerFontFamily),
        fontSizeSp = readerFontSizeSp,
        fontWeight = readerFontWeight,
        lineHeight = readerLineHeight,
        paragraphSpacing = readerParagraphSpacing,
        letterSpacing = readerLetterSpacing,
        textAlign = readerTextAlignment,
        publisherDefaults = readerPublisherDefaults,
        paginated = readerPaginationMode == "paginated",
        theme = readerTheme,
        // Horizontal margin slider (0..64dp) maps onto Readium's ~0.5..2.0 page-margin multiplier.
        pageMargins = (readerMarginsHorizontal / 24f).coerceIn(0.5f, 2f),
    )

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val POSITION_SAVE_DEBOUNCE_MS = 3_000L // RD-12 upper bound
        private const val MAX_EXCERPT_CHARS = 512

        fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
    }
}
