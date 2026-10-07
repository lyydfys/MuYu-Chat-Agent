package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.ChatGenerationMetrics
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.localContextWindowAdmission
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.muyuchat.mca.presentation.ChatContextOperations
import com.muyuchat.mca.presentation.ChatContextSnapshot
import com.muyuchat.mca.presentation.ChatGenerationUiPublisher
import com.muyuchat.mca.presentation.afterGenerationTerminated
import java.util.concurrent.atomic.AtomicLong

/** Promoted from MainViewModel for the execution-owner seam; same shape, internal visibility. */
internal data class AssistantImageModelBinding(
    val backend: ImageBackend,
    val modelId: String,
    val modelName: String,
    val options: LocalImageGenerationOptions,
    val fingerprint: String
)

/**
 * The owner seam for the chat generation execution body moved out of
 * `MainViewModel.startGeneration` (P3 step 1). The ViewModel keeps the reservation, the LAZY
 * launcher, the run publish/foreground-lease tail and every collaborator; the moved body is
 * a receiver extension over this port so the verbatim flow stays byte-for-byte recognisable.
 */
internal interface ChatGenerationExecutionPort {

    // R3/S27: 契约收敛——端口只暴露只读 StateFlow 与具名写入方法。
    val uiState: StateFlow<MainUiState>
    fun updateUiState(transform: (MainUiState) -> MainUiState)

    val engine: McaInferenceService

    val chatRuns: ChatRunOwnership

    val chatSessionStore: ChatSessionStore

    val chatContextComposer: ChatContextComposer

    val cloudChatProvider: OpenAiCompatibleChatProvider

    val webSearchProvider: WebSearchProvider

    val manualContextCompressionRequests: ContextCompressionRequestStore

    val localConversationContextInvalidationSequence: AtomicLong

    var activeWebSearchDeferred: Deferred<WebSearchResult>?

    var activeWebSearchTaskId: String?

    var localConversationContextNeedsInvalidation: Boolean

    val foregroundRecoveryJob: Job?

    fun persistChatSessions(sessions: List<ChatSessionRecord>? = null, knowledgeBinding: Pair<String, Set<String>>? = null, generationRunId: Long? = null): Job?

    fun appendAssistant(delta: String, reasoningDelta: String = "", reasoningDurationMs: Long = 0L, forcePublish: Boolean = false, generationRunId: Long? = null)

    fun flushPendingAssistantOutput(generationRunId: Long? = null): Boolean

    fun removePendingAssistantPlaceholder(generationRunId: Long? = null)

    fun ensureVisibleAssistantReplyAfterCompletion(generationRunId: Long? = null)

    fun applyWebSearchAnswerGuardsToLastAssistant(generationRunId: Long? = null): WebSearchCitationAudit?

    fun recordWebSearchCitationAudit(recordId: String?, audit: WebSearchCitationAudit, generationRunId: Long? = null)

    fun attachWebSearchEvidenceToPendingAssistant(sources: List<ChatSourceReference>, trace: ChatWebSearchTrace?, generationRunId: Long? = null)

    fun attachContextAssemblyTrace(traceJson: String?, generationRunId: Long, sessionId: String?, assistantMessageId: String?)

    fun promptContextUsageFor(plan: ChatRuntimeContextPlan, admission: ContextWindowAdmission, params: GenerationParams): PromptContextUsage

    fun appendWebSearchDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord>

    fun recordCompletedCharacterMemoryTurn(assistantId: String, sessionId: String, assistantSnapshot: AssistantConversationSnapshot, user: ChatMessage, assistant: ChatMessage, conversationPersistJob: Job?, backend: ChatBackend, params: GenerationParams, cloudConfig: CloudApiConfig?, localIdentity: String?)

    suspend fun summarizeContextForGeneration(request: ChatRequest, settings: ContextCompressionSettings, trigger: ContextCompressionTrigger, initialState: MainUiState, effectiveBackend: ChatBackend, cloudModelIdOverride: String?, generationRunId: Long, useModel: Boolean): ContextCompressionResult

    suspend fun ensureLocalConversationContextInvalidated(): Boolean

    fun loadContextCompressionThreshold(): ContextCompressionThreshold

    suspend fun handleAssistantImageToolCalls(calls: List<ChatToolCall>, originalRequestMessages: List<ChatMessage>, paramsBeforeAssistant: GenerationParams, chatSessionId: String?, cloudModelId: String?, chatModelIdentity: String?, imageModelBinding: AssistantImageModelBinding?, chatConfig: CloudApiConfig?): Boolean

    fun finishAssistantImageContinuation(requestId: String, completed: Boolean, failureMessage: String)

    fun chatImageToolChatModelIdentity(modelId: String?, config: CloudApiConfig?): String?

    suspend fun localVisionRunnerAvailable(): Boolean

    fun mergeExecutionProfile(generation: GenerationParams, profile: ModelExecutionProfile): GenerationParams

    fun persistGenerationParams(params: GenerationParams)

    fun canWriteToActiveAssistantSession(state: MainUiState): Boolean

    fun selectedAssistant(state: MainUiState): AssistantRecord?

    fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig?

    fun currentChatModelId(state: MainUiState): String?

    fun chatImageModelBinding(state: MainUiState): AssistantImageModelBinding?

    val activeRuntimeIdentity: ModelRuntimeIdentity?

    fun localVisionUnavailableMessage(state: MainUiState): String

    fun chatContextSnapshot(state: MainUiState, assistantId: String, fileContextEnabled: Boolean): ChatContextSnapshot

    fun isConfigurationActionError(event: GenerateEvent.Error): Boolean

    suspend fun withInlineImageDataForCloud(messages: List<ChatMessage>): List<ChatMessage>

    suspend fun withLocalImageFilesForVision(messages: List<ChatMessage>, createdFiles: MutableList<File>): List<ChatMessage>
}

internal suspend fun ChatGenerationExecutionPort.runChatGeneration(
    requestMessages: List<ChatMessage>,
    reservation: UiGenerationReservation,
    replyMetrics: ReplyGenerationMetrics,
    toolExchanges: List<ChatToolExchange>,
    paramsOverride: GenerationParams?,
    offerAssistantImageTool: Boolean,
    chatModelIdOverride: String?,
    continuationRequestId: String?,
    preflightCompression: ContextCompressionResult?
) {
    val generationRunId = reservation.runId
    // This coroutine's own Job handle, taken from its own scope receiver. The body used to
    // close over the `lateinit var` that was still being assigned while the lambda was
    // built; removing that self-reference is what lets the body be handed to a future
    // execution owner instead of reaching back into its own launcher.
    val selfJob = requireNotNull(coroutineContext[Job]) {
        "startGeneration body must run as a launched coroutine"
    }
    var terminalEventSeen = false
    var continuationCompleted = false
    var continuationFailureMessage = "角色回复未完成，可点击继续角色回复重试。"
    val preparedUiVisionFiles = mutableListOf<File>()
    fun generationStillOwnsUi(): Boolean =
        chatRuns.matches(generationRunId)

    fun settleGenerationUi(
        stats: RuntimeStats,
        statusMessage: String? = null
    ) {
        if (!generationStillOwnsUi()) return
        updateUiState { state ->
            if (!generationStillOwnsUi()) {
                state
            } else {
                state.afterGenerationTerminated(stats, statusMessage)
                    .withReplyGenerationMetrics(replyMetrics.finish())
            }
        }
    }

    val generationUiPublisher = ChatGenerationUiPublisher(chatRuns, generationRunId, uiState, { transform -> updateUiState(transform) })

    try {
    if (!generationStillOwnsUi()) return
    val initialState = uiState.value
    if (!canWriteToActiveAssistantSession(initialState)) {
        terminalEventSeen = true
        settleGenerationUi(engine.stats.value, "此对话的角色归属不明确，无法继续生成；请先手动分支。")
        return
    }
    val frozenSession = initialState.chatSessions.first { it.id == initialState.activeChatSessionId }
    val frozenAssistantSnapshot = requireNotNull(frozenSession.assistantSnapshot)
    val frozenAssistantId = frozenAssistantSnapshot.assistantId
    val effectiveChatBackend = if (chatModelIdOverride != null) {
        ChatBackend.CLOUD
    } else {
        initialState.selectedChatBackend
    }
    if (effectiveChatBackend == ChatBackend.LOCAL) {
        // Foreground reconciliation probes the isolated worker on IO. A send issued
        // immediately after returning to the app must not overtake that probe and
        // observe the transient unloaded projection before worker-loss recovery is armed.
        chatRuns.publishedJobUnless(foregroundRecoveryJob)?.join()
        if (!generationStillOwnsUi()) return
    }
    val assistantSnapshot = frozenAssistantSnapshot
    val characterMemoryEnabled = initialState.assistants.any {
        it.id == frozenAssistantId &&
            it.memoryEnabled
    }
    if (effectiveChatBackend == ChatBackend.LOCAL &&
        !ensureLocalConversationContextInvalidated()
    ) {
        return
    }
    val baseParams = (paramsOverride ?: initialState.params).let { current ->
        val backendParams = if (effectiveChatBackend == ChatBackend.CLOUD) {
            current.copy(
                reasoningMode = ReasoningMode.STANDARD,
                hideReasoning = false
            )
        } else {
            current
        }
        backendParams.copy(nPredict = backendParams.effectiveNPredict())
    }
    if (effectiveChatBackend == ChatBackend.LOCAL && baseParams != initialState.params) {
        persistGenerationParams(baseParams)
        generationUiPublisher.publishNormalizedTurnParams(baseParams)
    }
    val requestParams = assistantSnapshot?.applyTo(baseParams) ?: baseParams
    val memoryForTurn = if (characterMemoryEnabled) {
        withContext(Dispatchers.IO) {
            chatSessionStore.loadMemories(frozenAssistantId) to
                chatSessionStore.loadMemoryTurns(frozenAssistantId)
        }
    } else emptyList<MemoryRecord>() to emptyList<AssistantMemoryTurnRecord>()
    val characterMemoryContextForTurn = if (characterMemoryEnabled) {
        buildCharacterMemoryContext(
            assistantId = frozenAssistantId,
            memories = memoryForTurn.first,
            pendingTurns = memoryForTurn.second,
            currentSessionId = frozenSession.id
        )
    } else ""
    val frozenRoleCardVersion = com.muyuchat.core.engine.PrefixCacheKey.sha256Utf8(
        frozenAssistantSnapshot.toJsonString()
    )
    val frozenMemoryRevision = com.muyuchat.core.engine.PrefixCacheKey.sha256Utf8(
        buildString {
            append(frozenAssistantId).append('\n')
            memoryForTurn.first.sortedBy { it.id }.forEach { memory ->
                append(memory.id).append('\u001f').append(memory.scope).append('\u001f')
                    .append(memory.content).append('\n')
            }
            memoryForTurn.second.sortedBy { it.id }.forEach { turn ->
                append(turn.id).append('\u001f').append(turn.sessionId).append('\u001f')
                    .append(turn.userText).append('\u001f').append(turn.assistantText).append('\n')
            }
        }
    )
    // A private summary request completes before the normal chat request takes
    // the runtime lease. Original messages remain the revision source.
    val manualCompression = manualContextCompressionRequests.consume(initialState.activeChatSessionId)
    val compressionThreshold = loadContextCompressionThreshold()
    if (manualCompression) {
        generationUiPublisher.publishManualCompressionConsumed()
    }
    val summaryRecords = frozenSession.contextSummaries
    val allowSummaryCandidate = manualCompression || summaryRecords.isEmpty() || summaryRecords.any { it.active }
    val compression = summarizeContextForGeneration(
        request = ChatRequest(
            messages = requestMessages,
            params = requestParams,
            runtimeSystemContext = preflightCompression?.request?.runtimeSystemContext
                ?.takeIf(String::isNotBlank) ?: characterMemoryContextForTurn,
            protectedMessageIds = requestMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id }
        ),
        settings = ContextCompressionSettings(
            threshold = compressionThreshold,
            keepRecentMessages = 8
        ),
        trigger = if (manualCompression || preflightCompression?.didCompress == true) {
            ContextCompressionTrigger.MANUAL
        } else {
            ContextCompressionTrigger.AUTOMATIC
        },
        initialState = initialState,
        effectiveBackend = effectiveChatBackend,
        cloudModelIdOverride = chatModelIdOverride,
        generationRunId = generationRunId,
        useModel = allowSummaryCandidate
    )
    if (effectiveChatBackend == ChatBackend.LOCAL && !ensureLocalConversationContextInvalidated()) return
    if (!generationStillOwnsUi()) return
    val summaryScope = requestParams.systemPrompt + "\n" +
        assistantSnapshot?.toJsonString().orEmpty()
    val preparedSummary = if (allowSummaryCandidate) {
        contextSummaryCandidate(
            originals = requestMessages, result = compression,
            previous = summaryRecords, scope = summaryScope
        )
    } else null
    val reusableSummary = summaryRecords.lastOrNull { it.appliesTo(requestMessages, summaryScope) }
    val summaryCandidate = preparedSummary?.takeUnless {
        it.sourceFingerprint == reusableSummary?.sourceFingerprint &&
            it.scopeFingerprint == reusableSummary?.scopeFingerprint && it.text == reusableSummary?.text
    }
    val effectiveRequestMessages = summaryCandidate?.projectCandidate(requestMessages, summaryScope)
        ?: reusableSummary?.project(requestMessages, summaryScope)
        ?: requestMessages
    if (effectiveRequestMessages !== requestMessages) {
        generationUiPublisher.publishContextCompressedStatus(compression.compressedMessageCount)
    }
    val imageModelBindingForToolTurn = chatImageModelBinding(initialState)
    val configuredPersonaForTurn = assistantSnapshot
        ?.systemPrompt
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: requestParams.systemPrompt.trim().takeIf { it.isNotBlank() }
    val persistentLlamaPrefix = configuredPersonaForTurn.takeIf {
        initialState.persistentPrefixCacheEnabled &&
            activeRuntimeIdentity?.runtime == LocalChatRuntime.LLAMA_CPP
    }
    val runtimeContextPlan = withContext(Dispatchers.IO) {
        chatContextComposer.compose(
            messages = effectiveRequestMessages,
            params = requestParams,
            assistantId = assistantSnapshot?.assistantId ?: initialState.selectedAssistantId,
            chatSessionId = initialState.activeChatSessionId,
            knowledgeBaseIds = initialState.selectedKnowledgeBaseIds,
            fileContextEnabled = assistantSnapshot?.fileContextEnabled
                ?: selectedAssistant(initialState)?.fileContextEnabled
                ?: true
        )
    }
    // The wording decision now lives in ChatContextOperations.turnContextStatus, pinned by tests.
    val contextSnapshot = chatContextSnapshot(initialState,
        assistantId = assistantSnapshot?.assistantId ?: initialState.selectedAssistantId,
        // Same fallback chain the composer call above uses, resolved at the same moment.
        fileContextEnabled = assistantSnapshot?.fileContextEnabled
            ?: selectedAssistant(initialState)?.fileContextEnabled
            ?: true
    )
    val contextStatus = ChatContextOperations.turnContextStatus(contextSnapshot, runtimeContextPlan)
    if (contextStatus != null) {
        generationUiPublisher.publishContextStatusMessage(contextStatus)
    }
    val webSearchTurnMode = if (initialState.webSearchOneShotEnabled) {
        WebSearchTurnMode.ON
    } else {
        initialState.webSearchTurnMode
    }
    val webSearchConfigForTurn = initialState.webSearchResearchModeOverride?.let { researchMode ->
        initialState.webSearchConfig.copy(researchMode = researchMode)
    } ?: initialState.webSearchConfig
    if (
        initialState.webSearchOneShotEnabled ||
        initialState.webSearchTurnMode != WebSearchTurnMode.FOLLOW ||
        initialState.webSearchResearchModeOverride != null
    ) {
        generationUiPublisher.publishWebSearchTurnReset()
    }
    var browserSearchTaskId: String? = null
    val rawWebSearchTurn = executeWebSearchForChatTurn(
        messages = effectiveRequestMessages,
        config = webSearchConfigForTurn,
        oneShotEnabled = webSearchTurnMode == WebSearchTurnMode.ON,
        assistantWebSearchEnabled = assistantSnapshot?.webSearchEnabled
            ?: (selectedAssistant(initialState)?.webSearchEnabled == true),
        turnMode = webSearchTurnMode,
        search = { plan, config ->
            supervisorScope {
                val taskId = browserSearchTaskId
                if (taskId != null && uiState.value.browserTask?.let { task ->
                        task.taskId == taskId && task.phase ==
                            com.muyuchat.feature.chat.BrowserTaskPhase.CANCELLED
                    } == true
                ) throw CancellationException("Browser search task cancelled")
                val deferred = async(Dispatchers.IO) { webSearchProvider.search(plan, config) }
                activeWebSearchDeferred = deferred
                activeWebSearchTaskId = taskId
                try {
                    deferred.await()
                } finally {
                    if (activeWebSearchDeferred === deferred) {
                        activeWebSearchDeferred = null
                        activeWebSearchTaskId = null
                    }
                }
            }
        },
        beforeSearch = { plan, triggerReasons ->
            val task = com.muyuchat.feature.chat.BrowserTaskStateMachine.beginBackgroundSearch(
                owner = "chat-web-search",
                sessionId = initialState.activeChatSessionId,
                messageId = initialState.messages.lastOrNull()
                    ?.takeIf { it.role == Role.ASSISTANT && it.content.isBlank() }?.id
                    ?: requestMessages.lastOrNull { it.role == Role.USER }?.id,
                query = plan.displayQuery
            )
            if (generationUiPublisher.publishBrowserSearchTaskStart(task)) {
                browserSearchTaskId = task.taskId
            }
            attachWebSearchEvidenceToPendingAssistant(
                sources = emptyList(),
                trace = plan.toPendingChatWebSearchTrace(
                    config = webSearchConfigForTurn,
                    triggerReasons = triggerReasons
                ),
                generationRunId = generationRunId
            )
            generationUiPublisher.publishWebSearchStartStatus(plan.displayQuery)
        }
    )
    val searchTaskCancelled = browserSearchTaskId?.let { taskId ->
        uiState.value.browserTask?.let { task ->
            task.taskId == taskId &&
                task.phase == com.muyuchat.feature.chat.BrowserTaskPhase.CANCELLED
        }
    } == true
    val webSearchTurn = if (searchTaskCancelled) rawWebSearchTurn.copy(
        success = false,
        promptContext = "",
        sourceReferences = emptyList(),
        webSearchStatusMessage = "联网检索已取消",
        statusMessage = "联网检索已取消，本轮继续聊天。"
    ) else rawWebSearchTurn
    browserSearchTaskId?.let { taskId ->
        generationUiPublisher.publishBrowserSearchResults(
            taskId = taskId,
            urls = webSearchTurn.sourceReferences.map(ChatSourceReference::url),
            message = webSearchTurn.webSearchStatusMessage
        )
    }
    val runtimeSystemContextForTurn = listOf(
        runtimeContextPlan.runtimeSystemContext,
        characterMemoryContextForTurn,
        webSearchTurn.promptContext
    )
        .filter { it.isNotBlank() }
        .distinct()
        .joinToString("\n\n")
    attachWebSearchEvidenceToPendingAssistant(
        sources = webSearchTurn.sourceReferences,
        trace = webSearchTurn.diagnostic?.toChatWebSearchTrace(),
        generationRunId = generationRunId
    )
    if (webSearchTurn.requested) {
        val diagnostics = webSearchTurn.diagnostic?.let(::appendWebSearchDiagnostic)
        generationUiPublisher.publishWebSearchOutcome(
            webSearchStatusMessage = webSearchTurn.webSearchStatusMessage,
            diagnostics = diagnostics,
            statusMessage = webSearchTurn.statusMessage
        )
    }
    val contextAdmission = localContextWindowAdmission(
        ChatRequest(
            messages = effectiveRequestMessages,
            params = requestParams,
            protectedMessageIds = requestMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id },
            runtimeSystemContext = runtimeSystemContextForTurn
        )
    )
    if (!contextAdmission.isAccepted) {
        appendAssistant(
            "\n${contextAdmission.userMessage ?: "当前请求超过模型上下文窗口，请压缩历史对话或缩短输入后重试。"}",
            generationRunId = generationRunId
        )
        generationUiPublisher.refuseGenerationBeforeStream(
            contextAdmission.userMessage ?: "当前请求超过模型上下文窗口"
        )
        persistChatSessions(generationRunId = generationRunId)
        return
    }
    generationUiPublisher.publishPromptContextUsage(
        promptContextUsageFor(
            plan = runtimeContextPlan,
            admission = contextAdmission,
            params = requestParams
        )
    )
    // Admission may deterministically trim older turns after the
    // compression pass.  Use that exact list for every provider; the
    // full list remains in the persisted session for future summaries.
    val admittedRequestMessages = contextAdmission.request.messages
    // Live KV invalidation changes on every role switch. Keep the disk identity
    // stable for a return visit; native token-prefix validation rejects edited history.
    val frozenContextRevision = com.muyuchat.core.engine.PrefixCacheKey.sha256Utf8(
        buildString {
            append(frozenSession.id).append('\n')
            summaryRecords.forEach { append(it.toString()).append('\n') }
            summaryCandidate?.let { append(it.toString()) }
        }
    )
    attachContextAssemblyTrace(
        withContext(Dispatchers.IO) { runtimeContextPlan.trace?.toJsonString() },
        generationRunId,
        initialState.activeChatSessionId,
        initialState.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
    )
    val state = uiState.value
    val hasImageAttachments = admittedRequestMessages.any { it.imageAttachments.isNotEmpty() }
    var localUiRequestId: String? = null
    var cloudModelIdForToolTurn: String? = chatModelIdOverride
    var chatModelIdentityForToolTurn: String? = null
    var chatConfigForToolTurn: CloudApiConfig? = null
    val stream = if (effectiveChatBackend == ChatBackend.CLOUD) {
        val cloudConfig = (chatModelIdOverride?.let { modelId ->
            state.cloudModels.firstOrNull {
                it.id == modelId && it.kind == CloudModelKind.CHAT && it.configured
            }?.toChatConfig()
        } ?: selectedChatCloudConfig(state))?.normalized()
        if (cloudConfig == null) {
            appendAssistant(
                "\n请先在模型管理 > 云端 加载一个对话推理模型。",
                generationRunId = generationRunId
            )
            generationUiPublisher.refuseGenerationBeforeStream("未加载云端推理模型")
            persistChatSessions(generationRunId = generationRunId)
            return
        }
        chatConfigForToolTurn = cloudConfig
        cloudModelIdForToolTurn = chatModelIdOverride ?: currentChatModelId(initialState)
        chatModelIdentityForToolTurn = chatImageToolChatModelIdentity(
            cloudModelIdForToolTurn,
            cloudConfig
        )
        if (hasImageAttachments && !cloudConfig.supportsVision) {
            appendAssistant(
                "\n当前云端推理引擎未开启图片输入。请在模型管理中编辑该云端推理引擎，打开“支持图片输入”后再发送图片。",
                generationRunId = generationRunId
            )
            generationUiPublisher.refuseGenerationBeforeStream("云端识图未启用")
            persistChatSessions(generationRunId = generationRunId)
            return
        }
        val cloudMessages = runCatching {
            withInlineImageDataForCloud(admittedRequestMessages)
        }.getOrElse { error ->
            appendAssistant(
                "\n图片读取失败：无法读取或压缩这张图片。请换一张本地图片，或检查文件权限。${error.message?.let { "\n原因：$it" } ?: ""}",
                generationRunId = generationRunId
            )
            generationUiPublisher.refuseGenerationBeforeStream("图片读取失败")
            persistChatSessions(generationRunId = generationRunId)
            return
        }
        cloudChatProvider.streamChat(
            cloudConfig,
            ChatRequest(
                messages = cloudMessages,
                params = requestParams,
                runtimeSystemContext = runtimeSystemContextForTurn,
                tools = if (offerAssistantImageTool && toolExchanges.isEmpty() &&
                    cloudConfig.apiFormat == CloudApiFormat.OPENAI_RESPONSES &&
                    cloudConfig.supportsTools &&
                    imageModelBindingForToolTurn != null
                ) {
                    listOf(chatImageToolDefinition())
                } else {
                    emptyList()
                },
                toolExchanges = toolExchanges
            )
        )
    } else {
        if (hasImageAttachments && !localVisionRunnerAvailable()) {
            appendAssistant(
                "\n${localVisionUnavailableMessage(state)}",
                generationRunId = generationRunId
            )
            generationUiPublisher.refuseGenerationBeforeStream("本地视觉 runner 未启用")
            persistChatSessions(generationRunId = generationRunId)
            return
        }
        val localMessages = if (hasImageAttachments) {
            runCatching { withLocalImageFilesForVision(admittedRequestMessages, preparedUiVisionFiles) }
                .getOrElse { error ->
                    appendAssistant(
                        "\n本地图片预处理失败：图片压缩或缓存失败，请换一张本地图片后重试。${error.message?.let { "\n原因：$it" } ?: ""}",
                        generationRunId = generationRunId
                    )
                    generationUiPublisher.refuseGenerationBeforeStream("本地图片预处理失败")
                    persistChatSessions(generationRunId = generationRunId)
                    return
                }
        } else {
            admittedRequestMessages
        }
        val uiRequestId = "ui-$generationRunId-${UUID.randomUUID().toString().replace("-", "")}"
        localUiRequestId = uiRequestId
        val executionContext = LocalChatExecutionContext(requestId = uiRequestId)
        // The editor may contain a not-yet-applied load-bound value.
        // Ordinary chat must continue with the committed profile until
        // the explicit reload transaction succeeds.
        val activeRequestParams = engine.activeExecutionProfile()
            ?.let { profile -> mergeExecutionProfile(requestParams, profile) }
            ?: requestParams
        LocalApiRuntime.streamChat(
            ChatRequest(
                messages = localMessages,
                params = activeRequestParams,
                runtimeSystemContext = runtimeSystemContextForTurn,
                persistentPrefixSystemPrompt = persistentLlamaPrefix,
                persistentSessionId = frozenSession.id,
                persistentRoleId = frozenAssistantId,
                persistentRoleCardVersion = frozenRoleCardVersion,
                persistentMemoryVersion = frozenMemoryRevision,
                protectedMessageIds = localMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id },
                conversationContextRevision = frozenContextRevision
            ),
            executionContext
        ) ?: engine.streamChat(
            ChatRequest(
                messages = localMessages,
                params = activeRequestParams,
                runtimeSystemContext = runtimeSystemContextForTurn,
                persistentPrefixSystemPrompt = persistentLlamaPrefix,
                persistentSessionId = frozenSession.id,
                persistentRoleId = frozenAssistantId,
                persistentRoleCardVersion = frozenRoleCardVersion,
                persistentMemoryVersion = frozenMemoryRevision,
                protectedMessageIds = localMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id },
                conversationContextRevision = frozenContextRevision
            ),
            executionContext
        )
    }
    val runtimePhase = if (effectiveChatBackend == ChatBackend.LOCAL) {
        UiGenerationRuntimePhase.LOCAL_ACTIVE
    } else {
        UiGenerationRuntimePhase.CLOUD_ACTIVE
    }
    if (!chatRuns.markPhase(
            generationRunId,
            selfJob,
            runtimePhase
        )
    ) {
        return
    }
    stream.collect { event ->
        // Flow cancellation is cooperative. A transport can therefore
        // deliver a late event after a newer generation has claimed the
        // UI; never let that event overwrite the newer projection.
        if (!generationStillOwnsUi()) return@collect
        if (terminalEventSeen) return@collect
        when (event) {
            is GenerateEvent.Phase -> {
                if (!generationStillOwnsUi()) return@collect
                generationUiPublisher.publishStreamPhase(
                    event.stats,
                    event.phase,
                    event.tokenProgress
                )
            }
            is GenerateEvent.Chunk -> {
                if (!generationStillOwnsUi()) return@collect
                replyMetrics.record(event.stats)
                appendAssistant(
                    delta = event.text,
                    reasoningDelta = event.reasoning,
                    reasoningDurationMs = event.reasoningDurationMs,
                    generationRunId = generationRunId
                )
                generationUiPublisher.publishStreamChunkStats(event.stats)
            }
            is GenerateEvent.Persist -> {
                if (!generationStillOwnsUi() || !event.progress.isActive) return@collect
                generationUiPublisher.publishPersistProgress(event.progress)
            }
            is GenerateEvent.Done -> {
                if (!generationStillOwnsUi()) return@collect
                replyMetrics.record(event.stats)
                chatRuns.markPhase(
                    generationRunId,
                    selfJob,
                    UiGenerationRuntimePhase.TERMINAL
                )
                terminalEventSeen = true
                // This must happen before diagnostics, persistence, or
                // any Binder-backed reads. Those operations can be slow
                // or fail after native generation has already completed.
                settleGenerationUi(event.stats)
                if (!generationStillOwnsUi()) return@collect
                try {
                    flushPendingAssistantOutput(generationRunId)
                    if (!generationStillOwnsUi()) return@collect
                    if (event.toolCalls.isNotEmpty()) {
                        val handled = handleAssistantImageToolCalls(
                            calls = event.toolCalls,
                            originalRequestMessages = requestMessages,
                            paramsBeforeAssistant = requestParams,
                            chatSessionId = initialState.activeChatSessionId,
                            cloudModelId = cloudModelIdForToolTurn,
                            chatModelIdentity = chatModelIdentityForToolTurn,
                            imageModelBinding = imageModelBindingForToolTurn,
                            chatConfig = chatConfigForToolTurn
                        )
                        if (handled) {
                            persistChatSessions(generationRunId = generationRunId)
                            return@collect
                        }
                    }
                    val requestId = localUiRequestId
                    val sequence = if (requestId == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            LocalApiRuntime.generationSequence()
                        }
                    }
                    if (!generationStillOwnsUi()) return@collect
                    if (requestId != null && sequence != null) {
                        // Pair the UI-owned request id with the native
                        // sequence for the redacted acceptance trace.
                        // The context id is also the one registered by
                        // LocalApiRuntime.streamChat above.
                        LocalApiRuntime.recordGenerationSequence(requestId, sequence)
                    }
                    if (!generationStillOwnsUi()) return@collect
                    val citationAudit = applyWebSearchAnswerGuardsToLastAssistant(generationRunId)
                    if (citationAudit != null) {
                        recordWebSearchCitationAudit(
                            webSearchTurn.diagnostic?.id,
                            citationAudit,
                            generationRunId
                        )
                    }
                    if (!generationStillOwnsUi()) return@collect
                    ensureVisibleAssistantReplyAfterCompletion(generationRunId)
                    if (summaryCandidate != null && generationStillOwnsUi() &&
                        admittedRequestMessages.any { it.id == "summary-${summaryCandidate.id}" }
                    ) {
                        val activated = generationUiPublisher.publishContextSummaryActivation(
                            candidate = summaryCandidate,
                            scope = summaryScope,
                            expectedSessionId = initialState.activeChatSessionId,
                            frozenSessionId = frozenSession.id,
                            frozenAssistantId = frozenAssistantId,
                            frozenAssistantSnapshot = frozenAssistantSnapshot
                        )
                        if (activated) {
                            localConversationContextNeedsInvalidation = true
                            localConversationContextInvalidationSequence.incrementAndGet()
                        }
                    }
                    val logs = if (state.selectedChatBackend == ChatBackend.LOCAL) {
                        withContext(Dispatchers.IO) { engine.recentLogs() }
                    } else {
                        null
                    }
                    if (!generationStillOwnsUi()) return@collect
                    if (logs != null && generationStillOwnsUi()) {
                        generationUiPublisher.publishRuntimeLogs(logs)
                    }
                    if (!generationStillOwnsUi()) return@collect
                    val conversationPersistJob = persistChatSessions(generationRunId = generationRunId)
                    if (event.toolCalls.isEmpty() && characterMemoryEnabled && assistantSnapshot != null) {
                        val completedState = uiState.value
                        val expectedReplyId = initialState.messages.lastOrNull {
                            it.role == Role.ASSISTANT
                        }?.id
                        val reply = completedState.messages.lastOrNull {
                            it.role == Role.ASSISTANT && it.id == expectedReplyId
                        }
                        val user = requestMessages.lastOrNull { it.role == Role.USER }
                        val sessionId = initialState.activeChatSessionId
                        if (reply != null && user != null && sessionId != null &&
                            completedState.activeChatSessionId == sessionId
                        ) {
                            recordCompletedCharacterMemoryTurn(
                                assistantId = assistantSnapshot.assistantId,
                                sessionId = sessionId,
                                assistantSnapshot = assistantSnapshot,
                                user = user,
                                assistant = reply,
                                conversationPersistJob = conversationPersistJob,
                                backend = effectiveChatBackend,
                                params = requestParams,
                                cloudConfig = chatConfigForToolTurn,
                                localIdentity = activeRuntimeIdentity?.identityHash
                            )
                        }
                    }
                    continuationCompleted = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    continuationFailureMessage = "角色回复收尾失败：${error.message ?: "未知错误"}"
                    settleGenerationUi(
                        event.stats,
                        "生成已完成，但收尾处理失败：${error.message ?: "未知错误"}"
                    )
                }
            }
            is GenerateEvent.Error -> {
                if (!generationStillOwnsUi()) return@collect
                chatRuns.markPhase(
                    generationRunId,
                    selfJob,
                    UiGenerationRuntimePhase.TERMINAL
                )
                terminalEventSeen = true
                settleGenerationUi(event.stats, event.message)
                continuationFailureMessage = event.message.ifBlank { continuationFailureMessage }
                if (!generationStillOwnsUi()) return@collect
                try {
                    flushPendingAssistantOutput(generationRunId)
                    if (!generationStillOwnsUi()) return@collect
                    val requestId = localUiRequestId
                    val sequence = if (requestId == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            LocalApiRuntime.generationSequence()
                        }
                    }
                    if (!generationStillOwnsUi()) return@collect
                    if (requestId != null && sequence != null) {
                        LocalApiRuntime.recordGenerationSequence(requestId, sequence)
                    }
                    if (!generationStillOwnsUi()) return@collect
                    if (isConfigurationActionError(event)) {
                        removePendingAssistantPlaceholder(generationRunId)
                    } else {
                        appendAssistant(
                            "\n${event.message}",
                            generationRunId = generationRunId
                        )
                    }
                    if (!generationStillOwnsUi()) return@collect
                    persistChatSessions(generationRunId = generationRunId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    continuationFailureMessage = "角色回复收尾失败：${error.message ?: "未知错误"}"
                    settleGenerationUi(
                        event.stats,
                        "${event.message}；生成收尾失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        chatRuns.markPhase(
            generationRunId,
            selfJob,
            UiGenerationRuntimePhase.TERMINAL
        )
        terminalEventSeen = true
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: error::class.java.simpleName
        continuationFailureMessage = "角色回复失败：$message"
        val stats = engine.stats.value.copy(lastError = message)
        settleGenerationUi(stats, "生成失败：$message")
        if (!generationStillOwnsUi()) return
        try {
            flushPendingAssistantOutput(generationRunId)
            if (!generationStillOwnsUi()) return
            appendAssistant(
                "\n生成失败：$message",
                forcePublish = true,
                generationRunId = generationRunId
            )
            if (!generationStillOwnsUi()) return
            persistChatSessions(generationRunId = generationRunId)
        } catch (finalizationError: CancellationException) {
            throw finalizationError
        } catch (_: Throwable) {
            // The UI has already been released; do not let a secondary
            // persistence failure leave the generation lock behind.
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            preparedUiVisionFiles.forEach { file -> runCatching { file.delete() } }
            preparedUiVisionFiles.clear()
        }
        chatRuns.markPhase(
            generationRunId,
            selfJob,
            UiGenerationRuntimePhase.TERMINAL
        )
        if (!terminalEventSeen && generationStillOwnsUi()) {
            val fallbackStats = engine.stats.value
            generationUiPublisher.publishFallbackTermination(
                fallbackStats,
                "生成流已结束，但运行时未返回完成状态，请重试。",
                { replyMetrics.finish() }
            )
            try {
                flushPendingAssistantOutput(generationRunId)
                if (generationStillOwnsUi()) {
                    persistChatSessions(generationRunId = generationRunId)
                }
            } catch (_: Throwable) {
                // The terminal UI state above is intentionally independent
                // from best-effort output persistence.
            }
        }
        continuationRequestId?.let { requestId ->
            finishAssistantImageContinuation(
                requestId = requestId,
                completed = continuationCompleted,
                failureMessage = continuationFailureMessage
            )
        }
    }
}

internal fun attachContextAssemblyTrace(
    uiStateStore: UiStateStore,
    chatRuns: ChatRunOwnership,
    traceJson: String?,
    generationRunId: Long,
    sessionId: String?,
    assistantMessageId: String?
) {
    if (traceJson == null || sessionId == null || assistantMessageId == null ||
        !chatRuns.isCurrent(generationRunId)
    ) return
    uiStateStore.update { state ->
        if (!chatRuns.isCurrent(generationRunId) ||
            state.activeChatSessionId != sessionId
        ) return@update state
        val index = state.messages.indexOfLast {
            it.id == assistantMessageId && it.role == Role.ASSISTANT
        }
        val sessionIndex = state.chatSessions.indexOfFirst { it.id == sessionId }
        if (index < 0 || sessionIndex < 0 ||
            state.chatSessions[sessionIndex].messages.none { it.id == assistantMessageId }
        ) return@update state
        val updated = state.messages.mapIndexed { position, message ->
            if (position == index) message.copy(contextAssemblyTraceJson = traceJson) else message
        }
        state.copy(
            messages = updated,
            chatSessions = state.chatSessions.mapIndexed { position, session ->
                if (position != sessionIndex) session else session.copy(messages = updated)
            }
        )
    }
}

