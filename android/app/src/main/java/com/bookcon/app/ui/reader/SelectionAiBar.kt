package com.bookcon.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Selection AI actions (PRD SEL-AI): appears when the reader reports selected text;
 * offers Explain / Translate / Summarize over that passage using the BYOK provider.
 * Results render inline in a compact card above the bottom chrome.
 */
@Composable
fun SelectionAiBar(
    viewModel: ReaderViewModel,
    onDefine: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.selectionAi.collectAsStateWithLifecycle()
    val sel = state.selectedText ?: return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (sel.length > 60) sel.take(60) + "…" else sel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::dismissSelectionAi) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { viewModel.runSelectionAi("explain") },
                    label = { Text("Explain") },
                    leadingIcon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
                )
                AssistChip(
                    onClick = { viewModel.runSelectionAi("translate") },
                    label = { Text("Translate") },
                    leadingIcon = { Icon(Icons.Outlined.GTranslate, contentDescription = null) },
                )
                AssistChip(
                    onClick = { viewModel.runSelectionAi("summarize") },
                    label = { Text("Summarize") },
                )
                if (onDefine != null && sel.split(Regex("\\s+")).size <= 4) {
                    AssistChip(
                        onClick = { onDefine(sel) },
                        label = { Text("Define") },
                    )
                }
            }

            when {
                state.loading -> {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                }
                state.error != null -> {
                    Spacer(Modifier.height(6.dp))
                    Text(state.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.runSelectionAi(state.action ?: "explain") }) { Text("Retry") }
                }
                state.result != null -> {
                    Spacer(Modifier.height(8.dp))
                    Text(state.result!!, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
