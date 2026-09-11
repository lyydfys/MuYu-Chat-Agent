package com.muyuchat.feature.chat

/**
 * Prompt-token information for the selected model.
 *
 * [overflowOffset] is a UTF-16 offset into the original prompt. It is nullable
 * because some exact tokenizer backends do not expose token-to-source offsets.
 * When [exact] is false, [count] is a conservative estimate and must be labeled
 * as such; estimated measurements can never publish an overflow offset.
 */
data class ImagePromptTokenMeasurement(
    val count: Int,
    val maxTokens: Int,
    val overflowOffset: Int? = null,
    val exact: Boolean = true,
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
        require(exact || overflowOffset == null) {
            "An estimated prompt count cannot publish an exact overflow offset."
        }
    }

    val overflows: Boolean
        get() = count > maxTokens
}
