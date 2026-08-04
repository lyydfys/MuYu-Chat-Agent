package com.muyuchat.feature.chat

/**
 * Exact prompt-token information returned by the model's own tokenizer.
 *
 * [overflowOffset] is a UTF-16 offset into the original prompt. It is nullable
 * because some tokenizer backends can report the full count but do not expose
 * token-to-source offsets. A missing offset must never be replaced with a
 * character-count estimate by the UI.
 */
data class ImagePromptTokenMeasurement(
    val count: Int,
    val maxTokens: Int,
    val overflowOffset: Int? = null,
) {
    init {
        require(count >= 0) { "Prompt token count must be non-negative." }
        require(maxTokens > 0) { "Prompt token limit must be positive." }
        require(overflowOffset == null || overflowOffset >= 0) {
            "Prompt overflow offset must be non-negative."
        }
        require(count > maxTokens || overflowOffset == null) {
            "A fitting prompt cannot publish an overflow offset."
        }
    }

    val overflows: Boolean
        get() = count > maxTokens
}
