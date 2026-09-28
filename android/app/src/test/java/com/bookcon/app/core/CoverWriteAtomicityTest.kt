package com.bookcon.app.core

import com.bookcon.app.data.sync.stageFileAtomically
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cover that fails to encode must not be left on disk.
 *
 * The hazard is the caching guard in `ensureCover`: a cover file is accepted once it
 * exists and is non-empty. So writing straight to the final path meant an encode
 * that failed or was cut short left a truncated PNG there, and from then on every
 * import skipped regeneration — the book showed a permanently broken cover with
 * nothing in the logs.
 *
 * `Bitmap.compress` reports failure by returning false rather than throwing, which
 * is exactly the case that used to slip through unnoticed.
 */
class CoverWriteAtomicityTest {

    private fun tmp(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "coverwrite-${name.hashCode()}")
        dir.deleteRecursively(); dir.mkdirs()
        return File(dir, name)
    }

    /** Mirrors how ensureCover treats a failed compress. */
    private fun writeCover(target: File, compress: (OutputStream) -> Boolean): String? =
        try {
            val written = stageFileAtomically(target) { sink ->
                check(compress(sink)) { "PNG compression failed" }
            }
            "file://${written.absolutePath}"
        } catch (_: Throwable) {
            null
        }

    @Test
    fun a_failed_compress_leaves_no_cover_file() {
        val target = tmp("cover.png")
        val result = writeCover(target) { sink ->
            sink.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E)) // a fragment
            false // compress() failed
        }
        assertEquals(null, result)
        assertFalse("a truncated PNG must not be left where the cache guard finds it", target.exists())
    }

    @Test
    fun a_thrown_encode_leaves_no_cover_file() {
        val target = tmp("cover.png")
        val result = writeCover(target) { throw IOException("disk full") }
        assertEquals(null, result)
        assertFalse(target.exists())
    }

    @Test
    fun a_failed_compress_leaves_no_sidecar() {
        val target = tmp("cover.png")
        writeCover(target) { false }
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun a_failed_rewrite_leaves_the_previous_good_cover_intact() {
        val target = tmp("cover.png")
        assertTrue(writeCover(target) { it.write("GOOD".toByteArray()); true } != null)
        assertEquals("GOOD", target.readText())

        // A later extraction for the same book fails; the working cover survives.
        assertEquals(null, writeCover(target) { false })
        assertEquals("GOOD", target.readText())
    }

    @Test
    fun a_successful_compress_publishes_the_file_and_its_url() {
        val target = tmp("cover.png")
        val url = writeCover(target) { it.write("PNGDATA".toByteArray()); true }
        assertEquals("file://${target.absolutePath}", url)
        assertEquals("PNGDATA", target.readText())
    }
}
