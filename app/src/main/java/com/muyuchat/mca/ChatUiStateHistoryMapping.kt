package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.feature.chat.AssistantUiItem
import com.muyuchat.feature.chat.ChatHistoryItem
import com.muyuchat.feature.chat.ContextSummaryModelOption
import com.muyuchat.feature.chat.ContextSummaryUiItem
import com.muyuchat.feature.chat.KnowledgeBaseUiItem
import com.muyuchat.feature.chat.WorldBookUiItem

/** 由 ChatUiStateMapping.kt 平移的纯映射块（同包拆分，无行为变化）。 */

internal fun buildChatHistoryItems(
    state: MainUiState,
    sessionPresentations: Map<String, AssistantSessionPresentation>,
    timestampLocale: java.util.Locale
): List<ChatHistoryItem> =
    state.chatSessions.map { session ->
        val presentation = sessionPresentations[session.id]
        ChatHistoryItem(
            id = session.id,
            title = session.title,
            displayName = presentation?.name ?: session.title,
            summary = presentation?.summary.orEmpty(),
            assistantId = presentation?.assistantId,
            updatedAtText = java.text.SimpleDateFormat("MM-dd HH:mm", timestampLocale)
                .format(java.util.Date(session.updatedAt)),
            updatedAtMillis = session.updatedAt,
            messageCount = session.messages.size,
            pinned = session.pinned,
            selected = session.id == state.activeChatSessionId,
            mixedAssistantHistory = session.mixedAssistantHistory
        )
    }

internal fun buildAssistantUiItems(state: MainUiState): List<AssistantUiItem> =
    state.assistants.map { assistant ->
        val assistantParams = GenerationParams.fromJson(assistant.paramsJson, state.params)
        AssistantUiItem(
            id = assistant.id,
            name = assistant.name,
            avatar = assistant.avatar,
            tag = assistant.tag,
            systemPrompt = assistant.systemPrompt,
            modelSummary = when (assistant.defaultModelMode) {
                "local" -> state.models.firstOrNull { it.id == assistant.defaultModelId }?.displayName ?: "指定本地模型"
                "cloud" -> state.cloudModels.firstOrNull {
                    it.id == assistant.defaultModelId && it.kind == CloudModelKind.CHAT
                }?.modelName ?: "指定云端模型"
                else -> "跟随当前模型"
            },
            defaultModelMode = assistant.defaultModelMode,
            defaultModelId = assistant.defaultModelId,
            temperature = assistantParams.temperature,
            topP = assistantParams.topP,
            nPredict = assistantParams.nPredict,
            reasoningMode = assistantParams.reasoningMode,
            memoryEnabled = assistant.memoryEnabled,
            memorySummaryInterval = assistant.memorySummaryInterval,
            characterCardImported = !assistant.characterCardJson.isNullOrBlank(),
            webSearchEnabled = assistant.webSearchEnabled,
            fileContextEnabled = assistant.fileContextEnabled,
            selected = assistant.id == state.selectedAssistantId,
            exportJson = assistant.toJson().toString(2),
            topK = assistantParams.topK,
            minP = assistantParams.minP,
            repeatPenalty = assistantParams.repeatPenalty,
            presencePenalty = assistantParams.presencePenalty,
            frequencyPenalty = assistantParams.frequencyPenalty,
            stopWords = assistantParams.stopWords
        )
    }

internal fun buildWorldBookUiItems(state: MainUiState): List<WorldBookUiItem> =
    state.worldBooks
        .filter { book ->
            when (book.scope) {
                WorldBookScope.GLOBAL -> true
                WorldBookScope.ASSISTANT -> book.assistantId == state.selectedAssistantId
                WorldBookScope.CHAT -> book.chatSessionId == state.activeChatSessionId
            }
        }
        .map { book ->
            WorldBookUiItem(
                id = book.id,
                name = book.name,
                entryCount = book.entries.size,
                scopeLabel = when (book.scope) {
                    WorldBookScope.GLOBAL -> "全局"
                    WorldBookScope.ASSISTANT -> "当前角色"
                    WorldBookScope.CHAT -> "当前对话"
                },
                enabled = book.enabled,
                constantEntryCount = book.entries.count { it.enabled && it.constant },
                keywordEntryCount = book.entries.count { it.enabled && !it.constant }
            )
        }

internal fun buildKnowledgeBaseUiItems(state: MainUiState): List<KnowledgeBaseUiItem> =
    state.knowledgeBases.map { knowledgeBase ->
        KnowledgeBaseUiItem(
            id = knowledgeBase.id,
            name = knowledgeBase.name,
            description = knowledgeBase.description,
            selected = knowledgeBase.id in state.selectedKnowledgeBaseIds,
            indexStateLabel = when (knowledgeBase.indexState) {
                KnowledgeIndexState.LEXICAL_READY -> "本地检索已就绪"
                KnowledgeIndexState.EMBEDDING_PENDING -> "等待向量索引"
                KnowledgeIndexState.EMBEDDING_READY -> "向量索引已就绪"
                KnowledgeIndexState.REINDEX_REQUIRED -> "需要重建索引"
                KnowledgeIndexState.FAILED -> "索引失败"
            },
            documentCount = state.knowledgeDocumentCounts[knowledgeBase.id] ?: 0,
            importing = knowledgeBase.id in state.knowledgeBaseImportingIds
        )
    }

internal fun buildContextSummaryModelOptions(state: MainUiState): List<ContextSummaryModelOption> =
    buildList {
        add(ContextSummaryModelOption("current", "当前聊天模型"))
        add(ContextSummaryModelOption("deterministic", "规则整理"))
        add(ContextSummaryModelOption("local", "已加载的本地模型"))
        state.cloudModels.filter { it.kind == CloudModelKind.CHAT && it.configured }.forEach { model ->
            add(ContextSummaryModelOption("cloud:${model.id}", "云端 · ${model.displayName}"))
        }
    }

internal fun buildContextSummaryHistoryItems(state: MainUiState): List<ContextSummaryUiItem> =
    state.chatSessions
        .firstOrNull { it.id == state.activeChatSessionId }
        ?.contextSummaries.orEmpty().map { summary ->
            ContextSummaryUiItem(
                version = summary.version,
                source = summary.source,
                text = summary.text,
                sourceMessageIds = summary.sourceMessageIds,
                active = summary.active,
                createdAt = summary.createdAt,
                coverageLimited = summary.structuredSummary.coverageLimited,
                evidenceCount = summary.structuredSummary.evidence.size,
                omittedExcerptCount = summary.structuredSummary.omittedExcerptCount,
                omittedCharacterCount = summary.structuredSummary.omittedCharacterCount,
                modelIdentity = summary.summaryModelIdentity,
                diagnostic = summary.summaryDiagnostic
            )
        }

internal fun canUndoContextSummaryFor(state: MainUiState): Boolean =
    state.chatSessions
        .firstOrNull { it.id == state.activeChatSessionId }
        ?.contextSummaries?.any { it.active } == true
