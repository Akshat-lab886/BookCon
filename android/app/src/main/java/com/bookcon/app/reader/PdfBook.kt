package com.bookcon.app.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Minimal PDF document model built on Android's built-in [PdfRenderer] (API 21+).
 *
 * Readium kotlin-toolkit 3.1.0 ships no PDF navigator (readium-navigator-pdf does not
 * exist for this line), so PDFs are rendered page-by-page into bitmaps and paged by
 * our own Compose pager in PdfPager. PdfRenderer is NOT thread-safe — every access is
 * serialized through [lock].
 */
class PdfBook private constructor(
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

    override fun close() {
        synchronized(lock) {
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
                return PdfBook(fd, PdfRenderer(fd))
            } catch (t: Throwable) {
                runCatching { fd.close() }
                throw t
            }
        }
    }
}
