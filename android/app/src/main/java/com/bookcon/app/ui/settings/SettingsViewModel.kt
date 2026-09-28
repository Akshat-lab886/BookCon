package com.bookcon.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.core.SessionStore
import com.bookcon.app.data.repo.AuthRepository
import com.bookcon.app.data.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bookcon.app.data.sync.pendingSyncCount

sealed interface SettingsEvent {
    data object SignedOut : SettingsEvent
    data class Message(val text: String) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: AuthRepository,
    val settingsRepo: SettingsRepository,
    private val sessions: SessionStore,
    private val db: com.bookcon.app.data.local.BookConDatabase,
) : ViewModel() {

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Appearance + server URL etc. (PRD: auto/light/dark/black/sepia). */
    val settings: StateFlow<com.bookcon.app.core.AppSettings> =
        settingsRepo.settings

    val lastSyncedAt: StateFlow<Long?> = settingsRepo.lastSyncedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Local changes the server has not taken yet.
     *
     * "Last synced" only moves when a pull completes, so it can look perfectly
     * healthy while edits sit rejected and undelivered. This is the other half of
     * that story.
     */
    private val _pendingChanges = MutableStateFlow(0)
    val pendingChanges: StateFlow<Int> = _pendingChanges.asStateFlow()

    fun refreshPendingChanges() {
        viewModelScope.launch(Dispatchers.IO) {
            _pendingChanges.value =
                runCatching { db.pendingSyncCount() }.getOrDefault(0)
        }
    }

    val accountEmail: String get() = sessions.current()?.email.orEmpty()

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    /**
     * Force sync: refresh tokens, then enqueue push+pull.
     *
     * The "last synced" stamp is deliberately NOT written here. The jobs only get
     * queued and then wait behind a CONNECTED network constraint, so stamping at this
     * point told the user their library was up to date while nothing had synced —
     * especially misleading when tapped offline. PullWorker stamps it on real
     * completion instead.
     */
    fun forceSync() {
        viewModelScope.launch {
            val refreshed = auth.refreshNow()
            SyncScheduler.requestSync(appContext)
            _events.send(
                SettingsEvent.Message(
                    if (refreshed) "Sync started" else "Sync queued (token refresh failed — check connection)",
                ),
            )
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.logout()
            _events.send(SettingsEvent.SignedOut)
        }
    }
}
