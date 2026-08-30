package com.bookcon.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * BookCon v1.3 design tokens.
 *
 * Two-accent palette inspired by friendly bookstore UI references:
 *   brand/primary    → cobalt blue (header bars, active icons)
 *   brand/secondary  → warm orange (CTAs, promos)
 *
 * Surfaces are flat, near-white in light and near-black in dark; depth
 * comes from hairline borders, not shadows.
 */
object BrandColors {
    // Brand
    val Primary = Color(0xFF2F66F4)
    val PrimaryContainer = Color(0xFFE8EFFF)
    val OnPrimaryContainer = Color(0xFF0F2A6E)
    val Secondary = Color(0xFFFF6B35)
    val SecondaryContainer = Color(0xFFFFE2D2)
    val OnSecondaryContainer = Color(0xFF6E2A06)

    // Neutral / surface
    val Page = Color(0xFFFFFFFF)
    val Card = Color(0xFFFFFFFF)
    val Muted = Color(0xFFF4F6FA)
    val Divider = Color(0xFFEAECF1)

    val TextPrimary = Color(0xFF0F1623)
    val TextSecondary = Color(0xFF5A6478)

    // State
    val Success = Color(0xFF1FA868)
    val Error = Color(0xFFDC2626)

    // Dark
    val PrimaryDark = Color(0xFF5B8DFF)
    val PrimaryContainerDark = Color(0xFF1B2A55)
    val OnPrimaryContainerDark = Color(0xFFBFD0FF)
    val SecondaryDark = Color(0xFFFF8A5C)
    val SecondaryContainerDark = Color(0xFF552711)
    val OnSecondaryContainerDark = Color(0xFFFFCFB8)
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
    onSecondary = Color.White,
    secondaryContainer = BrandColors.SecondaryContainer,
    onSecondaryContainer = BrandColors.OnSecondaryContainer,
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
    onPrimary = Color(0xFF0E1A3A),
    primaryContainer = BrandColors.PrimaryContainerDark,
    onPrimaryContainer = BrandColors.OnPrimaryContainerDark,
    secondary = BrandColors.SecondaryDark,
    onSecondary = Color(0xFF3B1707),
    secondaryContainer = BrandColors.SecondaryContainerDark,
    onSecondaryContainer = BrandColors.OnSecondaryContainerDark,
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
