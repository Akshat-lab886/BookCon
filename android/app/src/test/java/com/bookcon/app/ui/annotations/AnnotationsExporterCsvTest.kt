package com.bookcon.app.ui.annotations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Annotation CSV export is meant to be opened in a spreadsheet, so two things matter
 * that the original escaping did not handle:
 *
 *  - a bare CR is a line break for some parsers and splits one highlight into two rows;
 *  - a cell starting with = + - @ tab or CR executes as a formula. A highlight reading
 *    a HYPERLINK formula runs on open — textbook CSV injection, and this file is
 *    explicitly produced for spreadsheet apps.
 */
class AnnotationsExporterCsvTest {

    private val escape: (String) -> String = AnnotationsExporter::escapeCsv

    @Test
    fun plain_text_is_quoted_and_unchanged() {
        assertEquals("\"hello world\"", escape("hello world"))
    }

    @Test
    fun embedded_quotes_are_doubled() {
        assertEquals("\"she said \"\"hi\"\"\"", escape("she said \"hi\""))
    }

    @Test
    fun a_bare_carriage_return_is_flattened() {
        val out = escape("line one\rline two")
        assertTrue("a bare CR must not survive: $out", !out.contains('\r'))
    }

    @Test
    fun windows_newlines_are_flattened() {
        val out = escape("a\r\nb")
        assertTrue("CRLF must not survive: $out", !out.contains('\r'))
    }

    @Test
    fun a_formula_injection_is_neutralised() {
        val dangerous = listOf(
            "=1+1",
            "+1+1",
            "-1+1",
            "@SUM(A1)",
            "\t=cmd",
            "\r=cmd",
        )
        for (candidate in dangerous) {
            val out = escape(candidate)
            assertTrue(
                "must be defused with a leading apostrophe: [$candidate] -> $out",
                out.startsWith("\"'"),
            )
        }
    }

    @Test
    fun a_hyperlink_formula_from_a_highlight_is_defused() {
        val attack = "=HYPERLINK(\"http://evil.example\",\"click\")"
        val out = escape(attack)
        val inner = out.trim('"')
        assertTrue("spreadsheet formula must not survive verbatim: $out", !inner.startsWith("="))
    }

    @Test
    fun ordinary_numbers_and_punctuation_are_not_mangled() {
        assertEquals("\"3.14\"", escape("3.14"))
        assertEquals("\"a - b\"", escape("a - b"))
    }

    @Test
    fun every_escaped_cell_is_still_quoted() {
        val samples = listOf("plain", "with \"quotes\"", "line\nbreak", "=formula", "")
        for (sample in samples) {
            val out = escape(sample)
            assertTrue("must be quoted: [$sample] -> $out", out.startsWith("\""))
        }
    }
}
