package com.bookcon.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Floating read-aloud controls (PRD TTS-*): play/pause/stop pill shown while a
 * read-aloud session is active. Auto page-turn happens in the ViewModel.
 */
@Composable
fun ReadAloudBar(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.ttsState.collectAsStateWithLifecycle()
    if (!state.active) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { if (state.speaking) viewModel.pauseReadAloud() else viewModel.resumeReadAloud() }) {
                Icon(
                    if (state.speaking) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.speaking) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                state.error ?: (if (state.speaking) "Reading aloud…" else "Paused"),
                style = MaterialTheme.typography.labelLarge,
                color = state.error?.let { MaterialTheme.colorScheme.error }
                    ?: MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = viewModel::stopReadAloud) {
                Icon(Icons.Outlined.Close, contentDescription = "Stop")
            }
        }
    }
}
