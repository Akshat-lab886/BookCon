package com.bookcon.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bookcon.app.reader.PdfBook
import com.bookcon.app.reader.PdfInkStroke
import com.bookcon.app.reader.PdfInkTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page-turning PDF surface backed by [PdfBook] (PdfRenderer bitmaps). The Readium
 * toolkit has no PDF navigator for 3.1.0, so PDFs get their own lightweight pager.
 * Tap zones mirror the EPUB reader: left/right thirds turn pages, centre toggles chrome.
 *
 * Ink (pen/marker/eraser) renders through [PdfInkLayer]; while a tool is active it
 * covers the page and consumes gestures so strokes never trigger page turns.
 */
@Composable
fun PdfPager(
    pdf: PdfBook,
    startPage: Int,
    title: String,
    chromeVisible: Boolean,
    strokes: Map<Int, List<PdfInkStroke>>,
    inkTool: PdfInkTool,
    inkColor: String,
    onPageChanged: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onStrokeFinished: (anchorKey: String, mode: String, points: List<Float>) -> Unit,
    onEraseStroke: (anchorKey: String, strokeId: String) -> Unit,
    onUndoStroke: () -> Unit,
    onInkToolChange: (PdfInkTool) -> Unit,
    onInkColorChange: (String) -> Unit,
    onSummarize: () -> Unit,
    nightMode: Boolean = false,
    warmth: Int = 0,
    onToggleNightMode: () -> Unit = {},
    onToggleReadAloud: () -> Unit = {},
    turnRequest: Int? = null,
    onTurnRequestConsumed: () -> Unit = {},
    showThumbs: Boolean = false,
    onToggleThumbs: () -> Unit = {},
    onJumpTo: (Int) -> Unit = {},
    pageAnimation: String = "slide",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = context.resources.displayMetrics.density
    // Render at ~1.5x dp width for crispness, capped so huge pages stay cheap.
    val targetWidth = (LocalConfiguration.current.screenWidthDp * density * 1.5f)
        .toInt().coerceIn(480, 1600)

    val pagerState = rememberPagerState(
        initialPage = startPage.coerceIn(0, maxOf(0, pdf.pageCount - 1)),
        pageCount = { pdf.pageCount },
    )
    val pageCache = remember { mutableStateMapOf<Int, Bitmap>() }
    val rendering = remember { mutableStateMapOf<Int, Boolean>() }

    suspend fun ensureRendered(index: Int) {
        if (index !in 0 until pdf.pageCount) return
        if (pageCache.containsKey(index) || rendering.containsKey(index)) return
        rendering[index] = true
        try {
            val bmp = withContext(Dispatchers.IO) { pdf.renderPage(index, targetWidth) }
            pageCache[index] = bmp
            // Keep the cache bounded: drop pages far from the current one.
            pageCache.keys
                .filter { it < pagerState.currentPage - 2 || it > pagerState.currentPage + 2 }
                .forEach { pageCache.remove(it) }
        } catch (_: Exception) {
            // Leave uncached; a later pass retries while the page stays visible.
        } finally {
            rendering.remove(index)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> onPageChanged(page) }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page ->
                ensureRendered(page)
                ensureRendered(page + 1)
                ensureRendered(page - 1)
            }
    }
    LaunchedEffect(pagerState) { ensureRendered(pagerState.currentPage) }

    LaunchedEffect(turnRequest) {
        val target = turnRequest ?: return@LaunchedEffect
        if (target in 0 until pdf.pageCount) {
            pagerState.animateScrollToPage(target)
        }
        onTurnRequestConsumed()
    }

    fun turnTo(page: Int) {
        val clamped = page.coerceIn(0, pdf.pageCount - 1)
        scope.launch { pagerState.animateScrollToPage(clamped) }
    }

    Box(modifier.fillMaxSize()) {
        AnimatedPager(
            state = pagerState,
            animation = PageAnimation.fromId(pageAnimation),
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val bmp = pageCache[index]
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        contentScale = ContentScale.Fit,
                        colorFilter = nightPageFilter(nightMode),
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "Rendering page ${index + 1}…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        // Tap zones only when no ink tool is armed (INK-1 keeps gestures exclusive).
        if (inkTool == PdfInkTool.NONE) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(pdf.pageCount) {
                        detectTapGestures { offset ->
                            val width = size.width.toFloat()
                            when {
                                offset.x < width / 3f -> turnTo(pagerState.currentPage - 1)
                                offset.x > 2f * width / 3f -> turnTo(pagerState.currentPage + 1)
                                else -> onToggleChrome()
                            }
                        }
                    },
            )
        }

        // INK-1: drawing overlay above everything but the toolbars.
        PdfInkLayer(
            strokes = strokes[pagerState.currentPage].orEmpty(),
            tool = inkTool,
            colorHex = inkColor,
            anchorKey = pagerState.currentPage.toString(),
            onStrokeFinished = onStrokeFinished,
            onEraseStroke = onEraseStroke,
            modifier = Modifier.fillMaxSize(),
        )

        // Page indicator pill + AI page-summary shortcut (PDF top chrome).
        Surface(
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$title · page ${pagerState.currentPage + 1} of ${pdf.pageCount}",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                )
                IconButton(onClick = onSummarize) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = "Summarize page")
                }
                IconButton(onClick = onToggleReadAloud) {
                    Icon(Icons.Outlined.Headphones, contentDescription = "Read aloud")
                }
                IconButton(onClick = onToggleThumbs) {
                    Icon(
                        Icons.Outlined.Collections,
                        contentDescription = "Page thumbnails",
                        tint = if (showThumbs) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onToggleNightMode) {
                    Icon(
                        Icons.Outlined.DarkMode,
                        contentDescription = if (nightMode) "Night mode on" else "Night mode off",
                        tint = if (nightMode) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
        }

        // PRD THUMB: filmstrip scrubber (tap thumbnail to jump).
        if (showThumbs) {
            PdfThumbStrip(
                pdf = pdf,
                currentPage = pagerState.currentPage,
                onPick = { target ->
                    scope.launch { pagerState.animateScrollToPage(target) }
                    onJumpTo(target)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp),
            )
        }

        // PRD PDF-NIGHT: warm veil above inverted pages.
        PdfWarmVeil(enabled = nightMode, warmth = warmth)

        // INK-2: floating pen button toggles the annotation toolbar.
        FloatingActionButton(
            onClick = {
                onInkToolChange(if (inkTool == PdfInkTool.NONE) PdfInkTool.PEN else PdfInkTool.NONE)
            },
            shape = CircleShape,
            containerColor = if (inkTool == PdfInkTool.NONE) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 28.dp)
                .size(52.dp),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = "Pen & highlighter")
        }

        // INK-2: tool palette while a tool is armed.
        if (inkTool != PdfInkTool.NONE) {
            PdfInkToolBar(
                activeTool = inkTool,
                activeColor = inkColor,
                canUndo = !strokes[pagerState.currentPage].isNullOrEmpty(),
                onPen = { onInkToolChange(PdfInkTool.PEN) },
                onHighlighter = { onInkToolChange(PdfInkTool.HIGHLIGHTER) },
                onEraser = { onInkToolChange(PdfInkTool.ERASER) },
                onColor = onInkColorChange,
                onUndo = onUndoStroke,
                onClose = { onInkToolChange(PdfInkTool.NONE) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}
