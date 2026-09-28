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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.BookCover
import com.bookcon.app.ui.details.BookDetailsViewModel
import com.bookcon.app.ui.theme.BrandColors

/**
 * Book detail matching the reference screenshot.
 *
 * The reference is a full-bleed dark maroon-plum page (not the purple I
 * originally inferred from a thumbnail): a pale-pink bold title up top, the
 * cover tilted on a dark plate with scattered dots, the serif title again
 * below it, a row of three stat chips, a "50% read" pill, and a large
 * near-white Continue Reading button. There is no bottom nav on this screen.
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
    val title = book?.title.orEmpty().ifBlank { "Untitled" }
    val author = book?.authors?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Unknown"
    val languageLabel = (book?.language?.takeIf { it.isNotBlank() } ?: "ENG").uppercase()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BrandColors.DetailTop, BrandColors.DetailMid, BrandColors.DetailBottom)
                )
            )
    ) {
        // Scattered decorative dots, matching the reference composition.
        DetailDots()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Bold sans title at the very top, in pale pink.
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = BrandColors.DetailTitle,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(26.dp))

            // Cover on a dark plate, tilted a few degrees.
            Box(
                modifier = Modifier
                    .width(232.dp)
                    .height(300.dp)
                    .rotate(4f),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BrandColors.DetailCoverBg, RoundedCornerShape(26.dp))
                        .offset(x = 10.dp, y = 14.dp)
                )
                BookCover(
                    coverUrl = book?.coverUrl,
                    title = title,
                    serverUrl = state.serverUrl.orEmpty(),
                    cornerRadius = 14.dp,
                    modifier = Modifier
                        .size(178.dp, 246.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            }

            Spacer(Modifier.height(30.dp))

            // Serif title, repeated under the cover.
            Text(
                text = title,
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            // Three stat chips.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatChip(
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Star, null, tint = BrandColors.DetailStar, modifier = Modifier.size(15.dp)) },
                    label = "Rating",
                    value = state.ratingLabel.ifBlank { "—" },
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    label = "Pages",
                    value = state.pagesLabel,
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    label = "Language",
                    value = languageLabel,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Reading-progress pill. progressPercent is already stored as 0-100
            // (PositionEntity.progressPercent is written as progression * 100), so
            // multiplying it by 100 again rendered a half-read book as "5000% read".
            val pct = (state.progressPercent ?: 0.0).toInt().coerceIn(0, 100)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = BrandColors.DetailChip,
            ) {
                Text(
                    "$pct% read",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.TextPrimary,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 11.dp),
                )
            }

            Spacer(Modifier.height(22.dp))

            Text(
                "Introduction",
                style = MaterialTheme.typography.headlineSmall,
                color = BrandColors.DetailLabel,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                book?.description?.takeIf { it.isNotBlank() }
                    ?: "No description for this book yet.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = BrandColors.DetailLabel,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(34.dp))

            // Large near-white pill.
            Button(
                onClick = onContinueReading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(66.dp),
                shape = RoundedCornerShape(26.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrandColors.DetailButton,
                    contentColor = BrandColors.DetailOnButton,
                ),
            ) {
                Icon(Icons.Default.AutoStories, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "Continue Reading",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.DetailOnButton,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatChip(
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    label: String,
    value: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = BrandColors.DetailChip,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.invoke()
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandColors.DetailLabel,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary,
            )
        }
    }
}

/** One decorative dot: offset from the top-left of the page plus its diameter. */
private data class Dot(val x: androidx.compose.ui.unit.Dp, val y: androidx.compose.ui.unit.Dp, val d: androidx.compose.ui.unit.Dp)

/** The muted grey-mauve dots scattered over the detail page. */
@Composable
private fun DetailDots() {
    DOT_SPOTS.forEach { dot ->
        Box(
            modifier = Modifier
                .offset(x = dot.x, y = dot.y)
                .size(dot.d)
                .background(BrandColors.DetailDot, CircleShape)
        )
    }
}

private val DOT_SPOTS = listOf(
    Dot((-28).dp, 96.dp, 15.dp),
    Dot(300.dp, 168.dp, 12.dp),
    Dot((-16).dp, 352.dp, 10.dp),
    Dot(310.dp, 486.dp, 14.dp),
    Dot((-24).dp, 612.dp, 9.dp),
    Dot(286.dp, 742.dp, 12.dp),
)
