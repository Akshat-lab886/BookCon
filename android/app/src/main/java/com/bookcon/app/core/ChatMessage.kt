package com.bookcon.app.core

/**
 * One turn in a voice-assistant conversation (PRD VOICE-1).
 *
 * Stored as a plain tuple so the history can be round-tripped through the
 * OpenAI / Gemini chat JSON without extra mapping. [role] is "system" |
 * "user" | "assistant"; [content] is the text of the turn.
 */
data class ChatMessage(
    val role: String,
    val content: String,
)
