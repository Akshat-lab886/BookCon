@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookcon.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.core.Net
import com.bookcon.app.core.Summarizer

private val PROVIDER_OPTIONS = listOf(
    "openai" to "OpenAI",
    "gemini" to "Gemini",
    "custom" to "Custom",
)

/** Muted success green for the inline "connected" confirmation line. */
private val AiOkGreen = Color(0xFF2E7D32)

/** BYOK AI page summarization (PRD): pick provider, model and on-device API key. */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: AiSettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val test by viewModel.test.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Key lives outside DataStore; read it once when the screen appears.
    val storedKey = remember { viewModel.storedKey() }
    var keyText by remember { mutableStateOf(storedKey) }
    var showKey by remember { mutableStateOf(false) }
    var keyStatus by remember { mutableStateOf<String?>(null) }

    val testing = test == AiTestState.Running
    val online = Net.isOnline(context)
    val suggestedModel = Summarizer.defaultModel(settings.aiProvider)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("AI summary") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Card(modifier = Modifier.padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Provider", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Who summarizes pages for you. The API key never leaves this device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PROVIDER_OPTIONS.forEach { (value, label) ->
                            FilterChip(
                                selected = settings.aiProvider == value,
                                onClick = { viewModel.setProvider(value) },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (settings.aiProvider == "custom") {
                        OutlinedTextField(
                            value = settings.aiBaseUrl,
                            onValueChange = viewModel::setBaseUrl,
                            label = { Text("Server URL") },
                            supportingText = {
                                Text("OpenAI-compatible base URL, e.g. https://api.groq.com/openai/v1")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Card(modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Model", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = settings.aiModel,
                        onValueChange = viewModel::setModel,
                        label = { Text("Model") },
                        placeholder = {
                            Text(suggestedModel.ifBlank { "server default" })
                        },
                        supportingText = {
                            Text(
                                if (suggestedModel.isBlank()) {
                                    "Leave blank unless your server needs a specific model id."
                                } else {
                                    "Leave blank to use $suggestedModel."
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Card(modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("API key", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = {
                            keyText = it
                            keyStatus = null
                        },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation =
                            if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showKey) "Hide key" else "Show key",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = keyText.isNotBlank(),
                            onClick = {
                                viewModel.saveKey(keyText)
                                keyStatus = "Key saved on this device"
                            },
                        ) {
                            Text("Save")
                        }
                        OutlinedButton(
                            enabled = keyText.isNotBlank() || storedKey.isNotBlank(),
                            onClick = {
                                viewModel.clearKey()
                                keyText = ""
                                keyStatus = null
                            },
                        ) {
                            Text("Clear")
                        }
                    }
                    keyStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Card(modifier = Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Test connection", style = MaterialTheme.typography.titleMedium)
                    Button(
                        enabled = online && keyText.isNotBlank() && !testing,
                        onClick = {
                            viewModel.testConnection(
                                provider = settings.aiProvider,
                                baseUrl = settings.aiBaseUrl,
                                model = settings.aiModel,
                                apiKey = keyText.trim(),
                            )
                        },
                    ) {
                        Text("Test connection")
                    }
                    when (val t = test) {
                        AiTestState.Idle ->
                            if (!online) {
                                Text(
                                    "You're offline — reconnect to run the test.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        AiTestState.Running -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Testing…", style = MaterialTheme.typography.bodySmall)
                        }
                        is AiTestState.Success -> Text(
                            "✓ Connected to ${t.providerLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AiOkGreen,
                        )
                        is AiTestState.Failure -> Text(
                            t.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}
