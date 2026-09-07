package com.bookcon.app.ui.notebooks

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.AppTopBar
import com.bookcon.app.ui.components.EmptyState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Notebook list (v1.5): one notebook per book, reachable from Library menu + Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebooksScreen(
    onBack: () -> Unit,
    openBook: (String) -> Unit,
    viewModel: NotebooksViewModel = hiltViewModel(),
) {
    val notebooks by viewModel.notebooks.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { AppTopBar(title = "Notebooks", onBack = onBack) },
    ) { padding ->
        if (notebooks.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState(
                    title = "No notebooks yet",
                    message = "Open a book, then swipe up from the bottom edge or tap the notebook button to start taking notes.",
                    illustration = Icons.AutoMirrored.Outlined.Notes,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(notebooks, key = { it.notebook.id }) { row ->
                    NotebookCard(
                        title = row.bookTitle,
                        noteCount = row.noteCount,
                        updated = row.notebook.updatedAt,
                        onClick = { openBook(row.notebook.bookId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotebookCard(title: String, noteCount: Int, updated: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.Notes,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.padding(start = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    "$noteCount page note${if (noteCount == 1) "" else "s"} · edited ${formatStamp(updated)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = "Open book",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun formatStamp(iso: String): String = runCatching {
    val t = Instant.parse(iso)
    DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault()).format(t)
}.getOrDefault("")
