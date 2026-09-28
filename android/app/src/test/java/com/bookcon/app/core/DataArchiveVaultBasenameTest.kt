package com.bookcon.app.core

import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.BookmarkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Two books in the same library can share a filename — "book.epub" from two
 * different sources, or a re-import. The archive dedupes entry names inside one
 * export, and the manifest records where each book's bytes ended up.
 *
 * It used to record the plain basename in `localFileBase` while the payload loop
 * renamed the colliding entry to "<hash>-<name>". On import both books then
 * resolved to the first book's staged file, so the second silently opened the
 * first book's content — a backup that restores the wrong data, with no error
 * anywhere in the round trip.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DataArchiveVaultBasenameTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun book(id: String, title: String, file: File) = BookEntity(
        id = id, userId = "u1", title = title, format = "epub", status = "ready",
        addedAt = "2024-01-01T00:00:00Z", updatedAt = "2024-01-01T00:00:00Z",
        localFile = file.absolutePath,
    )

    private fun writeBook(dir: File, dirName: String, name: String, body: String): File {
        val d = File(dir, dirName).apply { mkdirs() }
        // Distinct directories so the two books have the same basename but
        // different absolute paths, which is the real-world shape of this bug.
        return File(d, name).apply { writeText(body) }
    }

    @Test
    fun two_books_sharing_a_filename_each_get_their_own_file_back() = runTest {
        val tmp = File(context.cacheDir, "vaulttest").apply { mkdirs() }
        val a = writeBook(tmp, "one", "book.epub", "FIRST BOOK CONTENT")
        val b = writeBook(tmp, "two", "book.epub", "SECOND BOOK CONTENT")
        val books = listOf(book("id-a", "A", a), book("id-b", "B", b))

        val out = ByteArrayOutputStream()
        val stats = DataArchive.export(
            context = context,
            settings = AppSettings(),
            books = books,
            positions = emptyList(),
            annotations = emptyList(),
            bookmarks = emptyList<BookmarkEntity>(),
            out = out,
        )
        assertEquals("both payloads must be stored", 2, stats.files)

        // Import into a clean staging area and read back what each book resolved to.
        val sink = RecordingSink()
                val restoreDir = File(context.filesDir, "imports").apply {
            deleteRecursively(); mkdirs()
        }
        DataArchive.import(
            context = context,
            input = ByteArrayInputStream(out.toByteArray()),
            currentSettings = AppSettings(),
            applySettings = { },
            sink = sink,
        )

        val fileA = sink.books.first { it.id == "id-a" }.localFile
        val fileB = sink.books.first { it.id == "id-b" }.localFile
        assertNotEquals("books must not collapse onto one file", fileA, fileB)
        assertEquals("FIRST BOOK CONTENT", File(fileA!!).readText())
        assertEquals("SECOND BOOK CONTENT", File(fileB!!).readText())
        assertTrue(restoreDir.exists())
    }

    @Test
    fun a_single_book_still_round_trips() = runTest {
        val tmp = File(context.cacheDir, "vaulttest1").apply { mkdirs() }
        val a = writeBook(tmp, "one", "solo.epub", "ONLY BOOK")
        val out = ByteArrayOutputStream()
        DataArchive.export(
            context = context, settings = AppSettings(),
            books = listOf(book("id-solo", "Solo", a)),
            positions = emptyList(), annotations = emptyList(),
            bookmarks = emptyList<BookmarkEntity>(), out = out,
        )
        val sink = RecordingSink()
        DataArchive.import(
            context = context, input = ByteArrayInputStream(out.toByteArray()),
            currentSettings = AppSettings(), applySettings = { }, sink = sink,
        )
        val path = sink.books.single().localFile
        assertEquals("ONLY BOOK", File(path!!).readText())
    }

    @Test
    fun a_cloud_only_book_is_not_reported_as_a_missing_file() = runTest {
        // A book with no localFile never records a payload, so it comes back as
        // metadata and is expected to be re-downloaded. That is a normal restore,
        // not a partial one — it must not be counted, or every import would warn
        // about books the user never expected to be inline.
        val tmp = File(context.cacheDir, "vaulttest2").apply { mkdirs() }
        val present = writeBook(tmp, "one", "kept.epub", "I AM HERE")
        val out = ByteArrayOutputStream()
        DataArchive.export(
            context = context, settings = AppSettings(),
            books = listOf(
                book("id-kept", "Kept", present),
                book("id-cloud", "CloudOnly", File("/definitely/absent.epub")),
            ),
            positions = emptyList(), annotations = emptyList(),
            bookmarks = emptyList<BookmarkEntity>(), out = out,
        )
        val sink = RecordingSink()
        File(context.filesDir, "imports").deleteRecursively()
        val stats = DataArchive.import(
            context = context, input = ByteArrayInputStream(out.toByteArray()),
            currentSettings = AppSettings(), applySettings = { }, sink = sink,
        )
        assertEquals(0, stats.booksMissingFile)
        assertEquals(1, stats.filesCopied)
    }

    @Test
    fun a_dropped_payload_is_counted_so_a_partial_restore_is_visible() = runTest {
        // Same archive, but the payload entry is stripped — the shape of a
        // truncated or hand-edited file. The book is restored as a row with no
        // file, and the caller now says so instead of claiming success.
        val tmp = File(context.cacheDir, "vaulttest3").apply { mkdirs() }
        val a = writeBook(tmp, "one", "book.epub", "CONTENT")
        val out = ByteArrayOutputStream()
        DataArchive.export(
            context = context, settings = AppSettings(),
            books = listOf(book("id-a", "A", a)),
            positions = emptyList(), annotations = emptyList(),
            bookmarks = emptyList<BookmarkEntity>(), out = out,
        )
        val stripped = stripFileEntries(out.toByteArray())
        val sink = RecordingSink()
        File(context.filesDir, "imports").deleteRecursively()
        val stats = DataArchive.import(
            context = context, input = ByteArrayInputStream(stripped),
            currentSettings = AppSettings(), applySettings = { }, sink = sink,
        )
        assertEquals("a promised-but-absent payload must be reported", 1, stats.booksMissingFile)
        assertEquals(null, sink.books.single().localFile)
    }

    /** Rewrites an archive with every `files/` payload entry removed. */
    private fun stripFileEntries(archive: ByteArray): ByteArray {
        val kept = java.io.ByteArrayOutputStream()
        java.util.zip.ZipInputStream(ByteArrayInputStream(archive)).use { zin ->
            java.util.zip.ZipOutputStream(kept).use { zout ->
                var e = zin.nextEntry
                while (e != null) {
                    if (!e.name.startsWith("files/")) {
                        zout.putNextEntry(java.util.zip.ZipEntry(e.name))
                        zin.copyTo(zout)
                        zout.closeEntry()
                    }
                    e = zin.nextEntry
                }
            }
        }
        return kept.toByteArray()
    }

    private class RecordingSink : ArchiveSink {
        val books = mutableListOf<BookEntity>()
        override suspend fun existingBookUpdatedAt(id: String): String? = null
        override suspend fun existingPosition(bookId: String) = null
        override suspend fun existingAnnotation(id: String) = null
        override suspend fun existingBookmark(id: String) = null
        override suspend fun upsertBook(book: BookEntity) { books += book }
        override suspend fun upsertPosition(position: com.bookcon.app.data.local.PositionEntity) {}
        override suspend fun upsertAnnotation(annotation: com.bookcon.app.data.local.AnnotationEntity) {}
        override suspend fun upsertBookmark(bookmark: BookmarkEntity) {}
    }
}
