package com.bookcon.app.ui.reader

import android.content.Context
import android.graphics.Color
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.AiKeyStore
import com.bookcon.app.core.ReadAloudController
import com.bookcon.app.core.VoiceAssistant
import com.bookcon.app.core.AppSettings
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.core.Summarizer
import com.bookcon.app.core.SummaryCache
import com.bookcon.app.data.local.AnnotationDao
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookDao
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.BookmarkDao
import com.bookcon.app.data.local.BookmarkEntity
import com.bookcon.app.data.local.NoteDao
import com.bookcon.app.data.local.NotebookDao
import com.bookcon.app.data.local.NoteEntity
import com.bookcon.app.data.local.NotebookEntity
import com.bookcon.app.data.local.PositionDao
import com.bookcon.app.data.local.PositionEntity
import com.bookcon.app.data.sync.enqueueDownload
import com.bookcon.app.reader.EngineSearchHit
import com.bookcon.app.reader.EngineSettings
import com.bookcon.app.reader.Locators
import com.bookcon.app.reader.NoteContent
import com.bookcon.app.reader.NoteContentJson
import com.bookcon.app.reader.PdfBook
import com.bookcon.app.reader.PdfInkStroke
import com.bookcon.app.reader.PdfInkTool
import com.bookcon.app.reader.ReaderEngine
import com.bookcon.app.reader.ReaderEngineFactory
import com.bookcon.app.reader.TapZoneGrid
import com.bookcon.app.reader.inkJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import kotlinx.coroutines.sync.withLock
import com.bookcon.app.data.local.DownloadState
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted

/** Which overlay/panel (if any) is open on top of the reading surface. */
enum class ReaderPanel { NONE, TABLE_OF_CONTENTS, BOOKMARKS, SETTINGS, SEARCH }

enum class ReaderPhase { LOADING, DOWNLOADING, OPENING, READY, ERROR }

/**
 * The most recent ink stroke drawn on [page], or null when that page has none.
 *
 * Undo used to drop the last stroke in the whole notebook. Strokes carry the page
 * they were drawn on, so undoing while looking at a page you had not drawn on
 * silently erased ink from a different page — an invisible loss, since the stroke
 * that vanished was not the one under your finger.
 *
 * Top level so the page-scoping rule can be exercised directly.
 */
internal fun lastStrokeOnPage(
    strokes: List<PdfInkStroke>,
    page: Int,
): PdfInkStroke? = strokes.lastOrNull { it.page == page }

/**
 * Thins a stroke's points, dropping any that sit within [minFraction] of the last
 * one kept.
 *
 * Ink is captured at touch-sample rate — every event the OS delivers along a drag —
 * with no bound, and the finished stroke is serialized into one Room column and
 * pushed whole on every change. A few seconds of scribbling therefore produced tens
 * of thousands of coordinates, and each additional stroke re-serialized and
 * re-uploaded all of them.
 *
 * Points are normalized to 0..1, so [minFraction] is a fraction of the page: at the
 * default a point is dropped unless it moved at least ~0.4% of the page, which is
 * finer than any display can resolve. The first and last points are always kept so
 * a stroke's endpoints do not drift.
 *
 * What this guarantees is a bound, not perfect independence from the input. The
 * rule is greedy — measured against the last point kept — so a denser sampling of
 * the same line can keep a few more points, drifting up as float precision erodes
 * the small gaps. Measured on a line spanning 0.58 of a page: 240 samples keep 121
 * points, 480 keep 121, and 4800 keep 147. Storage is therefore set by how long the
 * stroke *is*, not by how long the finger was down or how fast the panel reported —
 * which is the property that matters, since the old cost was linear in samples.
 */
internal fun decimateStrokePoints(
    points: List<Float>,
    minFraction: Float = 0.004f,
): List<Float> {
    if (points.size < 4) return points
    val kept = ArrayList<Float>(points.size)
    kept.add(points[0])
    kept.add(points[1])
    var lastX = points[0]
    var lastY = points[1]
    // Iterate in whole points; a trailing odd float cannot form a coordinate and is
    // dropped rather than stored as a half-point.
    var i = 2
    while (i + 1 < points.size) {
        val x = points[i]
        val y = points[i + 1]
        val dx = x - lastX
        val dy = y - lastY
        if (dx * dx + dy * dy >= minFraction * minFraction) {
            kept.add(x)
            kept.add(y)
            lastX = x
            lastY = y
        }
        i += 2
    }
    // The endpoint is what the stroke is judged to have reached, so it is always
    // present even when the final samples were too close together to keep.
    if (points.size >= 2) {
        val endX = points[points.size - 2]
        val endY = points[points.size - 1]
        if (kept.size < 2 || kept[kept.size - 2] != endX || kept[kept.size - 1] != endY) {
            kept.add(endX)
            kept.add(endY)
        }
    }
    return kept
}

/** What the reader should do with a book row. */
internal enum class ReaderOpenDecision { OPEN_LOCAL, REPORT_FAILED, ENQUEUE_DOWNLOAD, WAIT }

/**
 * Decides how the reader reacts to a book row, given whether a local file is
 * present, whether the last download permanently failed, and whether a download has
 * already been asked for.
 *
 * Extracted so the whole state machine can be pinned in tests. The ordering is the
 * point: a present file always wins, a recorded failure is reported rather than
 * waited on, and only a book with neither gets a download enqueued.
 *
 * The failure case used to have no branch at all, so a book whose download the
 * server refused sat on "Downloading…" indefinitely — the row said FAILED, nothing
 * retried, and the reader had no way to say so.
 */
internal fun decideReaderOpen(
    hasLocalFile: Boolean,
    downloadFailed: Boolean,
    alreadyEnqueued: Boolean,
): ReaderOpenDecision = when {
    hasLocalFile -> ReaderOpenDecision.OPEN_LOCAL
    downloadFailed -> ReaderOpenDecision.REPORT_FAILED
    alreadyEnqueued -> ReaderOpenDecision.WAIT
    else -> ReaderOpenDecision.ENQUEUE_DOWNLOAD
}

/** Search hits grouped by chapter (RD-11: group by locator.href). */
data class SearchChapterGroup(
    val chapterTitle: String,
    val href: String,
    val hits: List<EngineSearchHit>,
)

/** In-reader AI page summary sheet state (loading / text / error are mutually exclusive). */
data class SummaryUiState(
    val loading: Boolean = false,
    val text: String? = null,
    val error: String? = null,
    val fromCache: Boolean = false,
)

/** Notebook sheet state (v1.5): one mixed-canvas note per book page. */
data class NotebookUiState(
    val open: Boolean = false,
    val pages: List<Int> = emptyList(),      // 0-based pages that already have notes
    val activePage: Int = 0,                 // page the open note belongs to
    val pageLabel: String = "",              // "Page 9" or the EPUB chapter title
    val content: NoteContent = NoteContent.EMPTY,
    val tool: PdfInkTool = PdfInkTool.NONE,  // NONE = type mode
    val color: String = "#FACC15",
    val saving: Boolean = false,
    /**
     * Text typed into the note box but not yet committed with "Add".
     *
     * This lives here rather than in the composable because the sheet used to hold it
     * in `remember(st.activePage, st.pages)`, which discarded the draft on every page
     * change and whenever the sheet was closed and reopened — while the row beside the
     * input cheerfully read "Saved" the whole time.
     */
    val draft: String = "",
)

data class ReaderUiState(
    val phase: ReaderPhase = ReaderPhase.LOADING,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val book: BookEntity? = null,
    val engine: ReaderEngine? = null,
    /** Non-null when the book is a PDF rendered by our own pager (no Readium navigator). */
    val pdfBook: PdfBook? = null,
    val pdfStartPage: Int = 0,
    /** Ink strokes per 0-based PDF page (INK-1). */
    val pdfStrokes: Map<Int, List<PdfInkStroke>> = emptyMap(),
    /** Ink strokes for EPUB pages keyed by "<href>#<screenPosition>" (INK-4). */
    val epubStrokes: Map<String, List<PdfInkStroke>> = emptyMap(),
    /** Anchor of the currently visible EPUB screen. */
    val epubAnchor: String = "",

    /** Active PDF ink tool; NONE hides the ink toolbar and restores page taps. */
    val pdfInkTool: PdfInkTool = PdfInkTool.NONE,
    val pdfInkColor: String = "#FACC15",
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

/**
 * The locator shape used for PDF positions and bookmarks alike.
 *
 * It is top level rather than a ViewModel member so it can be exercised directly:
 * it is pure string building, and both the saved-position path and the bookmark
 * path depend on it producing a form [PDF_PAGE_HREF] can read back.
 */
internal fun pdfLocatorJson(page: Int, pageCount: Int): String {
    val progress = (page + 1).toDouble() / pageCount.coerceAtLeast(1)
    return """{"href":"/p${page + 1}","title":"Page ${page + 1}","locations":{"totalProgression":$progress}}"""
}

/**
 * Pulls the page number out of a PDF locator.
 *
 * This pattern used to demand `"href":"/p` with no space after the colon, so any
 * locator that had been re-serialised with whitespace produced no match and the
 * book silently reopened at page 1 — indistinguishable from "my bookmark did
 * nothing".
 */
internal val PDF_PAGE_HREF = Regex("\"href\"\\s*:\\s*\"/p(\\d+)")

/**
 * The 0-based page a stored PDF locator points at, or null when it names no page
 * in range.
 *
 * PDF locator hrefs are one-based ("/p1" is the first page) while the pager is
 * zero-based, so this boundary is easy to get wrong in either direction — and a
 * wrong answer is silent: the book jumps to the wrong page, or off the end.
 * `pageCount <= 0` is treated as "unknown", so a not-yet-rendered book falls back
 * to opening where it is rather than refusing to move.
 */
/**
 * What the bookmarks list shows for a bookmark that has no label of its own.
 *
 * A bookmark is created as `(engineLocator?.title ?: chapterTitle).orEmpty()`, so
 * one saved before the navigator has reported a title — or in a book whose EPUB
 * carries no chapter metadata — has a blank label. The list used to fall back to
 * the raw locator, which put text like `{"href":"/p12","type":"application/pdf"}`
 * on screen as the description of the bookmark.
 *
 * A real label wins; failing that a page number is more use than a generic phrase
 * for the paginated formats, and the last resort says something honest rather than
 * leaking JSON.
 */
internal fun humanBookmarkLabel(label: String, locatorJson: String): String {
    label.trim().takeIf { it.isNotEmpty() }?.let { return it }
    PDF_PAGE_HREF.find(locatorJson)?.groupValues?.get(1)?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?.let { return "Page $it" }
    return "Saved bookmark"
}

internal fun pdfPageFromLocator(locatorJson: String?, pageCount: Int): Int? {
    val oneBased = locatorJson?.let { PDF_PAGE_HREF.find(it)?.groupValues?.get(1) }?.toIntOrNull()
        ?: return null
    val page = oneBased - 1
    if (page < 0) return null
    if (pageCount > 0 && page >= pageCount) return null
    return page
}

/**
 * The page a tap-zone or volume-key page turn should land on, or null when that
 * direction is already at the end of the document.
 *
 * A reader without a Readium engine — every PDF — cannot use the animated
 * page-turn coordinator, so those turns have to be resolved against the pager
 * instead. [current] is -1 until the pager reports its first page, which is
 * treated as the first page rather than being rejected.
 */
internal fun pdfTurnTarget(current: Int, pageCount: Int, forward: Boolean): Int? {
    if (pageCount <= 0) return null
    val from = current.coerceIn(0, pageCount - 1)
    val target = if (forward) from + 1 else from - 1
    return if (target in 0 until pageCount) target else null
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bookDao: BookDao,
    private val annotationDao: AnnotationDao,
    private val bookmarkDao: BookmarkDao,
    private val positionDao: PositionDao,
    private val notebookDao: NotebookDao,
    private val noteDao: NoteDao,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private var initialized = false
    private var startLocatorJson: String? = null
    private var bookId = ""
    private var downloadEnqueued = false
    private var engineOpening = false
    private var openedAtTouched = false

    /** 0-based page of the current PDF view; -1 while no PDF is open. */
    private var pdfCurrentPage = -1
    private var pdfPositionJob: Job? = null
    private var decorationsJob: Job? = null

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    // Notebook (v1.5): one note per book page, opened by swipe-up or pill button.
    private val _notebook = MutableStateFlow(NotebookUiState())
    val notebook: StateFlow<NotebookUiState> = _notebook.asStateFlow()
    private var notebookNotebookId: String = ""
    private var notebookSaveJob: Job? = null
    private var searchJob: Job? = null

    // AI page summaries: context-backed collaborators, constructed plainly like the
    // DataStore repository (no DI bindings yet).
    private val aiKeyStore by lazy { AiKeyStore(appContext) }
    private val summaryCache by lazy { SummaryCache(appContext) }
    private val summarizer = Summarizer()

    /** Voice conversation assistant (PRD VOICE-1) — shared singleton across the reader. */
    val voiceAssistant: VoiceAssistant by lazy {
        VoiceAssistant(appContext, settingsRepository, aiKeyStore, summarizer)
    }

    private var summaryJob: Job? = null

    // ---- Wave 2: AI selection actions + read-aloud + pdf jump requests (PRD SEL-AI/TTS/THUMB)
    private val readAloud by lazy {
        ReadAloudController(appContext) { viewModelScope.launch { advanceAndSpeakNext() } }
    }

    data class TtsState(val active: Boolean = false, val speaking: Boolean = false, val error: String? = null)

    private val _ttsState = MutableStateFlow(TtsState())
    val ttsState: StateFlow<TtsState> = _ttsState

    /** Set by the VM when TTS/auto-jump wants the PDF pager to scroll; consumed by PdfPager. */
    private val _pdfTurnRequest = MutableStateFlow<Int?>(null)
    val pdfTurnRequest: StateFlow<Int?> = _pdfTurnRequest

    fun consumePdfTurnRequest() {
        _pdfTurnRequest.value = null
    }

    data class SelectionAiState(
        val selectedText: String? = null,
        val loading: Boolean = false,
        val action: String? = null,
        val result: String? = null,
        val error: String? = null,
    )

    private val _selectionAi = MutableStateFlow(SelectionAiState())
    val selectionAi: StateFlow<SelectionAiState> = _selectionAi

    private var selectionPollJob: kotlinx.coroutines.Job? = null

    /** Starts polling the reader surface for a text selection (EPUB only in practice). */
    fun startSelectionPolling() {
        if (selectionPollJob != null) return
        selectionPollJob = viewModelScope.launch {
            while (true) {
                delay(900)
                val engine = _state.value.engine ?: continue
                if (!engine.hasSelectionBridge) continue
                val text = withContext(Dispatchers.IO) { suspendSelectionFetch(engine) }
                // suspendSelectionFetch has to block a thread to adapt the engine's
                // callback API, so it is not cancellable. Without this check a poll
                // that was cancelled by stopSelectionPolling() would still land and
                // repopulate the state that the stop had just cleared.
                coroutineContext.ensureActive()
                if (text != _selectionAi.value.selectedText && _selectionAi.value.action == null) {
                    _selectionAi.update { it.copy(selectedText = text) }
                }
            }
        }
    }

    fun stopSelectionPolling() {
        selectionPollJob?.cancel()
        selectionPollJob = null
        _selectionAi.value = SelectionAiState()
    }

    private fun suspendSelectionFetch(engine: ReaderEngine): String? =
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(1200) {
                kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                    engine.currentSelectionText { res -> cont.resume(res) {} }
                }
            }
        }

    /** Runs one of explain / translate / summarize over the current selection. */
    fun runSelectionAi(action: String) {
        val sel = _selectionAi.value.selectedText?.trim().orEmpty()
        runSelectionAi(action, sel)
    }

    /** Explicit-text variant used by the highlight/selection toolbar (iframe-safe). */
    fun runSelectionAi(action: String, text: String) {
        val sel = text.trim()
        if (sel.length < 2 || _selectionAi.value.loading) return
        _selectionAi.update { it.copy(loading = true, action = action, selectedText = sel.take(80), error = null) }
        viewModelScope.launch {
            val settingsNow = settingsRepository.settings.value
            val apiKey = withContext(Dispatchers.IO) { aiKeyStore.get() }
            if (apiKey.isBlank()) {
                _selectionAi.update { it.copy(loading = false, error = "Add your API key in Settings → AI summary.") }
                return@launch
            }
            val prompt = when (action) {
                "explain" -> "Explain this passage from \"${_state.value.book?.title.orEmpty()}\" in simple language. Keep it under 120 words.\n\n$sel"
                "translate" -> "Translate this passage into English. Output only the translation.\n\n$sel"
                else -> "Summarize this passage in 2-3 sentences.\n\n$sel"
            }
            val result = withContext(Dispatchers.IO) {
                Summarizer().summarize(
                    provider = settingsNow.aiProvider,
                    baseUrl = settingsNow.aiBaseUrl,
                    apiKey = apiKey,
                    model = settingsNow.aiModel.ifBlank { Summarizer.defaultModel(settingsNow.aiProvider) },
                    bookTitle = _state.value.book?.title.orEmpty(),
                    pageLabel = action,
                    pageText = prompt,
                )
            }
            result.fold(
                onSuccess = { text -> _selectionAi.update { it.copy(loading = false, result = text) } },
                onFailure = { t ->
                    _selectionAi.update { it.copy(loading = false, error = t.message ?: "AI request failed") }
                },
            )
        }
    }

    fun dismissSelectionAi() {
        _selectionAi.update { SelectionAiState(selectedText = it.selectedText) }
    }

    // ---- read-aloud ----
    fun toggleReadAloud() {
        if (_ttsState.value.active) {
            stopReadAloud()
        } else {
            startReadAloud()
        }
    }

    private fun startReadAloud() {
        val engine = _state.value.engine
        readAloud.ratePercent = settingsRepository.settings.value.ttsRate
        speakCurrentPage()
        if (_ttsState.value.error == null) {
            _ttsState.value = TtsState(active = true, speaking = true)
        }
        // Engine kept for auto-turn below.
        engine?.let { /* next handled in advanceAndSpeakNext */ }
    }

    /**
     * Held so Stop can cancel work that has not reached the engine yet.
     *
     * Extracting the page text can take a while (EPUB re-reads and tag-strips the
     * whole resource; PDF runs PDFBox). Previously the job was never stored, so
     * tapping Stop mid-extraction still ended up speaking the page and flipping the
     * pill back on.
     */
    private var speakJob: Job? = null

    /** Where the previous narration stopped, so the next one continues rather than restarts. */
    private var narratedHref: String? = null
    private var narratedUpTo = 0

    /**
     * Pushes the stored narration settings onto the TTS engine.
     *
     * setTtsRate and setTtsVoiceName existed and persisted, but nothing ever read
     * them back onto the controller, so speed was permanently 100% and the chosen
     * voice was ignored. Applied on every start so a change takes effect at once.
     */
    private fun applyNarrationSettings() {
        val s = settings.value
        runCatching {
            readAloud.ratePercent = s.ttsRate
            readAloud.voiceName = s.ttsVoiceName
        }
    }

    /** True while a PDF is open, so PDF-only controls can be shown. */
    val currentFormatIsPdf: Boolean get() = _state.value.pdfBook != null

    /** Voices this device can speak with, for the reader's narration settings. */
    fun availableVoices(): List<Pair<String, String>> =
        readAloud.availableVoices().map { it.name to (it.locale?.toLanguageTag() ?: "") }

    /**
     * The same list, but re-read once the speech engine reports it is ready.
     *
     * The reader's panel host stays in composition for the whole time a book is
     * open, so a caller that read the voices once and cached them held whatever it
     * saw at that moment. Opening a book and reaching for narration settings before
     * the engine finished initialising therefore hid the Voice section until the
     * book was closed and reopened.
     */
    val ttsVoices: StateFlow<List<Pair<String, String>>> = readAloud.ready
        .map { ready -> if (ready) availableVoices() else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTtsRate(percent: Int) {
        viewModelScope.launch { settingsRepository.setTtsRate(percent) }
        runCatching { readAloud.ratePercent = percent.coerceIn(50, 300) }
    }

    fun setTtsVoice(name: String) {
        viewModelScope.launch { settingsRepository.setTtsVoiceName(name) }
        runCatching { readAloud.voiceName = name }
    }

    private fun speakCurrentPage() {
        speakJob?.cancel()
        speakJob = viewModelScope.launch {
            val engine = _state.value.engine
            // EPUB has a continuous resource, so narration can pick up where it left
            // off. PDF pages are handled by currentPageTextInfo() below.
            val narration = engine?.narrationText()
            val (label, fullText, visibleOffset) = narration
                ?: run {
                    val info = withContext(Dispatchers.IO) { currentPageTextInfo() }
                    if (!_ttsState.value.active) return@launch
                    val text = info?.second
                    if (text.isNullOrBlank()) {
                        _ttsState.value = TtsState(
                            active = true, speaking = false,
                            error = "No readable text on this page.",
                        )
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        applyNarrationSettings()
                        readAloud.speak(trimToSentence(text, MAX_NARRATION_CHARS))
                    }
                    if (!_ttsState.value.active) return@launch
                    _ttsState.value = TtsState(active = true, speaking = true)
                    return@launch
                }

            if (fullText.isBlank()) {
                _ttsState.value = TtsState(
                    active = true, speaking = false, error = "No readable text on this page.",
                )
                return@launch
            }

            // Still in the same resource and the screen is at or before where we
            // stopped: carry on rather than narrating the opening again. This is what
            // made read-aloud loop the start of a chapter on every auto page-turn.
            val start = if (label == narratedHref && visibleOffset <= narratedUpTo) {
                narratedUpTo
            } else {
                visibleOffset
            }
            val slice = fullText.substring(
                start.coerceIn(0, fullText.length),
                (start + MAX_NARRATION_CHARS).coerceAtMost(fullText.length),
            ).trim()
            if (slice.isBlank()) {
                // Nothing new to say on this page; move on to the next one.
                advanceAndSpeakNext()
                return@launch
            }
            val said = trimToSentence(slice, MAX_NARRATION_CHARS)
            withContext(Dispatchers.IO) { applyNarrationSettings(); readAloud.speak(said) }
            if (!_ttsState.value.active) return@launch
            narratedHref = label
            narratedUpTo = start + said.length
            _ttsState.value = TtsState(active = true, speaking = true)
        }
    }

    /**
     * Trims to a sentence end so narration never stops mid-word.
     * Falls back to the last space, then to the raw cut.
     */
    private fun trimToSentence(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.substring(0, limit)
        val sentenceEnd = cut.lastIndexOfAny(charArrayOf('.', '!', '?', '\u2026'))
        if (sentenceEnd > limit / 3) return cut.substring(0, sentenceEnd + 1)
        val space = cut.lastIndexOf(' ')
        return if (space > limit / 3) cut.substring(0, space) else cut
    }

    private suspend fun advanceAndSpeakNext() {
        val engine = _state.value.engine
        if (!_ttsState.value.active) return
        val pdfBook = _state.value.pdfBook
        when {
            pdfBook != null -> {
                val next = pdfCurrentPage + 1
                if (next < pdfBook.pageCount) {
                    _pdfTurnRequest.value = next
                    onPdfPageChanged(next)
                    delay(700) // let the pager render
                    speakCurrentPage()
                } else {
                    stopReadAloud()
                }
            }
            engine != null -> {
                val moved = engine.next()
                if (moved) {
                    delay(900)
                    speakCurrentPage()
                } else {
                    stopReadAloud()
                }
            }
            else -> stopReadAloud()
        }
    }

    fun pauseReadAloud() {
        speakJob?.cancel()
        readAloud.pause()
        _ttsState.value = _ttsState.value.copy(speaking = false)
    }

    fun resumeReadAloud() {
        speakCurrentPage()
    }

    fun stopReadAloud() {
        // Clear `active` BEFORE cancelling so a job that is mid-extraction sees the
        // flag flip and bails out instead of speaking after the user stopped it.
        _ttsState.value = TtsState(active = false)
        speakJob?.cancel()
        speakJob = null
        narratedHref = null
        narratedUpTo = 0
        // stop(), not shutdown(): this also runs at the end of every chapter, and
        // shutting the engine down there killed read-aloud for the rest of the
        // session while the pill still claimed it was reading.
        readAloud.stop()
    }

    private val _summaryState = MutableStateFlow(SummaryUiState())
    val summaryState: StateFlow<SummaryUiState> = _summaryState.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    /**
     * Outlives [viewModelScope] so the RD-12 "save position immediately on close" flush and the
     * final navigator teardown still run after onCleared() cancels the UI scope.
     */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * @param startLocatorJson when set (coming from a bookmark), the navigator opens
     *   here instead of at the last saved position. The saved position is not
     *   overwritten until the reader actually saves, so backing out of a bookmark
     *   does not lose the real resume point.
     */
    fun init(bookId: String, startLocatorJson: String? = null) {
        if (initialized) return
        initialized = true
        this.bookId = bookId
        this.startLocatorJson = startLocatorJson
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
                when (decideReaderOpen(
                    hasLocalFile = file != null && file.exists(),
                    downloadFailed = book.downloadState == DownloadState.FAILED,
                    alreadyEnqueued = downloadEnqueued,
                )) {
                    // Offline-first: a present local file opens instantly, network or not.
                    ReaderOpenDecision.OPEN_LOCAL -> openEngine(file!!)

                    // A permanently failed download (the server answered 404/409, or
                    // the restored backup had no payload for this book) leaves the row
                    // with no local file and a FAILED state. Neither other branch
                    // matched that, so the reader sat on "Downloading…" for ever — no
                    // error, no way forward, and the download had long since stopped.
                    ReaderOpenDecision.REPORT_FAILED -> {
                        // Re-arm so retrying from the details screen starts a fresh
                        // attempt instead of falling through to the stale "still
                        // downloading" branch.
                        downloadEnqueued = false
                        _state.update {
                            it.copy(
                                phase = ReaderPhase.ERROR,
                                errorMessage = "This book could not be downloaded. " +
                                    "It may no longer be on the server, or a restored " +
                                    "backup was missing its file.",
                            )
                        }
                    }

                    // No file yet → kick the download worker and keep the reader in a
                    // non-blocking "Downloading…" state until the row updates (SYN-6).
                    ReaderOpenDecision.ENQUEUE_DOWNLOAD -> {
                        downloadEnqueued = true
                        enqueueDownload(appContext, book.id)
                        val offline = !com.bookcon.app.core.Net.isOnline(appContext)
                        _state.update {
                            it.copy(
                                phase = ReaderPhase.DOWNLOADING,
                                statusMessage = if (offline) {
                                    "This book isn't saved on this device yet — connect to internet once and it will open automatically."
                                } else {
                                    "Downloading…"
                                },
                            )
                        }
                    }

                    // A download really is still in flight; the row will move on.
                    ReaderOpenDecision.WAIT -> Unit
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
            val initialLocator = Locators.fromJsonString(startLocatorJson ?: saved?.locatorJson)

            if (PdfBook.looksLikePdf(file)) {
                // startLocatorJson wins: tapping a PDF bookmark has to land on the
                // bookmarked page, not the last-read one. This was previously dropped,
                // so every PDF bookmark opened at wherever the reader last stopped.
                openPdf(file, startLocatorJson ?: saved?.locatorJson)
                return
            }

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
            loadEpubStrokesFromDb()
            refreshDecorations(_state.value.annotations)
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

    /**
     * PDF path: Readium has no PDF navigator for toolkit 3.1.0, so PDFs render through
     * our own PdfRenderer pager ([com.bookcon.app.ui.reader.PdfPager]). Positions persist
     * as {"href":"/p<N>"} locators so they stay sync-compatible with EPUB positions.
     */
    private suspend fun openPdf(file: File, savedLocatorJson: String?) {
        try {
            // The book-row observer calls touchOpened() on its first emission, which
            // is a suspend UPDATE — that invalidates the Room Flow and re-emits the
            // row, so openEngine runs a second time. The EPUB path is protected only
            // because engineOpening stays true after a successful open; openPdf used
            // to reset it to false, which let the second pass overwrite pdfBook and
            // leak the first PdfRenderer and its file descriptor.
            val pdf = withContext(Dispatchers.IO) { PdfBook.open(file) }
            _state.value.pdfBook?.takeIf { it !== pdf }?.let { stale ->
                runCatching { stale.close() }
            }
            val savedPage = savedLocatorJson
                ?.let { json -> PDF_PAGE_HREF.find(json)?.groupValues?.get(1)?.toIntOrNull() }
                ?: 1
            if (savedLocatorJson != null && savedPage == 1 &&
                PDF_PAGE_HREF.containsMatchIn(savedLocatorJson) == false && savedLocatorJson.isNotBlank()
            ) {
                // A PDF locator we cannot read means the book reopens at page 1 even
                // though a position was saved. That used to happen silently, which
                // looks exactly like "my bookmark did nothing".
                Log.w(TAG, "Could not read a page number out of PDF locator: $savedLocatorJson")
            }
            val start = (savedPage - 1).coerceIn(0, maxOf(0, pdf.pageCount - 1))
            pdfCurrentPage = start
            _state.update {
                it.copy(
                    engine = null,
                    pdfBook = pdf,
                    pdfStartPage = start,
                    phase = ReaderPhase.READY,
                    chapterTitle = "Page ${start + 1}",
                    remainingPercent = if (pdf.pageCount > 0) 1f - (start + 1f) / pdf.pageCount else null,
                    tableOfContents = emptyList(),
                )
            }
            loadPdfStrokesFromDb()
        } catch (t: Throwable) {
            engineOpening = false
            Log.e(TAG, "Opening PDF failed", t)
            _state.update {
                it.copy(phase = ReaderPhase.ERROR, errorMessage = "Could not open this PDF: ${t.message}")
            }
        }
    }

    fun onPdfPageChanged(index: Int) {
        pdfCurrentPage = index
        val pdf = _state.value.pdfBook ?: return
        val count = pdf.pageCount
        if (count <= 0) return
        _state.update {
            it.copy(
                chapterTitle = "Page ${index + 1}",
                remainingPercent = 1f - (index + 1f) / count,
                // PdfPager seeds rememberPagerState from this, and the saved position
                // does not help here: on a configuration change the ViewModel (and so
                // pdfCurrentPage) survives while the composable tree is rebuilt, so the
                // pager was re-seeding from the page the book opened at and a rotation
                // threw the reader back to the start. Safe to keep current — remember
                // ignores a changed initialPage, so this cannot yank the pager around
                // mid-read; it only takes effect when the pager is (re)created.
                pdfStartPage = index,
            )
        }
        if (pdfPositionJob?.isActive == true) return // already debouncing
        pdfPositionJob = viewModelScope.launch {
            delay(POSITION_SAVE_DEBOUNCE_MS)
            savePdfPosition()
        }
    }

    private fun savePdfPosition() {
        val page = pdfCurrentPage
        val pdf = _state.value.pdfBook ?: return
        if (page < 0 || pdf.pageCount <= 0) return
        val progress = (page + 1).toDouble() / pdf.pageCount
        val json = pdfLocatorJson(page, pdf.pageCount)
        viewModelScope.launch {
            positionDao.upsert(
                PositionEntity(
                    bookId = bookId,
                    locatorJson = json,
                    progressPercent = progress * 100.0,
                    updatedAt = nowIso(),
                    dirty = true,
                ),
            )
        }
    }

    // --------------------------------------------------------------------- PDF ink (INK-1/2/3)

    /**
     * All strokes of one PDF page live in a single annotation row:
     * type="ink", locator={"href":"/pN"}, note=JSON array of [PdfInkStroke].
     * One row per page keeps erase/undo cheap and stays sync-compatible.
     */
    private fun inkRowId(page: Int) = "ink:$bookId:$page"

    private suspend fun loadPdfStrokesFromDb() {
        val rows = annotationDao.observeForBook(bookId).firstOrNull().orEmpty()
        val map = mutableMapOf<Int, List<PdfInkStroke>>()
        for (row in rows) {
            if (row.type != "ink") continue
            val page = Regex("\"href\":\"/p(\\d+)").find(row.locatorJson)
                ?.groupValues?.get(1)?.toIntOrNull()?.minus(1) ?: continue
            runCatching { inkJson.decodeFromString<List<PdfInkStroke>>(row.note) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { map[page] = it }
        }
        _state.update { it.copy(pdfStrokes = map) }
    }

    private suspend fun writePdfStrokes(page: Int, strokes: List<PdfInkStroke>) {
        val now = nowIso()
        if (strokes.isEmpty()) {
            // Tombstone the row so the eraser syncs to other devices too (SYN-3).
            annotationDao.tombstone(inkRowId(page), now, now)
        } else {
            val existing = annotationDao.getById(inkRowId(page))
            annotationDao.upsert(
                AnnotationEntity(
                    id = inkRowId(page),
                    bookId = bookId,
                    type = "ink",
                    locatorJson = """{"href":"/p${page + 1}"}""",
                    color = strokes.lastOrNull()?.color ?: "#FACC15",
                    note = inkJson.encodeToString(strokes),
                    excerpt = "",
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    dirty = true,
                ),
            )
        }
        _state.update { it.copy(pdfStrokes = it.pdfStrokes + (page to strokes)) }
    }

    /**
     * Serialises ink edits per page.
     *
     * Every stroke goes through a read of the current list, an append/remove, and a
     * write back. `writePdfStrokes` suspends on Room, and Room dispatches suspend DAO
     * calls to its own executor, so two strokes drawn a moment apart could both read
     * the same base list and the second write silently discarded the first — the
     * user's marks vanished with no error, exactly when drawing quickly.
     *
     * One lock per page keeps the read-modify-write atomic while still letting
     * different pages be edited at once.
     */
    private val inkLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** [key] is the PDF page index, or the EPUB "<href>#<position>" anchor. */
    private suspend fun <T> withInkLock(key: Any, block: suspend () -> T): T {
        val lock = synchronized(inkLocks) { inkLocks.getOrPut(key.toString()) { kotlinx.coroutines.sync.Mutex() } }
        return lock.withLock { block() }
    }

    /** INK-1: user lifted the finger/stylus → store the finished stroke. */
    fun addPdfStroke(page: Int, mode: String, points: List<Float>) {
        if (points.size < 4 || page < 0) return
        val tool = _state.value.pdfInkTool
        when (tool) {
            PdfInkTool.NONE, PdfInkTool.ERASER -> return
            else -> {}
        }
        val stroke = PdfInkStroke(
            id = UUID.randomUUID().toString(),
            page = page,
            // Use the toolbar-selected color for both pen and marker (v1.4.3 fix:
            // highlighter strokes used to be forced back to yellow on save).
            color = _state.value.pdfInkColor,
            width = if (mode == "highlighter") HIGHLIGHTER_WIDTH_DP else PEN_WIDTH_DP,
            points = decimateStrokePoints(points),
            mode = mode,
        )
        viewModelScope.launch {
            withInkLock(page) {
                val strokes = (_state.value.pdfStrokes[page].orEmpty() + stroke)
                writePdfStrokes(page, strokes)
            }
        }
    }

    /** INK-3: remove the stroke under the finger (hit-tested by the overlay). */
    fun erasePdfStroke(page: Int, strokeId: String) {
        viewModelScope.launch {
            withInkLock(page) {
                val strokes = _state.value.pdfStrokes[page].orEmpty()
                if (strokes.none { it.id == strokeId }) return@withInkLock
                writePdfStrokes(page, strokes.filterNot { it.id == strokeId })
            }
        }
    }

    /** INK-2 toolbar undo: drop the most recent stroke on the visible page. */
    fun undoLastPdfStroke() {
        val page = pdfCurrentPage
        if (page < 0) return
        viewModelScope.launch {
            withInkLock(page) {
                val strokes = _state.value.pdfStrokes[page].orEmpty()
                if (strokes.isEmpty()) return@withInkLock
                writePdfStrokes(page, strokes.dropLast(1))
            }
        }
    }

    // ---------------------------------------------------------------- Notebook (v1.5)

    /** Current note anchor: PDF → page index; EPUB → "<href>#<position>". */
    private fun currentNoteAnchor(): Pair<Int, String> =
        if (_state.value.pdfBook != null) {
            val p = pdfCurrentPage.coerceAtLeast(0)
            p to ""
        } else {
            -1 to _state.value.epubAnchor
        }

    /** Open the notebook sheet for the page being read (auto-creates the notebook). */
    fun openNotebook() {
        if (_notebook.value.open) return
        viewModelScope.launch {
            val nb = notebookDao.forBook(bookId)
                ?: NotebookEntity(
                    id = "nb:$bookId",
                    bookId = bookId,
                    createdAt = nowIso(),
                    updatedAt = nowIso(),
                ).also { notebookDao.upsert(it) }
            notebookNotebookId = nb.id
            val (page, anchor) = currentNoteAnchor()
            // Pages that already have notes (for the page chips row).
            val pages = noteDao.observeForNotebook(nb.id).first().map { it.bookPage }.distinct().sorted()
            val label =
                if (page >= 0) "Page ${page + 1}"
                else _state.value.chapterTitle.ifBlank { "Note" }
            _notebook.value = NotebookUiState(
                open = true,
                pages = pages,
                activePage = page,
                pageLabel = label,
                tool = PdfInkTool.NONE,
                color = _notebook.value.color,
            )
            loadNoteForActivePage(nb.id, page, anchor)
        }
    }

    /** Switch the sheet to another note page (or create it on demand). */
    fun openNotebookPage(page: Int) {
        val nbId = notebookNotebookId
        if (nbId.isBlank()) return
        viewModelScope.launch {
            val anchor = if (page >= 0) "" else _notebook.value.pageLabel
            _notebook.value = _notebook.value.copy(activePage = page, pageLabel = "Page ${page + 1}")
            loadNoteForActivePage(nbId, page, anchor)
        }
    }

    private suspend fun loadNoteForActivePage(nbId: String, page: Int, anchor: String) {
        val existing =
            if (page >= 0) noteDao.forPage(nbId, page)
            else noteDao.forAnchor(nbId, anchor)
        val content = existing?.let { NoteContentJson.decode(it.contentJson) } ?: NoteContent.EMPTY
        _notebook.value = _notebook.value.copy(content = content)
    }

    /** Updates the uncommitted note text. Survives page changes and sheet close. */
    fun setNotebookDraft(text: String) {
        _notebook.value = _notebook.value.copy(draft = text)
    }

    /** Clears the uncommitted draft once it has been committed as a block. */
    fun clearNotebookDraft() {
        if (_notebook.value.draft.isNotEmpty()) {
            _notebook.value = _notebook.value.copy(draft = "")
        }
    }

    fun closeNotebook() {
        flushNotebookSave()
        _notebook.value = _notebook.value.copy(open = false)
    }

    fun setNotebookTool(tool: PdfInkTool) {
        _notebook.value = _notebook.value.copy(tool = tool)
    }

    fun setNotebookColor(colorHex: String) {
        _notebook.value = _notebook.value.copy(color = colorHex)
    }

    /** Debounced autosave of the open note (called on every content mutation). */
    fun saveNotebookContent(content: NoteContent) {
        _notebook.value = _notebook.value.copy(content = content)
        notebookSaveJob?.cancel()
        notebookSaveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            persistNote(content)
        }
    }

    /** Immediate save (sheet closing, app backgrounding). */
    private fun flushNotebookSave() {
        val st = _notebook.value
        if (!st.open) return
        notebookSaveJob?.cancel()
        val content = st.content
        if (content.isEmpty) return
        viewModelScope.launch { persistNote(content) }
    }

    /**
     * The page/anchor the OPEN note belongs to.
     *
     * This deliberately reads the notebook sheet rather than the live reader. The
     * sheet's prev/next chips let you move to a different page while the reader
     * itself stays where it was, so deriving the anchor from `pdfCurrentPage` /
     * `epubAnchor` stored the edited text under the *reader's* page — wiping the
     * note that really lived there and leaving the chip-selected page empty.
     */
    private fun openNoteAnchor(): Pair<Int, String> {
        val page = _notebook.value.activePage
        return if (page >= 0) page to "" else -1 to _notebook.value.pageLabel
    }

    private suspend fun persistNote(content: NoteContent) {
        val nbId = notebookNotebookId
        if (nbId.isBlank()) return
        val (page, anchor) = openNoteAnchor()
        val id = "note:$nbId:${if (page >= 0) page.toString() else anchor.hashCode()}"
        val now = nowIso()
        val existing = noteDao.forPage(nbId, page)
            ?: noteDao.forAnchor(nbId, anchor)
        noteDao.upsert(
            NoteEntity(
                id = id,
                notebookId = nbId,
                bookId = bookId,
                bookPage = page,
                anchorKey = anchor,
                contentJson = NoteContentJson.encode(content),
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        _notebook.value = _notebook.value.copy(
            saving = false,
            // A note just created for this page has to join the chip row, otherwise
            // the freshly written page reports the wrong index and gets no chip.
            pages = if (page >= 0 && page !in _notebook.value.pages) {
                (_notebook.value.pages + page).sorted()
            } else {
                _notebook.value.pages
            },
        )
    }

    /** Add a finished ink stroke to the open note (drawn on the sheet canvas). */
    fun addNotebookStroke(points: List<Float>) {
        val st = _notebook.value
        if (points.size < 4) return
        val mode = when (st.tool) {
            PdfInkTool.HIGHLIGHTER -> "highlighter"
            else -> "pen"
        }
        val stroke = PdfInkStroke(
            page = st.activePage,
            color = st.color,
            width = if (mode == "highlighter") ReaderViewModel.HIGHLIGHTER_WIDTH_DP else ReaderViewModel.PEN_WIDTH_DP,
            points = decimateStrokePoints(points),
            mode = mode,
        )
        saveNotebookContent(st.content.copy(strokes = st.content.strokes + stroke))
    }

    fun undoNotebookStroke() {
        val st = _notebook.value
        val target = lastStrokeOnPage(st.content.strokes, st.activePage) ?: return
        saveNotebookContent(
            st.content.copy(strokes = st.content.strokes.filterNot { it.id == target.id }),
        )
    }

    fun eraseNotebookStroke(strokeId: String) {
        val st = _notebook.value
        saveNotebookContent(st.content.copy(strokes = st.content.strokes.filterNot { it.id == strokeId }))
    }

    // ------------------------------------------------------------ end Notebook (v1.5)


    // ------------------------------------------------------- EPUB ink (INK-4)

    private fun epubRowId(key: String) = "ink:$bookId:e:${key.hashCode()}"

    private suspend fun loadEpubStrokesFromDb() {
        val rows = annotationDao.observeForBook(bookId).firstOrNull().orEmpty()
        val map = mutableMapOf<String, List<PdfInkStroke>>()
        for (row in rows) {
            if (row.type != "ink" || !row.id.startsWith("ink:$bookId:e:")) continue
            runCatching { inkJson.decodeFromString<List<PdfInkStroke>>(row.note) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { strokes ->
                    val href = Regex("\"href\":\"([^\"]+)\"").find(row.locatorJson)
                        ?.groupValues?.get(1)
                    if (href != null) {
                        val posMatch = Regex("\"position\":\"?([0-9]+)\"?")
                            .find(row.locatorJson)?.groupValues?.get(1) ?: "0"
                        map["$href#$posMatch"] = strokes
                    }
                }
        }
        _state.update { it.copy(epubStrokes = map) }
    }

    private suspend fun writeEpubStrokes(key: String, strokes: List<PdfInkStroke>) {
        val now = nowIso()
        val href = key.substringBefore('#')
        val pos = key.substringAfter('#', "0").filter { it.isDigit() }.ifBlank { "0" }
        if (strokes.isEmpty()) {
            annotationDao.tombstone(epubRowId(key), now, now)
        } else {
            val existing = annotationDao.getById(epubRowId(key))
            val locatorJson = "{\"href\":\"$href\",\"locations\":{\"position\":\"$pos\"}}"
            annotationDao.upsert(
                AnnotationEntity(
                    id = epubRowId(key),
                    bookId = bookId,
                    type = "ink",
                    locatorJson = locatorJson,
                    color = strokes.lastOrNull()?.color ?: "#FACC15",
                    note = inkJson.encodeToString(strokes),
                    excerpt = "",
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                    dirty = true,
                ),
            )
        }
        _state.update { it.copy(epubStrokes = it.epubStrokes + (key to strokes)) }
    }

    fun addEpubStroke(key: String, mode: String, points: List<Float>) {
        if (points.size < 4 || key.isBlank()) return
        when (_state.value.pdfInkTool) {
            PdfInkTool.NONE, PdfInkTool.ERASER -> return
            else -> {}
        }
        val stroke = PdfInkStroke(
            id = UUID.randomUUID().toString(),
            page = -1,
            // v1.4.3 fix: honor the selected color for the marker too.
            color = _state.value.pdfInkColor,
            width = if (mode == "highlighter") HIGHLIGHTER_WIDTH_DP else PEN_WIDTH_DP,
            points = decimateStrokePoints(points),
            mode = mode,
        )
        viewModelScope.launch {
            withInkLock(key) {
                writeEpubStrokes(key, (_state.value.epubStrokes[key].orEmpty() + stroke))
            }
        }
    }

    fun eraseEpubStroke(key: String, strokeId: String) {
        viewModelScope.launch {
            withInkLock(key) {
                val strokes = _state.value.epubStrokes[key].orEmpty()
                if (strokes.none { it.id == strokeId }) return@withInkLock
                writeEpubStrokes(key, strokes.filterNot { it.id == strokeId })
            }
        }
    }

    fun undoLastEpubStroke() {
        val key = _state.value.epubAnchor
        if (key.isBlank()) return
        viewModelScope.launch {
            withInkLock(key) {
                val strokes = _state.value.epubStrokes[key].orEmpty()
                if (strokes.isEmpty()) return@withInkLock
                writeEpubStrokes(key, strokes.dropLast(1))
            }
        }
    }

    fun setPdfInkTool(tool: PdfInkTool) {
        _state.update { st ->
            // Sensible per-tool defaults; explicit color picks still win afterwards.
            var color = st.pdfInkColor
            if (tool == PdfInkTool.HIGHLIGHTER && color == "#1C1B1F") color = "#FACC15"
            if (tool == PdfInkTool.PEN && color == "#FACC15") color = "#1C1B1F"
            st.copy(pdfInkTool = tool, pdfInkColor = color)
        }
    }

    fun setPdfInkColor(colorHex: String) {
        _state.update { it.copy(pdfInkColor = colorHex) }
    }

    // --------------------------------------------------------------------- EPUB highlights (ANN-5)

    /**
     * Mirror persisted highlight annotations onto the live navigator as Readium
     * Decorations. applyDecorations replaces everything under the tag, so this is
     * idempotent and handles adds/edits/deletes uniformly.
     */
    private fun refreshDecorations(highlights: List<AnnotationEntity>) {
        val nav = _state.value.engine?.navigator ?: return
        val decorable = nav as? DecorableNavigator ?: return
        decorationsJob?.cancel()
        decorationsJob = viewModelScope.launch {
            // Readium's applyDecorations touches its fragment's ViewModelStore, which
            // throws if the fragment isn't attached yet — wait for attach like
            // flushPendingSettings does.
            val frag = nav as? androidx.fragment.app.Fragment
            var waited = 0
            while (frag != null && !frag.isAdded && waited < 5_000) {
                delay(50)
                waited += 50
            }
            if (frag != null && !frag.isAdded) {
                Log.w(TAG, "navigator fragment never attached; skipping decorations")
                return@launch
            }
            val decorations = highlights.mapNotNull { ann ->
                val locator = Locators.fromJsonString(ann.locatorJson) ?: return@mapNotNull null
                val tint = runCatching { Color.parseColor(ann.color) }
                    .getOrDefault(0xFFFACC15.toInt())
                Decoration(
                    id = ann.id,
                    locator = locator,
                    style = Decoration.Style.Highlight(tint = tint, isActive = false),
                )
            }
            runCatching { decorable.applyDecorations(decorations, DECORATION_TAG) }
                .onFailure { Log.w(TAG, "applyDecorations failed", it) }
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
        Log.d(TAG, "observeChromeData starting on engine=${System.identityHashCode(engine)}")
        viewModelScope.launch {
            Log.d(TAG, "locator collector launching")
            engine.currentLocator.collect { locator ->
                val href = locator.href.toString().substringBefore('#')
                // Paginated reflowable EPUBs expose an integer screen index — the exact
                // anchor we need so ink lands on the same page every time (INK-4).
                val pos = locator.locations.position
                    ?.toString()
                    ?: locator.locations.progression?.let { ((it * 100_000).toInt()).toString() }
                    ?: "0"
                _state.update {
                    it.copy(
                        chapterTitle = locator.title.orEmpty(),
                        remainingPercent = locator.locations.totalProgression
                            ?.let { p -> (1.0 - p).toFloat() },
                        epubAnchor = "$href#$pos",
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
        val engine = _state.value.engine
        if (engine == null) {
            // PDF path: flush the current page immediately. The locator is built by
            // the same helper the bookmark path uses — these were two hand-written
            // copies of the same JSON, free to drift apart.
            val page = pdfCurrentPage
            val pdf = _state.value.pdfBook ?: return
            if (page < 0 || pdf.pageCount <= 0) return
            val progress = (page + 1).toDouble() / pdf.pageCount
            val json = pdfLocatorJson(page, pdf.pageCount)
            closeScope.launch {
                runCatching {
                    positionDao.upsert(
                        PositionEntity(
                            bookId = bookId,
                            locatorJson = json,
                            progressPercent = progress * 100.0,
                            updatedAt = nowIso(),
                            dirty = true,
                        ),
                    )
                }
            }
            return
        }
        val locator = engine.currentLocator.value
        closeScope.launch {
            runCatching { persistPosition(locator) }
        }
    }

    override fun onCleared() {
        // Release the audio and speech engines FIRST. onCleared used to close the
        // navigator and the PDF renderer but left TextToSpeech bound, so leaving the
        // reader mid-read-aloud kept the engine speaking the page forever and
        // leaked one engine per visit.
        runCatching { stopReadAloud() }
        runCatching { voiceAssistant.release() }
        runCatching { stopSelectionPolling() }

        // Full engine teardown — stopReadAloud() only stops speaking now, so the
        // TextToSpeech instance would otherwise outlive the reader.
        runCatching { readAloud.shutdown() }
        _state.value.pdfBook?.let { pdf -> runCatching { pdf.close() } }
        val engine = _state.value.engine
        if (engine != null) {
            val locator = engine.currentLocator.value
            // The position write and the engine teardown are launched on closeScope
            // and that scope is NOT cancelled here.
            //
            // It used to be, immediately after the launch. onCleared runs on the main
            // thread and closeScope is Main.immediate, so the block starts inline and
            // suspends at the first real suspension point — Room always dispatches a
            // suspend DAO call to its executor. The next statement, closeScope.cancel(),
            // then killed the write before it landed, and the following
            // withContext(Main.immediate) failed its own ensureActive() check, so
            // engine.close() never ran either. Net effect: the RD-12 save-on-close and
            // the navigator teardown were both dead code, and any exit that did not go
            // through notifyClosing() lost the last few seconds of progress.
            //
            // The scope is cancelled once the work finishes, so nothing leaks.
            closeScope.launch {
                runCatching { persistPosition(locator) }
                runCatching { persistPendingNotebook() }
                withContext(Dispatchers.Main.immediate) {
                    runCatching { engine.close() }
                }
            }.invokeOnCompletion { closeScope.cancel() }
        } else {
            // Nothing to await, so nothing to keep alive — except a note, which
            // does not need the navigator and must still land.
            closeScope.launch { runCatching { persistPendingNotebook() } }
                .invokeOnCompletion { closeScope.cancel() }
        }
        super.onCleared()
    }

    /**
     * Last-resort save for the notebook.
     *
     * The sheet's own autosave is debounced by 400 ms and only flushed by
     * closeNotebook(). Composition disposal and the back handler both call that,
     * so in normal navigation the note is safe — but any exit that tears the
     * ViewModel down without disposing the composition (a recents swipe, a
     * process kill after the sheet closed) cancelled viewModelScope with a debounced
     * write still pending, losing the last few seconds of typing.
     *
     * Runs on closeScope for the same reason the position write does: it is not
     * cancelled underneath the write. Re-persisting an already-saved note is an
     * idempotent upsert, so running it unconditionally is safe.
     */
    private suspend fun persistPendingNotebook() {
        notebookSaveJob?.cancel()
        val content = _notebook.value.content
        if (content.isEmpty) return
        persistNote(content)
    }

    // --------------------------------------------------------------------- chrome & panels

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }

    /** PDF night mode toggle (PRD PDF-NIGHT): persists through SettingsRepository. */
    fun togglePdfNightMode() {
        viewModelScope.launch {
            val next = !settingsRepository.settings.value.pdfNightMode
            settingsRepository.setPdfNightMode(next)
        }
    }

    fun setPdfWarmth(v: Int) {
        viewModelScope.launch { settingsRepository.setPdfWarmth(v) }
    }

    fun setChromeVisible(visible: Boolean) = _state.update { it.copy(chromeVisible = visible) }

    fun setPanel(panel: ReaderPanel) = _state.update { it.copy(panel = panel) }

    fun closePanel() = _state.update { it.copy(panel = ReaderPanel.NONE) }

    // --------------------------------------------------------------------- navigation

    fun turnPage(forward: Boolean) {
        val engine = _state.value.engine ?: return
        viewModelScope.launch {
            runCatching {
                val ok = if (forward) engine.next() else engine.previous()
                Log.d(TAG, "turnPage forward=$forward -> $ok")
            }.onFailure { Log.w(TAG, "page turn failed", it) }
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

    /**
     * A tap-zone or volume-key page turn for a PDF.
     *
     * The animated page-turn coordinator drives the Readium navigator view, which
     * only exists for EPUB. A PDF has no engine, so those turns used to reach a
     * ViewModel that returned immediately — and because the tap-zone layer sits on
     * top and consumes the tap, a configured NEXT/PREV zone swallowed the tap and
     * did nothing at all. The pager animates the same `pageAnimation` the reader
     * settings already carry, so the chosen animation is honoured here too.
     */
    fun turnPdfPage(forward: Boolean) {
        val pdf = _state.value.pdfBook ?: return
        val target = pdfTurnTarget(pdfCurrentPage, pdf.pageCount, forward) ?: return
        _pdfTurnRequest.value = target
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
        // PDF clears the engine (the toolkit ships no PDF navigator at 3.1.0), so a
        // null engine is not a reason to refuse the search — it is the PDF case.
        val engine = _state.value.engine
        val pdf = _state.value.pdfBook
        if (engine == null && pdf == null) return
        if (query.isBlank()) {
            searchJob?.cancel()
            _state.update { it.copy(searchGroups = emptyList(), searching = false) }
            return
        }
        _state.update { it.copy(searching = true, searchQuery = query) }
        // A search reads and parses every page or chapter, so a fast typist would
        // otherwise have several running at once — and whichever finished last would
        // win, showing hits for an earlier query than the one on screen.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val hits = runCatching {
                if (pdf != null) {
                    withContext(Dispatchers.IO) { pdf.search(query) }
                        .mapNotNull { hit ->
                            EngineSearchHit(
                                locator = Locators.forHref("/p${hit.page + 1}", hit.label)
                                    ?: return@mapNotNull null,
                                excerpt = hit.excerpt,
                                pdfPage = hit.page,
                            )
                        }
                } else {
                    engine?.search(query).orEmpty()
                }
            }.getOrDefault(emptyList())
            if (_state.value.searchQuery != query) return@launch
            _state.update { it.copy(searching = false, searchGroups = groupByChapter(hits)) }
        }
    }

    fun selectSearchHit(hit: EngineSearchHit) {
        // PDFs have no navigator to jump into; the pager is driven by a one-shot
        // request that the PDF surface consumes and clears.
        val page = hit.pdfPage
        if (page != null) {
            _pdfTurnRequest.value = page
        } else {
            jumpTo(hit.locator)
        }
        closePanel()
        // Flash the match box overlay for ~800ms (approximation of in-page highlight).
        _state.update { it.copy(flashTick = it.flashTick + 1) }
    }

    private fun groupByChapter(hits: List<EngineSearchHit>): List<SearchChapterGroup> =
        hits.groupBy { Locators.normalizeHref(it.locator.href.toString()).orEmpty() }
            .map { (href, groupHits) ->
                SearchChapterGroup(
                    // Table of contents first, then the resource's own title. Without
                    // the middle step a PDF hit grouped under "/p3" showed the reader
                    // a heading reading "p3" instead of "Page 3", and an EPUB hit in
                    // a resource missing from the TOC showed its raw file name.
                    chapterTitle = tocTitleFor(href)
                        ?: groupHits.firstOrNull()?.locator?.title
                        ?: href.substringAfterLast('/'),
                    href = href,
                    hits = groupHits,
                )
            }

    private fun tocTitleFor(href: String): String? =
        _state.value.tableOfContents
            .firstOrNull { Locators.normalizeHref(it.href.toString()) == href }
            ?.title

    // ------------------------------------------------------- AI page summaries (in-reader)

    fun dismissSummary() {
        summaryJob?.cancel()
        summaryJob = null
        _summaryState.value = SummaryUiState()
    }

    /** Sheet button: re-run the summary for the visible page, bypassing the cache. */
    fun regenerateSummary() {
        summarizeCurrentPage(forceRefresh = true)
    }

    /**
     * Summarizes the currently visible page. Cache-first unless [forceRefresh]; every
     * failure surfaces as a message in [summaryState] instead of crashing the reader.
     */
    fun summarizeCurrentPage(forceRefresh: Boolean = false) {
        if (_summaryState.value.loading) return
        summaryJob?.cancel()
        _summaryState.value = SummaryUiState(loading = true)

        summaryJob = viewModelScope.launch {
            val settingsNow = settingsRepository.settings.value
            // Loopback providers (USB-tunneled test servers) work with no transport up.
            val loopback = settingsNow.aiBaseUrl.contains("127.0.0.1") ||
                settingsNow.aiBaseUrl.contains("localhost")
            if (!loopback && !com.bookcon.app.core.Net.isOnline(appContext)) {
                _summaryState.value = SummaryUiState(
                    error = "AI summaries need internet. Connect and try again.",
                )
                return@launch
            }

            val apiKey = withContext(Dispatchers.IO) { aiKeyStore.get() }
            if (apiKey.isBlank()) {
                _summaryState.value = SummaryUiState(
                    error = "Add your API key in Settings → AI summary.",
                )
                return@launch
            }
            val settings = settingsNow

            // Cache key, cache lookup, and text extraction happen together on IO so a
            // page turn mid-flight can never pair new text with an old page's key.
            var pageKey = ""
            var cachedHit: String? = null
            val pageInfo: Pair<String, String>? = withContext(Dispatchers.IO) {
                pageKey = currentPageSummaryKey()
                if (!forceRefresh) cachedHit = summaryCache.get(bookId, pageKey)
                if (cachedHit != null) null else currentPageTextInfo()
            }

            if (cachedHit != null) {
                _summaryState.value = SummaryUiState(text = cachedHit, fromCache = true)
                return@launch
            }
            if (pageInfo == null || pageInfo.second.isBlank()) {
                _summaryState.value = SummaryUiState(error = "Couldn't read text on this page.")
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                summarizer.summarize(
                    provider = settings.aiProvider,
                    baseUrl = settings.aiBaseUrl,
                    apiKey = apiKey,
                    model = settings.aiModel.ifBlank {
                        Summarizer.defaultModel(settings.aiProvider)
                    },
                    bookTitle = _state.value.book?.title.orEmpty(),
                    pageLabel = pageInfo.first,
                    pageText = pageInfo.second,
                )
            }
            result.fold(
                onSuccess = { summary ->
                    // put() guards its own IO; no runCatching here so cancellation
                    // of this job still propagates instead of resurrecting the sheet.
                    withContext(Dispatchers.IO) { summaryCache.put(bookId, pageKey, summary) }
                    _summaryState.value = SummaryUiState(text = summary)
                },
                onFailure = { t ->
                    Log.w(TAG, "Page summarization failed", t)
                    _summaryState.value = SummaryUiState(
                        error = t.message ?: "Couldn't summarize this page.",
                    )
                },
            )
        }
    }

    /** Stable per-position cache key: "epub:<href>#<pos|prog>" or "pdf:<pageIndex>". */
    private fun currentPageSummaryKey(): String {
        val engine = _state.value.engine
        if (engine != null) {
            val locator = engine.currentLocator.value
            val href = locator.href.toString().substringBefore('#')
            val pos = locator.locations.position?.toString()
                ?: locator.locations.progression?.let { ((it * 100_000).toInt()).toString() }
                ?: "0"
            return "epub:$href#$pos"
        }
        return "pdf:$pdfCurrentPage"
    }

    /**
     * Uniform (pageLabel, plainText) for the visible page across both render paths:
     * Readium EPUB engines and our own PdfRenderer pager (no navigator).
     */
    private fun currentPageTextInfo(): Pair<String, String>? =
        _state.value.engine?.currentPageText()
            ?: _state.value.pdfBook?.currentPageText(pdfCurrentPage)

    // --------------------------------------------------------------------- bookmarks (RD-10)

    fun toggleBookmark() {
        // The PDF path has no Readium engine, so this used to return immediately and
        // the Bookmark button — which is rendered in the shared bottom bar — did
        // nothing at all, with no feedback. PDFs get the same locator shape the
        // position saver already writes, so the two are interchangeable.
        val engineLocator = _state.value.engine?.currentLocator?.value
        val json: String? = if (engineLocator != null) {
            Locators.toJsonString(engineLocator)
        } else {
            _state.value.pdfBook?.let { pdf ->
                val page = pdfCurrentPage
                if (page < 0 || pdf.pageCount <= 0) null
                else pdfLocatorJson(page, pdf.pageCount)
            }
        }
        val fallbackLabel = _state.value.chapterTitle
        if (json == null) {
            _ttsState.value = TtsState(error = "Couldn't bookmark this page.")
            return
        }
        viewModelScope.launch {
            val currentHref = Locators.normalizeHref(Locators.hrefOfJson(json))
            // Compare position as well as href. Matching on href alone meant that in
            // any chapter longer than one screen, pressing Bookmark on a new page
            // found the bookmark from an earlier page, treated it as "already
            // bookmarked", and deleted it instead of adding the new one — so a
            // bookmark could never be added anywhere except the chapter's first page.
            val currentProgression = engineLocator?.locations?.totalProgression
                ?: engineLocator?.locations?.progression
                ?: Locators.fromJsonString(json)?.locations?.totalProgression
                ?: Locators.fromJsonString(json)?.locations?.progression
            val twin = _state.value.bookmarks.lastOrNull { existing ->
                if (Locators.normalizeHref(Locators.hrefOfJson(existing.locatorJson)) != currentHref) {
                    return@lastOrNull false
                }
                val other = Locators.fromJsonString(existing.locatorJson)
                val otherProgression = other?.locations?.totalProgression
                    ?: other?.locations?.progression
                if (currentProgression == null || otherProgression == null) {
                    // No positional information on either side: fall back to href,
                    // which is the whole of the locator for pageless formats.
                    true
                } else {
                    kotlin.math.abs(currentProgression - otherProgression) < SAME_SPOT_EPSILON
                }
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
                        label = (engineLocator?.title ?: fallbackLabel).orEmpty(),
                        createdAt = now,
                        updatedAt = now,
                        dirty = true,
                    ),
                )
            }
        }
    }

    /**
     * The bookmark most recently removed by a swipe, as observable state.
     *
     * This was a plain `var` read during composition. Nothing recomposed when a
     * bookmark was swiped away, so the Undo button only appeared if some
     * unrelated recomposition happened to land afterwards — and it raced the
     * bookmarks Flow that the delete itself triggers, since the row was
     * tombstoned (emitting the Flow) before this was assigned. A StateFlow makes
     * the affordance appear whenever it becomes true.
     */
    private val _undoableBookmarkDelete = MutableStateFlow<BookmarkEntity?>(null)
    val undoableBookmarkDelete: StateFlow<BookmarkEntity?> = _undoableBookmarkDelete.asStateFlow()

    /**
     * Swiping a bookmark away used to delete it outright, with no confirmation and no
     * way back. It is now undoable: the row is tombstoned, but the entity is kept so
     * [undoLastBookmarkDelete] can put it back exactly as it was.
     */
    fun deleteBookmark(id: String) {
        val entity = _state.value.bookmarks.firstOrNull { it.id == id }
        viewModelScope.launch {
            val now = nowIso()
            bookmarkDao.tombstone(id, now, now)
            if (entity != null) {
                _undoableBookmarkDelete.value =
                    entity.copy(deletedAt = now, updatedAt = now, dirty = true)
            }
        }
    }

    /** Restores the last swiped-away bookmark, if it has not already been superseded. */
    fun undoLastBookmarkDelete() {
        val entity = _undoableBookmarkDelete.value ?: return
        _undoableBookmarkDelete.value = null
        viewModelScope.launch {
            bookmarkDao.upsert(entity.copy(deletedAt = null, updatedAt = nowIso(), dirty = true))
        }
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

    /**
     * Saves an edited note.
     *
     * It used to look the annotation up only in the in-memory list and `return` when it
     * was not there, so an edit made in the narrow window before the list finished
     * loading was silently dropped — the dialog closed and the change was lost with no
     * message. It now falls back to the database, and only gives up if the row really
     * does not exist.
     */
    fun updateNote(id: String, note: String) {
        val cached = _state.value.annotations.firstOrNull { it.id == id }
        viewModelScope.launch {
            val current = cached ?: runCatching { annotationDao.getById(id) }.getOrNull()
            if (current == null) {
                Log.w(TAG, "updateNote: annotation $id is not in the database; nothing saved")
                return@launch
            }
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
        jumpToLocatorJson(item.locatorJson)
    }

    /**
     * Jumps to a stored locator, from a highlight or a bookmark.
     *
     * PDFs have no Readium engine to `go()` into — openPdf clears it, because the
     * toolkit ships no PDF navigator at 3.1.0 — so routing every jump through
     * [jumpTo] meant tapping a highlight in a PDF did nothing at all, with no error
     * and no movement. The page is read out of the `/pN` href the PDF locator format
     * already uses and handed to the pager instead.
     */
    private fun jumpToLocatorJson(locatorJson: String) {
        val pdf = _state.value.pdfBook
        if (pdf != null) {
            val target = pdfPageFromLocator(locatorJson, pdf.pageCount)
            if (target == null) {
                Log.w(TAG, "PDF locator does not name a page in range: $locatorJson")
                return
            }
            _pdfTurnRequest.value = target
            return
        }
        jumpTo(Locators.fromJsonString(locatorJson) ?: return)
    }

    // --------------------------------------------------------------------- settings setters

    fun setPaginationMode(mode: String) = updateSettings { it.copy(readerPaginationMode = mode) }

    fun setPageTurnAnimation(animation: String) =
        updateSettings { it.copy(readerPageTurnAnimation = animation) }

    /** Quick chrome toggle: flip between Slide and Page turn (v1.4 demo convenience). */
    fun togglePageTurnAnimation() = updateSettings {
        it.copy(
            readerPageTurnAnimation = if (it.readerPageTurnAnimation == "page_turn") "slide" else "page_turn",
        )
    }

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
                // Ink rows are internal drawing data, not list-visible annotations.
                _state.update { it.copy(annotations = items.filter { a -> a.type != "ink" }) }
                refreshDecorations(items.filter { it.type == "highlight" })
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
        marginHorizontalDp = readerMarginsHorizontal,
    )

    companion object {
        private const val TAG = "ReaderViewModel"
        private const val POSITION_SAVE_DEBOUNCE_MS = 3_000L // RD-12 upper bound
        private const val MAX_EXCERPT_CHARS = 512
        private const val DECORATION_TAG = "highlights"
        /** Roughly a screenful of prose per utterance; keeps one request bounded. */
        const val MAX_NARRATION_CHARS = 2200
        /**
         * Two positions closer than this are the same bookmark. A page turn moves
         * progression far more than this; the same page read twice moves it not at all.
         */
        const val SAME_SPOT_EPSILON = 0.002
        const val PEN_WIDTH_DP = 3f
        const val HIGHLIGHTER_WIDTH_DP = 18f

        fun nowIso(): String = OffsetDateTime.now(ZoneOffset.UTC).toString()
    }
}
