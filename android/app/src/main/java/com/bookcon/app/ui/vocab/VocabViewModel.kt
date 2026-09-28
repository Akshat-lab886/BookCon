package com.bookcon.app.ui.vocab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.AppSettings
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.core.VocabStore
import com.bookcon.app.core.VocabStore.VocabEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.flow.asStateFlow

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
    /**
     * The most recently removed entry, kept so the delete can be undone.
     *
     * Exposed as a StateFlow rather than a plain var behind a getter: the screen
     * reads it during composition to decide whether to show the undo banner, and
     * keys its auto-dismiss timer on it. A plain var changed neither, so the banner
     * relied on an unrelated recomposition and its countdown never started.
     */
    private val _pendingRemoval = MutableStateFlow<VocabEntry?>(null)
    val pendingRemoval: StateFlow<VocabEntry?> = _pendingRemoval.asStateFlow()

    fun hasUndoableRemove(): Boolean = _pendingRemoval.value != null

    /**
     * A stray tap on the trash icon used to destroy a word and all of its Leitner
     * box/due progress immediately — no dialog, no snackbar, no way back — and it could
     * even hit the card currently under review. The entry is now retained so
     * [undoRemove] can put it back with its progress intact.
     */
    fun remove(word: String) {
        viewModelScope.launch {
            val key = word.trim().lowercase()
            _pendingRemoval.value = store.all().firstOrNull { it.word.trim().lowercase() == key }
            store.remove(word)
            refreshNow()
        }
    }

    fun undoRemove() {
        val entry = _pendingRemoval.value ?: return
        _pendingRemoval.value = null
        viewModelScope.launch {
            // Restored verbatim: add() would start the word again at Leitner box 0,
            // so the undo would quietly discard the progress it is meant to bring back.
            store.restoreEntry(entry)
            refreshNow()
        }
    }

    private suspend fun refreshNow() {
        _entries.value = store.all()
        _due.value = store.due()
        if (_due.value.isEmpty()) _revealed.value = false
    }
}
