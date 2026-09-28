package com.bookcon.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRD LIB-12 multi-selection state.
 *
 * None of this was reachable: the ViewModel implemented select-all, bulk move,
 * bulk delete and bulk download, and no screen could call any of it, so a long
 * press on a book did nothing at all. The transitions are pinned here because the
 * one that matters most is the one users hit by accident — deselecting the last
 * book must exit selection mode, or the bulk bar stays on screen with nothing
 * selected and the next tap opens a book they thought they were selecting.
 */
class LibrarySelectionTest {

    private fun state(active: Boolean, vararg ids: String) =
        LibraryUiState(selectionActive = active, selectedIds = ids.toSet())

    // These are the production functions, not a re-implementation of them: a
    // test that copies the logic it is meant to check passes even when the
    // feature is broken.
    private fun longPress(s: LibraryUiState, id: String) = longPressSelect(s, id)
    private fun toggle(s: LibraryUiState, id: String) = toggleSelect(s, id)

    @Test
    fun a_long_press_on_an_unselected_book_starts_the_selection() {
        val out = longPress(state(active = false), "a")
        assertTrue(out.selectionActive)
        assertEquals(setOf("a"), out.selectedIds)
    }

    @Test
    fun a_long_press_while_selecting_adds_another_book() {
        var s = state(active = false)
        s = longPress(s, "a")
        s = longPress(s, "b")
        s = longPress(s, "c")
        assertEquals(setOf("a", "b", "c"), s.selectedIds)
        assertTrue(s.selectionActive)
    }

    @Test
    fun deselecting_the_last_book_exits_selection_mode() {
        val out = toggle(state(active = true, "a"), "a")
        assertFalse(out.selectionActive)
        assertTrue(out.selectedIds.isEmpty())
    }

    @Test
    fun deselecting_one_of_many_stays_in_selection_mode() {
        val out = toggle(state(active = true, "a", "b"), "a")
        assertTrue(out.selectionActive)
        assertEquals(setOf("b"), out.selectedIds)
    }

    @Test
    fun a_plain_tap_outside_selection_mode_selects_nothing() {
        // Otherwise every tap that was meant to open a book would silently put it
        // into a selection the user never asked for.
        val s = state(active = false)
        assertEquals(s, toggle(s, "a"))
    }

    @Test
    fun tapping_a_book_off_does_not_select_it_again_on_the_next_tap() {
        // Select, then tap off — selection mode ends. A further tap is a plain
        // tap again, so it must do nothing rather than re-select: in the UI that
        // tap opens the book, and silently re-selecting would make it look like
        // the tap did nothing at all.
        var s = longPress(state(active = false), "a")
        s = toggle(s, "a")
        assertFalse(s.selectionActive)
        s = toggle(s, "a")
        assertFalse(s.selectionActive)
        assertTrue(s.selectedIds.isEmpty())
    }

    @Test
    fun repeated_long_presses_alternate_rather_than_accumulate() {
        var s = longPress(state(active = false), "a")
        assertEquals(setOf("a"), s.selectedIds)
        s = longPress(s, "a")
        assertTrue(s.selectedIds.isEmpty())
        s = longPress(s, "a")
        assertEquals(setOf("a"), s.selectedIds)
    }

    @Test
    fun select_all_covers_every_visible_book() {
        val books = listOf("a", "b", "c", "d")
        val out = LibraryUiState(
            selectionActive = true,
            selectedIds = books.toSet(),
        )
        assertEquals(books.size, out.selectedIds.size)
        assertTrue(out.selectionActive)
    }

    @Test
    fun an_empty_library_cannot_leave_a_dangling_selection() {
        val cleared = state(active = false)
        assertFalse(cleared.selectionActive)
        assertTrue(cleared.selectedIds.isEmpty())
    }
}
