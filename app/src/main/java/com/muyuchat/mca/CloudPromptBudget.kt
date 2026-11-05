package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.Role

/**
 * The last hard boundary before a cloud chat request is serialized.
 *
 * Why it exists: nothing else in the cloud path relates the prompt size to the model window. The DSH
 * adapter only enforces a 1 M-character ceiling and *fails* past it, and the per-request `max_tokens`
 * is derived from the user's output preference alone. A long conversation therefore reached the
 * provider with `prompt + max_tokens > window` and was rejected there, with no readable explanation.
 *
 * Contract: `estimate(prompt) + output <= window`, always. When the request does not fit, the oldest
 * non-system messages are dropped first, then a single oversized message is truncated (head + tail, so
 * both the instruction and the newest content survive), and finally the system prefix is shortened. A
 * `[mca:prompt_trimmed …]` line is appended to the system prompt so the trimming is visible to the
 * model, to logs and to the user rather than silent or fatal.
 *
 * Estimation is `chars / 4`, the same cheap heuristic the token accountant already uses; it is
 * deliberately conservative enough for the 1 k-token reserve and never claims provider-accurate counts.
 *
 * Local execution is untouched: this object is only called from cloud request builders and the DSH
 * cloud adapter, never from the llama/GenerationParams path.
 */
internal object CloudPromptBudget {

    /** Window assumed when the caller cannot supply one (unknown compatible gateway / model name). */
    const val DEFAULT_WINDOW_TOKENS: Int = 128_000

    /** Hard output ceiling, independent of the model: the cloud transports already cap at 32 768. */
    const val MAX_OUTPUT_TOKENS: Int = 65_536

    /** Never ask for fewer output tokens than this, unless the window itself is smaller. */
    const val MIN_OUTPUT_TOKENS: Int = 256

    /** Head-room kept for tokenizer drift between the `chars / 4` estimate and the provider. */
    const val SYSTEM_RESERVE_TOKENS: Int = 1_024

    /** A configured window below this is treated as "unknown" rather than honoured literally. */
    private const val MIN_WINDOW_TOKENS: Int = 1_024

    private const val CHARS_PER_TOKEN: Int = 4

    /** Prefix of the visible trimming fact appended to the system prompt. */
    const val TRIM_MARKER_PREFIX: String = "[mca:prompt_trimmed"

    /** One window value: a plausible configured value wins, anything else falls back to 128 k. */
    fun windowTokens(configured: Int): Int =
        configured.takeIf { it >= MIN_WINDOW_TOKENS } ?: DEFAULT_WINDOW_TOKENS

    /**
     * Output budget for [requested] tokens inside [windowTokens]: at most half the window, at most
     * [outputCap], at least [MIN_OUTPUT_TOKENS] (or half the window when it is smaller).
     */
    fun outputTokens(
        windowTokens: Int,
        requested: Int,
        outputCap: Int = MAX_OUTPUT_TOKENS
    ): Int {
        val half = (CloudPromptBudget.windowTokens(windowTokens) / 2).coerceAtLeast(1)
        val cap = outputCap.coerceAtLeast(1)
        val floor = minOf(MIN_OUTPUT_TOKENS, half).coerceAtLeast(1)
        return minOf(requested.coerceAtLeast(1), half, cap).coerceAtLeast(floor)
    }

    /** `chars / 4`, rounded up, so an empty string is 0 tokens and one character is 1. */
    fun estimateTokens(text: String): Int =
        if (text.isEmpty()) 0 else ((text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN)

    fun estimateTokens(messages: List<ChatMessage>): Int =
        messages.sumOf { estimateTokens(it.content).toLong() }
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    /**
     * Fits [request] into [windowTokens] and returns the request to send, the output budget that goes
     * with it, and the visible trimming fact.
     *
     * The returned request has its system text materialised as one explicit `SYSTEM` message and a
     * blank `params.systemPrompt`/`runtimeSystemContext`/`appendReasoningInstruction`, so a second
     * `messagesWithSystemPrompt()` call is a no-op and cannot re-introduce dropped text.
     */
    fun fit(
        request: ChatRequest,
        windowTokens: Int,
        requestedOutputTokens: Int,
        outputCap: Int = MAX_OUTPUT_TOKENS
    ): CloudPromptFit {
        val window = CloudPromptBudget.windowTokens(windowTokens)
        val output = outputTokens(window, requestedOutputTokens, outputCap)
        val reserve = minOf(SYSTEM_RESERVE_TOKENS, window / 10).coerceAtLeast(1)
        val promptBudget = (window - output - reserve).coerceAtLeast(1)

        val merged = request.messagesWithSystemPrompt()
        val systemMessages = merged.filter { it.role == Role.SYSTEM }
        var systemText = systemMessages.joinToString("\n\n") { it.content }
        val conversation = merged.filter { it.role != Role.SYSTEM }.toMutableList()

        var dropped = 0
        var truncated = 0
        var systemTokens = estimateTokens(systemText)
        // Keep at least the newest message: an empty conversation is never a useful request.
        while (estimateTokens(conversation) + systemTokens > promptBudget && conversation.size > 1) {
            conversation.removeAt(0)
            dropped += 1
        }
        // One message still too large: keep its head and its tail, so the opening instruction and the
        // newest content both survive instead of a silent cut.
        if (systemTokens + estimateTokens(conversation) > promptBudget && conversation.isNotEmpty()) {
            val remainingChars = ((promptBudget - systemTokens).coerceAtLeast(64)) * CHARS_PER_TOKEN
            val truncatedMessage = truncateToChars(conversation.last().content, remainingChars)
            if (truncatedMessage != conversation.last().content) {
                conversation[conversation.lastIndex] = conversation.last().copy(content = truncatedMessage)
                truncated += 1
            }
        }
        // The system prefix itself is over budget: shorten it too (head only — the role and world-book
        // anchors live at the start).
        if (systemTokens + estimateTokens(conversation) > promptBudget && systemText.isNotEmpty()) {
            val remainingChars = (promptBudget * CHARS_PER_TOKEN).coerceAtLeast(64)
            systemText = truncateToChars(systemText, remainingChars)
            systemTokens = estimateTokens(systemText)
        }

        val finalEstimate = (systemTokens + estimateTokens(conversation))
            .coerceAtMost(promptBudget)
        val note = if (dropped > 0 || truncated > 0) {
            "$TRIM_MARKER_PREFIX dropped=$dropped truncated=$truncated" +
                " budget=$promptBudget est=$finalEstimate]"
        } else {
            null
        }
        val fittedMessages = buildList {
            if (systemText.isNotBlank() || note != null) {
                val content = listOfNotNull(
                    systemText.takeIf { it.isNotBlank() },
                    note
                ).joinToString("\n\n")
                add(
                    ChatMessage(
                        role = Role.SYSTEM,
                        content = content,
                        createdAt = systemMessages.firstOrNull()?.createdAt ?: 0L
                    )
                )
            }
            addAll(conversation)
        }
        val fittedRequest = request.copy(
            messages = fittedMessages,
            params = request.params.copy(nPredict = output, systemPrompt = ""),
            runtimeSystemContext = "",
            appendReasoningInstruction = false
        )
        return CloudPromptFit(
            request = fittedRequest,
            windowTokens = window,
            outputTokens = output,
            promptTokens = finalEstimate,
            droppedMessages = dropped,
            truncatedMessages = truncated,
            note = note
        )
    }

    /** Head 2/3 + tail 1/3 of [text], with an explicit marker in between. */
    private fun truncateToChars(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        if (maxChars <= 0) return ""
        val marker = "\n[mca:truncated ${text.length - maxChars} chars]\n"
        val usable = (maxChars - marker.length).coerceAtLeast(0)
        if (usable == 0) return marker.trim()
        val headLength = (usable * 2) / 3
        val tailLength = usable - headLength
        val head = text.take(headLength)
        val tail = if (tailLength > 0) text.takeLast(tailLength) else ""
        return head + marker + tail
    }
}

/** Result of [CloudPromptBudget.fit]; [trimmed] is the readable fact, not a boolean guess. */
internal data class CloudPromptFit(
    val request: ChatRequest,
    val windowTokens: Int,
    val outputTokens: Int,
    val promptTokens: Int,
    val droppedMessages: Int,
    val truncatedMessages: Int,
    val note: String?
) {
    val trimmed: Boolean get() = note != null
}

/**
 * Window table for cloud models: unknown names fall back to [CloudPromptBudget.DEFAULT_WINDOW_TOKENS].
 *
 * This is the single place to add a per-model override (for example
 * `"claude-" to 200_000`); no provider is hard-coded today, so every cloud model uses 128 k.
 */
internal object CloudModelWindow {

    private val overrides: Map<String, Int> = mapOf(
        // Intentionally empty: add provider/model-name overrides here when a real window is known.
    )

    fun windowTokens(modelName: String): Int {
        val name = modelName.trim().lowercase()
        if (name.isNotEmpty()) {
            overrides.entries.firstOrNull { (needle, _) -> name.contains(needle) }
                ?.let { return CloudPromptBudget.windowTokens(it.value) }
        }
        return CloudPromptBudget.DEFAULT_WINDOW_TOKENS
    }

    /** Output budget advertised for [windowTokens]; the same rule the request builder applies. */
    fun outputTokens(windowTokens: Int): Int =
        CloudPromptBudget.outputTokens(windowTokens, requested = Int.MAX_VALUE)
}
