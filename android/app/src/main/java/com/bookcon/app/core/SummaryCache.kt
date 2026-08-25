package com.bookcon.app.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Per-book cache of AI page summaries.
 *
 * Layout: filesDir/summaries/<sanitized-bookId>.json containing
 * `{ "<pageKey>": {"text": "...", "ts": 1690000000000} }`.
 * Every operation is load-modify-save against the whole book file, writes are atomic
 * (tmp file + rename), and every IO failure degrades to a cache miss / silent skip
 * instead of crashing the reader.
 */
class SummaryCache(context: Context) {

    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME)

    /** Cached summary for [bookId]/[pageKey], or null on miss or any storage failure. */
    fun get(bookId: String, pageKey: String): String? = runCatching {
        val entry = loadBook(bookId)?.optJSONObject(pageKey) ?: return@runCatching null
        entry.optString(KEY_TEXT)
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** Stores [summary]; overwrites any previous entry for the same page key. */
    fun put(bookId: String, pageKey: String, summary: String) {
        runCatching {
            dir.mkdirs()
            val root = loadBook(bookId) ?: JSONObject()
            root.put(
                pageKey,
                JSONObject().put(KEY_TEXT, summary).put(KEY_TS, System.currentTimeMillis()),
            )
            writeAtomic(fileFor(bookId), root)
        }.onFailure { Log.w(TAG, "SummaryCache.put failed", it) }
    }

    /** Drops every cached summary for [bookId]. */
    fun clearBook(bookId: String) {
        runCatching {
            if (!fileFor(bookId).delete()) {
                Log.w(TAG, "No summary cache deleted for ${sanitize(bookId)}")
            }
        }.onFailure { Log.w(TAG, "SummaryCache.clearBook failed", it) }
    }

    // ----------------------------------------------------------------- internals

    private fun loadBook(bookId: String): JSONObject? {
        val file = fileFor(bookId)
        if (!file.exists()) return null
        return JSONObject(file.readText())
    }

    private fun writeAtomic(target: File, json: JSONObject) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(json.toString())
            if (target.exists() && !target.delete()) error("Could not replace summary cache file")
            if (!tmp.renameTo(target)) error("Could not persist summary cache file")
        } finally {
            tmp.delete() // no-op once renamed successfully
        }
    }

    private fun fileFor(bookId: String): File = File(dir, "${sanitize(bookId)}.json")

    private fun sanitize(bookId: String): String =
        bookId.replace(UNSAFE_CHARS, "_").ifEmpty { "_" }

    private companion object {
        const val TAG = "SummaryCache"
        const val DIR_NAME = "summaries"
        const val KEY_TEXT = "text"
        const val KEY_TS = "ts"

        // Book ids may contain path-hostile characters; keep [a-zA-Z0-9_-] only so the
        // id can never escape the summaries directory via "/" or "..".
        val UNSAFE_CHARS = Regex("[^a-zA-Z0-9_-]")
    }
}
