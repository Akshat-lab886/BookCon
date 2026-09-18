package com.bookcon.app.ui.lifestyle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.BookCover
import com.bookcon.app.ui.details.BookDetailsViewModel

/**
 * Oripio-style book detail screen (PRD VOICE-2 design v2.2).
 *
 * Layout (top → bottom):
 *  1. Pink gradient hero with centered cover + back/share round buttons
 *  2. Title + author (centered)
 *  3. Three small chip cards: rating / pages / language
 *  4. "Introduction" heading + description excerpt
 *  5. Bottom-anchored black "Continue Reading" pill button
 *
 * Reads the same [BookDetailsViewModel] as the existing detail screen so the
 * Oripio variant can drop in as a route replacement without duplicating data.
 */
@Composable
fun LifestyleBookDetailScreen(
    bookId: String,
    onBack: () -> Unit,
    onContinueReading: () -> Unit,
    viewModel: BookDetailsViewModel = hiltViewModel(),
) {
    LaunchedEffect(bookId) { viewModel.bind(bookId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val book = state.book

    val pink = MaterialTheme.colorScheme.secondary
    val yellow = MaterialTheme.colorScheme.tertiary
    val pinkSoft = MaterialTheme.colorScheme.secondaryContainer

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Pink header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .background(
                    Brush.verticalGradient(listOf(pink, pinkSoft))
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)
                Text(
                    "Detail Book",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(40.dp))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BookCover(
                coverUrl = book?.coverUrl,
                title = book?.title.orEmpty(),
                serverUrl = "",
                cornerRadius = 18.dp,
                modifier = Modifier
                    .size(150.dp, 220.dp)
                    .clip(RoundedCornerShape(18.dp))
            )

            Spacer(Modifier.height(16.dp))
            Text(
                book?.title.orEmpty().ifBlank { "Unknown title" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "by " + (book?.authors?.firstOrNull() ?: "Unknown"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            // Chips row: rating / pages / language
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ChipStat(
                    label = "Rating",
                    value = state.ratingLabel.ifBlank { "—" },
                    accent = MaterialTheme.colorScheme.tertiaryContainer,
                    icon = Icons.Filled.Star,
                )
                ChipStat(
                    label = "Number of pages",
                    value = state.pagesLabel,
                    accent = MaterialTheme.colorScheme.primaryContainer,
                )
                ChipStat(
                    label = "Language",
                    value = (book?.language ?: "—").uppercase(),
                    accent = MaterialTheme.colorScheme.secondaryContainer,
                )
            }

            Spacer(Modifier.height(24.dp))
            Column(modifier = Modifier.padding(horizontal = 24.dp)) {
                Text(
                    "Introduction",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    book?.description.takeUnless { it.isNullOrBlank() }
                        ?: "No description yet. Tap the edit button to add one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(100.dp))
        }

        // Bottom Continue Reading button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Button(
                onClick = onContinueReading,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black,
                    contentColor = Color.White,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
            ) {
                Text(
                    "Continue Reading",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.85f),
        modifier = Modifier.size(40.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun ChipStat(
    label: String,
    value: String,
    accent: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = accent,
        modifier = Modifier.height(64.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onBackground)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
