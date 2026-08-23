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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            delay(2_000)
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
        if (engine == null) {
            ReaderStatusCard(state = state, onClose = onClose)
        } else {
            NavigatorContainer(engine = engine, modifier = Modifier.fillMaxSize())

            // RD-8: configurable 3×3 tap zones. Only cells with a configured action
            // are hit-testable; every other pixel passes through to the navigator so
            // scrolling, text selection, and links keep working.
            TapZoneLayer(
                grid = TapZoneGrid.fromJson(settings.tapZonesJson),
                modifier = Modifier.fillMaxSize(),
                onPrev = { viewModel.turnPage(forward = false) },
                onNext = { viewModel.turnPage(forward = true) },
                onToggleChrome = { viewModel.toggleChrome() },
            )

            // ANN-1/ANN-2: selection toolbar while text is selected.
            SelectionToolbarHost(
                engine = engine,
                visible = state.panel == ReaderPanel.NONE,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 104.dp),
                onSave = { selection, color, note ->
                    if (selection != null) {
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
        }

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
    }
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
private fun NavigatorContainer(engine: ReaderEngine, modifier: Modifier = Modifier) {
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

/** RD-13 top bar: chapter title, remaining %, battery stub, close. */
@Composable
private fun ReaderTopBar(
    title: String,
    remainingPercent: Float?,
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
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleChrome: () -> Unit,
) {
    Column(modifier = modifier) {
        repeat(TapZoneGrid.GRID_SIZE) { row ->
            Row(modifier = Modifier.weight(1f)) {
                repeat(TapZoneGrid.GRID_SIZE) { column ->
                    val action = grid.actionFor(row, column)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .then(
                                if (action == TapAction.NONE) {
                                    Modifier // no clickable → touches pass through
                                } else {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        when (action) {
                                            TapAction.PREV_PAGE -> onPrev()
                                            TapAction.NEXT_PAGE -> onNext()
                                            TapAction.TOGGLE_CHROME -> onToggleChrome()
                                            TapAction.NONE -> Unit
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
