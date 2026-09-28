package com.bookcon.app.data.sync

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A downloaded book is written via a `.part` sidecar and renamed into place.
 *
 * The failure this prevents: writing straight to the target truncates whatever was
 * already there, so a download cut short by a dropped connection, a stopped worker
 * or a killed process left a half-written file under the name the database still
 * pointed at. Nothing rejects it — it exists, it is non-empty, the book still claims
 * to be downloaded, and it still will not open.
 */
class StageFileAtomicallyTest {

    private fun tmp(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "stagefile-${name.hashCode()}")
        dir.deleteRecursively(); dir.mkdirs()
        return File(dir, name)
    }

    @Test
    fun a_successful_write_lands_the_whole_content() {
        val target = tmp("book.epub")
        stageFileAtomically(target) { it.write("complete contents".toByteArray()) }
        assertEquals("complete contents", target.readText())
    }

    @Test
    fun an_interrupted_write_leaves_an_existing_good_file_untouched() {
        // The regression: writing straight to the target would leave "PARTIAL"
        // here, a file that exists, is non-empty, and cannot be opened.
        val target = tmp("book.epub")
        target.writeText("the good copy that was already downloaded")

        val failed = runCatching {
            stageFileAtomically(target) { out ->
                out.write("PARTIAL".toByteArray())
                out.flush()
                throw IOException("connection dropped")
            }
        }
        assertTrue("the failure must propagate", failed.isFailure)
        assertEquals(
            "the existing download was destroyed by an interrupted re-download",
            "the good copy that was already downloaded",
            target.readText(),
        )
    }

    @Test
    fun a_failed_write_leaves_no_sidecar_behind() {
        val target = tmp("book.epub")
        runCatching {
            stageFileAtomically(target) { throw IOException("connection dropped") }
        }
        val sidecar = File(target.parentFile, "${target.name}.part")
        assertFalse("a stray .part could be mistaken for content", sidecar.exists())
    }

    @Test
    fun a_successful_write_leaves_no_sidecar_behind() {
        val target = tmp("book.epub")
        stageFileAtomically(target) { it.write("done".toByteArray()) }
        assertFalse(File(target.parentFile, "${target.name}.part").exists())
    }

    @Test
    fun an_interrupted_first_download_leaves_no_file_at_all() {
        // Nothing existed before, so nothing half-written should exist after —
        // otherwise the book would look downloaded without being readable.
        val target = tmp("book.epub")
        runCatching {
            stageFileAtomically(target) { out ->
                out.write("PARTIAL".toByteArray())
                throw IOException("connection dropped")
            }
        }
        assertFalse(target.exists())
    }

    @Test
    fun the_target_directory_is_created_if_missing() {
        val dir = File(tmp("nested").parentFile, "does/not/exist/yet").apply { deleteRecursively() }
        val target = File(dir, "book.pdf")
        stageFileAtomically(target) { it.write("ok".toByteArray()) }
        assertEquals("ok", target.readText())
    }

    @Test
    fun a_later_successful_write_replaces_the_earlier_one() {
        val target = tmp("book.epub")
        stageFileAtomically(target) { it.write("first".toByteArray()) }
        stageFileAtomically(target) { it.write("second".toByteArray()) }
        assertEquals("second", target.readText())
    }
}
