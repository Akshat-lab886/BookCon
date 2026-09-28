package com.bookcon.app.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.bookcon.app.reader.findTextMatches
import java.io.Closeable
import java.io.File

/** One in-book search hit on a PDF page. */
internal data class PdfSearchHit(val page: Int, val label: String, val excerpt: String)

/**
 * Walks pages looking for [query], using [pageText] to pull the text of each.
 *
 * Extracted from [PdfBook.search] and parameterised on the text source so the
 * walking rules can be tested without a real PDF: [pageText] takes a 0-based page
 * index and returns (label, text) or null when the page has no extractable text.
 *
 * Page text extraction parses the whole document, so the walk is bounded on both
 * ends — a ceiling on hits per page as well as overall, or a single dense page of a
 * legal contract would consume the entire result budget.
 */
internal fun searchPdfPages(
    query: String,
    pageCount: Int,
    pageText: (Int) -> Pair<String, String>?,
    maxTotal: Int = 200,
    maxPerPage: Int = 20,
): List<PdfSearchHit> {
    val needle = query.trim()
    if (needle.isEmpty() || pageCount <= 0) return emptyList()
    val hits = mutableListOf<PdfSearchHit>()
    for (page in 0 until pageCount) {
        if (hits.size >= maxTotal) break
        val (label, text) = pageText(page) ?: continue
        if (text.isBlank()) continue
        for (match in findTextMatches(needle, text, maxMatches = maxPerPage)) {
            hits += PdfSearchHit(page, label, match.excerpt)
            if (hits.size >= maxTotal) break
        }
    }
    return hits
}

/**
 * Minimal PDF document model built on Android's built-in [PdfRenderer] (API 21+).
 *
 * Readium kotlin-toolkit 3.1.0 ships no PDF navigator (readium-navigator-pdf does not
 * exist for this line), so PDFs are rendered page-by-page into bitmaps and paged by
 * our own Compose pager in PdfPager. PdfRenderer is NOT thread-safe — every access is
 * serialized through [lock].
 */
class PdfBook private constructor(
    private val file: File,
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    val pageCount: Int = renderer.pageCount

    private val lock = Any()

    /** Aspect ratio (height/width) of a page, for placeholder sizing before render. */
    fun aspectRatio(index: Int): Float = synchronized(lock) {
        renderer.openPage(index).use { page ->
            page.height.toFloat() / page.width.toFloat()
        }
    }

    /** Renders page [index] (0-based) scaled to [targetWidth] px. Call off the main thread. */
    fun renderPage(index: Int, targetWidth: Int): Bitmap = synchronized(lock) {
        renderer.openPage(index).use { page ->
            val width = targetWidth.coerceAtLeast(1)
            val height = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    /**
     * Page text, keyed by 0-based page index. The value is the trimmed text, or
     * "" when the page has none — LruCache cannot hold null, so emptiness is the
     * "nothing extractable" marker and callers still get a null result.
     *
     * Extracting a page means parsing the *entire* PDF through PDFBox, which is far
     * too slow to repeat per request: the voice assistant asks for the current
     * page's text on every turn of a conversation, so a user chatting while reading
     * a long PDF re-parsed the whole book over and over. The assistant is asked
     * about the same page repeatedly, so a tiny cache turns a stall per turn into
     * a cost per page visited.
     *
     * No PDDocument is held between calls on purpose — its parse tree runs to tens
     * of MB for a long book, and PdfBook lives as long as the reader is open.
     */
    private val textCache = android.util.LruCache<Int, String>(8)

    /**
     * Extracts plain text of page [index] (0-based) via PDFBox for AI page summaries.
     * Opens its own read-only handle on the file so the shared renderer fd stays untouched.
     * Returns (pageLabel, text), or null when the page has no extractable text.
     * Call off the main thread.
     */
    fun currentPageText(index: Int): Pair<String, String>? {
        if (index !in 0 until pageCount) return null
        val label = "Page ${index + 1}"
        synchronized(lock) { textCache.get(index) }?.let { cached ->
            return cached.takeIf { it.isNotEmpty() }?.let { label to it }
        }
        val text = runCatching {
            PDDocument.load(file).use { doc ->
                PDFTextStripper().apply {
                    startPage = index + 1
                    endPage = index + 1
                }.getText(doc)
            }
        }.getOrNull()?.trim().orEmpty()
        synchronized(lock) { textCache.put(index, text) }
        return text.takeIf { it.isNotEmpty() }?.let { label to it }
    }

    /**
     * In-book search across every page.
     *
     * The PDF path has no Readium engine (`openPdf` clears it — the toolkit ships no
     * PDF navigator), so search used to return nothing at all here even though the
     * reader panel was built to show results. Text comes from the page-text cache,
     * so a second search over the same pages is cheap.
     */
    internal fun search(query: String, maxTotal: Int = 200): List<PdfSearchHit> =
        searchPdfPages(query, pageCount, { currentPageText(it) }, maxTotal)

    override fun close() {
        synchronized(lock) {
            textCache.evictAll()
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    companion object {
        /** Files starting with the %PDF magic even without a .pdf extension. */
        fun looksLikePdf(file: File): Boolean = try {
            file.inputStream().use { input ->
                val magic = ByteArray(5)
                val read = input.read(magic)
                read == 5 && String(magic) == "%PDF-"
            }
        } catch (_: Exception) {
            false
        }

        fun open(file: File): PdfBook {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return PdfBook(file, fd, PdfRenderer(fd))
            } catch (t: Throwable) {
                runCatching { fd.close() }
                throw t
            }
        }
    }
}
