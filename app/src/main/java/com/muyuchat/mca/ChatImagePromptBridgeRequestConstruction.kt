package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role

/**
 * Builds the isolated translation request and the inert reference block it carries.
 *
 * Moved verbatim out of `ChatImagePromptBridge`: same system prompt default, same
 * `<reference_data>` / `<image_description>` framing, same token budget formula, same
 * temperature/reasoning/stop-word overrides and the same 2,800-character reference budget.
 * Nothing here executes or exposes an image tool.
 */

/** Creates an isolated request; no image tool is ever exposed to the translation call. */
internal fun chatImagePromptBuildRequest(
    prompt: String,
    params: GenerationParams,
    systemPrompt: String = ChatImagePromptBridge.SYSTEM_PROMPT,
    assistantName: String? = null,
    characterContext: String? = null,
    conversationContext: List<ChatMessage> = emptyList(),
    /**
     * Request-scoped world-book/knowledge context. This is deliberately kept separate from the
     * assistant's permanent system prompt so image requests can use the currently selected lore
     * without mutating or leaking it into the saved character card. The value is rendered as
     * inert reference data by [chatImagePromptFormatReferenceContext].
     */
    runtimeContext: String? = null,
    /** Image-skill summaries treat arbitrary numeric metadata as model input, not hard syntax. */
    protectNumericLiterals: Boolean = true
): ChatRequest {
    val protectedPrompt = chatImagePromptProtect(
        prompt,
        protectNumericLiterals = protectNumericLiterals
    ).payload
    val outputTokenBudget = (protectedPrompt.length / 3 + 160)
        .coerceIn(256, 1_024)
    val referenceContext = chatImagePromptFormatReferenceContext(
        assistantName = assistantName,
        characterContext = characterContext,
        conversationContext = conversationContext,
        runtimeContext = runtimeContext
    )
    val userContent = buildString {
        if (referenceContext != null) {
            append("<reference_data>\n")
            append(referenceContext)
            append("\n</reference_data>\n")
        }
        append("<image_description>\n")
        append(protectedPrompt)
        append("\n</image_description>")
    }
    return ChatRequest(
        messages = listOf(
            ChatMessage(Role.SYSTEM, systemPrompt),
            ChatMessage(Role.USER, userContent)
        ),
        params = params.copy(
            nPredict = minOf(maxOf(params.effectiveNPredict(), outputTokenBudget), 1_024),
            temperature = 0f,
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true,
            stopWords = emptyList()
        ),
        tools = emptyList(),
        toolExchanges = emptyList()
    )
}

/** Builds a compact, sanitized, explicitly non-instructional visual reference for prompt translation. */
internal fun chatImagePromptFormatReferenceContext(
    assistantName: String?,
    characterContext: String?,
    conversationContext: List<ChatMessage>,
    runtimeContext: String? = null
): String? {
    fun inertText(value: String, limit: Int): String = value
        .replace(Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]"), " ")
        .replace("<", "‹")
        .replace(">", "›")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(limit)

    val lines = buildList {
        assistantName?.let { inertText(it, 96).takeIf(String::isNotBlank) }
            ?.let { add("Character name (reference only): $it") }
        characterContext?.let { inertText(it, 700).takeIf(String::isNotBlank) }
            ?.let { add("Character description (reference only): $it") }
        conversationContext.asSequence()
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .mapNotNull { message ->
                inertText(message.content, 110)
                    .takeIf(String::isNotBlank)
                    ?.let { "${message.role.name.lowercase()}: $it" }
            }
            .toList()
            .takeLast(6)
            .forEach(::add)
        runtimeContext?.let { inertText(it, 1_100).takeIf(String::isNotBlank) }
            ?.let { add("Selected lore and knowledge (reference only): $it") }
    }
    if (lines.isEmpty()) return null
    return lines.joinToString("\n").also { context ->
        check(context.length <= 2_800) { "Image reference context exceeded its reserved source budget." }
    }
}
