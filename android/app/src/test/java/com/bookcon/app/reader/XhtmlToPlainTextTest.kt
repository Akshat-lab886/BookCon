package com.bookcon.app.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text produced here feeds two things a user would notice immediately:
 * read-aloud narration, and AI page prompts.
 *
 * Stripping tags is not enough on its own — the bodies of `<style>` and
 * `<script>` are text, so a chapter with an embedded stylesheet used to have its
 * CSS declarations spoken out loud and handed to the summariser as if it were
 * prose.
 */
class XhtmlToPlainTextTest {

    @Test
    fun a_stylesheet_is_removed_including_its_body() {
        val xhtml = """
            <html><head>
            <style type="text/css">
            body { margin: 0; font-family: serif; }
            p.indent { text-indent: 2em; }
            </style>
            </head><body><p>Real text.</p></body></html>
        """.trimIndent()
        val out = xhtmlToPlainText(xhtml)
        assertFalse("CSS leaked into the text: $out", out.contains("margin"))
        assertFalse(out.contains("font-family"))
        assertEquals("Real text.", out)
    }

    @Test
    fun a_multiline_stylesheet_is_still_removed_whole() {
        // The regression this guards: without DOT_MATCHES_ALL the block matched
        // only to the first newline and the rest of the CSS survived.
        val out = xhtmlToPlainText(
            "<style>\n.a{x:1}\n.b{y:2}\n.c{z:3}\n</style><p>Body</p>",
        )
        assertEquals("Body", out)
    }

    @Test
    fun script_bodies_are_removed() {
        val out = xhtmlToPlainText(
            "<body><p>Before</p><script>var x = 1; alert('hi');</script><p>After</p></body>",
        )
        assertEquals("Before After", out)
    }

    @Test
    fun html_comments_are_removed() {
        val out = xhtmlToPlainText("<p>Kept</p><!-- editor note: TODO -->")
        assertEquals("Kept", out)
    }

    @Test
    fun an_escaped_tag_in_the_prose_stays_text() {
        // Entity decoding happens after the non-content pass, so this must NOT turn
        // into a script block.
        val out = xhtmlToPlainText("<p>Use &lt;script&gt; carefully</p>")
        assertEquals("Use <script> carefully", out)
    }

    @Test
    fun ordinary_markup_still_yields_its_text() {
        val xhtml = """
            <body>
              <h1>Chapter One</h1>
              <p>It was a <em>dark</em> and <strong>stormy</strong> night.</p>
              <img src="plate.png" alt="A plate"/>
            </body>
        """.trimIndent()
        val out = xhtmlToPlainText(xhtml)
        assertTrue(out, out.contains("Chapter One"))
        assertTrue(out, out.contains("It was a dark and stormy night."))
    }

    @Test
    fun entities_are_decoded() {
        val out = xhtmlToPlainText("<p>Tom &amp; Jerry &lt;3 &quot;quotes&quot; &#39;apostrophes&#39;</p>")
        assertEquals("Tom & Jerry <3 \"quotes\" 'apostrophes'", out)
    }

    @Test
    fun whitespace_and_newlines_are_collapsed() {
        val out = xhtmlToPlainText("<p>one\n\n   two</p>\n<p>three</p>")
        assertEquals("one two three", out)
    }

    @Test
    fun a_style_block_with_uppercase_tags_is_removed() {
        val out = xhtmlToPlainText("<STYLE>.a{color:red}</STYLE><p>Text</p>")
        assertEquals("Text", out)
    }

    @Test
    fun plain_text_passes_through_untouched() {
        assertEquals("Just words.", xhtmlToPlainText("Just words."))
    }
}
