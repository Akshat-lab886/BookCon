package com.bookcon.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tap-zone and volume-key page turns inside a PDF.
 *
 * The animated page-turn coordinator drives the Readium navigator view, which only
 * exists for EPUB. A PDF has no engine, so these turns reached a ViewModel method
 * that returned immediately — and because the tap-zone layer sits on top and
 * consumes the tap, a configured NEXT/PREV zone on a PDF swallowed the tap and did
 * nothing at all. PDFs now resolve the turn against the pager instead.
 */
class PdfTurnTargetTest {

    private fun fwd(current: Int, count: Int = 10) = pdfTurnTarget(current, count, forward = true)
    private fun back(current: Int, count: Int = 10) = pdfTurnTarget(current, count, forward = false)

    @Test
    fun forward_advances_one_page() {
        assertEquals(1, fwd(0))
        assertEquals(5, fwd(4))
        assertEquals(8, fwd(7))
    }

    @Test
    fun backward_retreats_one_page() {
        assertEquals(4, back(5))
        assertEquals(0, back(1))
    }

    @Test
    fun forward_past_the_last_page_stops_rather_than_wrapping() {
        // Wrapping would silently teleport the reader to the start of the book.
        assertNull(fwd(9, count = 10))
    }

    @Test
    fun backward_before_the_first_page_stops() {
        assertNull(back(0, count = 10))
    }

    @Test
    fun the_very_first_page_forwards_and_the_very_last_goes_back() {
        assertEquals(1, fwd(0, count = 2))
        assertEquals(0, back(1, count = 2))
    }

    @Test
    fun a_single_page_document_turns_nowhere_in_either_direction() {
        assertNull(fwd(0, count = 1))
        assertNull(back(0, count = 1))
    }

    @Test
    fun an_unknown_page_count_refuses_rather_than_guessing() {
        // Count 0 means the document is not laid out yet. Guessing a page would
        // send the reader somewhere arbitrary, so a turn simply does nothing.
        assertNull(fwd(0, count = 0))
        assertNull(back(0, count = 0))
    }

    @Test
    fun a_negative_count_is_treated_as_unknown() {
        assertNull(fwd(0, count = -3))
    }

    @Test
    fun an_unreported_current_page_starts_from_the_first() {
        // pdfCurrentPage is -1 until the pager reports its first page. Clamping
        // that to 0 means "we are on the first page", so a forward turn lands on
        // page 1…
        assertEquals(1, fwd(-1, count = 10))
        // …and a backward turn is correctly a no-op, because we are already at the
        // start. Landing on page 0 again would be a no-op with extra steps.
        assertNull(back(-1, count = 10))
    }

    @Test
    fun a_current_page_beyond_the_document_clamps_to_the_end() {
        // A stale page index left over from a re-opened book clamps to the last
        // page, so forward is at the end and does nothing, and backward steps off
        // it normally.
        assertNull(fwd(99, count = 10))
        assertEquals(8, back(99, count = 10))
    }
}
