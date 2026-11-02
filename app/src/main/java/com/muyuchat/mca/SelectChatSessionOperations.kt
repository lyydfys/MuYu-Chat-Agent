package com.muyuchat.mca

import com.muyuchat.feature.chat.BrowserTaskStateMachine
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.SelectChatSessionPort
import com.muyuchat.mca.selectChatSession

/**
 * What selecting a conversation needs from its owner.
 *
 * Fourteen members: the state it reads and publishes, the eight operations it sequences, and the
 * handful of persisted settings it has to keep in step. Everything else the selection does is a pure
 * local derivation over the session record, which is why a 106-line member could move without taking
 * state ownership with it.
 */
internal interface SelectChatSessionPort {
    /** The published state. Read once, at the same moment the original read it. */
    fun uiState(): MainUiState

    /** The single atomic publish. */
    fun updateUiState(transform: (ConversationSelectionFields) -> ConversationSelectionFields)

    fun rejectWhileConversationMutationInProgress(): Boolean

    fun hasActiveAssistantImageExecution(sessionId: String?): Boolean

    fun showStatus(message: String)

    fun invalidatePendingChatImagePreparation()

    /** Cancels the in-flight web-search request, if any. */
    fun cancelActiveWebSearch()

    /** Bumps the selection revision so a stale content import cannot commit. */
    fun bumpContentImportSelectionRevision()

    /** The knowledge bases bound to a conversation. */
    fun selectedKnowledgeBaseIdsFor(sessionId: String): Set<String>

    fun saveSelectedAssistantId(assistantId: String)

    fun loadModelGenerationParams(
        backend: ChatBackend,
        modelId: String?,
        defaults: GenerationParams
    ): GenerationParams

    fun persistGenerationParams(params: GenerationParams)

    fun saveSelectedBackend(backend: ChatBackend)

    fun saveSelectedCloudChatModelId(modelId: String)

    /**
     * Normalises a cloud chat config the same way the ViewModel's private extension did.
     *
     * The extension is private to `MainViewModel`, so the normalisation stays on the owner side and
     * the operations file never learns how a config is canonicalised.
     */
    fun normalizedCloudChatConfig(config: CloudApiConfig): CloudApiConfig

    /** Stores the cloud chat config, normalised exactly as the ViewModel's private extension did. */
    fun saveCloudChatConfig(config: CloudApiConfig)

    /** Derives a record's generation parameters, as the ViewModel's private extension did. */
    fun toGenerationParams(assistant: AssistantRecord, defaults: GenerationParams): GenerationParams

    fun isContextCompressionPending(sessionId: String): Boolean

    /** Invalidates the local conversation context so the next local turn rebuilds it. */
    fun markLocalConversationContextInvalid()

    /** Resumes a pending assistant image turn for this conversation, if the tail has one. */
    fun continueAssistantImageTurn(requestId: String)
}

/**
 * Opens a conversation: restores the backend, model and parameters it was using, republishes the
 * message list, and resumes an unfinished assistant image turn.
 *
 * Moved verbatim out of `MainViewModel` (106 lines). The body is the original logic: the same three
 * early-return guards and their exact wording, the same backend/model restoration rules, the same
 * five-way status message, and the same ordering of the single publish relative to the context
 * invalidation and the image-turn resumption. Only the owner interactions became [port] calls.
 *
 * Synchronous on purpose: the original had no suspension point, so adding one would move the publish
 * to a different moment.
 */
internal fun selectChatSession(port: SelectChatSessionPort, sessionId: String) {
        val state = port.uiState()
        if (port.rejectWhileConversationMutationInProgress()) return
        if (port.hasActiveAssistantImageExecution(state.activeChatSessionId) ||
            port.hasActiveAssistantImageExecution(sessionId)
        ) {
            port.showStatus("角色图片请求尚未完成，请先确认、取消或等待任务结束。")
            return
        }
        if (state.isGenerating) {
            port.showStatus("请先停止当前生成，再切换对话")
            return
        }
        val session = state.chatSessions.firstOrNull { it.id == sessionId } ?: return
        port.invalidatePendingChatImagePreparation()
        port.cancelActiveWebSearch()
        port.bumpContentImportSelectionRevision()
        val selectedKnowledgeBaseIds = port.selectedKnowledgeBaseIdsFor(session.id)
        val assistant = (session.assistantSnapshot?.assistantId ?: session.assistantId)
            ?.let { id -> state.assistants.firstOrNull { it.id == id } }
            ?: state.assistants.firstOrNull { it.id == state.selectedAssistantId }
        val sessionBackend = session.modelMode.toChatBackendOrNull()
        val sessionCloudModel = if (sessionBackend == ChatBackend.CLOUD) {
            state.cloudModels.firstOrNull { it.id == session.modelId && it.kind == CloudModelKind.CHAT && it.configured }
        } else {
            null
        }
        val restoreCloud = sessionCloudModel != null
        val restoreLoadedLocal = sessionBackend == ChatBackend.LOCAL &&
            (session.modelId.isNullOrBlank() || session.modelId == state.loadedModelId)
        val restoredBackend = when {
            restoreCloud -> ChatBackend.CLOUD
            restoreLoadedLocal -> ChatBackend.LOCAL
            else -> state.selectedChatBackend
        }
        val restoredModelId = when {
            restoreCloud -> sessionCloudModel?.id
            restoredBackend == ChatBackend.LOCAL -> session.modelId ?: state.loadedModelId
            else -> state.selectedCloudChatModelId
        }
        val modelPreset = port.loadModelGenerationParams(
            backend = restoredBackend,
            modelId = restoredModelId,
            defaults = state.params
        )
        val updatedParams = assistant?.let { record -> port.toGenerationParams(record, modelPreset) }
            ?: modelPreset
        if (assistant != null && assistant.id != state.selectedAssistantId) {
            port.saveSelectedAssistantId(assistant.id)
            port.persistGenerationParams(updatedParams)
        }
        if (sessionCloudModel != null) {
            port.saveSelectedBackend(ChatBackend.CLOUD)
            port.saveSelectedCloudChatModelId(sessionCloudModel.id)
            port.saveCloudChatConfig(sessionCloudModel.toChatConfig())
        } else if (restoreLoadedLocal) {
            port.saveSelectedBackend(ChatBackend.LOCAL)
        }
        val status = when {
            session.mixedAssistantHistory ->
                "混合会话已保留原始消息，仅供查看；请从历史菜单手动创建角色分支。"
            session.assistantId == null || session.assistantSnapshot?.assistantId != session.assistantId ||
                state.assistants.none { it.id == session.assistantId } ->
                "原角色已不存在或会话归属不明确，此会话仅供查看。"
            sessionBackend == ChatBackend.LOCAL &&
                !session.modelId.isNullOrBlank() &&
                session.modelId != state.loadedModelId ->
                "已打开对话；该会话原本使用的本地模型尚未加载，可在顶部模型胶囊中重新加载。"
            sessionBackend == ChatBackend.CLOUD && session.modelId != null && !restoreCloud ->
                "已打开对话；该会话原本使用的云端模型已不可用，请重新选择云端推理引擎。"
            else -> null
        }
        val compressionPending = port.isContextCompressionPending(session.id)
        port.updateUiState {
            it.copy(
                messages = session.messages,
                input = "",
                activeChatSessionId = session.id,
                browserTask = it.browserTask?.let { task ->
                    if (task.sessionId == session.id) task else
                        com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation owner changed")
                },
                selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
                selectedAssistantId = assistant?.id ?: it.selectedAssistantId,
                params = updatedParams,
                selectedChatBackend = restoredBackend,
                selectedCloudChatModelId = sessionCloudModel?.id ?: it.selectedCloudChatModelId,
                cloudApiConfig = sessionCloudModel?.let { model -> port.normalizedCloudChatConfig(model.toChatConfig()) }
                    ?: it.cloudApiConfig,
                promptContextUsage = null,
                contextCompressionPending = compressionPending,
                statusMessage = status
            )
        }
        port.markLocalConversationContextInvalid()
        if (session.mixedAssistantHistory || session.assistantSnapshot?.assistantId != session.assistantId ||
            state.assistants.none { it.id == session.assistantId }) return
        session.messages.firstNotNullOfOrNull { message ->
            message.generatedImageRequest?.takeIf { request ->
                request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                    request.toolContinuationStatus == ChatImageToolContinuationStatus.NOT_STARTED &&
                    request.status in setOf(
                        ChatGeneratedImageStatus.DONE,
                        ChatGeneratedImageStatus.FAILED,
                        ChatGeneratedImageStatus.CANCELLED,
                        ChatGeneratedImageStatus.INTERRUPTED
                    )
            }
        }?.let { port.continueAssistantImageTurn(it.id) }
}
