package com.bookcon.app.ui.reader

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.bookcon.app.ui.reader.PdfInkToolBar
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.filled.Edit
import com.bookcon.app.reader.PdfInkTool
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bookcon.app.core.AppSettings
import com.bookcon.app.data.local.AnnotationEntity
import com.bookcon.app.reader.EngineSelection
import com.bookcon.app.reader.Locators
import com.bookcon.app.reader.ReaderEngine
import com.bookcon.app.reader.TapAction
import com.bookcon.app.reader.TapZoneGrid
import com.bookcon.app.ui.theme.ReaderTheme
import com.bookcon.app.ui.theme.ReaderThemes
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

/**
 * Reader route (PRD RD-8…RD-18, ANN-1…4). Hosts the Readium navigator fragment plus Compose
 * chrome, tap zones, panels and window-level effects. Fully offline once the book file exists.
 */
@Composable
fun ReaderScreen(bookId: String, onClose: () -> Unit) {
    val viewModel: ReaderViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    LaunchedEffect(bookId) { viewModel.init(bookId) }

    val readerTheme: ReaderTheme = ReaderThemes[settings.readerTheme] ?: ReaderThemes.getValue("light")

    // Window-level reader effects (RD-14, RD-15, RD-18).
    val activity = LocalContext.current as? Activity
    ApplyBrightness(activity = activity, brightness = settings.readerBrightness)
    ApplyKeepScreenOn(activity = activity, enabled = settings.readerKeepScreenOn)
    ApplyOrientationLock(activity = activity, lock = settings.orientationLock)

    // Focus anchor so volume-key page turns (RD-16) receive key events on the container.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // RD-13: auto-hide chrome after 2s of idle while reading.
    LaunchedEffect(state.chromeVisible, state.panel, state.phase) {
        if (state.chromeVisible &&
            state.panel == ReaderPanel.NONE &&
            state.phase == ReaderPhase.READY
        ) {
            // Debug builds keep chrome up much longer so automated UI tests can
            // reach toolbar buttons before it slides away.
            delay(if (com.bookcon.app.BuildConfig.DEBUG) 20_000L else 2_000L)
            viewModel.setChromeVisible(false)
        }
    }

    // System back: dismiss overlays first, otherwise flush position and pop (RD-12).
    BackHandler {
        when {
            state.panel != ReaderPanel.NONE -> viewModel.closePanel()
            state.chromeVisible -> viewModel.setChromeVisible(false)
            else -> {
                viewModel.notifyClosing()
                onClose()
            }
        }
    }

    // Local dialog state.
    var editingAnnotation by remember { mutableStateOf<AnnotationEntity?>(null) }
    var showTapZoneEditor by remember { mutableStateOf(false) }

    // RD-9 TOC drawer: opened/closed is driven by state.panel so the bottom-bar button and the
    // drawer scrim stay in sync (swipe-to-close writes back through snapshotFlow below).
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(state.panel) {
        if (state.panel == ReaderPanel.TABLE_OF_CONTENTS) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue }.collect { target ->
            if (target == DrawerValue.Closed && state.panel == ReaderPanel.TABLE_OF_CONTENTS) {
                viewModel.closePanel()
            }
        }
    }

    val activeHref = rememberActiveHref(state.engine)

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = state.panel == ReaderPanel.TABLE_OF_CONTENTS,
        drawerContent = {
            if (state.panel == ReaderPanel.TABLE_OF_CONTENTS) {
                ModalDrawerSheet {
                    TocDrawerContent(
                        links = state.tableOfContents,
                        activeHref = activeHref,
                        onSelect = { href, title ->
                            viewModel.jumpToHref(href, title)
                            viewModel.closePanel()
                        },
                        onClose = viewModel::closePanel,
                    )
                }
            }
        },
    ) {
        ReaderContentHost(
            state = state,
            settings = settings,
            viewModel = viewModel,
            readerTheme = readerTheme,
            focusRequester = focusRequester,
            editingAnnotation = editingAnnotation,
            onDismissAnnotationEditor = { editingAnnotation = null },
            showTapZoneEditor = showTapZoneEditor,
            onDismissTapZoneEditor = { showTapZoneEditor = false },
            onEditTapZones = { showTapZoneEditor = true },
            onLongPressAnnotation = { editingAnnotation = it },
            onClose = {
                viewModel.notifyClosing()
                onClose()
            },
        )
    }
}

/** Current chapter href (normalized) for TOC position indicator. */
@Composable
private fun rememberActiveHref(engine: ReaderEngine?): String {
    if (engine == null) return ""
    val locator by engine.currentLocator.collectAsStateWithLifecycle()
    return Locators.normalizeHref(locator.href.toString()).orEmpty()
}

/** Reading surface + chrome layering. */
@Composable
private fun ReaderContentHost(
    state: ReaderUiState,
    settings: AppSettings,
    viewModel: ReaderViewModel,
    readerTheme: ReaderTheme,
    focusRequester: FocusRequester,
    editingAnnotation: AnnotationEntity?,
    onDismissAnnotationEditor: () -> Unit,
    showTapZoneEditor: Boolean,
    onDismissTapZoneEditor: () -> Unit,
    onEditTapZones: () -> Unit,
    onLongPressAnnotation: (AnnotationEntity) -> Unit,
    onClose: () -> Unit,
) {
    val engine = state.engine

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(readerTheme.bg)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                // RD-16: volume keys turn pages when enabled.
                if (!settings.volumeKeyTurns) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        viewModel.turnPage(forward = true)
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        viewModel.turnPage(forward = false)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val pdf = state.pdfBook
        var pdfThumbs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        val lookupWord = androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<String?>(null)
        }
        // Reading-time tracking: one minute per minute of active reader session.
        ReadingMinuteTicker(bookId = state.book?.id)

        androidx.compose.runtime.LaunchedEffect(state.engine, state.phase) {
            if (state.engine?.hasSelectionBridge == true && state.phase == ReaderPhase.READY) {
                viewModel.startSelectionPolling()
            }
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose { viewModel.stopSelectionPolling() }
        }

        when {
            // PDFs render through our own PdfRenderer pager (no Readium navigator).
            pdf != null -> PdfPager(
                pdf = pdf,
                startPage = state.pdfStartPage,
                title = state.book?.title.orEmpty(),
                chromeVisible = state.chromeVisible,
                strokes = state.pdfStrokes,
                inkTool = state.pdfInkTool,
                inkColor = state.pdfInkColor,
                onPageChanged = viewModel::onPdfPageChanged,
                onToggleChrome = viewModel::toggleChrome,
                onStrokeFinished = { key, mode, pts ->
                    viewModel.addPdfStroke(key.toIntOrNull() ?: 0, mode, pts)
                },
                onEraseStroke = { key, strokeId ->
                    viewModel.erasePdfStroke(key.toIntOrNull() ?: 0, strokeId)
                },
                onUndoStroke = viewModel::undoLastPdfStroke,
                onInkToolChange = viewModel::setPdfInkTool,
                onInkColorChange = viewModel::setPdfInkColor,
                onSummarize = { viewModel.summarizeCurrentPage() },
                nightMode = settings.pdfNightMode,
                warmth = settings.pdfWarmth,
                onToggleNightMode = { viewModel.togglePdfNightMode() },
                onToggleReadAloud = { viewModel.toggleReadAloud() },
                showThumbs = pdfThumbs,
                onToggleThumbs = { pdfThumbs = !pdfThumbs },
                onJumpTo = { },
                turnRequest = viewModel.pdfTurnRequest.collectAsStateWithLifecycle().value,
                onTurnRequestConsumed = { viewModel.consumePdfTurnRequest() },
                pageAnimation = settings.readerPageTurnAnimation,
                modifier = Modifier.fillMaxSize(),
            )

            engine == null -> ReaderStatusCard(state = state, onClose = onClose)

            else -> {
            NavigatorContainer(engine = engine, modifier = Modifier.fillMaxSize())


            val probeActivity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            LaunchedEffect(engine, probeActivity) {
                if (probeActivity == null) return@LaunchedEffect
                // The navigator webview attaches asynchronously; poll until it exists.
                var wv = findWebView(probeActivity.window.decorView)
                var tries = 0
                while (wv == null && tries < 40) {
                    kotlinx.coroutines.delay(500)
                    wv = findWebView(probeActivity.window.decorView)
                    tries++
                }
                if (wv == null) return@LaunchedEffect
                engine.webViewEvaluator = { js, onResult ->
                    wv.post { wv.evaluateJavascript(js, onResult) }
                }
                engine.nativeSwipeTurn = { forward ->
                    val width = wv.width.toFloat()
                    val height = wv.height.toFloat()
                    if (width <= 0f || height <= 0f) false
                    else {
                        val y = height * 0.6f
                        // Moderate drag (~45% width, ~260ms): enough for the pager to
                        // register one column, gentle enough to avoid momentum skips.
                        val x0 = width * (if (forward) 0.72f else 0.28f)
                        val x1 = width * (if (forward) 0.28f else 0.72f)
                        val start = android.os.SystemClock.uptimeMillis()
                        fun ev(action: Int, x: Float, t: Long) =
                            android.view.MotionEvent.obtain(start, t, action, x, y, 0)
                        val steps = 12
                        wv.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_DOWN, x0, start))
                        for (i in 1 until steps) {
                            val t = start + i * 26L
                            val frac = i.toFloat() / steps
                            wv.dispatchTouchEvent(
                                ev(android.view.MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * frac, t),
                            )
                        }
                        wv.dispatchTouchEvent(ev(android.view.MotionEvent.ACTION_UP, x1, start + steps * 22L))
                        true
                    }
                }
            }

            // ANN-2: long-presses inside actionable tap-zone cells must still reach the
            // navigator webview, otherwise text selection (and thus highlights) is
            // impossible. We forward a synthetic DOWN…UP pair into the WebView.
            val activityForFind = androidx.compose.ui.platform.LocalContext.current
            val forwardLongPress = remember(activityForFind) {
                { screenX: Float, screenY: Float ->
                    val webView = (activityForFind as? android.app.Activity)
                        ?.window?.decorView?.let { findWebView(it) }
                    if (webView != null) {
                        val loc = IntArray(2)
                        webView.getLocationOnScreen(loc)
                        val lx = screenX - loc[0]
                        val ly = screenY - loc[1]
                        val now = android.os.SystemClock.uptimeMillis()
                        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, lx, ly, 0)
                        webView.dispatchTouchEvent(down)
                        webView.postDelayed({
                            val up = android.view.MotionEvent.obtain(now, now + 400, android.view.MotionEvent.ACTION_UP, lx, ly, 0)
                            webView.dispatchTouchEvent(up)
                            down.recycle()
                            up.recycle()
                        }, 420L)
                    }
                }
            }

            // RD-8: configurable 3×3 tap zones. Only cells with a configured action
            // are hit-testable; every other pixel passes through to the navigator so
            // scrolling, text selection, and links keep working.
            val inkArmed = state.pdfInkTool != PdfInkTool.NONE
            TapZoneLayer(
                grid = TapZoneGrid.fromJson(settings.tapZonesJson),
                modifier = Modifier.fillMaxSize(),
                gesturesEnabled = !inkArmed,
                onPrev = { viewModel.turnPage(forward = false) },
                onNext = { viewModel.turnPage(forward = true) },
                onToggleChrome = { viewModel.toggleChrome() },
                onLongPressAtRoot = forwardLongPress.takeIf { !inkArmed },
            )

            // INK-4: freehand pen/marker/eraser over EPUB pages.
            PdfInkLayer(
                strokes = state.epubStrokes[state.epubAnchor].orEmpty(),
                tool = state.pdfInkTool,
                colorHex = state.pdfInkColor,
                anchorKey = state.epubAnchor,
                onStrokeFinished = { key, mode, points ->
                    viewModel.addEpubStroke(key, mode, points)
                },
                onEraseStroke = { key, strokeId ->
                    viewModel.eraseEpubStroke(key, strokeId)
                },
                modifier = Modifier.fillMaxSize(),
            )

            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = {
                    viewModel.setPdfInkTool(
                        if (inkArmed) PdfInkTool.NONE else PdfInkTool.PEN,
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 96.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "Pen & highlighter")
            }

            if (inkArmed) {
                PdfInkToolBar(
                    activeTool = state.pdfInkTool,
                    activeColor = state.pdfInkColor,
                    canUndo = true,
                    onPen = { viewModel.setPdfInkTool(PdfInkTool.PEN) },
                    onHighlighter = { viewModel.setPdfInkTool(PdfInkTool.HIGHLIGHTER) },
                    onEraser = { viewModel.setPdfInkTool(PdfInkTool.ERASER) },
                    onColor = viewModel::setPdfInkColor,
                    onUndo = viewModel::undoLastEpubStroke,
                    onClose = { viewModel.setPdfInkTool(PdfInkTool.NONE) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 88.dp),
                )
            }

            // ANN-1/ANN-2: selection toolbar while text is selected.
            SelectionToolbarHost(
                engine = engine,
                visible = state.panel == ReaderPanel.NONE && !state.pdfInkTool.let { it != PdfInkTool.NONE },
                onAiAction = { action, excerpt -> viewModel.runSelectionAi(action, excerpt) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp),
                onSave = { selection, color, note ->
                    if (selection != null) {
                        // Persisted highlight; observeAnnotations mirrors it onto the
                        // navigator as a Readium Decoration (visible in-page highlight).
                        viewModel.addAnnotation(
                            color = color,
                            note = note,
                            excerpt = selection.text,
                            locatorJson = Locators.toJsonString(selection.locator).orEmpty(),
                        )
                    }
                },
                onCancel = { engine.clearSelection() },
            )

            // ANN-4 side rail: jump back to annotations.
            AnnotationRail(
                annotations = state.annotations,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 3.dp),
                onTap = viewModel::jumpToAnnotation,
                onLongPress = onLongPressAnnotation,
            )

        // RD-13 chrome.
        val showChrome = engine != null && state.chromeVisible && state.panel == ReaderPanel.NONE
        AnimatedVisibility(
            visible = showChrome,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = state.chapterTitle.ifBlank { state.book?.title.orEmpty() },
                remainingPercent = state.remainingPercent,
                onSummarize = { viewModel.summarizeCurrentPage() },
                onToggleReadAloud = { viewModel.toggleReadAloud() },
                onClose = onClose,
            )
        }
        AnimatedVisibility(
            visible = showChrome,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                bookmarkCount = state.bookmarks.size,
                onTableOfContents = { viewModel.setPanel(ReaderPanel.TABLE_OF_CONTENTS) },
                onToggleBookmark = { viewModel.toggleBookmark() },
                onBookmarks = { viewModel.setPanel(ReaderPanel.BOOKMARKS) },
                onSearch = { viewModel.setPanel(ReaderPanel.SEARCH) },
                onTextSettings = { viewModel.setPanel(ReaderPanel.SETTINGS) },
                onClose = onClose,
            )
        }

        // RD-11 flash box approximation for opened search hits (~800ms).
        FlashHighlightOverlay(tick = state.flashTick, modifier = Modifier.align(Alignment.Center))

        // Panels, sheets and dialogs.
        ReaderPanelsHost(
            state = state,
            settings = settings,
            viewModel = viewModel,
            editingAnnotation = editingAnnotation,
            onDismissAnnotationEditor = onDismissAnnotationEditor,
            showTapZoneEditor = showTapZoneEditor,
            onDismissTapZoneEditor = onDismissTapZoneEditor,
            onEditTapZones = onEditTapZones,
        )

            } // else (Readium engine)
        } // when (pdf / status / engine)

        // In-reader AI page summary sheet — overlays both the PDF and EPUB render paths.
        val summary by viewModel.summaryState.collectAsStateWithLifecycle()
        if (summary.loading || summary.text != null || summary.error != null) {
            SummarySheet(
                state = summary,
                subtitle = state.chapterTitle.ifBlank { state.book?.title.orEmpty() },
                onRegenerate = { viewModel.regenerateSummary() },
                onRetry = { viewModel.summarizeCurrentPage(forceRefresh = true) },
                onDismiss = viewModel::dismissSummary,
            )
        }

        // Selection AI actions (EPUB selection) — sits above bottom chrome.
        if (state.phase == ReaderPhase.READY && state.panel == ReaderPanel.NONE) {
            SelectionAiBar(
                viewModel = viewModel,
                onDefine = { lookupWord.value = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            )
        }
        com.bookcon.app.ui.reader.WordLookupHost(state = lookupWord)

        // Read-aloud pill.
        ReadAloudBar(
            viewModel = viewModel,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 170.dp),
        )
    } // Box
}

// --------------------------------------------------------------------------------- navigator

/**
 * Hosts the Readium navigator fragment inside a plain FrameLayout owned by Compose.
 *
 * NOTE: Readium navigator fragments require a FragmentActivity host. MainActivity already
 * extends AppCompatActivity (a FragmentActivity subclass), so the cast below succeeds in the
 * real app; if the reader is ever hosted by a plain ComponentActivity we degrade to an
 * explanatory card instead of crashing.
 */
@Composable
private fun NavigatorContainer(
    engine: ReaderEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    if (activity == null) {
        UnsupportedHostCard(modifier = modifier)
        return
    }
    val containerId = remember { View.generateViewId() }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply { id = containerId }
        },
    )
    LaunchedEffect(engine) {
        val fragment = engine.navigator as? Fragment ?: return@LaunchedEffect
        val fm = activity.supportFragmentManager
        if (!fragment.isAdded && fm.findFragmentById(containerId) == null) {
            fm.beginTransaction()
                .replace(containerId, fragment)
                .commitAllowingStateLoss()
        }
        // Settings captured before attach are applied once the fragment lands.
        engine.flushPendingSettings()
    }
}

@Composable
private fun UnsupportedHostCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Reader host unsupported", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "The reader needs an AppCompatActivity host to embed the navigator. " +
                "Make sure MainActivity extends AppCompatActivity.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// --------------------------------------------------------------------------------- status

/** Loading / "Downloading…" (non-blocking, offline-first) / error card. */
@Composable
private fun ReaderStatusCard(state: ReaderUiState, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            if (state.phase == ReaderPhase.ERROR) {
                Text(
                    text = state.errorMessage ?: "Something went wrong.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                androidx.compose.material3.TextButton(onClick = onClose) {
                    Text("Close reader")
                }
            } else {
                CircularProgressIndicator()
                Text(
                    text = when (state.phase) {
                        ReaderPhase.DOWNLOADING -> state.statusMessage ?: "Downloading…"
                        ReaderPhase.OPENING -> "Opening…"
                        else -> state.statusMessage ?: "Loading…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.book?.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------------- chrome

/** In-reader AI page summary sheet (loading / text / error states from the ViewModel). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SummarySheet(
    state: SummaryUiState,
    subtitle: String?,
    onRegenerate: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("Page summary", style = MaterialTheme.typography.titleMedium)
            subtitle?.takeIf { it.isNotBlank() }?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            when {
                state.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.error != null -> Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }

                state.text != null -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SelectionContainer {
                        Text(text = state.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.fromCache) {
                        Text(
                            text = "cached",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRegenerate) { Text("Regenerate") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    }
                }
            }
        }
    }
}

/** RD-13 top bar: chapter title, remaining %, AI summary, battery stub, close. */
@Composable
private fun ReaderTopBar(
    title: String,
    remainingPercent: Float?,
    onSummarize: () -> Unit,
    onToggleReadAloud: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close reader")
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "Reading" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = remainingPercent
                    ?.let { "${(it * 100).toInt().coerceIn(0, 100)}% left" }
                    .orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onSummarize) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Summarize page")
        }
        IconButton(onClick = onToggleReadAloud) {
            Icon(Icons.Outlined.Headphones, contentDescription = "Read aloud")
        }
        BatteryStub(modifier = Modifier.padding(end = 8.dp))
    }
}

/** RD-13 bottom bar: TOC, bookmark add/list, search, text settings, close. */
@Composable
private fun ReaderBottomBar(
    bookmarkCount: Int,
    onTableOfContents: () -> Unit,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    onSearch: () -> Unit,
    onTextSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onTableOfContents) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of contents")
        }
        IconButton(onClick = onToggleBookmark) {
            Icon(Icons.Filled.BookmarkAdd, contentDescription = "Add bookmark")
        }
        IconButton(onClick = onBookmarks) {
            BadgedBox(badge = {
                if (bookmarkCount > 0) Badge { Text(bookmarkCount.toString()) }
            }) {
                Icon(Icons.Filled.Bookmark, contentDescription = "Bookmarks")
            }
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Filled.Search, contentDescription = "Search")
        }
        IconButton(onClick = onTextSettings) {
            Icon(Icons.Filled.TextFields, contentDescription = "Reading settings")
        }
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
    }
}

/** Optional battery indicator (PRD RD-13 "optional"); reads the sticky battery intent once. */
@Composable
private fun BatteryStub(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val level = remember(context) {
        runCatching {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (raw >= 0 && scale > 0) raw * 100 / scale else -1
        }.getOrDefault(-1)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.BatteryFull,
            contentDescription = "Battery",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(18.dp),
        )
        if (level >= 0) {
            Text(
                "$level%",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

// --------------------------------------------------------------------------------- tap zones

/**
 * RD-8: invisible 3×3 tap grid implemented as nine WEIGHTED CELLS in a column of
 * rows — a cell is clickable only when its configured action is not NONE, and the
 * center cell (where reading happens) defaults to NONE. Unmapped areas are NOT
 * hit-testable, so navigator gestures flow through untouched.
 */
@Composable
private fun TapZoneLayer(
    grid: TapZoneGrid,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleChrome: () -> Unit,
    onLongPressAtRoot: ((Float, Float) -> Unit)? = null,
) {
    Column(modifier = modifier) {
        repeat(TapZoneGrid.GRID_SIZE) { row ->
            Row(modifier = Modifier.weight(1f)) {
                repeat(TapZoneGrid.GRID_SIZE) { column ->
                    val action = grid.actionFor(row, column)
                    var cellRoot by remember { mutableStateOf(Offset.Zero) }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .onGloballyPositioned { cellRoot = it.positionInRoot() }
                            .then(
                                if (action == TapAction.NONE || !gesturesEnabled) {
                                    Modifier // no handlers → touches pass through
                                } else {
                                    Modifier.pointerInput(action, gesturesEnabled) {
                                        // RD-8 v2: detect only genuine TAPS and let every
                                        // other gesture (swipes, scrolls) fall through to the
                                        // navigator webview below so native page-turn swipes
                                        // keep working.
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            var consumedElsewhere = false
                                            var moved = false
                                            var upOffset: Offset? = null
                                            while (true) {
                                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                                if (event.changes.any { it.isConsumed }) {
                                                    consumedElsewhere = true
                                                }
                                                val change = event.changes.firstOrNull() ?: break
                                                if (!change.pressed) {
                                                    upOffset = change.position
                                                    break
                                                }
                                                val dx = change.position.x - change.previousPosition.x
                                                val dy = change.position.y - change.previousPosition.y
                                                if (kotlin.math.sqrt(dx * dx + dy * dy) >
                                                    viewConfiguration.touchSlop * 2f
                                                ) {
                                                    moved = true
                                                }
                                            }
                                            if (!consumedElsewhere && !moved && upOffset != null) {
                                                when (action) {
                                                    TapAction.PREV_PAGE -> onPrev()
                                                    TapAction.NEXT_PAGE -> onNext()
                                                    TapAction.TOGGLE_CHROME -> onToggleChrome()
                                                    TapAction.NONE -> Unit
                                                }
                                            } else if (!moved && upOffset != null &&
                                                action != TapAction.NONE
                                            ) {
                                                // Stationary press inside an actionable zone:
                                                // forward as long-press for text selection.
                                                val target = cellRoot + upOffset
                                                onLongPressAtRoot?.invoke(target.x, target.y)
                                            }
                                        }
                                    }
                                },
                            ),
                    )
                }
            }
        }
    }
}

/** Recursively finds the Readium content WebView under [view]. */
fun findWebView(view: android.view.View): android.webkit.WebView? {
    if (view is android.webkit.WebView) return view
    if (view is android.view.ViewGroup) {
        for (i in 0 until view.childCount) {
            findWebView(view.getChildAt(i))?.let { return it }
        }
    }
    return null
}

// --------------------------------------------------------------------------------- overlays

/** RD-11: brief border box flash (~800ms) approximating the in-page match highlight. */
@Composable
private fun FlashHighlightOverlay(tick: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        if (tick > 0) {
            visible = true
            delay(800)
            visible = false
        }
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.55f)
                .border(
                    width = 3.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(10.dp),
                ),
        )
    }
}

/** ANN-1/2: floating toolbar while the navigator reports a text selection. */
@Composable
private fun SelectionToolbarHost(
    engine: ReaderEngine,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onSave: (selection: EngineSelection?, color: String, note: String) -> Unit,
    onCancel: () -> Unit,
    onAiAction: (action: String, excerpt: String) -> Unit = { _, _ -> },
) {
    // Toolkit 3.1.0 exposes the navigator selection as a suspend query, not a StateFlow, so we
    // poll cheaply while the toolbar is eligible (the toolbar is hidden most of the time).
    var selection by remember { mutableStateOf<EngineSelection?>(null) }
    LaunchedEffect(engine, visible) {
        if (!visible) {
            selection = null
        } else {
            while (true) {
                selection = engine.currentSelection()
                kotlinx.coroutines.delay(250)
            }
        }
    }
    AnimatedVisibility(
        visible = visible && selection != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        SelectionToolbar(
            onSave = { color, note -> onSave(selection, color, note) },
            onCancel = onCancel,
            onAiAction = { action, excerpt ->
                onCancel()
                onAiAction(action, excerpt)
            },
            excerpt = { selection?.text.orEmpty() },
        )
    }
}

/** ANN-4: side rail of annotation markers; tap jumps back, long-press edits. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AnnotationRail(
    annotations: List<AnnotationEntity>,
    modifier: Modifier = Modifier,
    onTap: (AnnotationEntity) -> Unit,
    onLongPress: (AnnotationEntity) -> Unit,
) {
    if (annotations.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        annotations.take(16).forEach { item ->
            Box(
                Modifier
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(annotationColor(item.color))
                    .combinedClickable(
                        onClick = { onTap(item) },
                        onLongClick = { onLongPress(item) },
                    ),
            )
        }
    }
}

// --------------------------------------------------------------------------------- window effects

/** RD-14: override window brightness while non-null; null restores "follow system". */
@Composable
private fun ApplyBrightness(activity: Activity?, brightness: Float?) {
    DisposableEffect(activity, brightness) {
        val window = activity?.window
        val previous = window?.attributes?.screenBrightness
        if (window != null) {
            window.attributes = window.attributes.also {
                it.screenBrightness =
                    brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
        onDispose {
            if (window != null) {
                window.attributes = window.attributes.also {
                    it.screenBrightness =
                        previous ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }
}

/** RD-18: FLAG_KEEP_SCREEN_ON while enabled. */
@Composable
private fun ApplyKeepScreenOn(activity: Activity?, enabled: Boolean) {
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (window != null) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** RD-15: orientation lock chips map onto ActivityInfo screen orientations. */
@Composable
private fun ApplyOrientationLock(activity: Activity?, lock: String) {
    DisposableEffect(activity, lock) {
        val previous = activity?.requestedOrientation
        if (activity != null) {
            activity.requestedOrientation = when (lock) {
                "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        onDispose {
            if (activity != null && previous != null) {
                activity.requestedOrientation = previous
            }
        }
    }
}


/** Logs one reading minute per 60s while the reader is in the foreground (PRD STAT-*). */
@Composable
private fun ReadingMinuteTicker(bookId: String?) {
    if (bookId == null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(bookId) {
        val tracker = com.bookcon.app.core.ReadingTracker(context)
        while (true) {
            kotlinx.coroutines.delay(60_000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                tracker.logMinute(bookId)
            }
        }
    }
}
