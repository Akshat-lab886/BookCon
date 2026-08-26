package com.bookcon.app.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.bookcon.app.core.Dictionary
import com.bookcon.app.core.VocabStore
import kotlinx.coroutines.launch

/**
 * Hosts the word-lookup popup. Teammates trigger it by setting the bound state's
 * value to the word to define (null hides). Usage:
 *
 *     val lookupWord = remember { mutableStateOf<String?>(null) }
 *     WordLookupHost(state = lookupWord, onSaved = { ... })
 *
 * "Save to vocabulary" persists through [VocabStore] (capture gating is done by
 * the caller via SettingsRepository.vocabCaptureEnabled).
 */
@Composable
fun WordLookupHost(
    state: androidx.compose.runtime.MutableState<String?>,
    onSaved: (() -> Unit)? = null,
) {
    val word = state.value ?: return
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var definition by remember(word) { mutableStateOf<String?>(null) }
    var saved by remember(word) { mutableStateOf(false) }
    val dictionary = remember { Dictionary(context) }
    val vocab = remember { VocabStore(context) }

    LaunchedEffect(word) {
        definition = dictionary.lookup(word)?.meaning
    }

    AlertDialog(
        onDismissRequest = { state.value = null },
        confirmButton = {
            TextButton(onClick = { state.value = null }) { Text("Close") }
        },
        dismissButton = {
            TextButton(
                enabled = definition != null && !saved,
                onClick = {
                    val meaning = definition.orEmpty()
                    scope.launch {
                        vocab.add(word.trim().lowercase(), meaning)
                        saved = true
                        onSaved?.invoke()
                    }
                },
            ) {
                Icon(Icons.Outlined.BookmarkAdd, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (saved) "Saved" else "Save to vocabulary")
            }
        },
        title = { Text(word.trim()) },
        text = {
            Column {
                when {
                    definition == null -> {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    }
                    else -> Text(definition!!, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        properties = DialogProperties(dismissOnClickOutside = true),
    )
}
