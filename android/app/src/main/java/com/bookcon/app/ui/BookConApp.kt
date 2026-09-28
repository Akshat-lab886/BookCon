package com.bookcon.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.bookcon.app.core.Session
import com.bookcon.app.di.authRepository
import kotlinx.coroutines.launch

/** Route table for the single-activity app (TRD §1: single-activity Compose). */
object Routes {
    const val AUTH = "auth"
    const val LIBRARY = "library"
    const val BOOKMARKS = "bookmarks"
    const val PROFILE = "profile"
    const val DETAILS = "details/{bookId}"
    const val READER = "reader/{bookId}"
    const val SETTINGS = "settings"
    const val ANNOTATIONS = "annotations"          // global list
    const val BOOK_ANNOTATIONS = "annotations/{bookId}"
    const val DEVICES = "settings/devices"
    const val STORAGE = "settings/storage"
    const val AI_SETTINGS = "settings/ai"
    const val VOCAB = "vocab"
    const val STATS = "stats"
    const val WIFI_IMPORT = "import/wifi"
    const val NOTEBOOKS = "notebooks"
    const val WELCOME = "welcome"
    const val LIFESTYLE_HOME = "lifestyle"
    const val LIFESTYLE_DETAILS = "lifestyle/details/{bookId}"

    fun details(bookId: String) = "details/$bookId"
    /**
     * `locator` seeks straight to a saved position (from a bookmark). It is a query
     * parameter and URL-encoded because a Readium locator is JSON containing
     * slashes and quotes, which would otherwise be parsed as extra path segments.
     */
    fun reader(bookId: String, locator: String? = null): String =
        if (locator.isNullOrBlank()) {
            "reader/$bookId"
        } else {
            "reader/$bookId?locator=${android.net.Uri.encode(locator)}"
        }
    fun bookAnnotations(bookId: String) = "annotations/$bookId"
    fun lifestyleDetails(bookId: String) = "lifestyle/details/$bookId"
}

@Composable
fun BookConApp(
    navController: NavHostController,
    session: Session?,
    localMode: Boolean = false,
) {
    // Local Vault: storageMode != cloud lets you use the whole app without an
    // account — everything stays on-device and sync workers simply no-op.
    val startInLibrary = session != null || localMode
    val authRepository = LocalContext.current.authRepository()
    val signOutScope = rememberCoroutineScope()
    when {
        !startInLibrary -> NavHost(navController, startDestination = Routes.AUTH) {
            composable(Routes.AUTH) {
                // No navigate() here on purpose: this branch's graph has no LIBRARY
                // destination. Signing in flips `session` in the caller, which flips
                // startInLibrary and rebuilds the graph on the signed-in branch below
                // (startDestination = LIBRARY).
                com.bookcon.app.ui.auth.AuthScreen(
                    onSignedIn = { },
                )
            }
        }
        else -> NavHost(navController, startDestination = Routes.LIBRARY) {
            // Registered in BOTH graphs. Sign-out navigates here from the signed-in
            // graph, and a NavHost that lacks the destination throws
            // IllegalArgumentException on navigate() rather than failing quietly.
            composable(Routes.AUTH) {
                com.bookcon.app.ui.auth.AuthScreen(onSignedIn = { })
            }
            // The four bottom-nav tabs are siblings, not a stack.
            //
            // The library stays the permanent root (popUpTo inclusive = false), so
            // Back from any tab returns to Books instead of exiting the app. Using
            // inclusive = true here would pop the root and break that.
            //
            // saveState/restoreState are deliberately NOT used. With four sibling
            // tabs popping back to a fixed root, restoring a previously-saved
            // destination state made the very first Books -> Home switch work and
            // then every later return to Home silently do nothing (the user had to
            // tap Home dozens of times). A plain pop + launchSingleTop gives a
            // predictable stack: Books is always the root and Back always returns
            // to it.
            fun switchTab(route: String) {
                if (navController.currentDestination?.route == route) return
                navController.navigate(route) {
                    popUpTo(Routes.LIBRARY) { inclusive = false }
                    launchSingleTop = true
                }
            }

            // Library is the main landing page with bottom nav
            composable(Routes.LIBRARY) {
                com.bookcon.app.ui.library.LibraryScreen(
                    onOpenBook = { navController.navigate(Routes.lifestyleDetails(it)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenHome = { switchTab(Routes.LIFESTYLE_HOME) },
                    onOpenBookmarks = { switchTab(Routes.BOOKMARKS) },
                    onOpenProfile = { switchTab(Routes.PROFILE) },
                )
            }
            // Bookmarks tab
            composable(Routes.BOOKMARKS) {
                com.bookcon.app.ui.bookmarks.BookmarksScreen(
                    onBack = { navController.popBackStack() },
                    // Seek to the bookmarked page. Tapping a bookmark used to just
                    // open the book at the last-read position, so every bookmark in
                    // the tab led to the same place.
                    onOpenBook = { bookId, locatorJson ->
                        navController.navigate(Routes.reader(bookId, locatorJson))
                    },
                    onOpenHome = { switchTab(Routes.LIFESTYLE_HOME) },
                    onOpenLibrary = { switchTab(Routes.LIBRARY) },
                    onOpenProfile = { switchTab(Routes.PROFILE) },
                )
            }
            // Profile tab
            composable(Routes.PROFILE) {
                com.bookcon.app.ui.profile.ProfileScreen(
                    onBack = { navController.popBackStack() },
                    onSignOut = {
                        // Clear the session first. Navigating alone left `session`
                        // set, so startInLibrary stayed true and the signed-in graph
                        // was immediately re-selected — the user bounced back into
                        // the library with a still-valid token.
                        signOutScope.launch {
                            authRepository.logout()
                            // The session flip re-installs the auth graph on the next
                            // frame, which is what actually shows the sign-in screen.
                            // Clearing the back stack here keeps Back from returning to
                            // a signed-in screen.
                            navController.popBackStack(Routes.AUTH, inclusive = false)
                        }
                    },
                    onNavigateToSettings = { section ->
                        when (section) {
                            "ai" -> navController.navigate(Routes.AI_SETTINGS)
                            "storage" -> navController.navigate(Routes.STORAGE)
                            "notebooks" -> navController.navigate(Routes.NOTEBOOKS)
                            "vocab" -> navController.navigate(Routes.VOCAB)
                            "stats" -> navController.navigate(Routes.STATS)
                            "wifi" -> navController.navigate(Routes.WIFI_IMPORT)
                            "devices" -> navController.navigate(Routes.DEVICES)
                            "annotations" -> navController.navigate(Routes.ANNOTATIONS)
                            else -> navController.navigate(Routes.SETTINGS)
                        }
                    },
                    onOpenHome = { switchTab(Routes.LIFESTYLE_HOME) },
                    onOpenLibrary = { switchTab(Routes.LIBRARY) },
                    onOpenBookmarks = { switchTab(Routes.BOOKMARKS) },
                )
            }
            // Oripio-style home (legacy route, kept for compatibility)
            composable(Routes.LIFESTYLE_HOME) {
                com.bookcon.app.ui.lifestyle.LifestyleHomeScreen(
                    onOpenBook = { navController.navigate(Routes.lifestyleDetails(it)) },
                    onOpenReader = { navController.navigate(Routes.reader(it)) },
                    onOpenLibrary = { switchTab(Routes.LIBRARY) },
                    onOpenBookmarks = { switchTab(Routes.BOOKMARKS) },
                    onOpenProfile = { switchTab(Routes.PROFILE) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onImport = { navController.navigate(Routes.WIFI_IMPORT) },
                    onAddBook = { navController.navigate(Routes.WIFI_IMPORT) },
                )
            }
            composable(Routes.LIFESTYLE_DETAILS) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                com.bookcon.app.ui.lifestyle.LifestyleBookDetailScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onContinueReading = { navController.navigate(Routes.reader(bookId)) },
                )
            }
            composable(Routes.WELCOME) {
                com.bookcon.app.ui.lifestyle.LifestyleWelcomeScreen(
                    onGetStarted = {
                        navController.navigate(Routes.LIBRARY) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.DETAILS) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                com.bookcon.app.ui.details.BookDetailsScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    openReader = { navController.navigate(Routes.reader(bookId)) },
                    openEdit = { /* edit sheet lives inside details screen */ },
                    // Per-book highlights. This route existed and was registered, but
                    // nothing ever navigated to it, so annotations could be created and
                    // exported yet never read back.
                    openAnnotations = { navController.navigate(Routes.bookAnnotations(bookId)) },
                )
            }
            composable(Routes.READER) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                // Set when arriving from a bookmark; null means "resume where I was".
                val startLocator = entry.arguments?.getString("locator")?.takeIf { it.isNotBlank() }
                com.bookcon.app.ui.reader.ReaderScreen(
                    bookId = bookId,
                    startLocatorJson = startLocator,
                    onClose = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                com.bookcon.app.ui.settings.SettingsScreen(
                    onBack = { navController.popBackStack() },
                    openDevices = { navController.navigate(Routes.DEVICES) },
                    openStorage = { navController.navigate(Routes.STORAGE) },
                    openAiSummary = { navController.navigate(Routes.AI_SETTINGS) },
                    openVocab = { navController.navigate(Routes.VOCAB) },
                    openStats = { navController.navigate(Routes.STATS) },
                    openWifiImport = { navController.navigate(Routes.WIFI_IMPORT) },
                    openNotebooks = { navController.navigate(Routes.NOTEBOOKS) },
                    onSignedOut = {
                        // SettingsViewModel.signOut() already cleared the session, so
                        // the graph has swapped to AUTH by now. Only the back stack
                        // needs tidying; navigating again would stack a second
                        // sign-in screen on top of the current one.
                        navController.popBackStack(Routes.AUTH, inclusive = false)
                    },
                )
            }
            composable(Routes.DEVICES) {
                com.bookcon.app.ui.settings.DevicesScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STORAGE) {
                com.bookcon.app.ui.settings.StorageManagerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.AI_SETTINGS) {
                com.bookcon.app.ui.settings.AiSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.VOCAB) {
                com.bookcon.app.ui.vocab.VocabScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.STATS) {
                com.bookcon.app.ui.stats.StatsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WIFI_IMPORT) {
                com.bookcon.app.ui.importwifi.WifiImportScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NOTEBOOKS) {
                com.bookcon.app.ui.notebooks.NotebooksScreen(
                    onBack = { navController.popBackStack() },
                    openBook = { navController.navigate(Routes.reader(it)) },
                )
            }
            composable(Routes.ANNOTATIONS) {
                com.bookcon.app.ui.annotations.AnnotationsScreen(
                    onBack = { navController.popBackStack() },
                    openBook = { navController.navigate(Routes.details(it)) },
                )
            }
            composable(Routes.BOOK_ANNOTATIONS) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                com.bookcon.app.ui.annotations.AnnotationsScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    // Tapping a book from its annotation list opens the same detail
                    // screen the library uses.
                    openBook = { navController.navigate(Routes.lifestyleDetails(it)) },
                )
            }
        }
    }
}
