@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.bookcon.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.ui.components.BookCover
import com.bookcon.app.ui.components.BottomNavBar
import com.bookcon.app.ui.components.NavTab
import com.bookcon.app.ui.theme.BrandColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape

@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // The ViewModel emits every import/shelf/tag/delete result here, but nothing was
    // collecting this channel, so all of it was buffered and the user never saw a
    // single confirmation or failure.
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.Snackbar -> snackbarHostState.showSnackbar(
                    message = event.text,
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }
    var isGridMode by remember { mutableStateOf(true) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showShelfPicker by remember { mutableStateOf(false) }
    var showAuthorPicker by remember { mutableStateOf(false) }
    // PRD LIB-12: the bulk "move to shelf" picker, separate from the filter chip
    // dropdown of the same name.
    var showShelfPickerForSelection by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandColors.PageDark)
    ) {
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(if (uiState.selectedIds.size == 1) "Delete this book?" else "Delete ${uiState.selectedIds.size} books?") },
                text = {
                    Text(
                        "This removes them from your BookCon server and from this device. " +
                            "Local annotations and reading positions go with them, and it cannot be undone here."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            viewModel.deleteSelected()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = BrandColors.Salmon,
                            contentColor = BrandColors.OnSalmon,
                        ),
                    ) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
            )
        }
        // PRD LIB-12: bulk move-to-shelf. Creating a shelf is offered here too,
        // because the ViewModel could always do it and nothing in the app let
        // anyone start — so a first-time user has no shelves to move onto.
        if (showShelfPickerForSelection) {
            MoveSelectedToShelfDialog(
                shelves = uiState.shelves,
                count = uiState.selectedIds.size,
                onPick = { shelfId ->
                    viewModel.moveToShelf(shelfId)
                    showShelfPickerForSelection = false
                },
                onCreate = { name -> viewModel.createShelf(name) },
                onDismiss = { showShelfPickerForSelection = false },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "My Library",
                        style = MaterialTheme.typography.displaySmall,
                        color = BrandColors.TextPrimary
                    )
                    Text(
                        "${uiState.books.size} books",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandColors.TextPrimary
                    )
                }
                Row {
                    IconButton(onClick = { /* Filter */ }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = BrandColors.TextPrimaryDark)
                    }
                    IconButton(onClick = { isGridMode = !isGridMode; viewModel.toggleViewMode() }) {
                        Icon(
                            if (isGridMode) Icons.Default.GridView else Icons.Default.ViewList,
                            contentDescription = "View mode",
                            tint = BrandColors.TextPrimaryDark
                        )
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = BrandColors.TextPrimaryDark)
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sort by title") },
                                onClick = { viewModel.setSort(SortMode.TITLE); showSortMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by date") },
                                onClick = { viewModel.setSort(SortMode.ADDED); showSortMenu = false }
                            )
                        }
                    }
                }
            }

            // Welcome text
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Welcome back", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = BrandColors.TextSecondary)
                Text("Discover your next read", style = MaterialTheme.typography.displaySmall, color = BrandColors.TextPrimary)
            }

            Spacer(Modifier.height(16.dp))

            // Search bar. This used to be a static Box with a placeholder Text: not a
            // text field, not focusable, not tappable, and never filtered anything.
            // The ViewModel already had setSearchText and a debounced query behind it.
            OutlinedTextField(
                value = uiState.searchText,
                onValueChange = viewModel::setSearchText,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = {
                    Text(
                        "Search title or description",
                        color = BrandColors.TextSecondary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = BrandColors.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                },
                trailingIcon = {
                    if (uiState.searchText.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchText("") }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = BrandColors.TextSecondary,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BrandColors.Card,
                    unfocusedContainerColor = BrandColors.Card,
                    focusedBorderColor = BrandColors.Green,
                    unfocusedBorderColor = BrandColors.Divider,
                    cursorColor = BrandColors.Green,
                ),
            )

            Spacer(Modifier.height(16.dp))

            // Filter chips
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { FilterChipItem("EPUB", selectedFilter == "epub") { selectedFilter = if (selectedFilter == "epub") null else "epub"; viewModel.toggleFormat("epub") } }
                item { FilterChipItem("PDF", selectedFilter == "pdf") { selectedFilter = if (selectedFilter == "pdf") null else "pdf"; viewModel.toggleFormat("pdf") } }
                item { FilterChipItem("CBZ", selectedFilter == "cbz") { selectedFilter = if (selectedFilter == "cbz") null else "cbz"; viewModel.toggleFormat("cbz") } }
                // Tag and Shelf used to flip a local flag and stop there: the chip
                // rendered as active while the grid underneath did not change, and
                // the real filters (setTagFilter / setShelfFilter, with counts) had no
                // caller at all. They now open a picker of the tags/shelves that
                // actually exist on this device.
                item {
                    Box {
                        FilterChipItem(
                            label = uiState.tags
                                .firstOrNull { it.id == uiState.filterTagId }
                                ?.name?.let { "Tag: $it" } ?: "Tag",
                            selected = uiState.filterTagId != null,
                            onClick = { showTagPicker = !showTagPicker; showShelfPicker = false },
                        )
                        PickerDropdown(
                            expanded = showTagPicker,
                            onDismiss = { showTagPicker = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("All tags") },
                                onClick = {
                                    viewModel.setTagFilter(null)
                                    showTagPicker = false
                                },
                            )
                            uiState.tags.forEach { tag ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${tag.name} (${uiState.countByTag[tag.id] ?: 0})",
                                        )
                                    },
                                    onClick = {
                                        viewModel.setTagFilter(tag.id)
                                        showTagPicker = false
                                    },
                                )
                            }
                        }
                    }
                }
                item {
                    Box {
                        FilterChipItem(
                            label = uiState.shelves
                                .firstOrNull { it.id == uiState.filterShelfId }
                                ?.name?.let { "Shelf: $it" } ?: "Shelf",
                            selected = uiState.filterShelfId != null,
                            onClick = { showShelfPicker = !showShelfPicker; showTagPicker = false },
                        )
                        PickerDropdown(
                            expanded = showShelfPicker,
                            onDismiss = { showShelfPicker = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("All shelves") },
                                onClick = {
                                    viewModel.setShelfFilter(null)
                                    showShelfPicker = false
                                },
                            )
                            uiState.shelves.forEach { shelf ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "${shelf.name} (${uiState.countByShelf[shelf.id] ?: 0})",
                                        )
                                    },
                                    onClick = {
                                        viewModel.setShelfFilter(shelf.id)
                                        showShelfPicker = false
                                    },
                                )
                            }
                        }
                    }
                }
                // Author filter. The filtering itself was implemented and wired to
                // "Clear filters", but no chip ever set it, so an author could be
                // filtered in the data and never in the UI. Same shape of gap as the
                // tag and shelf pickers had.
                item {
                    Box {
                        FilterChipItem(
                            label = uiState.filterAuthor?.let { "Author: $it" } ?: "Author",
                            selected = uiState.filterAuthor != null,
                            onClick = { showAuthorPicker = !showAuthorPicker; showShelfPicker = false },
                        )
                        PickerDropdown(
                            expanded = showAuthorPicker,
                            onDismiss = { showAuthorPicker = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("All authors") },
                                onClick = {
                                    viewModel.setAuthorFilter(null)
                                    showAuthorPicker = false
                                },
                            )
                            uiState.authors.forEach { author ->
                                DropdownMenuItem(
                                    text = {
                                        Text("$author (${uiState.countByAuthor[author] ?: 0})")
                                    },
                                    onClick = {
                                        viewModel.setAuthorFilter(author)
                                        showAuthorPicker = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Content
            if (uiState.books.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // An empty grid is ambiguous: the library may genuinely be empty,
                    // or every book may have been filtered out by a search or chip.
                    // Telling a reader with 200 books that their "library is empty"
                    // because they tapped "PDF" is worse than useless. totalCount is
                    // the unfiltered count.
                    val filtered = uiState.totalCount > 0
                    Icon(Icons.Default.AutoStories, contentDescription = null, modifier = Modifier.size(104.dp), tint = BrandColors.EmptyGlyph)
                    Spacer(Modifier.height(26.dp))
                    Text(
                        if (filtered) "No books match" else "Your library is empty",
                        style = MaterialTheme.typography.displaySmall,
                        color = BrandColors.TextPrimary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        if (filtered) {
                            "Try a different search term,\nor clear the filters."
                        } else {
                            "Use the Import books button to add\nEPUB, PDF or CBZ files."
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = BrandColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    if (filtered) {
                        Spacer(Modifier.height(20.dp))
                        Surface(
                            onClick = {
                                viewModel.setSearchText("")
                                viewModel.setTagFilter(null)
                                viewModel.setShelfFilter(null)
                                listOf("epub", "pdf", "cbz").forEach(viewModel::toggleFormat)
                            },
                            shape = RoundedCornerShape(20.dp),
                            color = BrandColors.Green,
                        ) {
                            Text(
                                "Clear filters",
                                modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                                color = BrandColors.TextPrimary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            } else {
                // PRD LIB-12 bulk actions.
                //
                // These had no entry point at all: the ViewModel had select-all,
                // bulk move, bulk delete (with undo) and bulk download fully
                // implemented, and nothing in this screen could reach any of it, so
                // a long press on a book did nothing and multi-select was
                // unreachable. Long press enters the mode; a tap then toggles.
                if (uiState.selectionActive) {
                    SelectionActionBar(
                        count = uiState.selectedIds.size,
                        onSelectAll = viewModel::selectAllVisible,
                        onClear = viewModel::clearSelection,
                        onMoveToShelf = { showShelfPickerForSelection = true },
                        onDownload = { viewModel.downloadSelected() },
                        // Books have no undo — they are never pushed, so a delete
                        // cannot be undone by a later sync the way a bookmark can.
                        // One tap of a bulk bar must not be the only barrier.
                        onDelete = { showDeleteConfirm = true },
                    )
                }
                if (isGridMode) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        gridItems(uiState.books) { book ->
                            BookGridItem(
                                book = book,
                                serverUrl = uiState.serverUrl,
                                selected = book.id in uiState.selectedIds,
                                onClick = {
                                    if (uiState.selectionActive) viewModel.toggleSelected(book.id) else onOpenBook(book.id)
                                },
                                onLongClick = { viewModel.onLongPress(book.id) },
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listItems(uiState.books) { book ->
                            BookListItem(
                                book = book,
                                serverUrl = uiState.serverUrl,
                                selected = book.id in uiState.selectedIds,
                                onClick = {
                                    if (uiState.selectionActive) viewModel.toggleSelected(book.id) else onOpenBook(book.id)
                                },
                                onLongClick = { viewModel.onLongPress(book.id) },
                            )
                        }
                    }
                }
            }

            // Import button
            //
            // The bottom nav is a floating overlay pinned to the bottom of the
            // root Box, so this Column needs bottom padding to clear it. Without
            // this the button sits underneath the nav — and because the empty
            // state above uses weight(1f), it was pushed fully off-screen
            // whenever the library had no books.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 104.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                androidx.compose.material3.Button(
                    onClick = { launcher.launch(arrayOf("application/epub+zip", "application/pdf", "application/x-cbz")) },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = BrandColors.Salmon,
                        contentColor = BrandColors.OnSalmon,
                    ),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(20.dp), tint = BrandColors.OnSalmon)
                    Spacer(Modifier.width(8.dp))
                    Text("Import books", fontWeight = FontWeight.Bold, color = BrandColors.OnSalmon)
                }
            }
        }

        // Bottom nav overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp)
                .padding(bottom = 14.dp)
        ) {
            BottomNavBar(
                current = NavTab.Books,
                onSelect = { tab ->
                    when (tab) {
                        NavTab.Home -> onOpenHome()
                        NavTab.Books -> Unit
                        NavTab.Bookmarks -> onOpenBookmarks()
                        NavTab.Profile -> onOpenProfile()
                    }
                },
            )
        }
    }
}

/** Anchored dropdown used by the Tag and Shelf filter chips. */
@Composable
private fun PickerDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) = DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, content = content)

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = BrandColors.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) BrandColors.Green else BrandColors.Divider)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = if (selected) BrandColors.GreenText else BrandColors.TextSecondary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

/** PRD LIB-12: pick a shelf for the current multi-selection, or make a new one. */
@Composable
private fun MoveSelectedToShelfDialog(
    shelves: List<com.bookcon.app.data.local.ShelfEntity>,
    count: Int,
    onPick: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Move book to shelf" else "Move $count books to shelf") },
        text = {
            Column {
                if (shelves.isEmpty()) {
                    Text("No shelves yet — name one below.")
                } else {
                    shelves.forEach { shelf ->
                        TextButton(onClick = { onPick(shelf.id) }) { Text(shelf.name) }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New shelf name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        onCreate(newName.trim())
                        newName = ""
                    },
                    enabled = newName.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Create shelf") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** PRD LIB-12: the bulk-action bar shown while a multi-selection is active. */
@Composable
private fun SelectionActionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onMoveToShelf: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = BrandColors.Card,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (count == 1) "1 selected" else "$count selected",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = BrandColors.TextPrimary,
            )
            Spacer(Modifier.weight(1f))
            SelectionAction("All", onSelectAll)
            SelectionAction("Shelf", onMoveToShelf)
            SelectionAction("Get", onDownload)
            SelectionAction("Delete", onDelete, tint = BrandColors.Salmon)
            SelectionAction("✕", onClear)
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = BrandColors.GreenText,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = BrandColors.Card,
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandColors.Divider),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = tint,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BookGridItem(
    book: BookEntity,
    serverUrl: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (selected) Modifier.background(BrandColors.Green.copy(alpha = 0.18f))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .width(100.dp)
    ) {
        Column {
            BookCover(
                coverUrl = book.coverUrl,
                title = book.title,
                serverUrl = serverUrl,
                cornerRadius = 8.dp,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.height(8.dp))
            Text(book.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = BrandColors.TextPrimaryDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.authors.firstOrNull() ?: "Unknown", style = MaterialTheme.typography.labelSmall, color = BrandColors.TextSecondaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // A tick, not just a tint: a colour wash alone is easy to miss on a dark
        // cover, and in selection mode it is the only thing saying which books
        // the bulk actions will apply to.
        if (selected) {
            Box(
                modifier = Modifier
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(BrandColors.Green),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = androidx.compose.ui.graphics.Color.Black, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BookListItem(
    book: BookEntity,
    serverUrl: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (selected) Modifier.background(BrandColors.Green.copy(alpha = 0.18f))
                else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            BookCover(
                coverUrl = book.coverUrl,
                title = book.title,
                serverUrl = serverUrl,
                cornerRadius = 6.dp,
                modifier = Modifier.width(60.dp).aspectRatio(0.7f).clip(RoundedCornerShape(6.dp))
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(BrandColors.Green),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✓", color = androidx.compose.ui.graphics.Color.Black, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = BrandColors.TextPrimaryDark, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.authors.firstOrNull() ?: "Unknown", style = MaterialTheme.typography.bodyMedium, color = BrandColors.TextSecondaryDark, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
