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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.bookcon.app.ui.components.AppTopBar
import com.bookcon.app.ui.components.PillButton

private val PROVIDER_OPTIONS = listOf(
    "openai" to "OpenAI",
    "anthropic" to "Anthropic",
    "groq" to "Groq",
    "gemini" to "Gemini",
    "custom" to "Custom",
)

private val GROQ_MODELS = listOf(
    "llama-3.3-70b-versatile" to "Most capable",
    "llama-3.1-8b-instant" to "Fastest",
    "gemma2-9b-it" to "Lightweight",
    "mixtral-8x7b-32768" to "Large context",
    "llama-3.2-3b-preview" to "Smallest",
    "deepseek-r1-distill-llama-70b" to "Reasoning",
)

/** Muted success green for the inline "connected" confirmation line. */
private val AiOkGreen = Color(0xFF2E7D32)

/** BYOK AI page summarization (PRD): pick provider, model and on-device API key.
 *  v1.3 redesign: blue AppTopBar, grouped card sections, pill Test button. */
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
    val online = settings.aiBaseUrl.contains("127.0.0.1") ||
        settings.aiBaseUrl.contains("localhost") || Net.isOnline(context)
    val suggestedModel = Summarizer.defaultModel(settings.aiProvider)

    Scaffold(
        topBar = {
            AppTopBar(
                title = "AI summary",
                onBack = onBack,
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
            // ---- Provider card ----
            Spacer(Modifier.padding(top = 16.dp))
            SectionCard {
                SectionTitle("Provider")
                SectionSubtitle("Who summarizes pages for you. The API key never leaves this device.")
                Spacer(Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROVIDER_OPTIONS.forEach { (value, label) ->
                        FilterChip(
                            selected = settings.aiProvider == value,
                            onClick = { viewModel.setProvider(value) },
                            label = { Text(label) },
                        )
                    }
                }
                if (settings.aiProvider == "groq") {
                    Spacer(Modifier.padding(top = 12.dp))
                    Text(
                        "Base URL: https://api.groq.com/openai/v1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (settings.aiProvider == "custom") {
                    Spacer(Modifier.padding(top = 12.dp))
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

            // ---- Model card ----
            Spacer(Modifier.padding(top = 12.dp))
            SectionCard {
                SectionTitle("Model")
                if (settings.aiProvider == "groq") {
                    var expandedModel by remember { mutableStateOf(false) }
                    val currentLabel = GROQ_MODELS.find { it.first == settings.aiModel }
                    OutlinedTextField(
                        value = currentLabel?.let { "${it.first}  —  ${it.second}" } ?: settings.aiModel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Groq model") },
                        trailingIcon = {
                            IconButton(onClick = { expandedModel = !expandedModel }) {
                                Icon(
                                    if (expandedModel) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Select model",
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    DropdownMenu(
                        expanded = expandedModel,
                        onDismissRequest = { expandedModel = false },
                    ) {
                        GROQ_MODELS.forEach { (id, desc) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(id, style = MaterialTheme.typography.bodyMedium)
                                        Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    viewModel.setModel(id)
                                    expandedModel = false
                                },
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = settings.aiModel,
                        onValueChange = viewModel::setModel,
                        label = { Text("Model") },
                        placeholder = { Text(suggestedModel.ifBlank { "server default" }) },
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
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }

            // ---- API key card ----
            Spacer(Modifier.padding(top = 12.dp))
            SectionCard {
                SectionTitle("API key")
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
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                Spacer(Modifier.padding(top = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton(
                        text = "Save",
                        onClick = {
                            viewModel.saveKey(keyText)
                            keyStatus = "Key saved on this device"
                        },
                        enabled = keyText.isNotBlank(),
                    )
                    OutlinedButton(
                        onClick = {
                            viewModel.clearKey()
                            keyText = ""
                            keyStatus = null
                        },
                        enabled = keyText.isNotBlank() || storedKey.isNotBlank(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                    ) { Text("Clear") }
                }
                keyStatus?.let {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ---- Test connection card ----
            Spacer(Modifier.padding(top = 12.dp))
            SectionCard {
                SectionTitle("Test connection")
                Spacer(Modifier.padding(top = 12.dp))
                PillButton(
                    text = if (testing) "Testing…" else "Test connection",
                    onClick = {
                        viewModel.testConnection(
                            provider = settings.aiProvider,
                            baseUrl = settings.aiBaseUrl,
                            model = settings.aiModel,
                            apiKey = keyText.trim(),
                        )
                    },
                    enabled = online && keyText.isNotBlank() && !testing,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.padding(top = 8.dp))
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
                    is AiTestState.Success -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = AiOkGreen,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Connected to ${t.providerLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AiOkGreen,
                        )
                    }
                    is AiTestState.Failure -> Text(
                        t.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SectionSubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
