package com.bookcon.app.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.bookcon.app.reader.PdfInkStroke
import com.bookcon.app.reader.PdfInkTool

/** Ink colors offered in the toolbar (INK-2). */
val INK_COLORS = listOf(
    "#FACC15", // yellow marker
    "#F87171", // red
    "#4ADE80", // green
    "#60A5FA", // blue
    "#1C1B1F", // near-black pen
)

private fun colorFromHex(hex: String): Color =
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
 * Transparent overlay capturing ink on top of a PDF page (INK-1).
 *
 * Coordinates are stored normalized (0..1 relative to the page view) so strokes
 * re-scale across screen sizes. While [tool] is NONE the layer is fully
 * pass-through; while a tool is active it consumes drags so page-turn taps don't
 * fire mid-stroke.
 */
@Composable
fun PdfInkLayer(
    strokes: List<PdfInkStroke>,
    tool: PdfInkTool,
    colorHex: String,
    anchorKey: String,
    onStrokeFinished: (anchorKey: String, mode: String, points: List<Float>) -> Unit,
    onEraseStroke: (anchorKey: String, strokeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val canvasSize = remember { mutableStateOf(IntSize.Zero) }
    val livePoints = remember { mutableStateListOf<Offset>() }

    fun normalize(points: List<Offset>): List<Float> {
        val w = canvasSize.value.width.toFloat()
        val h = canvasSize.value.height.toFloat()
        if (w <= 0f || h <= 0f || points.isEmpty()) return emptyList()
        return points.flatMap { listOf(it.x / w, it.y / h) }
    }

    /** Stroke whose polyline passes nearest [p] (within a finger-sized threshold). */
    fun hitStroke(p: Offset): PdfInkStroke? {
        val w = canvasSize.value.width.toFloat()
        val h = canvasSize.value.height.toFloat()
        if (w <= 0f || h <= 0f || strokes.isEmpty()) return null
        val threshold = with(density) { 14.dp.toPx() }
        var best: PdfInkStroke? = null
        var bestDist = Float.MAX_VALUE
        for (stroke in strokes.asReversed()) { // newest first feels right under the finger
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
        val strokeWidens = (best?.width ?: 0f) * density.density / 2f
        return best.takeIf { bestDist <= threshold + strokeWidens }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize.value = it }
            .then(
                when (tool) {
                    PdfInkTool.NONE -> Modifier
                    PdfInkTool.ERASER -> Modifier.pointerInput(anchorKey, strokes) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                hitStroke(offset)?.let { onEraseStroke(anchorKey, it.id) }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                hitStroke(change.position)?.let { onEraseStroke(anchorKey, it.id) }
                            },
                        )
                    }
                    else -> Modifier.pointerInput(tool, anchorKey, colorHex) {
                        detectDragGestures(
                            onDragStart = { offset -> livePoints.clear(); livePoints.add(offset) },
                            onDrag = { change, _ ->
                                change.consume()
                                livePoints.add(change.position)
                            },
                            onDragEnd = {
                                val mode = if (tool == PdfInkTool.HIGHLIGHTER) "highlighter" else "pen"
                                onStrokeFinished(anchorKey, mode, normalize(livePoints.toList()))
                                livePoints.clear()
                            },
                            onDragCancel = { livePoints.clear() },
                        )
                    }
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (stroke in strokes) drawStroke(stroke)
            if (livePoints.isNotEmpty() && (tool == PdfInkTool.PEN || tool == PdfInkTool.HIGHLIGHTER)) {
                drawStroke(
                    PdfInkStroke(
                        id = "live",
                        page = -1,
                        color = colorHex,
                        width = if (tool == PdfInkTool.HIGHLIGHTER) {
                            ReaderViewModel.HIGHLIGHTER_WIDTH_DP
                        } else {
                            ReaderViewModel.PEN_WIDTH_DP
                        },
                        points = normalize(livePoints.toList()),
                        mode = if (tool == PdfInkTool.HIGHLIGHTER) "highlighter" else "pen",
                    ),
                )
            }
        }
        if (tool == PdfInkTool.ERASER) {
            Text(
                text = "Eraser — touch a stroke",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp),
            )
        }
    }
}

private fun DrawScope.drawStroke(stroke: PdfInkStroke) {
    val pts = stroke.points
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
        color = colorFromHex(stroke.color).copy(alpha = if (highlighter) 0.35f else 1f),
        style = Stroke(
            width = stroke.width * density,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ),
    )
}

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val lenSq = abx * abx + aby * aby
    if (lenSq <= 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby) / lenSq).coerceIn(0f, 1f)
    val proj = Offset(a.x + t * abx, a.y + t * aby)
    return (p - proj).getDistance()
}

/**
 * Floating palette (INK-2): pen / highlighter / eraser / undo / close + color dots.
 * Deliberately uses text chips instead of icon extensions so no extra dependency.
 */
@Composable
fun PdfInkToolBar(
    activeTool: PdfInkTool,
    activeColor: String,
    canUndo: Boolean,
    onPen: () -> Unit,
    onHighlighter: () -> Unit,
    onEraser: () -> Unit,
    onColor: (String) -> Unit,
    onUndo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolChip("Pen", activeTool == PdfInkTool.PEN, onPen)
            ToolChip("Marker", activeTool == PdfInkTool.HIGHLIGHTER, onHighlighter)
            ToolChip("Erase", activeTool == PdfInkTool.ERASER, onEraser)

            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                INK_COLORS.forEach { hex ->
                    Box(
                        modifier = Modifier
                            .size(if (hex == activeColor) 26.dp else 20.dp)
                            .background(colorFromHex(hex), CircleShape)
                            .then(
                                if (hex == activeColor) {
                                    Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { onColor(hex) },
                    )
                }
            }

            IconButton(onClick = onUndo, enabled = canUndo) {
                Text("↺", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close ink tools")
            }
        }
    }
}

@Composable
private fun ToolChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier
            .padding(2.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
