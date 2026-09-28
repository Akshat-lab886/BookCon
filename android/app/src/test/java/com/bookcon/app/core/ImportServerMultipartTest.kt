package com.bookcon.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Wi-Fi import used to read the whole upload into a `ByteArray` and then
 * `copyOfRange` the payload out of it, so a 400 MB book needed ~800 MB of heap and
 * the process was killed mid-upload. The body is now spooled to disk and the payload
 * range is located and copied in bounded chunks.
 *
 * These tests build real multipart bodies on disk (including one large enough to span
 * many read buffers, which is exactly where a streaming scanner goes wrong) and check
 * that the extracted payload is byte-identical to what was sent.
 */
class ImportServerMultipartTest {

    /** Every temp file this test creates, removed afterwards. */
    private val scratch = mutableListOf<File>()

    @org.junit.After
    fun cleanUp() {
        // Without this the 5 MB extraction fixture leaked 10 MB per run; repeated
        // local runs filled the disk with orphaned bcout*.bin files.
        scratch.forEach { runCatching { it.delete() } }
        scratch.clear()
    }

    private fun tempFile(bytes: ByteArray): File =
        Files.createTempFile("bctest", ".bin").toFile().apply {
            writeBytes(bytes)
            scratch += this
        }

    private fun outFile(): File =
        Files.createTempFile("bcout", ".bin").toFile().also { scratch += it }

    private fun multipart(fileName: String, payload: ByteArray): ByteArray {
        val boundary = "----BookConBoundary9x7q"
        val head = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"book\"; filename=\"$fileName\"\r\n")
            append("Content-Type: application/octet-stream\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        val tail = "\r\n--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1)
        return head + payload + tail
    }

    // The ImportServer internals are private, so they are exercised through a tiny
    // reflection helper rather than by widening the class's API for the test.
    private fun extractFileNameOf(file: File): String? {
        val m = ImportServer::class.java.getDeclaredMethod("extractFileName", File::class.java)
        m.isAccessible = true
        return m.invoke(newInstance(), file) as String?
    }

    private fun payloadRangeOf(file: File, boundary: String): Pair<Long, Long>? {
        val server = newInstance()
        val m = ImportServer::class.java.getDeclaredMethod("payloadRange", File::class.java, String::class.java)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return m.invoke(server, file, boundary) as Pair<Long, Long>?
    }

    private fun copyRangeOf(from: File, to: File, start: Long, endExclusive: Long) {
        val server = newInstance()
        val m = ImportServer::class.java.getDeclaredMethod(
            "copyRange", File::class.java, File::class.java, Long::class.java, Long::class.java,
        )
        m.isAccessible = true
        m.invoke(server, from, to, start, endExclusive)
    }

    private fun newInstance(): ImportServer {
        val ctor = ImportServer::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType, Function2::class.java, Function2::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            8090,
            { _: File, _: String -> },
            { _: String, _: String -> },
        ) as ImportServer
    }

    @Test
    fun a_small_payload_is_extracted_intact() {
        val payload = "Hello, this is a book.".toByteArray()
        val body = multipart("novel.epub", payload)
        val file = tempFile(body)
        val range = payloadRangeOf(file, "----BookConBoundary9x7q")!!
        val out = outFile()
        copyRangeOf(file, out, range.first, range.second)
        assertArrayEquals(payload, out.readBytes())
    }

    @Test
    fun a_payload_larger_than_the_read_buffer_is_extracted_intact() {
        // 5 MB of varied, non-repeating-ish bytes so a scanner that mishandles a
        // match spanning two read buffers produces visibly wrong output.
        val random = java.util.Random(42)
        val payload = ByteArray(5 * 1024 * 1024).also { random.nextBytes(it) }
        val body = multipart("big.pdf", payload)
        val file = tempFile(body)
        val range = payloadRangeOf(file, "----BookConBoundary9x7q")!!
        val out = outFile()
        copyRangeOf(file, out, range.first, range.second)
        val extracted = out.readBytes()
        assertEquals(payload.size, extracted.size)
        assertArrayEquals(payload, extracted)
    }

    @Test
    fun the_file_name_is_read_from_the_part_headers() {
        val file = tempFile(multipart("The Hobbit.epub", ByteArray(10)))
        assertEquals("The Hobbit.epub", extractFileNameOf(file))
    }

    @Test
    fun a_body_with_no_boundary_yields_no_range() {
        val file = tempFile("just some bytes, no multipart framing here".toByteArray())
        assertNull(payloadRangeOf(file, "----Nope"))
    }

    @Test
    fun the_extracted_range_starts_after_the_header_and_ends_before_the_terminator() {
        val payload = "0123456789".toByteArray()
        val body = multipart("a.epub", payload)
        val file = tempFile(body)
        val range = payloadRangeOf(file, "----BookConBoundary9x7q")!!
        assertTrue("range must be inside the body", range.second <= body.size)
        assertEquals(payload.size.toLong(), range.second - range.first)
    }
}
