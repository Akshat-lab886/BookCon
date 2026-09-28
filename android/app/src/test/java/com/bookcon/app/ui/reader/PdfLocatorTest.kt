package com.bookcon.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PDF "locator" is not a Readium Locator — it is this app's own small JSON blob,
 * built by [pdfLocatorJson] and read back by [PDF_PAGE_HREF]. Two independent paths
 * depend on that round trip: the saved reading position, and opening a PDF
 * bookmark.
 *
 * The reading pattern used to be `"href":"/p(\d+)`, which required no space after
 * the colon. A locator that had been re-serialised with whitespace therefore
 * produced no match and the book silently reopened at page 1 — indistinguishable
 * from "my bookmark did nothing".
 */
class PdfLocatorTest {

    private fun pageOf(locator: String): Int? =
        PDF_PAGE_HREF.find(locator)?.groupValues?.get(1)?.toIntOrNull()

    @Test
    fun a_locator_round_trips_to_the_page_it_was_built_from() {
        // The href is deliberately ONE-based ("/p1" is the first page) while [page]
        // is the zero-based index used by PdfBook. openPdf reads the number back and
        // subtracts one. This test pins both halves of that boundary so neither side
        // can drift: the off-by-one is the whole failure mode here, and getting it
        // wrong silently reopens a PDF one page off — or on page 0.
        for (page in listOf(0, 1, 9, 99, 1234)) {
            val readBack = pageOf(pdfLocatorJson(page, 2000))
            assertEquals("href for zero-based page $page", page + 1, readBack)
            assertEquals("openPdf's conversion back to zero-based", page, readBack!! - 1)
        }
    }

    @Test
    fun the_page_is_one_based_in_json() {
        assertEquals(1, pageOf(pdfLocatorJson(0, 100)))
        assertEquals(100, pageOf(pdfLocatorJson(99, 100)))
    }

    @Test
    fun whitespace_around_the_colon_does_not_break_it() {
        assertEquals(42, pageOf("""{ "href" : "/p42", "title": "Page 42" }"""))
    }

    @Test
    fun the_progression_is_relative_to_the_page_count() {
        assertTrue(pdfLocatorJson(0, 200).contains("\"totalProgression\":0.005"))
        assertTrue(pdfLocatorJson(199, 200).contains("\"totalProgression\":1.0"))
    }

    @Test
    fun a_zero_page_count_does_not_divide_by_zero() {
        val json = pdfLocatorJson(0, 0)
        assertTrue("must not emit NaN or Infinity", !json.contains("NaN") && !json.contains("Infinity"))
        assertEquals(1, pageOf(json))
    }

    @Test
    fun a_non_pdf_locator_yields_nothing_rather_than_a_wrong_page() {
        assertEquals(null, pageOf("""{"href":"/chapter1","title":"One"}"""))
        assertEquals(null, pageOf(""))
    }
}
