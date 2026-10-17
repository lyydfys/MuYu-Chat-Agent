package com.muyuchat.core.engine

import org.json.JSONArray
import org.json.JSONObject

data class ChatRequest(
    val messages: List<ChatMessage>,
    val params: GenerationParams = GenerationParams(),
    /** Stable IDs whose original content must survive context admission. */
    val protectedMessageIds: Set<String> = emptySet(),
    /** Request-scoped authoritative context; never persisted into assistants or chat history. */
    val runtimeSystemContext: String = "",
    /**
     * Stable persona/system prefix for the role cache. Request-scoped
     * retrieval, web-search, and clock text are excluded from this field.
     * When [persistentSessionId] is present, the exact rendered conversation
     * state is additionally checkpointed under that session identity and is
     * accepted only after native token-prefix validation.
     */
    val persistentPrefixSystemPrompt: String? = null,
    /** Stable conversation identity for disk-backed full-session KV reuse. */
    val persistentSessionId: String? = null,
    /** Only structured-capable providers serialize this allowlisted tool schema. */
    val tools: List<ChatToolDefinition> = emptyList(),
    /** Tool transcript appended to this request using the provider's native message format. */
    val toolExchanges: List<ChatToolExchange> = emptyList(),
    /** Changes when edits, summary changes or explicit invalidation replace native history. */
    val conversationContextRevision: String? = null,
    /** Frozen role ownership for this request's disk-backed KV state. */
    val persistentRoleId: String? = null,
    /** Fingerprint of the role card snapshot used to render this request. */
    val persistentRoleCardVersion: String? = null,
    /** Fingerprint of the role memory and progress injected into this request. */
    val persistentMemoryVersion: String? = null
) {
    /**
     * Returns only the stable configured persona prefix. Request-scoped system
     * context (world books, retrieval, clock text, and caller additions) is
     * deliberately excluded so it can never be persisted in a prefix state.
     */
    fun fixedSystemPromptForPrefixCache(): String {
        val stablePrompt = persistentPrefixSystemPrompt?.trim().orEmpty()
        if (stablePrompt.isBlank()) return ""
        return listOf(stablePrompt, params.reasoningInstruction())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    fun messagesJson(
        multimodal: Boolean = false,
        contentEncoding: MultimodalContentEncoding = MultimodalContentEncoding.OPENAI_PARTS
    ): String {
        val array = JSONArray()
        val effectiveMessages = messagesWithSystemPrompt()
        effectiveMessages.forEach { message ->
            array.put(
                JSONObject()
                    .put("role", message.role.name.lowercase())
                    .put("id", message.id)
                    .put("content", message.toJsonContent(multimodal, contentEncoding))
                    .put("created_at", message.createdAt)
            )
        }
        return array.toString()
    }

    fun messagesWithSystemPrompt(): List<ChatMessage> = withSystemPrompt(messages)

    private fun ChatMessage.toJsonContent(
        multimodal: Boolean,
        contentEncoding: MultimodalContentEncoding
    ): Any {
        if (!multimodal || imageAttachments.isEmpty()) return content
        // A picker/API client can append the same image twice (for example as
        // both a content URI and a normalized file URI).  Native vision
        // runtimes interpret each part as a separate image, so collapse exact
        // duplicates before serializing the request while preserving order.
        val usableAttachments = imageAttachments
            .filter { it.hasInlineData || it.uriString.isNotBlank() }
            .deduplicateVisionAttachments()
        if (usableAttachments.isEmpty()) return content
        if (contentEncoding == MultimodalContentEncoding.MNN_IMAGE_TAGS_FIRST) {
            return buildString {
                usableAttachments.forEach { attachment ->
                    val source = if (attachment.hasInlineData) attachment.dataUrl() else attachment.uriString
                    append("<img>").append(source).append("</img>")
                }
                append(content)
            }
        }
        val parts = JSONArray()
        if (content.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", content))
        }
        usableAttachments.forEach { attachment ->
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject()
                            .put(
                                "url",
                                if (attachment.hasInlineData) attachment.dataUrl() else attachment.uriString
                            )
                            .put("detail", "auto")
                        )
            )
        }
        return parts
    }

    private fun withSystemPrompt(input: List<ChatMessage>): List<ChatMessage> {
        val systemParts = linkedSetOf<String>()
        fun addSystemPart(value: String) {
            value.trim().takeIf { it.isNotBlank() }?.let(systemParts::add)
        }

        val inputSystemMessages = input.filter { it.role == Role.SYSTEM }
        // An explicit system message from an API client is its character card
        // and therefore replaces the app's default persona, as it did before
        // role snapshots were introduced. Runtime context and the requested
        // reasoning instruction still apply to either form of system prompt.
        if (inputSystemMessages.isEmpty()) {
            addSystemPart(params.effectiveSystemPrompt())
        } else {
            inputSystemMessages.forEach { addSystemPart(it.content) }
            addSystemPart(params.reasoningInstruction())
        }
        addSystemPart(runtimeSystemContext)

        val nonSystemMessages = input.filter { it.role != Role.SYSTEM }
        if (systemParts.isEmpty()) return nonSystemMessages
        val systemCreatedAt = inputSystemMessages.firstOrNull()?.createdAt
            ?: System.currentTimeMillis()
        return listOf(
            ChatMessage(
                role = Role.SYSTEM,
                content = systemParts.joinToString("\n\n"),
                createdAt = systemCreatedAt
            )
        ) + nonSystemMessages
    }

    private fun GenerationParams.effectiveSystemPrompt(): String =
        listOf(systemPrompt, reasoningInstruction())
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    private fun GenerationParams.reasoningInstruction(): String = when (reasoningMode) {
        ReasoningMode.OFF -> "请直接回答，不展示思考过程。"
        ReasoningMode.STANDARD,
        ReasoningMode.ADVANCED -> ""
    }
}
