package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import com.muyuchat.mca.composition.McaAppContainer
import com.muyuchat.mca.presentation.withGenerationProgressCleared
import org.json.JSONObject

/**
 * R4-S19：助手生图后继与轮次流转整族下沉。
 *
 * 封装助手生图工具轮次处理、后继状态流转、未决轮次恢复与校验、作业完成回调、以及会话活性判断。
 */
internal fun isChatImageRequestActive(request: ChatGeneratedImageRequest): Boolean =
    request.status in setOf(
        ChatGeneratedImageStatus.AWAITING_APPROVAL,
        ChatGeneratedImageStatus.QUEUED,
        ChatGeneratedImageStatus.GENERATING
    ) || request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING

internal fun hasUnresolvedAssistantImageToolTurnIn(
    state: MainUiState,
    pendingTurns: Map<String, PendingAssistantImageToolTurn>,
    chatSessionId: String?
): Boolean {
    if (chatSessionId.isNullOrBlank()) return false
    val session = state.chatSessions.firstOrNull { it.id == chatSessionId }
    val messages = if (state.activeChatSessionId == chatSessionId) state.messages else session?.messages.orEmpty()
    return messages.any { message ->
        message.generatedImageRequest?.let { request ->
            request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                request.toolContinuationStatus != ChatImageToolContinuationStatus.COMPLETED
        } == true
    } || pendingTurns.values.any { pending ->
        pending.chatSessionId == chatSessionId
    }
}

internal fun restorePendingAssistantImageToolTurnFor(
    state: MainUiState,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    chatSessionId: String,
    request: ChatGeneratedImageRequest
): PendingAssistantImageToolTurn? {
    if (request.origin != ChatGeneratedImageOrigin.ASSISTANT_TOOL ||
        !request.canContinueAssistantImageTurn()
    ) return null
    val callId = request.toolCallId?.takeIf(String::isNotBlank) ?: return null
    val modelId = request.toolChatModelId?.takeIf(String::isNotBlank) ?: return null
    val params = request.toolParamsJson?.let { encoded ->
        runCatching { GenerationParams.fromJson(JSONObject(encoded)) }.getOrNull()
    } ?: return null
    val session = state.chatSessions.firstOrNull { it.id == chatSessionId } ?: return null
    val messages = if (state.activeChatSessionId == chatSessionId) state.messages else session.messages
    val requestIndex = messages.indexOfFirst { it.generatedImageRequest?.id == request.id }
    if (requestIndex < 0) return null
    val requestMessageCount = request.toolRequestMessageCount ?: requestIndex
    if (requestMessageCount !in 0..requestIndex) return null
    val originalMessages = messages.take(requestMessageCount)
    request.toolRequestMessagesFingerprint?.let { expected ->
        if (chatImageToolContextFingerprint(originalMessages) != expected) return null
    }
    val call = ChatToolCall(
        id = callId,
        name = CHAT_IMAGE_TOOL_NAME,
        argumentsJson = request.toolArgumentsJson ?: JSONObject().put("prompt", request.prompt).toString()
    )
    val pending = PendingAssistantImageToolTurn(
        requestId = request.id,
        chatSessionId = chatSessionId,
        originalRequestMessages = originalMessages,
        params = params,
        cloudModelId = modelId,
        toolCalls = listOf(call),
        primaryToolCallId = call.id,
        initialToolOutputs = emptyMap()
    )
    pendingAssistantImageToolTurns[request.id] = pending
    return pending
}

internal fun finishAssistantImageContinuationFor(
    store: UiStateStore,
    chatImageRequestPort: ChatImageRequestPort,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    requestId: String,
    completed: Boolean,
    failureMessage: String
) {
    val owner = findChatGeneratedImageRequestIn(store.current, requestId) ?: return
    val (sessionId, request) = owner
    if (request.toolContinuationStatus != ChatImageToolContinuationStatus.RUNNING) return
    val updated = request.copy(
        toolContinuationStatus = if (completed) {
            ChatImageToolContinuationStatus.COMPLETED
        } else {
            ChatImageToolContinuationStatus.FAILED
        },
        toolContinuationStarted = completed,
        message = if (completed) request.assistantTranscriptText() else failureMessage
    )
    applyChatGeneratedImageRequest(chatImageRequestPort, sessionId, requestId, updated)
    if (completed) {
        pendingAssistantImageToolTurns.remove(requestId)
    }
}

internal fun resumePendingAssistantImageToolTurnIfReadyFor(
    state: MainUiState,
    continueAssistantImageTurn: (String) -> Unit
) {
    val sessionId = state.activeChatSessionId ?: return
    val requestId = state.messages.asSequence()
        .mapNotNull(ChatMessage::generatedImageRequest)
        .firstOrNull { request ->
            request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                request.toolContinuationStatus == ChatImageToolContinuationStatus.NOT_STARTED &&
                request.status in setOf(
                    ChatGeneratedImageStatus.DONE,
                    ChatGeneratedImageStatus.FAILED,
                    ChatGeneratedImageStatus.CANCELLED,
                    ChatGeneratedImageStatus.INTERRUPTED
                )
        }
        ?.id
        ?: return
    continueAssistantImageTurn(requestId)
}

internal fun continueAssistantImageToolTurnFor(
    store: UiStateStore,
    admission: RuntimeAdmissionState,
    chatImageRequestPort: ChatImageRequestPort,
    restorePendingTurn: (String, ChatGeneratedImageRequest) -> PendingAssistantImageToolTurn?,
    reserveUiGenerationStart: () -> UiGenerationReservation?,
    cancelPendingReservation: (UiGenerationReservation) -> Unit,
    showStatus: (String) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    startGeneration: (
        requestMessages: List<ChatMessage>,
        reservation: UiGenerationReservation?,
        toolExchanges: List<ChatToolExchange>,
        paramsOverride: GenerationParams?,
        offerAssistantImageTool: Boolean,
        chatModelIdOverride: String?,
        continuationRequestId: String?
    ) -> Boolean,
    requestId: String,
    outputJson: String
) {
    synchronized(admission.assistantImageContinuationLock) {
        val current = findChatGeneratedImageRequestIn(store.current, requestId)?.second ?: return
        if (!current.canContinueAssistantImageTurn() ||
            !admission.assistantImageContinuationsInFlight.add(requestId)
        ) return
        current
    }
    var reservation: UiGenerationReservation? = null
    var started = false
    try {
        val (sessionId, request) = findChatGeneratedImageRequestIn(store.current, requestId) ?: return
        val pending = admission.pendingAssistantImageToolTurns[requestId]
            ?: restorePendingTurn(sessionId, request)
            ?: run {
                applyChatGeneratedImageRequest(
                    chatImageRequestPort,
                    sessionId,
                    requestId,
                    request.copy(
                        toolOutputJson = outputJson,
                        toolContinuationStatus = ChatImageToolContinuationStatus.COMPLETED,
                        toolContinuationStarted = true,
                        message = "图片已保存，但聊天上下文已变化，已跳过自动续答。你可以继续发送新消息。"
                    )
                )
                admission.pendingAssistantImageToolTurns.remove(requestId)
                return
            }
        val latest = findChatGeneratedImageRequestIn(store.current, requestId)?.second ?: request
        applyChatGeneratedImageRequest(
            chatImageRequestPort,
            sessionId,
            requestId,
            latest.copy(toolOutputJson = outputJson)
        )
        val state = store.current
        if (state.activeChatSessionId != pending.chatSessionId) {
            showStatus("图片任务已结束；请回到原对话查看结果并继续聊天。")
            return
        }
        val cloudModelStillAvailable = state.cloudModels.any {
            it.id == pending.cloudModelId && it.kind == CloudModelKind.CHAT && it.configured
        }
        if (!cloudModelStillAvailable) {
            store.update {
                it.copy(statusMessage = "图片已处理，但原云端聊天模型不可用；请恢复配置后继续角色回复。")
            }
            return
        }
        if (state.isGenerating) {
            store.update {
                it.copy(statusMessage = "图片已完成；当前聊天仍有生成任务，请完成或停止后再继续角色回复。")
            }
            return
        }
        // Reserve foreground UI ownership before persisting RUNNING. A background completion
        // remains resumable and is retried by onAppForegrounded instead of being lost.
        reservation = reserveUiGenerationStart()
        if (reservation == null) {
            store.update {
                it.copy(statusMessage = "图片已完成；返回 MCA 后会继续角色回复。")
            }
            return
        }
        val call = pending.toolCalls.firstOrNull { it.id == pending.primaryToolCallId }
        if (call == null) {
            applyChatGeneratedImageRequest(
                chatImageRequestPort,
                sessionId,
                requestId,
                latest.copy(
                    toolOutputJson = outputJson,
                    toolContinuationStatus = ChatImageToolContinuationStatus.FAILED,
                    toolContinuationStarted = false,
                    message = "找不到本次工具调用记录，无法恢复角色回复。"
                )
            )
            return
        }
        applyChatGeneratedImageRequest(
            chatImageRequestPort,
            pending.chatSessionId,
            requestId,
            latest.copy(
                toolOutputJson = outputJson,
                toolContinuationStatus = ChatImageToolContinuationStatus.RUNNING,
                toolContinuationStarted = true,
                message = latest.assistantTranscriptText()
            )
        )
        val continuationState = store.current
        val messages = continuationState.messages + ChatMessage(Role.ASSISTANT, "")
        val assistantSnapshot = continuationState.activeAssistantSnapshot()
            ?: continuationState.selectedAssistant()?.toConversationSnapshot()
        val sessions = continuationState.chatSessions.upsertSessionFor(
            state = continuationState,
            sessionId = pending.chatSessionId,
            messages = messages,
            assistantId = assistantSnapshot?.assistantId ?: continuationState.selectedAssistantId,
            assistantSnapshot = assistantSnapshot,
            modelMode = ChatBackend.CLOUD.bindingValue(),
            modelId = pending.cloudModelId
        )
        store.update {
            it.copy(
                input = "",
                messages = messages,
                chatSessions = sessions,
                isGenerating = true,
                statusMessage = null
            ).withGenerationProgressCleared()
        }
        persistChatSessions(sessions)
        started = startGeneration(
            pending.originalRequestMessages,
            reservation,
            listOf(ChatToolExchange(call, outputJson)),
            pending.params,
            false,
            pending.cloudModelId,
            requestId
        )
        if (!started) {
            applyChatGeneratedImageRequest(
                chatImageRequestPort,
                pending.chatSessionId,
                requestId,
                latest.copy(
                    toolOutputJson = outputJson,
                    toolContinuationStatus = ChatImageToolContinuationStatus.FAILED,
                    toolContinuationStarted = false,
                    message = "角色回复未能启动，请检查云端模型后重试。"
                )
            )
            removeChatImageContinuationPlaceholderFor(store, persistChatSessions, pending.chatSessionId)
        }
    } finally {
        if (!started) reservation?.let { cancelPendingReservation(it) }
        synchronized(admission.assistantImageContinuationLock) {
            admission.assistantImageContinuationsInFlight.remove(requestId)
        }
    }
}

internal fun removeChatImageContinuationPlaceholderFor(
    store: UiStateStore,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    chatSessionId: String
) {
    var updatedSessions: List<ChatSessionRecord>? = null
    store.update { state ->
        if (state.activeChatSessionId != chatSessionId) return@update state
        val last = state.messages.lastOrNull()
        if (last?.role != Role.ASSISTANT || last.content.isNotBlank() ||
            last.generatedImageRequest != null
        ) return@update state
        val messages = state.messages.dropLast(1)
        val owner = state.chatSessions.firstOrNull { it.id == chatSessionId } ?: return@update state
        val sessions = state.chatSessions.map { session ->
            if (session.id == chatSessionId) {
                owner.copy(messages = messages, updatedAt = System.currentTimeMillis())
            } else session
        }.sortedForHistory()
        updatedSessions = sessions
        state.copy(
            messages = messages,
            chatSessions = sessions,
            isGenerating = false
        ).withGenerationProgressCleared()
    }
    updatedSessions?.let(persistChatSessions)
}

internal class AssistantImageToolContinuationCoordinator(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val container: McaAppContainer,
    private val cloudChatProvider: OpenAiCompatibleChatProvider,
    private val chatContextComposer: ChatContextComposer,
    private val imageGenerationOwnerProvider: () -> ImageGenerationOwner,
    private val chatImageRequestPort: () -> ChatImageRequestPort,
    private val persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    private val buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
    private val chatImageNegativePromptSupported: (MainUiState) -> Boolean,
    private val cancelImageGenerationForJob: (String?) -> Unit,
    private val reserveUiGenerationStart: () -> UiGenerationReservation?,
    private val cancelPendingReservation: (UiGenerationReservation) -> Unit,
    private val showStatus: (String) -> Unit,
    private val startGeneration: (
        requestMessages: List<ChatMessage>,
        reservation: UiGenerationReservation?,
        toolExchanges: List<ChatToolExchange>,
        paramsOverride: GenerationParams?,
        offerAssistantImageTool: Boolean,
        chatModelIdOverride: String?,
        continuationRequestId: String?
    ) -> Boolean
) {
    private val chatImageRequestApprovalPort: ChatImageRequestApprovalPort =
        container.createChatImageRequestApprovalPort(
            store = store,
            imageGenerationOwnerProvider = imageGenerationOwnerProvider,
            chatImageRequestPortProvider = chatImageRequestPort,
            buildChatImageRetrySnapshot = buildChatImageRetrySnapshot,
            continueAssistantImageToolTurn = ::continueAssistantImageToolTurn
        )

    fun continueAssistantImageToolTurn(requestId: String, outputJson: String) {
        continueAssistantImageToolTurnFor(
            store = store,
            admission = admission,
            chatImageRequestPort = chatImageRequestPort(),
            restorePendingTurn = ::restorePendingAssistantImageToolTurn,
            reserveUiGenerationStart = reserveUiGenerationStart,
            cancelPendingReservation = cancelPendingReservation,
            showStatus = showStatus,
            persistChatSessions = persistChatSessions,
            startGeneration = startGeneration,
            requestId = requestId,
            outputJson = outputJson
        )
    }

    suspend fun handleAssistantImageToolCalls(
        calls: List<ChatToolCall>,
        originalRequestMessages: List<ChatMessage>,
        paramsBeforeAssistant: GenerationParams,
        chatSessionId: String?,
        cloudModelId: String?,
        chatModelIdentity: String?,
        imageModelBinding: AssistantImageModelBinding?,
        chatConfig: CloudApiConfig?
    ): Boolean {
        val port = container.createAssistantImageToolTurnPort(
            store = store,
            chatImageNegativePromptSupported = chatImageNegativePromptSupported,
            prepareAssistantImagePrompt = { prompt, config, params, ctx ->
                prepareAssistantImagePromptFor(
                    store = store,
                    cloudChatProvider = cloudChatProvider,
                    chatContextComposer = chatContextComposer,
                    prompt = prompt,
                    chatConfig = config,
                    params = params,
                    conversationContext = ctx
                )
            },
            prepareAssistantImageNegativePrompt = { prompt, config, params, ctx, preserve ->
                prepareAssistantImageNegativePromptFor(
                    prepareAssistantImagePrompt = { p, c, par, cx ->
                        prepareAssistantImagePromptFor(
                            store = store,
                            cloudChatProvider = cloudChatProvider,
                            chatContextComposer = chatContextComposer,
                            prompt = p,
                            chatConfig = c,
                            params = par,
                            conversationContext = cx
                        )
                    },
                    prompt = prompt,
                    chatConfig = config,
                    params = params,
                    conversationContext = ctx,
                    preserveAuthoredNegative = preserve
                )
            },
            setPendingAssistantToolFailure = { message ->
                setPendingAssistantToolFailureFor(
                    store = store,
                    persistChatSessions = persistChatSessions,
                    message = message
                )
            },
            buildChatImageRetrySnapshot = buildChatImageRetrySnapshot,
            attachAssistantImageRequestToTimeline = { sId, req ->
                attachAssistantImageRequestToTimelineFor(
                    store = store,
                    persistChatSessions = persistChatSessions,
                    chatSessionId = sId,
                    request = req
                )
            },
            approveChatImageRequest = { requestId ->
                approveChatImageRequest(requestId)
            }
        )
        return handleAssistantImageToolTurn(
            port = port,
            calls = calls,
            originalRequestMessages = originalRequestMessages,
            paramsBeforeAssistant = paramsBeforeAssistant,
            chatSessionId = chatSessionId,
            cloudModelId = cloudModelId,
            chatModelIdentity = chatModelIdentity,
            imageModelBinding = imageModelBinding?.let { binding ->
                AssistantImageToolModelBinding(
                    backend = binding.backend,
                    modelId = binding.modelId,
                    modelName = binding.modelName,
                    options = binding.options,
                    fingerprint = binding.fingerprint,
                    authorizationIdentity = { binding.authorizationIdentity() }
                )
            },
            chatConfig = chatConfig
        )
    }

    fun finishAssistantImageContinuation(
        requestId: String,
        completed: Boolean,
        failureMessage: String
    ) {
        finishAssistantImageContinuationFor(
            store = store,
            chatImageRequestPort = chatImageRequestPort(),
            pendingAssistantImageToolTurns = admission.pendingAssistantImageToolTurns,
            requestId = requestId,
            completed = completed,
            failureMessage = failureMessage
        )
    }

    fun restorePendingAssistantImageToolTurn(
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    ): PendingAssistantImageToolTurn? {
        return restorePendingAssistantImageToolTurnFor(
            state = store.current,
            pendingAssistantImageToolTurns = admission.pendingAssistantImageToolTurns,
            chatSessionId = chatSessionId,
            request = request
        )
    }

    fun completeAssistantImageToolTurnForJob(jobId: String) {
        completeAssistantImageToolTurnForJobFor(
            store = store,
            requestPort = chatImageRequestPort(),
            findRequest = { findChatGeneratedImageRequestIn(store.current, it) },
            continueTurn = ::continueAssistantImageToolTurn,
            jobId = jobId
        )
    }

    fun continueAssistantImageTurn(requestId: String) {
        continueAssistantImageTurnFor(
            store = store,
            findRequest = { findChatGeneratedImageRequestIn(store.current, it) },
            continueTurn = ::continueAssistantImageToolTurn,
            requestId = requestId
        )
    }

    fun cancelChatImageGeneration(requestId: String) {
        cancelChatImageGenerationFor(
            store = store,
            findRequest = { findChatGeneratedImageRequestIn(store.current, it) },
            rejectRequest = { rejectChatImageRequest(it) },
            cancelGenerationForJob = cancelImageGenerationForJob,
            requestId = requestId
        )
    }

    fun retryChatImageGeneration(requestId: String) =
        com.muyuchat.mca.retryChatImageGeneration(port = chatImageRequestApprovalPort, requestId = requestId)

    fun approveChatImageRequest(requestId: String) =
        com.muyuchat.mca.approveChatImageRequest(port = chatImageRequestApprovalPort, requestId = requestId)

    fun rejectChatImageRequest(requestId: String) =
        com.muyuchat.mca.rejectChatImageRequest(port = chatImageRequestApprovalPort, requestId = requestId)

    fun hasUnresolvedAssistantImageToolTurn(chatSessionId: String?): Boolean {
        return hasUnresolvedAssistantImageToolTurnIn(
            state = store.current,
            pendingTurns = admission.pendingAssistantImageToolTurns,
            chatSessionId = chatSessionId
        )
    }

    fun resumePendingAssistantImageToolTurnIfReady() {
        resumePendingAssistantImageToolTurnIfReadyFor(
            state = store.current,
            continueAssistantImageTurn = ::continueAssistantImageTurn
        )
    }
}
