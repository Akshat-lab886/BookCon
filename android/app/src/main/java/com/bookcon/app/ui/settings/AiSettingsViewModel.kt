package com.bookcon.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookcon.app.core.AiKeyStore
import com.bookcon.app.core.AppSettings
import com.bookcon.app.core.SettingsRepository
import com.bookcon.app.core.Summarizer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Outcome of the "Test connection" round-trip shown inline on the AI screen. */
sealed interface AiTestState {
    data object Idle : AiTestState
    data object Running : AiTestState
    data class Success(val providerLabel: String) : AiTestState
    data class Failure(val message: String) : AiTestState
}

/**
 * Backing state for [AiSettingsScreen]: provider/base-url/model persist through
 * [SettingsRepository]; the API key never leaves this device ([AiKeyStore]).
 */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    // AiKeyStore is constructed, not injected: core ships it without a Hilt binding.
    private val keyStore = AiKeyStore(appContext)

    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val _test = MutableStateFlow<AiTestState>(AiTestState.Idle)
    val test: StateFlow<AiTestState> = _test

    fun setProvider(provider: String) {
        _test.value = AiTestState.Idle // a result from the previous provider is stale
        viewModelScope.launch {
            settingsRepo.setAiProvider(provider)
            // Auto-fill base URL for known providers
            when (provider) {
                "groq" -> settingsRepo.setAiBaseUrl("https://api.groq.com/openai/v1")
                "openai" -> settingsRepo.setAiBaseUrl("")
                "gemini" -> settingsRepo.setAiBaseUrl("")
                else -> {} // custom — user sets it
            }
        }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch { settingsRepo.setAiBaseUrl(url) }
    }

    fun setModel(model: String) {
        viewModelScope.launch { settingsRepo.setAiModel(model) }
    }

    /** Key stored on this device, read once when the screen is composed. */
    fun storedKey(): String = keyStore.get()

    /** Persists the entered key on-device; blank input clears it instead. */
    fun saveKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (key.isBlank()) keyStore.clear() else keyStore.set(key.trim())
        }
    }

    /** Removes the key from this device. */
    fun clearKey() {
        viewModelScope.launch(Dispatchers.IO) { keyStore.clear() }
    }

    /** One-shot summarize round-trip used as a connectivity/credential test. */
    fun testConnection(provider: String, baseUrl: String, model: String, apiKey: String) {
        _test.value = AiTestState.Running
        viewModelScope.launch(Dispatchers.IO) {
            val result = Summarizer().summarize(
                provider = provider,
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model.ifBlank { Summarizer.defaultModel(provider) },
                bookTitle = "Test",
                pageLabel = "test page",
                pageText = "The quick brown fox.",
            )
            _test.value = result.fold(
                onSuccess = { AiTestState.Success(providerLabel(provider)) },
                onFailure = { AiTestState.Failure(it.message ?: "Request failed") },
            )
        }
    }

    companion object {
        /** Display name for a provider id (openai | gemini | custom). */
        fun providerLabel(provider: String): String = when (provider) {
            "openai" -> "OpenAI"
            "gemini" -> "Gemini"
            "groq" -> "Groq"
            "anthropic" -> "Anthropic"
            else -> "custom server"
        }
    }
}
