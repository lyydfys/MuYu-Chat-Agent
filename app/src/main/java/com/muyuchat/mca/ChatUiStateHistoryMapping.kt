package com.muyuchat.mca

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.feature.chat.AssistantUiItem
import com.muyuchat.feature.chat.ChatHistoryItem
import com.muyuchat.feature.chat.ContextSummaryModelOption
import com.muyuchat.feature.chat.ContextSummaryUiItem
import com.muyuchat.feature.chat.KnowledgeBaseUiItem
import com.muyuchat.feature.chat.WorldBookUiItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 由 ChatUiStateMapping.kt 平移的纯映射块（同包拆分，无行为变化）。
 *
 * 历史行只缓存纯展示结果，不持有业务状态。缓存键包含所有会改变该行的显式输入；
 * 原始 updatedAt 即使格式化文本未变也参与键，避免伪造命中。每次投影都会裁掉当前
 * 会话快照中已不存在的 id，因此删除会话会释放对应缓存，缓存总量不超过当前历史行数。
 */
internal class ChatHistoryRowProjector {
    private val rowsBySessionId = HashMap<String, CachedChatHistoryRow>()

    @Synchronized
    fun project(
        chatSessions: List<ChatSessionRecord>,
        activeChatSessionId: String?,
        sessionPresentations: Map<String, AssistantSessionPresentation>,
        timestampLocale: Locale,
        timestampTimeZone: TimeZone
    ): List<ChatHistoryItem> {
        val updatedAtFormatter = SimpleDateFormat("MM-dd HH:mm", timestampLocale).apply {
            timeZone = timestampTimeZone.clone() as TimeZone
        }
        val timeZoneSnapshot = timestampTimeZone.snapshot()
        val items = chatSessions.map { session ->
            val presentation = sessionPresentations[session.id]
            val selected = session.id == activeChatSessionId
            val dependencies = ChatHistoryRowDependencies(
                title = session.title,
                displayName = presentation?.name ?: session.title,
                summary = presentation?.summary.orEmpty(),
                assistantId = presentation?.assistantId,
                updatedAt = session.updatedAt,
                timestampLocale = timestampLocale,
                timestampTimeZone = timeZoneSnapshot.copy(
                    offsetAtUpdatedAt = timestampTimeZone.getOffset(session.updatedAt)
                ),
                messageCount = session.messages.size,
                pinned = session.pinned,
                manualTitle = session.manualTitle,
                selected = selected,
                mixedAssistantHistory = session.mixedAssistantHistory
            )
            val cached = rowsBySessionId[session.id]
            if (cached?.dependencies == dependencies) {
                cached.item
            } else {
                val item = ChatHistoryItem(
                    id = session.id,
                    title = session.title,
                    displayName = dependencies.displayName,
                    summary = dependencies.summary,
                    assistantId = dependencies.assistantId,
                    updatedAtText = updatedAtFormatter.format(Date(session.updatedAt)),
                    updatedAtMillis = session.updatedAt,
                    messageCount = dependencies.messageCount,
                    pinned = session.pinned,
                    selected = selected,
                    mixedAssistantHistory = session.mixedAssistantHistory
                )
                rowsBySessionId[session.id] = CachedChatHistoryRow(dependencies, item)
                item
            }
        }
        val currentSessionIds = chatSessions.mapTo(HashSet(chatSessions.size)) { it.id }
        rowsBySessionId.keys.retainAll(currentSessionIds)
        return items
    }
}

private val sharedChatHistoryRowProjector = ChatHistoryRowProjector()

internal fun buildChatHistoryItems(
    state: MainUiState,
    sessionPresentations: Map<String, AssistantSessionPresentation>,
    timestampLocale: Locale
): List<ChatHistoryItem> = projectChatHistoryItems(
    state = state,
    sessionPresentations = sessionPresentations,
    timestampLocale = timestampLocale,
    timestampTimeZone = TimeZone.getDefault(),
    rowProjector = sharedChatHistoryRowProjector
)

internal fun projectChatHistoryItems(
    state: MainUiState,
    sessionPresentations: Map<String, AssistantSessionPresentation>,
    timestampLocale: Locale,
    timestampTimeZone: TimeZone,
    rowProjector: ChatHistoryRowProjector
): List<ChatHistoryItem> = rowProjector.project(
    chatSessions = state.chatSessions,
    activeChatSessionId = state.activeChatSessionId,
    sessionPresentations = sessionPresentations,
    timestampLocale = timestampLocale,
    timestampTimeZone = timestampTimeZone
)

private data class ChatHistoryRowDependencies(
    val title: String,
    val displayName: String,
    val summary: String,
    val assistantId: String?,
    val updatedAt: Long,
    val timestampLocale: Locale,
    val timestampTimeZone: ChatHistoryTimeZoneSnapshot,
    val messageCount: Int,
    val pinned: Boolean,
    val manualTitle: Boolean,
    val selected: Boolean,
    val mixedAssistantHistory: Boolean
)

private data class ChatHistoryTimeZoneSnapshot(
    val id: String,
    val rawOffset: Int,
    val dstSavings: Int,
    val usesDaylightTime: Boolean,
    val offsetAtUpdatedAt: Int = 0
)

private fun TimeZone.snapshot(): ChatHistoryTimeZoneSnapshot = ChatHistoryTimeZoneSnapshot(
    id = id,
    rawOffset = rawOffset,
    dstSavings = dstSavings,
    usesDaylightTime = useDaylightTime()
)

private data class CachedChatHistoryRow(
    val dependencies: ChatHistoryRowDependencies,
    val item: ChatHistoryItem
)

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
