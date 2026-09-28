package com.bookcon.app.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Read-aloud controller (PRD TTS-*): wraps the platform TextToSpeech engine and
 * reports state through [state]. Page text arrives from ReaderEngine.currentPageText()
 * (already implemented for both PDF and EPUB); when an utterance finishes, [onDone]
 * lets the ViewModel auto-turn to the next page and keep reading.
 */
class ReadAloudController(
    context: Context,
    private val onDone: () -> Unit,
) {
    enum class Status { IDLE, SPEAKING, PAUSED, ERROR }

    data class State(val status: Status = Status.IDLE, val message: String? = null)

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Volatile
    var ratePercent: Int = 100
        set(value) {
            field = value.coerceIn(50, 300)
            if (initialised) tts.setSpeechRate(field / 100f)
        }

    /**
     * Persisted TTS voice name. The setting was stored and read back into the settings
     * object but never applied, so voice selection did nothing at all — speech always
     * used whatever the device default happened to be.
     */
    var voiceName: String = ""
        set(value) {
            field = value.trim()
            if (initialised) applyVoice()
        }

    private fun applyVoice() {
        val wanted = voiceName
        runCatching {
            if (wanted.isBlank()) {
                // Reset to the default for the locale we already selected.
                tts.language = Locale.getDefault()
                return@runCatching
            }
            val voice = tts.voices?.firstOrNull { it.name == wanted }
            if (voice != null) tts.voice = voice
        }
    }

    /** Voices available on this device, for the settings picker. */
    fun availableVoices(): List<android.speech.tts.Voice> = runCatching {
        tts.voices?.sortedBy { it.name }?.toList().orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Whether the engine has finished initialising, as observable state.
     *
     * `tts.voices` throws or yields nothing until then, and the init callback only
     * ever pushed an error state on failure — nothing emitted on success. So a
     * caller that read [availableVoices] once and cached it kept whatever it saw at
     * that moment, and a reader that opened before the engine was ready showed no
     * voices at all until the screen was left and reopened.
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private var initialised = false
    private var pending: String? = null
    private var counter: Int = 0

    @Volatile
    private var destroyed = false

    private val tts: TextToSpeech = TextToSpeech(appContext) { code ->
        initialised = code == TextToSpeech.SUCCESS
        _ready.value = initialised
        if (!initialised) {
            _state.value = State(Status.ERROR, "Text-to-speech unavailable on this device")
            return@TextToSpeech
        }
        runCatching { tts.language = Locale.getDefault() }
        runCatching { applyVoice() }
        ratePercent = ratePercent
        pending?.let { rest -> speak(rest) }
        pending = null
    }

    /** Speaks [text]; queues until init finishes if needed. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (destroyed) {
            // The engine is gone and the init callback can never fire again, so
            // parking this in `pending` would drop it silently forever.
            _state.value = State(Status.ERROR, "Text-to-speech has been shut down")
            onIdle?.invoke()
            return
        }
        if (!initialised) {
            pending = text
            return
        }
        val id = "bc-tts-${counter++}"
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = State(Status.SPEAKING)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = State(Status.ERROR, "Speech failed")
                onIdle?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                _state.value = State(Status.IDLE)
                onIdle?.invoke()
                onDone()
            }
        })
        val res = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (res != TextToSpeech.SUCCESS) {
            _state.value = State(Status.ERROR, "Couldn't start speech")
        } else {
            _state.value = State(Status.SPEAKING)
        }
    }

    /** Stops current speech; keeps session alive for resume. */
    fun pause() {
        if (initialised) tts.stop()
        _state.value = State(Status.PAUSED)
    }

    /** Returns true when the underlying TTS engine has finished initialising. */
    fun isReady(): Boolean = initialised

    /**
     * Callback invoked once the currently-spoken utterance finishes. Used by the
     * VoiceAssistant so it can return to the INACTIVE state without re-quering TTS.
     */
    var onIdle: (() -> Unit)? = null
        set(value) {
            field = value
            // Re-bind so pending listeners fire on the next speak()
        }

    /**
     * Stops speaking but keeps the engine alive.
     *
     * This has to be distinct from [shutdown]. `TextToSpeech.shutdown()` releases
     * the engine permanently, and the `TextToSpeech` init callback that drains
     * [pending] only ever fires once — so after a shutdown, the next `speak()` parked
     * its text in `pending` forever while the UI still showed "Reading aloud…". The
     * ViewModel holds this controller in a `by lazy`, so it was never rebuilt: one
     * Stop, or reaching the end of any chapter (which routes through the same call),
     * killed read-aloud for the rest of the session.
     */
    fun stop() {
        if (destroyed) return
        runCatching { tts.stop() }
        pending = null
        _state.value = State(Status.IDLE)
    }

    /** Full teardown. Only for leaving the reader — see [stop] for pausing. */
    fun shutdown() {
        if (destroyed) return
        destroyed = true
        runCatching {
            tts.stop()
            tts.shutdown()
        }
        _state.value = State(Status.IDLE)
        initialised = false
        pending = null
        onIdle = null
    }
}
