package com.bookcon.app.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an unlabelled bookmark shows in the bookmarks list.
 *
 * A bookmark is stored as `(engineLocator?.title ?: chapterTitle).orEmpty()`, so one
 * saved before the navigator reports a title — or in a book whose EPUB has no
 * chapter metadata — carries a blank label. The list used to fall back to the raw
 * locator, putting JSON on screen as the description of the bookmark.
 */
class HumanBookmarkLabelTest {

    @Test
    fun a_real_label_is_shown_unchanged() {
        assertEquals(
            "Chapter 4",
            humanBookmarkLabel("Chapter 4", """{"href":"/p12"}"""),
        )
    }

    @Test
    fun a_blank_label_never_leaks_the_locator() {
        val out = humanBookmarkLabel("", """{"href":"/p12","type":"application/pdf"}""")
        assertEquals("Page 12", out)
        assert(!out.contains("href"))
        assert(!out.contains("{"))
    }

    @Test
    fun a_whitespace_only_label_counts_as_blank() {
        assertEquals("Page 7", humanBookmarkLabel("   ", """{"href":"/p7"}"""))
    }

    @Test
    fun a_pdf_page_number_is_recovered_from_the_locator() {
        assertEquals("Page 1", humanBookmarkLabel("", """{"href":"/p1"}"""))
        assertEquals("Page 240", humanBookmarkLabel("", """{"href":"/p240"}"""))
    }

    @Test
    fun spacing_in_the_locator_does_not_defeat_it() {
        assertEquals("Page 5", humanBookmarkLabel("", """{"href" : "/p5"}"""))
    }

    @Test
    fun an_epub_locator_falls_back_to_something_honest() {
        // No page to name, and no raw JSON either.
        val out = humanBookmarkLabel("", """{"href":"chapter1.xhtml","type":"text/html"}""")
        assertEquals("Saved bookmark", out)
    }

    @Test
    fun a_missing_or_malformed_locator_falls_back_to_something_honest() {
        assertEquals("Saved bookmark", humanBookmarkLabel("", ""))
        assertEquals("Saved bookmark", humanBookmarkLabel("", "not json at all"))
        assertEquals("Saved bookmark", humanBookmarkLabel("", """{"href":"/notanumber"}"""))
    }

    @Test
    fun a_nonsensical_page_number_does_not_reach_the_user() {
        // "/p0" cannot be a page; showing "Page 0" would be its own small lie.
        assertEquals("Saved bookmark", humanBookmarkLabel("", """{"href":"/p0"}"""))
    }

    @Test
    fun a_label_is_trimmed_on_the_way_through() {
        assertEquals("Chapter 4", humanBookmarkLabel("  Chapter 4  ", ""))
    }
}
