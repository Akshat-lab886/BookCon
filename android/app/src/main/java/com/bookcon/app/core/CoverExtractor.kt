package com.bookcon.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a real cover image from locally stored books so library tiles show
 * artwork instead of a black placeholder.
 *
 *  - EPUB: pulls the OPF-declared cover image (or first plausible cover file)
 *    straight out of the zip container — no server round-trip needed.
 *  - PDF: renders page 1 with the system PdfRenderer at thumbnail size.
 *
 * Covers are cached under filesDir/covers/<bookId>.png; the returned value is a
 * `file://` URL that resolveCoverUrl()/Coil already understand.
 */
object CoverExtractor {
    private const val MAX_DIM = 512

    /**
     * Hard cap on bytes pulled out of one zip entry.
     *
     * `readBytes()` on a zip entry is unbounded, and a malformed or hostile book can
     * declare a cover of any size. These entries are only ever thumbnails or small
     * XML documents, so a few megabytes is far more than a real book needs — and
     * `runCatching` around the caller turns an over-read into a silent "no cover"
     * only after the allocation has already stressed the heap.
     */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024

    private val failureCache = LruCache<String, Boolean>(64)

    suspend fun ensureCover(
        context: Context,
        bookId: String,
        format: String,
        localFile: String?,
    ): String? = withContext(Dispatchers.IO) {
        if (localFile.isNullOrBlank()) return@withContext null
        if (failureCache.get(bookId) == true) return@withContext null
        val out = coverFile(context, bookId)
        if (out.exists() && out.length() > 0) return@withContext fileUrl(out)
        val bitmap = runCatching {
            when (format.lowercase()) {
                "pdf" -> renderPdfCover(localFile)
                "epub" -> renderEpubCover(localFile)
                else -> null
            }
        }.getOrNull()
        if (bitmap == null) {
            failureCache.put(bookId, true)
            return@withContext null
        }
        // The cover is written through a sidecar and renamed only once it is whole.
        //
        // The guard above accepts any non-empty file as a finished cover, so writing
        // straight to the final path meant a compress that failed or was cut short
        // left a truncated PNG there — and from then on every import skipped
        // regeneration and Coil failed to load it. The book showed a permanently
        // broken cover with nothing in the logs and no way to recover it short of
        // clearing app data.
        try {
            val written = com.bookcon.app.data.sync.stageFileAtomically(out) { sink ->
                // compress() reports failure by returning false rather than throwing.
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 90, sink)) {
                    "PNG compression failed for $bookId"
                }
            }
            fileUrl(written)
        } catch (t: Throwable) {
            Log.w("CoverExtractor", "cover write failed for $bookId", t)
            null
        } finally {
            bitmap.recycle()
        }
    }

    fun coverFile(context: Context, bookId: String): File =
        File(File(context.filesDir, "covers"), "$bookId.png")

    fun fileUrl(f: File): String = "file://${f.absolutePath}"

    // ------------------------------------------------------------------ EPUB

    private fun renderEpubCover(path: String): Bitmap? {
        ZipFile(File(path)).use { zip ->
            val entry = findEpubCoverEntry(zip) ?: return null
            val bytes = readEntry(zip, entry) ?: return null
            return decodeCover(bytes)
        }
    }

    /** Reads at most [MAX_ENTRY_BYTES] from [entry]; null when it is larger. */
    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry): ByteArray? =
        zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ENTRY_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    /**
     * Decodes a cover at thumbnail size.
     *
     * Decoding straight to a bitmap allocates the image at its FULL resolution
     * first — an 8000x6000 cover is ~192 MB of ARGB_8888 — and only afterwards
     * shrinks it to [MAX_DIM]. A cover is never shown larger than a library tile,
     * so that peak is pure waste and an easy way to be killed for memory while
     * importing. A bounds-only pass costs almost nothing and lets the real decode
     * be subsampled straight to roughly the right size.
     */
    private fun decodeCover(bytes: ByteArray): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM)
        }
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return null
        return scaleDown(decoded)
    }

    /**
     * The `inSampleSize` that brings a [srcWidth]x[srcHeight] image within [maxDim]
     * on its longest side.
     *
     * Always a power of two, because that is the only set of factors the decoder
     * can honour without resampling. Returns 1 when the image already fits.
     */
    internal fun coverSampleSize(srcWidth: Int, srcHeight: Int, maxDim: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxDim <= 0) return 1
        var sample = 1
        var longest = maxOf(srcWidth, srcHeight)
        // A power-of-two decoder step; stop at 1 so the loop always terminates even
        // for a pathological 0-sized or overflowing input.
        while (longest / 2 >= maxDim && sample < (1 shl 12)) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun findEpubCoverEntry(zip: ZipFile): java.util.zip.ZipEntry? {
        // container.xml → rootfile (OPF)
        val container = zip.getEntry("META-INF/container.xml")
        var opfPath = "content.opf"
        var opfDir = ""
        if (container != null) {
            val xml = readEntry(zip, container)?.toString(Charsets.UTF_8) ?: ""
            Regex("full-path=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)?.let { opfPath = it }
            opfDir = opfPath.substringBeforeLast('/', "")
        }
        val opf = zip.getEntry(opfPath) ?: return firstImageFallback(zip)

        val text = readEntry(zip, opf)?.toString(Charsets.UTF_8) ?: ""
        val items = Regex("<item\\b[^>]*>").findAll(text).toList()

        fun itemHrefById(id: String): String? =
            items.firstOrNull { it.value.contains("id=\"$id\"") }
                ?.value?.let { Regex("href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }

        // 1) manifest item with properties="…cover-image"
        val propItem = items.firstOrNull { it.value.contains("cover-image") }
        // 2) <meta name="cover" content="<id>"/>
        val metaId = Regex("<meta[^>]*name=\"cover\"[^>]*content=\"([^\"]+)\"").find(text)
            ?.groupValues?.get(1)
            ?: Regex("<meta[^>]*content=\"([^\"]+)\"[^>]*name=\"cover\"").find(text)
                ?.groupValues?.get(1)
        val metaItem = metaId?.let { id ->
            items.firstOrNull { it.value.contains("id=\"$id\"") }?.value
        }
        // 3) any manifest item whose id/href mentions cover
        val nameMatch = items.firstOrNull {
            it.value.contains("cover", ignoreCase = true)
        }?.value

        for (candidate in listOf(propItem?.value, metaItem, nameMatch)) {
            val href = candidate?.let { Regex("href=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
                ?: continue
            val decoded = href.replace("%20", " ")
            val full = if (opfDir.isBlank()) decoded else "$opfDir/$decoded"
            val normalized = File(full).normalize().path.removePrefix("/")
            zip.getEntry(normalized)?.let { return it }
            zip.getEntry(decoded)?.let { return it }
        }
        return firstImageFallback(zip)
    }

    private fun firstImageFallback(zip: ZipFile): java.util.zip.ZipEntry? =
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .filter { it.name.endsWith(".jpg", true) || it.name.endsWith(".jpeg", true) || it.name.endsWith(".png", true) }
            .minByOrNull { it.name.length } // shortest path ≈ most likely a cover

    // ------------------------------------------------------------------ PDF

    private fun renderPdfCover(path: String): Bitmap? {
        val fd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        fd.use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val page = renderer.openPage(0)
                page.use { p ->
                    val ratio = p.height.toFloat() / p.width.toFloat()
                    val w = MAX_DIM
                    val h = (MAX_DIM * ratio).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= MAX_DIM) return src
        val scale = MAX_DIM.toFloat() / maxSide
        val out = Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
        if (out != src) src.recycle()
        return out
    }
}
