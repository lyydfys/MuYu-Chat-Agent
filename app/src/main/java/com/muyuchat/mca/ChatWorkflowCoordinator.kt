package com.muyuchat.mca

import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.Job
import java.util.UUID

/**
 * Coordinates chat conversation workflow mutations, including turn deletion and rollbacks.
 */
internal class ChatWorkflowCoordinator(
    private val store: UiStateStore,
    private val rejectWhileConversationMutationInProgress: () -> Boolean,
    private val showStatus: (String) -> Unit,
    private val isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean = ::isChatImageRequestActive,
    private val pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    private val persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, Boolean) -> Job?
) {
    /**
     * Deletes the last conversation turn (user prompt and corresponding assistant response).
     *
     * Calculates message indices for the last turn, updates UI state, cancels/removes pending
     * image tasks, updates session summaries, and commits durable mutation preserving reusable native prefix.
     */
    fun deleteLastConversationTurn(chatSessionId: String? = null): Boolean {
        val state = store.state.value
        if (rejectWhileConversationMutationInProgress()) return false
        if (!state.canWriteToActiveAssistantSession()) return false
        if (state.isGenerating) {
            showStatus("请先停止当前生成，再删除本轮对话")
            return false
        }
        val prune = state.messages.pruneLastConversationTurn()
        if (prune == null) {
            showStatus("没有可删除的本轮对话")
            return false
        }
        val lastUserIndex = state.messages.indexOfLast { it.role == Role.USER }
        val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        val removedEndIndex = if (lastAssistantIndex > lastUserIndex) lastAssistantIndex else lastUserIndex
        val removedRequests = if (lastUserIndex >= 0 && removedEndIndex >= lastUserIndex) {
            state.messages.subList(lastUserIndex, removedEndIndex + 1)
                .mapNotNull { it.generatedImageRequest }
        } else emptyList()
        if (removedRequests.any(isChatImageRequestActive)) {
            showStatus("本轮仍关联运行中的图片任务，请等任务结束后再删除。")
            return false
        }
        val rollback = state.conversationMutationRollbackState()
        val sessionId = chatSessionId ?: state.activeChatSessionId ?: UUID.randomUUID().toString()
        var updatedSessions: List<ChatSessionRecord> = state.chatSessions
        store.update {
            updatedSessions = if (prune.messages.isEmpty()) {
                it.chatSessions.map { session ->
                    if (session.id == sessionId) session.copy(
                        messages = emptyList(),
                        contextSummaries = emptyList(),
                        updatedAt = System.currentTimeMillis()
                    ) else session
                }
            } else {
                it.chatSessions.upsertSessionFor(
                    state = it,
                    sessionId = sessionId,
                    messages = prune.messages,
                    assistantId = it.selectedAssistantId,
                    modelMode = it.selectedChatBackend.bindingValue(),
                    modelId = it.currentChatModelId()
                )
            }
            it.copy(
                messages = prune.messages,
                activeChatSessionId = sessionId,
                chatSessions = updatedSessions,
                promptContextUsage = null,
                statusMessage = "已删除最后一轮对话"
            )
        }
        removedRequests.forEach { pendingAssistantImageToolTurns.remove(it.id) }
        persistConversationMutation(
            updatedSessions,
            rollback,
            true // preserveReusableNativePrefix = true
        )
        return true
    }
}
