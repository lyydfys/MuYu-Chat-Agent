package com.muyuchat.mca

import android.content.SharedPreferences
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextSummarizationService
import com.muyuchat.core.engine.ContextSummaryModel
import com.muyuchat.core.engine.ContextSummarySource
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.estimateLocalPromptTokens
import com.muyuchat.feature.chat.ContextSummaryEvidenceUiItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.UUID

internal class ContextCompressionCoordinator(
    private val store: UiStateWriter<ContextCompressionFields>,
    private val engine: McaInferenceService,
    private val cloudChatProvider: OpenAiCompatibleChatProvider,
    private val modelGenerationProfileStore: ModelGenerationProfileStore,
    private val hasActiveImageRequest: () -> Boolean,
    private val manualContextCompressionRequests: ContextCompressionRequestStore,
    private val preferences: SharedPreferences,
    private val chatRuns: ChatRunOwnership,
    private val getActiveRuntimeIdentity: () -> ModelRuntimeIdentity?,
    private val markLocalConversationContextInvalid: () -> Unit,
    private val persistChatSessions: () -> Job?,
    private val showStatus: (String) -> Unit,
    private val onLocalInferenceStarted: () -> Unit,
    private val contextSummarizationService: ContextSummarizationService = ContextSummarizationService()
) {
    companion object {
        const val PREFERENCES_NAME = "mca_context_compression_v1"
        const val THRESHOLD_KEY = "threshold_percent"
        const val SUMMARY_MODEL_KEY = "summary_model"

        fun loadThreshold(preferences: SharedPreferences): ContextCompressionThreshold =
            ContextCompressionThreshold.values()
                .firstOrNull {
                    it.percent == preferences.getInt(
                        THRESHOLD_KEY,
                        ContextCompressionThreshold.EIGHTY.percent
                    )
                }
                ?: ContextCompressionThreshold.EIGHTY

        fun loadSummaryModelSelection(preferences: SharedPreferences): String =
            preferences.getString(SUMMARY_MODEL_KEY, "current") ?: "current"
    }

    /**
     * Schedules a one-shot compression before the next chat request. The
     * request is deliberately consumed by startGeneration so the stored
     * conversation is never rewritten behind the user's back.
     */
    fun requestContextCompression() {
        val state = store.current
        if (!state.canWriteToActiveAssistantSession()) return
        if (state.isGenerating) {
            showStatus("当前正在生成，请完成或停止后再压缩上下文。")
            return
        }
        if (state.messages.isEmpty()) {
            showStatus("当前对话还没有可压缩的内容。")
            return
        }
        val sessionId = state.activeChatSessionId
        if (sessionId.isNullOrBlank()) {
            showStatus("当前对话尚未保存，暂时无法安排压缩。")
            return
        }
        manualContextCompressionRequests.request(sessionId)
        store.update {
            it.copy(
                contextCompressionPending = true,
                statusMessage = "已安排压缩；下一次发送时会保留角色设定和最近消息。"
            )
        }
    }

    fun toggleMessagePinned(messageId: String) {
        val owner = store.current
        val sessionId = owner.activeChatSessionId ?: return
        if (owner.isGenerating || !owner.canWriteToActiveAssistantSession()) return
        val occurredAt = System.currentTimeMillis()
        val changed = store.updateWithResult { state ->
            if (state.isGenerating || state.activeChatSessionId != sessionId ||
                !state.snapshot.canWriteToActiveAssistantSession() ||
                state.messages.none { it.id == messageId }
            ) return@updateWithResult state to false
            val messages = state.messages.map { message ->
                if (message.id == messageId) message.copy(pinned = !message.pinned) else message
            }
            state.copy(
                messages = messages,
                chatSessions = state.chatSessions.upsertSessionFor(state.snapshot, sessionId, messages, occurredAt = occurredAt).map { session ->
                    if (session.id != sessionId) session else session.copy(
                        contextSummaries = revokeContextSummaries(session.contextSummaries)
                    )
                }
            ) to true
        }
        if (changed) {
            markLocalConversationContextInvalid()
            persistChatSessions()
        }
    }

    fun undoContextSummary() {
        val state = store.current
        if (state.isGenerating || !state.canWriteToActiveAssistantSession()) return
        val sessionId = state.activeChatSessionId ?: return
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val summaryScope = (assistantSnapshot?.applyTo(state.params) ?: state.params).systemPrompt +
            "\n" + assistantSnapshot?.toJsonString().orEmpty()
        store.update { current ->
            current.copy(
                chatSessions = current.chatSessions.map { session ->
                    if (session.id != sessionId) session else session.copy(
                        contextSummaries = restorePreviousContextSummaryRevision(
                            session.contextSummaries, session.messages, summaryScope
                        )
                    )
                },
                statusMessage = "已撤销当前摘要并恢复可用的上一版本；原始消息保留。"
            )
        }
        markLocalConversationContextInvalid()
        persistChatSessions()
    }

    fun contextSummaryEvidenceForQuery(query: String): List<ContextSummaryEvidenceUiItem> {
        val state = store.current
        val session = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
            ?: return emptyList()
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val scope = (assistantSnapshot?.applyTo(state.params) ?: state.params).systemPrompt +
            "\n" + assistantSnapshot?.toJsonString().orEmpty()
        return ContextSummarySourceIndex.fromActiveRevision(
            session.contextSummaries, session.messages, scope
        ).search(query).map { hit ->
            ContextSummaryEvidenceUiItem(
                kind = hit.kind.name,
                text = hit.text,
                sourceMessageId = hit.sourceMessageId,
                sourceOffset = hit.sourceOffset
            )
        }
    }

    /** Persists one of the supported automatic thresholds and updates the UI. */
    fun setContextCompressionThreshold(percent: Int) {
        val threshold = ContextCompressionThreshold.values()
            .firstOrNull { it.percent == percent }
            ?: ContextCompressionThreshold.EIGHTY
        preferences.edit()
            .putInt(THRESHOLD_KEY, threshold.percent)
            .apply()
        store.update {
            it.copy(
                contextCompressionThresholdPercent = threshold.percent,
                statusMessage = "自动压缩阈值已设为 ${threshold.percent}%。"
            )
        }
    }

    fun loadContextCompressionThreshold(): ContextCompressionThreshold =
        loadThreshold(preferences)

    fun setContextSummaryModel(selection: String) {
        val state = store.current
        if (state.isGenerating) return
        val valid = selection in setOf("current", "deterministic", "local") ||
            state.cloudModels.any {
                selection == "cloud:${it.id}" && it.kind == CloudModelKind.CHAT && it.configured
            }
        if (!valid) return
        preferences.edit().putString(SUMMARY_MODEL_KEY, selection).apply()
        store.update { it.copy(contextSummaryModelSelection = selection) }
    }

    suspend fun summarizeContextForGeneration(
        request: ChatRequest,
        settings: ContextCompressionSettings,
        trigger: ContextCompressionTrigger,
        initialState: MainUiState,
        effectiveBackend: ChatBackend,
        cloudModelIdOverride: String?,
        generationRunId: Long,
        useModel: Boolean
    ): ContextCompressionResult {
        fun ownerCurrent(): Boolean {
            val current = store.current
            val expectedSession = initialState.chatSessions.firstOrNull {
                it.id == initialState.activeChatSessionId
            }
            val actualSession = current.chatSessions.firstOrNull {
                it.id == initialState.activeChatSessionId
            }
            return chatRuns.matches(generationRunId) &&
                current.activeChatSessionId == initialState.activeChatSessionId &&
                current.messages == initialState.messages &&
                expectedSession != null && actualSession != null &&
                !actualSession.mixedAssistantHistory &&
                actualSession.assistantId == expectedSession.assistantId &&
                actualSession.assistantSnapshot == expectedSession.assistantSnapshot
        }
        val selection = initialState.contextSummaryModelSelection
        val local = selection == "local" || selection == "current" && effectiveBackend == ChatBackend.LOCAL
        val localIdentity = getActiveRuntimeIdentity()
        val cloudId = if (selection.startsWith("cloud:")) selection.removePrefix("cloud:")
            else cloudModelIdOverride ?: initialState.selectedCloudChatModelId
        val cloudConfig = initialState.cloudModels.firstOrNull {
            it.id == cloudId && it.kind == CloudModelKind.CHAT && it.configured
        }?.toChatConfig()?.normalized()
        val model = when {
            !useModel || selection == "deterministic" -> null
            local && localIdentity != null && engine.stats.value.loaded -> ContextSummaryModel(
                modelId = initialState.loadedModelId ?: localIdentity.identityHash,
                runtimeIdentity = localIdentity.identityHash,
                params = engine.activeExecutionProfile()?.let { mergedExecutionProfile(request.params, it) }
                    ?: request.params
            )
            !local && cloudConfig != null && cloudId != null -> ContextSummaryModel(
                modelId = cloudId,
                runtimeIdentity = "cloud:" + contextSummaryDigest(JSONArray(listOf(
                    cloudId, cloudConfig.baseUrl.trimEnd('/'), cloudConfig.apiFormat.name, cloudConfig.chatModel
                )).toString()),
                params = modelGenerationProfileStore.load(ModelGenerationProfileKey.cloud(cloudId), request.params),
                source = ContextSummarySource.CLOUD_MODEL
            )
            else -> null
        }
        return contextSummarizationService.compress(
            request = request,
            settings = settings,
            trigger = trigger,
            model = model,
            isOwnerCurrent = ::ownerCurrent,
            infer = { summaryRequest ->
                if (!ownerCurrent()) throw CancellationException("Summary owner changed.")
                store.update { current ->
                    if (ownerCurrent()) current.copy(statusMessage = "正在整理上下文摘要...") else current
                }
                if (local) {
                    check(!hasActiveImageRequest()) {
                        "Local image generation owns the runtime resources."
                    }
                    withContext(Dispatchers.IO) {
                        val lease = engine.acquireExclusiveLifecycleLease(stopActiveGeneration = false)
                        var inferenceStarted = false
                        try {
                            if (!ownerCurrent() || getActiveRuntimeIdentity()?.identityHash != localIdentity?.identityHash) {
                                throw CancellationException("Summary model or owner changed.")
                            }
                            inferenceStarted = true
                            collectContextSummary(
                                engine.streamChat(
                                    summaryRequest,
                                    LocalChatExecutionContext(
                                        requestId = "context-summary-$generationRunId-${UUID.randomUUID()}",
                                        lifecycleLease = lease,
                                        includeDeviceClockContext = false
                                    )
                                ),
                                ::ownerCurrent
                            )
                        } finally {
                            if (inferenceStarted) {
                                onLocalInferenceStarted()
                            }
                            withContext(NonCancellable) { lease.release() }
                        }
                    }
                } else {
                    // The summary model is chosen before this branch; re-read defensively instead of
                    // asserting, because a model deleted between selection and inference would turn
                    // a summarization miss into an uncaught NPE.
                    val config = cloudConfig
                        ?: throw CancellationException("Summary cloud model is no longer available.")
                    collectContextSummary(cloudChatProvider.streamChat(config, summaryRequest), ::ownerCurrent)
                }
            }
        )
    }

    fun promptContextUsageFor(
        plan: ChatRuntimeContextPlan,
        admission: ContextWindowAdmission,
        params: GenerationParams
    ): PromptContextUsage {
        val historyRetention = admission.messageRetention.filter { it.role != Role.SYSTEM }
        return PromptContextUsage(
            retainedMessageCount = historyRetention.count { it.retained },
            trimmedMessageCount = historyRetention.count { !it.retained },
            roleTokens = estimateLocalPromptTokens(params.systemPrompt),
            worldBookTokens = plan.worldBook.estimatedTokens.coerceAtLeast(0),
            knowledgeTokens = plan.knowledge.estimatedTokens.coerceAtLeast(0),
            totalEstimatedTokens = admission.admittedUsage.estimatedTokens.coerceAtLeast(0L),
            messageRetention = historyRetention,
            selectedWorldBookEntryIds = plan.worldBook.selectedEntryIds,
            skippedWorldBookEntryIds = plan.worldBook.skippedEntryIds,
            selectedKnowledgeChunkIds = plan.knowledge.chunks.map { it.id },
            skippedKnowledgeChunkIds = plan.knowledge.skippedChunkIds
        )
    }
}
