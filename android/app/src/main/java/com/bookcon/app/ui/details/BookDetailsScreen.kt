@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.bookcon.app.ui.details

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.core.rememberBookPainter
import com.bookcon.app.core.resolveCoverUrl
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.DownloadState

private sealed interface DetailDlg {
    data object None : DetailDlg
    data object AddToShelf : DetailDlg
    data object ConfirmDelete1 : DetailDlg
    data object ConfirmDelete2 : DetailDlg
    data object ConfirmRemoveOffline : DetailDlg
    data object EditSheet : DetailDlg
}

/**
 * Book details (PRD FR-DET): hero cover, full metadata, progress, annotations count;
 * Read / Download-toggle / Edit sheet / Add-to-shelf / Export annotations /
 * double-confirm delete with undo.
 */
@Composable
fun BookDetailsScreen(
    bookId: String,
    onBack: () -> Unit,
    openReader: () -> Unit,
    openEdit: () -> Unit,
    viewModel: BookDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<DetailDlg>(DetailDlg.None) }
    var editFields by remember { mutableStateOf<BookEditFields?>(null) }

    LaunchedEffect(bookId) { viewModel.bind(bookId) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DetailsEvent.Snackbar -> {
                    if (event.undoBookId != null) {
                        val result = snackbarHostState.showSnackbar(
                            message = event.text,
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short, // ≈4–5 s window per PRD LIB-11
                        )
                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                            viewModel.undoDelete(requireNotNull(event.undoBookId))
                        }
                    } else {
                        snackbarHostState.showSnackbar(event.text)
                    }
                }
                is DetailsEvent.ShareAnnotations ->
                    com.bookcon.app.ui.annotations.AnnotationsExporter.shareText(
                        context = context,
                        subject = event.subject,
                        text = event.text,
                    )
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
                title = {
                    Text(
                        state.book?.title ?: "Book",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { dialog = DetailDlg.ConfirmDelete1 }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete book")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val book = state.book
        when {
            state.loading && book == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            book == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("Book not found.")
                }
            else -> DetailsContent(
                book = book,
                serverUrl = state.serverUrl,
                seriesName = state.seriesName,
                progressPercent = state.progressPercent,
                annotationCount = state.annotationCount,
                modifier = Modifier.padding(padding),
                onRead = { viewModel.markOpened(); openReader() },
                onDownloadToggle = {
                    if (book.downloadState == DownloadState.READY && book.localFile != null) {
                        dialog = DetailDlg.ConfirmRemoveOffline
                    } else {
                        viewModel.download()
                    }
                },
                onEdit = {
                    editFields = BookEditFields(
                        title = book.title,
                        authorsCsv = book.authors.joinToString(", "),
                        description = book.description,
                        language = book.language.orEmpty(),
                        publisher = book.publisher.orEmpty(),
                        publishedDate = book.publishedDate.orEmpty(),
                        seriesName = state.seriesName.orEmpty(),
                        seriesIndex = book.seriesIndex?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }.orEmpty(),
                        tagIds = book.tagIds.toSet(),
                        shelfIds = book.shelfIds.toSet(),
                    )
                    dialog = DetailDlg.EditSheet
                    openEdit() // nav no-op; the sheet lives here (per route contract)
                },
                onAddToShelf = { dialog = DetailDlg.AddToShelf },
                onExportAnnotations = viewModel::exportAnnotations,
            )
        }
    }

    when (dialog) {
        DetailDlg.None -> Unit
        DetailDlg.AddToShelf -> AddToShelfDialog(
            shelves = state.shelves,
            memberOf = state.book?.shelfIds.orEmpty().toSet(),
            onCancel = { dialog = DetailDlg.None },
            onPick = { viewModel.addToShelf(it); dialog = DetailDlg.None },
        )
        // PRD LIB-11 double confirm: the second dialog names what is removed server-side.
        DetailDlg.ConfirmDelete1 -> AlertDialog(
            onDismissRequest = { dialog = DetailDlg.None },
            title = { Text("Delete “${state.book?.title}”?") },
            text = { Text("The book will be removed from your library.") },
            confirmButton = { TextButton(onClick = { dialog = DetailDlg.ConfirmDelete2 }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { dialog = DetailDlg.None }) { Text("Cancel") } },
        )
        DetailDlg.ConfirmDelete2 -> AlertDialog(
            onDismissRequest = { dialog = DetailDlg.None },
            title = { Text("Delete permanently?") },
            text = {
                Text(
                    "This removes the book file AND its annotations on the server. " +
                        "You can undo locally for a few seconds after.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteBook(); dialog = DetailDlg.None }) {
                    Text("Delete permanently", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = DetailDlg.None }) { Text("Keep book") } },
        )
        DetailDlg.ConfirmRemoveOffline -> AlertDialog(
            onDismissRequest = { dialog = DetailDlg.None },
            title = { Text("Remove offline copy?") },
            text = { Text("The downloaded file will be deleted from this device. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeOffline(); dialog = DetailDlg.None }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { dialog = DetailDlg.None }) { Text("Cancel") } },
        )
        DetailDlg.EditSheet -> editFields?.let { fields ->
            EditSheet(
                fields = fields,
                tags = state.tags,
                shelves = state.shelves,
                onChange = { editFields = it },
                onCancel = { dialog = DetailDlg.None },
                onSave = { viewModel.saveEdits(it); dialog = DetailDlg.None },
            )
        }
    }
}

@Composable
private fun DetailsContent(
    book: BookEntity,
    serverUrl: String,
    seriesName: String?,
    progressPercent: Double?,
    annotationCount: Int,
    modifier: Modifier = Modifier,
    onRead: () -> Unit,
    onDownloadToggle: () -> Unit,
    onEdit: () -> Unit,
    onAddToShelf: () -> Unit,
    onExportAnnotations: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(170.dp)
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(14.dp)),
        ) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = rememberBookPainter(resolveCoverUrl(serverUrl, book.coverUrl)),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(book.title, style = MaterialTheme.typography.headlineSmall)
        if (book.authors.isNotEmpty()) {
            Text(
                book.authors.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (seriesName != null || book.seriesIndex != null) {
            Text(
                buildString {
                    append(seriesName ?: "Series")
                    book.seriesIndex?.let { append(" · #${trimIndex(it)}") }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        progressPercent?.let { pct ->
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                )
                Text("  ${pct.toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRead, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.MenuBook, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (book.localFile != null) "Read offline" else "Read")
            }
            OutlinedButton(onClick = onDownloadToggle, enabled = book.downloadState != DownloadState.DOWNLOADING) {
                when (book.downloadState) {
                    DownloadState.DOWNLOADING -> CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    DownloadState.READY -> Icon(Icons.Filled.Check, contentDescription = null)
                    else -> Icon(Icons.Filled.Download, contentDescription = null)
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        book.downloadState == DownloadState.DOWNLOADING -> "Downloading"
                        book.localFile != null -> "Remove offline"
                        else -> "Download"
                    },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
            OutlinedButton(onClick = onAddToShelf, modifier = Modifier.weight(1f)) { Text("Add to shelf") }
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Edit, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Edit")
            }
        }
        OutlinedButton(onClick = onExportAnnotations, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Export annotations ($annotationCount)")
        }

        Spacer(Modifier.height(20.dp))
        MetadataRow("Format", book.format.uppercase())
        MetadataRow("Language", book.language)
        MetadataRow("Publisher", book.publisher)
        MetadataRow("Published", book.publishedDate)
        MetadataRow("Pages", book.pageCount?.toString())
        MetadataRow("Words", book.wordCount?.toString())
        MetadataRow("File size", book.fileSizeBytes?.let { humanBytes(it) })
        MetadataRow("Tags", book.tagIds.size.takeIf { it > 0 }?.let { "$it tag(s)" })
        MetadataRow("Shelves", book.shelfIds.size.takeIf { it > 0 }?.let { "$it shelf/shelves" })
        MetadataRow("Added", formatDate(book.addedAt))
        MetadataRow("Updated", formatDate(book.updatedAt))

        if (book.description.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Description", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(6.dp))
            Text(
                book.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start),
            )
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun MetadataRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

private fun trimIndex(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

internal fun humanBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes.toDouble() / (1 shl 10))
    else -> "$bytes B"
}

private fun formatDate(iso: String?): String? =
    iso?.take(10)?.ifBlank { null }

// --------------------------------------------------------------------------- dialogs & sheet

@Composable
private fun AddToShelfDialog(
    shelves: List<com.bookcon.app.data.local.ShelfEntity>,
    memberOf: Set<String>,
    onCancel: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add to shelf") },
        text = {
            if (shelves.isEmpty()) {
                Text("No shelves yet — create one from the Library's Shelves tab.")
            } else {
                Column {
                    shelves.forEach { shelf ->
                        TextButton(onClick = { onPick(shelf.id) }) {
                            Text(if (shelf.id in memberOf) "${shelf.name} ✓" else shelf.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Close") } },
    )
}

@Composable
private fun EditSheet(
    fields: BookEditFields,
    tags: List<com.bookcon.app.data.local.TagEntity>,
    shelves: List<com.bookcon.app.data.local.ShelfEntity>,
    onChange: (BookEditFields) -> Unit,
    onCancel: () -> Unit,
    onSave: (BookEditFields) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Edit book", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(fields.title, { onChange(fields.copy(title = it)) }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fields.authorsCsv, { onChange(fields.copy(authorsCsv = it)) }, label = { Text("Authors (comma-separated)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fields.description, { onChange(fields.copy(description = it)) }, label = { Text("Description") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(fields.language, { onChange(fields.copy(language = it)) }, label = { Text("Language") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(fields.publisher, { onChange(fields.copy(publisher = it)) }, label = { Text("Publisher") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(fields.publishedDate, { onChange(fields.copy(publishedDate = it)) }, label = { Text("Published (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(fields.seriesIndex, { onChange(fields.copy(seriesIndex = it)) }, label = { Text("Series #") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(fields.seriesName, { onChange(fields.copy(seriesName = it)) }, label = { Text("Series name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Tags", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in fields.tagIds,
                        onClick = {
                            onChange(
                                fields.copy(
                                    tagIds = if (tag.id in fields.tagIds) fields.tagIds - tag.id else fields.tagIds + tag.id,
                                ),
                            )
                        },
                        label = { Text(tag.name) },
                    )
                }
                if (tags.isEmpty()) Text("No tags yet.", style = MaterialTheme.typography.bodySmall)
            }

            Text("Shelves", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shelves.forEach { shelf ->
                    FilterChip(
                        selected = shelf.id in fields.shelfIds,
                        onClick = {
                            onChange(
                                fields.copy(
                                    shelfIds = if (shelf.id in fields.shelfIds) fields.shelfIds - shelf.id else fields.shelfIds + shelf.id,
                                ),
                            )
                        },
                        label = { Text(shelf.name) },
                    )
                }
                if (shelves.isEmpty()) Text("No shelves yet.", style = MaterialTheme.typography.bodySmall)
            }

            Row(modifier = Modifier.padding(bottom = 32.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onSave(fields) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
}
