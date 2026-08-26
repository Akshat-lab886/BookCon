package com.bookcon.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
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

    fun logMinute(bookId: String) {
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
            val tmp = File(dir, "daily.json.tmp")
            tmp.writeText(all.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "logMinute failed", t)
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
