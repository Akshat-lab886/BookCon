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
        // -ed forms: try the bare stem, the stem with the silent e restored, and the
        // doubled-consonant spelling. The old expression appended `if (...) "" else ""`
        // — both arms were the empty string, so the intended `+ "e"` never happened
        // and this was a no-op, making "loved" fail to find "love" on the first tap.
        if (base.endsWith("ed")) {
            val stem = base.removeSuffix("ed")
            list += stem
            if (stem.length > 2 && stem.last() == stem[stem.length - 2]) {
                // "stopped" -> "stop"
                list += stem.dropLast(1)
            } else {
                // "loved" -> "love"
                list += stem + "e"
            }
        }
        if (base.endsWith("ing")) {
            val stem = base.removeSuffix("ing")
            // The old expression APPENDED the doubled consonant instead of removing
            // it, so "running" produced "runnn" and never "run"; it also never
            // restored the silent e, so "making" never reached "make". Both spellings
            // are needed and neither was being produced correctly.
            list += if (stem.length > 2 && stem.last() == stem[stem.length - 2]) {
                stem.dropLast(1)      // running -> run
            } else {
                stem                  // walking -> walk
            }
            list += stem + "e"         // making -> make
        }
        if (base.endsWith("ly")) list += base.removeSuffix("ly")
        if (base.endsWith("ities")) list += base.removeSuffix("ities") + "ity"
        return list.map { it.trimEnd() }.filter { it.length > 1 }.distinct()
    }

    private suspend fun ensureIndex(): Map<String, Int>? = withContext(Dispatchers.IO) {
        index ?: mutex.withLock {
            if (index != null) return@withLock index
            try {
                val text = appContext.assets.open(ASSET).bufferedReader().use { it.readText() }
                val allLines = text.lines()
                lines = allLines
                val map = HashMap<String, Int>(96_000)
                // A local, not the field: `lines` is a var on the singleton, and a
                // read of it here would be racing whatever else assigns it.
                allLines.forEachIndexed { i, line ->
                    if (line.isBlank()) return@forEachIndexed
                    val w = runCatching { JSONObject(line).optString("w") }.getOrNull()
                    if (!w.isNullOrBlank() && !map.containsKey(w)) map[w] = i
                }
                index = map
                map
            } catch (t: Throwable) {
                Log.w(TAG, "dictionary asset unavailable", t)
                // Cache the failure as well as the success. Returning without
                // assigning left `index` null, so a missing or corrupt asset was
                // re-read and re-parsed in full on EVERY word tap.
                HashMap<String, Int>().also { index = it }
            }
        }
    }

    private fun readEntry(lineNo: Int): Pair<String, String>? = runCatching {
        val line = lines?.getOrNull(lineNo) ?: return null
        val obj = JSONObject(line)
        obj.optString("w") to obj.optString("d")
    }.getOrNull()

    companion object {
        /**
         * One instance for the process. The index covers ~86k lines and is expensive
         * to build; a fresh Dictionary per lookup re-read and re-parsed the whole
         * asset every time.
         */
        @Volatile
        private var instance: Dictionary? = null

        fun get(context: Context): Dictionary =
            instance ?: synchronized(this) {
                instance ?: Dictionary(context.applicationContext).also { instance = it }
            }

        private const val TAG = "Dictionary"
        private const val ASSET = "dictionary.jsonl"
    }
}
