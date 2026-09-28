package com.bookcon.app.core

import android.content.Context
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable local archive (`.bookcon` zip) used by the Local Vault feature.
 *
 * Layout:
 *   manifest.json   — schema/app/timestamps            (deflated)
 *   data.json       — settings + all table rows        (deflated)
 *   files/<basename>— raw book payloads                (level-0: EPUB/PDF are
 *                     already compressed containers; recompressing wastes CPU
 *                     and battery for ~0% size win — this keeps export snappy)
 *
 * Everything uses java.util.zip → no extra dependency, fast on low-end tablets.
 */
object DataArchive {
    const val SCHEMA = 1
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_DATA = "data.json"
    private const val ENTRY_FILES = "files/"

    data class ExportStats(val books: Int, val files: Int, val bytes: Long)
    data class ImportStats(
        val books: Int,
        val positions: Int,
        val annotations: Int,
        val bookmarks: Int,
        val filesCopied: Int,
        val settingsApplied: Boolean,
        /**
         * Books the archive promised a payload for, but which have no usable file
         * after the import.
         *
         * These arrive as rows with no `localFile`, so they show up in the library
         * and cannot be opened. Without this count the import reports a clean
         * success and the failure only surfaces much later as "could not open this
         * file", which reads as corruption rather than a partial restore.
         */
        val booksMissingFile: Int = 0,
    )

    // ------------------------------------------------------------------ export

    fun export(
        context: Context,
        settings: AppSettings,
        books: List<com.bookcon.app.data.local.BookEntity>,
        positions: List<com.bookcon.app.data.local.PositionEntity>,
        annotations: List<com.bookcon.app.data.local.AnnotationEntity>,
        bookmarks: List<com.bookcon.app.data.local.BookmarkEntity>,
        out: OutputStream,
    ): ExportStats {
        var totalBytes = 0L
        var fileCount = 0
        ZipOutputStream(out.buffered()).use { zip ->
            // Metadata compresses well; book payloads go in near-stored (level 0).
            zip.setLevel(Deflater.BEST_SPEED)

            putDeflated(zip, ENTRY_MANIFEST, manifestJson().toString(2))
            totalBytes += 0

            // The export card promises "books, reading positions, highlights and
            // history". Reading history and the vocabulary notebook live in
            // filesDir/stats/daily.json and filesDir/vocab/vocab.json, and neither was
            // ever read here — so moving to a new tablet as instructed arrived with a
            // zeroed streak and an empty vocabulary list, with no warning. They are
            // plain JSON on disk, so they can be carried verbatim.
            // Plan the payload entry names BEFORE writing data.json, so each book
            // records the name its own bytes were actually stored under.
            //
            // This used to record the plain basename while the payload loop renamed
            // collisions to "<hash>-<name>". Two books called "book.epub" therefore
            // both pointed at the first one's file on import, and the second
            // silently opened the first book's content — a backup that restores the
            // wrong data with no error anywhere.
            val payloadNames = LinkedHashMap<String, String>()
            val seen = HashSet<String>()
            for (book in books) {
                val path = book.localFile ?: continue
                val src = File(path)
                if (!src.exists() || src.length() == 0L) continue
                val base = src.name
                val entry = if (seen.add(base)) base else {
                    val unique = "${src.hashCode()}-$base"
                    seen.add(unique)
                    unique
                }
                payloadNames[book.id] = entry
            }

            val dataJson = dataJson(
                settings, books, positions, annotations, bookmarks,
                readingHistory = readJsonFile(File(context.filesDir, "stats/daily.json")),
                vocabulary = readJsonArray(File(context.filesDir, "vocab/vocab.json")),
                entryNames = payloadNames,
            )
            putDeflated(zip, ENTRY_DATA, dataJson)

            zip.setLevel(Deflater.NO_COMPRESSION) // payloads below are already compressed
            for (book in books) {
                val path = book.localFile ?: continue
                val src = File(path)
                val base = payloadNames[book.id] ?: continue
                zip.putNextEntry(ZipEntry("$ENTRY_FILES$base"))
                src.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
                totalBytes += src.length()
                fileCount++
            }
        }
        return ExportStats(books = books.size, files = fileCount, bytes = totalBytes)
    }

    private fun manifestJson(): JSONObject = JSONObject()
        .put("app", "bookcon")
        .put("kind", "local-vault")
        .put("schema", SCHEMA)
        .put("exportedAt", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString())

    private fun putDeflated(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    // ------------------------------------------------------------------ import

    suspend fun import(
        context: Context,
        input: InputStream,
        currentSettings: AppSettings,
        applySettings: suspend (AppSettings) -> Unit,
        sink: ArchiveSink,
    ): ImportStats {
        var dataObj: JSONObject? = null
        var statsBooks = 0
        var statsPositions = 0
        var statsAnnotations = 0
        var statsBookmarks = 0
        var statsFiles = 0
        var statsMissingFile = 0

        ZipInputStream(input.buffered()).use { zip ->
            // Files stream straight to disk; rows apply once data.json is seen.
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == ENTRY_DATA -> {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        dataObj = JSONObject(text)
                    }
                    entry.name.startsWith(ENTRY_FILES) -> {
                        val base = entry.name.removePrefix(ENTRY_FILES)
                        if (base.isNotBlank() && !base.contains("..")) {
                            val dest = stagedFile(context, base)
                            // Staged and renamed like every other file this app writes.
                            // resolveVaultFile() below accepts any non-empty file as a
                            // restored book, so an extraction killed mid-copy would
                            // otherwise leave a partial file that a later import could
                            // pick up and point a book at.
                            com.bookcon.app.data.sync.stageFileAtomically(dest) { out ->
                                zip.copyTo(out, 64 * 1024)
                            }
                            statsFiles++
                        }
                    }
                }
                entry = zip.nextEntry
            }

            dataObj?.let { data ->
                // --- books -------------------------------------------------------
                for (i in 0 until (data.optJSONArray("books")?.length() ?: 0)) {
                    val o = data.getJSONArray("books").getJSONObject(i)
                    val base = bookFromJson(o) ?: continue
                    val resolved = base.localFile
                        ?.let { resolveVaultFile(context, it)?.absolutePath }
                    val book = base.copy(localFile = resolved ?: base.localFile?.takeIf { !it.startsWith("__VAULT__:") })
                    // The archive recorded a payload for this book, but no usable
                    // file came out of it. Count it so the caller can say so.
                    if (base.localFile?.startsWith("__VAULT__:") == true && resolved == null) {
                        statsMissingFile++
                    }
                    val existingAt = sink.existingBookUpdatedAt(book.id)
                    // Format-agnostic: a local stamp ends in "Z" and a pulled one in "+00:00",
                    // so a text compare misorders two rows that are really the same age.
                    val newer = existingAt?.let { isNewerIso(book.updatedAt, it) } == true
                    if (!newer) {
                        sink.upsertBook(book)
                        statsBooks++
                    }
                }
                // --- positions ---------------------------------------------------
                for (i in 0 until (data.optJSONArray("positions")?.length() ?: 0)) {
                    val o = data.getJSONArray("positions").getJSONObject(i)
                    val p = positionFromJson(o) ?: continue
                    val ex = sink.existingPosition(p.bookId)
                    if (ex == null || isNewerIso(p.updatedAt, ex.updatedAt)) {
                        sink.upsertPosition(p); statsPositions++
                    }
                }
                // --- annotations -------------------------------------------------
                for (i in 0 until (data.optJSONArray("annotations")?.length() ?: 0)) {
                    val o = data.getJSONArray("annotations").getJSONObject(i)
                    val a = annotationFromJson(o) ?: continue
                    val ex = sink.existingAnnotation(a.id)
                    if (ex == null || isNewerIso(a.updatedAt, ex.updatedAt) || (a.deletedAt != null && ex.deletedAt == null)) {
                        sink.upsertAnnotation(a); statsAnnotations++
                    }
                }
                // --- bookmarks ---------------------------------------------------
                for (i in 0 until (data.optJSONArray("bookmarks")?.length() ?: 0)) {
                    val o = data.getJSONArray("bookmarks").getJSONObject(i)
                    val b = bookmarkFromJson(o) ?: continue
                    val ex = sink.existingBookmark(b.id)
                    if (ex == null || isNewerIso(b.updatedAt, ex.updatedAt) || (b.deletedAt != null && ex.deletedAt == null)) {
                        sink.upsertBookmark(b); statsBookmarks++
                    }
                }
                // --- settings (merge only keys present) --------------------------
                data.optJSONObject("settings")?.let { s ->
                    if (s.length() > 0) {
                        applySettings(settingsFromJson(s, currentSettings))
                    }
                }
                // --- reading history + vocabulary --------------------------------
                // Restored only when this device has nothing, so a re-import cannot
                // wipe history that has accumulated since the archive was taken.
                data.optJSONObject("readingHistory")?.let { history ->
                    if (history.length() > 0) restoreFileJson(
                        context, File(context.filesDir, "stats/daily.json"), history,
                    )
                }
                data.optJSONArray("vocabulary")?.let { vocab ->
                    if (vocab.length() > 0) restoreFileJson(
                        context, File(context.filesDir, "vocab/vocab.json"), vocab,
                    )
                }
            }
        }
        return ImportStats(
            statsBooks, statsPositions, statsAnnotations, statsBookmarks, statsFiles, false,
            booksMissingFile = statsMissingFile,
        )
    }

    /**
     * Writes a restored JSON document back into filesDir, but only if the device has
     * no data there yet. Overwriting unconditionally would let a stale archive erase
     * reading history the user has built up since exporting it.
     */
    private fun restoreFileJson(context: Context, file: File, payload: Any) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > 0) return@runCatching
            val text = payload.toString()
            val tmp = File(file.parentFile, "${file.name}.restore")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                runCatching { tmp.delete() }
            }
        }
    }

    /** Files land in imports/ under an archive-prefixed name; re-import reuses them. */
    private fun stagedFile(context: Context, base: String): File {
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        return File(dir, "vault-$base")
    }

    // ------------------------------------------------------------------ json codecs

    private fun dataJson(
        settings: AppSettings,
        books: List<com.bookcon.app.data.local.BookEntity>,
        positions: List<com.bookcon.app.data.local.PositionEntity>,
        annotations: List<com.bookcon.app.data.local.AnnotationEntity>,
        bookmarks: List<com.bookcon.app.data.local.BookmarkEntity>,
        readingHistory: JSONObject? = null,
        vocabulary: JSONArray? = null,
        entryNames: Map<String, String> = emptyMap(),
    ): String {
        val settingsArr = JSONObject()
            .put("themeMode", settings.themeMode)
            .put("storageMode", settings.storageMode)
            .put("readerTheme", settings.readerTheme)
            .put("readerFontFamily", settings.readerFontFamily)
            .put("readerFontSizeSp", settings.readerFontSizeSp.toDouble())
            .put("readerPaginationMode", settings.readerPaginationMode)
            .put("volumeKeyTurns", settings.volumeKeyTurns)
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(JSONObject()
                .put("id", b.id)
                .put("userId", b.userId)
                .put("format", b.format)
                .put("status", b.status)
                .putOpt("statusMessage", b.statusMessage)
                .put("title", b.title)
                .put("authors", JSONArray(b.authors))
                .putOpt("description", b.description.ifBlank { null })
                .putOpt("language", b.language)
                .putOpt("publisher", b.publisher)
                .putOpt("fileSizeBytes", b.fileSizeBytes)
                .putOpt("pageCount", b.pageCount)
                .putOpt("coverUrl", b.coverUrl)
                .put("addedAt", b.addedAt)
                .put("updatedAt", b.updatedAt)
                .putOpt("deletedAt", b.deletedAt)
                .put("dirty", b.dirty)
                .putOpt("localFileBase", entryNames[b.id]))
        }
        val pos = JSONArray()
        positions.forEach { p ->
            pos.put(JSONObject()
                .put("bookId", p.bookId)
                .put("locatorJson", p.locatorJson)
                .putOpt("progressPercent", p.progressPercent)
                .put("updatedAt", p.updatedAt))
        }
        val ann = JSONArray()
        annotations.forEach { a ->
            ann.put(JSONObject()
                .put("id", a.id)
                .put("bookId", a.bookId)
                .put("type", a.type)
                .put("locatorJson", a.locatorJson)
                .put("color", a.color)
                .putOpt("note", a.note.ifBlank { null })
                .put("annotationTags", JSONArray(a.annotationTags))
                .putOpt("excerpt", a.excerpt.ifBlank { null })
                .putOpt("createdAt", a.createdAt)
                .put("updatedAt", a.updatedAt)
                .putOpt("deletedAt", a.deletedAt))
        }
        val bm = JSONArray()
        bookmarks.forEach { k ->
            bm.put(JSONObject()
                .put("id", k.id)
                .put("bookId", k.bookId)
                .put("locatorJson", k.locatorJson)
                .putOpt("label", k.label.ifBlank { null })
                .put("createdAt", k.createdAt)
                .put("updatedAt", k.updatedAt)
                .putOpt("deletedAt", k.deletedAt))
        }
        val root = JSONObject()
            .put("settings", settingsArr)
            .put("books", arr)
            .put("positions", pos)
            .put("annotations", ann)
            .put("bookmarks", bm)
        // Optional extras. An archive written before these keys still imports, because
        // every read on the import side goes through opt* accessors.
        readingHistory?.let { root.put("readingHistory", it) }
        vocabulary?.let { root.put("vocabulary", it) }
        return root.toString(2)
    }

    /** Reads a file-backed JSON document, or null when it is absent or unreadable. */
    private fun readJsonFile(file: File): JSONObject? = runCatching {
        if (file.exists() && file.length() > 0) JSONObject(file.readText()) else null
    }.getOrNull()

    /** Reads a file-backed JSON array, or null when it is absent or unreadable. */
    private fun readJsonArray(file: File): JSONArray? = runCatching {
        if (file.exists() && file.length() > 0) JSONArray(file.readText()) else null
    }.getOrNull()

    private fun optStr(o: JSONObject, k: String): String? =
        if (o.isNull(k)) null else o.optString(k, null)

    private fun bookFromJson(o: JSONObject): com.bookcon.app.data.local.BookEntity? = runCatching {
        val authors = mutableListOf<String>().also { out ->
            val a = o.optJSONArray("authors") ?: JSONArray()
            for (i in 0 until a.length()) out.add(a.getString(i))
        }
        val tags = mutableListOf<String>().also { out ->
            val a = o.optJSONArray("annotationTags") ?: JSONArray()
            for (i in 0 until a.length()) out.add(a.getString(i))
        }
        val localBase = optStr(o, "localFileBase")
        com.bookcon.app.data.local.BookEntity(
            id = o.getString("id"),
            userId = o.optString("userId", "vault"),
            format = o.getString("format"),
            status = o.optString("status", "ready"),
            statusMessage = optStr(o, "statusMessage"),
            title = o.getString("title"),
            authors = authors,
            description = o.optString("description", ""),
            language = optStr(o, "language"),
            publisher = optStr(o, "publisher"),
            publishedDate = optStr(o, "publishedDate"),
            fileSizeBytes = if (o.has("fileSizeBytes")) o.getLong("fileSizeBytes") else null,
            pageCount = if (o.has("pageCount")) o.getInt("pageCount") else null,
            coverUrl = optStr(o, "coverUrl"),
            addedAt = o.optString("addedAt", ""),
            updatedAt = o.getString("updatedAt"),
            deletedAt = optStr(o, "deletedAt"),
            dirty = o.optBoolean("dirty", true),
            // Rewritten by the caller after the payload lands in imports/.
            localFile = localBase?.let { "__VAULT__:$it" },
        )
    }.getOrNull()

    private fun positionFromJson(o: JSONObject): com.bookcon.app.data.local.PositionEntity? = runCatching {
        com.bookcon.app.data.local.PositionEntity(
            bookId = o.getString("bookId"),
            locatorJson = o.getString("locatorJson"),
            progressPercent = if (o.has("progressPercent") && !o.isNull("progressPercent")) o.getDouble("progressPercent") else null,
            updatedAt = o.getString("updatedAt"),
            dirty = true,
        )
    }.getOrNull()

    private fun annotationFromJson(o: JSONObject): com.bookcon.app.data.local.AnnotationEntity? = runCatching {
        val tags = mutableListOf<String>().also { out ->
            val a = o.optJSONArray("annotationTags") ?: JSONArray()
            for (i in 0 until a.length()) out.add(a.getString(i))
        }
        com.bookcon.app.data.local.AnnotationEntity(
            id = o.getString("id"),
            bookId = o.getString("bookId"),
            type = o.getString("type"),
            locatorJson = o.getString("locatorJson"),
            color = o.optString("color", "yellow"),
            note = o.optString("note", ""),
            annotationTags = tags,
            excerpt = o.optString("excerpt", ""),
            createdAt = optStr(o, "createdAt"),
            updatedAt = o.getString("updatedAt"),
            deletedAt = optStr(o, "deletedAt"),
            dirty = true,
        )
    }.getOrNull()

    private fun bookmarkFromJson(o: JSONObject): com.bookcon.app.data.local.BookmarkEntity? = runCatching {
        com.bookcon.app.data.local.BookmarkEntity(
            id = o.getString("id"),
            bookId = o.getString("bookId"),
            locatorJson = o.getString("locatorJson"),
            label = o.optString("label", ""),
            createdAt = o.optString("createdAt", o.getString("updatedAt")),
            updatedAt = o.getString("updatedAt"),
            deletedAt = optStr(o, "deletedAt"),
            dirty = true,
        )
    }.getOrNull()

    private fun settingsFromJson(s: JSONObject, cur: AppSettings): AppSettings = cur.copy(
        themeMode = s.optString("themeMode", cur.themeMode),
        storageMode = s.optString("storageMode", cur.storageMode),
        readerTheme = s.optString("readerTheme", cur.readerTheme),
        readerFontFamily = s.optString("readerFontFamily", cur.readerFontFamily),
        readerFontSizeSp = if (s.has("readerFontSizeSp")) s.getDouble("readerFontSizeSp").toFloat() else cur.readerFontSizeSp,
        readerPaginationMode = s.optString("readerPaginationMode", cur.readerPaginationMode),
        volumeKeyTurns = s.optBoolean("volumeKeyTurns", cur.volumeKeyTurns),
    )

    /**
     * Resolves `__VAULT__:base` placeholders written by [bookFromJson] to the real
     * staged path; returns true when the file is present on disk.
     */
    fun resolveVaultFile(context: Context, localFile: String?): File? {
        if (localFile == null || !localFile.startsWith("__VAULT__:")) return null
        val f = stagedFile(context, localFile.removePrefix("__VAULT__:"))
        return f.takeIf { it.exists() && it.length() > 0 }
    }
}

/**
 * DAO façade so [DataArchive] stays testable and database-agnostic.
 */
interface ArchiveSink {
    suspend fun existingBookUpdatedAt(id: String): String?
    suspend fun existingPosition(bookId: String): com.bookcon.app.data.local.PositionEntity?
    suspend fun existingAnnotation(id: String): com.bookcon.app.data.local.AnnotationEntity?
    suspend fun existingBookmark(id: String): com.bookcon.app.data.local.BookmarkEntity?
    suspend fun upsertBook(book: com.bookcon.app.data.local.BookEntity)
    suspend fun upsertPosition(position: com.bookcon.app.data.local.PositionEntity)
    suspend fun upsertAnnotation(annotation: com.bookcon.app.data.local.AnnotationEntity)
    suspend fun upsertBookmark(bookmark: com.bookcon.app.data.local.BookmarkEntity)
}
