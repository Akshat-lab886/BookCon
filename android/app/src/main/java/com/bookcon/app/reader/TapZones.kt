package com.bookcon.app.reader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RD-8: configurable 3×3 tap-zone grid over the reading surface, persisted as JSON in DataStore.
 * Row-major order: cells 0..2 = top row, 3..5 = middle row, 6..8 = bottom row.
 */
enum class TapAction {
    PREV_PAGE,
    NEXT_PAGE,
    TOGGLE_CHROME,
    NONE,
}

data class TapZoneGrid(val cells: List<TapAction>) {

    init {
        require(cells.size == CELLS) { "TapZoneGrid needs exactly $CELLS actions" }
    }

    fun actionFor(row: Int, column: Int): TapAction =
        cells.getOrNull(row * GRID_SIZE + column) ?: TapAction.NONE

    fun toJson(): String = buildJsonObject {
        put(
            "cells",
            JsonArray(cells.map { JsonPrimitive(it.name) }),
        )
    }.toString()

    companion object {
        const val GRID_SIZE = 3
        const val CELLS = GRID_SIZE * GRID_SIZE

        /** Default right-handed layout: left third = back, center = chrome, right third = forward. */
        fun rightHanded(): TapZoneGrid = TapZoneGrid(
            listOf(
                TapAction.PREV_PAGE, TapAction.NONE, TapAction.NEXT_PAGE,
                TapAction.PREV_PAGE, TapAction.TOGGLE_CHROME, TapAction.NEXT_PAGE,
                TapAction.PREV_PAGE, TapAction.NONE, TapAction.NEXT_PAGE,
            ),
        )

        /** Left-handed preset mirrored for thumb reach. */
        fun leftHanded(): TapZoneGrid = TapZoneGrid(
            listOf(
                TapAction.NEXT_PAGE, TapAction.NONE, TapAction.PREV_PAGE,
                TapAction.NEXT_PAGE, TapAction.TOGGLE_CHROME, TapAction.PREV_PAGE,
                TapAction.NEXT_PAGE, TapAction.NONE, TapAction.PREV_PAGE,
            ),
        )

        fun fromJson(raw: String?): TapZoneGrid {
            if (raw.isNullOrBlank()) return rightHanded()
            return try {
                val obj = Json.parseToJsonElement(raw)
                val names = (obj as? kotlinx.serialization.json.JsonObject)?.get("cells") as? JsonArray
                if (names == null || names.size != CELLS) {
                    rightHanded()
                } else {
                    TapZoneGrid(
                        names.map { element ->
                            (element as? JsonPrimitive)?.content?.let { name ->
                                TapAction.entries.firstOrNull { it.name == name }
                            } ?: TapAction.NONE
                        },
                    )
                }
            } catch (_: IllegalArgumentException) {
                rightHanded()
            }
        }
    }
}
