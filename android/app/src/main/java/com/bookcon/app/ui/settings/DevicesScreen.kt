@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookcon.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
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
import com.bookcon.app.data.remote.DeviceDto

/** Device management (PRD AUTH-4): list sessions, revoke other devices. */
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var revokeTarget by remember { mutableStateOf<DeviceDto?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DevicesEvent.Message -> snackbarHostState.showSnackbar(event.text)
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
                title = { Text("Devices") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.loading ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.devices.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("No devices found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(state.devices, key = { it.id }) { device ->
                    val isCurrent = device.id == state.currentDeviceId
                    Card(modifier = Modifier.padding(vertical = 6.dp)) {
                        ListItem(
                            headlineContent = { Text(device.name + if (isCurrent) "  · This device" else "") },
                            supportingContent = {
                                Text(
                                    buildString {
                                        append(device.platform.replaceFirstChar { it.uppercase() })
                                        if (device.appVersion.isNotBlank()) append(" · v${device.appVersion}")
                                        device.lastSeenAt?.let { append(" · last seen ${it.take(10)}") }
                                    },
                                )
                            },
                            leadingContent = { Icon(Icons.Filled.Devices, contentDescription = null) },
                            trailingContent = {
                                if (!isCurrent) {
                                    IconButton(onClick = { revokeTarget = device }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Revoke ${device.name}", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    revokeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text("Revoke “${target.name}”?") },
            text = { Text("The device will be signed out and its refresh token invalidated on the server.") },
            confirmButton = {
                TextButton(onClick = { viewModel.revoke(target.id); revokeTarget = null }) {
                    Text("Revoke", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { revokeTarget = null }) { Text("Cancel") } },
        )
    }
}
