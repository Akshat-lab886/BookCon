package com.bookcon.app.ui.reader

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bookcon.app.core.AppSettings
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.data.local.BookmarkEntity
import com.bookcon.app.reader.EngineSearchHit
import com.bookcon.app.reader.Locators
import com.bookcon.app.reader.TapAction
import com.bookcon.app.reader.TapZoneGrid
import com.bookcon.app.ui.theme.ReaderThemes
import java.time.OffsetDateTime
import org.readium.r2.shared.publication.Link

// --------------------------------------------------------------------------------- host

/** All overlays: bottom sheets (bookmarks/settings), search overlay and dialogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderPanelsHost(
    state: ReaderUiState,
    settings: AppSettings,
    viewModel: ReaderViewModel,
    editingAnnotation: AnnotationEntity?,
    onDismissAnnotationEditor: () -> Unit,
    showTapZoneEditor: Boolean,
    onDismissTapZoneEditor: () -> Unit,
    onEditTapZones: () -> Unit,
) {
    when (state.panel) {
        ReaderPanel.BOOKMARKS -> {
            ModalBottomSheet(
                onDismissRequest = viewModel::closePanel,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                BookmarksSheetContent(
                    bookmarks = state.bookmarks,
                    onTap = { item ->
                        Locators.fromJsonString(item.locatorJson)?.let(viewModel::jumpTo)
                        viewModel.closePanel()
                    },
                    onDelete = viewModel::deleteBookmark,
                )
            }
        }

        ReaderPanel.SETTINGS -> {
            ModalBottomSheet(
                onDismissRequest = viewModel::closePanel,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                ReaderSettingsSheet(
                    settings = settings,
                    viewModel = viewModel,
                    onEditTapZones = onEditTapZones,
                )
            }
        }

        else -> Unit
    }

    if (state.panel == ReaderPanel.SEARCH) {
        SearchOverlay(
            state = state,
            onQueryChange = viewModel::setSearchQuery,
            onSearch = viewModel::search,
            onSelect = viewModel::selectSearchHit,
            onClose = viewModel::closePanel,
        )
    }

    editingAnnotation?.let { item ->
        AnnotationEditDialog(
            annotation = item,
            onSave = { note ->
                viewModel.updateNote(item.id, note)
                onDismissAnnotationEditor()
            },
            onDelete = {
                viewModel.deleteAnnotation(item.id)
                onDismissAnnotationEditor()
            },
            onDismiss = onDismissAnnotationEditor,
        )
    }

    if (showTapZoneEditor) {
        TapZoneEditorDialog(
            current = TapZoneGrid.fromJson(settings.tapZonesJson),
            onSave = { grid ->
                viewModel.setTapZones(grid)
                onDismissTapZoneEditor()
            },
            onDismiss = onDismissTapZoneEditor,
        )
    }
}

// --------------------------------------------------------------------------------- TOC (RD-9)

@Composable
internal fun TocDrawerContent(
    links: List<Link>,
    activeHref: String,
    onSelect: (href: String, title: String?) -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Contents",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close contents")
            }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            // No keys: TOCs may legally contain duplicate hrefs.
            items(links) { link ->
                TocItem(link, depth = 0, activeHref = activeHref, onSelect = onSelect)
            }
        }
    }
}

/** Recursive TOC entry; the dot marks the chapter containing the current position. */
@Composable
private fun TocItem(
    link: Link,
    depth: Int,
    activeHref: String,
    onSelect: (String, String?) -> Unit,
) {
    val href = Locators.normalizeHref(link.href.toString()).orEmpty()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(link.href.toString(), link.title) }
                .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (activeHref.isNotEmpty() && href == activeHref) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = link.title ?: link.href.toString().substringAfterLast('/'),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        link.children.forEach { child ->
            TocItem(child, depth = depth + 1, activeHref = activeHref, onSelect = onSelect)
        }
    }
}

// --------------------------------------------------------------------------------- search (RD-11)

@Composable
private fun SearchOverlay(
    state: ReaderUiState,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (EngineSearchHit) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search in book") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(state.searchQuery) }),
            )
            if (state.searchQuery.isNotEmpty()) {
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
        if (state.searching) {
            CircularProgressIndicator(Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            state.searchGroups.forEach { group ->
                item(key = "header-${group.href}") {
                    Text(
                        text = group.chapterTitle,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(group.hits) { hit ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(hit) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = hit.excerpt.ifBlank { "(no excerpt)" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
            if (!state.searching && state.searchGroups.isEmpty()) {
                item {
                    Text(
                        text = if (state.searchQuery.isBlank()) {
                            "Type to search inside this book."
                        } else {
                            "No results."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------- bookmarks (RD-10)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheetContent(
    bookmarks: List<BookmarkEntity>,
    onTap: (BookmarkEntity) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            "Bookmarks",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp),
        )
        if (bookmarks.isEmpty()) {
            Text(
                "No bookmarks yet.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn {
            items(bookmarks, key = { it.id }) { item ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) {
                            onDelete(item.id)
                            true
                        } else {
                            false
                        }
                    },
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete bookmark",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTap(item) }
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = item.label.ifBlank { "Bookmark" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = relativeTime(item.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Icon(Icons.Filled.BookmarkBorder, contentDescription = null)
                    }
                }
            }
            }
        }
    }
}

// --------------------------------------------------------------------------------- settings (RD-2…RD-7, RD-14…RD-18)

@Composable
internal fun ReaderSettingsSheet(
    settings: AppSettings,
    viewModel: ReaderViewModel,
    onEditTapZones: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Text", "Layout", "Theme", "System")
    Column(Modifier.padding(bottom = 24.dp)) {
        TabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (tab) {
                0 -> TextTab(settings, viewModel)
                1 -> LayoutTab(settings, viewModel)
                2 -> ThemeTab(settings, viewModel)
                else -> SystemTab(settings, viewModel, onEditTapZones)
            }
        }
    }
}

@Composable
private fun TextTab(settings: AppSettings, viewModel: ReaderViewModel) {
    // RD-2 font family: six bundled open-font names.
    Text("Font family", style = MaterialTheme.typography.titleSmall)
    ReaderFonts.OPTIONS.chunked(3).forEach { rowOptions ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            rowOptions.forEach { option ->
                FilterChip(
                    selected = settings.readerFontFamily.equals(option.label, ignoreCase = true),
                    onClick = { viewModel.setFontFamily(option.label) },
                    label = {
                        Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            repeat(3 - rowOptions.size) { Spacer(Modifier.weight(1f)) }
        }
    }

    // RD-2 size 12–36sp.
    SettingSlider(
        label = "Text size",
        initial = settings.readerFontSizeSp,
        range = 12f..36f,
        steps = 23,
        format = { "%.0fsp".format(it) },
    ) { viewModel.setFontSizeSp(it) }

    // RD-3 weight.
    SettingSlider(
        label = "Weight",
        initial = settings.readerFontWeight,
        range = 300f..800f,
        steps = 4,
        format = { "%.0f".format(it) },
    ) { viewModel.setFontWeight(it) }

    // RD-3 line height 1.0–2.0.
    SettingSlider(
        label = "Line height",
        initial = settings.readerLineHeight,
        range = 1f..2f,
        steps = 19,
        format = { "%.2f".format(it) },
    ) { viewModel.setLineHeight(it) }

    // RD-4 paragraph spacing.
    SettingSlider(
        label = "Paragraph spacing",
        initial = settings.readerParagraphSpacing,
        range = 0f..2f,
        steps = 19,
        format = { "%.1f".format(it) },
    ) { viewModel.setParagraphSpacing(it) }

    // RD-4 letter spacing.
    SettingSlider(
        label = "Letter spacing",
        initial = settings.readerLetterSpacing,
        range = -0.05f..0.25f,
        steps = 5,
        format = { "%.2f".format(it) },
    ) { viewModel.setLetterSpacing(it) }

    // RD-6 alignment.
    Text("Alignment", style = MaterialTheme.typography.titleSmall)
    SegmentedRow(
        options = listOf(
            "start" to "Start",
            "center" to "Center",
            "end" to "End",
            "justify" to "Justify",
        ),
        selected = settings.readerTextAlignment,
    ) { viewModel.setTextAlignment(it) }

    // RD-6 publisher default override.
    SettingSwitch(
        label = "Use publisher defaults",
        checked = settings.readerPublisherDefaults,
    ) { viewModel.setPublisherDefaults(it) }
}

@Composable
private fun LayoutTab(settings: AppSettings, viewModel: ReaderViewModel) {
    // RD-7 margins.
    SettingSlider(
        label = "Horizontal margins",
        initial = settings.readerMarginsHorizontal,
        range = 0f..64f,
        steps = 15,
        format = { "%.0fdp".format(it) },
    ) { viewModel.setMargins(horizontal = it) }

    SettingSlider(
        label = "Vertical margins",
        initial = settings.readerMarginsVertical,
        range = 0f..96f,
        steps = 11,
        format = { "%.0fdp".format(it) },
    ) { viewModel.setMargins(vertical = it) }

    // RD-2 scroll vs paginated.
    Text("Layout mode", style = MaterialTheme.typography.titleSmall)
    SegmentedRow(
        options = listOf("paginated" to "Paginated", "scroll" to "Scroll"),
        selected = settings.readerPaginationMode,
    ) { viewModel.setPaginationMode(it) }

    // RD-2 page turn animation.
    Text("Page turn animation", style = MaterialTheme.typography.titleSmall)
    SegmentedRow(
        options = listOf("none" to "None", "slide" to "Slide", "fade" to "Fade"),
        selected = settings.readerPageTurnAnimation,
    ) { viewModel.setPageTurnAnimation(it) }
}

@Composable
private fun ThemeTab(settings: AppSettings, viewModel: ReaderViewModel) {
    // RD-5 reader themes over the reading surface.
    Text("Reading theme", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ReaderThemes.forEach { (name, theme) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(theme.bg)
                        .border(
                            width = if (settings.readerTheme == name) 3.dp else 1.dp,
                            color = if (settings.readerTheme == name) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                theme.fg.copy(alpha = 0.5f)
                            },
                            shape = CircleShape,
                        )
                        .clickable { viewModel.setReaderTheme(name) },
                )
                Text(
                    name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SystemTab(
    settings: AppSettings,
    viewModel: ReaderViewModel,
    onEditTapZones: () -> Unit,
) {
    // RD-14 brightness; slider 0 == follow system.
    var brightnessPercent by remember(settings.readerBrightness) {
        mutableFloatStateOf((settings.readerBrightness ?: 0f) * 100f)
    }
    SettingSlider(
        label = "Brightness",
        initial = brightnessPercent,
        range = 0f..100f,
        steps = 99,
        format = { if (it <= 0.5f) "Follow system" else "%.0f%%".format(it) },
    ) {
        brightnessPercent = it
        if (it <= 0.5f) {
            viewModel.setBrightness(null)
        } else {
            viewModel.setBrightness((it / 100f).coerceIn(0.03f, 1f))
        }
    }

    // RD-15 orientation lock.
    Text("Orientation", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("system" to "System", "portrait" to "Portrait", "landscape" to "Landscape")
            .forEach { (value, label) ->
                FilterChip(
                    selected = settings.orientationLock == value,
                    onClick = { viewModel.setOrientationLock(value) },
                    label = { Text(label) },
                )
            }
    }

    // RD-18 keep screen on / RD-16 volume-key turns.
    SettingSwitch(
        label = "Keep screen on while reading",
        checked = settings.readerKeepScreenOn,
    ) { viewModel.setKeepScreenOn(it) }

    SettingSwitch(
        label = "Volume keys turn pages",
        checked = settings.volumeKeyTurns,
    ) { viewModel.setVolumeKeyTurns(it) }

    // RD-8 tap zone editor entry point.
    OutlinedButton(onClick = onEditTapZones) {
        Text("Customize tap zones…")
    }
}

// --------------------------------------------------------------------------------- setting widgets

@Composable
private fun SettingSlider(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var current by remember(initial) { mutableFloatStateOf(initial) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(format(current), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = { current = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onCommit(current) },
        )
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(label, maxLines = 1)
            }
        }
    }
}

// --------------------------------------------------------------------------------- annotations (ANN-1/2/3/4)

/** Highlight palette keys match AnnotationEntity.color strings. */
val HIGHLIGHT_COLORS: List<Pair<String, Color>> = listOf(
    "yellow" to Color(0xFFF7C948),
    "green" to Color(0xFF8BC34A),
    "blue" to Color(0xFF64B5F6),
    "red" to Color(0xFFE57373),
    "purple" to Color(0xFFBA68C8),
    "orange" to Color(0xFFFFB74D),
)

fun annotationColor(name: String): Color =
    HIGHLIGHT_COLORS.firstOrNull { it.first == name }?.second ?: HIGHLIGHT_COLORS.first().second

/** ANN-1/ANN-2 popup shown over the reading surface during a text selection. */
@Composable
internal fun SelectionToolbar(
    onSave: (color: String, note: String) -> Unit,
    onCancel: () -> Unit,
    onAiAction: ((action: String, excerpt: String) -> Unit)? = null,
    excerpt: () -> String = { "" },
) {
    var selectedColor by remember { mutableStateOf(HIGHLIGHT_COLORS.first().first) }
    var note by remember { mutableStateOf("") }
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HIGHLIGHT_COLORS.forEach { (name, color) ->
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (selectedColor == name) 3.dp else 1.dp,
                                color = if (selectedColor == name) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    Color.Gray
                                },
                                shape = CircleShape,
                            )
                            .clickable { selectedColor = name },
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Quick note (optional)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
            if (onAiAction != null && excerpt().length > 2) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { onAiAction("explain", excerpt()) }) { Text("✨ Explain") }
                    TextButton(onClick = { onAiAction("translate", excerpt()) }) { Text("🌐 Translate") }
                    TextButton(onClick = { onAiAction("summarize", excerpt()) }) { Text("Summarize") }
                }
            }
            Button(
                onClick = { onSave(selectedColor, note.trim()) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Save highlight")
            }
        }
    }
}

/** ANN-3: edit or delete an existing annotation. */
@Composable
internal fun AnnotationEditDialog(
    annotation: AnnotationEntity,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var note by remember(annotation.id) { mutableStateOf(annotation.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Annotation") },
        text = {
            Column {
                if (annotation.excerpt.isNotBlank()) {
                    Text(
                        annotation.excerpt,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note.trim()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// --------------------------------------------------------------------------------- tap zones editor (RD-8)

@Composable
internal fun TapZoneEditorDialog(
    current: TapZoneGrid,
    onSave: (TapZoneGrid) -> Unit,
    onDismiss: () -> Unit,
) {
    var grid by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tap zones") },
        text = {
            Column {
                Text(
                    "Tap a cell to cycle its action.",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    grid.cells.chunked(TapZoneGrid.GRID_SIZE).forEachIndexed { rowIndex, rowCells ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowCells.forEachIndexed { columnIndex, _ ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1.15f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outline,
                                            RoundedCornerShape(8.dp),
                                        )
                                        .clickable {
                                            val next = when (
                                                grid.actionFor(rowIndex, columnIndex)
                                            ) {
                                                TapAction.NONE -> TapAction.PREV_PAGE
                                                TapAction.PREV_PAGE -> TapAction.NEXT_PAGE
                                                TapAction.NEXT_PAGE -> TapAction.TOGGLE_CHROME
                                                TapAction.TOGGLE_CHROME -> TapAction.NONE
                                            }
                                            val cells = grid.cells.toMutableList().also {
                                                it[rowIndex * TapZoneGrid.GRID_SIZE + columnIndex] = next
                                            }
                                            grid = TapZoneGrid(cells)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        tapActionLabel(grid.actionFor(rowIndex, columnIndex)),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { grid = TapZoneGrid.rightHanded() }) {
                        Text("Right-handed preset")
                    }
                    TextButton(onClick = { grid = TapZoneGrid.leftHanded() }) {
                        Text("Left-handed preset")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(grid) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun tapActionLabel(action: TapAction): String = when (action) {
    TapAction.NONE -> "—"
    TapAction.PREV_PAGE -> "< Prev"
    TapAction.NEXT_PAGE -> "Next >"
    TapAction.TOGGLE_CHROME -> "Chrome"
}

// --------------------------------------------------------------------------------- helpers

internal fun relativeTime(iso: String?): String = try {
    val epochMillis = iso?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() } ?: return ""
    DateUtils.getRelativeTimeSpanString(
        epochMillis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
} catch (_: Exception) {
    ""
}
