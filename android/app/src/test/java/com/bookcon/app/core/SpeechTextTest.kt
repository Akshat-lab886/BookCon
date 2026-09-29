package com.bookcon.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the speech engine is actually handed.
 *
 * A page of prose is the easy part. These are the cases where handing the engine
 * the text verbatim produces something that does not sound like a person reading,
 * regardless of which voice is selected.
 */
class SpeechTextTest {

    // --- abbreviations ------------------------------------------------------

    @Test
    fun common_titles_are_spelled_out() {
        assertTrue(SpeechText.normalize("Dr. Smith arrived.").contains("Doctor"))
        assertTrue(SpeechText.normalize("Mr. and Mrs. Jones").contains("Mister"))
        assertTrue(SpeechText.normalize("Prof. Klein").contains("Professor"))
    }

    @Test
    fun an_abbreviation_inside_a_word_is_not_rewritten() {
        // "drive." ends in the letters of "Dr" but is not the abbreviation.
        assertFalse(SpeechText.normalize("They drive. Then they stopped.").contains("Doctor"))
    }

    // --- numbers ------------------------------------------------------------

    @Test
    fun ordinals_gain_the_word_engines_skip() {
        assertTrue(SpeechText.normalize("the 1st chapter").contains("1 first"))
        assertTrue(SpeechText.normalize("on the 2nd day").contains("2 second"))
        assertTrue(SpeechText.normalize("the 3rd part").contains("3 third"))
    }

    @Test
    fun teens_keep_their_suffix_because_the_ordinal_is_wrong() {
        // 11th is eleventh, not eleven-first.
        assertTrue(SpeechText.normalize("the 11th hour").contains("11th"))
        assertTrue(SpeechText.normalize("the 12th day").contains("12th"))
        assertTrue(SpeechText.normalize("the 13th time").contains("13th"))
    }

    @Test
    fun a_year_is_read_as_a_year_not_as_a_quantity() {
        val out = SpeechText.normalize("It was published in 1984.")
        assertTrue("got: $out", out.contains("19 84"))
        assertFalse("the engine would read it as a thousands group: $out", out.contains("1984"))
    }

    @Test
    fun a_year_inside_a_longer_number_is_left_alone() {
        // 11984 is not a year and must not be rewritten as one.
        assertTrue(SpeechText.normalize("code 11984").contains("11984"))
    }

    @Test
    fun thousands_separators_are_dropped_for_the_engine() {
        val out = SpeechText.normalize("about 12,500 people")
        assertTrue(out.contains("12500"))
        assertFalse(out.contains(","))
    }

    @Test
    fun a_decimal_point_survives() {
        assertTrue(SpeechText.normalize("3.5 percent").contains("3.5"))
    }

    // --- initials -----------------------------------------------------------

    @Test
    fun initials_are_spaced_so_they_are_read_as_letters() {
        val out = SpeechText.normalize("J.R.R. Tolkien wrote it.")
        assertTrue("got: $out", out.contains("J R R"))
    }

    @Test
    fun an_acronym_that_is_just_caps_is_left_readable() {
        // "NASA" is one word the engine handles; it must not become "N A S A".
        val out = SpeechText.normalize("NASA sent it")
        assertTrue("got: $out", out.contains("NASA"))
    }

    // --- markers, links -----------------------------------------------------

    @Test
    fun footnote_markers_are_removed() {
        val out = SpeechText.normalize("The claim is contested[12] in places.")
        assertFalse("got: $out", out.contains("[12]"))
    }

    @Test
    fun a_url_is_not_read_character_by_character() {
        val out = SpeechText.normalize("See https://example.com/page for more.")
        assertFalse("got: $out", out.contains("example.com/page"))
    }

    // --- flow ---------------------------------------------------------------

    @Test
    fun a_paragraph_break_becomes_a_pause_rather_than_nothing() {
        val out = SpeechText.normalize("First paragraph.\n\nSecond paragraph.")
        assertTrue("a bare newline is ignored by most engines: $out", out.contains(". "))
    }

    @Test
    fun runs_of_whitespace_collapse() {
        assertEquals("a b c", SpeechText.normalize("a \t  b\u00a0c"))
    }

    @Test
    fun ordinary_prose_is_left_alone() {
        val sentence = "She opened the book and began to read."
        assertEquals(sentence, SpeechText.normalize(sentence))
    }

    // --- chunking -----------------------------------------------------------

    @Test
    fun a_short_passage_is_one_utterance() {
        assertEquals(1, SpeechText.chunk("A single short sentence.").size)
    }

    @Test
    fun a_long_page_is_split_into_several_utterances() {
        val page = "This is a sentence in a long page. ".repeat(200)
        val parts = SpeechText.chunk(page, maxChars = 300)
        assertTrue("expected a split, got ${parts.size}", parts.size > 1)
    }

    @Test
    fun no_piece_exceeds_the_limit() {
        val page = "Another sentence goes here. ".repeat(300)
        val parts = SpeechText.chunk(page, maxChars = 400)
        assertTrue(parts.all { it.length <= 400 })
    }

    @Test
    fun splitting_loses_no_text() {
        val page = "Words and sentences, joined. ".repeat(120)
        val rejoined = SpeechText.chunk(page, maxChars = 250).joinToString(" ")
        assertEquals(
            "a split must not drop or duplicate characters",
            page.filter { !it.isWhitespace() },
            rejoined.filter { !it.isWhitespace() },
        )
    }

    @Test
    fun a_piece_is_preferred_to_end_on_a_sentence_boundary() {
        val page = "One two three four five. ".repeat(40)
        val parts = SpeechText.chunk(page, maxChars = 200)
        assertTrue(
            "expected sentence ends, got ${parts.first().takeLast(20)}",
            parts.first().trimEnd().endsWith("."),
        )
    }

    @Test
    fun empty_input_yields_nothing_to_say() {
        assertTrue(SpeechText.chunk("").isEmpty())
        assertTrue(SpeechText.chunk("   \n\n  ").isEmpty())
        assertTrue(SpeechText.prepare("").isEmpty())
    }

    @Test
    fun an_unbroken_run_of_characters_still_splits_without_losing_any() {
        // No spaces anywhere: the boundary search finds nothing and the fallback cut
        // has to work, or the passage would be dropped or looped forever.
        val blob = "x".repeat(1000)
        val parts = SpeechText.chunk(blob, maxChars = 250)
        assertEquals(1000, parts.sumOf { it.length })
        assertTrue(parts.all { it.length <= 250 })
    }
}
