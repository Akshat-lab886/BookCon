package com.bookcon.app.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import java.io.File

/**
 * daily.json is the user's entire reading history: every day, the streak and the
 * goal, accumulated since install. Two defects here were data loss, not inconvenience:
 *
 *  - the rename fallback did `file.delete()` then `tmp.renameTo(file)`, so if the
 *    second rename also failed the history was already unlinked and unrecoverable;
 *  - the read-modify-write was unsynchronised, so two overlapping minute ticks both
 *    read the same totals and one was lost.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReadingTrackerTest {

    private lateinit var context: android.content.Context
    private val dir: File get() = File(context.filesDir, "stats")

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun tracker() = ReadingTracker(context)

    private fun readTotals(): JSONObject = File(dir, "daily.json").let {
        if (!it.exists()) JSONObject() else JSONObject(it.readText())
    }

    @Test
    fun a_logged_minute_is_persisted() {
        val t = tracker()
        t.logMinute("book-1")
        val day = readTotals().optJSONObject(todayKey())!!
        assertEquals(1, day.optInt("total"))
        assertEquals(1, day.optJSONObject("books")!!.optInt("book-1"))
    }

    @Test
    fun many_minutes_are_all_counted() {
        val t = tracker()
        repeat(25) { t.logMinute("book-1") }
        val day = readTotals().optJSONObject(todayKey())!!
        assertEquals(25, day.optInt("total"))
    }

    @Test
    fun concurrent_minutes_do_not_lose_counts() {
        // The unsynchronised read-modify-write dropped a minute here; the whole point
        // of the mutex is that every increment survives.
        val t = tracker()
        val threads = (1..8).map { i ->
            Thread { repeat(10) { t.logMinute("book-$i") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val day = readTotals().optJSONObject(todayKey())!!
        assertEquals("no minute may be lost", 80, day.optInt("total"))
        val books = day.optJSONObject("books")!!
        (1..8).forEach { assertEquals(10, books.optInt("book-$it")) }
    }

    @Test
    fun an_existing_history_is_preserved_when_a_new_day_is_logged() {
        val file = File(dir, "daily.json")
        file.writeText(JSONObject().put("2020-01-01", JSONObject().put("total", 42)).toString())
        tracker().logMinute("book-1")
        val all = readTotals()
        assertEquals(
            "a past day's total must survive a write",
            42,
            all.optJSONObject("2020-01-01")!!.optInt("total"),
        )
        assertEquals(1, all.optJSONObject(todayKey())!!.optInt("total"))
    }

    @Test
    fun no_temp_file_is_left_behind() {
        tracker().logMinute("book-1")
        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("stray temp files: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun the_history_file_is_never_removed_by_a_failed_write() {
        val file = File(dir, "daily.json")
        file.writeText(JSONObject().put("2020-01-01", JSONObject().put("total", 7)).toString())
        // Make the directory read-only so the temp file cannot be created and the
        // write fails. The live file must still be there afterwards.
        dir.setWritable(false)
        try {
            tracker().logMinute("book-1")
        } finally {
            dir.setWritable(true)
        }
        assertTrue("daily.json must never be deleted on a write failure", file.exists())
        assertEquals(7, readTotals().optJSONObject("2020-01-01")!!.optInt("total"))
    }

    private fun todayKey(): String = java.time.LocalDate.now().toString()

}
