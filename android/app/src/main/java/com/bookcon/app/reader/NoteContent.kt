package com.bookcon.app.reader

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * A typed text fragment on a notebook page (v1.5 notes). Styles are applied at
 * render time so old notes keep rendering if new styles are added later.
 */
@Serializable
data class NoteTextBlock(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    /** "body" | "heading" | "title" — picks the Compose style + size. */
    val style: String = "body",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

/** One notebook page's full canvas: typed blocks in order + freehand ink strokes. */
@Serializable
data class NoteContent(
    val blocks: List<NoteTextBlock> = emptyList(),
    val strokes: List<PdfInkStroke> = emptyList(),
) {
    val isEmpty: Boolean get() = blocks.isEmpty() && strokes.isEmpty()

    companion object {
        val EMPTY = NoteContent()
    }
}

/** One stroke style preset for the notebook pen (mirrors PDF ink palette). */
@Serializable
data class NoteStrokeStyle(
    @SerialName("w") val widthDp: Float,
)

object NoteContentJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(content: NoteContent): String = json.encodeToString(NoteContent.serializer(), content)

    fun decode(raw: String): NoteContent? =
        runCatching { json.decodeFromString(NoteContent.serializer(), raw) }.getOrNull()
}
