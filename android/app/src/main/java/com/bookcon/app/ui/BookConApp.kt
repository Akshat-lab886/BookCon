package com.bookcon.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.bookcon.app.core.Session

/** Route table for the single-activity app (TRD §1: single-activity Compose). */
object Routes {
    const val AUTH = "auth"
    const val LIBRARY = "library"
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

    fun details(bookId: String) = "details/$bookId"
    fun reader(bookId: String) = "reader/$bookId"
    fun bookAnnotations(bookId: String) = "annotations/$bookId"
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
    when {
        !startInLibrary -> NavHost(navController, startDestination = Routes.AUTH) {
            composable(Routes.AUTH) {
                com.bookcon.app.ui.auth.AuthScreen(
                    // No navigate() here: publishing the authenticated session flips
                    // `session` and swaps this whole NavHost for the library graph.
                    // Navigating manually raced that recomposition and crashed with
                    // "destination route library cannot be found" (graph still auth).
                    onSignedIn = { },
                )
            }
        }
        else -> NavHost(navController, startDestination = Routes.LIBRARY) {
            composable(Routes.LIBRARY) {
                com.bookcon.app.ui.library.LibraryScreen(
                    openDetails = { navController.navigate(Routes.details(it)) },
                    openReader = { navController.navigate(Routes.reader(it)) },
                    openSettings = { navController.navigate(Routes.SETTINGS) },
                    openAnnotations = { navController.navigate(Routes.ANNOTATIONS) },
                )
            }
            composable(Routes.DETAILS) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                com.bookcon.app.ui.details.BookDetailsScreen(
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    openReader = { navController.navigate(Routes.reader(bookId)) },
                    openEdit = { /* edit sheet lives inside details screen */ },
                )
            }
            composable(Routes.READER) { entry ->
                val bookId = requireNotNull(entry.arguments?.getString("bookId"))
                com.bookcon.app.ui.reader.ReaderScreen(
                    bookId = bookId,
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
                    onSignedOut = {
                        navController.navigate(Routes.AUTH) {
                            popUpTo(Routes.LIBRARY) { inclusive = true }
                        }
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
                    openBook = {},
                )
            }
        }
    }
}
