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
                applyBestVoice()
                return@runCatching
            }
            val voice = tts.voices?.firstOrNull { it.name == wanted }
            if (voice != null) tts.voice = voice
        }
    }

    /**
     * Picks the best voice available for the current language.
     *
     * Without a choice, `tts.language = X` leaves whatever the engine considers its
     * own default — frequently the lowest-quality voice it ships, which is the main
     * reason narration can sound thin and robotic on a device that also has good
     * voices installed. The ranking below prefers, in order: a high-quality voice
     * over a normal one, a network (neural) voice over an on-device one, and
     * something that requires no network when the user may be offline.
     *
     * A network voice is preferred even though it needs a connection, because the
     * neural voices are the ones that sound human; the on-device ones are chosen
     * only when there is no network voice for the language. setSpeechRate is left
     * alone — changing it per voice would override the user's own speed setting.
     */
    private fun applyBestVoice() {
        runCatching {
            val locale = tts.language ?: return@runCatching
            val candidates = tts.voices?.filter { matchesLanguage(it, locale) }
                ?: return@runCatching
            if (candidates.isEmpty()) return@runCatching
            val best = candidates.minWithOrNull(
                compareBy<android.speech.tts.Voice> { voice ->
                    // higher quality first
                    -voice.quality
                }.thenBy { voice ->
                    // network (neural) voices before on-device ones
                    if (voice.isNetworkConnectionRequired) 0 else 1
                }.thenBy { it.name },
            ) ?: return@runCatching
            tts.voice = best
        }
    }

    private fun matchesLanguage(voice: android.speech.tts.Voice, locale: Locale): Boolean {
        val v = voice.locale ?: return false
        if (v.language.equals(locale.language, ignoreCase = true)) {
            // A region-specific voice only speaks for its own region; an
            // unspecified one ("en") is the safe fallback for "en-GB".
            return v.country.isNullOrEmpty() ||
                v.country.equals(locale.country, ignoreCase = true)
        }
        return false
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

    /** Utterance id of the last piece queued, so onDone fires once per passage. */
    @Volatile
    private var lastId: String? = null

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
        runCatching { applyBestVoice() }
        ratePercent = ratePercent
        pending?.let { rest -> speak(rest) }
        pending = null
    }

    /**
     * Speaks [text]; queues until init finishes if needed.
     *
     * The text is normalised and then queued in pieces. Two separate problems are
     * addressed: a page sent verbatim is mispronounced (see [SpeechText]), and a
     * page sent whole is longer than an engine will reliably finish, so it used to
     * be truncated mid-sentence with no error reported anywhere.
     */
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
        val parts = SpeechText.prepare(text)
        if (parts.isEmpty()) return
        val session = counter++
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = State(Status.SPEAKING)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = State(Status.ERROR, "Speech failed")
                onIdle?.invoke()
            }

            override fun onDone(utteranceId: String?) {
                // Only the final piece means the whole passage is finished; each
                // earlier one just hands over to the next in the queue.
                if (utteranceId == lastId) {
                    _state.value = State(Status.IDLE)
                    onIdle?.invoke()
                    onDone()
                }
            }
        }
        tts.setOnUtteranceProgressListener(listener)

        // The first piece flushes (the user asked to hear this text now); the rest
        // are appended so the engine keeps a natural pause between them.
        var lastQueued: String? = null
        parts.forEachIndexed { index, part ->
            val id = "bc-tts-$session-$index"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val res = runCatching { tts.speak(part, queueMode, null, id) }
                .getOrDefault(TextToSpeech.ERROR)
            if (res != TextToSpeech.SUCCESS) {
                if (index == 0) {
                    _state.value = State(Status.ERROR, "Couldn't start speech")
                    return
                }
                // The queue was refused part-way: stop cleanly at the piece that
                // did land rather than reporting the whole passage as finished.
                return
            }
            lastQueued = id
        }
        lastId = lastQueued
        _state.value = State(Status.SPEAKING)
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
