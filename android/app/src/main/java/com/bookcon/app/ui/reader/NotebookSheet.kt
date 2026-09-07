package com.bookcon.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.reader.NoteTextBlock
import com.bookcon.app.reader.PdfInkTool
import androidx.compose.material3.FilterChip
import com.bookcon.app.ui.components.PillButton
import java.util.UUID
import com.bookcon.app.reader.NoteContent

private val NOTE_COLORS = listOf("#FACC15", "#F87171", "#4ADE80", "#60A5FA", "#1C1B1F")

private fun noteColorFromHex(hex: String): Color =
    runCatching {
        val argb = android.graphics.Color.parseColor(hex)
        Color(
            red = (argb shr 16 and 0xFF) / 255f,
            green = (argb shr 8 and 0xFF) / 255f,
            blue = (argb and 0xFF) / 255f,
            alpha = 1f,
        )
    }.getOrDefault(Color(0xFFFACC15))

/**
 * Notebook sheet (v1.5): slides up over the reading surface. One mixed-canvas
 * note per book page — type text blocks and/or draw with pen/marker; the note
 * autosaves and remembers which page it belongs to.
 */
@Composable
fun NotebookSheet(
    viewModel: ReaderViewModel,
    modifier: Modifier = Modifier,
) {
    val st by viewModel.notebook.collectAsStateWithLifecycle()
    if (!st.open) return

    // Style toggles for the next typed block.
    var blockStyle by remember { mutableStateOf("body") }
    var bold by remember { mutableStateOf(false) }
    var italic by remember { mutableStateOf(false) }
    var underline by remember { mutableStateOf(false) }
    // Text being typed for the newest block.
    var draft by remember(st.activePage, st.pages) { mutableStateOf("") }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // ---- Header: title + page nav + close --------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::closeNotebook) {
                    Icon(Icons.Filled.Close, contentDescription = "Close notebook", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text("Notebook", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text(
                        st.pageLabel.ifBlank { "Note" },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
                if (st.pages.isNotEmpty()) {
                    TextButton(onClick = {
                        val idx = st.pages.indexOf(st.activePage)
                        val prev = if (idx <= 0) st.pages.last() else st.pages[idx - 1]
                        viewModel.openNotebookPage(prev)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous note page", tint = Color.White)
                    }
                    Text(
                        "${st.pages.indexOf(st.activePage).coerceAtLeast(0) + 1}/${st.pages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                    TextButton(onClick = {
                        val idx = st.pages.indexOf(st.activePage)
                        val next = if (idx == -1 || idx >= st.pages.size - 1) st.pages.first() else st.pages[idx + 1]
                        viewModel.openNotebookPage(next)
                    }) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next note page", tint = Color.White)
                    }
                }
            }

            // ---- Existing content canvas (typed blocks + strokes) ----------
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (st.content.isEmpty) {
                    item {
                        Text(
                            "Notes for this page. Type below or switch to Draw.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(st.content.blocks, key = { it.id }) { block ->
                    NoteBlockRow(
                        block = block,
                        onDelete = {
                            viewModel.saveNotebookContent(
                                st.content.copy(blocks = st.content.blocks.filterNot { it.id == block.id }),
                            )
                        },
                    )
                }
                if (st.content.strokes.isNotEmpty()) {
                    item {
                        StrokeCanvas(
                            strokes = st.content.strokes,
                            tool = PdfInkTool.NONE,
                            colorHex = st.color,
                            onStrokeFinished = {},
                            onEraseStroke = viewModel::eraseNotebookStroke,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }

            // ---- Input / tool area -----------------------------------------
            when (st.tool) {
                PdfInkTool.NONE -> {
                    // Type mode: style chips row + text field + Add.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = blockStyle == "heading",
                            onClick = { blockStyle = if (blockStyle == "heading") "body" else "heading" },
                            label = { Text("Title", style = MaterialTheme.typography.labelSmall) },
                        )
                        IconButton(onClick = { bold = !bold }) {
                            Icon(
                                Icons.Filled.FormatBold, contentDescription = "Bold",
                                tint = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { italic = !italic }) {
                            Icon(
                                Icons.Filled.FormatItalic, contentDescription = "Italic",
                                tint = if (italic) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { underline = !underline }) {
                            Icon(
                                Icons.Filled.FormatUnderlined, contentDescription = "Underline",
                                tint = if (underline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (st.saving) "Saving…" else "Saved",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder = { Text("Type a note…") },
                            modifier = Modifier.weight(1f),
                            minLines = 1,
                            maxLines = 4,
                        )
                        PillButton(
                            text = "Add",
                            onClick = {
                                if (draft.isNotBlank()) {
                                    val block = NoteTextBlock(
                                        text = draft.trim(),
                                        style = blockStyle,
                                        bold = bold,
                                        italic = italic,
                                        underline = underline,
                                    )
                                    viewModel.saveNotebookContent(st.content.copy(blocks = st.content.blocks + block))
                                    draft = ""
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                PdfInkTool.ERASER -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Touch a stroke to erase it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = viewModel::undoNotebookStroke) { Text("Undo stroke") }
                        PillButton(text = "Done", onClick = { viewModel.setNotebookTool(PdfInkTool.NONE) })
                    }
                }
                else -> {
                    // Draw mode: color dots + live stroke canvas.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NOTE_COLORS.forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(if (hex == st.color) 26.dp else 20.dp)
                                    .background(noteColorFromHex(hex), CircleShape)
                                    .then(
                                        if (hex == st.color) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { viewModel.setNotebookColor(hex) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = viewModel::undoNotebookStroke) { Text("Undo") }
                        TextButton(onClick = { viewModel.setNotebookTool(PdfInkTool.ERASER) }) { Text("Erase") }
                        TextButton(onClick = { viewModel.setNotebookTool(PdfInkTool.NONE) }) { Text("Type") }
                    }
                    StrokeCanvas(
                        strokes = st.content.strokes,
                        tool = st.tool,
                        colorHex = st.color,
                        onStrokeFinished = viewModel::addNotebookStroke,
                        onEraseStroke = viewModel::eraseNotebookStroke,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .height(220.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                    )
                }
            }

            // ---- Mode switcher ---------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = st.tool == PdfInkTool.NONE,
                    onClick = { viewModel.setNotebookTool(PdfInkTool.NONE) },
                    label = { Text("⌨ Type") },
                )
                FilterChip(
                    selected = st.tool == PdfInkTool.PEN,
                    onClick = { viewModel.setNotebookTool(PdfInkTool.PEN) },
                    label = { Text("✒ Pen") },
                )
                FilterChip(
                    selected = st.tool == PdfInkTool.HIGHLIGHTER,
                    onClick = { viewModel.setNotebookTool(PdfInkTool.HIGHLIGHTER) },
                    label = { Text("🖍 Marker") },
                )
            }
        }
    }
}

@Composable
private fun NoteBlockRow(block: NoteTextBlock, onDelete: () -> Unit) {
    val style = when (block.style) {
        "heading" -> MaterialTheme.typography.titleLarge
        "title" -> MaterialTheme.typography.headlineSmall
        else -> MaterialTheme.typography.bodyLarge
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = block.text,
            style = style.copy(
                fontWeight = if (block.bold) androidx.compose.ui.text.font.FontWeight.Bold else style.fontWeight,
                fontStyle = if (block.italic) androidx.compose.ui.text.font.FontStyle.Italic else style.fontStyle,
                textDecoration = if (block.underline) TextDecoration.Underline else null,
            ),
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete note block", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Mixed-canvas stroke surface: draws saved strokes + captures new ones with the
 * active tool (pen solid / marker translucent wide). In ERASER mode taps remove
 * the nearest stroke; otherwise it is display-only.
 */
@Composable
private fun StrokeCanvas(
    strokes: List<com.bookcon.app.reader.PdfInkStroke>,
    tool: PdfInkTool,
    colorHex: String,
    onStrokeFinished: (List<Float>) -> Unit,
    onEraseStroke: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    val livePoints = remember { mutableStateListOf<Offset>() }

    fun normalize(points: List<Offset>): List<Float> {
        val w = canvasSize.value.width.toFloat()
        val h = canvasSize.value.height.toFloat()
        if (w <= 0f || h <= 0f || points.isEmpty()) return emptyList()
        return points.flatMap { listOf(it.x / w, it.y / h) }
    }

    fun hitStroke(p: Offset): com.bookcon.app.reader.PdfInkStroke? {
        val w = canvasSize.value.width.toFloat()
        val h = canvasSize.value.height.toFloat()
        if (w <= 0f || h <= 0f || strokes.isEmpty()) return null
        val threshold = with(density) { 14.dp.toPx() }
        var best: com.bookcon.app.reader.PdfInkStroke? = null
        var bestDist = Float.MAX_VALUE
        for (stroke in strokes.asReversed()) {
            val pts = stroke.points
            if (pts.size < 4) continue
            var i = 0
            while (i + 3 < pts.size) {
                val a = Offset(pts[i] * w, pts[i + 1] * h)
                val b = Offset(pts[i + 2] * w, pts[i + 3] * h)
                val d = distanceToSegment(p, a, b)
                if (d < bestDist) {
                    bestDist = d
                    best = stroke
                }
                i += 2
            }
        }
        return best.takeIf { bestDist <= threshold + (best?.width ?: 0f) * density.density / 2f }
    }

    val gesture = when (tool) {
        PdfInkTool.PEN, PdfInkTool.HIGHLIGHTER -> Modifier.pointerInput(tool, colorHex) {
            detectDragGestures(
                onDragStart = { offset -> livePoints.clear(); livePoints.add(offset) },
                onDrag = { change, _ ->
                    change.consume()
                    livePoints.add(change.position)
                },
                onDragEnd = {
                    onStrokeFinished(normalize(livePoints.toList()))
                    livePoints.clear()
                },
                onDragCancel = { livePoints.clear() },
            )
        }
        PdfInkTool.ERASER -> Modifier.pointerInput(strokes) {
            detectDragGestures(
                onDragStart = { offset -> hitStroke(offset)?.let { onEraseStroke(it.id) } },
                onDrag = { change, _ ->
                    change.consume()
                    hitStroke(change.position)?.let { onEraseStroke(it.id) }
                },
            )
        }
        else -> Modifier
    }

    Box(
        modifier = modifier
            .onSizeChanged { canvasSize.value = it }
            .then(gesture),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (stroke in strokes) drawStrokeC(stroke)
            if (livePoints.isNotEmpty() && (tool == PdfInkTool.PEN || tool == PdfInkTool.HIGHLIGHTER)) {
                drawStrokeC(
                    com.bookcon.app.reader.PdfInkStroke(
                        id = "live",
                        page = -1,
                        color = colorHex,
                        width = if (tool == PdfInkTool.HIGHLIGHTER) 18f else 3f,
                        points = normalize(livePoints.toList()),
                        mode = if (tool == PdfInkTool.HIGHLIGHTER) "highlighter" else "pen",
                    ),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStrokeC(stroke: com.bookcon.app.reader.PdfInkStroke) {    val pts = stroke.points
    if (pts.size < 4) return
    val path = Path()
    path.moveTo(pts[0] * size.width, pts[1] * size.height)
    var i = 2
    while (i + 1 < pts.size) {
        path.lineTo(pts[i] * size.width, pts[i + 1] * size.height)
        i += 2
    }
    val highlighter = stroke.mode == "highlighter"
    drawPath(
        path = path,
        color = noteColorFromHex(stroke.color).copy(alpha = if (highlighter) 0.35f else 1f),
        style = Stroke(
            width = stroke.width * density,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}
