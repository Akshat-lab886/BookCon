package com.bookcon.app.reader

import android.content.Context
import android.util.Log
import androidx.fragment.app.Fragment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONException
import org.json.JSONObject
import org.readium.r2.navigator.Navigator
import org.readium.r2.navigator.OverflowableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.TextAlign
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import kotlin.coroutines.resume
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.format.FormatHints
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * Thin seam over the Readium toolkit (TRD risk mitigation): every direct Readium call lives in
 * this file so a future toolkit upgrade only touches one place. The UI/ViewModel layers speak
 * [ReaderEngine] + plain data classes only.
 *
 * Pinned against **readium-kotlin-toolkit 3.1.0** (verified against the AAR class signatures):
 * publications open through [PublicationOpener] (the old `Streamer` class is gone in 3.x), and
 * navigator fragments are created through [EpubNavigatorFactory.createFragmentFactory].
 */

/** Thrown by [ReaderEngineFactory.open] when a publication cannot be opened or rendered. */
class ReaderOpenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * UI-agnostic snapshot of reader presentation settings (PRD RD-2…RD-7, RD-14).
 * Built from [com.bookcon.app.core.AppSettings]; engines map it onto navigator preferences.
 */
data class EngineSettings(
    val fontFamily: String? = null,        // CSS font-family ("serif", "sans-serif", …)
    val fontSizeSp: Float? = null,
    val fontWeight: Float? = null,
    val lineHeight: Float? = null,
    val paragraphSpacing: Float? = null,   // no direct toolkit equivalent (mapped to wordSpacing)
    val letterSpacing: Float? = null,
    val textAlign: String? = null,         // start | center | end | justify
    val publisherDefaults: Boolean? = null,// RD-6 (not exposed by toolkit preferences; reserved)
    val paginated: Boolean? = null,        // true → paginated, false → scroll
    val theme: String? = null,             // light | sepia | dark | black
    val pageMargins: Float? = null,        // reserved (toolkit pageMargins pref is a Boolean)
)

/** Text currently selected inside the reading surface (ANN-1). */
data class EngineSelection(
    val locator: Locator,
    val text: String,
)

/** One search hit (RD-11); grouping by chapter happens in the ViewModel via locator.href. */
data class EngineSearchHit(
    val locator: Locator,
    val excerpt: String,
)

/**
 * Format-agnostic handle over an open publication.
 *
 * Must be created and closed on the Android **main thread** (navigator fragments are UI objects).
 */
interface ReaderEngine {

    /** Underlying Readium navigator; the screen embeds it as a Fragment. */
    val navigator: Navigator

    /** Opened publication (null for adapters that cannot expose it). */
    val publication: Publication?

    /** Live position locator. */
    val currentLocator: StateFlow<Locator>

    /** Current in-publication text selection, or null (ANN-1/ANN-2). */
    suspend fun currentSelection(): EngineSelection?

    suspend fun go(locator: Locator, animated: Boolean = false): Boolean

    suspend fun next(): Boolean

    suspend fun previous(): Boolean

    /**
     * Full-text search (RD-11). Default returns no hits until the toolkit Search API is wired —
     * see [EpubReaderEngine.search].
     */
    suspend fun search(query: String): List<EngineSearchHit> = emptyList()

    /** 0-based index of the visible fixed-layout page (PDF), when the engine can know it. */
    val currentPageIndex: Int?
        get() = null

    /** Returns (pageLabel, plainText) for the currently visible page, or null. */
    fun currentPageText(): Pair<String, String>? = null

    fun applySettings(settings: EngineSettings) {}

    /**
     * Applies settings captured while the navigator fragment was not yet attached.
     * ReaderScreen calls this right after committing the fragment transaction;
     * default is a no-op for engines without deferral.
     */
    fun flushPendingSettings() {}

    fun clearSelection() {}

    /** Optional JS bridge into the reader webview (EPUB). Null for non-web engines. */
    var webViewEvaluator: ((js: String, onResult: (String?) -> Unit) -> Unit)?

    /** Optional real-touch swipe injector (EPUB). Null for non-web engines. */
    var nativeSwipeTurn: ((forward: Boolean) -> Boolean)?

    /** Releases the navigator fragment and any engine resources. Idempotent; main thread. */
    fun close()

    companion object {
        const val TAG: String = "ReaderEngine"

        /// App font-size setting is stored in sp; Readium EPUB `fontSize` is a relative multiplier.
        const val BASE_FONT_SIZE_SP: Float = 18f
    }
}


/** A single ink stroke (pen/highlighter/eraser) on a PDF page. */
@Serializable
data class PdfInkStroke(
    val id: String = UUID.randomUUID().toString(),
    val page: Int,
    val color: String,
    val width: Float,
    val points: List<Float>, // flattened [x1, y1, x2, y2, ...] normalized 0..1
    val mode: String, // "pen", "highlighter", "eraser"
    val createdAt: String = java.time.Instant.now().toString(),
)

/** Tool modes for PDF ink overlay. */
enum class PdfInkTool {
    NONE,
    PEN,
    HIGHLIGHTER,
    ERASER,
}

internal val inkJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * Shared plumbing for engines backed by any Readium [Navigator] fragment
 * ([EpubNavigatorFragment], and later Pdf/Image navigators).
 */
abstract class CommonNavigatorEngine(
    final override val navigator: Navigator,
    protected val engineScope: CoroutineScope,
) : ReaderEngine {

    final override val currentLocator: StateFlow<Locator> = navigator.currentLocator

    /** Set by the UI layer to run JS inside the reader webview (see EpubReaderEngine). */
    override var webViewEvaluator: ((js: String, onResult: (String?) -> Unit) -> Unit)? = null

    /** Set by UI: performs a real touch-swipe inside the reader webview. */
    override var nativeSwipeTurn: ((forward: Boolean) -> Boolean)? = null

    protected suspend fun tryNativeSwipe(forward: Boolean): Boolean? {
        val swipe = nativeSwipeTurn ?: return null
        val before = navigator.currentLocator.value
        val dispatched = swipe(forward)
        if (!dispatched) return null
        // Success = locator actually moved within ~1.6s of the gesture.
        // One retry: the first gesture sometimes only wakes the pager.
        repeat(2) { attempt ->
            repeat(if (attempt == 0) 24 else 10) {
                kotlinx.coroutines.delay(50)
                val now = navigator.currentLocator.value
                val hrefMoved = now.href != before.href
                val progMoved =
                    (now.locations.progression ?: -1.0) != (before.locations.progression ?: -1.0)
                val posMoved = now.locations.position != before.locations.position
                if (hrefMoved || progMoved || posMoved) return true
            }
            if (attempt == 0) swipe(forward)
        }
        return false
    }

    override val publication: Publication? = null

    /** Page-flip API lives on [OverflowableNavigator] (EPUB today; PDF/image later). */
    private val overflowable: OverflowableNavigator? = navigator as? OverflowableNavigator

    override suspend fun currentSelection(): EngineSelection? = null

    /**
     * 0-based index of the currently visible fixed-layout page, when the engine can know it.
     * PDFs render outside the navigator in this app (PdfBook/PdfPager path), so engines
     * ship the null default for now.
     */
    override val currentPageIndex: Int? = null

    /** Returns (pageLabel, plainText) for the currently visible page, or null. */
    override fun currentPageText(): Pair<String, String>? = null

    // Readium 3.1.0: Navigator.go(Locator, animated): Boolean and
    // OverflowableNavigator.goForward/goBackward(animated): Boolean — plain, non-suspend.
    override suspend fun go(locator: Locator, animated: Boolean): Boolean = try {
        navigator.go(locator, animated)
    } catch (e: Exception) {
        Log.w(ReaderEngine.TAG, "go(locator) failed", e)
        false
    }

    override suspend fun next(): Boolean = try {
        overflowable?.goForward(true) ?: false
    } catch (e: Exception) {
        Log.w(ReaderEngine.TAG, "goForward failed", e)
        false
    }

    override suspend fun previous(): Boolean = try {
        overflowable?.goBackward(true) ?: false
    } catch (e: Exception) {
        Log.w(ReaderEngine.TAG, "goBackward failed", e)
        false
    }

    override fun close() {
        try {
            val fragment = navigator as? Fragment
            if (fragment?.isAdded == true) {
                fragment.parentFragmentManager
                    .beginTransaction()
                    .remove(fragment)
                    .commitAllowingStateLoss()
            }
        } catch (_: IllegalStateException) {
            Log.w(ReaderEngine.TAG, "Navigator fragment already detached; skipped removal")
        }
        engineScope.cancel()
    }
}

/** EPUB engine over [EpubNavigatorFragment]. */
class EpubReaderEngine internal constructor(
    private val fragment: EpubNavigatorFragment,
    private val openedPublication: Publication,
    engineScope: CoroutineScope,
) : CommonNavigatorEngine(fragment, engineScope) {

    // NavigatorFragment.publication is protected in 3.x; the factory passes the opened
    // publication alongside the fragment instead of reaching into the fragment.
    override val publication: Publication = openedPublication

    // ---------------------------------------------------------------- page turns
    // Readium's OverflowableNavigator paging proved unreliable inside our Compose
    // embedding (returns true without moving). Instead we navigate through the
    // publication's per-screen POSITIONS list: every entry is a real Locator that
    // navigator.go() resolves exactly — the same mechanism as restore-position.

    private var positionsCache: List<Locator>? = null

    private suspend fun screenPositions(): List<Locator> {
        positionsCache?.let { return it }
        val computed: List<Locator> =
            runCatching {
                openedPublication.positions()
            }.getOrDefault(emptyList())
        if (computed.isNotEmpty()) positionsCache = computed
        return computed
    }

    private suspend fun turnByScreen(forward: Boolean): Boolean? {
        val all = screenPositions()
        if (all.isEmpty()) return null
        val cur = currentLocator.value
        val key = { l: Locator -> l.href.toString().substringBefore('#') }
        val curKey = key(cur)
        val prog = cur.locations.progression ?: 0.0

        val inResource = all.withIndex().filter { key(it.value) == curKey }
        if (inResource.isEmpty()) return null

        // Half-a-screen tolerance: the live locator's continuous progression can sit
        // anywhere inside the current screen; comparing with 1e-9 could select the
        // ALREADY-VISIBLE position and loop forever reporting success.
        val eps = 0.5 / inResource.size.coerceAtLeast(1)

        val target: Locator? = if (forward) {
            inResource.firstOrNull { (it.value.locations.progression ?: 0.0) > prog + eps }
                ?.value
                // At end of resource → first position of the next reading-order item.
                ?: nextResourceFirstPosition(all, curKey)
        } else {
            inResource.lastOrNull { (it.value.locations.progression ?: 0.0) < prog - eps }
                ?.value
                ?: previousResourceLastPosition(all, curKey)
        }
        val loc = target ?: return null
        return try {
            val ok = navigator.go(loc, false)
            Log.d(
                ReaderEngine.TAG,
                "turn fwd=$forward prog=$prog -> ${loc.href} ${loc.locations.progression} ok=$ok",
            )
            ok
        } catch (e: Exception) {
            Log.w(ReaderEngine.TAG, "turnByScreen go failed", e)
            false
        }
    }

    private fun nextResourceFirstPosition(all: List<Locator>, curHrefKey: String): Locator? {
        val order = openedPublication.readingOrder
        val idx = order.indexOfFirst { it.href.toString().substringBefore('#') == curHrefKey }
        if (idx == -1 || idx + 1 >= order.size) return null
        val nextKey = order[idx + 1].href.toString().substringBefore('#')
        return all.firstOrNull { it.href.toString().substringBefore('#') == nextKey }
    }

    private fun previousResourceLastPosition(all: List<Locator>, curHrefKey: String): Locator? {
        val order = openedPublication.readingOrder
        val idx = order.indexOfFirst { it.href.toString().substringBefore('#') == curHrefKey }
        if (idx <= 0) return null
        val prevKey = order[idx - 1].href.toString().substringBefore('#')
        return all.lastOrNull { it.href.toString().substringBefore('#') == prevKey }
    }

    private fun jsTurnJs(forward: Boolean): String {
        // Paginated reflowable lays content out in columns exactly one viewport
        // wide. We address them by ABSOLUTE index — incremental deltas accumulate
        // rounding error that makes the paginator snap back a column (the
        // "previous page shows up" bug).
        return "(function(){var d=document.scrollingElement||document.documentElement;" +
            "var vpW=window.innerWidth;" +
            "var maxL=d.scrollWidth-vpW;if(maxL<=10)return 'edge';" +
            "var cur=Math.round(d.scrollLeft/vpW);" +
            "var n=cur" + (if (forward) "+1" else "-1") + ";" +
            "if(n<0||(n*vpW)>maxL+1)return 'edge';" +
            "var before=d.scrollLeft;d.scrollLeft=n*vpW;" +
            "return (d.scrollLeft!==before)?('moved:'+before+'->'+d.scrollLeft):'stuck';})()"
    }

    /** Snaps the webview onto the nearest exact column boundary, healing drift. */
    private fun jsAlignJs(): String =
        "(function(){var d=document.scrollingElement||document.documentElement;" +
            "var vpW=window.innerWidth;if(d.scrollWidth-vpW<=10)return 'flat';" +
            "var s=Math.round(d.scrollLeft/vpW)*vpW;" +
            "if(s!==d.scrollLeft){d.scrollLeft=s;return 'aligned:'+d.scrollLeft;}" +
            "return 'ok';})()"

    private suspend fun tryJsTurn(forward: Boolean): Boolean? {
        val evaluator = webViewEvaluator ?: return null
        val result = kotlinx.coroutines.withTimeoutOrNull(1500) {
            kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                evaluator(jsTurnJs(forward)) { res -> cont.resume(res) }
            }
        }
        Log.d(ReaderEngine.TAG, "jsTurn forward=$forward -> $result")
        val moved = when {
            result == null -> null
            result.contains("moved") -> true
            // 'edge' or 'stuck' → let the caller try exact-position navigation.
            else -> false
        }
        if (moved == true) {
            kotlinx.coroutines.delay(120)
            evaluator(jsAlignJs()) { }
        }
        return moved
    }

    override suspend fun next(): Boolean {
        if (tryJsTurn(true) == true) return true
        if (tryNativeSwipe(true) == true) return true
        return turnByScreen(true) ?: super.next()
    }

    override suspend fun previous(): Boolean {
        if (tryJsTurn(false) == true) return true
        if (tryNativeSwipe(false) == true) return true
        return turnByScreen(false) ?: super.previous()
    }

    /** Settings captured before the fragment attached; flushed by [flushPendingSettings]. */
    private var pendingPreferences: EpubPreferences? = null

    override suspend fun currentSelection(): EngineSelection? = try {
        fragment.currentSelection()?.let { sel ->
            EngineSelection(locator = sel.locator, text = sel.locator.text.highlight.orEmpty())
        }
    } catch (e: Exception) {
        Log.w(ReaderEngine.TAG, "currentSelection failed", e)
        null
    }

    // ------------------------------------------------------------------ AI page text

    /**
     * Plain text of the visible EPUB screen, extracted from the current reading-order
     * resource. Serves in-reader AI page summaries; null when nothing readable is open.
     */
    override fun currentPageText(): Pair<String, String>? {
        val locator = currentLocator.value
        val hrefKey = locator.href.toString().substringBefore('#')
        val link = openedPublication.readingOrder.firstOrNull {
            it.href.toString().substringBefore('#') == hrefKey
        }
        val resource = link?.let { openedPublication.get(it) } ?: return null

        // The Resource API is suspend-only; summarizeCurrentPage calls this off the main
        // thread (Dispatchers.IO), so bridge with runBlocking here.
        val raw = kotlinx.coroutines.runBlocking {
            runCatching {
                val length = resource.length().getOrNull() ?: return@runCatching ByteArray(0)
                if (length <= 0L) {
                    ByteArray(0)
                } else {
                    resource.read(0 until length).getOrNull() ?: ByteArray(0)
                }
            }.getOrDefault(ByteArray(0))
        }
        if (raw.isEmpty()) return null

        // XHTML → rough plain text: strip tags, decode the common entities, collapse space.
        val text = String(raw, Charsets.UTF_8)
            .let { Regex("<[^>]*>").replace(it, " ") }
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty()) return null

        // Long resources feed only a window around the visible progression so prompts stay bounded.
        val window = if (text.length > 8000) {
            val progression = locator.locations.progression
            if (progression != null && progression in 0.0..1.0) {
                val start = ((text.length * progression).toInt() - 2000).coerceAtLeast(0)
                text.substring(start, minOf(text.length, start + 8000))
            } else {
                text.take(8000)
            }
        } else {
            text
        }

        val label = locator.title ?: hrefKey.substringAfterLast('/')
        return label to window
    }

    override suspend fun search(query: String): List<EngineSearchHit> {
        // TODO(readium-api): wire the toolkit search service here (org.readium.r2.shared.search
        // Searchable on the publication services). The UI already renders grouped hits.
        return emptyList()
    }

    override fun clearSelection() {
        try {
            fragment.clearSelection()
        } catch (e: Exception) {
            Log.w(ReaderEngine.TAG, "clearSelection failed", e)
        }
    }

    override fun applySettings(settings: EngineSettings) {
        // EpubPreferences (3.1.0) exposes: color, columnCount, fontFamily, fontSize, fontWeight,
        // hyphens, imageFilter, language, letterSpacing, ligatures, lineHeight, marginHorizontal,
        // marginBottom, marginVertical, pageMargins(Boolean), readingProgression, scroll, spread,
        // textAlign, textColor, textNormalization, theme, typeScale, wordSpacing.
        // Readium 3.1.0 validates EpubPreferences in its constructor (require()):
        // fontSize >= 0, fontWeight in 0..2.5 as a MULTIPLIER of the base weight
        // (not CSS 100-900!), letterSpacing/wordSpacing/pageMargins >= 0. Sanitize
        // every value so a stored CSS-style setting can never throw here.
        val prefs = EpubPreferences(
            fontFamily = settings.fontFamily?.let { FontFamily(it) },
            fontSize = settings.fontSizeSp?.let { (it / ReaderEngine.BASE_FONT_SIZE_SP).toDouble().coerceAtLeast(0.0) },
            fontWeight = settings.fontWeight?.let { (it.toDouble() / 400.0).coerceIn(0.5, 2.5) },
            lineHeight = settings.lineHeight?.toDouble()?.coerceAtLeast(1.0),
            letterSpacing = settings.letterSpacing?.toDouble()?.coerceAtLeast(0.0),
            wordSpacing = settings.paragraphSpacing?.toDouble()?.coerceAtLeast(0.0),
            scroll = settings.paginated?.not(),
            textAlign = settings.textAlign?.let { readiumTextAlign(it) },
            theme = settings.theme?.let { readiumTheme(it) },
        )
        // submitPreferences() touches the navigator's ViewModel, which throws
        // "Can't access ViewModels from detached fragment" when called between
        // instantiate() and the fragment transaction — defer until attached.
        if (fragment.isAdded) {
            fragment.submitPreferences(prefs)
        } else {
            pendingPreferences = prefs
        }
    }

    override fun flushPendingSettings() {
        if (pendingPreferences == null) return
        engineScope.launch {
            while (!fragment.isAdded && isActive) delay(50)
            pendingPreferences?.let { prefs ->
                runCatching { fragment.submitPreferences(prefs) }
                    .onFailure { Log.w(ReaderEngine.TAG, "Deferred applySettings failed", it) }
            }
            pendingPreferences = null
        }
    }

    private fun readiumTheme(name: String): Theme? = when (name.lowercase()) {
        "light" -> Theme.LIGHT
        "sepia" -> Theme.SEPIA
        "dark" -> Theme.DARK
        // No dedicated black theme in the toolkit; dark keeps the closest rendering while the
        // Compose chrome already paints pure black around the webview. TODO: consider injecting
        // custom CSS (background #000) for true AMOLED black.
        "black" -> Theme.DARK
        else -> null
    }

    private fun readiumTextAlign(name: String): TextAlign? = when (name.lowercase()) {
        "start" -> TextAlign.START
        "center" -> TextAlign.CENTER
        "end" -> TextAlign.END
        "justify" -> TextAlign.JUSTIFY
        else -> null
    }
}

/**
 * PDF engine adapter. The toolkit artifact providing PdfNavigatorFragment
 * (`org.readium.kotlin-toolkit:readium-navigator-pdf`, plus its pdfium dependency) does not exist
 * at toolkit 3.1.0 on Maven Central (verified: 404), so nothing can construct it today. Once a
 * compatible artifact is published, add it to libs.versions.toml and mirror [EpubReaderEngine].
 */
class PdfReaderEngine internal constructor(
    navigator: Navigator,
    engineScope: CoroutineScope,
) : CommonNavigatorEngine(navigator, engineScope)

/**
 * Comic (CBZ/CBR) engine adapter. In the Kotlin toolkit CBZ publications are rendered by
 * ImageNavigatorFragment (`readium-navigator-image` artifact — also absent at 3.1.0).
 * Once available, construct exactly like [PdfReaderEngine].
 */
class ComicReaderEngine internal constructor(
    navigator: Navigator,
    engineScope: CoroutineScope,
) : CommonNavigatorEngine(navigator, engineScope)

/** Builds [ReaderEngine]s. All Readium imports used by the app funnel through here. */
object ReaderEngineFactory {

    /**
     * Parses [publicationFile] and creates the matching navigator-backed engine.
     * MUST be invoked on the Android main thread (fragment creation requirement).
     * Never touches the network — fully offline once the local file exists.
     */
    suspend fun open(
        context: Context,
        publicationFile: File,
        initialLocator: Locator?,
        settings: EngineSettings? = null,
    ): ReaderEngine {
        require(publicationFile.exists()) { "Publication file not found: $publicationFile" }
        val appContext = context.applicationContext

        // Readium 3.x opening pipeline: DefaultPublicationParser → PublicationOpener → Try<Publication>.
        val retriever = AssetRetriever(appContext.contentResolver, DefaultHttpClient())
        val asset = try {
            retriever.retrieve(publicationFile, FormatHints()).getOrNull()
        } catch (e: Exception) {
            throw ReaderOpenException("Failed to read ${publicationFile.name}: ${e.message}", e)
        } ?: throw ReaderOpenException("Unrecognized file format: ${publicationFile.name}")
        val parser = DefaultPublicationParser(
            appContext,
            DefaultHttpClient(),
            retriever,
            null, // PdfDocumentFactory — PDF parsing unsupported until navigator-pdf is available
            emptyList(),
        )
        val opener = PublicationOpener(parser, emptyList())
        val publication = try {
            opener.open(asset, null, false).getOrNull()
        } catch (e: Exception) {
            throw ReaderOpenException("Failed to open ${publicationFile.name}: ${e.message}", e)
        } ?: throw ReaderOpenException("Unsupported or corrupted file: ${publicationFile.name}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        return when {
            publication.conformsTo(Publication.Profile.EPUB) -> {
                val fragment = createEpubFragment(appContext, publication, initialLocator)
                EpubReaderEngine(fragment, publication, scope).apply {
                    settings?.let { applySettings(it) }
                }
            }

            publication.conformsTo(Publication.Profile.PDF) ->
                throw ReaderOpenException(
                    "PDF reading is not available in this build (readium-navigator-pdf artifact " +
                        "does not exist for toolkit 3.1.0).",
                )

            else ->
                throw ReaderOpenException(
                    "${publicationFile.extension.ifBlank { "cbz" }} reading is not available in " +
                        "this build (readium-navigator-image artifact does not exist for 3.1.0).",
                )
        }
    }

    /**
     * Creates an [EpubNavigatorFragment] through [EpubNavigatorFactory] — the 3.x replacement for
     * the removed `EpubNavigatorFragment.createInstance(...)` companion factory.
     */
    private fun createEpubFragment(
        context: Context,
        publication: Publication,
        initialLocator: Locator?,
    ): EpubNavigatorFragment {
        val navFactory = EpubNavigatorFactory(publication, EpubNavigatorFactory.Configuration())
        // createFragmentFactory's 2nd parameter is the READING ORDER override — passing an
        // empty list there made EpubNavigatorFragment crash with
        // NoSuchElementException ("List is empty") on readingOrder.first(). Omit it so
        // Readium uses publication.readingOrder (Kotlin default argument).
        val openLocator: Locator = initialLocator
            ?: fallbackLocator(publication)
            ?: throw ReaderOpenException("Publication has no readable content")
        val fragmentFactory = navFactory.createFragmentFactory(
            initialLocator = openLocator,
            initialPreferences = EpubPreferences(scroll = false),
            paginationListener = object : EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(pageIndex: Int, pageCount: Int, locator: Locator) = Unit
                override fun onPageLoaded() = Unit
            },
        )
        return fragmentFactory.instantiate(
            context.classLoader,
            EpubNavigatorFragment::class.java.name,
        ) as EpubNavigatorFragment
    }

    /** First reading-order link as a locator, for opens without a stored position. */
    private fun fallbackLocator(publication: Publication): Locator? =
        publication.manifest.readingOrder.firstOrNull()?.let { link: Link ->
            // Same helper EpubNavigatorFragment uses internally; Locator.fromJSON
            // silently returned null for plain relative hrefs like "c1.xhtml".
            publication.locatorFromLink(link)
        }
}

/**
 * Locator ⇄ JSON helpers (TRD §2.3: Locator JSON is the cross-platform payload stored in Room).
 *
 * Readium Locators serialize through org.json (`Locator.toJSON()` / `Locator.fromJSON(JSONObject)`),
 * while the rest of the app speaks kotlinx.serialization — these helpers bridge both, always
 * falling back to null instead of throwing so persistence bugs can never crash the reader.
 */
object Locators {

    private val json = Json { ignoreUnknownKeys = true }

    fun toJsonString(locator: Locator): String? = try {
        locator.toJSON().toString()
    } catch (t: Throwable) {
        Log.w(ReaderEngine.TAG, "Locator serialization failed", t)
        null
    }

    fun fromJsonString(raw: String?): Locator? = try {
        raw?.takeIf { it.isNotBlank() }?.let { Locator.fromJSON(JSONObject(it)) }
    } catch (t: Throwable) {
        Log.w(ReaderEngine.TAG, "Locator deserialization failed", t)
        null
    }

    /** kotlinx JsonObject view of a locator (handy for server payloads / debugging). */
    fun toJsonObject(locator: Locator): JsonObject? = try {
        json.parseToJsonElement(requireNotNull(toJsonString(locator))) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: JSONException) {
        null
    }

    /**
     * Minimal locator for href jumps (TOC entries, chapter groups) built through the stable
     * JSONObject round-trip so we do not depend on the Href-vs-String href type migration.
     */
    fun forHref(href: String, title: String? = null, totalProgression: Double? = null): Locator? = try {
        val locations = JSONObject()
        totalProgression?.let { locations.put("totalProgression", it) }
        val obj = JSONObject()
            .put("href", href)
        title?.let { obj.put("title", it) }
        obj.put("locations", locations)
        Locator.fromJSON(obj)
    } catch (_: JSONException) {
        null
    }

    /** Extracts just the href of a serialized locator without building a full Locator. */
    fun hrefOfJson(raw: String?): String? = try {
        raw?.takeIf { it.isNotBlank() }
            ?.let { JSONObject(it).optString("href") }
            ?.ifBlank { null }
    } catch (_: JSONException) {
        null
    }

    /** Convenience for progress bookkeeping. */
    fun progressionOf(locator: Locator): Double? = locator.locations.totalProgression

    /** Normalizes hrefs for equality checks (strip fragments/query, case-insensitive tail). */
    fun normalizeHref(href: String?): String? = href
        ?.substringBefore('#')
        ?.substringBefore('?')
        ?.trim()
        ?.lowercase()

    /** Positions helper kept next to serialization for symmetry. */
    fun withProgression(locator: Locator, progression: Double): Locator = locator.copy(
        locations = Locator.Locations(progression = progression),
    )
}
