package com.bookcon.app.screenshots

import com.bookcon.app.reader.Locators
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/**
 * Guards the table of contents.
 *
 * `forHref` builds a Locator by hand and hands it to Readium's `Locator.fromJSON`,
 * which is strict: it requires BOTH `href` and `type`, and `type` must parse as a
 * media type. It silently returns null otherwise, and `jumpToHref` then returns
 * without navigating — so the failure mode was a chapter tap that did nothing at
 * all, with no error anywhere. This test runs the real Readium parser.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocatorForHrefTest {

    @Test
    fun a_plain_chapter_href_produces_a_usable_locator() {
        val locator = Locators.forHref("chapter1.xhtml", title = "Chapter 1")
        assertNotNull("Readium rejected the locator, so TOC taps would do nothing", locator)
        assertEquals("chapter1.xhtml", locator!!.href.toString())
    }

    @Test
    fun a_fragment_href_keeps_its_fragment() {
        val locator = Locators.forHref("chapter1.xhtml#section2", title = "Section 2")
        assertNotNull(locator)
        assertTrue(
            "href should retain the fragment, was ${locator!!.href}",
            locator.href.toString().contains("section2"),
        )
    }

    @Test
    fun progression_survives_the_round_trip() {
        val locator = Locators.forHref("chapter1.xhtml", totalProgression = 0.42)
        assertNotNull(locator)
        assertEquals(0.42, locator!!.locations.totalProgression!!, 1e-6)
    }

    @Test
    fun the_title_survives_the_round_trip() {
        // Search groups fall back to a hit's own title when the href is not in the
        // table of contents. For a PDF that title is "Page 3" — without it the group
        // heading read "p3". So this has to actually survive Locator.fromJSON.
        val locator = Locators.forHref("/p3", title = "Page 3")
        assertNotNull(locator)
        assertEquals("Page 3", locator!!.title)
    }

    @Test
    fun an_absent_title_stays_absent_rather_than_becoming_empty() {
        // The fallback chain is TOC title -> hit title -> raw href; a blank title
        // would satisfy `?:` and show an empty heading.
        val locator = Locators.forHref("/p3")
        assertNotNull(locator)
        assertTrue("title should be null, was '${locator!!.title}'", locator.title.isNullOrBlank())
    }

    @Test
    fun a_relative_href_is_accepted() {
        // EPUB TOCs routinely use relative paths like "text/ch3.xhtml".
        val locator = Locators.forHref("text/ch3.xhtml", title = "Three")
        assertNotNull(locator)
        assertTrue(locator!!.href.toString().contains("ch3.xhtml"))
    }

    @Test
    fun the_built_locator_survives_serialisation() {
        // Whatever forHref produces has to round-trip through the same
        // toJsonString/fromJsonString path the app uses to persist positions,
        // or a TOC jump would still land nowhere.
        val original = Locators.forHref("chapter1.xhtml", title = "Chapter 1")
        assertNotNull(original)
        val json = Locators.toJsonString(original!!)
        val restored = Locators.fromJsonString(json)
        assertNotNull("a TOC locator must survive serialisation", restored)
        assertEquals(original.href.toString(), restored!!.href.toString())
    }
}
