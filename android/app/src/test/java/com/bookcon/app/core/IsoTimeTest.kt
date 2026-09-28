package com.bookcon.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local edits are stamped `OffsetDateTime.now(UTC).toString()` → `...Z`.
 * Rows pulled from the server arrive as `...+00:00`. Both live in the same table
 * at the same time, and the archive import decides which version of a highlight
 * to keep by comparing those stamps.
 *
 * Compared as text, "2024-05-01T10:00:00Z" sorts AFTER
 * "2024-05-01T10:00:00+00:00" even though they are the same instant, because 'Z'
 * is 0x5A and '+' is 0x2B. A restore would then keep the wrong version of an
 * annotation and report success.
 */
class IsoTimeTest {

    @Test
    fun the_same_instant_in_two_formats_is_not_considered_newer() {
        val client = "2024-05-01T10:00:00Z"
        val server = "2024-05-01T10:00:00+00:00"
        // A raw text compare would say the client stamp is later; it is not.
        assertTrue("precondition: text order is wrong", client > server)
        assertFalse(isNewerIso(client, server))
        assertFalse(isNewerIso(server, client))
    }

    @Test
    fun a_genuinely_later_stamp_wins_in_both_formats() {
        assertTrue(isNewerIso("2024-05-01T10:00:01Z", "2024-05-01T10:00:00+00:00"))
        assertTrue(isNewerIso("2024-05-01T10:00:01+00:00", "2024-05-01T10:00:00Z"))
        assertFalse(isNewerIso("2024-05-01T10:00:00Z", "2024-05-01T10:00:01+00:00"))
    }

    @Test
    fun a_non_utc_offset_is_respected() {
        // 12:00+05:00 is 07:00Z, so it is EARLIER than 08:00Z despite the text
        // looking later in the day.
        assertTrue(isNewerIso("2024-05-01T08:00:00Z", "2024-05-01T12:00:00+05:00"))
        assertFalse(isNewerIso("2024-05-01T12:00:00+05:00", "2024-05-01T08:00:00Z"))
    }

    @Test
    fun sub_second_precision_is_kept_when_present() {
        assertTrue(isNewerIso("2024-05-01T10:00:00.500Z", "2024-05-01T10:00:00.100Z"))
    }

    @Test
    fun a_zoned_local_stamp_is_read_as_utc() {
        assertTrue(isNewerIso("2024-05-01T10:00:01", "2024-05-01T10:00:00Z"))
    }

    @Test
    fun an_unparseable_stamp_falls_back_to_a_text_compare_instead_of_throwing() {
        // Import must not blow up on one malformed row and lose the whole restore.
        assertTrue(isNewerIso("zzz", "aaa"))
        assertFalse(isNewerIso("aaa", "zzz"))
        assertTrue(isNewerIso("2024-05-01T10:00:00Z", "not-a-date"))
    }

    @Test
    fun is_older_is_the_mirror_of_is_newer() {
        assertTrue(isOlderIso("2024-05-01T09:00:00Z", "2024-05-01T10:00:00+00:00"))
        assertFalse(isOlderIso("2024-05-01T11:00:00Z", "2024-05-01T10:00:00+00:00"))
    }
}
