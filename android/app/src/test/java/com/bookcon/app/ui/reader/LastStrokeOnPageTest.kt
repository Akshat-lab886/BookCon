package com.bookcon.app.ui.reader

import com.bookcon.app.reader.PdfInkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which ink stroke "undo" removes in the notebook.
 *
 * Undo dropped the last stroke in the whole notebook. Strokes are stamped with the
 * page they were drawn on, so undoing while looking at a page you had not drawn on
 * erased ink from a different page — and because that stroke is not the one under
 * your finger, the loss is invisible until you flip back a page and find something
 * missing.
 */
class LastStrokeOnPageTest {

    private fun stroke(id: String, page: Int) = PdfInkStroke(
        id = id,
        page = page,
        color = "#FFFFFF",
        width = 2f,
        points = listOf(0f, 0f, 1f, 1f),
        mode = "pen",
    )

    private fun undo(strokes: List<PdfInkStroke>, page: Int): List<String> {
        val target = lastStrokeOnPage(strokes, page) ?: return strokes.map { it.id }
        return strokes.filterNot { it.id == target.id }.map { it.id }
    }

    @Test
    fun undo_removes_the_most_recent_stroke_on_the_page_in_front_of_you() {
        val strokes = listOf(stroke("a", 0), stroke("b", 0), stroke("c", 0))
        assertEquals(listOf("a", "b"), undo(strokes, page = 0))
    }

    @Test
    fun undo_does_not_reach_into_another_page() {
        // The regression: on page 1 with nothing drawn here, undo used to remove the
        // last stroke of page 0.
        val strokes = listOf(stroke("a", 0), stroke("b", 0))
        assertEquals(listOf("a", "b"), undo(strokes, page = 1))
    }

    @Test
    fun undo_on_a_page_with_ink_leaves_other_pages_untouched() {
        val strokes = listOf(stroke("a", 0), stroke("b", 1), stroke("c", 1), stroke("d", 0))
        assertEquals(listOf("a", "b", "d"), undo(strokes, page = 1))
    }

    @Test
    fun undo_works_when_interleaved_pages_are_older_than_the_current_one() {
        // Drawing order is not page order: a user can return to page 0 and add to it
        // after being on page 1. Undo must still target what is on screen.
        val strokes = listOf(stroke("a", 0), stroke("b", 1), stroke("c", 0))
        assertEquals("c", lastStrokeOnPage(strokes, page = 0)?.id)
        assertEquals("b", lastStrokeOnPage(strokes, page = 1)?.id)
    }

    @Test
    fun an_empty_notebook_has_nothing_to_undo() {
        assertNull(lastStrokeOnPage(emptyList(), page = 0))
    }

    @Test
    fun a_page_with_no_ink_has_nothing_to_undo() {
        assertNull(lastStrokeOnPage(listOf(stroke("a", 0)), page = 7))
    }

    @Test
    fun undoing_the_only_stroke_empties_the_page() {
        assertEquals(emptyList<String>(), undo(listOf(stroke("a", 3)), page = 3))
    }
}
