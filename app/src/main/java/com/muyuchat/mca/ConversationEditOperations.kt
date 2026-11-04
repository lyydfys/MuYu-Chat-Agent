package com.muyuchat.mca

import android.app.Application
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import com.muyuchat.feature.chat.CHAT_MESSAGE_EDIT_MAX_CHARS
import com.muyuchat.feature.chat.ChatMessageIdentity
import java.util.UUID
import kotlinx.coroutines.Job

/**
 * Pure projection of one "edit and resend" turn.
 *
 * [persistedMessages] is the optimistic UI/Room shape (edited user message plus one empty assistant
 * placeholder that the generation owner fills). [requestMessages] is what the runtime receives:
 * the history before the edited message plus the edited message, without the placeholder.
 */
internal data class ConversationEditPlan(
    val requestMessages: List<ChatMessage>,
    val persistedMessages: List<ChatMessage>,
    val removedGeneratedImageRequests: List<ChatGeneratedImageRequest>
)

/** Lease seam so the operation's ordering tests do not need an Android foreground service. */
internal fun interface ChatEditPreparationLease {
    fun close()
}

private fun foregroundPreparationLease(application: Application): ChatEditPreparationLease {
    val handle = McaGenerationForegroundService.acquire(
        application,
        McaGenerationForegroundService.KIND_CHAT
    )
    return ChatEditPreparationLease {
        McaGenerationForegroundService.release(application, handle)
    }
}

/** Human-readable rejection for an edit that must not touch state, or null when it may proceed. */
internal fun conversationEditRejection(
    messages: List<ChatMessage>,
    targetIndex: Int,
    editedText: String,
    maxChars: Int = CHAT_MESSAGE_EDIT_MAX_CHARS
): String? {
    if (targetIndex !in messages.indices) return "消息已变化，请重新打开该会话后再试。"
    val target = messages[targetIndex]
    if (target.role != Role.USER) return "只能编辑自己发送的消息；助手回复请用重新生成。"
    val text = editedText.trim()
    if (text.isEmpty() && target.imageAttachments.isEmpty()) return "编辑后的消息不能为空。"
    if (text.length > maxChars) return "编辑后的消息超过 $maxChars 字上限。"
    return null
}

/**
 * Builds the edit plan. Editing retires the target message and everything after it, so a later
 * assistant answer can never survive as an orphan; only the target text (and its own attachments)
 * is replaced. Assistant content is not edited.
 */
internal fun planConversationEdit(
    messages: List<ChatMessage>,
    targetIndex: Int,
    editedText: String,
    occurredAt: Long = System.currentTimeMillis(),
    maxChars: Int = CHAT_MESSAGE_EDIT_MAX_CHARS
): ConversationEditPlan? {
    if (conversationEditRejection(messages, targetIndex, editedText, maxChars) != null) return null
    val target = messages[targetIndex]
    val edited = ChatMessage(
        role = Role.USER,
        content = editedText.trim(),
        createdAt = occurredAt,
        imageAttachments = target.imageAttachments,
        pinned = target.pinned
    )
    val requestMessages = messages.take(targetIndex) + edited
    return ConversationEditPlan(
        requestMessages = requestMessages,
        persistedMessages = requestMessages + ChatMessage(
            role = Role.ASSISTANT,
            content = "",
            createdAt = occurredAt
        ),
        removedGeneratedImageRequests = messages
            .subList(targetIndex, messages.size)
            .mapNotNull { it.generatedImageRequest }
    )
}

/**
 * Edits one sent user message in place semantically, then regenerates from it.
 *
 * Ordering is load-bearing:
 * 1. validate against the current snapshot and reserve the generation identity;
 * 2. apply one atomic UI state transition (edited user message + empty assistant placeholder);
 * 3. persist that transition through the existing durable conversation-mutation boundary;
 * 4. only after the Room commit starts generation with the same reservation.
 *
 * A persistence failure restores the pre-edit state through the existing rollback path and cancels
 * the reservation, so a half-committed turn can never be generated. The method is idempotent from
 * the caller's point of view: the target identity is re-resolved against the live snapshot and a
 * stale target simply becomes a visible status instead of a partial write.
 */
internal fun editAndResendMessageFor(
    application: Application?,
    store: UiStateWriter<ConversationRegenerationFields>,
    target: ChatMessageIdentity,
    editedText: String,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    showStatus: (String) -> Unit,
    isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    discardPendingAssistantOutput: () -> Unit,
    reserveGeneration: () -> UiGenerationReservation?,
    cancelPendingReservation: (UiGenerationReservation) -> Unit,
    persistMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, () -> Unit, () -> Unit) -> Job,
    startGeneration: (List<ChatMessage>, UiGenerationReservation) -> Boolean,
    acquirePreparationLease: () -> ChatEditPreparationLease = {
        application?.let(::foregroundPreparationLease) ?: ChatEditPreparationLease {}
    }
) {
    if (rejectWhileConversationMutationInProgress()) return
    val state = store.current
    if (!state.canWriteToActiveAssistantSession()) {
        showStatus("此对话的角色归属不明确或角色已删除，无法编辑消息。")
        return
    }
    if (state.isGenerating) {
        showStatus("请先停止当前生成，再编辑并重发消息。")
        return
    }
    val targetIndex = target.resolveIndex(state.messages, state.activeChatSessionId)
    if (targetIndex == null) {
        showStatus("消息已变化，请重新打开该会话后再试。")
        return
    }
    val rejection = conversationEditRejection(state.messages, targetIndex, editedText)
    if (rejection != null) {
        showStatus(rejection)
        return
    }
    val plan = planConversationEdit(state.messages, targetIndex, editedText) ?: return
    if (plan.removedGeneratedImageRequests.any(isChatImageRequestActive)) {
        showStatus("要回退的消息仍关联运行中的图片任务，请等任务结束后再编辑。")
        return
    }
    discardPendingAssistantOutput()
    val reservation = reserveGeneration()
    if (reservation == null) {
        showStatus("当前无法开始新的生成，本次编辑未应用。")
        return
    }
    val preparationLease = acquirePreparationLease()
    val rollback = state.conversationMutationRollbackState()
    val candidateSessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
    val occurredAt = System.currentTimeMillis()
    val applied = store.updateWithResult { fields ->
        if (fields.isGenerating) return@updateWithResult fields to null
        val currentIndex = target.resolveIndex(fields.messages, fields.activeChatSessionId)
            ?: return@updateWithResult fields to null
        val currentPlan = planConversationEdit(
            messages = fields.messages,
            targetIndex = currentIndex,
            editedText = editedText,
            occurredAt = occurredAt
        ) ?: return@updateWithResult fields to null
        val sessionId = fields.activeChatSessionId ?: candidateSessionId
        val sessions = fields.chatSessions.upsertSessionFor(
            state = fields.snapshot,
            sessionId = sessionId,
            messages = currentPlan.persistedMessages,
            assistantId = fields.selectedAssistantId,
            modelMode = fields.selectedChatBackend.bindingValue(),
            modelId = fields.snapshot.currentChatModelId(),
            occurredAt = occurredAt
        )
        fields.copy(
            messages = currentPlan.persistedMessages,
            activeChatSessionId = sessionId,
            chatSessions = sessions,
            isGenerating = true,
            generationPhase = null,
            generationTokenProgress = null,
            generationStats = null,
            promptContextUsage = null,
            statusMessage = "正在应用编辑并重新发送…"
        ) to (sessions to currentPlan)
    }
    if (applied == null) {
        cancelPendingReservation(reservation)
        runCatching { preparationLease.close() }
        showStatus("消息或会话已变化，本次编辑未应用。")
        return
    }
    val (sessionsToPersist, appliedPlan) = applied
    appliedPlan.removedGeneratedImageRequests.forEach { request ->
        pendingAssistantImageToolTurns.remove(request.id)
    }
    val persistenceJob = persistMutation(
        sessionsToPersist,
        rollback,
        {
            val current = store.current
            if (current.isGenerating && current.messages == appliedPlan.persistedMessages) {
                val started = startGeneration(appliedPlan.requestMessages, reservation)
                if (!started) {
                    cancelPendingReservation(reservation)
                    store.update {
                        it.copy(
                            isGenerating = false,
                            statusMessage = "编辑已保存，但无法开始生成；请重试发送。"
                        )
                    }
                }
            } else {
                cancelPendingReservation(reservation)
            }
        },
        { cancelPendingReservation(reservation) }
    )
    persistenceJob.invokeOnCompletion {
        cancelPendingReservation(reservation)
        runCatching { preparationLease.close() }
    }
}
