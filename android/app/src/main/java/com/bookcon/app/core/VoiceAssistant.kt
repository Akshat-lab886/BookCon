package com.bookcon.app.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * Voice conversation mode (PRD VOICE-1: "talk to my AI from anywhere").
 *
 * Lifecycle:
 *  1. User taps the floating voice button → [VoiceState] goes ACTIVE → we ask the app to
 *     capture a screen shot / read the current page text ([contextProvider]).
 *  2. We start listening (STT). The user speaks.
 *  3. Speech is transcribed → sent to the configured LLM provider with the conversation
 *     history + current page/scrim context. The model reply is streamed back.
 *  4. Reply is spoken via the existing TTS engine ([ReadAloudController] reuse).
 *
 * Permissions: requires [Manifest.permission.RECORD_AUDIO]. The caller (ReaderScreen /
 * MainActivity) is expected to request this via the ActivityResultContracts APIs; this
 * class only *uses* the mic once [startListening] is invoked.
 *
 * Screen context ("can see my screen"): the host supplies [pageContext], which by default
 * returns the current PDF/EPUB page text via [ReaderEngine.currentPageText]. When no engine
 * is attached (e.g. we're in Settings or the library), we fall back to an empty context so
 * the assistant still works as a general companion — "from anywhere".
 */
class VoiceAssistant @Inject constructor(
    private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val aiKeyStore: AiKeyStore,
    private val summarizer: Summarizer,
) {
    enum class Phase { INACTIVE, LISTENING, THINKING, SPEAKING, ERROR }

    data class VoiceState(
        val phase: Phase = Phase.INACTIVE,
        val transcript: String = "",
        val reply: String = "",
        val error: String? = null,
        val voiceAvailable: Boolean = false,
    )

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /**
     * Current page-text / screen context hook. Set by the reader when an engine is active;
     * cleared when leaving (so the assistant gracefully degrades to a general companion).
     */
    var pageContext: () -> String? = { null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listenJob: Job? = null
    private var speakJob: Job? = null

    // -- speech-to-text --------------------------------------------------------

    @SuppressLint("MissingPermission") // caller requests RECORD_AUDIO before starting
    private fun startStt(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            _state.update { it.copy(phase = Phase.ERROR, error = "Speech recognition unavailable on this device") }
            return null
        }
        val sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            ?: SpeechRecognizer.createSpeechRecognizer(appContext)
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    _state.update { it.copy(transcript = text) }
                    handleUtterance(text)
                } else {
                    _state.update { it.copy(phase = Phase.ERROR, error = "Didn't catch that. Try again.") }
                    reset()
                }
            }

            override fun onError(errorCode: Int) {
                val msg = when (errorCode) {
                    SpeechRecognizer.ERROR_NETWORK -> "Network error contacting the speech service"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match — speak clearly"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try again"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy, retry in a moment"
                    SpeechRecognizer.ERROR_CLIENT -> "Recognizer client error"
                    SpeechRecognizer.ERROR_SERVER -> "Recognizer server error"
                    else -> "Speech error code $errorCode"
                }
                _state.update { it.copy(phase = Phase.ERROR, error = msg) }
                reset()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        sr.startListening(intent)
        return sr
    }

    // -- the LLM call ----------------------------------------------------------

    private val history = mutableListOf<ChatMessage>()

    private fun handleUtterance(text: String) {
        scope.launch {
            val settings = settingsRepository.settings.value
            val apiKey = withContext(Dispatchers.IO) { aiKeyStore.get() }
            if (apiKey.isBlank()) {
                _state.update {
                    it.copy(phase = Phase.ERROR, error = "Set your AI key in Settings (BYOK).")
                }
                reset()
                return@launch
            }
            _state.update { it.copy(phase = Phase.THINKING) }
            val ctx = withContext(Dispatchers.IO) { pageContext()?.take(8_000) }
            val result = summarizer.chat(
                provider = settings.aiProvider,
                baseUrl = settings.aiBaseUrl,
                apiKey = apiKey,
                model = settings.aiModel,
                history = history.toList(),
                systemPrompt = VOICE_SYSTEM_PROMPT,
                context = ctx,
            )
            result.onSuccess { reply ->
                history.add(ChatMessage("user", text))
                history.add(ChatMessage("assistant", reply))
                // trim history to last 12 turns to bound token window
                if (history.size > 24) history.subList(0, history.size - 24).clear()
                speak(reply)
            }.onFailure { e ->
                _state.update {
                    it.copy(phase = Phase.ERROR, error = e.message ?: "Assistant error")
                }
                reset()
            }
        }
    }

    // -- text-to-speech (reuse existing controller when available) -------------

    private var readAloud: ReadAloudController? = null
    fun attachTts(controller: ReadAloudController) { readAloud = controller }
    fun detachTts() { readAloud = null }

    private fun speak(text: String) {
        _state.update { it.copy(phase = Phase.SPEAKING, reply = text) }
        val controller = readAloud
        if (controller != null && controller.isReady()) {
            controller.speak(text)
            controller.onIdle = {
                _state.update { it.copy(phase = Phase.INACTIVE) }
            }
        } else {
            // Fallback: direct platform TTS
            fallbackTts(text)
        }
    }

    private var fallbackTts: TextToSpeech? = null
    private fun fallbackTts(text: String) {
        if (fallbackTts != null) { doSpeak(text); return }
        fallbackTts = TextToSpeech(appContext) { code ->
            if (code == TextToSpeech.SUCCESS) {
                fallbackTts?.language = Locale.getDefault()
                doSpeak(text)
            } else {
                _state.update { it.copy(phase = Phase.ERROR, error = "TTS unavailable") }
                reset()
            }
        }
    }

    private fun doSpeak(text: String) {
        val tts = fallbackTts ?: return
        tts.setLanguage(Locale.getDefault())
        tts.setSpeechRate(0.95f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice-assistant")
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { reset() }
            @Deprecated("Deprecated in Java")
            override fun onStart(utteranceId: String?) {}
            override fun onStop(utteranceId: String?, interrupted: Boolean) { reset() }
            override fun onDone(utteranceId: String?) {
                _state.update { it.copy(phase = Phase.INACTIVE) }
            }
        })
    }

    // -- lifecycle / control ---------------------------------------------------

    fun startListening() {
        if (_state.value.phase != Phase.INACTIVE) return
        _state.update { VoiceState(phase = Phase.LISTENING, voiceAvailable = true) }
        val sr = startStt()
        // auto-stop after a generous window
        listenJob?.cancel()
        listenJob = scope.launch {
            kotlinx.coroutines.delay(15_000)
            if (_state.value.phase == Phase.LISTENING) {
                sr?.stopListening()
                _state.update { it.copy(phase = Phase.ERROR, error = "Stopped listening — try again") }
                reset()
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        speakJob?.cancel()
        readAloud?.shutdown()
        _state.update { VoiceState() }
    }

    private fun reset() {
        listenJob?.cancel()
        listenJob = null
    }

    companion object {
        private const val TAG = "VoiceAssistant"
        private const val VOICE_SYSTEM_PROMPT =
            "You are BookCon, a friendly voice reading assistant. The user is holding their " +
            "phone or tablet and can be reading anything (a book, a web page, an email, an " +
            "app, or even just staring at their home screen). Answer concisely and conversationally " +
            "in 1-3 short sentences. If the user asks about what is on screen, use the page " +
            "context they shared. Otherwise just chat normally."
    }
}
