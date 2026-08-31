package com.bookcon.app.ui.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.ui.components.AppTopBar

/** Reading stats dashboard: today-vs-goal ring, streak, 30-day bars, goal editor.
 *  v1.3 redesign: blue AppTopBar with rounded bottom, hairline-bordered cards,
 *  hero goal ring with streak chip. */
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            AppTopBar(
                title = "Reading stats",
                subtitle = if (state.streak > 0) "${state.streak}-day streak" else null,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.padding(top = 8.dp))
            // Hero goal ring card
            HeroCard {
                GoalRing(todayMinutes = state.todayMinutes, goalMinutes = state.goalMinutes)
                Spacer(Modifier.height(12.dp))
                Text(
                    "${state.todayMinutes} of ${state.goalMinutes} min today",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                StreakChip(state.streak)
            }

            // Last 30 days chart card
            BorderedCard {
                SectionTitle("Last 30 days")
                Spacer(Modifier.height(12.dp))
                BarsChart(
                    values = state.last30.map { it.totalMinutes },
                    goal = state.goalMinutes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                )
            }

            // Daily goal editor card
            BorderedCard {
                SectionTitle("Daily goal")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Slider(
                        value = state.goalMinutes.toFloat(),
                        onValueChange = { viewModel.setGoal(it.toInt()) },
                        valueRange = 0f..180f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${state.goalMinutes} min",
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (state.weekBooks.isNotEmpty()) {
                BorderedCard {
                    SectionTitle("This week by book")
                    Spacer(Modifier.height(8.dp))
                    state.weekBooks.forEachIndexed { i, book ->
                        if (i > 0) androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                book.first.take(48),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${book.second} min",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HeroCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun BorderedCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StreakChip(days: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Filled.LocalFireDepartment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "$days-day streak",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun GoalRing(todayMinutes: Int, goalMinutes: Int) {
    val progress = if (goalMinutes <= 0) {
        if (todayMinutes > 0) 1f else 0f
    } else {
        (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
    }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.height(140.dp).fillMaxWidth()) {
        val diameter = minOf(size.width, size.height) * 0.7f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 22f),
        )
        drawArc(
            color = fill,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 22f),
        )
    }
}

@Composable
private fun BarsChart(values: List<Int>, goal: Int, modifier: Modifier = Modifier) {
    val maxV = (values.maxOrNull() ?: 1).coerceAtLeast(goal.coerceAtLeast(1))
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val zeroColor = MaterialTheme.colorScheme.surfaceVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier) {
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = slot * 0.62f
        values.forEachIndexed { i, v ->
            val h = (v.toFloat() / maxV) * size.height
            drawRect(
                color = if (v > 0) barColor else zeroColor,
                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h),
                style = Fill,
            )
        }
        if (goal in 1..maxV) {
            val y = size.height - (goal.toFloat() / maxV) * size.height
            drawLine(goalColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 4f)
        }
    }
}
