package com.bookcon.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-book search (RD-11).
 *
 * This searched nothing at all before: the EPUB engine's `search` returned an
 * empty list while the panel's grouping and jump-to-hit UI was already built. The
 * matcher is pure so the behaviour can be pinned without a publication.
 */
class FindTextMatchesTest {

    private val text = "It was the best of times. It was the worst of times. " +
        "It was the age of wisdom. Nothing else matters here."

    @Test
    fun finds_every_occurrence_not_just_the_first() {
        val hits = findTextMatches("times", text)
        assertEquals(2, hits.size)
    }

    @Test
    fun matching_ignores_case() {
        assertEquals(2, findTextMatches("TIMES", text).size)
        assertEquals(1, findTextMatches("worst", text).size)
    }

    @Test
    fun a_repeated_word_does_not_loop_on_its_own_opening() {
        // The resume point must be past the match, or "aa" in "aaaa" re-finds the
        // same index forever and the search never returns. Matches do not overlap,
        // so "aaaa" yields two, not three.
        assertEquals(2, findTextMatches("aa", "aaaa").size)
        assertEquals(3, findTextMatches("aa", "aaaaaa").size)
    }

    @Test
    fun a_blank_query_returns_nothing() {
        assertTrue(findTextMatches("", text).isEmpty())
        assertTrue(findTextMatches("   ", text).isEmpty())
    }

    @Test
    fun a_query_that_is_not_present_returns_nothing() {
        assertTrue(findTextMatches("dragon", text).isEmpty())
    }

    @Test
    fun a_match_inside_a_word_is_still_found() {
        // Readers expect literal substring behaviour, not whole-word matching.
        assertEquals(1, findTextMatches("worst", "The worsted hour.").size)
    }

    @Test
    fun the_excerpt_shows_surrounding_context() {
        val hit = findTextMatches("wisdom", text, excerptRadius = 20).single()
        assertTrue("no context: ${hit.excerpt}", hit.excerpt.contains("age of wisdom"))
        assertTrue("no context: ${hit.excerpt}", hit.excerpt.length > "wisdom".length)
    }

    @Test
    fun the_excerpt_is_elided_at_the_edges_but_not_in_the_middle() {
        val atStart = findTextMatches("Alpha", "Alpha beta gamma delta", excerptRadius = 5).single()
        assertTrue("no leading ellipsis expected: ${atStart.excerpt}", !atStart.excerpt.startsWith("…"))

        val atEnd = findTextMatches("delta", "alpha beta gamma delta", excerptRadius = 5).single()
        assertTrue("no trailing ellipsis expected: ${atEnd.excerpt}", !atEnd.excerpt.endsWith("…"))

        val inMiddle = findTextMatches("gamma", "alpha beta gamma delta epsilon", excerptRadius = 5).single()
        assertTrue("expected leading ellipsis: ${inMiddle.excerpt}", inMiddle.excerpt.startsWith("…"))
        assertTrue("expected trailing ellipsis: ${inMiddle.excerpt}", inMiddle.excerpt.endsWith("…"))
    }

    @Test
    fun progression_lands_between_zero_and_one_and_in_reading_order() {
        val hits = findTextMatches("times", text)
        for (h in hits) {
            assertTrue("out of range: ${h.progression}", h.progression in 0f..1f)
        }
        // The first "times" comes before the second in the text, so it must not
        // report a later progression.
        assertTrue(hits[0].progression < hits[1].progression)
    }

    @Test
    fun progression_reflects_position_within_the_resource() {
        val near = findTextMatches("a", "a" + "x".repeat(1000)).single()
        val far = findTextMatches("a", "x".repeat(1000) + "a").single()
        assertTrue(near.progression < 0.01f)
        assertTrue(far.progression > 0.99f)
    }

    @Test
    fun results_are_capped_so_a_common_word_cannot_stall_the_panel() {
        val many = "cat ".repeat(5000)
        assertEquals(50, findTextMatches("cat", many, maxMatches = 50).size)
    }

    @Test
    fun a_match_at_the_very_start_and_end_does_not_throw() {
        assertEquals(1, findTextMatches("start", "start of it").size)
        assertEquals(1, findTextMatches("end", "the very end").size)
    }

    @Test
    fun searching_an_empty_resource_is_safe() {
        assertTrue(findTextMatches("anything", "").isEmpty())
    }
}
