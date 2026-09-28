package com.bookcon.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-book search over a PDF (RD-11).
 *
 * The PDF path has no Readium engine — `openPdf` clears it, because the toolkit
 * ships no PDF navigator at 3.1.0 — so `search()` bailed out before doing anything
 * and every search in a PDF returned no results at all, while the reader panel sat
 * there fully built waiting for hits that could never arrive.
 *
 * The walk is parameterised on the text source so these rules can be pinned without
 * a real PDF document.
 */
class SearchPdfPagesTest {

    /** Three pages; the term appears on pages 0 and 2, twice on page 2. */
    private val pages = mapOf(
        0 to ("Page 1" to "The indemnity clause begins here."),
        1 to ("Page 2" to "Nothing of interest on this page."),
        2 to ("Page 3" to "The indemnity is mutual. So is the indemnity clause."),
    )

    private fun search(vararg args: Any) = searchPdfPages(
        query = args[0] as String,
        pageCount = 3,
        pageText = { i -> pages[i] },
        maxTotal = if (args.size > 1) args[1] as Int else 200,
        maxPerPage = if (args.size > 2) args[2] as Int else 20,
    )

    @Test
    fun hits_are_found_across_pages() {
        val hits = search("indemnity")
        assertEquals(3, hits.size)
        assertEquals(listOf(0, 2, 2), hits.map { it.page })
    }

    @Test
    fun a_page_label_is_carried_for_grouping() {
        assertEquals("Page 1", search("indemnity").first().label)
    }

    @Test
    fun hits_carry_an_excerpt_with_context() {
        val hit = search("mutual").single()
        assertTrue(hit.excerpt.contains("The indemnity is mutual"))
    }

    @Test
    fun matching_ignores_case() {
        assertEquals(3, search("INDEMNITY").size)
    }

    @Test
    fun a_blank_query_searches_nothing() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun a_term_that_is_absent_yields_nothing() {
        assertTrue(search("arbitration").isEmpty())
    }

    @Test
    fun a_page_with_no_extractable_text_is_skipped_not_fatal() {
        val hits = searchPdfPages(
            query = "indemnity",
            pageCount = 3,
            pageText = { i -> if (i == 1) null else pages[i] },
        )
        assertEquals(3, hits.size)
    }

    @Test
    fun a_blank_page_yields_no_hits() {
        val hits = searchPdfPages(
            query = "indemnity",
            pageCount = 3,
            pageText = { i -> if (i == 1) ("Page 2" to "   ") else pages[i] },
        )
        assertEquals(3, hits.size)
    }

    @Test
    fun one_dense_page_cannot_consume_the_whole_budget() {
        // The regression this guards: with only a global cap, a single page of dense
        // text (a contract, a glossary) would return everything and the other pages
        // would never be searched.
        val dense = mapOf(0 to ("Page 1" to "clause ".repeat(100)), 1 to ("Page 2" to "clause at last"))
        val hits = searchPdfPages("clause", 2, { i -> dense[i] }, maxTotal = 200, maxPerPage = 5)
        assertEquals(6, hits.size)
        assertEquals(5, hits.count { it.page == 0 })
        assertEquals(1, hits.count { it.page == 1 })
    }

    @Test
    fun the_total_is_capped() {
        val dense = (0 until 50).associateWith { "Page ${it + 1}" to "clause ".repeat(20) }
        val hits = searchPdfPages("clause", 50, { i -> dense[i] }, maxTotal = 30, maxPerPage = 20)
        assertEquals(30, hits.size)
    }

    @Test
    fun a_document_with_no_pages_is_safe() {
        assertTrue(searchPdfPages("indemnity", 0, { null }).isEmpty())
    }
}
