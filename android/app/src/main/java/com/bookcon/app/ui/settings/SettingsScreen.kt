@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookcon.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val THEME_OPTIONS = listOf(
    "auto" to "Follow system",
    "light" to "Light",
    "dark" to "Dark",
    "black" to "Black (OLED)",
    "sepia" to "Sepia",
)

/** Settings (PRD SET-*): account, appearance, sync, reader defaults stub, about/licenses. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    openDevices: () -> Unit,
    openStorage: () -> Unit,
    openAiSummary: () -> Unit,
    openVocab: () -> Unit,
    openStats: () -> Unit,
    openWifiImport: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lastSyncedAt by viewModel.lastSyncedAt.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showLicenses by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.SignedOut -> onSignedOut()
                is SettingsEvent.Message -> snackbarHostState.showSnackbar(event.text)
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
                title = { Text("Settings") },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SectionCard("Account", icon = null) {
                ListItem(
                    headlineContent = { Text(viewModel.accountEmail.ifBlank { "Not signed in" }) },
                    supportingContent = { Text("BookCon account") },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Sign out") },
                    supportingContent = { Text("Stops sync on this device") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    modifier = Modifier.clickable { confirmSignOut = true },
                )
            }

            SectionCard("Appearance", icon = Icons.Filled.Palette) {
                THEME_OPTIONS.forEach { (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setThemeMode(mode) }
                            .padding(horizontal = 8.dp),
                    ) {
                        RadioButton(selected = settings.themeMode == mode, onClick = { viewModel.setThemeMode(mode) })
                        Text(label)
                    }
                }
            }

            SectionCard("Sync", icon = Icons.Filled.Sync) {
                ListItem(
                    headlineContent = { Text("Last synced") },
                    supportingContent = { Text(formatStamp(lastSyncedAt)) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Force sync now") },
                    supportingContent = { Text("Push local changes, pull remote updates") },
                    leadingContent = { Icon(Icons.Filled.Sync, contentDescription = null) },
                    modifier = Modifier.clickable { viewModel.forceSync() },
                )
            }

            SectionCard("Library & storage", icon = Icons.Filled.Storage) {
                ListItem(
                    headlineContent = { Text("Devices") },
                    supportingContent = { Text("Manage signed-in devices") },
                    leadingContent = { Icon(Icons.Filled.Devices, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = openDevices),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Storage manager") },
                    supportingContent = { Text("Downloaded books and import staging") },
                    leadingContent = { Icon(Icons.Filled.Storage, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = openStorage),
                )
            }

            // Reader defaults live in the reader package; link row kept as a stub here.
            SectionCard("Reader defaults", icon = Icons.Filled.MenuBook) {
                // TODO(reader-package): navigate to ReaderDefaultsScreen when it exists.
                ListItem(
                    headlineContent = { Text("Font, spacing, pagination…") },
                    supportingContent = { Text("Adjust while reading any book (reader toolbar)") },
                    leadingContent = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                )
            }

            SectionCard("AI", icon = Icons.Outlined.AutoAwesome) {
                ListItem(
                    headlineContent = { Text("AI summary") },
                    supportingContent = { Text("Bring your own key — summarize pages") },
                    leadingContent = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = openAiSummary),
                )
            }

            SectionCard("Reading", icon = Icons.Outlined.MenuBook) {
                ListItem(
                    headlineContent = { Text("Vocabulary") },
                    supportingContent = { Text("Saved words with spaced-repetition review") },
                    leadingContent = { Icon(Icons.Outlined.Spellcheck, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = openVocab),
                )
                ListItem(
                    headlineContent = { Text("Reading stats") },
                    supportingContent = { Text("Daily minutes, streaks and goals") },
                    leadingContent = { Icon(Icons.Outlined.BarChart, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = openStats),
                )
                ListItem(
                    headlineContent = { Text("Import over Wi-Fi") },
                    supportingContent = { Text("Send books from a browser on the same network") },
                    leadingContent = { Icon(Icons.Outlined.Wifi, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = openWifiImport),
                )
            }

            SectionCard("About", icon = Icons.Filled.Info) {
                ListItem(
                    headlineContent = { Text("About BookCon") },
                    supportingContent = { Text("Version 1.2.0 · Open-source licenses") },
                    leadingContent = { Icon(Icons.Filled.Info, contentDescription = null) },
                    modifier = Modifier.clickable { showLicenses = true },
                )
            }

            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Pending offline changes will keep syncing when you sign back in on this device.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector?, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp, start = 4.dp),
    )
    Card(colors = CardDefaults.cardColors(), modifier = Modifier.fillMaxWidth()) {
        Column { content() }
    }
}

private fun formatStamp(epochMillis: Long?): String =
    if (epochMillis == null) {
        "Never"
    } else {
        val dateTime = java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm")
            .format(dateTime)
    }

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LicenseLine("Jetpack Compose / Material 3", "Apache License 2.0")
                LicenseLine("AndroidX (Room, DataStore, WorkManager, Lifecycle)", "Apache License 2.0")
                LicenseLine("Kotlin & Coroutines", "Apache License 2.0")
                LicenseLine("Retrofit", "Apache License 2.0")
                LicenseLine("OkHttp", "Apache License 2.0")
                LicenseLine("Readium Kotlin Toolkit", "Apache License 2.0 (BSD-3 parts)")
                LicenseLine("Coil", "Apache License 2.0")
                LicenseLine("kotlinx.serialization", "Apache License 2.0")
                LicenseLine("Hilt / Dagger", "Apache License 2.0")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun LicenseLine(name: String, license: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
