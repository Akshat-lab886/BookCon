package com.bookcon.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.bookcon.app.core.SessionStore
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.ui.BookConApp
import com.bookcon.app.ui.theme.BookConTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * AppCompatActivity so Readium navigator fragments can be embedded by the reader
 * (they require a FragmentActivity host).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var sessions: SessionStore
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            // Keep AppCompat night mode in step for dialogs/fragments outside Compose.
            // Side effect must run OUT of composition (LaunchedEffect), not inline.
            LaunchedEffect(settings.themeMode) {
                val nightMode = when (settings.themeMode) {
                    "light", "sepia" -> AppCompatDelegate.MODE_NIGHT_NO
                    "dark", "black" -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
                if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
                    AppCompatDelegate.setDefaultNightMode(nightMode)
                }
            }
            BookConTheme(themeMode = settings.themeMode) {
                val session by sessions.session.collectAsStateWithLifecycle()
                // A blank-token session is the pre-auth placeholder used to pin the API
                // base URL — it must NOT count as "signed in" or the library switch would
                // tear down the AuthViewModel mid-login and cancel the HTTP call.
                val activeSession = session?.takeIf { it.accessToken.isNotBlank() }
                val navController = rememberNavController()
                BookConApp(
                    navController = navController,
                    session = activeSession,
                    localMode = settings.storageMode != "cloud",
                )
            }
        }
    }
}
