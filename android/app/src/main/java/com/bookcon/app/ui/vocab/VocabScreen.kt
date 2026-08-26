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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Vocabulary notebook: due-card review + browse list + auto-capture toggle (PRD VOC-*). */
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
            TopAppBar(
                title = { Text("Vocabulary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${entries.size} words saved",
                    style = MaterialTheme.typography.titleMedium,
                )
                FilterChip(
                    selected = settings.vocabCaptureEnabled,
                    onClick = { viewModel.setCaptureEnabled(!settings.vocabCaptureEnabled) },
                    label = { Text("Auto-capture") },
                )
            }

            Spacer(Modifier.height(12.dp))

            if (due.isNotEmpty()) {
                val card = due.first()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            card.word.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(10.dp))
                        if (revealed) {
                            Text(card.definition, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(14.dp))
                        } else {
                            Text(
                                "Tap to reveal",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                        if (!revealed) {
                            TextButton(onClick = viewModel::toggleRevealed) { Text("Reveal") }
                        } else {
                            Row {
                                TextButton(onClick = { viewModel.gradeCurrent(false) }) {
                                    Text("Again")
                                }
                                TextButton(onClick = { viewModel.gradeCurrent(true) }) {
                                    Text("Got it")
                                }
                            }
                        }
                        Text(
                            "Due now: ${due.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No saved words yet",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Look up a word while reading and save it here to review later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries, key = { it.word }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.word) },
                            supportingContent = {
                                Text(
                                    entry.definition,
                                    maxLines = 2,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { viewModel.remove(entry.word) }) {
                                    Icon(Icons.Outlined.Delete, contentDescription = "Delete ${entry.word}")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
