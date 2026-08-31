package com.bookcon.app.ui.vocab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.AppTopBar
import com.bookcon.app.ui.components.EmptyState
import com.bookcon.app.ui.components.OutlinePillButton
import com.bookcon.app.ui.components.PillButton

/** Vocabulary notebook: due-card review + browse list + auto-capture toggle (PRD VOC-*).
 *  v1.3 redesign: blue AppTopBar, hairline-bordered cards, pill review buttons,
 *  EmptyState illustration when no words saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabScreen(
    onBack: () -> Unit,
    viewModel: VocabViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val due by viewModel.due.collectAsStateWithLifecycle()
    val revealed by viewModel.revealed.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Vocabulary",
                subtitle = if (entries.isNotEmpty()) "${entries.size} word${if (entries.size == 1) "" else "s"} saved" else null,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Auto-capture toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = settings.vocabCaptureEnabled,
                    onClick = { viewModel.setCaptureEnabled(!settings.vocabCaptureEnabled) },
                    label = { Text("Auto-capture") },
                )
            }

            if (due.isNotEmpty()) {
                val card = due.first()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    ReviewCard(
                        word = card.word,
                        definition = card.definition,
                        revealed = revealed,
                        dueCount = due.size,
                        onReveal = viewModel::toggleRevealed,
                        onAgain = { viewModel.gradeCurrent(false) },
                        onGotIt = { viewModel.gradeCurrent(true) },
                    )
                }
            }

            if (entries.isEmpty()) {
                EmptyState(
                    title = "No saved words yet",
                    message = "Look up a word while reading and save it here to review later.",
                    illustration = Icons.Outlined.MenuBook,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    items(entries, key = { it.word }) { entry ->
                        WordRow(
                            word = entry.word,
                            definition = entry.definition,
                            onDelete = { viewModel.remove(entry.word) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    word: String,
    definition: String,
    revealed: Boolean,
    dueCount: Int,
    onReveal: () -> Unit,
    onAgain: () -> Unit,
    onGotIt: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Review",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                word.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            if (revealed) {
                Text(
                    definition,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinePillButton(
                        text = "Again",
                        onClick = onAgain,
                    )
                    PillButton(
                        text = "Got it",
                        onClick = onGotIt,
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            } else {
                Spacer(Modifier.height(12.dp))
                PillButton(
                    text = "Tap to reveal",
                    onClick = onReveal,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "$dueCount due now",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WordRow(
    word: String,
    definition: String,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                word,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (definition.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    definition,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete $word",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
