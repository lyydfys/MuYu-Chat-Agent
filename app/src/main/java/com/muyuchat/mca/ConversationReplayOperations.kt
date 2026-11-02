package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.mca.presentation.lifecycleAfterGeneration
import java.util.UUID
import kotlinx.coroutines.Job

/**
 * Owns the regeneration admission/persistence transaction. The caller supplies the existing run
 * owner and persistence command; no ViewModel or arbitrary root-state writer enters this operation.
 */
internal fun regenerateConversationResponseFor(
    application: Application,
    store: UiStateWriter<ConversationRegenerationFields>,
    target: ChatMessageIdentity?,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    discardPendingAssistantOutput: () -> Unit,
    reserveGeneration: () -> UiGenerationReservation?,
    cancelPendingReservation: (UiGenerationReservation) -> Unit,
    persistMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, () -> Unit, () -> Unit) -> Job,
    startGeneration: (List<ChatMessage>, UiGenerationReservation) -> Boolean,
) {
    val state = store.current
    if (rejectWhileConversationMutationInProgress()) return
    if (!state.canWriteToActiveAssistantSession()) return
    if (state.isGenerating) return
    val lastAssistant = state.messages.indexOfLast { it.role == Role.ASSISTANT }
    if (lastAssistant < 0) return
    if (target != null) {
        val targetIndex = target.resolveIndex(state.messages, state.activeChatSessionId)
        if (targetIndex == null || targetIndex != lastAssistant) return
    }
    val priorUser = state.messages.take(lastAssistant).indexOfLast { it.role == Role.USER }
    if (priorUser < 0) return
    val rollback = state.conversationMutationRollbackState()
    discardPendingAssistantOutput()
    val kept = state.messages.take(lastAssistant) + ChatMessage(Role.ASSISTANT, "")
    val reservation = reserveGeneration() ?: return
    val preparationLease = McaGenerationForegroundService.acquire(application, McaGenerationForegroundService.KIND_CHAT)
    // Identity is replay-safe: a failed CAS attempt reuses the same candidate ID.
    val candidateSessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
    val occurredAt = System.currentTimeMillis()
    val sessionsToPersist = store.updateWithResult { fields ->
        val sessionId = fields.activeChatSessionId ?: candidateSessionId
        val sessions = fields.chatSessions.upsertSessionFor(
            state = fields.snapshot,
            occurredAt = occurredAt,
            sessionId = sessionId,
            messages = kept,
            assistantId = fields.selectedAssistantId,
            modelMode = fields.selectedChatBackend.bindingValue(),
            modelId = fields.snapshot.currentChatModelId(),
        )
        fields.copy(
            messages = kept,
            activeChatSessionId = sessionId,
            chatSessions = sessions,
            isGenerating = true,
            generationPhase = null,
            generationTokenProgress = null,
            generationStats = null,
            promptContextUsage = null,
            statusMessage = "正在重新生成上一条回答...",
        ) to sessions
    }
    val persistenceJob = persistMutation(
        sessionsToPersist,
        rollback,
        { startGeneration(kept.dropLast(1), reservation) },
        { cancelPendingReservation(reservation) },
    )
    persistenceJob.invokeOnCompletion {
        cancelPendingReservation(reservation)
        McaGenerationForegroundService.release(application, preparationLease)
    }
}

/** Cancel and stop precede the one atomic conversation/reset publication. */
internal suspend fun clearConversationFor(
    store: UiStateWriter<ConversationResetFields>,
    clearManualCompressionRequest: (String?) -> Unit,
    cancelGeneration: () -> Unit,
    stopGenerationAndReadStats: suspend () -> RuntimeStats,
    discardPendingAssistantOutput: () -> Unit,
    invalidateConversationContext: () -> Unit,
    persistChatSessions: () -> Unit,
) {
    clearManualCompressionRequest(store.current.activeChatSessionId)
    cancelGeneration()
    val stoppedStats = stopGenerationAndReadStats()
    discardPendingAssistantOutput()
    invalidateConversationContext()
    store.update { it.afterConversationGenerationStopped(stoppedStats) }
    persistChatSessions()
}

internal fun ConversationResetFields.afterConversationGenerationStopped(stats: RuntimeStats): ConversationResetFields = copy(
    messages = emptyList(),
    input = "",
    activeChatSessionId = null,
    isGenerating = false,
    generationPhase = null,
    generationTokenProgress = null,
    generationPersistProgress = null,
    generationStats = null,
    promptContextUsage = null,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = "已清空对话，上下文已重置",
    contextCompressionPending = false,
)
