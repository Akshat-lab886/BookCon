package com.bookcon.app.ui.reader

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.core.VoiceAssistant

/**
 * Always-accessible voice conversation button (PRD VOICE-1).
 *
 * Place this anywhere a user can invoke the assistant — in the reader overlays
 * (PdfPager / epub ReaderScreen) or floating over the library grid. Tapping it
 * cycles: INACTIVE → listening (hold to speak) → the assistant speaks its reply.
 *
 * The host supplies [assistant] and optionally a screen-capture callback used to
 * build the "current page/screen" context that the LLM sees.
 */
@Composable
fun VoiceModeButton(
    assistant: VoiceAssistant,
    onRequestAudioPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by assistant.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var pressing by rememberSaveable { mutableStateOf(false) }

    val iconTint = when (state.phase) {
        VoiceAssistant.Phase.SPEAKING -> MaterialTheme.colorScheme.primary
        VoiceAssistant.Phase.THINKING -> MaterialTheme.colorScheme.tertiary
        VoiceAssistant.Phase.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    FloatingActionButton(
        onClick = {
            when (state.phase) {
                VoiceAssistant.Phase.INACTIVE -> {
                    onRequestAudioPermission()
                    pressing = true
                    assistant.startListening()
                }
                VoiceAssistant.Phase.LISTENING,
                VoiceAssistant.Phase.THINKING,
                VoiceAssistant.Phase.SPEAKING -> {
                    assistant.stop()
                    pressing = false
                }
                VoiceAssistant.Phase.ERROR -> assistant.stop()
            }
        },
        containerColor = if (pressing) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        contentColor = iconTint,
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = "Voice assistant — tap and speak" },
    ) {
        Icon(
            imageVector = when (state.phase) {
                VoiceAssistant.Phase.SPEAKING -> Icons.Filled.Pause
                VoiceAssistant.Phase.THINKING -> Icons.Filled.PlayArrow
                VoiceAssistant.Phase.ERROR -> Icons.Filled.ErrorOutline
                VoiceAssistant.Phase.LISTENING -> Icons.Filled.Mic
                VoiceAssistant.Phase.INACTIVE -> Icons.Filled.Mic
            },
            contentDescription = "Voice assistant",
            tint = iconTint,
            modifier = Modifier.size(26.dp),
        )
    }

    // Voice reply card: shows the transcript + reply when active.
    if (state.phase != VoiceAssistant.Phase.INACTIVE && state.reply.isNotBlank()) {
        Surface(
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.transcript, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                Text(state.reply, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    LaunchedEffect(state.reply) {
        if (state.reply.isNotBlank()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    if (state.phase == VoiceAssistant.Phase.ERROR && state.error != null) {
        Text(
            state.error!!,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(8.dp),
        )
    }
}
