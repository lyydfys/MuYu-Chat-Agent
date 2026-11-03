package com.muyuchat.mca

import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.ContextCompressionResult
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.RuntimeStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.muyuchat.mca.presentation.ChatContextOperations
import com.muyuchat.mca.presentation.ChatGenerationUiPublisher
import com.muyuchat.mca.presentation.afterGenerationTerminated

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
        val metrics = replyMetrics.finish()
        generationUiWriter.update { state ->
            if (!generationStillOwnsUi()) {
                state
            } else {
                state.afterGenerationTerminated(stats, statusMessage)
                    .withReplyGenerationMetrics(metrics)
            }
        }
    }

    val generationUiPublisher = ChatGenerationUiPublisher(chatRuns, generationRunId, generationUiWriter)

    try {
    if (!generationStillOwnsUi()) return
    val initialState = uiState.value
    if (!canWriteToActiveAssistantSession(initialState)) {
        terminalEventSeen = true
        settleGenerationUi(engine.stats.value, "此对话的角色归属不明确，无法继续生成；请先手动分支。")
        return
    }
    val frozenSession = initialState.chatSessions.firstOrNull {
        it.id == initialState.activeChatSessionId
    }
    // The active session id can point at a session that is no longer in the list (a store read
    // that failed mid-save, a deleted session restored from an old snapshot, or a concurrent
    // reset). `first {}` threw NoSuchElementException here and killed the process on send.
    val frozenAssistantSnapshot = frozenSession?.assistantSnapshot
    if (frozenSession == null || frozenAssistantSnapshot == null) {
        terminalEventSeen = true
        settleGenerationUi(
            engine.stats.value,
            "当前会话缺少角色信息，无法继续生成；请新建对话或从历史中重新打开该会话。"
        )
        return
    }
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
        recoveryJobToAwait(foregroundRecoveryJob, selfJob)?.join()
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
    val compression = summarizeTurnContextFor(manualCompression, compressionThreshold, preflightCompression, requestMessages, requestParams, characterMemoryContextForTurn, initialState, effectiveChatBackend, chatModelIdOverride, generationRunId, allowSummaryCandidate)
    if (effectiveChatBackend == ChatBackend.LOCAL && !ensureLocalConversationContextInvalidated()) return
    if (!generationStillOwnsUi()) return
    val summaryTurn = selectTurnSummaryFor(requestMessages, requestParams, assistantSnapshot, summaryRecords, compression, allowSummaryCandidate, generationUiPublisher)
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
            messages = summaryTurn.effectiveMessages,
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
    publishTurnContextStatusFor(initialState, assistantSnapshot, runtimeContextPlan, generationUiPublisher)
    val webSearchTurn = runWebSearchTurnFor(initialState, generationUiPublisher, summaryTurn.effectiveMessages, requestMessages, assistantSnapshot, generationRunId)
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
    publishWebSearchOutcomeFor(webSearchTurn, generationUiPublisher)
    val contextAdmission = admitTurnContextFor(summaryTurn.effectiveMessages, requestParams, requestMessages, runtimeSystemContextForTurn)
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
    publishPromptContextUsageFor(runtimeContextPlan, contextAdmission, requestParams, generationUiPublisher)
    // Admission may deterministically trim older turns after the
    // compression pass.  Use that exact list for every provider; the
    // full list remains in the persisted session for future summaries.
    val admittedRequestMessages = contextAdmission.request.messages
    // Live KV invalidation changes on every role switch. Keep the disk identity
    // stable for a return visit; native token-prefix validation rejects edited history.
    val frozenContextRevision = frozenContextRevisionFor(frozenSession.id, summaryRecords, summaryTurn.candidate)
    attachTurnContextAssemblyTraceFor(runtimeContextPlan, generationRunId, initialState)
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
        // P03: register the exact engine request before it is admitted or dispatched. It is frozen
        // here, before the run becomes stoppable, so the user stop never has to infer its target
        // from whichever token the engine happens to own later. The id encodes this run id and a
        // fresh UUID, so it can never name another run's attempt.
        val registered = chatRuns.markLocalAttempt(
            generationRunId,
            selfJob,
            ChatAttemptIdentity(generationRunId, uiRequestId)
        )
        if (!registered) return
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
        ) ?: error("The single chat execution facade is not attached to LocalApiRuntime")
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
                        // Tool turns verify/refresh local image engines, which can read and hash
                        // multi-gigabyte model components. Keep that work off the UI thread: the
                        // generation collector runs on main, and a first-run image proof used to
                        // freeze the surface here.
                        val handled = withContext(Dispatchers.IO) {
                            handleAssistantImageToolCalls(
                                calls = event.toolCalls,
                                originalRequestMessages = requestMessages,
                                paramsBeforeAssistant = requestParams,
                                chatSessionId = initialState.activeChatSessionId,
                                cloudModelId = cloudModelIdForToolTurn,
                                chatModelIdentity = chatModelIdentityForToolTurn,
                                imageModelBinding = imageModelBindingForToolTurn,
                                chatConfig = chatConfigForToolTurn
                            )
                        }
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
    recordCitationAuditFor(citationAudit, webSearchTurn, generationRunId)
                    if (!generationStillOwnsUi()) return@collect
                    ensureVisibleAssistantReplyAfterCompletion(generationRunId)
    publishSummaryActivationFor(summaryTurn.candidate, admittedRequestMessages, summaryTurn.scope, generationRunId, initialState, frozenSession, frozenAssistantId, frozenAssistantSnapshot, generationUiPublisher)
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
    recordCompletedTurnMemoryFor(event.toolCalls, characterMemoryEnabled, assistantSnapshot, initialState, requestMessages, effectiveChatBackend, requestParams, chatConfigForToolTurn, generationRunId)
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
    appendErrorOutcomeFor(event, generationRunId)
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
