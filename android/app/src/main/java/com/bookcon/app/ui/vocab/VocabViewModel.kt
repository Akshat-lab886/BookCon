package com.bookcon.app.ui.vocab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.AppSettings
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.core.VocabStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing state for [VocabScreen]: the saved-word list, the due review queue and
 * the "Auto-capture" setting (persisted through [SettingsRepository]).
 */
@HiltViewModel
class VocabViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // VocabStore is constructed, not injected: core ships it without a Hilt binding.
    private val store = VocabStore(appContext)

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    /** All saved words, newest first (browse list + count). */
    private val _entries = MutableStateFlow<List<VocabStore.VocabEntry>>(emptyList())
    val entries: StateFlow<List<VocabStore.VocabEntry>> = _entries

    /** Cards due for review right now, longest-overdue first. */
    private val _due = MutableStateFlow<List<VocabStore.VocabEntry>>(emptyList())
    val due: StateFlow<List<VocabStore.VocabEntry>> = _due

    /** Whether the current review card's definition is revealed. */
    private val _revealed = MutableStateFlow(false)
    val revealed: StateFlow<Boolean> = _revealed

    init {
        refresh()
    }

    /** Reloads entries + due queue from disk (after grading, removal, screen focus). */
    fun refresh() {
        viewModelScope.launch {
            _entries.value = store.all()
            _due.value = store.due()
            if (_due.value.isEmpty()) _revealed.value = false
        }
    }

    /** Persists the auto-capture toggle used by the reader word-lookup popup. */
    fun setCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setVocabCaptureEnabled(enabled) }
    }

    fun toggleRevealed() {
        _revealed.value = !_revealed.value
    }

    /** Grades the front card ("Again" = false / "Got it" = true), then advances. */
    fun gradeCurrent(known: Boolean) {
        val card = _due.value.firstOrNull() ?: return
        _revealed.value = false
        viewModelScope.launch {
            store.grade(card.word, known)
            refreshNow()
        }
    }

    /** Deletes [word] from the notebook entirely. */
    fun remove(word: String) {
        viewModelScope.launch {
            store.remove(word)
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        _entries.value = store.all()
        _due.value = store.due()
        if (_due.value.isEmpty()) _revealed.value = false
    }
}
