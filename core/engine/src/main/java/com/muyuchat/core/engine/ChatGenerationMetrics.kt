package com.muyuchat.core.engine

import org.json.JSONObject

/** A small, immutable per-reply snapshot. Never sent back as model input. */
data class ChatGenerationMetrics(
    val promptTokens: Int?,
    val completionTokens: Int?,
    val tokensPerSecond: Double?,
    val elapsedMs: Long,
    val promptTokensEstimated: Boolean = false,
    val completionTokensEstimated: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("promptTokens", promptTokens?.takeIf { it >= 0 })
        .put("completionTokens", completionTokens?.takeIf { it >= 0 })
        .put("tokensPerSecond", tokensPerSecond?.takeIf { it.isFinite() && it >= 0.0 })
        .put("elapsedMs", elapsedMs.coerceAtLeast(0L))
        .put("promptTokensEstimated", promptTokensEstimated)
        .put("completionTokensEstimated", completionTokensEstimated)

    companion object {
        fun fromJson(json: JSONObject?): ChatGenerationMetrics? {
            json ?: return null
            val elapsed = json.optLong("elapsedMs", -1L).takeIf { it >= 0L } ?: return null
            fun count(key: String): Int? = json.optLong(key, -1L)
                .takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt()
            return ChatGenerationMetrics(
                promptTokens = count("promptTokens"),
                completionTokens = count("completionTokens"),
                tokensPerSecond = json.optDouble("tokensPerSecond", Double.NaN)
                    .takeIf { it.isFinite() && it >= 0.0 },
                elapsedMs = elapsed,
                promptTokensEstimated = json.optBoolean("promptTokensEstimated", false),
                completionTokensEstimated = json.optBoolean("completionTokensEstimated", false)
            )
        }
    }
}

fun RuntimeStats.toChatGenerationMetrics(elapsedMs: Long): ChatGenerationMetrics = ChatGenerationMetrics(
    promptTokens = promptTokens.takeIf { it >= 0 },
    completionTokens = completionTokens.takeIf { it >= 0 },
    // A remote server may buffer the whole answer into one chunk. Only the
    // request-wide rate is comparable in that case; chunk arrival is not decode timing.
    tokensPerSecond = if (backend == "cloud") {
        e2eTps.takeIf { it.isFinite() && it > 0.0 }
    } else {
        decodeTps.takeIf { it.isFinite() && it > 0.0 }
            ?: e2eTps.takeIf { it.isFinite() && it > 0.0 }
    },
    elapsedMs = elapsedMs.coerceAtLeast(0L),
    promptTokensEstimated = promptTokensEstimated,
    completionTokensEstimated = completionTokensEstimated
)
