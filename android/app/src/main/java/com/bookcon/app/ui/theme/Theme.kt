package com.bookcon.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

private val Ink = Color(0xFF10131A)
private val Ember = Color(0xFFE76F51)
private val Teal = Color(0xFF2A9D8F)

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Ember,
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFEFB),
    surfaceVariant = Color(0xFFF0EBE2),
    onPrimary = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Teal,
    secondary = Ember,
    background = Color(0xFF10131A),
    surface = Color(0xFF171B24),
    surfaceVariant = Color(0xFF232833),
)

/** Reader themes per PRD RD-5: Light, Sepia, Dark, Black (+custom later). */
data class ReaderTheme(val bg: Color, val fg: Color, val isDark: Boolean)

val ReaderThemes = mapOf(
    "light" to ReaderTheme(Color(0xFFFFFFFF), Color(0xFF1A1C20), false),
    "sepia" to ReaderTheme(Color(0xFFF5EDDE), Color(0xFF4A4136), false),
    "dark" to ReaderTheme(Color(0xFF171B24), Color(0xFFD6DAE2), true),
    "black" to ReaderTheme(Color(0xFF000000), Color(0xFFB9BEC9), true),
)

/**
 * App theme. `themeMode` per PRD appearance setting: auto | light | dark | black | sepia.
 * black/sepia map onto the dark/light color schemes here; the reader surface uses
 * [ReaderThemes] for the full reading-specific palette.
 */
@Composable
fun BookConTheme(
    themeMode: String = "auto",
    darkTheme: Boolean = when (themeMode) {
        "light", "sepia" -> false
        "dark", "black" -> true
        else -> isSystemInDarkTheme()
    },
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
    MaterialTheme(colorScheme = colors, content = content)
}
