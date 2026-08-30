@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.bookcon.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.core.rememberBookPainter
import com.bookcon.app.core.resolveCoverUrl
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.DownloadState
import com.bookcon.app.ui.components.AppTopBar
import com.bookcon.app.ui.components.BookCard as BookCardNew
import com.bookcon.app.ui.components.BookCover
import com.bookcon.app.ui.components.BookListRow
import com.bookcon.app.ui.components.FormatChip
import com.bookcon.app.ui.components.PillButton
import com.bookcon.app.ui.components.SearchField
import com.bookcon.app.ui.components.SectionHeader

private sealed interface Dlg {
    data object None : Dlg
    data object CreateShelf : Dlg
    data object CreateTag : Dlg
    data object CreateSeries : Dlg
    data object MoveToShelf : Dlg
    data object ConfirmDeleteSelected : Dlg
}

/**
 * Library home (PRD FR-LIB): instant search + sort + view modes, continue-reading carousel,
 * AND filters (format/tag/shelf/author), bulk-select actions, SAF import, and the
 * Shelves/Series/Tags tabs implemented as in-screen filter states.
 *
 * v1.3 redesign: blue AppTopBar, greeting header, pill search, SectionHeader
 * for "Continue reading" and "All books", BookListRow for the list view.
 */
@Composable
fun LibraryScreen(
    openDetails: (String) -> Unit,
    openReader: (String) -> Unit,
    openSettings: () -> Unit,
    openAnnotations: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<Dlg>(Dlg.None) }
    var pendingDelete by remember { mutableStateOf<Pair<OrgItem, String>?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        viewModel.importUris(uris)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Snackbar -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    Scaffold(
        topBar = {
            if (!state.selectionActive) {
                AppTopBar(
                    title = "My Library",
                    subtitle = "${state.books.size} book${if (state.books.size == 1) "" else "s"}",
                    actions = {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(
                                Icons.Filled.Sort,
                                contentDescription = "Sort",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = viewModel::toggleViewMode) {
                            Icon(
                                imageVector = if (state.gridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = if (state.gridView) "Switch to list view" else "Switch to grid view",
                                tint = Color.White,
                            )
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "More options",
                                    tint = Color.White,
                                )
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("All annotations") },
                                    onClick = { overflowOpen = false; openAnnotations() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    onClick = { overflowOpen = false; openSettings() },
                                )
                            }
                        }
                        // DropdownMenu for Sort is rendered at root because DropdownMenu
                        // can't be a direct child of AppTopBar's actions slot.
                        DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    trailingIcon = {
                                        if (mode == state.sort) Icon(Icons.Filled.Check, contentDescription = null)
                                    },
                                    onClick = {
                                        viewModel.setSort(mode)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                AppTopBar(
                    title = "${state.selectedIds.size} selected",
                    onBack = viewModel::clearSelection,
                    actions = {
                        IconButton(onClick = viewModel::selectAllVisible) {
                            Icon(
                                Icons.Filled.SelectAll,
                                contentDescription = "Select all",
                                tint = Color.White,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selectionActive) {
                NavigationBar {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { dialog = Dlg.MoveToShelf }) { Text("Shelf") }
                    TextButton(onClick = viewModel::downloadSelected) { Text("Download") }
                    IconButton(onClick = { dialog = Dlg.ConfirmDeleteSelected }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete selected", tint = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.width(8.dp))
                }
            } else {
                NavigationBar {
                    LibTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { viewModel.setTab(tab) },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        LibTab.LIBRARY -> Icons.Filled.Home
                                        LibTab.SHELVES -> Icons.Filled.Bookmarks
                                        LibTab.SERIES -> Icons.AutoMirrored.Filled.List
                                        LibTab.TAGS -> Icons.Filled.Label
                                    },
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (state.tab == LibTab.LIBRARY && !state.selectionActive) {
                PillButton(
                    text = "Import books",
                    icon = Icons.Filled.FileUpload,
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/pdf",
                                "application/vnd.comicbook+zip",
                                "application/octet-stream",
                            ),
                        )
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when (state.tab) {
            LibTab.LIBRARY -> LibraryTabContent(
                state = state,
                modifier = Modifier.padding(padding),
                onSearch = viewModel::setSearchText,
                onToggleFormat = viewModel::toggleFormat,
                onClearFilters = viewModel::clearFilters,
                onSetTagFilter = viewModel::setTagFilter,
                onSetShelfFilter = viewModel::setShelfFilter,
                onSetAuthorFilter = viewModel::setAuthorFilter,
                onOpenDetails = openDetails,
                onOpenReader = openReader,
                onLongPress = viewModel::onLongPress,
            )
            LibTab.SHELVES -> OrganizeTabContent(
                title = "Shelves",
                hint = "Tapping a shelf filters the library.",
                items = state.shelves.map { OrgItem(it.id, it.name, state.countByShelf[it.id] ?: 0) },
                emptyHint = "No shelves yet. Group your books into collections.",
                onCreate = { dialog = Dlg.CreateShelf },
                onSelect = viewModel::setShelfFilter,
                activeId = state.filterShelfId,
                modifier = Modifier.padding(padding),
            )
            LibTab.SERIES -> OrganizeTabContent(
                title = "Series",
                hint = "Long-press a series to delete it.",
                items = state.series.map { OrgItem(it.id, it.name, state.countBySeries[it.id] ?: 0) },
                emptyHint = "No series yet.",
                onCreate = { dialog = Dlg.CreateSeries },
                onSelect = null,
                activeId = null,
                onDelete = { id -> state.series.find { it.id == id }?.let { pendingDelete = OrgItem(it.id, it.name, 0) to "series" } },
                modifier = Modifier.padding(padding),
            )
            LibTab.TAGS -> OrganizeTabContent(
                title = "Tags",
                hint = "Tap to filter. Long-press to delete.",
                items = state.tags.map { OrgItem(it.id, it.name, state.countByTag[it.id] ?: 0) },
                emptyHint = "No tags yet. Tag books to slice your library.",
                onCreate = { dialog = Dlg.CreateTag },
                onSelect = viewModel::setTagFilter,
                activeId = state.filterTagId,
                onDelete = { id -> state.tags.find { it.id == id }?.let { pendingDelete = OrgItem(it.id, it.name, 0) to "tag" } },
                modifier = Modifier.padding(padding),
            )
        }
    }

    // ---- dialogs ---------------------------------------------------------------
    when (dialog) {
        Dlg.None -> Unit
        Dlg.CreateShelf -> NameDialog("New shelf", onCancel = { dialog = Dlg.None }) { viewModel.createShelf(it); dialog = Dlg.None }
        Dlg.CreateTag -> NameDialog("New tag", onCancel = { dialog = Dlg.None }) { viewModel.createTag(it); dialog = Dlg.None }
        Dlg.CreateSeries -> NameDialog("New series", onCancel = { dialog = Dlg.None }) { viewModel.createSeries(it); dialog = Dlg.None }
        else -> Unit
    }
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.second}?") },
            text = { Text("Delete \"${target.first.name}\"? Your books won't be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    if (target.second == "series") viewModel.deleteSeries(target.first.id)
                    else viewModel.deleteTag(target.first.id)
                    pendingDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    when (dialog) {
        Dlg.None, Dlg.CreateShelf, Dlg.CreateTag, Dlg.CreateSeries -> Unit
        Dlg.MoveToShelf -> MoveToShelfDialog(
            shelves = state.shelves,
            onCancel = { dialog = Dlg.None },
            onPick = { shelfId -> viewModel.moveToShelf(shelfId); dialog = Dlg.None },
        )
        is Dlg.ConfirmDeleteSelected -> AlertDialog(
            onDismissRequest = { dialog = Dlg.None },
            title = { Text("Delete ${state.selectedIds.size} book${if (state.selectedIds.size == 1) "" else "s"}?") },
            text = {
                Text(
                    "These books are removed from your library here and on the server. " +
                        "Downloaded files stay until removed from Settings → Storage.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSelected(); dialog = Dlg.None }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { dialog = Dlg.None }) { Text("Cancel") } },
        )
    }
}

// --------------------------------------------------------------------------- library tab

@Composable
private fun LibraryTabContent(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    onSearch: (String) -> Unit,
    onToggleFormat: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSetTagFilter: (String?) -> Unit,
    onSetShelfFilter: (String?) -> Unit,
    onSetAuthorFilter: (String?) -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenReader: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Greeting header (v1.3)
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Welcome back",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Discover your next read",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        SearchField(
            value = state.searchText,
            onValueChange = onSearch,
            placeholder = "Search title or description",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val anyFilter = state.filterFormats.isNotEmpty() || state.filterTagId != null ||
            state.filterShelfId != null || state.filterAuthor != null
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            listOf("epub", "pdf", "cbz").forEach { format ->
                FilterChip(
                    selected = format in state.filterFormats,
                    onClick = { onToggleFormat(format) },
                    label = { Text(format.uppercase()) },
                )
            }

            // Tag filter menu.
            Box {
                var expanded by remember { mutableStateOf(false) }
                FilterChip(
                    selected = state.filterTagId != null,
                    onClick = { expanded = true },
                    label = { Text(state.tags.firstOrNull { it.id == state.filterTagId }?.name ?: "Tag") },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Any tag") }, onClick = { onSetTagFilter(null); expanded = false })
                    state.tags.forEach { tag ->
                        DropdownMenuItem(
                            text = { Text(tag.name) },
                            onClick = { onSetTagFilter(tag.id); expanded = false },
                        )
                    }
                }
            }

            // Shelf filter menu.
            Box {
                var expanded by remember { mutableStateOf(false) }
                FilterChip(
                    selected = state.filterShelfId != null,
                    onClick = { expanded = true },
                    label = { Text(state.shelves.firstOrNull { it.id == state.filterShelfId }?.name ?: "Shelf") },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Any shelf") }, onClick = { onSetShelfFilter(null); expanded = false })
                    state.shelves.forEach { shelf ->
                        DropdownMenuItem(
                            text = { Text(shelf.name) },
                            onClick = { onSetShelfFilter(shelf.id); expanded = false },
                        )
                    }
                }
            }

            // Author filter menu (derived from loaded books).
            Box {
                var expanded by remember { mutableStateOf(false) }
                FilterChip(
                    selected = state.filterAuthor != null,
                    onClick = { expanded = true },
                    label = { Text(state.filterAuthor ?: "Author") },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text("Any author") }, onClick = { onSetAuthorFilter(null); expanded = false })
                    state.authors.forEach { author ->
                        DropdownMenuItem(
                            text = { Text(author) },
                            onClick = { onSetAuthorFilter(author); expanded = false },
                        )
                    }
                }
            }

            if (anyFilter) {
                FilterChip(selected = false, onClick = onClearFilters, label = { Text("✕ Clear") })
            }
        }

        if (state.continueReading.isNotEmpty()) {
            SectionHeader(
                title = "Continue reading",
                actionLabel = if (state.continueReading.size > 3) "See more" else null,
                onAction = null,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                listItems(state.continueReading, key = { it.id }) { book ->
                    ContinueCard(
                        book = book,
                        serverUrl = state.serverUrl,
                        progress = state.progressPercent[book.id],
                        onClick = { onOpenReader(book.id) },
                    )
                }
            }
        }

        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.books.isEmpty() -> com.bookcon.app.ui.components.EmptyState(
                title = if (state.searchText.isNotBlank() || anyFilter) "No matches" else "Your library is empty",
                message = if (state.searchText.isNotBlank() || anyFilter) {
                    "Try a different search or clear the filters."
                } else {
                    "Use the Import books button to add EPUB, PDF or CBZ files."
                },
                illustration = Icons.Filled.AutoStories,
            )
            state.gridView -> BooksGrid(
                state = state,
                modifier = Modifier.weight(1f),
                onOpenDetails = onOpenDetails,
                onLongPress = onLongPress,
            )
            else -> BooksList(
                state = state,
                modifier = Modifier.weight(1f),
                onOpenDetails = onOpenDetails,
                onLongPress = onLongPress,
            )
        }
    }
}

@Composable
private fun BooksGrid(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        gridItems(state.books, key = { it.id }) { book ->
            BookCardGrid(
                book = book,
                serverUrl = state.serverUrl,
                progress = state.progressPercent[book.id],
                selected = book.id in state.selectedIds,
                onClick = { onOpenDetails(book.id) },
                onLongClick = { onLongPress(book.id) },
            )
        }
    }
}

@Composable
private fun BooksList(
    state: LibraryUiState,
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit,
    onLongPress: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        listItems(state.books, key = { it.id }) { book ->
            BookListRow(
                title = book.title,
                subtitle = book.authors.joinToString(", ").ifBlank { book.format.uppercase() },
                coverUrl = book.coverUrl,
                serverUrl = state.serverUrl,
                onClick = { onOpenDetails(book.id) },
                format = book.format,
                trailing = {
                    androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                        when (book.downloadState) {
                            DownloadState.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            DownloadState.READY -> Icon(Icons.Filled.CheckCircle, contentDescription = "Downloaded", tint = MaterialTheme.colorScheme.primary)
                            DownloadState.FAILED -> Icon(Icons.Filled.Error, contentDescription = "Download failed", tint = MaterialTheme.colorScheme.error)
                            else -> Unit
                        }
                        if (book.id in state.selectedIds) {
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenDetails(book.id) },
                    onLongClick = { onLongPress(book.id) },
                ),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun BookCardGrid(
    book: BookEntity,
    serverUrl: String,
    progress: Double?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)),
            ) {
                BookCover(
                    coverUrl = book.coverUrl,
                    title = book.title,
                    serverUrl = serverUrl,
                    modifier = Modifier.fillMaxWidth().aspectRatio(0.7f),
                    cornerRadius = 0.dp,
                )
                FormatChip(
                    book.format,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    background = Color.Black.copy(alpha = 0.6f),
                    foreground = Color.White,
                )
                if (progress != null && progress > 0.0) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp),
                    ) {
                        Text(
                            "${progress.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(Modifier.padding(10.dp)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.authors.isNotEmpty()) {
                    Text(
                        book.authors.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { (progress / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(
    book: BookEntity,
    serverUrl: String,
    progress: Double?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(112.dp)
            .clickable(onClick = onClick),
    ) {
        BookCover(
            coverUrl = book.coverUrl,
            title = book.title,
            serverUrl = serverUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f),
            cornerRadius = 10.dp,
        )
        LinearProgressIndicator(
            progress = { ((progress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp),
        )
        Text(
            book.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

// --------------------------------------------------------------------------- organize tabs

data class OrgItem(val id: String, val name: String, val count: Int)

@Composable
private fun OrganizeTabContent(
    title: String,
    hint: String?,
    items: List<OrgItem>,
    emptyHint: String,
    onCreate: () -> Unit,
    onSelect: ((String) -> Unit)?,
    activeId: String?,
    modifier: Modifier = Modifier,
    onDelete: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
            listItems(items, key = { it.id }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onSelect?.invoke(item.id) },
                            onLongClick = { onDelete?.invoke(item.id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        item.name + if (item.id == activeId) "  ✓" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "${item.count} book${if (item.count == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

// --------------------------------------------------------------------------- dialogs

@Composable
private fun NameDialog(title: String, onCancel: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun MoveToShelfDialog(
    shelves: List<com.bookcon.app.data.local.ShelfEntity>,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Move to shelf") },
        text = {
            if (shelves.isEmpty()) {
                Text("No shelves yet — create one from the Shelves tab first.")
            } else {
                Column {
                    shelves.forEach { shelf ->
                        Text(
                            shelf.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(shelf.id) }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
