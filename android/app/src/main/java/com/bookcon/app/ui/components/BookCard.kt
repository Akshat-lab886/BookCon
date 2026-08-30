package com.bookcon.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Vertical book card used in carousels, recommendations, search grids.
 *
 * Layout: 2:3 cover on top + title + optional subtitle + optional progress bar.
 * Cards have flat 0 dp elevation; depth comes from a 1 px outline border so the
 * design matches the reference's "almost no shadow" feel.
 */
@Composable
fun BookCard(
    title: String,
    coverUrl: String?,
    serverUrl: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Double? = null,
    format: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.68f)) {
                BookCover(
                    coverUrl = coverUrl,
                    title = title,
                    serverUrl = serverUrl,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.68f),
                    cornerRadius = 12.dp,
                )
                if (format != null) {
                    FormatChip(
                        format,
                        modifier = Modifier
                            .align(androidx.compose.ui.Alignment.TopStart)
                            .padding(6.dp),
                    )
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null && progress > 0.0) {
                    LinearProgressIndicator(
                        progress = { (progress / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
