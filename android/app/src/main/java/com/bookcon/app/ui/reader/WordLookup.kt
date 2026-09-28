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
 * "Save to vocabulary" persists through [VocabStore].
 *
 * [autoCapture] is the real gate behind the "Auto-capture" switch. The switch used to
 * be persisted and displayed but read by nothing except itself: the only code path
 * that ever stored a word was the explicit Save button, and its caller never checked
 * the setting. Turning auto-capture off changed the chip and survived restarts while
 * having no effect on anything. With autoCapture on, looking a word up now saves it
 * automatically; with it off, saving is still available by hand.
 */
@Composable
fun WordLookupHost(
    state: androidx.compose.runtime.MutableState<String?>,
    autoCapture: Boolean = false,
    onSaved: (() -> Unit)? = null,
) {
    // Hoisted ABOVE the early return on purpose. Returning first tore down the
    // composition whenever no word was selected, so the `remember` below was
    // discarded and the next lookup re-read and re-parsed the whole ~86k-line
    // dictionary. It is expensive enough that it belongs for the reader's lifetime.
    val context = LocalContext.current
    val dictionary = remember(context) { Dictionary.get(context) }
    val vocab = remember { VocabStore(context) }

    val word = state.value ?: return
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var definition by remember(word) { mutableStateOf<String?>(null) }
    var lookedUp by remember(word) { mutableStateOf(false) }
    var saved by remember(word) { mutableStateOf(false) }

    // Auto-capture: the setting now actually does something.
    LaunchedEffect(word, autoCapture) {
        if (autoCapture) {
            val meaning = dictionary.lookup(word)?.meaning
            if (meaning != null) {
                scope.launch {
                    vocab.add(word.trim().lowercase(), meaning)
                    saved = true
                    onSaved?.invoke()
                }
            }
        }
    }

    LaunchedEffect(word) {
        definition = dictionary.lookup(word)?.meaning
        lookedUp = true
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
                    definition == null && !lookedUp -> {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    }
                    definition == null -> {
                        Text(
                            "No definition found in the offline dictionary.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    else -> Text(definition!!, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        properties = DialogProperties(dismissOnClickOutside = true),
    )
}
