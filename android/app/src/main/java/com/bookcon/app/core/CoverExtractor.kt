package com.bookcon.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
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
        runCatching {
            out.parentFile?.mkdirs()
            FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
            fileUrl(out)
        }.getOrNull()
    }

    fun coverFile(context: Context, bookId: String): File =
        File(File(context.filesDir, "covers"), "$bookId.png")

    fun fileUrl(f: File): String = "file://${f.absolutePath}"

    // ------------------------------------------------------------------ EPUB

    private fun renderEpubCover(path: String): Bitmap? {
        ZipFile(File(path)).use { zip ->
            val entry = findEpubCoverEntry(zip) ?: return null
            val bytes = zip.getInputStream(entry).readBytes()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return null
            return scaleDown(bmp)
        }
    }

    private fun findEpubCoverEntry(zip: ZipFile): java.util.zip.ZipEntry? {
        // container.xml → rootfile (OPF)
        val container = zip.getEntry("META-INF/container.xml")
        var opfPath = "content.opf"
        var opfDir = ""
        if (container != null) {
            val xml = zip.getInputStream(container).readBytes().toString(Charsets.UTF_8)
            Regex("full-path=\"([^\"]+)\"").find(xml)?.groupValues?.get(1)?.let { opfPath = it }
            opfDir = opfPath.substringBeforeLast('/', "")
        }
        val opf = zip.getEntry(opfPath) ?: return firstImageFallback(zip)

        val text = zip.getInputStream(opf).readBytes().toString(Charsets.UTF_8)
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
