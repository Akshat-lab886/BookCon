@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.bookcon.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.bookcon.app.R

/**
 * BookCon typography, matched to the reference screenshots.
 *
 * Display family: **Playfair Display** — a high-contrast transitional serif
 * with fine, elegant serifs. It carries the hero headline ("Your Book Library /
 * Make Your Own Space"), the section headers ("Categories", "Recently added")
 * and the empty-state titles. The reference sets these light-to-medium; the
 * previous Georgia Bold read as a heavy slab and was visibly wrong.
 *
 * Body family: **Poppins** — a wide geometric sans. It carries the bold
 * subtitles, the search placeholder, chip labels, buttons and the bottom nav.
 * The platform default (Roboto) is noticeably narrower than the reference.
 */
private val Display = FontFamily(
    Font(
        R.font.playfair_display,
        FontWeight.Normal,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        R.font.playfair_display,
        FontWeight.Medium,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        R.font.playfair_display,
        FontWeight.SemiBold,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        R.font.playfair_display,
        FontWeight.Bold,
        FontStyle.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

private val Body = FontFamily(
    Font(R.font.poppins_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.poppins_semibold, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.poppins_bold, FontWeight.Bold, FontStyle.Normal),
)

val BookConTypography: Typography = Typography(
    // Display family — the hero headline is set light, not bold.
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = (-0.3).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 30.sp, lineHeight = 37.sp, letterSpacing = (-0.2).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 27.sp, lineHeight = 34.sp,
    ),
    // Section headers (Categories, Recently added) — serif, medium
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 23.sp, lineHeight = 30.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 21.sp, lineHeight = 28.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 19.sp, lineHeight = 26.sp,
    ),
    // Body family — titles, chip labels, nav
    titleLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 21.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 19.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp,
    ),
)
