package com.bookcon.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

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
 *
 * v1.3 ships new brand tokens (cobalt primary, orange secondary), retuned type,
 * and a friendlier shape scale. No screen has been migrated yet — see
 * docs/UI_REDESIGN_PLAN.md.
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
    val colors = if (darkTheme) DarkColorsScheme else LightColorsScheme
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
    MaterialTheme(
        colorScheme = colors,
        typography = BookConTypography,
        shapes = BookConShapes,
        content = content,
    )
}
