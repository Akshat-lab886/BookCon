package com.bookcon.app.ui.importwifi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Import over Wi-Fi (PRD IMP-WIFI): runs the on-device upload server and shows the URL
 * to open on any laptop/phone on the same network. Server stops when the screen leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiImportScreen(
    onBack: () -> Unit,
    viewModel: WifiImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    DisposableEffect(Unit) {
        viewModel.startServer()
        onDispose { viewModel.stopServer() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import over Wi-Fi") },
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
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.running && state.url != null) {
                        Text("Server running", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            state.url!!,
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(state.url!!))
                            }) { Text("Copy URL") }
                        }
                    } else {
                        Text("Server stopped", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = viewModel::startServer) { Text("Start server") }
                    }

                    if (state.received > 0) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Books received: ${state.received}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "1. Connect this tablet and your computer to the same Wi-Fi.\n" +
                    "2. Open the address above in any browser.\n" +
                    "3. Choose your PDF or EPUB files and upload.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val Row: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
    get() = throw IllegalStateException()

/** Local alias so imports stay minimal in this file. */
@Composable
private fun Row(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) =
    androidx.compose.foundation.layout.Row { content() }
