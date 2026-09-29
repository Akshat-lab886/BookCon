package com.bookcon.app.core

/**
 * Prepares page text for the platform speech engine.
 *
 * The engine is good at reading prose and bad at reading the things books are full
 * of. Handing it a page verbatim makes it sound wrong in ways that have nothing to
 * do with the voice itself: `1st` becomes "one S T" or "first" depending on the
 * engine, `pp. 12-15` becomes "P P twelve dash fifteen", `&` is skipped or read
 * as "et cetera", and a citation marker like `[12]` is read as "twelve" in the
 * middle of a sentence. Every one of those is a pronunciation decision the app can
 * make, so this object makes them once, consistently, instead of leaving them to
 * whichever engine happens to be installed.
 *
 * Deliberately conservative: it rewrites only what is unambiguous. A number is
 * never converted to a guessed value, and an abbreviation is only expanded where
 * the expansion cannot be wrong in a book.
 */
object SpeechText {

    /** Longest single utterance. Engines truncate or fail well before this. */
    const val MAX_UTTERANCE_CHARS = 1800

    /** Over this, a pause is inserted where the text breaks into a paragraph. */
    private const val PARAGRAPH_BREAK = "\n\n"

    /**
     * Expansions that are safe everywhere. Deliberately excludes anything
     * context-dependent: "St" is a saint or a street, "No" is a number or a word,
     * "vol" is a volume or a verb, and "cf" is compare or the confer prefix.
     */
    private val ABBREVIATIONS = linkedMapOf(
        "mr." to "Mister", "mrs." to "Missus", "ms." to "Miss",
        "dr." to "Doctor", "prof." to "Professor",
        "st." to "Saint", "jr." to "Junior", "sr." to "Senior",
        "etc." to "et cetera", "vs." to "versus",
        "approx." to "approximately", "dept." to "department",
        "est." to "established", "fig." to "figure",
        "min." to "minutes", "max." to "maximum",
    )

    /** A single capital, or an initial run like "J.R.R." — read as letters. */
    private val INITIALS = Regex("(?<![A-Za-z])[A-Z](?:\\.[A-Z])+\\.?(?![a-z])")

    /** A citation or footnote marker, e.g. "[12]", "[3, p. 45]". Dropped. */
    private val FOOTNOTE = Regex("\\[\\d+[^\\]]{0,24}]")

    private val URL = Regex("https?://\\S+|www\\.\\S+")
    private val NUMBER = Regex("\\b\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?\\b")

    /**
     * Normalises [text] for speech.
     *
     * Order matters. Footnotes and URLs go first, because their digits must not be
     * seen by the later passes, and the ordinal pass runs before the year pass so
     * "1984" is judged as a year before anything treats it as a plain quantity.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        var out = text
        out = FOOTNOTE.replace(out, "")
        out = URL.replace(out, " link ")
        out = ABBREVIATIONS.entries.fold(out) { acc, (from, to) ->
            // Word-bounded, case-insensitive, and only outside a word, so "Dr." in
            // "drive." is not touched and "Fig." mid-sentence is.
            acc.replace(Regex("(?<![A-Za-z])${Regex.escape(from)}", RegexOption.IGNORE_CASE), to)
        }
        out = INITIALS.replace(out) { m -> spacedInitials(m.value) }
        out = NUMBER.replace(out) { m -> speakableNumber(m.value) }
        out = expandOrdinals(out)
        out = expandYears(out)
        out = normaliseWhitespace(out)
        return out.trim()
    }

    /** Turns "J.R.R." into "J R R" so the engine reads letters, not "jay are are". */
    private fun spacedInitials(raw: String): String {
        val letters = raw.replace(".", "").map { it }.filter { it.isLetterOrDigit() }
        if (letters.size <= 1) return raw
        return letters.joinToString(" ") + "."
    }

    /**
     * "12,500" -> "12,500" with the separator dropped. The engine handles plain
     * grouped digits better than it handles the comma, which it may read as a pause
     * or skip. Decimal points are left alone: "3.5" is read correctly as-is.
     */
    private fun speakableNumber(raw: String): String = raw.replace(",", "")

    /** "1st" -> "first", "22nd" -> "twenty second". Only the suffixes engines miss. */
    private fun expandOrdinals(text: String): String {
        val ordinal = Regex("(?<![\\d])(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
        return ordinal.replace(text) { m ->
            val digits = m.groupValues[1]
            val suffix = m.groupValues[2].lowercase()
            // 11th/12th/13th are exceptions, as are 111th and friends.
            val lastTwo = digits.takeLast(2)
            val teen = lastTwo.length == 2 && lastTwo.toInt() in 11..13
            val ends = when {
                teen -> false
                suffix == "st" -> digits.endsWith("1")
                suffix == "nd" -> digits.endsWith("2")
                suffix == "rd" -> digits.endsWith("3")
                else -> digits.endsWith("0") || digits.endsWith("1") ||
                    digits.endsWith("2") || digits.endsWith("3") ||
                    digits.endsWith("4") || digits.endsWith("5") ||
                    digits.endsWith("6") || digits.endsWith("7") ||
                    digits.endsWith("8") || digits.endsWith("9")
            }
            if (ends) "$digits ${ordinalWord(suffix)}" else m.value
        }
    }

    /**
     * A four-digit number in 1100..1999 is nearly always a year in a book, and
     * engines read "1984" as "one thousand nine hundred and eighty-four". Saying
     * "nineteen eighty-four" is what a person does.
     *
     * The trailing guard rejects a following digit, and a dot *only when a digit
     * follows it*, so a sentence-ending period ("published in 1984.") is still a
     * year while a decimal like "11984.5" is left alone. Rejecting every dot
     * instead — the obvious version of this — matches nothing at all, because
     * years almost always end a sentence or a clause.
     */
    private fun expandYears(text: String): String {
        val year = Regex("(?<![\\d,.])(\\d{4})(?!\\d)(?!\\.\\d)")
        return year.replace(text) { m ->
            val n = m.groupValues[1].toInt()
            if (n in 1100..1999) {
                val century = n / 100
                val rest = n % 100
                if (rest in 0..9) "$century oh $rest" else "$century $rest"
            } else {
                m.value
            }
        }
    }

    private fun ordinalWord(suffix: String): String = when (suffix) {
        "st" -> "first"
        "nd" -> "second"
        "rd" -> "third"
        else -> "fourth"
    }

    /**
     * Collapses runs of whitespace and turns a paragraph break into a full stop,
     * because a bare newline is either ignored or read as nothing at all, leaving
     * two paragraphs to run together with no pause.
     */
    private fun normaliseWhitespace(text: String): String =
        text.replace(PARAGRAPH_BREAK, ". ")
            .replace(Regex("[\\t\\u00a0\\u200b]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\.{3,}"), "…")

    /**
     * Splits [text] into utterances the engine can actually finish.
     *
     * A whole page sent as one call is the single most common reason narration
     * sounds wrong: engines have a practical limit well below a page, and past it
     * they either truncate mid-sentence or drop the tail silently, so the reader
     * stops early with no error anywhere. Splitting on sentence boundaries and
     * keeping the remainder in the tail means each piece ends where a person would
     * breathe, and no piece can be cut in half.
     */
    fun chunk(text: String, maxChars: Int = MAX_UTTERANCE_CHARS): List<String> {
        val flat = normaliseWhitespace(text).trim()
        // A page break is common in extracted book text, and normalising one turns
        // a run of newlines into ". " — leaving punctuation alone, which the engine
        // then dutifully speaks as a full stop of silence. Nothing is speakable
        // unless there is at least one letter or digit in it.
        if (flat.none { it.isLetterOrDigit() }) return emptyList()
        if (flat.length <= maxChars) return listOf(flat)

        val out = mutableListOf<String>()
        var start = 0
        while (start < flat.length) {
            val remaining = flat.length - start
            if (remaining <= maxChars) {
                out.add(flat.substring(start))
                break
            }
            val window = flat.substring(start, start + maxChars)
            // Prefer a sentence end, then a clause break, then any space. A cut is
            // only accepted if it is far enough in that the piece is worth keeping.
            val boundary = listOf('.', '!', '?', '…', ';', ',', ':')
                .map { window.lastIndexOf(it) }
                .filter { it > maxChars / 3 }
                .maxOrNull()
                ?: window.lastIndexOf(' ').takeIf { it > maxChars / 3 }
            val cut = if (boundary != null) boundary + 1 else maxChars
            out.add(flat.substring(start, start + cut).trim())
            start += cut
        }
        return out.filter { it.isNotEmpty() }
    }

    /** Convenience: normalise and split in one step. */
    fun prepare(text: String, maxChars: Int = MAX_UTTERANCE_CHARS): List<String> =
        chunk(normalize(text), maxChars)
}
