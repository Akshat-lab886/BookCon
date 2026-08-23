package com.bookcon.app.ui.reader

/**
 * RD-2: six bundled open-font choices. v1 maps each name onto the closest Android system
 * family; TODO(bundle-fonts): ship the actual open-license TTFs under assets/fonts and register
 * them as navigator font-family declarations so every name resolves to its true face.
 */
data class FontOption(
    val label: String,
    val cssFamily: String,
)

object ReaderFonts {
    val OPTIONS: List<FontOption> = listOf(
        FontOption("Literata", "serif"),
        FontOption("PT Serif", "serif"),
        FontOption("Noto Serif", "serif"),
        FontOption("Noto Sans", "sans-serif"),
        FontOption("Atkinson Hyperlegible", "sans-serif"),
        FontOption("OpenDyslexic", "sans-serif"),
    )

    /** Maps a stored font name to the CSS font-family handed to the EPUB navigator. */
    fun cssFor(label: String): String =
        OPTIONS.firstOrNull { it.label.equals(label, ignoreCase = true) }?.cssFamily ?: "serif"

    fun isValid(label: String): Boolean = OPTIONS.any { it.label.equals(label, ignoreCase = true) }
}
