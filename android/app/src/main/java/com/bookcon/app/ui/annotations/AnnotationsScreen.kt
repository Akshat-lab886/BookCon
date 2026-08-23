@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.bookcon.app.ui.annotations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Annotation colors supported by the reader palette (subset used for filters). */
private val ANNOTATION_COLORS = listOf("yellow", "green", "blue", "red", "purple")
private val ANNOTATION_TYPES = listOf("highlight", "underline", "area")

private val COLOR_SWATCHES = mapOf(
    "yellow" to Color(0xFFF2C94C),
    "green" to Color(0xFF6FCF97),
    "blue" to Color(0xFF56A8F5),
    "red" to Color(0xFFEB5757),
    "purple" to Color(0xFF9B51E0),
)

/**
 * Global (bookId = null) or per-book annotation list (PRD ANN-6/7): search, color/type/tag
 * chips with AND logic across categories, sort menu, tap → open book, export via share sheet.
 */
@Composable
fun AnnotationsScreen(
    bookId: String? = null,
    onBack: () -> Unit,
    openBook: (String) -> Unit,
    viewModel: AnnotationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var sortMenuOpen by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) { viewModel.bind(bookId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AnnotationsEvent.Share -> {
                    AnnotationsExporter.shareText(context, event.subject, event.text)
                    snackbarHostState.showSnackbar("Exported as ${event.format.label}")
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(if (state.perBookId == null) "Annotations" else "Book annotations") },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            AnnotationSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    trailingIcon = {
                                        if (sort == state.filters.sort) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = { viewModel.setSort(sort); sortMenuOpen = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { exportDialog = true }) {
                        Icon(Icons.Filled.IosShare, contentDescription = "Export")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.filters.search,
                onValueChange = viewModel::setSearch,
                label = { Text("Search excerpts, notes, books") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // Filter chips: color · type · tag — AND across categories (PRD ANN-6).
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Box {
                    var expanded by remember { mutableStateOf(false) }
                    FilterChip(
                        selected = state.filters.colors.isNotEmpty(),
                        onClick = { expanded = true },
                        label = {
                            Text(
                                if (state.filters.colors.isEmpty()) "Color"
                                else state.filters.colors.joinToString("/") { it.take(3).replaceFirstChar(Char::uppercase) },
                            )
                        },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ANNOTATION_COLORS.forEach { color ->
                            DropdownMenuItem(
                                leadingIcon = {
                                    Box(
                                        Modifier
                                            .width(18.dp)
                                            .height(14.dp)
                                            .background(COLOR_SWATCHES[color] ?: Color.Gray, RoundedCornerShape(4.dp)),
                                    )
                                },
                                text = { Text(color.replaceFirstChar(Char::uppercase)) },
                                trailingIcon = {
                                    if (color in state.filters.colors) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                                onClick = { viewModel.toggleColor(color); expanded = false },
                            )
                        }
                    }
                }

                Box {
                    var expanded by remember { mutableStateOf(false) }
                    FilterChip(
                        selected = state.filters.types.isNotEmpty(),
                        onClick = { expanded = true },
                        label = {
                            Text(
                                if (state.filters.types.isEmpty()) "Type"
                                else state.filters.types.joinToString("/") { it.replaceFirstChar(Char::uppercase) },
                            )
                        },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ANNOTATION_TYPES.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.replaceFirstChar(Char::uppercase)) },
                                trailingIcon = {
                                    if (type in state.filters.types) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                                onClick = { viewModel.toggleType(type); expanded = false },
                            )
                        }
                    }
                }

                Box {
                    var expanded by remember { mutableStateOf(false) }
                    FilterChip(
                        selected = state.filters.tags.isNotEmpty(),
                        onClick = { expanded = true },
                        label = {
                            Text(
                                if (state.filters.tags.isEmpty()) "Tag"
                                else state.filters.tags.joinToString("/"),
                            )
                        },
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (state.availableTags.isEmpty()) {
                            DropdownMenuItem(text = { Text("No tags yet") }, onClick = {})
                        }
                        state.availableTags.forEach { tag ->
                            DropdownMenuItem(
                                text = { Text(tag) },
                                trailingIcon = {
                                    if (tag in state.filters.tags) Icon(Icons.Filled.Check, contentDescription = null)
                                },
                                onClick = { viewModel.toggleTag(tag); expanded = false },
                            )
                        }
                    }
                }

                if (state.filters.search.isNotBlank() || state.filters.colors.isNotEmpty() ||
                    state.filters.types.isNotEmpty() || state.filters.tags.isNotEmpty()
                ) {
                    FilterChip(selected = false, onClick = viewModel::clearFilters, label = { Text("✕ Clear") })
                }
            }

            when {
                state.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (state.perBookId == null) "No annotations yet.\nHighlight while reading to see them here."
                            else "No annotations in this book yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.items, key = { it.entity.id }) { item ->
                        AnnotationCard(
                            item = item,
                            showBookTitle = state.perBookId == null,
                            onClick = { openBook(item.entity.bookId) },
                        )
                    }
                }
            }
        }
    }

    if (exportDialog) {
        ExportFormatDialog(
            onDismiss = { exportDialog = false },
            onExport = { format -> viewModel.export(format); exportDialog = false },
        )
    }
}

@Composable
private fun AnnotationCard(
    item: AnnotationItem,
    showBookTitle: Boolean,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp).height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(
                        COLOR_SWATCHES[item.entity.color] ?: MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp),
                    ),
            ) {}
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = buildString {
                            append(item.entity.type.replaceFirstChar { it.uppercase() })
                            if (item.progression != null) append(" · ${(item.progression * 100).toInt()}%")
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        item.entity.updatedAt.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.entity.excerpt.isNotBlank()) {
                    Text(
                        item.entity.excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (item.entity.note.isNotBlank()) {
                    Text(
                        item.entity.note,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (showBookTitle) {
                    Text(
                        item.bookTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (item.entity.annotationTags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        item.entity.annotationTags.take(4).forEach { tag ->
                            Text(
                                "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportFormatDialog(
    onDismiss: () -> Unit,
    onExport: (AnnotationsExporter.Format) -> Unit,
) {
    var selected by remember { mutableStateOf(AnnotationsExporter.Format.MARKDOWN) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export annotations") },
        text = {
            Column {
                AnnotationsExporter.Format.entries.forEach { format ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = format }
                            .padding(vertical = 4.dp),
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected == format,
                            onClick = { selected = format },
                        )
                        Text(format.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(selected) }) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
