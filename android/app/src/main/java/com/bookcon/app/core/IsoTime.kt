package com.bookcon.app.core

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Last-write-wins comparison for the `updatedAt` stamps this app stores.
 *
 * Two formats coexist in the same database. Local edits are stamped with
 * `OffsetDateTime.now(UTC).toString()`, which renders as `...Z`, while rows
 * arriving from the server are rendered `...+00:00`. Those are the same instant
 * but not the same string, and lexicographic comparison gets them wrong:
 *
 *     "2024-05-01T10:00:00Z" > "2024-05-01T10:00:00+00:00"   // both are 10:00
 *
 * because 'Z' (0x5A) sorts after '+' (0x2B). Comparing stamps as text therefore
 * resurrects stale rows or discards fresh ones, which is how a restore ends up
 * quietly keeping the wrong version of a highlight.
 *
 * Anything unparseable falls back to a string compare, so a malformed stamp still
 * orders deterministically instead of throwing mid-import.
 */
internal fun isNewerIso(candidate: String, stored: String): Boolean {
    val a = parseIso(candidate) ?: return candidate > stored
    val b = parseIso(stored) ?: return true
    return a.isAfter(b)
}

private fun parseIso(value: String): Instant? = try {
    OffsetDateTime.parse(value).toInstant()
} catch (_: Exception) {
    try {
        Instant.parse(value)
    } catch (_: Exception) {
        // Some stamps arrive as a local date-time with no zone; treat as UTC, which
        // is what the writers meant.
        try {
            java.time.LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
        } catch (_: Exception) {
            null
        }
    }
}

/** Convenience wrapper for call sites that read as "is this older than that". */
internal fun isOlderIso(candidate: String, stored: String): Boolean = isNewerIso(stored, candidate)
