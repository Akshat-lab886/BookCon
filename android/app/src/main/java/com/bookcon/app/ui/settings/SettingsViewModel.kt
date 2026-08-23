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

sealed interface SettingsEvent {
    data object SignedOut : SettingsEvent
    data class Message(val text: String) : SettingsEvent
}

/**
 * Tiny helper that records sync completion into the DataStore key read by the
 * Settings screen ("Last synced"). The force-sync path below writes it; TODO(sync):
 * PullWorker should call `settingsRepository.setLastSyncedAt(...)` after a successful
 * applyMerged() so background pulls update the stamp too.
 */
object SyncBookkeeping {
    suspend fun record(settings: SettingsRepository) {
        settings.setLastSyncedAt(System.currentTimeMillis())
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val auth: AuthRepository,
    val settingsRepo: SettingsRepository,
    private val sessions: SessionStore,
) : ViewModel() {

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Appearance + server URL etc. (PRD: auto/light/dark/black/sepia). */
    val settings: StateFlow<com.bookcon.app.core.AppSettings> =
        settingsRepo.settings

    val lastSyncedAt: StateFlow<Long?> = settingsRepo.lastSyncedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accountEmail: String get() = sessions.current()?.email.orEmpty()

    fun setThemeMode(mode: String) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    /** Force sync: refresh tokens, then enqueue push+pull; stamp optimistically. */
    fun forceSync() {
        viewModelScope.launch {
            val refreshed = auth.refreshNow()
            SyncScheduler.requestSync(appContext)
            SyncBookkeeping.record(settingsRepo)
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
