package com.bookcon.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Replaces Material `TopAppBar` for chrome surfaces (Library, Settings, Stats, etc).
 *
 * The reference design uses a solid cobalt header bar with white title text and
 * 1–3 trailing icon buttons. The bottom corners are gently rounded so it sits
 * on top of the page surface without looking like a system bar.
 *
 * @param title screen title; rendered as [MaterialTheme.typography.headlineSmall].
 * @param subtitle optional secondary line; shown under the title in muted style.
 * @param onBack optional back arrow; renders only if provided.
 * @param actions trailing icon slots; pass `@Composable` actions from the caller.
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    background: Color = MaterialTheme.colorScheme.primary,
    foreground: Color = Color.White,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = foreground,
                    )
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = foreground,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = foreground.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
            actions()
        }
    }
}
