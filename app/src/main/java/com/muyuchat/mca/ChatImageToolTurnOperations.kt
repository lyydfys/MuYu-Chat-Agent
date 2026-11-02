package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun setAssistantImageToolAutoApprovalFor(
    store: UiStateWriter<ConversationStatusFields>,
    scope: CoroutineScope,
    chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore,
    chatImageToolPermissionUiState: () -> ChatImageToolPermissionUiState,
    showStatus: (String) -> Unit,
    enabled: Boolean
) {
    val state = store.state.value
    val permission = chatImageToolPermissionUiState()
    if (!permission.available) {
        showStatus(permission.unavailableReason)
        return
    }
    val sessionId = state.activeChatSessionId
    val chatModelId = state.selectedCloudChatModelId
    val chatModelIdentity = chatImageToolChatModelIdentity(
        chatModelId,
        state.selectedChatCloudConfig()?.normalized()
    )
    val imageModelIdentity = state.chatImageModelBinding()?.authorizationIdentity()
    if (sessionId.isNullOrBlank() || chatModelIdentity == null || imageModelIdentity == null) {
        store.update {
            it.copy(statusMessage = "先发送一条消息创建对话，再设置本对话的角色生图授权。")
        }
        return
    }
    val target = if (enabled) {
        ChatImageToolAuthorizationMode.AUTO_APPROVE
    } else {
        ChatImageToolAuthorizationMode.ASK_EVERY_TIME
    }
    scope.launch(Dispatchers.IO) {
        val saved = chatImageToolAuthorizationStore.setMode(
            sessionId,
            chatModelIdentity,
            imageModelIdentity,
            target
        )
        store.update { current ->
            val currentChatIdentity = chatImageToolChatModelIdentity(
                current.selectedCloudChatModelId,
                current.snapshot.selectedChatCloudConfig()?.normalized()
            )
            val currentImageIdentity = current.snapshot.chatImageModelBinding()?.authorizationIdentity()
            if (current.activeChatSessionId != sessionId ||
                currentChatIdentity != chatModelIdentity ||
                currentImageIdentity != imageModelIdentity
            ) {
                current
            } else {
                current.copy(
                    statusMessage = if (saved) {
                        if (target == ChatImageToolAuthorizationMode.AUTO_APPROVE) {
                            "已允许当前对话中的 Responses 角色请求自动生图；可随时改回每次询问。"
                        } else {
                            "已改为每次询问；之后的角色生图请求会先等待你确认。"
                        }
                    } else {
                        "授权设置未能保存，请稍后重试。"
                    }
                )
            }
        }
    }
}

internal suspend fun prepareAssistantImagePromptFor(
    store: UiStateReadPort,
    cloudChatProvider: OpenAiCompatibleChatProvider,
    chatContextComposer: ChatContextComposer,
    prompt: String,
    chatConfig: CloudApiConfig?,
    params: GenerationParams,
    conversationContext: List<ChatMessage> = store.state.value.messages
): ChatImagePromptBridge.Result {
    val config = chatConfig?.takeIf { it.configured }
        ?: return ChatImagePromptBridge.Result.Failed(
            ChatImagePromptBridge.Code.MODEL_ERROR,
            "当前聊天模型配置不可用。请恢复聊天模型后重试。"
        )
    val state = store.state.value
    val assistantSnapshot = state.activeAssistantSnapshot()
        ?: state.selectedAssistant()?.toConversationSnapshot()
    val assistantRecord = assistantSnapshot?.assistantId
        ?.let { assistantId -> state.assistants.firstOrNull { it.id == assistantId } }
        ?: state.selectedAssistant()
    val assistantName = assistantSnapshot?.name ?: assistantRecord?.name
    val characterContext = assistantSnapshot?.systemPrompt ?: assistantRecord?.systemPrompt
    val runtimeContext = runCatching {
        withContext(Dispatchers.IO) {
            chatContextComposer.compose(
                messages = conversationContext + ChatMessage(Role.USER, prompt),
                params = params,
                assistantId = assistantSnapshot?.assistantId
                    ?: state.selectedAssistantId.orEmpty(),
                chatSessionId = state.activeChatSessionId,
                knowledgeBaseIds = state.selectedKnowledgeBaseIds,
                fileContextEnabled = assistantSnapshot?.fileContextEnabled
                    ?: assistantRecord?.fileContextEnabled
                    ?: true
            ).runtimeSystemContext
        }
    }.getOrNull()
    val first = ChatImagePromptBridge.translateChatImagePrompt(
        prompt = prompt,
        stream = cloudChatProvider.streamChat(
            config = config,
            request = ChatImagePromptBridge.chatImagePromptBridgeRequest(
                prompt = prompt,
                params = params,
                assistantName = assistantName,
                characterContext = characterContext,
                conversationContext = conversationContext,
                runtimeContext = runtimeContext,
                protectNumericLiterals = false
            )
        ),
        requireModelSummary = true
    )
    if (first is ChatImagePromptBridge.Result.Prepared) return first
    return ChatImagePromptBridge.translateChatImagePrompt(
        prompt = prompt,
        stream = cloudChatProvider.streamChat(
            config = config,
            request = ChatImagePromptBridge.chatImagePromptBridgeRequest(
                prompt,
                params,
                systemPrompt = ChatImagePromptBridge.FALLBACK_SYSTEM_PROMPT,
                assistantName = assistantName,
                characterContext = characterContext,
                conversationContext = conversationContext,
                runtimeContext = runtimeContext,
                protectNumericLiterals = false
            )
        ),
        requireModelSummary = true
    )
}

internal suspend fun prepareAssistantImageNegativePromptFor(
    prepareAssistantImagePrompt: suspend (String, CloudApiConfig?, GenerationParams, List<ChatMessage>) -> ChatImagePromptBridge.Result,
    prompt: String,
    chatConfig: CloudApiConfig?,
    params: GenerationParams,
    conversationContext: List<ChatMessage>,
    preserveAuthoredNegative: Boolean
): ChatImagePromptBridge.Result {
    val source = prompt.trim()
    if (source.isBlank()) {
        return ChatImagePromptBridge.Result.Failed(
            ChatImagePromptBridge.Code.INVALID_INPUT,
            "负面图片描述为空。"
        )
    }
    val neutralized = prepareAssistantImagePrompt(
        "neutral image\nNegative prompt: $source",
        chatConfig,
        params,
        conversationContext
    )
    return when (neutralized) {
        is ChatImagePromptBridge.Result.Prepared -> {
            val translatedNegative = neutralized.effectiveNegativePrompt
                ?.trim()
                ?.takeIf(String::isNotBlank)
            when {
                translatedNegative != null -> neutralized.copy(
                    originalPrompt = source,
                    effectiveNegativePrompt = translatedNegative
                )
                preserveAuthoredNegative && !source.containsHanScript() &&
                    source.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
                    source.isSafeAsciiDiffusionPrompt() -> neutralized.copy(
                    originalPrompt = source,
                    effectiveNegativePrompt = source
                )
                preserveAuthoredNegative -> neutralized.copy(originalPrompt = source)
                else -> neutralized.copy(
                    originalPrompt = source,
                    effectiveNegativePrompt = ""
                )
            }
        }
        is ChatImagePromptBridge.Result.Failed -> {
            if (!source.containsHanScript() &&
                source.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
                source.isSafeAsciiDiffusionPrompt()
            ) {
                ChatImagePromptBridge.Result.Prepared(
                    originalPrompt = source,
                    effectivePrompt = "neutral image",
                    translated = false,
                    effectiveNegativePrompt = source
                )
            } else if (!preserveAuthoredNegative && source.containsHanScript()) {
                ChatImagePromptBridge.Result.Prepared(
                    originalPrompt = source,
                    effectivePrompt = "neutral image",
                    translated = false,
                    effectiveNegativePrompt = ""
                )
            } else {
                neutralized
            }
        }
    }
}

internal fun setPendingAssistantToolFailureFor(
    store: UiStateWriter<ConversationMessageFields>,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    message: String
) {
    val state = store.state.value
    val messages = state.messages.toMutableList()
    val pendingIndex = messages.indexOfLast {
        it.role == Role.ASSISTANT && it.content.isBlank() && it.generatedImageRequest == null
    }
    val failedText = "图片请求未执行：$message"
    if (pendingIndex >= 0) {
        messages[pendingIndex] = messages[pendingIndex].copy(content = failedText)
    } else {
        messages += ChatMessage(Role.ASSISTANT, failedText)
    }
    val sessionId = state.activeChatSessionId ?: return
    val assistantSnapshot = state.activeAssistantSnapshot()
        ?: state.selectedAssistant()?.toConversationSnapshot()
    val sessions = state.chatSessions.upsertSessionFor(
        state = state,
        sessionId = sessionId,
        messages = messages,
        assistantId = assistantSnapshot?.assistantId ?: state.selectedAssistantId,
        assistantSnapshot = assistantSnapshot,
        modelMode = state.selectedChatBackend.bindingValue(),
        modelId = state.currentChatModelId()
    )
    store.update {
        it.copy(messages = messages, chatSessions = sessions, statusMessage = message)
    }
    persistChatSessions(sessions)
}

internal fun attachAssistantImageRequestToTimelineFor(
    store: UiStateWriter<ConversationMessageFields>,
    persistChatSessions: (List<ChatSessionRecord>) -> Unit,
    chatSessionId: String,
    request: ChatGeneratedImageRequest
) {
    val state = store.state.value
    val messages = state.messages.toMutableList()
    val pendingIndex = messages.indexOfLast {
        it.role == Role.ASSISTANT && it.content.isBlank() && it.generatedImageRequest == null
    }
    val card = ChatMessage(
        role = Role.ASSISTANT,
        content = request.assistantTranscriptText(),
        generatedImageRequest = request
    )
    if (pendingIndex >= 0) messages[pendingIndex] = card else messages += card
    val assistantSnapshot = state.activeAssistantSnapshot()
        ?: state.selectedAssistant()?.toConversationSnapshot()
    val sessions = state.chatSessions.upsertSessionFor(
        state = state,
        sessionId = chatSessionId,
        messages = messages,
        assistantId = assistantSnapshot?.assistantId ?: state.selectedAssistantId,
        assistantSnapshot = assistantSnapshot,
        modelMode = state.selectedChatBackend.bindingValue(),
        modelId = state.currentChatModelId()
    )
    store.update {
        it.copy(messages = messages, chatSessions = sessions, statusMessage = null)
    }
    persistChatSessions(sessions)
}
