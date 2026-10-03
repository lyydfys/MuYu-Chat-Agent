package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.GenerationParams
import java.util.UUID

/**
 * Everything `MainViewModel.handleAssistantImageToolCalls` needed from the ViewModel.
 *
 * The member moved here as a top-level function so the decision -- which user-visible failure a
 * malformed or unauthorized tool turn produces, what the pending turn records, and when the
 * request is auto-approved -- can be read and tested without a 20,000-line class.
 *
 * The members below are exactly the eleven ViewModel members the body used, and they keep the
 * original names so the moved body can be diffed line by line against `MainViewModel.kt`. A
 * complete port is the point: the body must not read `_uiState`, `viewModelScope`, or any other
 * ViewModel field, because that is what would make the move a lie.
 */
internal interface AssistantImageToolTurnPort {
    /** `_uiState.value.activeChatSessionId`. */
    fun activeChatSessionId(): String?

    /** `chatImageNegativePromptSupported(_uiState.value)`, reached only for a LOCAL backend. */
    fun chatImageNegativePromptSupported(): Boolean

    /** The chat-connector bridge that turns the tool payload into an executable prompt. */
    suspend fun prepareAssistantImagePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>
    ): ChatImagePromptBridge.Result

    /** The negative-branch bridge; `preserveAuthoredNegative` keeps an authored exclusion failing closed. */
    suspend fun prepareAssistantImageNegativePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>,
        preserveAuthoredNegative: Boolean
    ): ChatImagePromptBridge.Result

    /** Writes the visible failure line on the pending assistant message and persists it. */
    fun setPendingAssistantToolFailure(message: String)

    /** The retry snapshot the request's generation options are frozen from; null = model gone. */
    fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec?

    /** `pendingAssistantImageToolTurns[requestId] = ...`. */
    fun registerPendingAssistantImageToolTurn(turn: AssistantImageToolPendingTurn)

    /** Replaces the pending assistant message with the generated-image card. */
    fun attachAssistantImageRequestToTimeline(
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    )

    /**
     * One `_uiState.value` read for the four live facts the auto-approval gate compares.
     *
     * The original read the state once into `currentState` and derived the two identities from that
     * one revision, so this must stay a single read rather than four independent lookups:
     * `activeChatSessionId` and `selectedCloudChatModelId` are compared against the request, and the
     * two identities are the ones the user approved.
     */
    fun currentTurnSelectionSnapshot(): AssistantImageToolTurnSelection

    /** `chatImageToolAuthorizationStore.getMode(...)` for the exact session and identities. */
    fun chatImageToolAuthorizationMode(
        chatSessionId: String?,
        chatModelIdentity: String?,
        imageModelIdentity: String?
    ): ChatImageToolAuthorizationMode

    /** `approveChatImageRequest(requestId)`. */
    fun approveChatImageRequest(requestId: String)
}

/**
 * The selected image model in the narrow shape this turn actually reads.
 *
 * `MainViewModel.AssistantImageModelBinding` is a file-private data class, so it cannot be named
 * here and it must not be widened to `internal` just for this move: nothing outside
 * `MainViewModel.kt` builds one, and a binding is only meaningful together with the private
 * `MainUiState.chatImageModelBinding()` projection that produces it. The six fields the body reads
 * are carried instead; the caller keeps the private type and maps it 1:1.
 *
 * [authorizationIdentity] is a provider, not a value, because the original evaluated
 * `binding.authorizationIdentity()` at the auto-approval gate -- after the request and the pending
 * turn existed. Pre-computing it would move that read earlier for no reason.
 */
internal data class AssistantImageToolModelBinding(
    val backend: ImageBackend,
    val modelId: String,
    val modelName: String,
    val options: LocalImageGenerationOptions,
    val fingerprint: String,
    val authorizationIdentity: () -> String
)

/** `PendingAssistantImageToolTurn` in a shape this file is allowed to name. */
internal data class AssistantImageToolPendingTurn(
    val requestId: String,
    val chatSessionId: String,
    val originalRequestMessages: List<ChatMessage>,
    val params: GenerationParams,
    val cloudModelId: String,
    val toolCalls: List<ChatToolCall>,
    val primaryToolCallId: String,
    val initialToolOutputs: Map<String, String>
)

/** The four live-selection facts the auto-approval gate needs, from one state revision. */
internal data class AssistantImageToolTurnSelection(
    val activeChatSessionId: String?,
    val selectedCloudChatModelId: String?,
    val chatModelIdentity: String?,
    val imageAuthorizationIdentity: String?
)

/**
 * `MainViewModel.handleAssistantImageToolCalls`, moved out unchanged.
 *
 * The body is the original, line for line: the same guards in the same order, the same
 * user-visible wording, the same `return true`/`return false` shape, and the same absence of a
 * catch block -- an exception from the port still propagates to the generation collector exactly
 * as it did from the ViewModel. Only the ViewModel member accesses were replaced by port calls.
 *
 * Returns true when the assistant tool turn was consumed here (the caller must stop and persist),
 * false when there was no tool call to handle.
 */
internal suspend fun handleAssistantImageToolTurn(
    port: AssistantImageToolTurnPort,
    calls: List<ChatToolCall>,
    originalRequestMessages: List<ChatMessage>,
    paramsBeforeAssistant: GenerationParams,
    chatSessionId: String?,
    cloudModelId: String?,
    chatModelIdentity: String?,
    imageModelBinding: AssistantImageToolModelBinding?,
    chatConfig: CloudApiConfig?
): Boolean {
    if (calls.isEmpty()) return false
    if (calls.size != 1) {
        port.setPendingAssistantToolFailure("一次助手回复最多只能请求生成一张图片。")
        return true
    }
    val call = calls.single()
    val args = validateChatImageToolCall(call)
    if (args !is ChatImageToolArguments.Valid) {
        port.setPendingAssistantToolFailure(
            (args as ChatImageToolArguments.Invalid).reason
        )
        return true
    }
    val sessionId = chatSessionId ?: port.activeChatSessionId()
    if (sessionId.isNullOrBlank() || cloudModelId.isNullOrBlank()) {
        port.setPendingAssistantToolFailure("无法确定本次生图请求所属的对话或云端模型。")
        return true
    }
    if (imageModelBinding == null) {
        port.setPendingAssistantToolFailure("请先在模型管理中选择一个可用的生图模型，再发送本轮消息。")
        return true
    }
    val effectivePromptResult = when (val promptResult = port.prepareAssistantImagePrompt(
        prompt = args.prompt,
        chatConfig = chatConfig,
        params = paramsBeforeAssistant,
        conversationContext = originalRequestMessages
    )) {
        is ChatImagePromptBridge.Result.Prepared -> promptResult
        is ChatImagePromptBridge.Result.Failed -> {
            port.setPendingAssistantToolFailure(
                "聊天模型未能把角色的图片描述整理成可执行提示词，未启动生图。请重试角色回复。原因：${promptResult.message}"
            )
            return true
        }
    }
    val inlineNegativePrompt = effectivePromptResult.effectiveNegativePrompt
    // Responses tool calls may carry a separate negative prompt. Translate it through the
    // same chat connector and keep the effective value in the immutable image job options;
    // previously only the positive prompt was bridged, so an English-only encoder rejected
    // Chinese exclusions after the tool call had already been approved.
    val translatedArgumentNegativePrompt = args.negativePrompt?.let { negativePrompt ->
        when (val negativeResult = port.prepareAssistantImageNegativePrompt(
            prompt = negativePrompt,
            chatConfig = chatConfig,
            params = paramsBeforeAssistant,
            conversationContext = originalRequestMessages,
            preserveAuthoredNegative = true
        )) {
            is ChatImagePromptBridge.Result.Prepared ->
                negativeResult.effectiveNegativePrompt?.trim()?.takeIf(String::isNotBlank)
                    ?: run {
                        port.setPendingAssistantToolFailure(
                            "聊天模型未返回可用的英文负面提示词，未启动生图。请重试角色回复。"
                        )
                        return true
                    }
            is ChatImagePromptBridge.Result.Failed -> {
                port.setPendingAssistantToolFailure(
                    "聊天模型未能把角色请求中的负面提示词整理成英文，未启动生图。请重试角色回复。原因：${negativeResult.message}"
                )
                return true
            }
        }
    }
    val modelNegativePrompt = imageModelBinding.options.negativePrompt
    val effectiveModelNegativePrompt = if (translatedArgumentNegativePrompt != null ||
        inlineNegativePrompt != null
    ) {
        // An assistant tool call can carry exclusions in either the structured
        // negative_prompt argument or the structured bridge response. Keep both branches
        // separate from the positive prompt and preserve their order without duplicates.
        mergeChatImageNegativePrompts(
            translatedArgumentNegativePrompt,
            inlineNegativePrompt
        )
    } else if (chatImageNegativePromptNeedsBridge(modelNegativePrompt)) {
        when (val modelNegativeResult = port.prepareAssistantImageNegativePrompt(
            prompt = modelNegativePrompt.orEmpty(),
            chatConfig = chatConfig,
            params = paramsBeforeAssistant,
            conversationContext = originalRequestMessages,
            preserveAuthoredNegative = false
        )) {
            is ChatImagePromptBridge.Result.Prepared ->
                modelNegativeResult.effectiveNegativePrompt?.trim()?.takeIf(String::isNotBlank)
                    // A model default is optional conditioning. If the chat bridge cannot
                    // translate it, the helper returns an empty branch so generation can use
                    // the image backend's built-in default (or no negative conditioning).
            is ChatImagePromptBridge.Result.Failed -> {
                port.setPendingAssistantToolFailure(
                    "聊天模型未能整理生图模型的默认负面提示词，未启动生图。请重试角色回复。原因：${modelNegativeResult.message}"
                )
                return true
            }
        }
    } else {
        modelNegativePrompt
    }
    // The bridge may emit a generic quality branch when the model/profile has no authored
    // default. Keep that branch only for a local topology that actually encodes CFG negatives;
    // otherwise the tool path would bypass the normal chat-image handoff gate and send an
    // unusable negative prompt into a CFG-off worker.
    val toolNegativeSupported = imageModelBinding.backend != ImageBackend.LOCAL ||
        port.chatImageNegativePromptSupported()
    val effectiveImageOptions = imageModelBinding.options.copy(
        negativePrompt = effectiveModelNegativePrompt.takeIf { toolNegativeSupported }
    )
    val requestId = UUID.randomUUID().toString()
    val request = ChatGeneratedImageRequest(
        id = requestId,
        prompt = effectivePromptResult.effectivePrompt,
        status = ChatGeneratedImageStatus.AWAITING_APPROVAL,
        message = "角色请求生成一张图片。",
        origin = ChatGeneratedImageOrigin.ASSISTANT_TOOL,
        backendId = imageModelBinding.backend.name,
        modelId = imageModelBinding.modelId,
        modelName = imageModelBinding.modelName,
        modelFingerprint = imageModelBinding.fingerprint,
        generationOptionsJson = effectiveImageOptions.toJson().toString(),
        toolCallId = call.id,
        toolArgumentsJson = call.argumentsJson,
        toolChatModelId = cloudModelId,
        toolParamsJson = paramsBeforeAssistant.toJson(),
        toolRequestMessageCount = originalRequestMessages.size,
        toolRequestMessagesFingerprint = chatImageToolContextFingerprint(originalRequestMessages)
    )
    val imageSnapshot = port.buildChatImageRetrySnapshot(request, sessionId)
    if (imageSnapshot == null) {
        port.setPendingAssistantToolFailure("所选生图模型已不可用，请先恢复模型后重新发送本轮消息。")
        return true
    }
    val snapshottedRequest = request.copy(
        generationOptionsJson = imageSnapshot.options.toJson().toString()
    )
    port.registerPendingAssistantImageToolTurn(
        AssistantImageToolPendingTurn(
            requestId = requestId,
            chatSessionId = sessionId,
            originalRequestMessages = originalRequestMessages,
            params = paramsBeforeAssistant,
            cloudModelId = cloudModelId,
            toolCalls = listOf(call),
            primaryToolCallId = call.id,
            initialToolOutputs = emptyMap()
        )
    )
    port.attachAssistantImageRequestToTimeline(sessionId, snapshottedRequest)
    val currentSelection = port.currentTurnSelectionSnapshot()
    val expectedImageIdentity = imageModelBinding.authorizationIdentity()
    val currentChatIdentity = currentSelection.chatModelIdentity
    val currentImageIdentity = currentSelection.imageAuthorizationIdentity
    val selectionStillMatchesRequest = currentSelection.activeChatSessionId == sessionId &&
        currentSelection.selectedCloudChatModelId == cloudModelId &&
        currentChatIdentity == chatModelIdentity &&
        currentImageIdentity == expectedImageIdentity
    if (port.chatImageToolAuthorizationMode(
            sessionId,
            chatModelIdentity.takeIf { selectionStillMatchesRequest },
            expectedImageIdentity.takeIf { selectionStillMatchesRequest }
        ) ==
        ChatImageToolAuthorizationMode.AUTO_APPROVE
    ) {
        // Automatic approval is valid only while the exact session, chat configuration,
        // and image backend/model configuration remain the ones the user approved.
        port.approveChatImageRequest(requestId)
    }
    return true
}
