package com.bookcon.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Local reading-time tracker: one atomic JSON file at files/stats/daily.json of shape
 * {"2026-08-26": {"total": 34, "books": {"<bookId>": 12}}}. Mirrors SummaryCache's
 * guarded-IO conventions; never throws across the API surface.
 */
class ReadingTracker(context: Context) {

    data class DailyStat(val date: String, val totalMinutes: Int)
    data class BookMinutes(val bookId: String, val minutes: Int)

    private val dir = File(context.applicationContext.filesDir, "stats")
    private val file = File(dir, "daily.json")
    private val todayKey: String get() = LocalDate.now().toString()

    // Today's bucket cached in memory so per-minute ticks avoid re-reading disk.
    @Volatile
    private var cacheDate: String? = null

    @Volatile
    private var cacheTotal: Int = 0

    /**
     * Serialises read-modify-write, matching VocabStore.mutate.
     *
     * The previous version did `readAll()` then incremented and wrote with no lock, so
     * two overlapping minute ticks both read the same totals and one increment was
     * lost — reading time silently under-counted.
     */
    private val logMutex = Mutex()

    fun logMinute(bookId: String) {
        runBlocking {
            logMutex.withLock {
                try {
                    if (cacheDate != todayKey) {
                        cacheDate = todayKey
                        cacheTotal = readAll().optJSONObject(todayKey)?.optInt("total", 0) ?: 0
                    }
                    cacheTotal += 1
                    val all = readAll()
                    val day = all.optJSONObject(todayKey) ?: JSONObject().also { all.put(todayKey, it) }
                    day.put("total", day.optInt("total", 0) + 1)
                    val books = day.optJSONObject("books") ?: JSONObject().also { day.put("books", it) }
                    books.put(bookId, books.optInt(bookId, 0) + 1)

                    dir.mkdirs()
                    // Write to a temp file, then replace. The old fallback deleted the
                    // live file first and retried the rename — if that second rename
                    // also failed, daily.json was already gone and with it every day,
                    // the streak and the goal since install. VocabStore.writeAtomic
                    // documents exactly why that pattern is unsafe. Here the original
                    // is only removed once the replacement is known to be in place, and
                    // if the replacement cannot be made at all the original is kept.
                    val tmp = File(dir, "daily.json.tmp")
                    tmp.writeText(all.toString())
                    if (!tmp.renameTo(file)) {
                        // Rename within the same directory usually fails only on some
                        // filesystems when the target exists; copy across, keeping the
                        // original until the copy has fully succeeded.
                        //
                        // The copy itself goes through the shared atomic stager rather
                        // than `file.outputStream()`. That call truncates the target the
                        // instant it is opened, so a copy that died halfway left
                        // daily.json truncated — while the code below reported "kept the
                        // original" and the comment above promised exactly the opposite.
                        // The bytes are in `tmp`, so there is no reason to stream them
                        // into a file that is already being destroyed.
                        val copied = runCatching {
                            tmp.inputStream().use { input ->
                                com.bookcon.app.data.sync.stageFileAtomically(file) { out ->
                                    input.copyTo(out)
                                }
                            }
                            true
                        }.getOrDefault(false)
                        runCatching { tmp.delete() }
                        if (!copied) {
                            // Original is still intact; leave it alone.
                            Log.w(TAG, "logMinute could not replace daily.json; kept the original")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "logMinute failed", t)
                }
            }
        }
    }

    /** (dateKey, minutes) for today; memory-cached. */
    suspend fun today(): Pair<String, Int> = withContext(Dispatchers.IO) {
        if (cacheDate == todayKey) return@withContext todayKey to cacheTotal
        val total = readAll().optJSONObject(todayKey)?.optInt("total", 0) ?: 0
        cacheDate = todayKey
        cacheTotal = total
        todayKey to total
    }

    /** Last [days] days ending today, missing days as zero-minute entries. */
    suspend fun last(days: Int = 30): List<DailyStat> = withContext(Dispatchers.IO) {
        val all = readAll()
        (0 until days).map { offset ->
            val key = LocalDate.now().minusDays(offset.toLong()).toString()
            DailyStat(key, all.optJSONObject(key)?.optInt("total", 0) ?: 0)
        }.reversed()
    }

    /** Per-book minutes for a given ISO date, highest first. */
    suspend fun booksFor(date: String): List<BookMinutes> = withContext(Dispatchers.IO) {
        val books = readAll().optJSONObject(date)?.optJSONObject("books")
        books?.keys()?.asSequence()?.map { BookMinutes(it, books.optInt(it)) }
            ?.sortedByDescending { it.minutes }?.toList().orEmpty()
    }

    /** Consecutive days with any reading, counting today or yesterday as the anchor. */
    suspend fun streak(): Int = withContext(Dispatchers.IO) {
        val all = readAll()
        var day = LocalDate.now()
        fun read(d: LocalDate) = all.optJSONObject(d.toString())?.optInt("total", 0) ?: 0
        if (read(day) == 0) day = day.minusDays(1)
        var streak = 0
        while (read(day) > 0) {
            streak += 1
            day = day.minusDays(1)
        }
        streak
    }

    private fun readAll(): JSONObject = runCatching {
        if (!file.exists()) JSONObject() else JSONObject(file.readText())
    }.getOrElse { JSONObject() }

    private companion object {
        const val TAG = "ReadingTracker"
    }
}
