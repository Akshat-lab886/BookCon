package com.bookcon.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * PDF night mode (PRD PDF-NIGHT): pages render through an invert-luminance
 * ColorFilter applied directly on the page [androidx.compose.foundation.Image]
 * (see [nightPageFilter]), so white paper reads as dark without touching layout or
 * gesture code. A warm amber veil ([PdfWarmVeil]) sits above as a soft tint.
 */
fun nightPageFilter(enabled: Boolean): ColorFilter? =
    if (!enabled) {
        null
    } else {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 1f,
                    0f, -1f, 0f, 0f, 1f,
                    0f, 0f, -1f, 0f, 1f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }

/** Warm amber veil above night-inverted pages; strength follows warmth 0..100. */
@Composable
fun PdfWarmVeil(enabled: Boolean, warmth: Int) {
    val veil = remember(warmth) { Color(0xFFFF9329).copy(alpha = warmth / 100f * 0.22f) }
    if (!enabled || veil.alpha <= 0f) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color = veil),
    )
}
