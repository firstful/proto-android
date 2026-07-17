package com.reedsutliff.talaria.model

/**
 * Represents a Hermes session from any platform (CLI, Telegram, Discord, etc.).
 * Used for the "Recent Sessions" feature that lets users fork and continue
 * conversations from other channels.
 */
data class HermesSession(
    val id: String,
    val title: String = "",
    val source: String = "",
    val startedAt: String = "",
    val endedAt: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val model: String = "",
) {
    val displaySource: String
        get() = when (source) {
            "cli" -> "CLI"
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "api-server" -> "API"
            "talaria" -> "Talaria"
            else -> source.replaceFirstChar { it.uppercase() }
        }

    val sourceEmoji: String
        get() = when (source) {
            "cli" -> "\uD83D\uDCBB"
            "telegram" -> "\u2708\uFE0F"
            "discord" -> "\uD83D\uDCAC"
            "slack" -> "\uD83D\uDCE2"
            "api-server" -> "\uD83D\uDD0C"
            "talaria" -> "\uD83C\uDFA4"
            else -> "\uD83D\uDCDD"
        }

    companion object {
        fun fromJson(json: org.json.JSONObject): HermesSession {
            return HermesSession(
                id = json.optString("id", json.optString("session_id", "")),
                title = json.optString("title", ""),
                source = json.optString("source", ""),
                startedAt = json.optString("started_at", ""),
                endedAt = json.optString("ended_at", null),
                inputTokens = json.optInt("input_tokens", 0),
                outputTokens = json.optInt("output_tokens", 0),
                model = json.optString("model", ""),
            )
        }
    }
}
