@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookcon.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Wifi
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.AppTopBar

private val THEME_OPTIONS = listOf(
    "auto" to "Follow system",
    "light" to "Light",
    "dark" to "Dark",
    "black" to "Black (OLED)",
    "sepia" to "Sepia",
)

/** Settings (PRD SET-*): account, appearance, sync, reader defaults, about/licenses.
 *  v1.3 redesign: blue AppTopBar with rounded bottom, grouped cards with circular
 *  tinted icon avatars + chevron rows. */
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
            AppTopBar(
                title = "Settings",
                onBack = onBack,
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
            // ---- Account ----------------------------------------------------
            SettingsSectionHeader("Account")
            SettingsCard {
                ListItem(
                    headlineContent = {
                        Text(
                            if (viewModel.accountEmail.isBlank()) "Not signed in"
                            else viewModel.accountEmail,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    supportingContent = {
                        Text(
                            "BookCon account",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.Info, MaterialTheme.colorScheme.primaryContainer) },
                    trailingContent = { Chevron() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Sign out", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Stops sync on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.AutoMirrored.Filled.Logout, MaterialTheme.colorScheme.errorContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable { confirmSignOut = true },
                )
            }

            // ---- Appearance -------------------------------------------------
            SettingsSectionHeader("Appearance")
            SettingsCard {
                THEME_OPTIONS.forEachIndexed { i, (mode, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setThemeMode(mode) }
                            .padding(horizontal = 8.dp),
                    ) {
                        RadioButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (i < THEME_OPTIONS.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }

            // ---- Sync -------------------------------------------------------
            SettingsSectionHeader("Sync")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Last synced", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            formatStamp(lastSyncedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.Sync, MaterialTheme.colorScheme.primaryContainer) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Force sync now", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Push local changes, pull remote updates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.Sync, MaterialTheme.colorScheme.secondaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable { viewModel.forceSync() },
                )
            }

            // ---- Library & Storage -----------------------------------------
            SettingsSectionHeader("Library & storage")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Devices", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Manage signed-in devices",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.Devices, MaterialTheme.colorScheme.primaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable(onClick = openDevices),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Storage manager", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Downloaded books and import staging",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.Storage, MaterialTheme.colorScheme.secondaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable(onClick = openStorage),
                )
            }

            // ---- Reader defaults --------------------------------------------
            SettingsSectionHeader("Reader defaults")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Font, spacing, pagination…", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Adjust while reading any book (reader toolbar)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.MenuBook, MaterialTheme.colorScheme.primaryContainer) },
                )
            }

            // ---- AI --------------------------------------------------------
            SettingsSectionHeader("AI")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("AI summary", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Bring your own key — summarize pages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Outlined.AutoAwesome, MaterialTheme.colorScheme.secondaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable(onClick = openAiSummary),
                )
            }

            // ---- Reading ---------------------------------------------------
            SettingsSectionHeader("Reading")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("Vocabulary", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Saved words with spaced-repetition review",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Outlined.Spellcheck, MaterialTheme.colorScheme.primaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable(onClick = openVocab),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Reading stats", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Daily minutes, streaks and goals",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Outlined.BarChart, MaterialTheme.colorScheme.secondaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable(onClick = openStats),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                ListItem(
                    headlineContent = { Text("Import over Wi-Fi", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Send books from a browser on the same network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Outlined.Wifi, MaterialTheme.colorScheme.primaryContainer) },
                    trailingContent = { Chevron() },
                    modifier = Modifier.clickable(onClick = openWifiImport),
                )
            }

            // ---- About ----------------------------------------------------
            SettingsSectionHeader("About")
            SettingsCard {
                ListItem(
                    headlineContent = { Text("About BookCon", style = MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        Text(
                            "Version 1.4.2 · Open-source licenses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = { AvatarCircle(Icons.Filled.Info, MaterialTheme.colorScheme.primaryContainer) },
                    trailingContent = { Chevron() },
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

/** Section header above each card group, matches the reference's title style. */
@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp, start = 4.dp),
    )
}

/** Card wrapping a group of related rows, with hairline border + zero elevation. */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** Round tinted avatar used in the leading slot of a settings row. */
@Composable
private fun AvatarCircle(icon: ImageVector, background: Color) {
    Surface(
        color = background,
        shape = CircleShape,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun Chevron() {
    Icon(
        Icons.Filled.ChevronRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
