package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.composition.McaAppContainer
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

internal class AssistantImageToolContinuationCoordinator(
    private val store: UiStateStore,
    private val admission: RuntimeAdmissionState,
    private val container: McaAppContainer,
    private val cloudChatProvider: OpenAiCompatibleChatProvider,
    private val chatContextComposer: ChatContextComposer,
    private val chatImageRequestPort: () -> ChatImageRequestPort,
    private val chatImageRequestApprovalPort: () -> ChatImageRequestApprovalPort,
    private val persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    private val buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
    private val chatImageNegativePromptSupported: (MainUiState) -> Boolean,
    private val continueAssistantImageToolTurn: (String, String) -> Unit,
    private val cancelImageGenerationForJob: (String?) -> Unit
) {
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
                approveChatImageRequest(port = chatImageRequestApprovalPort(), requestId = requestId)
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
            continueTurn = continueAssistantImageToolTurn,
            jobId = jobId
        )
    }

    fun continueAssistantImageTurn(requestId: String) {
        continueAssistantImageTurnFor(
            store = store,
            findRequest = { findChatGeneratedImageRequestIn(store.current, it) },
            continueTurn = continueAssistantImageToolTurn,
            requestId = requestId
        )
    }

    fun cancelChatImageGeneration(requestId: String) {
        cancelChatImageGenerationFor(
            store = store,
            findRequest = { findChatGeneratedImageRequestIn(store.current, it) },
            rejectRequest = { rejectChatImageRequest(chatImageRequestApprovalPort(), it) },
            cancelGenerationForJob = cancelImageGenerationForJob,
            requestId = requestId
        )
    }

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
