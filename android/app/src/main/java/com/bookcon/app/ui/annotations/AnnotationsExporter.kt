package com.bookcon.app.ui.annotations

import android.content.Context
import android.content.Intent
import com.bookcon.app.data.local.AnnotationEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Builds shareable annotation exports (PRD ANN-7) in Markdown / CSV / plain text and
 * hands them to the system share sheet via ACTION_SEND.
 */
object AnnotationsExporter {

    /** One flattened, format-neutral entry. */
    data class Entry(
        val id: String,
        val bookId: String,
        val bookTitle: String,
        val type: String,
        val color: String,
        val excerpt: String,
        val note: String,
        val tags: List<String>,
        val href: String?,
        val progression: Double?,
        val updatedAt: String,
    )

    enum class Format(val label: String) {
        MARKDOWN("Markdown"),
        CSV("CSV"),
        TXT("Plain text"),
    }

    /** Flattens entities against a bookId → title map. */
    fun buildEntries(
        annotations: List<AnnotationEntity>,
        titlesById: Map<String, String>,
    ): List<Entry> = annotations.map { a ->
        val (href, progression) = locatorRef(a.locatorJson)
        Entry(
            id = a.id,
            bookId = a.bookId,
            bookTitle = titlesById[a.bookId] ?: "Unknown book",
            type = a.type,
            color = a.color,
            excerpt = a.excerpt,
            note = a.note,
            tags = a.annotationTags,
            href = href,
            progression = progression,
            updatedAt = a.updatedAt,
        )
    }

    fun markdown(entries: List<Entry>): String = buildString {
        appendLine("# BookCon annotations")
        appendLine()
        if (entries.isEmpty()) {
            appendLine("_No annotations._")
            return@buildString
        }
        entries.groupBy { it.bookTitle }.forEach { (title, list) ->
            appendLine("## $title")
            appendLine()
            list.forEach { e ->
                appendLine("### ${e.type.replaceFirstChar { it.uppercase() }} · ${e.color} — ${e.updatedAt.take(10)}")
                if (e.excerpt.isNotBlank()) {
                    e.excerpt.lines().forEach { appendLine("> $it") }
                }
                if (e.note.isNotBlank()) {
                    appendLine()
                    appendLine(e.note)
                }
                if (e.tags.isNotEmpty()) {
                    appendLine()
                    appendLine("Tags: " + e.tags.joinToString(", ") { "#${it.replace(' ', '_')}" })
                }
                appendLocation(this, e)
                appendLine()
            }
        }
    }

    fun csv(entries: List<Entry>): String {
        val header = listOf("id", "book", "type", "color", "date", "progression", "href", "excerpt", "note", "tags")
        return buildString {
            appendLine(header.joinToString(",") { escapeCsv(it) })
            entries.forEach { e ->
                appendLine(
                    listOf(
                        e.id,
                        e.bookTitle,
                        e.type,
                        e.color,
                        e.updatedAt,
                        e.progression?.toString().orEmpty(),
                        e.href.orEmpty(),
                        e.excerpt,
                        e.note,
                        e.tags.joinToString("; "),
                    ).joinToString(",") { escapeCsv(it) },
                )
            }
        }
    }

    fun plainText(entries: List<Entry>): String = buildString {
        appendLine("BookCon annotations")
        appendLine("===================")
        entries.forEach { e ->
            appendLine()
            appendLine("[${e.bookTitle}] ${e.type} (${e.color}) — ${e.updatedAt.take(10)}")
            if (e.excerpt.isNotBlank()) appendLine("“${e.excerpt}”")
            if (e.note.isNotBlank()) appendLine("Note: ${e.note}")
            if (e.tags.isNotEmpty()) appendLine("Tags: ${e.tags.joinToString(", ")}")
            appendLocation(this, e)
        }
    }

    fun build(format: Format, entries: List<Entry>): String = when (format) {
        Format.MARKDOWN -> markdown(entries)
        Format.CSV -> csv(entries)
        Format.TXT -> plainText(entries)
    }

    /** ACTION_SEND share sheet (text/plain). */
    fun shareText(context: Context, subject: String, text: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share annotations"))
        }
    }

    // --- locator helpers -------------------------------------------------------------

    private val lenientJson = Json { ignoreUnknownKeys = true }

    /** Extracts (href, progression) from a serialized Readium Locator. */
    fun locatorRef(locatorJson: String): Pair<String?, Double?> {
        val obj = runCatching { lenientJson.parseToJsonElement(locatorJson).jsonObject }.getOrNull()
            ?: return null to null
        val href = (obj["href"] as? JsonPrimitive)?.content
        val progression = ((obj["locations"] as? JsonObject)?.get("progression"))
            ?.let { (it as? JsonPrimitive)?.doubleOrNull }
        return href to progression
    }

    private fun appendLocation(sb: StringBuilder, e: Entry) {
        if (e.href == null && e.progression == null) return
        sb.append("Location: ")
        e.href?.let { sb.append(it) }
        if (e.href != null && e.progression != null) sb.append(" ")
        e.progression?.let { sb.append("(progression ${(it * 100).toInt()}%)") }
        sb.appendLine()
    }

    private fun escapeCsv(value: String): String =
        "\"" + value.replace("\"", "\"\"").replace("\n", "\\n") + "\""
}
