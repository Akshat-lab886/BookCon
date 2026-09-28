package com.bookcon.app.ui.importwifi

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.bookcon.app.ui.components.AppTopBar
import com.bookcon.app.ui.components.OutlinePillButton
import com.bookcon.app.ui.components.PillButton
import com.bookcon.app.ui.theme.BrandColors

/**
 * Import over Wi-Fi (PRD IMP-WIFI): runs the on-device upload server and shows the URL
 * to open on any laptop/phone on the same network. Server stops when the screen leaves.
 *
 * v1.3 redesign: blue AppTopBar, hero URL card with copy pill, instructions card.
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
            AppTopBar(
                title = "Import over Wi-Fi",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.padding(top = 16.dp))
            HeroCard {
                if (state.running && state.url != null) {
                    // Resolved once, on purpose. `state` is a delegated property
                    // (`by collectAsStateWithLifecycle()`), so no smart cast reaches
                    // through it and the value has to be bound to a name. Doing that
                    // here — during composition, inside the branch that proved it
                    // non-null — is safe, and the click lambda below then closes over
                    // an immutable String. Reading `state.url` inside that lambda
                    // instead would crash the app if the server stopped between the
                    // button being drawn and being tapped.
                    val runningUrl = state.url!!
                    Text(
                        "Server running",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        runningUrl,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(16.dp))
                    PillButton(
                        text = "Copy URL",
                        onClick = { clipboard.setText(AnnotatedString(runningUrl)) },
                        container = MaterialTheme.colorScheme.primary,
                        content = MaterialTheme.colorScheme.onPrimary,
                    )
                } else if (state.error != null) {
                    // Say what actually went wrong. The previous behaviour showed
                    // "Server stopped" for a port clash, hiding the reason, and on
                    // the way to it reported "Server running" with a dead URL.
                    Text(
                        "Couldn't start",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandColors.Salmon,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text("Server stopped", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(14.dp))
                    PillButton(
                        text = "Start server",
                        onClick = viewModel::startServer,
                    )
                }

                state.lastError?.let { rejection ->
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        rejection,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrandColors.Salmon,
                    )
                }

                if (state.received > 0) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Text(
                            "${state.received} book${if (state.received == 1) "" else "s"} received this session",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            BorderedCard {
                Text(
                    "How to import",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))
                InstructionRow(1, "Connect this tablet and your computer to the same Wi-Fi.")
                InstructionRow(2, "Open the address above in any browser.")
                InstructionRow(3, "Choose your PDF or EPUB files and upload.")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeroCard(content: @Composable () -> Unit) {
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
        ) { content() }
    }
}

@Composable
private fun BorderedCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun InstructionRow(num: Int, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(24.dp)
                .padding(end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier = Modifier.size(24.dp),
            ) {
                Text(
                    "$num",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(2.dp),
                )
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
    }
}
