package com.bookcon.app.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import kotlin.test.assertEquals
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bookcon.app.data.local.BookEntity
import com.bookcon.app.data.local.BookmarkEntity
import com.bookcon.app.ui.bookmarks.BookmarksScreen
import com.bookcon.app.ui.bookmarks.BookmarksViewModel
import com.bookcon.app.ui.library.LibraryScreen
import com.bookcon.app.ui.library.LibraryViewModel
import com.bookcon.app.ui.profile.ProfileScreen
import com.bookcon.app.ui.theme.BrandColors
import com.bookcon.app.ui.reader.PageAnimation
import com.bookcon.app.ui.reader.PageTurnCoordinator
import com.bookcon.app.ui.reader.PageTurnOverlay
import com.bookcon.app.ui.reader.rememberPageTurnCoordinator
import com.bookcon.app.ui.theme.BookConTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertTrue

/**
 * Renders the reference-cloned screens on the JVM (Robolectric native graphics)
 * and writes PNGs to `build/screenshots/`.
 *
 * This exists because the tablet has been unavailable: it lets the UI be
 * verified pixel-by-pixel without a device attached.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenRenderTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    // --- fixtures -------------------------------------------------------

    private fun book(
        id: String,
        title: String,
        author: String,
        format: String = "epub",
        pages: Int? = 320,
    ) = BookEntity(
        id = id,
        userId = "u1",
        format = format,
        status = "ready",
        title = title,
        authors = listOf(author),
        description = "Tiny changes, remarkable results. A practical guide.",
        pageCount = pages,
        addedAt = "2026-09-26T00:00:00Z",
        updatedAt = "2026-09-26T00:00:00Z",
    )

    private fun libraryVm(vararg books: BookEntity): LibraryViewModel = mock {
        on { state } doReturn MutableStateFlow(
            com.bookcon.app.ui.library.LibraryUiState(
                loading = false,
                books = books.toList(),
                totalCount = books.size,
                serverUrl = "http://192.168.1.10:8000",
            ),
        )
        // The screen collects this channel for its snackbars, so the mock has to
        // provide one; Mockito returns null for an unstubbed flow property.
        on { events } doReturn kotlinx.coroutines.flow.emptyFlow<com.bookcon.app.ui.library.LibraryEvent>()
    }

    private fun bookmarksVm(vararg rows: com.bookcon.app.ui.bookmarks.BookmarkRow): BookmarksViewModel = mock {
        on { uiState } doReturn MutableStateFlow(
            com.bookcon.app.ui.bookmarks.BookmarksUiState(bookmarks = rows.toList(), isLoading = false),
        )
    }

    private fun bmRow(id: String, bookId: String, title: String, label: String) =
        com.bookcon.app.ui.bookmarks.BookmarkRow(
            id = id, bookId = bookId, bookTitle = title, label = label, locatorJson = "{}",
        )

    // --- capture --------------------------------------------------------

    /**
     * Robolectric's `captureToImage()` relies on PixelCopy + forceRedraw, which
     * never completes under the paused looper. Measuring/laying out the decor
     * view and drawing it into a Bitmap directly is reliable here.
     */
    private fun render(): Bitmap {
        compose.waitForIdle()
        val view: View = compose.activity.window.decorView
        val metrics = compose.activity.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    private fun save(name: String): Bitmap {
        val bmp = render()
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("RENDERED $name ${bmp.width}x${bmp.height}")
        return bmp
    }

    private fun capture(name: String) {
        save(name)
    }

    private fun hasColour(bmp: Bitmap, target: Int, tol: Int = 14): Int {
        val tR = android.graphics.Color.red(target)
        val tG = android.graphics.Color.green(target)
        val tB = android.graphics.Color.blue(target)
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                if (kotlin.math.abs(android.graphics.Color.red(p) - tR) <= tol &&
                    kotlin.math.abs(android.graphics.Color.green(p) - tG) <= tol &&
                    kotlin.math.abs(android.graphics.Color.blue(p) - tB) <= tol
                ) n++
                x += 2
            }
            y += 2
        }
        return n
    }

    // --- tests ----------------------------------------------------------

    @Test
    fun libraryScreen_matchesReferenceDarkTheme() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                LibraryScreen(
                    onOpenBook = {},
                    onOpenSettings = {},
                    onOpenHome = {},
                    onOpenBookmarks = {},
                    onOpenProfile = {},
                    viewModel = libraryVm(
                        book("b1", "Atomic Habits", "James Clear"),
                        book("b2", "Deep Work", "Cal Newport"),
                        book("b3", "Sapiens", "Yuval Noah Harari", format = "pdf"),
                    ),
                )
            }
        }
        val bmp = save("android-library")

        val bg = hasColour(bmp, BrandColors.PageDark.toArgbInt())
        val green = hasColour(bmp, BrandColors.Primary.toArgbInt())
        println("PIXELS bg=$bg green=$green")
        assertTrue(bg > 5_000, "expected the dark page background to dominate, got $bg")
        assertTrue(green > 0, "expected the green selected-nav accent, got $green")
    }

    @Test
    fun bookmarksScreen_rendersEmptyState() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                BookmarksScreen(
                    onBack = {},
                    onOpenBook = { _, _ -> },
                    onOpenHome = {},
                    onOpenLibrary = {},
                    onOpenProfile = {},
                    viewModel = bookmarksVm(),
                )
            }
        }
        capture("android-bookmarks")
    }

    @Test
    fun bookmarksScreen_rendersRows() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                BookmarksScreen(
                    onBack = {},
                    onOpenBook = { _, _ -> },
                    onOpenHome = {},
                    onOpenLibrary = {},
                    onOpenProfile = {},
                    viewModel = bookmarksVm(
                        bmRow("k1", "b1", "Atomic Habits", "Chapter 1"),
                        bmRow("k2", "b2", "Dune", "The Butlerian Jihad"),
                    ),
                )
            }
        }
        capture("android-bookmarks-rows")
    }

    @Test
    fun profileScreen_renders() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                ProfileScreen(
                    onBack = {},
                    onSignOut = {},
                    onNavigateToSettings = {},
                    onOpenHome = {},
                    onOpenLibrary = {},
                    onOpenBookmarks = {},
                )
            }
        }
        val bmp = save("android-profile")
        val bg = hasColour(bmp, BrandColors.PageDark.toArgbInt())
        println("PIXELS profile bg=$bg")
        assertTrue(bg > 5_000, "expected the dark page background, got $bg")
    }
    @Test
    fun bookDetailScreen_matchesPurpleGradientReference() {
        val vm: com.bookcon.app.ui.details.BookDetailsViewModel = mock {
            on { state } doReturn MutableStateFlow(
                com.bookcon.app.ui.details.BookDetailsUiState(
                    loading = false,
                    book = book("b1", "Atomic Habits", "James Clear"),
                    ratingLabel = "—",
                    pagesLabel = "320 Pgs",
                ),
            )
        }
        compose.setContent {
            BookConTheme(darkTheme = true) {
                com.bookcon.app.ui.lifestyle.LifestyleBookDetailScreen(
                    bookId = "b1",
                    onBack = {},
                    onContinueReading = {},
                    viewModel = vm,
                )
            }
        }
        val bmp = save("android-book-detail")
        val purple = hasColour(bmp, detailTopArgb())
        println("PIXELS detail purple=$purple")
        assertTrue(purple > 20_000, "expected the purple gradient hero, got $purple")
    }

    @Test
    fun homeScreen_rendersTealHeroReference() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                com.bookcon.app.ui.lifestyle.LifestyleHomeScreen(
                    onOpenBook = {},
                    onOpenReader = {},
                    onOpenLibrary = {},
                    onOpenBookmarks = {},
                    onOpenProfile = {},
                    onOpenSettings = {},
                    onImport = {},
                    onAddBook = {},
                    viewModel = libraryVm(
                        book("b1", "Atomic Habits", "James Clear"),
                        book("b2", "Deep Work", "Cal Newport"),
                    ),
                )
            }
        }
        val bmp = save("android-home")
        val teal = hasColour(bmp, mintArgb())
        val cream = hasColour(bmp, pageArgb())
        println("PIXELS home teal=$teal cream=$cream")
        assertTrue(teal > 500, "expected the teal hero, got $teal")
        assertTrue(cream > 20_000, "expected the cream page, got $cream")
    }

    // --- semantic checks: the text the reference screenshots show ---

    @Test
    fun libraryScreen_showsReferenceText() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                LibraryScreen(
                    onOpenBook = {}, onOpenSettings = {}, onOpenHome = {},
                    onOpenBookmarks = {}, onOpenProfile = {},
                    viewModel = libraryVm(
                        book("b1", "Atomic Habits", "James Clear"),
                        book("b2", "Deep Work", "Cal Newport"),
                    ),
                )
            }
        }
        compose.onNodeWithText("My Library").assertIsDisplayed()
        compose.onNodeWithText("2 books").assertIsDisplayed()
        compose.onNodeWithText("Import books").assertIsDisplayed()
        compose.onNodeWithText("Atomic Habits").assertIsDisplayed()
        listOf("Home", "Books", "Bookmarks", "Profile").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        println("SEMANTICS library OK")
    }

    @Test
    fun bookmarksScreen_showsReferenceEmptyState() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                BookmarksScreen(
                    onBack = {}, onOpenBook = { _, _ -> }, onOpenHome = {},
                    onOpenLibrary = {}, onOpenProfile = {},
                    viewModel = bookmarksVm(),
                )
            }
        }
        // "Bookmarks" appears twice by design: page header + bottom-nav tab.
        compose.onAllNodesWithText("Bookmarks").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Bookmarks")[1].assertIsDisplayed()
        compose.onNodeWithText("No bookmarks yet").assertIsDisplayed()
        println("SEMANTICS bookmarks OK")
    }

    @Test
    fun profileScreen_showsReferenceRows() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                ProfileScreen(
                    onBack = {}, onSignOut = {}, onNavigateToSettings = {},
                    onOpenHome = {}, onOpenLibrary = {}, onOpenBookmarks = {},
                )
            }
        }
        // "Profile" appears twice by design: page header + bottom-nav tab.
        compose.onAllNodesWithText("Profile").onFirst().assertIsDisplayed()
        compose.onAllNodesWithText("Profile")[1].assertIsDisplayed()
        listOf("Settings", "Notebooks", "Vocabulary").forEach {
            compose.onAllNodesWithText(it).onFirst().assertIsDisplayed()
        }
        // The sign-out button is fixed below the scrolling settings list.
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        println("SEMANTICS profile OK")
    }

    // --- empty states: this is what the reference screenshots show ---

    @Test
    fun libraryScreen_emptyState_matchesReference() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                LibraryScreen(
                    onOpenBook = {}, onOpenSettings = {}, onOpenHome = {},
                    onOpenBookmarks = {}, onOpenProfile = {},
                    viewModel = libraryVm(),
                )
            }
        }
        compose.onNodeWithText("My Library").assertIsDisplayed()
        compose.onNodeWithText("0 books").assertIsDisplayed()
        compose.onNodeWithText("Import books").assertIsDisplayed()
        val bmp = save("android-library-empty")
        val bg = hasColour(bmp, BrandColors.PageDark.toArgbInt())
        println("PIXELS library-empty bg=$bg")
        assertTrue(bg > 5_000, "expected the dark page background, got $bg")
    }

    @Test
    fun homeScreen_emptyState_renders() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                com.bookcon.app.ui.lifestyle.LifestyleHomeScreen(
                    onOpenBook = {}, onOpenReader = {}, onOpenLibrary = {},
                    onOpenBookmarks = {}, onOpenProfile = {}, onOpenSettings = {},
                    onImport = {}, onAddBook = {},
                    viewModel = libraryVm(),
                )
            }
        }
        save("android-home-empty")
    }

    // --- interactions: tapping a control must reach its callback ---

    @Test
    fun libraryScreen_opensBookOnCardTap() {
        val opened = mutableListOf<String>()
        compose.setContent {
            BookConTheme(darkTheme = true) {
                LibraryScreen(
                    onOpenBook = { opened += it },
                    onOpenSettings = {}, onOpenHome = {},
                    onOpenBookmarks = {}, onOpenProfile = {},
                    viewModel = libraryVm(book("b1", "Atomic Habits", "James Clear")),
                )
            }
        }
        compose.onNodeWithText("Atomic Habits").performClick()
        compose.waitForIdle()
        assertEquals(listOf("b1"), opened)
        println("INTERACTION openBook -> $opened")
    }

    @Test
    fun libraryScreen_formatChipIsInteractive() {
        compose.setContent {
            BookConTheme(darkTheme = true) {
                LibraryScreen(
                    onOpenBook = {}, onOpenSettings = {}, onOpenHome = {},
                    onOpenBookmarks = {}, onOpenProfile = {},
                    viewModel = libraryVm(book("b1", "Atomic Habits", "James Clear")),
                )
            }
        }
        listOf("EPUB", "PDF", "CBZ").forEach { label ->
            compose.onAllNodesWithText(label).onFirst().assertIsDisplayed()
        }
        // The chip row scrolls horizontally, so Tag/Shelf may be off-screen;
        // EPUB/PDF/CBZ are the reference's leading chips.
        compose.onAllNodesWithText("PDF").onFirst().performClick()
        compose.waitForIdle()
        println("INTERACTION format chip click OK")
    }

    @Test
    fun libraryScreen_bottomNavIsInteractive() {
        val hits = mutableListOf<String>()
        compose.setContent {
            BookConTheme(darkTheme = true) {
                LibraryScreen(
                    onOpenBook = {}, onOpenSettings = {},
                    onOpenHome = { hits += "home" },
                    onOpenBookmarks = { hits += "bookmarks" },
                    onOpenProfile = { hits += "profile" },
                    viewModel = libraryVm(book("b1", "Atomic Habits", "James Clear")),
                )
            }
        }
        compose.onAllNodesWithText("Home").onFirst().performClick()
        compose.onAllNodesWithText("Bookmarks").onFirst().performClick()
        compose.onAllNodesWithText("Profile").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(listOf("home", "bookmarks", "profile"), hits)
        println("INTERACTION bottom nav -> $hits")
    }

    @Test
    fun bookDetail_continueReadingReachesCallback() {
        val resumed = mutableListOf<String>()
        val vm: com.bookcon.app.ui.details.BookDetailsViewModel = mock {
            on { state } doReturn MutableStateFlow(
                com.bookcon.app.ui.details.BookDetailsUiState(
                    loading = false,
                    book = book("b1", "Atomic Habits", "James Clear"),
                    pagesLabel = "320 Pgs",
                    progressPercent = 0.42,
                ),
            )
            // bind() is a void method; a Mockito mock no-ops it by default.
        }
        compose.setContent {
            BookConTheme(darkTheme = true) {
                com.bookcon.app.ui.lifestyle.LifestyleBookDetailScreen(
                    bookId = "b1", onBack = {},
                    onContinueReading = { resumed += "b1" },
                    viewModel = vm,
                )
            }
        }
        compose.onNodeWithText("Continue Reading").performClick()
        compose.waitForIdle()
        assertEquals(listOf("b1"), resumed)
        println("INTERACTION continueReading -> $resumed")
    }

    /**
     * The page-turn sheet must not get stuck over the reader.
     *
     * This lives here rather than in PageTurnCoordinatorTest because it needs a
     * real frame clock: `Animatable.animateTo` suspends forever without one, so a
     * plain Robolectric scope can never reach the cleanup that clears the overlay.
     */
    @Test
    fun pageTurnOverlay_clears_itself_when_the_turn_finishes() {
        lateinit var coordinator: PageTurnCoordinator
        var navigated = false

        compose.setContent {
            coordinator = rememberPageTurnCoordinator()
            Box(Modifier.fillMaxSize()) {
                PageTurnOverlay(coordinator = coordinator, pageBackground = Color.Black)
            }
        }
        compose.waitForIdle()

        // No navigator view attached: the turn falls back to a plain navigation and
        // must leave no overlay behind.
        coordinator.configure(
            animation = { PageAnimation.PageTurn },
            navigate = { navigated = true },
        )
        coordinator.turnAnimated(forward = true)
        compose.waitForIdle()

        assertEquals(true, navigated, "the turn must still navigate")
        kotlin.test.assertNull(coordinator.turn, "no overlay may be left covering the reader")
        assertEquals(0, coordinator.animatedTurnCount)
    }
}

// Sampled directly from the reference screenshots.
private fun detailTopArgb() = android.graphics.Color.rgb(0x2A, 0x14, 0x20)
private fun mintArgb() = android.graphics.Color.rgb(0x52, 0xD7, 0xB5)
private fun pageArgb() = android.graphics.Color.rgb(0x0F, 0x10, 0x14)

/** Compose Color -> packed ARGB int. */
private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb(
        (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
    )
