package com.bookcon.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Shape scale for BookCon v1.3.
 *
 * - small  = 8 dp  → chips, search field
 * - medium = 14 dp → cards (the dominant shape)
 * - large  = 20 dp → sheets, dialogs
 * - extraLarge = 28 dp → top app bar, hero cards
 */
val BookConShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
