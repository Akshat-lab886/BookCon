package com.bookcon.app.core

import com.bookcon.app.core.ImportServer.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Wi-Fi importer used to decide a file's type from its name alone, so a renamed
 * JPEG or a truncated download was accepted, inserted into the library as READY, and
 * then could not be opened. The payload is now sniffed by its magic bytes.
 */
class ImportServerFormatSniffTest {

    // sniffFormat is an instance member, so it needs a server instance.
    private val server = ImportServer(port = 8090, onSave = { _, _ -> })

    private fun sniff(bytes: ByteArray): Format? {
        val file = File.createTempFile("sniff", ".bin").apply { writeBytes(bytes) }
        return server.sniffFormat(file, 0L to bytes.size.toLong())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun a_real_pdf_is_recognised() {
        assertEquals(Format.PDF, sniff("%PDF-1.7\nrest of the document".toByteArray()))
    }

    @Test
    fun an_epub_zip_is_recognised_by_its_mimetype() {
        val bytes = zip("mimetype" to "application/epub+zip".toByteArray())
        assertEquals(Format.EPUB, sniff(bytes))
    }

    @Test
    fun an_epub_zip_with_container_xml_is_recognised() {
        val bytes = zip("META-INF/container.xml" to "<container/>".toByteArray())
        assertEquals(Format.EPUB, sniff(bytes))
    }

    @Test
    fun a_renamed_jpeg_is_rejected() {
        // JPEG SOI marker FF D8 FF, named "book.pdf" by the caller.
        assertNull(sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)))
    }

    @Test
    fun a_renamed_text_file_is_rejected() {
        assertNull(sniff("This is just a text file pretending to be a book.".toByteArray()))
    }

    @Test
    fun a_truncated_download_is_rejected() {
        // Right extension, zero content — used to be accepted and marked READY.
        assertNull(sniff(ByteArray(0)))
    }

    @Test
    fun a_plain_zip_that_is_not_an_epub_is_rejected() {
        val bytes = zip("holiday.jpg" to byteArrayOf(1, 2, 3), "notes.txt" to "x".toByteArray())
        assertNull(sniff(bytes))
    }

    @Test
    fun a_pdf_renamed_to_epub_is_still_recognised_as_a_pdf() {
        // The caller turns this into a 415, which is the point: the name lies.
        assertEquals(Format.PDF, sniff("%PDF-1.4".toByteArray()))
    }
}
