package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.localContextWindowBudget
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ContextAssemblyTrace(
    val rolePromptHash: String,
    val selectedWorldBookSources: List<WorldBookSource>,
    val skippedWorldBookSources: List<WorldBookSource>,
    val selectedKnowledgeSources: List<KnowledgeSource>,
    val skippedKnowledgeSources: List<KnowledgeSource>,
    val tokenBudget: Int,
    val assembledHash: String
) {
    fun toJsonString(): String {
        // Trace storage has its own budget; it must never grow with every skipped source.
        val budget = TraceStorageBudget()
        val selectedWorld = worldBookJson(selectedWorldBookSources, budget)
        val selectedKnowledge = knowledgeJson(selectedKnowledgeSources, budget)
        val skippedWorld = worldBookJson(skippedWorldBookSources, budget)
        val skippedKnowledge = knowledgeJson(skippedKnowledgeSources, budget)
        return JSONObject()
            .put("schemaVersion", 1)
            .put("retrievalType", "lexical")
            .put("rolePromptHash", rolePromptHash.take(64))
            .put("tokenBudget", tokenBudget)
            .put("assembledHash", assembledHash.take(64))
            .put("selectedWorldBookSources", selectedWorld)
            .put("skippedWorldBookSources", skippedWorld)
            .put("selectedKnowledgeSources", selectedKnowledge)
            .put("skippedKnowledgeSources", skippedKnowledge)
            .put("omittedSourceCount", selectedWorldBookSources.size.toLong() + skippedWorldBookSources.size +
                selectedKnowledgeSources.size + skippedKnowledgeSources.size -
                selectedWorld.length() - skippedWorld.length() - selectedKnowledge.length() - skippedKnowledge.length())
            .toString()
    }

    private fun worldBookJson(sources: List<WorldBookSource>, budget: TraceStorageBudget): JSONArray = JSONArray().apply {
        sources.take(64).forEach { source ->
            if (source.bookId.length <= 512 && source.entryId.length <= 512) {
                budget.append(this, source.excerpt, JSONObject()
                    .put("bookId", source.bookId).put("entryId", source.entryId)
                    .put("scope", source.scope.wireName)
                    .put("estimatedTokens", source.estimatedTokens).put("reason", source.reason.take(256)))
            }
        }
    }

    private fun knowledgeJson(sources: List<KnowledgeSource>, budget: TraceStorageBudget): JSONArray = JSONArray().apply {
        sources.take(64).forEach { source ->
            if (source.knowledgeBaseId.length <= 512 && source.documentId.length <= 512 && source.chunkId.length <= 512) {
                budget.append(this, source.excerpt, JSONObject()
                    .put("knowledgeBaseId", source.knowledgeBaseId).put("documentId", source.documentId)
                    .put("chunkId", source.chunkId)
                    .put("lexicalScore", source.lexicalScore).put("estimatedTokens", source.estimatedTokens)
                    .put("reason", source.reason.take(256)))
            }
        }
    }

    private class TraceStorageBudget {
        private var remainingBytes = 126 * 1024

        fun append(array: JSONArray, excerpt: String, source: JSONObject) {
            val end = minOf(excerpt.length, 2048).let { end ->
                if (end < excerpt.length && end > 0 && excerpt[end - 1].isHighSurrogate()) end - 1 else end
            }
            source.put("excerpt", excerpt.substring(0, end))
                .put("excerptTruncated", end < excerpt.length)
                .put("excerptLength", excerpt.length)
                .put("excerptSha256", MessageDigest.getInstance("SHA-256")
                    .digest(excerpt.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) })
            val bytes = source.toString().toByteArray(Charsets.UTF_8).size + 1
            if (bytes <= remainingBytes) {
                array.put(source)
                remainingBytes -= bytes
            }
        }
    }
}

data class ChatRuntimeContextPlan(
    val runtimeSystemContext: String = "",
    val worldBook: WorldBookSelection = WorldBookSelection(),
    val knowledge: KnowledgeRetrieval = KnowledgeRetrieval(),
    val tokenBudget: Int = 0,
    val trace: ContextAssemblyTrace? = null
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
        knowledgeBaseIds: Set<String>,
        fileContextEnabled: Boolean = true
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
        val knowledge = if (fileContextEnabled) {
            val query = messages.asReversed()
                .firstOrNull { it.role == Role.USER && it.content.isNotBlank() }
                ?.content
                ?.let(::boundedQuery)
                .orEmpty()
            knowledgeBaseStore.retrieve(
                knowledgeBaseIds = knowledgeBaseIds,
                query = query,
                maxChunks = MAX_KNOWLEDGE_CHUNKS,
                tokenBudget = retrievalBudget
            )
        } else {
            // The role setting disables only document retrieval. World Book is
            // persona lore, so its scope and matching behavior stay unchanged.
            KnowledgeRetrieval()
        }
        val context = listOf(lore.context, knowledge.context)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return ChatRuntimeContextPlan(
            runtimeSystemContext = context,
            worldBook = lore,
            knowledge = knowledge,
            tokenBudget = dynamicBudget,
            trace = ContextAssemblyTrace(
                rolePromptHash = sha256(params.systemPrompt),
                selectedWorldBookSources = lore.selectedSources,
                skippedWorldBookSources = lore.skippedSources,
                selectedKnowledgeSources = knowledge.selectedSources,
                skippedKnowledgeSources = knowledge.skippedSources,
                tokenBudget = dynamicBudget,
                assembledHash = sha256(context)
            )
        )
    }

    private companion object {
        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        const val MAX_QUERY_CHARS = 8_192
        const val MAX_KNOWLEDGE_CHUNKS = 4

        fun boundedQuery(value: String): String {
            if (value.length <= MAX_QUERY_CHARS) return value
            val half = MAX_QUERY_CHARS / 2
            return value.take(half) + "\n…\n" + value.takeLast(half)
        }
    }
}
