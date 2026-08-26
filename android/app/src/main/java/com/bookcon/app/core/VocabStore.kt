package com.bookcon.app.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-device vocabulary notebook with Leitner spaced repetition.
 *
 * Layout: a single `filesDir/vocab/vocab.json` holding a JSON array of entries
 * `{ "word": "...", "definition": "...", "added": 1690000000000, "box": 2, "due": 1690100000000 }`.
 * Every operation is load-modify-save against the whole file, writes are atomic
 * (tmp file + rename, same pattern as [SummaryCache]), and every IO failure degrades
 * to a silent miss / skipped write instead of crashing the caller. A [Mutex]
 * serializes mutations so concurrent saves cannot drop each other's entries.
 *
 * Identity is the lowercased word: `add` never creates a duplicate for a word that
 * is already stored (capture gating lives with the caller, not here).
 */
class VocabStore(context: Context) {

    data class VocabEntry(
        val word: String,
        val definition: String,
        val addedTs: Long,
        val box: Int = 0,
        val dueTs: Long = addedTs,
    )

    private val dir: File = File(context.applicationContext.filesDir, DIR_NAME)

    private val mutex = Mutex()

    /**
     * Adds [word]; a case-insensitive duplicate is ignored so repeated lookups of
     * the same word stay a single entry.
     */
    suspend fun add(word: String, definition: String) {
        mutate { list ->
            val key = word.trim().lowercase()
            if (key.isEmpty() || list.any { it.normalizedWord() == key }) return@mutate
            list += VocabEntry(
                word = word.trim(),
                definition = definition.trim(),
                addedTs = System.currentTimeMillis(),
            )
        }
    }

    /** Every stored entry, newest first. */
    suspend fun all(): List<VocabEntry> =
        snapshot().sortedByDescending { it.addedTs }

    /** Entries whose review is due at [nowTs], longest-overdue first. */
    suspend fun due(nowTs: Long = System.currentTimeMillis()): List<VocabEntry> =
        snapshot().filter { it.dueTs <= nowTs }.sortedBy { it.dueTs }

    /**
     * Leitner grading for [word]: known moves the card up a box (max [MAX_BOX]) and
     * schedules it [INTERVAL_DAYS] days out; unknown resets to box 0, re-due in 10 min.
     */
    suspend fun grade(word: String, known: Boolean) {
        mutate { list ->
            val key = word.trim().lowercase()
            val idx = list.indexOfFirst { it.normalizedWord() == key }
            if (idx < 0) return@mutate
            val current = list[idx]
            val now = System.currentTimeMillis()
            list[idx] = if (known) {
                val box = (current.box + 1).coerceAtMost(MAX_BOX)
                val days = INTERVAL_DAYS[box.coerceIn(0, INTERVAL_DAYS.lastIndex)]
                current.copy(box = box, dueTs = now + days * DAY_MS)
            } else {
                current.copy(box = 0, dueTs = now + RELEARN_MS)
            }
        }
    }

    /** Removes every entry matching [word] (case-insensitive). */
    suspend fun remove(word: String) {
        mutate { list ->
            val key = word.trim().lowercase()
            list.removeAll { it.normalizedWord() == key }
        }
    }

    /** Number of stored entries. */
    suspend fun count(): Int = snapshot().size

    // ----------------------------------------------------------------- internals

    private fun VocabEntry.normalizedWord(): String = word.trim().lowercase()

    /** Reads the store; any failure (missing/corrupt file) yields an empty list. */
    private suspend fun snapshot(): List<VocabEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { decode(file().readText()) }.getOrElse {
                Log.w(TAG, "VocabStore load failed", it)
                emptyList()
            }
        }
    }

    /** Load-modify-save under the mutex; failures are logged, never thrown. */
    private suspend fun mutate(transform: (MutableList<VocabEntry>) -> Unit) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    val list = decode(file().readText()).toMutableList()
                    transform(list)
                    writeAtomic(file(), encode(list))
                }.onFailure { Log.w(TAG, "VocabStore write failed", it) }
            }
        }
    }

    private fun file(): File = File(dir, FILE_NAME)

    private fun encode(entries: List<VocabEntry>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put(KEY_WORD, entry.word)
                    .put(KEY_DEFINITION, entry.definition)
                    .put(KEY_ADDED, entry.addedTs)
                    .put(KEY_BOX, entry.box)
                    .put(KEY_DUE, entry.dueTs),
            )
        }
    }

    private fun decode(text: String): List<VocabEntry> {
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                val word = obj.optString(KEY_WORD).trim()
                if (word.isEmpty()) return@runCatching null
                val added = obj.optLong(KEY_ADDED)
                val due = obj.optLong(KEY_DUE)
                VocabEntry(
                    word = word,
                    definition = obj.optString(KEY_DEFINITION),
                    addedTs = added,
                    box = obj.optInt(KEY_BOX, 0).coerceIn(0, MAX_BOX),
                    // Absent/zero due falls back to the entry's own added timestamp.
                    dueTs = if (due > 0) due else added,
                )
            }.getOrNull()
        }
    }

    private fun writeAtomic(target: File, json: JSONArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            tmp.writeText(json.toString())
            // rename(2) atomically replaces an existing target on Linux — never
            // delete-then-rename (a crash between the two loses every word).
            if (!tmp.renameTo(target)) error("Could not persist vocab file")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private companion object {
        const val TAG = "VocabStore"
        const val DIR_NAME = "vocab"
        const val FILE_NAME = "vocab.json"

        const val KEY_WORD = "word"
        const val KEY_DEFINITION = "definition"
        const val KEY_ADDED = "added"
        const val KEY_BOX = "box"
        const val KEY_DUE = "due"

        const val MAX_BOX = 6

        /** Days-out indexed by the box reached after a "known" grade. */
        val INTERVAL_DAYS = longArrayOf(0L, 1L, 3L, 7L, 16L, 35L)

        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** An "again" grade comes back in 10 minutes. */
        const val RELEARN_MS = 10L * 60L * 1000L
    }
}
