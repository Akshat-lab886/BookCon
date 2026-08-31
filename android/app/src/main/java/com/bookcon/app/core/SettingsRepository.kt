package com.bookcon.app.core

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "bookcon_settings")

/**
 * Reader defaults & app settings persisted in DataStore (PRD RD-2…RD-16).
 *
 * Extended (additive only, per contract) with individual keys + a typed [SettingsRepository.update]
 * transform for every reader field the reader UI needs:
 * font family/size/weight/line-height/paragraph-spacing/letter-spacing/alignment/publisher-defaults,
 * margins, pagination mode, page-turn animation, reader theme, brightness, keep-screen-on,
 * volume-key turns, orientation lock and the 3×3 tap-zone grid (RD-8).
 */
data class AppSettings(
    // 127.0.0.1 pairs with `adb reverse tcp:8000 tcp:8000` (USB debugging); emulator users
    // should set http://10.0.2.2:<port> and LAN users the host's IP in Settings.
    val serverUrl: String = "http://127.0.0.1:8000",
    val themeMode: String = "auto", // auto | light | dark | black | sepia
    val readerFontFamily: String = "Literata", // bundled open-font name (see ui/reader/ReaderFonts)
    val readerFontSizeSp: Float = 18f,
    val readerFontWeight: Float = 400f,
    val readerLetterSpacing: Float = 0f,
    val readerTextAlignment: String = "justify", // start | center | end | justify
    val readerPublisherDefaults: Boolean = false, // RD-6 publisher default override
    val readerLineHeight: Float = 1.5f,
    val readerParagraphSpacing: Float = 0.6f,
    val readerMarginsHorizontal: Float = 24f,
    val readerMarginsVertical: Float = 32f,
    val readerPaginationMode: String = "paginated", // paginated | scroll
    val readerPageTurnAnimation: String = "slide", // none | slide | fade | page_turn
    val readerTheme: String = "light", // light | sepia | dark | black (RD-5)
    val readerBrightness: Float? = null, // null → system (RD-14)
    val readerKeepScreenOn: Boolean = false, // RD-18
    val volumeKeyTurns: Boolean = true, // RD-16
    val orientationLock: String = "system", // system | portrait | landscape (RD-15)
    /** Serialized TapZoneGrid JSON (RD-8); blank → right-handed default. */
    val tapZonesJson: String = "",
    /**
     * Data & storage mode: "cloud" (account sync), "local" (on-device only, no
     * account needed), or "both" (account + manual local archive import/export).
     */
    val storageMode: String = "cloud",
    /** BYOK AI page summarization provider: openai | gemini | custom. */
    val aiProvider: String = "openai",
    /** OpenAI-compatible base URL, used only when [aiProvider] == "custom". */
    val aiBaseUrl: String = "",
    /** Model id; blank → Summarizer.defaultModel([aiProvider]). */
    val aiModel: String = "",

    val pdfNightMode: Boolean = false,
    val pdfWarmth: Int = 0,
    val ttsRate: Int = 100,
    val ttsVoiceName: String = "",
    val statsGoalMinutes: Int = 0,
    val vocabCaptureEnabled: Boolean = true,
) {
    val localUsable: Boolean get() = storageMode != "cloud"
}


@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val store = context.dataStore

    private val keyServer = stringPreferencesKey("server_url")
    private val keyThemeMode = stringPreferencesKey("theme_mode")

    // Legacy single-blob key kept for backward compatibility of the fontSize value.
    private val keyReaderSettings = stringPreferencesKey("reader_settings_json")

    // New individual preference keys (reader).
    private val keyReaderFontFamily = stringPreferencesKey("reader_font_family")
    private val keyReaderFontSizeSp = floatPreferencesKey("reader_font_size_sp")
    private val keyReaderFontWeight = floatPreferencesKey("reader_font_weight")
    private val keyReaderLetterSpacing = floatPreferencesKey("reader_letter_spacing")
    private val keyReaderTextAlignment = stringPreferencesKey("reader_text_alignment")
    private val keyReaderPublisherDefaults = booleanPreferencesKey("reader_publisher_defaults")
    private val keyReaderLineHeight = floatPreferencesKey("reader_line_height")
    private val keyReaderParagraphSpacing = floatPreferencesKey("reader_paragraph_spacing")
    private val keyReaderMarginsHorizontal = floatPreferencesKey("reader_margins_horizontal")
    private val keyReaderMarginsVertical = floatPreferencesKey("reader_margins_vertical")
    private val keyReaderPaginationMode = stringPreferencesKey("reader_pagination_mode")
    private val keyReaderPageTurnAnimation = stringPreferencesKey("reader_page_turn_animation")
    private val keyReaderTheme = stringPreferencesKey("reader_theme")
    private val keyReaderBrightness = floatPreferencesKey("reader_brightness")
    private val keyReaderKeepScreenOn = booleanPreferencesKey("reader_keep_screen_on")
    private val keyVolumeKeyTurns = booleanPreferencesKey("volume_key_turns")
    private val keyOrientationLock = stringPreferencesKey("orientation_lock")
    private val keyTapZones = stringPreferencesKey("reader_tap_zones_json")
    private val keyStorageMode = stringPreferencesKey("storage_mode")
    private val keyAiProvider = stringPreferencesKey("ai_provider")
    private val keyAiBaseUrl = stringPreferencesKey("ai_base_url")
    private val keyAiModel = stringPreferencesKey("ai_model")
    private val keyPdfNightMode = booleanPreferencesKey("pdf_night_mode")
    private val keyPdfWarmth = intPreferencesKey("pdf_warmth")
    private val keyTtsRate = intPreferencesKey("tts_rate")
    private val keyTtsVoiceName = stringPreferencesKey("tts_voice_name")
    private val keyStatsGoalMinutes = intPreferencesKey("stats_goal_minutes")
    private val keyVocabCaptureEnabled = booleanPreferencesKey("vocab_capture_enabled")

    val settings: StateFlow<AppSettings> = context.dataStore.data
        .map(::readSettings)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /**
     * Typed update hook used by all reader setters: read current values, apply [transform],
     * write every known key back atomically.
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            prefs.writeSettings(transform(readSettings(prefs)))
        }
    }

    suspend fun setServerUrl(url: String) {
        update { it.copy(serverUrl = url.trimEnd('/')) }
    }

    // --- Appended for the settings UI package (additive, contract-allowed) -------------

    /** Appearance (PRD): auto | light | dark | black | sepia. */
    suspend fun setThemeMode(mode: String) {
        update { it.copy(themeMode = mode) }
    }

    /** cloud | local | both — see [AppSettings.storageMode]. */
    suspend fun setStorageMode(mode: String) {
        update { it.copy(storageMode = mode) }
    }

    /** openai | gemini | custom — see [AppSettings.aiProvider]. */
    suspend fun setAiProvider(provider: String) {
        update { it.copy(aiProvider = provider) }
    }

    suspend fun setAiBaseUrl(url: String) {
        update { it.copy(aiBaseUrl = url.trim()) }
    }

    suspend fun setAiModel(model: String) {
        update { it.copy(aiModel = model.trim()) }
    }
    suspend fun setPdfNightMode(v: Boolean) { update { it.copy(pdfNightMode = v) } }
    suspend fun setPdfWarmth(v: Int) { update { it.copy(pdfWarmth = v.coerceIn(0, 100)) } }
    suspend fun setTtsRate(v: Int) { update { it.copy(ttsRate = v.coerceIn(50, 300)) } }
    suspend fun setTtsVoiceName(v: String) { update { it.copy(ttsVoiceName = v) } }
    suspend fun setStatsGoalMinutes(v: Int) { update { it.copy(statsGoalMinutes = v.coerceAtLeast(0)) } }
    suspend fun setVocabCaptureEnabled(v: Boolean) { update { it.copy(vocabCaptureEnabled = v) } }

    private val keyLastSyncedAt = longPreferencesKey("last_synced_at")

    /** Epoch millis of the last completed pull sync; "never" when null. */
    val lastSyncedAt: Flow<Long?> = context.dataStore.data.map { prefs -> prefs[keyLastSyncedAt] }

    /** Called when a pull sync completes (force-sync path here; TODO(PullWorker): wire it too). */
    suspend fun setLastSyncedAt(epochMillis: Long) {
        store.edit { it[keyLastSyncedAt] = epochMillis }
    }

    fun observe(): Flow<AppSettings> = settings

    private fun readSettings(prefs: Preferences): AppSettings = AppSettings(
        serverUrl = prefs[keyServer] ?: DEFAULT_SERVER,
        themeMode = prefs[keyThemeMode] ?: "auto",
        // Fall back to the legacy JSON blob if the dedicated key was never written.
        readerFontSizeSp = prefs[keyReaderFontSizeSp]
            ?: prefs[keyReaderSettings]?.let { jsonFloat(it, "fontSize") }
            ?: 18f,
        readerFontFamily = prefs[keyReaderFontFamily] ?: "Literata",
        readerFontWeight = prefs[keyReaderFontWeight] ?: 400f,
        readerLetterSpacing = prefs[keyReaderLetterSpacing] ?: 0f,
        readerTextAlignment = prefs[keyReaderTextAlignment] ?: "justify",
        readerPublisherDefaults = prefs[keyReaderPublisherDefaults] ?: false,
        readerLineHeight = prefs[keyReaderLineHeight] ?: 1.5f,
        readerParagraphSpacing = prefs[keyReaderParagraphSpacing] ?: 0.6f,
        readerMarginsHorizontal = prefs[keyReaderMarginsHorizontal] ?: 24f,
        readerMarginsVertical = prefs[keyReaderMarginsVertical] ?: 32f,
        readerPaginationMode = prefs[keyReaderPaginationMode] ?: "paginated",
        readerPageTurnAnimation = prefs[keyReaderPageTurnAnimation] ?: "slide",
        readerTheme = prefs[keyReaderTheme] ?: "light",
        readerBrightness = prefs[keyReaderBrightness],
        readerKeepScreenOn = prefs[keyReaderKeepScreenOn] ?: false,
        volumeKeyTurns = prefs[keyVolumeKeyTurns] ?: true,
        orientationLock = prefs[keyOrientationLock] ?: "system",
        storageMode = prefs[keyStorageMode] ?: "cloud",
        aiProvider = prefs[keyAiProvider] ?: "openai",
        aiBaseUrl = prefs[keyAiBaseUrl] ?: "",
        aiModel = prefs[keyAiModel] ?: "",
        pdfNightMode = prefs[keyPdfNightMode] ?: false,
        pdfWarmth = prefs[keyPdfWarmth] ?: 0,
        ttsRate = prefs[keyTtsRate] ?: 100,
        ttsVoiceName = prefs[keyTtsVoiceName] ?: "",
        statsGoalMinutes = prefs[keyStatsGoalMinutes] ?: 0,
        vocabCaptureEnabled = prefs[keyVocabCaptureEnabled] ?: true,
        tapZonesJson = prefs[keyTapZones] ?: "",
    )

    private fun MutablePreferences.writeSettings(s: AppSettings) {
        this[keyServer] = s.serverUrl.trimEnd('/')
        this[keyThemeMode] = s.themeMode
        this[keyReaderFontSizeSp] = s.readerFontSizeSp
        this[keyReaderFontFamily] = s.readerFontFamily
        this[keyReaderFontWeight] = s.readerFontWeight
        this[keyReaderLetterSpacing] = s.readerLetterSpacing
        this[keyReaderTextAlignment] = s.readerTextAlignment
        this[keyReaderPublisherDefaults] = s.readerPublisherDefaults
        this[keyReaderLineHeight] = s.readerLineHeight
        this[keyReaderParagraphSpacing] = s.readerParagraphSpacing
        this[keyReaderMarginsHorizontal] = s.readerMarginsHorizontal
        this[keyReaderMarginsVertical] = s.readerMarginsVertical
        this[keyReaderPaginationMode] = s.readerPaginationMode
        this[keyReaderPageTurnAnimation] = s.readerPageTurnAnimation
        this[keyReaderTheme] = s.readerTheme
        val brightness = s.readerBrightness
        if (brightness != null) this[keyReaderBrightness] = brightness else remove(keyReaderBrightness)
        this[keyReaderKeepScreenOn] = s.readerKeepScreenOn
        this[keyVolumeKeyTurns] = s.volumeKeyTurns
        this[keyOrientationLock] = s.orientationLock
        this[keyTapZones] = s.tapZonesJson
        this[keyStorageMode] = s.storageMode
        this[keyAiProvider] = s.aiProvider
        this[keyAiBaseUrl] = s.aiBaseUrl
        this[keyAiModel] = s.aiModel
        this[keyPdfNightMode] = s.pdfNightMode
        this[keyPdfWarmth] = s.pdfWarmth
        this[keyTtsRate] = s.ttsRate
        this[keyTtsVoiceName] = s.ttsVoiceName
        this[keyStatsGoalMinutes] = s.statsGoalMinutes
        this[keyVocabCaptureEnabled] = s.vocabCaptureEnabled
    }

    private fun jsonFloat(json: String, field: String): Float? =
        Regex("\"$field\"\\s*:\\s*(-?[0-9.]+)").find(json)?.groupValues?.get(1)?.toFloatOrNull()

    companion object {
        const val DEFAULT_SERVER = "http://127.0.0.1:8000"
    }
}
