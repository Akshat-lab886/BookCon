package com.bookcon.app.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
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
            if (ready) tts.setSpeechRate(field / 100f)
        }

    private var ready = false
    private var pending: String? = null
    private var counter: Int = 0

    private val tts: TextToSpeech = TextToSpeech(appContext) { code ->
        ready = code == TextToSpeech.SUCCESS
        if (!ready) {
            _state.value = State(Status.ERROR, "Text-to-speech unavailable on this device")
            return@TextToSpeech
        }
        runCatching { tts.language = Locale.getDefault() }
        ratePercent = ratePercent
        pending?.let { rest -> speak(rest) }
        pending = null
    }

    /** Speaks [text]; queues until init finishes if needed. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ready) {
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
            }

            override fun onDone(utteranceId: String?) {
                _state.value = State(Status.IDLE)
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
        if (ready) tts.stop()
        _state.value = State(Status.PAUSED)
    }

    /** Full teardown when leaving the reader. */
    fun shutdown() {
        runCatching {
            tts.stop()
            tts.shutdown()
        }
        _state.value = State(Status.IDLE)
        ready = false
    }
}
