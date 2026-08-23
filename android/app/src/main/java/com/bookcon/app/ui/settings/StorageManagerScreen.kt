@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookcon.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private sealed interface StorageDlg {
    data object None : StorageDlg
    data class ConfirmRemoveOffline(val bookId: String, val title: String) : StorageDlg
    data object ConfirmClearImports : StorageDlg
}

/** Storage manager (PRD SET): server usage stats, downloaded books, import staging cleanup. */
@Composable
fun StorageManagerScreen(
    onBack: () -> Unit,
    viewModel: StorageManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<StorageDlg>(StorageDlg.None) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is StorageEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Storage") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading && state.stats == null && state.downloads.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(padding),
            ) {
                item(key = "server") {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Server usage", style = MaterialTheme.typography.titleSmall)
                            val stats = state.stats
                            if (stats == null) {
                                Text(
                                    state.statsError ?: "…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "${StorageManagerViewModel.humanize(stats.totalBytes)} across ${stats.bookCount} books",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "${stats.annotationCount} annotations · ${stats.bookmarkCount} bookmarks",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                item(key = "downloads-header") {
                    Text(
                        "Downloaded on this device (${state.downloads.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (state.downloads.isEmpty()) {
                    item(key = "downloads-empty") {
                        Text(
                            "Nothing downloaded yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(state.downloads, key = { it.book.id }) { item ->
                        Card {
                            ListItem(
                                headlineContent = { Text(item.book.title, maxLines = 1) },
                                supportingContent = {
                                    Text(
                                        "${item.book.format.uppercase()} · ${StorageManagerViewModel.humanize(item.sizeBytes)}",
                                    )
                                },
                                leadingContent = { Icon(Icons.Filled.DownloadDone, contentDescription = null) },
                                trailingContent = {
                                    TextButton(onClick = {
                                        dialog = StorageDlg.ConfirmRemoveOffline(item.book.id, item.book.title)
                                    }) { Text("Remove") }
                                },
                            )
                        }
                    }
                }

                item(key = "imports") {
                    Card {
                        ListItem(
                            headlineContent = { Text("Import staging") },
                            supportingContent = {
                                Text(
                                    "${StorageManagerViewModel.humanize(state.importsBytes)} in files/imports — leftover copies of imported files",
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
                            trailingContent = {
                                TextButton(onClick = { dialog = StorageDlg.ConfirmClearImports }) { Text("Clear") }
                            },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        StorageDlg.None -> Unit
        is StorageDlg.ConfirmRemoveOffline -> AlertDialog(
            onDismissRequest = { dialog = StorageDlg.None },
            title = { Text("Remove “${d.title}”?") },
            text = { Text("The downloaded file will be deleted from this device. The book stays in your library.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeOffline(d.bookId); dialog = StorageDlg.None }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { dialog = StorageDlg.None }) { Text("Cancel") } },
        )
        StorageDlg.ConfirmClearImports -> AlertDialog(
            onDismissRequest = { dialog = StorageDlg.None },
            title = { Text("Clear import staging?") },
            text = { Text("Deletes leftover copies of imported files from filesDir/imports.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearImports(); dialog = StorageDlg.None }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { dialog = StorageDlg.None }) { Text("Cancel") } },
        )
    }
}
