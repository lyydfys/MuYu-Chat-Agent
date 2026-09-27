package com.muyuchat.mca

import com.muyuchat.core.engine.ChatRequest
import org.json.JSONArray
import org.json.JSONObject

internal data class CloudTokenUsage(val input: Int? = null, val output: Int? = null) {
    // Stream usage is cumulative. A later output-only event must not erase input usage.
    fun merge(newer: CloudTokenUsage): CloudTokenUsage = CloudTokenUsage(
        input = newer.input ?: input,
        output = newer.output ?: output
    )

    companion object {
        fun parse(json: JSONObject?, anthropic: Boolean = false): CloudTokenUsage {
            json ?: return CloudTokenUsage()
            fun count(key: String): Int? = json.optLong(key, -1L)
                .takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
            val input = if (anthropic) count("input_tokens")?.let { uncached ->
                // Anthropic separates cache reads/writes from uncached input_tokens.
                (uncached.toLong() + (count("cache_read_input_tokens") ?: 0) +
                    (count("cache_creation_input_tokens") ?: 0))
                    .takeIf { it <= Int.MAX_VALUE }?.toInt()
            } else count("prompt_tokens")
            return CloudTokenUsage(input, count(if (anthropic) "output_tokens" else "completion_tokens"))
        }
    }
}

internal fun estimateCloudTokens(characters: Int): Int =
    (characters / 4).coerceAtLeast(if (characters > 0) 1 else 0)

internal fun estimateCloudPromptTokens(request: ChatRequest): Int {
    val rendered = JSONArray(request.messagesJson())
    val characters = (0 until rendered.length()).sumOf {
        rendered.getJSONObject(it).optString("content").length.toLong()
    }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return estimateCloudTokens(characters)
}
