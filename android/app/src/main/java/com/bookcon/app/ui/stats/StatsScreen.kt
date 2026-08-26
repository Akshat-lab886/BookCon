package com.bookcon.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

/** Reading stats dashboard: today-vs-goal ring, streak, 30-day bars, goal editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Reading stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    GoalRing(todayMinutes = state.todayMinutes, goalMinutes = state.goalMinutes)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "${state.todayMinutes} of ${state.goalMinutes} min today",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.LocalFireDepartment,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text("${state.streak}-day streak", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Last 30 days", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    BarsChart(
                        values = state.last30.map { it.totalMinutes },
                        goal = state.goalMinutes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Daily goal",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = state.goalMinutes.toFloat(),
                            onValueChange = { viewModel.setGoal(it.toInt()) },
                            valueRange = 0f..180f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${state.goalMinutes} min",
                            modifier = Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }

            if (state.weekBooks.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            "This week by book",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        state.weekBooks.forEach { book ->
                            ListItem(
                                headlineContent = { Text(book.first.take(48)) },
                                trailingContent = { Text("${book.second} min") },
                            )
                        }
                    }
                }
            }
        }
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
    Canvas(modifier = Modifier.height(120.dp).fillMaxWidth()) {
        val diameter = minOf(size.width, size.height) * 0.62f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 26f),
        )
        drawArc(
            color = fill,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 26f),
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
