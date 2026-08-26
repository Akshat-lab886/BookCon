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

    companion object {
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_GEMINI = "gemini"
        const val PROVIDER_CUSTOM = "custom"

        private const val OPENAI_BASE = "https://api.openai.com/v1"
        private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"

        private const val SYSTEM_PROMPT = "You are a concise reading assistant. Summarize book pages faithfully."
        private const val NETWORK_ERROR = "Network error: check your internet connection"

        private const val MAX_PAGE_TEXT_CHARS = 12_000
        private const val MAX_ERROR_BODY_CHARS = 200

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Model suggestion per provider; "" means the caller must supply their own. */
        fun defaultModel(provider: String): String = when (provider.lowercase().trim()) {
            PROVIDER_OPENAI -> "gpt-4o-mini"
            PROVIDER_GEMINI -> "gemini-1.5-flash"
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
                else -> trimmed
            }
        }
    }
}
