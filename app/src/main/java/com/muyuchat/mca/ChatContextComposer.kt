package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.localContextWindowBudget

data class ChatRuntimeContextPlan(
    val runtimeSystemContext: String = "",
    val worldBook: WorldBookSelection = WorldBookSelection(),
    val knowledge: KnowledgeRetrieval = KnowledgeRetrieval(),
    val tokenBudget: Int = 0
) {
    val hasContext: Boolean
        get() = runtimeSystemContext.isNotBlank()
}

/**
 * Keeps optional retrieval and lore under a bounded part of the actual loaded
 * context window. The plan stays request-scoped so it never inflates an
 * assistant's permanent system prompt or chat history.
 */
class ChatContextComposer(
    private val worldBookStore: WorldBookStore,
    private val knowledgeBaseStore: KnowledgeBaseStore
) {
    fun compose(
        messages: List<ChatMessage>,
        params: GenerationParams,
        assistantId: String,
        chatSessionId: String?,
        knowledgeBaseIds: Set<String>
    ): ChatRuntimeContextPlan {
        val promptBudget = localContextWindowBudget(params.nCtx).promptBudgetTokens
        if (promptBudget <= 0) return ChatRuntimeContextPlan()
        // Dynamic data is deliberately a minority of the usable window so it
        // cannot crowd out the latest user turn or normal conversation history.
        val dynamicBudget = (promptBudget / 5)
            .coerceAtLeast(1)
            .coerceAtMost(promptBudget)
        val loreBudget = dynamicBudget * 3 / 5
        val retrievalBudget = dynamicBudget - loreBudget
        val lore = WorldBookResolver.select(
            books = worldBookStore.load(),
            messages = messages,
            assistantId = assistantId,
            chatSessionId = chatSessionId,
            tokenBudget = loreBudget
        )
        val query = messages.asReversed()
            .firstOrNull { it.role == Role.USER && it.content.isNotBlank() }
            ?.content
            ?.takeLast(MAX_QUERY_CHARS)
            .orEmpty()
        val knowledge = knowledgeBaseStore.retrieve(
            knowledgeBaseIds = knowledgeBaseIds,
            query = query,
            maxChunks = MAX_KNOWLEDGE_CHUNKS,
            tokenBudget = retrievalBudget
        )
        val context = listOf(lore.context, knowledge.context)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return ChatRuntimeContextPlan(
            runtimeSystemContext = context,
            worldBook = lore,
            knowledge = knowledge,
            tokenBudget = dynamicBudget
        )
    }

    private companion object {
        const val MAX_QUERY_CHARS = 8_192
        const val MAX_KNOWLEDGE_CHUNKS = 4
    }
}
