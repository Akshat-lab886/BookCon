package com.bookcon.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Jumping to a stored highlight or bookmark inside a PDF.
 *
 * PDFs have no Readium engine to navigate — `openPdf` clears it because the toolkit
 * ships no PDF navigator at 3.1.0 — so these jumps were routed through `jumpTo`,
 * which returns immediately when there is no engine. Tapping a highlight in a PDF
 * did nothing: no movement, no error.
 *
 * The page is read out of the `/pN` href, which is one-based, and handed to the
 * pager, which is zero-based. That boundary is the whole risk here.
 */
class PdfPageFromLocatorTest {

    private fun page(json: String, count: Int = 100) = pdfPageFromLocator(json, count)

    @Test
    fun the_first_page_hrefs_as_p1_and_is_page_zero() {
        assertEquals(0, page("""{"href":"/p1"}"""))
    }

    @Test
    fun a_page_number_maps_across_the_one_to_zero_boundary() {
        assertEquals(11, page("""{"href":"/p12"}"""))
        assertEquals(0, page("""{"href":"/p1","locations":{"totalProgression":0.01}}"""))
        assertEquals(99, page("""{"href":"/p100"}"""))
    }

    @Test
    fun a_locator_written_by_the_apps_own_format_round_trips() {
        // What notifyClosing() writes must read back to the same page.
        val json = pdfLocatorJson(41, 200) // 0-based page 41
        assertEquals(41, page(json, 200))
    }

    @Test
    fun whitespace_around_the_colon_does_not_break_it() {
        // Re-serialised locators put a space in; the old pattern demanded none and
        // silently reopened at page 1.
        assertEquals(4, page("""{"href" : "/p5"}"""))
    }

    @Test
    fun a_page_beyond_the_document_is_refused() {
        assertNull(page("""{"href":"/p101"}""", count = 100))
    }

    @Test
    fun a_zero_or_negative_page_is_refused() {
        assertNull(page("""{"href":"/p0"}"""))
    }

    @Test
    fun a_locator_with_no_page_is_refused_rather_than_guessed() {
        assertNull(page("""{"href":"chapter1.xhtml","type":"application/xhtml+xml"}"""))
        assertNull(page("{}"))
        assertNull(page(""))
    }

    @Test
    fun a_null_locator_is_refused() {
        assertNull(pdfPageFromLocator(null, 100))
    }

    @Test
    fun an_unknown_page_count_still_resolves_the_page() {
        // A not-yet-rendered book should still move to the stored page rather than
        // refusing; only an out-of-range answer is rejected.
        assertEquals(4, page("""{"href":"/p5"}""", count = 0))
    }

    @Test
    fun an_epub_locator_is_not_mistaken_for_a_page() {
        assertNull(page("""{"href":"text/ch3.xhtml","type":"application/xhtml+xml"}"""))
    }
}
