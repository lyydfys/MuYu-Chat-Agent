package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.ChatImageActionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The owner seam for the chat-page image prompt bridge: resolve the request-scoped lore and
 * knowledge context, run the offline/cloud/local translation passes, then either enqueue the
 * image job, offer the cloud-translation consent, or publish a review draft.
 *
 * Moved verbatim out of `MainViewModel.sendChatImageRequestWithPromptBridge` (P3 step 2). The
 * ViewModel keeps the bridge job slot, the invalidation token, the pending action/translation
 * slots and every collaborator; the orchestration below reaches them through
 * [ChatImagePromptBridgePort] and has no direct field access.
 */
internal interface ChatImagePromptBridgePort {

    fun currentState(): MainUiState

    fun updateUiState(transform: (MainUiState) -> MainUiState)

    fun currentBridgeGeneration(): Long

    fun incrementBridgeGeneration(): Long

    /** Cancels the in-flight bridge job without clearing the slot; [setBridgeJob] replaces it. */
    fun cancelBridgeJob()

    fun setBridgeJob(job: Job)

    fun bridgeScope(): CoroutineScope

    fun setPendingChatImageAction(
        id: String,
        kind: ChatImageActionKind,
        visibleUserText: String,
        requestedOutputCount: Int?,
        stillOwned: () -> Boolean
    )

    fun setPendingImageTranslation(translation: PendingImageTranslation)

    fun showStatus(message: String?)

    fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions

    fun composeChatContext(
        messages: List<ChatMessage>,
        params: GenerationParams,
        assistantId: String,
        chatSessionId: String?,
        knowledgeBaseIds: Set<String>,
        fileContextEnabled: Boolean
    ): ChatRuntimeContextPlan

    fun streamChatCloud(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent>

    fun streamChatLocal(request: ChatRequest, executionContext: LocalChatExecutionContext): Flow<GenerateEvent>

    suspend fun translateOffline(request: OfflinePromptTranslationRequest): OfflinePromptTranslationResolution

    fun sendChatImageRequest(
        visibleUserText: String,
        prompt: String,
        optionsOverride: LocalImageGenerationOptions?,
        promptEnvelope: PromptEnvelope?,
        contextTraceJson: String?
    )

    fun negativePromptSupported(state: MainUiState): Boolean

    // The moved body also reads these private ViewModel extensions; each stays owned by the
    // ViewModel and is exposed to the bridge only through this port.
    fun canWriteToActiveAssistantSession(state: MainUiState): Boolean

    fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig?

    fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig?

    fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord?

    fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot?

    fun selectedAssistant(state: MainUiState): AssistantRecord?
}

/**
     * Image models with an English-dominant text encoder cannot consume a Chinese chat request
     * directly. Keep the user's original text in the chat composer/timeline, and ask the currently
     * selected chat model for a bounded English diffusion prompt before creating the image job.
     * Every explicit image-skill request gets one model pass, including already-English prompts.
     */
internal fun runChatImagePromptBridge(
    port: ChatImagePromptBridgePort,
    visibleUserText: String,
    prompt: String,
    requestedOutputCount: Int? = null,
    allowCloudTranslationFallback: Boolean = false
) {
    val original = prompt.trim()
    val bridgeContextState = port.currentState()
    if (!port.canWriteToActiveAssistantSession(bridgeContextState)) {
        port.showStatus("此会话仅供查看，请先创建角色分支。")
        return
    }
    val initialImageOptions = runCatching {
        port.chatImageOptionsForCurrentSelection(bridgeContextState).let { options ->
            options.copy(batchCount = requestedOutputCount ?: options.batchCount)
        }
    }.getOrElse { error ->
        port.updateUiState { it.copy(input = visibleUserText, statusMessage = error.message.orEmpty()) }
        return
    }
    val originalNegativePrompt = initialImageOptions.negativePrompt
    // This method is reachable only after a chat image skill has been selected. Every
    // non-empty payload, including already-English, JSON, Markdown, and tagged prompts,
    // must pass through the selected chat model before it reaches an image backend.
    val bridgeGeneration = port.incrementBridgeGeneration()
    port.cancelBridgeJob()
    port.updateUiState {
        it.copy(statusMessage = "正在准备图片描述…")
    }
    val bridgeBackend = bridgeContextState.selectedChatBackend
    val bridgeCloudConfig = if (bridgeBackend == ChatBackend.CLOUD) {
        port.selectedChatCloudConfig(bridgeContextState)
            ?.normalized()
            ?.takeIf { config -> config.configured }
    } else {
        null
    }
    val bridgeParams = bridgeContextState.params
    val bridgeImageFingerprint = when (bridgeContextState.selectedImageBackend) {
        ImageBackend.LOCAL -> port.selectedLocalImageModel(bridgeContextState)?.chatImageRetryFingerprint()
        ImageBackend.CLOUD -> port.selectedImageCloudConfig(bridgeContextState)?.chatImageRetryFingerprint()
    }
    fun preparationStillOwned(): Boolean {
        val current = port.currentState()
        val currentImageFingerprint = when (current.selectedImageBackend) {
            ImageBackend.LOCAL -> port.selectedLocalImageModel(current)?.chatImageRetryFingerprint()
            ImageBackend.CLOUD -> port.selectedImageCloudConfig(current)?.chatImageRetryFingerprint()
        }
        return bridgeGeneration == port.currentBridgeGeneration() &&
            current.activeChatSessionId == bridgeContextState.activeChatSessionId &&
            current.messages == bridgeContextState.messages &&
            current.input == bridgeContextState.input &&
            current.selectedAssistantId == bridgeContextState.selectedAssistantId &&
            current.selectedKnowledgeBaseIds == bridgeContextState.selectedKnowledgeBaseIds &&
            current.worldBooks == bridgeContextState.worldBooks &&
            current.selectedChatBackend == bridgeBackend &&
            current.loadedModelId == bridgeContextState.loadedModelId &&
            current.selectedCloudChatModelId == bridgeContextState.selectedCloudChatModelId &&
            port.selectedChatCloudConfig(current) == port.selectedChatCloudConfig(bridgeContextState) &&
            current.selectedImageBackend == bridgeContextState.selectedImageBackend &&
            currentImageFingerprint == bridgeImageFingerprint
    }
    val bridgeAssistantSnapshot = port.activeAssistantSnapshot(bridgeContextState)
        ?: port.selectedAssistant(bridgeContextState)?.toConversationSnapshot()
    val bridgeAssistantRecord = bridgeAssistantSnapshot?.assistantId
        ?.let { assistantId -> bridgeContextState.assistants.firstOrNull { it.id == assistantId } }
        ?: port.selectedAssistant(bridgeContextState)
    val bridgeAssistantName = bridgeAssistantSnapshot?.name ?: bridgeAssistantRecord?.name
    val bridgeCharacterContext = bridgeAssistantSnapshot?.systemPrompt
        ?: bridgeAssistantRecord?.systemPrompt
    val bridgeConversationContext = bridgeContextState.messages
    port.setBridgeJob(port.bridgeScope().launch {
        var cloudConsentRequired = false
        fun offerCloudTranslationConsent() {
            if (!preparationStillOwned()) return
            val actionId = UUID.randomUUID().toString()
            port.setPendingChatImageAction(
                id = actionId,
                kind = com.muyuchat.feature.chat.ChatImageActionKind.CLOUD_TRANSLATION,
                visibleUserText = visibleUserText,
                requestedOutputCount = requestedOutputCount,
                stillOwned = ::preparationStillOwned
            )
            port.updateUiState { it.copy(
                input = visibleUserText,
                pendingChatImageActionDraft = com.muyuchat.feature.chat.ChatImageActionDraftUi(
                    id = actionId,
                    sourceText = visibleUserText,
                    kind = com.muyuchat.feature.chat.ChatImageActionKind.CLOUD_TRANSLATION,
                    reason = "离线翻译未完成。使用当前云端聊天模型整理描述会发送原文到云端。"
                ),
                statusMessage = null
            ) }
        }
        // Resolve the same request-scoped lore and knowledge context used by ordinary chat.
        // Previously the image bridge only received the assistant system prompt and a few
        // recent turns, so phrases such as “让角色按世界书设定自拍” silently lost the
        // selected World Book/knowledge entries. Compose it off the UI dispatcher and pass it
        // as bounded inert reference data; the bridge's system contract still treats it as
        // data and never executes instructions found inside imported documents.
        val bridgeContextPlan = try {
            withContext(Dispatchers.IO) {
                port.composeChatContext(
                    // Include the current image request in the retrieval query. Using only
                    // the previous turns made a newly selected knowledge base appear empty
                    // for prompts such as “让角色按这条设定自拍”.
                    messages = bridgeConversationContext + ChatMessage(Role.USER, original),
                    params = bridgeParams,
                    assistantId = bridgeAssistantSnapshot?.assistantId
                        ?: bridgeContextState.selectedAssistantId.orEmpty(),
                    chatSessionId = bridgeContextState.activeChatSessionId,
                    knowledgeBaseIds = bridgeContextState.selectedKnowledgeBaseIds,
                    fileContextEnabled = bridgeAssistantSnapshot?.fileContextEnabled
                        ?: bridgeAssistantRecord?.fileContextEnabled
                        ?: true
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (preparationStillOwned()) {
                port.updateUiState { it.copy(
                    input = visibleUserText,
                    statusMessage = "图片上下文检索失败，未启动生图：${error.message ?: "请重试"}"
                ) }
            }
            return@launch
        }
        val bridgeRuntimeContext = bridgeContextPlan.runtimeSystemContext
        val bridgeContextTraceJson = withContext(Dispatchers.IO) {
            bridgeContextPlan.trace?.toJsonString()
        }

        suspend fun bridgeStreamFor(value: String, systemPrompt: String): Flow<GenerateEvent>? {
            val request = ChatImagePromptBridge.chatImagePromptBridgeRequest(
                value,
                bridgeParams,
                systemPrompt = systemPrompt,
                assistantName = bridgeAssistantName,
                characterContext = bridgeCharacterContext,
                conversationContext = bridgeConversationContext,
                runtimeContext = bridgeRuntimeContext,
                protectNumericLiterals = false
            )
            return runCatching {
                when (bridgeBackend) {
                    ChatBackend.CLOUD -> {
                        bridgeCloudConfig?.let { config ->
                            port.streamChatCloud(config, request)
                        }
                    }
                    ChatBackend.LOCAL -> {
                        val executionContext = LocalChatExecutionContext(
                            requestId = "ui-image-prompt-${UUID.randomUUID()}"
                        )
                        port.streamChatLocal(request, executionContext)
                    }
                }
            }.getOrNull()
        }
        suspend fun translateOne(value: String): ChatImagePromptBridge.Result {
            if (value.containsHanScript()) {
                val parts = parseChatImagePromptInput(value)
                val offlineRequest = runCatching {
                    OfflinePromptTranslationRequest(
                        sourceText = parts.positivePrompt,
                        negativePrompt = parts.negativePrompt.orEmpty()
                    )
                }.getOrNull()
                if (offlineRequest != null) {
                    when (val offline = port.translateOffline(offlineRequest)) {
                        is OfflinePromptTranslationResolution.Translated -> {
                            return ChatImagePromptBridge.Result.Prepared(
                                originalPrompt = value,
                                effectivePrompt = offline.translatedPrompt,
                                translated = true,
                                effectiveNegativePrompt = offline.effectiveNegativePrompt
                            )
                        }
                        is OfflinePromptTranslationResolution.Fallback -> {
                            if (bridgeBackend == ChatBackend.CLOUD && !allowCloudTranslationFallback) {
                                cloudConsentRequired = true
                                return ChatImagePromptBridge.Result.Failed(
                                    ChatImagePromptBridge.Code.MODEL_ERROR,
                                    "离线翻译不可用，等待确认是否使用云端整理。"
                                )
                            }
                        }
                    }
                }
                if (offlineRequest == null && bridgeBackend == ChatBackend.CLOUD &&
                    !allowCloudTranslationFallback
                ) {
                    cloudConsentRequired = true
                    return ChatImagePromptBridge.Result.Failed(
                        ChatImagePromptBridge.Code.MODEL_ERROR,
                        "离线翻译输入无效，等待确认是否使用云端整理。"
                    )
                }
            }
            val firstStream = bridgeStreamFor(value, ChatImagePromptBridge.SYSTEM_PROMPT)
                ?: return ChatImagePromptBridge.Result.Failed(
                    ChatImagePromptBridge.Code.MODEL_ERROR,
                    "当前聊天模型不可用，无法整理图片描述。"
                )
            var translated = ChatImagePromptBridge.translateChatImagePrompt(
                value,
                firstStream,
                requireModelSummary = true
            )
            if (translated is ChatImagePromptBridge.Result.Failed) {
                bridgeStreamFor(value, ChatImagePromptBridge.FALLBACK_SYSTEM_PROMPT)?.let { retryStream ->
                    translated = ChatImagePromptBridge.translateChatImagePrompt(
                        value,
                        retryStream,
                        requireModelSummary = true
                    )
                }
            }
            if (translated is ChatImagePromptBridge.Result.Prepared &&
                ChatImagePromptBridge.chatImagePromptNeedsDetailRepair(
                    value,
                    translated as ChatImagePromptBridge.Result.Prepared
                )
            ) {
                val repairStream = bridgeStreamFor(
                    value,
                    ChatImagePromptBridge.DETAIL_REPAIR_SYSTEM_PROMPT
                )
                translated = if (repairStream == null) {
                    ChatImagePromptBridge.Result.Failed(
                        ChatImagePromptBridge.Code.DETAIL_LOSS,
                        "聊天模型把复杂图片描述压缩成了过短摘要，无法取得完整修复结果。"
                    )
                } else {
                    val repaired = ChatImagePromptBridge.translateChatImagePrompt(
                        value,
                        repairStream,
                        requireModelSummary = true
                    )
                    when {
                        repaired is ChatImagePromptBridge.Result.Prepared &&
                            !ChatImagePromptBridge.chatImagePromptNeedsDetailRepair(value, repaired) ->
                            repaired
                        repaired is ChatImagePromptBridge.Result.Failed -> repaired
                        else -> ChatImagePromptBridge.Result.Failed(
                            ChatImagePromptBridge.Code.DETAIL_LOSS,
                            "聊天模型仍未保留复杂图片描述的主体、动作或构图细节。"
                        )
                    }
                }
            }
            return translated
        }

        suspend fun translateConfiguredNegative(value: String?): ChatImagePromptBridge.Result {
            if (value.isNullOrBlank() || !value.containsHanScript()) {
                // The configured negative branch is already safe to send when it is ASCII.
                // It is still merged with the model-produced negative branch below; only a
                // Chinese configured default needs its own model pass.
                return ChatImagePromptBridge.Result.Prepared(
                    originalPrompt = value.orEmpty(),
                    effectivePrompt = value.orEmpty(),
                    translated = false,
                    effectiveNegativePrompt = value
                )
            }
            // A negative-only prompt is intentionally rejected by the bridge because it
            // cannot produce an image on its own. Wrap it with a neutral positive subject so
            // the same strict JSON contract can translate the configured negative branch.
            return translateOne("neutral image\nNegative prompt: $value")
        }

        // This function is entered only after the image skill has been selected. The
        // complete payload therefore always goes through the current chat model, regardless
        // of whether it is English, Chinese, JSON, Markdown, a code block, a Tavern preset,
        // or ordinary prose. Do not add a fast path here: it would silently reintroduce the
        // old bug where only a narrow prompt dialect was normalized.
        val translatedResult = translateOne(original)
        val recovery = ChatImagePromptBridge.chatImagePromptRecovery(original, nativeMultilingual = false)
        val originalRecoveryOffered = translatedResult is ChatImagePromptBridge.Result.Failed &&
            recovery.canUseOriginal
        val result = if (originalRecoveryOffered) {
            ChatImagePromptBridge.Result.Prepared(
                originalPrompt = original,
                effectivePrompt = recovery.originalPositive,
                effectiveNegativePrompt = recovery.originalNegative,
                translated = false
            )
        } else translatedResult
        // A provider may deliver a final chunk after coroutine cancellation.  Check both
        // cancellation and the generation token before publishing anything or enqueueing a
        // request, otherwise two rapid taps can start two image jobs.
        currentCoroutineContext().ensureActive()
        if (!preparationStillOwned()) return@launch
        when (result) {
            is ChatImagePromptBridge.Result.Prepared -> {
                currentCoroutineContext().ensureActive()
                if (!preparationStillOwned()) return@launch
                if (port.currentState().isGenerating) {
                    port.showStatus("当前对话已开始生成，图片请求未发送。")
                } else {
                    val translatedNegative = if (originalNegativePrompt.isNullOrBlank()) {
                        null
                    } else {
                        currentCoroutineContext().ensureActive()
                        if (!preparationStillOwned()) return@launch
                        when (val negativeResult = translateConfiguredNegative(originalNegativePrompt)) {
                            is ChatImagePromptBridge.Result.Prepared -> {
                                currentCoroutineContext().ensureActive()
                                if (!preparationStillOwned()) return@launch
                                // A negative-only translation request is wrapped with a
                                // neutral positive subject so the same JSON contract can be
                                // used.  Never fall back to that neutral positive text as a
                                // negative prompt: doing so silently turns a missing
                                // translation into an exclusion such as "neutral image".
                                val translated = negativeResult.effectiveNegativePrompt
                                    ?.trim()
                                    ?.takeIf(String::isNotBlank)
                                if (translated == null) {
                                    port.updateUiState {
                                        it.copy(
                                            input = visibleUserText,
                                            statusMessage = "聊天模型未返回可用的英文负面图片描述，未启动生图。请重试。"
                                        )
                                    }
                                    return@launch
                                }
                                translated
                            }
                            is ChatImagePromptBridge.Result.Failed -> {
                                if (cloudConsentRequired) offerCloudTranslationConsent()
                                else port.updateUiState {
                                    it.copy(
                                        input = visibleUserText,
                                        statusMessage = "聊天模型未能整理负面图片描述，未启动生图。请重试。原因：${negativeResult.message}"
                                    )
                                }
                                return@launch
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!preparationStillOwned()) return@launch
                    val handoff = runCatching { ChatImagePromptBridge.chatImagePromptBridgeHandoff(
                        result = result,
                        baseOptions = initialImageOptions,
                        originalNegativePrompt = originalNegativePrompt,
                        translatedModelNegativePrompt = translatedNegative,
                        nativeMultilingual = false,
                        allowNegativePrompt = port.negativePromptSupported(bridgeContextState)
                    ) }.getOrElse { error ->
                        port.updateUiState {
                            it.copy(input = visibleUserText, statusMessage = error.message ?: "图片描述无效，草稿已保留。")
                        }
                        return@launch
                    }
                    if (!preparationStillOwned()) return@launch
                    if (originalRecoveryOffered || result.translated &&
                        (original.containsHanScript() || originalNegativePrompt.orEmpty().containsHanScript())) {
                        val draftId = UUID.randomUUID().toString()
                        port.setPendingImageTranslation(PendingImageTranslation(
                            draftId, visibleUserText, handoff, bridgeContextTraceJson,
                            ::preparationStillOwned
                        ))
                        val originalParts = parseChatImagePromptInput(original)
                        port.updateUiState { it.copy(
                            input = visibleUserText,
                            pendingChatImageTranslationDraft = com.muyuchat.feature.chat.ChatImageTranslationDraftUi(
                                id = draftId,
                                originalPositive = originalParts.positivePrompt,
                                originalNegative = originalParts.negativePrompt ?: originalNegativePrompt,
                                proposedPositive = handoff.prompt,
                                proposedNegative = handoff.options.negativePrompt,
                                sourceLabel = if (originalRecoveryOffered) {
                                    "原始描述（整理失败，请确认后继续）"
                                } else {
                                    "离线模型或当前聊天模型整理的英文草稿"
                                }
                            ),
                            statusMessage = "译文草稿已准备，确认后生成。"
                        ) }
                        return@launch
                    }
                    port.sendChatImageRequest(
                        visibleUserText = visibleUserText,
                        prompt = handoff.prompt,
                        optionsOverride = handoff.options,
                        promptEnvelope = handoff.envelope,
                        contextTraceJson = bridgeContextTraceJson
                    )
                    if (result.translated) {
                        port.updateUiState {
                            it.copy(statusMessage = "已由当前聊天模型整理英文图片描述，开始生成。")
                        }
                    }
                }
            }
            is ChatImagePromptBridge.Result.Failed -> {
                if (cloudConsentRequired) offerCloudTranslationConsent()
                else port.updateUiState {
                    it.copy(
                        input = visibleUserText,
                        statusMessage = "聊天模型未能整理出可执行的图片描述，未启动生图。请重试。原因：${result.message}"
                    )
                }
            }
        }
    })
}
