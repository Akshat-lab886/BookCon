package com.bookcon.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * BookCon v2.2 design tokens (Oripio-style three-accent palette).
 *
 * Inspired by the Oripio bookstore Dribbble shot — friendly, soft, bookstore-y.
 *   brand/primary    → teal (header bars, hero, primary actions)
 *   brand/secondary  → soft pink (cards, secondary surfaces, accents)
 *   brand/tertiary   → warm yellow (badges, chips, highlights)
 *
 * Surfaces are flat and near-white in light / near-black in dark; depth
 * comes from hairline borders + soft pill shadows.
 */
object BrandColors {
    // Brand
    val Primary = Color(0xFF1FB8A8)         // teal
    val PrimaryContainer = Color(0xFFD6F4F0) // teal-tinted card bg
    val OnPrimaryContainer = Color(0xFF064E45)
    val Secondary = Color(0xFFEFA8C4)        // soft pink
    val SecondaryContainer = Color(0xFFFCE4EE)
    val OnSecondaryContainer = Color(0xFF6E1F44)
    val Tertiary = Color(0xFFFFC857)         // warm yellow
    val TertiaryContainer = Color(0xFFFFF1D1)
    val OnTertiaryContainer = Color(0xFF6E4A00)

    // Neutral / surface — cream/warm-white background (matches Oripio reference)
    val Page = Color(0xFFF8F4EE)
    val Card = Color(0xFFFFFFFF)
    val Muted = Color(0xFFF8F4EE)
    val Divider = Color(0xFFE6E1D8)

    val TextPrimary = Color(0xFF0F1623)
    val TextSecondary = Color(0xFF5A6478)

    // State
    val Success = Color(0xFF1FA868)
    val Error = Color(0xFFDC2626)

    // Dark
    val PrimaryDark = Color(0xFF1FB8A8)
    val PrimaryContainerDark = Color(0xFF093B36)
    val OnPrimaryContainerDark = Color(0xFF8EE9DC)
    val SecondaryDark = Color(0xFFEFA8C4)
    val SecondaryContainerDark = Color(0xFF551935)
    val OnSecondaryContainerDark = Color(0xFFFAD2E2)
    val TertiaryDark = Color(0xFFFFC857)
    val TertiaryContainerDark = Color(0xFF553E10)
    val OnTertiaryContainerDark = Color(0xFFFFE6B0)
    val PageDark = Color(0xFF0F1115)
    val CardDark = Color(0xFF1A1D24)
    val MutedDark = Color(0xFF161A22)
    val DividerDark = Color(0xFF262B36)
    val TextPrimaryDark = Color(0xFFF1F4F8)
    val TextSecondaryDark = Color(0xFFA4ADBC)
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = BrandColors.Primary,
    onPrimary = Color.White,
    primaryContainer = BrandColors.PrimaryContainer,
    onPrimaryContainer = BrandColors.OnPrimaryContainer,
    secondary = BrandColors.Secondary,
    onSecondary = Color(0xFF492035),
    secondaryContainer = BrandColors.SecondaryContainer,
    onSecondaryContainer = BrandColors.OnSecondaryContainer,
    tertiary = BrandColors.Tertiary,
    onTertiary = Color(0xFF402A00),
    tertiaryContainer = BrandColors.TertiaryContainer,
    onTertiaryContainer = BrandColors.OnTertiaryContainer,
    background = BrandColors.Muted,
    onBackground = BrandColors.TextPrimary,
    surface = BrandColors.Page,
    onSurface = BrandColors.TextPrimary,
    surfaceVariant = BrandColors.Muted,
    onSurfaceVariant = BrandColors.TextSecondary,
    outline = BrandColors.Divider,
    outlineVariant = BrandColors.Divider,
    error = BrandColors.Error,
    onError = Color.White,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = BrandColors.PrimaryDark,
    onPrimary = Color(0xFF002923),
    primaryContainer = BrandColors.PrimaryContainerDark,
    onPrimaryContainer = BrandColors.OnPrimaryContainerDark,
    secondary = BrandColors.SecondaryDark,
    onSecondary = Color(0xFF492035),
    secondaryContainer = BrandColors.SecondaryContainerDark,
    onSecondaryContainer = BrandColors.OnSecondaryContainerDark,
    tertiary = BrandColors.TertiaryDark,
    onTertiary = Color(0xFF402A00),
    tertiaryContainer = BrandColors.TertiaryContainerDark,
    onTertiaryContainer = BrandColors.OnTertiaryContainerDark,
    background = BrandColors.PageDark,
    onBackground = BrandColors.TextPrimaryDark,
    surface = BrandColors.CardDark,
    onSurface = BrandColors.TextPrimaryDark,
    surfaceVariant = BrandColors.MutedDark,
    onSurfaceVariant = BrandColors.TextSecondaryDark,
    outline = BrandColors.DividerDark,
    outlineVariant = BrandColors.DividerDark,
    error = Color(0xFFF87171),
    onError = Color(0xFF3F0A0A),
)

internal val LightColorsScheme: ColorScheme = LightColors
internal val DarkColorsScheme: ColorScheme = DarkColors
