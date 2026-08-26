package com.bookcon.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Offline word lookup over the bundled GCIDE-derived [assets/dictionary.jsonl]
 * (one {"w": word, "d": definition} JSON object per line, ~86k entries).
 *
 * The index is a full in-memory lowercase-word → line map built lazily on first
 * lookup; every call is main-thread safe (all IO on Dispatchers.IO) and never
 * throws — misses simply return null.
 */
class Dictionary(context: Context) {

    data class Definition(val word: String, val meaning: String)

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var index: MutableMap<String, Int>? = null

    @Volatile
    private var lines: List<String>? = null

    /** Looks up [raw] with light stemming fallbacks; null when unknown. */
    suspend fun lookup(raw: String): Definition? {
        val idx = ensureIndex() ?: return null
        for (candidate in candidatesFor(raw)) {
            val lineNo = idx[candidate] ?: continue
            val entry = readEntry(lineNo) ?: continue
            return Definition(candidate, entry.second)
        }
        return null
    }

    /** All lookup keys tried for a raw token, in priority order. */
    private fun candidatesFor(raw: String): List<String> {
        val base = raw.trim().lowercase().filter { it.isLetter() || it == '-' || it == '\'' || it == ' ' }
        if (base.isBlank()) return emptyList()
        val list = mutableListOf(base)
        // Possessives + common inflections (ordered most-specific first).
        if (base.endsWith("'s")) list += base.removeSuffix("'s")
        if (base.endsWith("ies")) list += base.removeSuffix("ies") + "y"
        if (base.endsWith("es")) list += base.removeSuffix("es")
        if (base.endsWith("s")) list += base.removeSuffix("s")
        if (base.endsWith("ied")) list += base.removeSuffix("ied") + "y"
        if (base.endsWith("ed")) list += base.removeSuffix("ed") + if (base.endsWith("ied")) "" else ""
        if (base.endsWith("ing")) list += base.removeSuffix("ing").let { it + if (it.length > 2 && it.last()==it[it.length-2]) it.last().toString() else "" }
        if (base.endsWith("ly")) list += base.removeSuffix("ly")
        if (base.endsWith("ities")) list += base.removeSuffix("ities") + "ity"
        return list.map { it.trimEnd() }.filter { it.length > 1 }.distinct()
    }

    private suspend fun ensureIndex(): Map<String, Int>? = withContext(Dispatchers.IO) {
        index ?: mutex.withLock {
            if (index != null) return@withLock index
            try {
                val text = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                lines = text.lines()
                val map = HashMap<String, Int>(96_000)
                lines!!.forEachIndexed { i, line ->
                    if (line.isBlank()) return@forEachIndexed
                    val w = runCatching { JSONObject(line).optString("w") }.getOrNull()
                    if (!w.isNullOrBlank() && !map.containsKey(w)) map[w] = i
                }
                index = map
                map
            } catch (t: Throwable) {
                Log.w(TAG, "dictionary asset unavailable", t)
                emptyMap<String, Int>()
            }
        }
    }

    private fun readEntry(lineNo: Int): Pair<String, String>? = runCatching {
        val line = lines?.getOrNull(lineNo) ?: return null
        val obj = JSONObject(line)
        obj.optString("w") to obj.optString("d")
    }.getOrNull()

    private companion object {
        const val TAG = "Dictionary"
        const val ASSET = "dictionary.jsonl"
    }
}
