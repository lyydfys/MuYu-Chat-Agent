package com.muyuchat.feature.chat

/** Lowest chat context the dialog accepts; the engine's compact-window policy is guarded above it. */
internal const val MIN_CHAT_CONTEXT_LENGTH = 512

/** Highest chat context the dialog accepts (the engine's own ceiling). */
internal const val MAX_CHAT_CONTEXT_LENGTH = 1_048_576

/**
 * Validates the chat model's `n_ctx` input.
 *
 * Two rules matter for correctness: the value must fit the engine's supported range, and it must be
 * strictly larger than the requested output budget. A window equal to (or smaller than) the output
 * reservation leaves no prompt budget, which is exactly how a "valid" 8192/8192 pair produced
 * `SYSTEM_CONTEXT_TOO_LARGE` on a real DSH turn. Returns null when the value is acceptable.
 */
internal fun validateChatContextLength(value: String, nPredict: Int): String? {
    val parsed = value.trim().toIntOrNull()
        ?: return "上下文 n_ctx 请输入 $MIN_CHAT_CONTEXT_LENGTH 到 $MAX_CHAT_CONTEXT_LENGTH 之间的整数"
    if (parsed !in MIN_CHAT_CONTEXT_LENGTH..MAX_CHAT_CONTEXT_LENGTH) {
        return "上下文 n_ctx 请输入 $MIN_CHAT_CONTEXT_LENGTH 到 $MAX_CHAT_CONTEXT_LENGTH 之间的整数"
    }
    if (parsed <= nPredict) {
        return "上下文 n_ctx 必须大于最大输出 tokens（当前 $nPredict）"
    }
    return null
}
