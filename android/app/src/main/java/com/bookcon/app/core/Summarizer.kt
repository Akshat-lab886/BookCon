package com.bookcon.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BYOK page summarization against OpenAI-compatible chat APIs (provider "openai" /
 * "custom") and Google Gemini ("gemini").
 *
 * Never throws across its API boundary: every outcome is a [Result]. Failures carry a
 * short human-readable message; HTTP-level errors include the status code plus a snippet
 * of the response body so users can debug rejected API keys or wrong model names.
 */
class Summarizer {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun summarize(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        bookTitle: String,
        pageLabel: String,
        pageText: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val key = apiKey.trim()
            val effectiveModel = model.trim().ifBlank { defaultModel(provider) }
            val userPrompt = buildUserPrompt(bookTitle, pageLabel, pageText)

            val summary = when (provider.lowercase().trim()) {
                PROVIDER_GEMINI -> {
                    val request = buildGeminiRequest(key, effectiveModel, "$SYSTEM_PROMPT\n\n$userPrompt")
                    parseGemini(execute(request))
                }
                PROVIDER_GROQ -> {
                    val base = normalizedBaseUrl(provider, baseUrl)
                    val request = buildOpenAiRequest("$base/chat/completions", key, effectiveModel, userPrompt)
                    parseOpenAi(execute(request))
                }
                PROVIDER_ANTHROPIC -> {
                    val base = normalizedBaseUrl(provider, baseUrl)
                    val request = buildAnthropicRequest("$base/v1/messages", key, effectiveModel, userPrompt)
                    parseAnthropic(execute(request))
                }
                PROVIDER_CUSTOM -> {
                    // The one documented throw: surfaces as Result.failure(IllegalArgumentException).
                    val base = normalizedBaseUrl(provider, baseUrl)
                    if (base.isBlank()) throw IllegalArgumentException("Set a server URL in Settings")
                    val request = buildOpenAiRequest("$base/chat/completions", key, effectiveModel, userPrompt)
                    parseOpenAi(execute(request))
                }
                PROVIDER_OPENAI -> {
                    val base = normalizedBaseUrl(provider, baseUrl)
                    val request = buildOpenAiRequest("$base/chat/completions", key, effectiveModel, userPrompt)
                    parseOpenAi(execute(request))
                }
                else -> throw IllegalArgumentException("Unsupported AI provider: $provider")
            }
            Result.success(summary)
        } catch (e: HttpStatusException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(IOException(NETWORK_ERROR, e))
        } catch (e: Exception) {
            Result.failure(Exception("Summarization failed: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    // ---------------------------------------------------------------- providers

    private fun buildOpenAiRequest(
        url: String,
        apiKey: String,
        model: String,
        userPrompt: String,
    ): Request {
        val body = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
            put("temperature", 0.3)
            put("max_tokens", 500)
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /**
     * Anthropic's Messages API.
     *
     * It was offered in the settings chips but had no implementation, so selecting it
     * made every AI call fail with "Unsupported AI provider: anthropic" and silently
     * killed page summarization and the whole voice assistant for that setting. It is
     * not OpenAI-compatible: the credential goes in `x-api-key`, the version is
     * pinned in `anthropic-version`, there is no "system" message role (it is a
     * top-level `system` field), and the reply is under `content[].text`.
     */
    private fun buildAnthropicRequest(
        url: String,
        apiKey: String,
        model: String,
        userPrompt: String,
    ): Request {
        val body = JSONObject().apply {
            put("model", model)
            put("system", SYSTEM_PROMPT)
            put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt)),
            )
            put("temperature", 0.3)
            put("max_tokens", 500)
        }
        return Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** Anthropic chat: same wire format, with a multi-turn message array. */
    private fun buildAnthropicChatRequest(
        url: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        systemPrompt: String,
    ): Request {
        val body = JSONObject().apply {
            put("model", model)
            put("system", systemPrompt)
            put(
                "messages",
                JSONArray().apply {
                    for (m in messages) {
                        if (m.role == "system") continue // carried in the top-level field
                        put(JSONObject().put("role", m.role).put("content", m.content))
                    }
                },
            )
            put("max_tokens", 700)
        }
        return Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    /** Concatenates the `text` blocks of an Anthropic content array. */
    private fun parseAnthropic(body: String): String {
        val json = JSONObject(body)
        json.optJSONArray("error")?.let { err ->
            val message = err.optJSONObject(0)?.optString("message")
            if (!message.isNullOrBlank()) throw IllegalStateException(message)
        }
        val content = json.optJSONArray("content")
            ?: throw IllegalStateException("Anthropic returned no content")
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                if (sb.isNotEmpty()) sb.append("\n\n")
                sb.append(block.optString("text"))
            }
        }
        if (sb.isEmpty()) throw IllegalStateException("Anthropic returned an empty response")
        return sb.toString()
    }

    private fun buildGeminiRequest(
        apiKey: String,
        model: String,
        combinedPrompt: String,
    ): Request {
        val body = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", combinedPrompt)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject().put("temperature", 0.3).put("maxOutputTokens", 500),
            )
        }
        return Request.Builder()
            .url("$GEMINI_BASE/models/$model:generateContent")
            // Key goes in a header, never the query string (keeps it out of logs/history).
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun execute(request: Request): String {
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HttpStatusException(response.code, responseBody.take(MAX_ERROR_BODY_CHARS))
            }
            return responseBody
        }
    }

    // ------------------------------------------------------------------ parsing

    private fun parseOpenAi(json: String): String =
        runCatching {
            JSONObject(json)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }.getOrElse {
            throw IllegalStateException("Unexpected response format from the provider")
        }.ifEmpty { throw IllegalStateException("The model returned an empty summary") }

    private fun parseGemini(json: String): String =
        runCatching {
            val parts = JSONObject(json)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            buildString {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (!part.isNull("text")) append(part.optString("text"))
                }
            }.trim()
        }.getOrElse {
            throw IllegalStateException("Unexpected response format from Gemini")
        }.ifEmpty { throw IllegalStateException("Gemini returned an empty summary") }

    // -------------------------------------------------------------- prompt text

    private fun buildUserPrompt(bookTitle: String, pageLabel: String, pageText: String): String =
        "Summarize this page from the book \"$bookTitle\" ($pageLabel) in 4-6 short bullet points. " +
            "Keep names and key facts.\n\nPAGE TEXT:\n${pageText.take(MAX_PAGE_TEXT_CHARS)}"

    /**
     * Marker exception for non-2xx responses so [summarize] keeps the status code and body
     * snippet instead of rebranding it as a network failure.
     */
    private class HttpStatusException(val code: Int, bodySnippet: String) :
        RuntimeException("Server returned HTTP $code: ${bodySnippet.replace('\n', ' ').trim()}")

    // ----------------------------------------------------------- conversation chat

    /**
     * Converses with an OpenAI-compatible (or Gemini) chat API over a conversation
     * history. Used by the voice-assistant feature so the model remembers earlier turns
     * in the session (PRD VOICE-1: "it can be from anywhere").
     *
     * [systemPrompt] may differ from page-summarization; [context] is optional
     * reader/screen context prepended as a user turn.
     */
    suspend fun chat(
        provider: String,
        baseUrl: String,
        apiKey: String,
        model: String,
        history: List<ChatMessage>,
        systemPrompt: String = SYSTEM_PROMPT,
        context: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val key = apiKey.trim()
            val effectiveModel = model.trim().ifBlank { defaultModel(provider) }
            val messages = buildList {
                if (!context.isNullOrBlank()) {
                    add(ChatMessage("user", "Current reading context:\n$context"))
                }
                addAll(history)
            }
            val text = when (provider.lowercase().trim()) {
                PROVIDER_GEMINI -> {
                    val request = buildGeminiChatRequest(key, effectiveModel, messages, systemPrompt)
                    parseGeminiChat(execute(request))
                }
                PROVIDER_GROQ, PROVIDER_CUSTOM, PROVIDER_OPENAI -> {
                    val base = normalizedBaseUrl(provider, baseUrl)
                    val request = buildChatRequest(base, key, effectiveModel, messages)
                    parseOpenAiChat(execute(request))
                }
                PROVIDER_ANTHROPIC -> {
                    // Anthropic needs its own transport, so the whole voice
                    // assistant was dead for this provider before.
                    val base = normalizedBaseUrl(provider, baseUrl)
                    val request = buildAnthropicChatRequest(
                        "$base/v1/messages", key, effectiveModel, messages, systemPrompt,
                    )
                    parseAnthropic(execute(request))
                }
                else -> throw IllegalArgumentException("Unsupported AI provider: $provider")
            }
            Result.success(text)
        } catch (e: HttpStatusException) {
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(IOException(NETWORK_ERROR, e))
        } catch (e: Exception) {
            Result.failure(Exception("Chat failed: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    private fun buildChatRequest(
        base: String, apiKey: String, model: String, messages: List<ChatMessage>
    ): Request {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                for (m in messages) put(JSONObject().put("role", m.role).put("content", m.content))
            })
            put("temperature", 0.7)
            put("max_tokens", 800)
        }
        return Request.Builder()
            .url("$base/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildGeminiChatRequest(
        apiKey: String, model: String, messages: List<ChatMessage>, systemPrompt: String
    ): Request {
        val contents = JSONArray()
        for (m in messages) {
            if (m.role == "system") continue
            val parts = JSONArray().put(JSONObject().put("text", m.content))
            contents.put(JSONObject()
                .put("role", if (m.role == "assistant") "model" else "user")
                .put("parts", parts))
        }
        val body = JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", 800))
            put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", systemPrompt))))
        }
        return Request.Builder()
            .url("$GEMINI_BASE/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun parseOpenAiChat(json: String): String =
        runCatching {
            JSONObject(json)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }.getOrElse {
            throw IllegalStateException("Unexpected chat response format from the provider")
        }.ifEmpty { throw IllegalStateException("The model returned an empty reply") }

    private fun parseGeminiChat(json: String): String =
        runCatching {
            buildString {
                val candidates = JSONObject(json).getJSONArray("candidates")
                for (i in 0 until candidates.length()) {
                    val parts = candidates.getJSONObject(i)
                        .getJSONObject("content").getJSONArray("parts")
                    for (j in 0 until parts.length()) {
                        val p = parts.getJSONObject(j)
                        if (!p.isNull("text")) append(p.optString("text"))
                    }
                }
            }.trim()
        }.getOrElse {
            throw IllegalStateException("Unexpected chat response format from Gemini")
        }.ifEmpty { throw IllegalStateException("Gemini returned an empty reply") }

    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_GROQ = "groq"
        const val PROVIDER_CUSTOM = "custom"
        const val PROVIDER_ANTHROPIC = "anthropic"

        private const val OPENAI_BASE = "https://api.openai.com/v1"
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val GROQ_BASE = "https://api.groq.com/openai/v1"
        private const val ANTHROPIC_BASE = "https://api.anthropic.com"

        private const val SYSTEM_PROMPT = "You are a concise reading assistant. Summarize book pages faithfully."
        private const val NETWORK_ERROR = "Network error: check your internet connection"

        private const val MAX_PAGE_TEXT_CHARS = 12_000
        private const val MAX_ERROR_BODY_CHARS = 200

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Model suggestion per provider; "" means the caller must supply their own. */
        fun defaultModel(provider: String): String = when (provider.lowercase().trim()) {
            PROVIDER_OPENAI -> "gpt-4o-mini"
            PROVIDER_GEMINI -> "gemini-1.5-flash"
            PROVIDER_GROQ -> "llama-3.3-70b-versatile"
            PROVIDER_ANTHROPIC -> "claude-3-5-haiku-latest"
            else -> ""
        }

        /**
         * Normalizes a user-entered server URL: trimmed, no trailing slash. Blank input
         * falls back to the public OpenAI root for provider "openai"; "gemini" always uses
         * Google's fixed endpoint. For "custom" the result may still be blank — callers
         * ([Summarizer.summarize]) turn that into a settings-nudge error.
         */
        fun normalizedBaseUrl(provider: String, baseUrl: String): String {
            val trimmed = baseUrl.trim().trimEnd('/')
            return when (provider.lowercase().trim()) {
                PROVIDER_OPENAI -> trimmed.ifBlank { OPENAI_BASE }
                PROVIDER_GEMINI -> GEMINI_BASE
                PROVIDER_GROQ -> trimmed.ifBlank { GROQ_BASE }
                PROVIDER_ANTHROPIC -> trimmed.ifBlank { ANTHROPIC_BASE }
                else -> trimmed
            }
        }
    }
}
