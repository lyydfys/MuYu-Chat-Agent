package com.muyuchat.core.engine

/**
 * Logical context budget shared by the production engine and authenticated
 * Local API. The requested n_ctx is never silently raised: a 128/256 context
 * is guarded against that exact native window, while the established >=512
 * policy keeps its existing safety margins.
 */
data class ContextWindowBudget(
    val contextLength: Int,
    val reservedOutputTokens: Int,
    val headroomTokens: Int,
    val minimumPromptBudgetTokens: Int
) {
    val promptBudgetTokens: Int
        get() = contextLength - reservedOutputTokens - headroomTokens
}

fun localContextWindowBudget(requestedContextLength: Int): ContextWindowBudget {
    val contextLength = requestedContextLength.coerceAtLeast(1)
    return if (contextLength >= STANDARD_CONTEXT_THRESHOLD) {
        ContextWindowBudget(
            contextLength = contextLength,
            reservedOutputTokens = (contextLength / 8)
                .coerceIn(STANDARD_MIN_RESERVED_OUTPUT_TOKENS, MAX_RESERVED_OUTPUT_TOKENS),
            headroomTokens = STANDARD_HEADROOM_TOKENS,
            minimumPromptBudgetTokens = STANDARD_MIN_PROMPT_BUDGET_TOKENS
        )
    } else {
        val reservedOutputTokens = (contextLength / 8).coerceAtLeast(COMPACT_MIN_MARGIN_TOKENS)
        val defaultHeadroomTokens = (contextLength / 16).coerceAtLeast(COMPACT_MIN_MARGIN_TOKENS)
        // Keep compact windows exact without making the usable prompt budget
        // fall when the user raises n_ctx from 511 to 512. Once the compact
        // formula reaches the established 512-token prompt budget, extra
        // tokens become headroom until the standard policy takes over.
        val uncappedPromptBudgetTokens = if (contextLength >= COMPACT_PROPORTIONAL_BUDGET_START) {
            contextLength * COMPACT_PROMPT_BUDGET_NUMERATOR / COMPACT_PROMPT_BUDGET_DENOMINATOR
        } else {
            contextLength - reservedOutputTokens - defaultHeadroomTokens
        }
        val promptBudgetTokens = uncappedPromptBudgetTokens
            .coerceAtMost(STANDARD_BOUNDARY_PROMPT_BUDGET_TOKENS)
        ContextWindowBudget(
            contextLength = contextLength,
            reservedOutputTokens = reservedOutputTokens,
            headroomTokens = contextLength - reservedOutputTokens - promptBudgetTokens,
            minimumPromptBudgetTokens = (contextLength / 4).coerceAtLeast(COMPACT_MIN_PROMPT_BUDGET_TOKENS)
        )
    }
}

private const val STANDARD_CONTEXT_THRESHOLD = 512
private const val STANDARD_HEADROOM_TOKENS = 96
private const val STANDARD_MIN_RESERVED_OUTPUT_TOKENS = 64
private const val STANDARD_MIN_PROMPT_BUDGET_TOKENS = 256
private const val MAX_RESERVED_OUTPUT_TOKENS = 1024
private const val STANDARD_BOUNDARY_PROMPT_BUDGET_TOKENS =
    STANDARD_CONTEXT_THRESHOLD - STANDARD_MIN_RESERVED_OUTPUT_TOKENS - STANDARD_HEADROOM_TOKENS
private const val COMPACT_PROPORTIONAL_BUDGET_START = 128
private const val COMPACT_PROMPT_BUDGET_NUMERATOR = 13
private const val COMPACT_PROMPT_BUDGET_DENOMINATOR = 16
private const val COMPACT_MIN_MARGIN_TOKENS = 8
private const val COMPACT_MIN_PROMPT_BUDGET_TOKENS = 16
