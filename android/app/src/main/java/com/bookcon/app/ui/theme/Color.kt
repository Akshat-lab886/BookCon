package com.bookcon.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * BookCon design tokens, sampled directly from the reference screenshots.
 *
 * Every value below was read off the real PNGs rather than guessed, so the
 * rendered UI matches them pixel for pixel.
 */
object BrandColors {
    // ---- Dark theme (Books / Bookmarks / Profile) ----
    val Page = Color(0xFF0F1014)
    val Card = Color(0xFF12151B)
    val CardRaised = Color(0xFF141923)

    // Deep forest green: selected nav pill + settings icon circles.
    val Green = Color(0xFF114838)
    val GreenText = Color(0xFFF2F3F7)

    val TextPrimary = Color(0xFFF2F3F7)
    val TextSecondary = Color(0xFFBABFC9)
    val TextTertiary = Color(0xFF87888C)

    // Salmon "Import books" pill.
    val Salmon = Color(0xFFFF8A5B)
    val OnSalmon = Color(0xFF0F1014)

    val SignOut = Color(0xFFE87171)
    // The empty-state book / bookmark glyphs are pure black on the dark page.
    val EmptyGlyph = Color(0xFF000000)

    val Divider = Color(0xFF1C2028)

    val Error = Color(0xFFE87171)
    val Success = Color(0xFF114838)

    // ---- Book detail (dark maroon-plum gradient, NOT purple) ----
    val DetailTop = Color(0xFF2A1420)
    val DetailMid = Color(0xFF25111C)
    val DetailBottom = Color(0xFF1F0F19)
    val DetailChip = Color(0xFF22111B)
    val DetailCard = Color(0xFF3A1A29)
    val DetailCoverBg = Color(0xFF1D1720)
    val DetailTitle = Color(0xFFF9D4E5)
    val DetailLabel = Color(0xFF8F848C)
    val DetailButton = Color(0xFFF0F4F7)
    val DetailOnButton = Color(0xFF21101A)
    val DetailStar = Color(0xFFF18D75)
    val DetailDot = Color(0xFF6B5560)

    // ---- Home (bright mint) ----
    val Mint = Color(0xFF52D7B5)
    val MintDark = Color(0xFF50D8B5)
    val MintInk = Color(0xFF0F1018)
    val AvatarPlum = Color(0xFF6A3953)

    // Aliases kept so existing call sites keep compiling.
    val Primary = Green
    val PrimaryContainer = Green
    val OnPrimaryContainer = GreenText
    val Secondary = Salmon
    val SecondaryContainer = Salmon
    val OnSecondaryContainer = OnSalmon
    val Tertiary = DetailCard
    val TertiaryContainer = DetailChip
    val OnTertiaryContainer = DetailTitle
    val Muted = Page
    val TextPrimaryDark = TextPrimary
    val TextSecondaryDark = TextSecondary
    val DividerDark = Divider
    val CardDark = Card
    val MutedDark = Page
    val PageDark = Page
    val PrimaryDark = Green
    val PrimaryContainerDark = Green
    val OnPrimaryContainerDark = GreenText
    val SecondaryDark = Salmon
    val SecondaryContainerDark = Salmon
    val OnSecondaryContainerDark = OnSalmon
    val TertiaryDark = DetailCard
    val TertiaryContainerDark = DetailChip
    val OnTertiaryContainerDark = DetailTitle
}

private val LightColors: ColorScheme = lightColorScheme(
    primary = BrandColors.Green,
    onPrimary = BrandColors.GreenText,
    primaryContainer = BrandColors.Green,
    onPrimaryContainer = BrandColors.GreenText,
    secondary = BrandColors.Salmon,
    onSecondary = BrandColors.OnSalmon,
    secondaryContainer = BrandColors.Salmon,
    onSecondaryContainer = BrandColors.OnSalmon,
    tertiary = BrandColors.DetailCard,
    onTertiary = BrandColors.DetailTitle,
    tertiaryContainer = BrandColors.DetailChip,
    onTertiaryContainer = BrandColors.DetailTitle,
    background = BrandColors.Page,
    onBackground = BrandColors.TextPrimary,
    surface = BrandColors.Card,
    onSurface = BrandColors.TextPrimary,
    surfaceVariant = BrandColors.CardRaised,
    onSurfaceVariant = BrandColors.TextSecondary,
    outline = BrandColors.Divider,
    outlineVariant = BrandColors.Divider,
    error = BrandColors.Error,
    onError = Color.White,
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = BrandColors.Green,
    onPrimary = BrandColors.GreenText,
    primaryContainer = BrandColors.Green,
    onPrimaryContainer = BrandColors.GreenText,
    secondary = BrandColors.Salmon,
    onSecondary = BrandColors.OnSalmon,
    secondaryContainer = BrandColors.Salmon,
    onSecondaryContainer = BrandColors.OnSalmon,
    tertiary = BrandColors.DetailCard,
    onTertiary = BrandColors.DetailTitle,
    tertiaryContainer = BrandColors.DetailChip,
    onTertiaryContainer = BrandColors.DetailTitle,
    background = BrandColors.Page,
    onBackground = BrandColors.TextPrimary,
    surface = BrandColors.Card,
    onSurface = BrandColors.TextPrimary,
    surfaceVariant = BrandColors.CardRaised,
    onSurfaceVariant = BrandColors.TextSecondary,
    outline = BrandColors.Divider,
    outlineVariant = BrandColors.Divider,
    error = BrandColors.Error,
    onError = Color(0xFF3F0A0A),
)

internal val LightColorsScheme: ColorScheme = LightColors
internal val DarkColorsScheme: ColorScheme = DarkColors
