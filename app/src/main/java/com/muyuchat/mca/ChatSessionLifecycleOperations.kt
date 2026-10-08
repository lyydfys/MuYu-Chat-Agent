package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.Role
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun deleteMessageAtFor(
    store: UiStateStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    showStatus: (String) -> Unit,
    isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState) -> Job?,
    index: Int
) {
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress()) return
    if (!state.canWriteToActiveAssistantSession()) return
    if (state.isGenerating) {
        showStatus("请先停止当前生成，再删除消息")
        return
    }
    if (index !in state.messages.indices) return
    val removedRequest = state.messages[index].generatedImageRequest
    if (removedRequest?.let(isChatImageRequestActive) == true) {
        showStatus("这条消息仍关联运行中的图片任务，请等任务结束后再删除。")
        return
    }
    val rollback = state.conversationMutationRollbackState()
    val updatedMessages = state.messages.filterIndexed { messageIndex, _ -> messageIndex != index }
    val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
    var updatedSessions: List<ChatSessionRecord> = state.chatSessions
    store.update {
        updatedSessions = if (updatedMessages.isEmpty()) {
            it.chatSessions.map { session ->
                if (session.id == sessionId) session.copy(
                    messages = emptyList(), contextSummaries = emptyList(),
                    updatedAt = System.currentTimeMillis()
                ) else session
            }
        } else {
            it.chatSessions.upsertSessionFor(
                state = it,
                sessionId = sessionId,
                messages = updatedMessages,
                assistantId = it.selectedAssistantId,
                modelMode = it.selectedChatBackend.bindingValue(),
                modelId = it.currentChatModelId()
            )
        }
        it.copy(
            messages = updatedMessages,
            activeChatSessionId = sessionId,
            chatSessions = updatedSessions,
            promptContextUsage = null,
            statusMessage = "已删除消息"
        )
    }
    removedRequest?.let { pendingAssistantImageToolTurns.remove(it.id) }
    persistConversationMutation(updatedSessions, rollback)
}

internal fun deleteLastConversationTurnFor(
    store: UiStateStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    showStatus: (String) -> Unit,
    isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, Boolean) -> Job?
) {
    ChatWorkflowCoordinator(
        store = store,
        rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
        showStatus = showStatus,
        isChatImageRequestActive = isChatImageRequestActive,
        pendingAssistantImageToolTurns = pendingAssistantImageToolTurns,
        persistConversationMutation = persistConversationMutation
    ).deleteLastConversationTurn()
}

internal fun newChatForAssistantFor(
    store: UiStateStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    showStatus: (String) -> Unit,
    invalidatePendingChatImagePreparation: () -> Unit,
    cancelActiveWebSearch: () -> Unit,
    bumpContentImportSelectionRevision: () -> Unit,
    markLocalConversationContextInvalid: () -> Unit,
    persistChatSessions: (List<ChatSessionRecord>) -> Job?,
    assistant: AssistantRecord?,
    bindDefaultModel: Boolean = false
): Boolean {
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress()) return false
    if (hasActiveAssistantImageExecution(state.activeChatSessionId)) {
        showStatus("角色图片请求尚未完成，请先确认、取消或等待任务结束。")
        return false
    }
    if (state.isGenerating) {
        showStatus("请先停止当前生成，再新建对话")
        return false
    }
    invalidatePendingChatImagePreparation()
    cancelActiveWebSearch()
    bumpContentImportSelectionRevision()
    val defaultModelMode = assistant
        ?.takeIf { bindDefaultModel && !it.defaultModelId.isNullOrBlank() }
        ?.defaultModelMode
        ?.normalizedAssistantModelMode()
    val targetAssistant = assistant ?: state.selectedAssistant() ?: return false
    val modelMode = defaultModelMode?.takeUnless { it == ASSISTANT_MODEL_MODE_FOLLOW_CURRENT }
        ?: state.selectedChatBackend.bindingValue()
    val modelId = if (defaultModelMode != null && defaultModelMode != ASSISTANT_MODEL_MODE_FOLLOW_CURRENT) {
        targetAssistant.defaultModelId
    } else {
        state.currentChatModelId()
    }
    val greetingSession = targetAssistant.newConversationSession(
        modelMode = modelMode,
        modelId = modelId
    )
    var sessionsToPersist: List<ChatSessionRecord>? = null
    store.update {
        sessionsToPersist = (listOf(greetingSession) + it.chatSessions).sortedForHistory()
        it.copy(
            messages = greetingSession.messages,
            input = "",
            activeChatSessionId = greetingSession.id,
            selectedAssistantId = targetAssistant.id,
            chatSessions = sessionsToPersist ?: it.chatSessions,
            browserTask = it.browserTask?.let { task ->
                com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation owner changed")
            },
            contextCompressionPending = false,
            selectedKnowledgeBaseIds = emptySet(),
            promptContextUsage = null,
            statusMessage = "已新建对话"
        )
    }
    sessionsToPersist?.let { persistChatSessions(it) }
    markLocalConversationContextInvalid()
    return true
}

internal fun branchMixedChatSessionFor(
    store: UiStateStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    persistChatSessions: (List<ChatSessionRecord>) -> Job?,
    selectChatSession: (String) -> Unit,
    sessionId: String,
    assistantId: String
) {
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress() || state.isGenerating) return
    val source = state.chatSessions.firstOrNull { it.id == sessionId && it.mixedAssistantHistory } ?: return
    val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
    if (hasActiveAssistantImageExecution(state.activeChatSessionId)) return
    val now = System.currentTimeMillis()
    val branch = ChatSessionRecord(
        id = UUID.randomUUID().toString(),
        title = "${source.title.take(48)} · 分支",
        messages = emptyList(),
        manualTitle = true,
        updatedAt = now,
        assistantId = assistant.id,
        assistantSnapshot = assistant.toConversationSnapshot(now),
        modelMode = state.selectedChatBackend.bindingValue(),
        modelId = state.currentChatModelId()
    )
    val sessions = (listOf(branch) + state.chatSessions).sortedForHistory()
    store.update { current -> current.copy(chatSessions = sessions) }
    persistChatSessions(sessions)
    selectChatSession(branch.id)
}

internal fun deleteChatSessionFor(
    store: UiStateStore,
    scope: CoroutineScope,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    showStatus: (String) -> Unit,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    manualContextCompressionRequests: ContextCompressionRequestStore,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    queueWorldBookCleanup: (WorldBookScope, Set<String>) -> Unit,
    persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState) -> Job?,
    chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore,
    cleanupUnusedChatBackgrounds: suspend () -> Unit,
    sessionId: String
) {
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress()) return
    if (state.isGenerating) {
        showStatus("请先停止当前生成，再删除记录")
        return
    }
    val session = state.chatSessions.firstOrNull { it.id == sessionId } ?: return
    if (hasActiveAssistantImageExecution(sessionId) || state.imageJobs.any { job ->
            job.spec?.chatSessionId == sessionId && !job.status.terminal
        }
    ) {
        showStatus("该对话仍有图片任务正在执行，请等任务结束后再删除。")
        return
    }
    val rollback = state.conversationMutationRollbackState()
    val isActive = state.activeChatSessionId == sessionId
    val replacement = if (isActive) state.selectedAssistant()?.newConversationSession(
        modelMode = state.selectedChatBackend.bindingValue(),
        modelId = state.currentChatModelId()
    ) else null
    val remaining = (state.chatSessions.filterNot { it.id == sessionId } + listOfNotNull(replacement))
        .sortedForHistory()
    manualContextCompressionRequests.clear(sessionId)
    store.update {
        it.copy(
            chatSessions = remaining,
            messages = if (isActive) replacement?.messages.orEmpty() else it.messages,
            input = if (isActive) "" else it.input,
            activeChatSessionId = if (isActive) replacement?.id else it.activeChatSessionId,
            browserTask = it.browserTask?.let { task ->
                if (task.sessionId == sessionId) {
                    com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation deleted")
                } else task
            },
            selectedKnowledgeBaseIds = if (isActive) emptySet() else it.selectedKnowledgeBaseIds,
            promptContextUsage = if (isActive) null else it.promptContextUsage,
            contextCompressionPending = if (isActive) false else it.contextCompressionPending,
            statusMessage = "已删除对话记录"
        )
    }
    pendingAssistantImageToolTurns.entries.removeIf { entry ->
        entry.value.chatSessionId == sessionId
    }
    queueWorldBookCleanup(WorldBookScope.CHAT, setOf(sessionId))
    persistConversationMutation(remaining, rollback)
    scope.launch(Dispatchers.IO) {
        chatImageToolAuthorizationStore.remove(sessionId)
    }
    scope.launch { cleanupUnusedChatBackgrounds() }
}

internal fun renameChatSessionFor(
    store: UiStateStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    showStatus: (String) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>) -> Job?,
    sessionId: String,
    title: String
) {
    if (rejectWhileConversationMutationInProgress()) return
    val cleanTitle = title.trim().take(48)
    if (cleanTitle.isBlank()) {
        showStatus("标题不能为空")
        return
    }
    var updatedSessions: List<ChatSessionRecord> = emptyList()
    store.update { state ->
        updatedSessions = state.chatSessions.map { session ->
            if (session.id == sessionId) {
                session.copy(
                    title = cleanTitle,
                    manualTitle = true,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                session
            }
        }.sortedForHistory()
        state.copy(
            chatSessions = updatedSessions,
            statusMessage = "已重命名对话"
        )
    }
    persistChatSessions(updatedSessions)
}

internal fun toggleChatSessionPinnedFor(
    store: UiStateStore,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    persistChatSessions: (List<ChatSessionRecord>) -> Job?,
    sessionId: String
) {
    if (rejectWhileConversationMutationInProgress()) return
    var updatedSessions: List<ChatSessionRecord> = emptyList()
    var pinned = false
    store.update { state ->
        updatedSessions = state.chatSessions.map { session ->
            if (session.id == sessionId) {
                pinned = !session.pinned
                session.copy(
                    pinned = pinned,
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                session
            }
        }.sortedForHistory()
        state.copy(
            chatSessions = updatedSessions,
            statusMessage = if (pinned) "已置顶对话" else "已取消置顶"
        )
    }
    persistChatSessions(updatedSessions)
}

internal fun clearChatHistoryFor(
    store: UiStateStore,
    scope: CoroutineScope,
    rejectWhileConversationMutationInProgress: () -> Boolean,
    showStatus: (String) -> Unit,
    hasActiveAssistantImageExecution: (String?) -> Boolean,
    manualContextCompressionRequests: ContextCompressionRequestStore,
    queueWorldBookCleanup: (WorldBookScope, Set<String>) -> Unit,
    pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
    persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState) -> Job?,
    chatImageToolAuthorizationStore: ChatImageToolAuthorizationStore
) {
    val state = store.state.value
    if (rejectWhileConversationMutationInProgress()) return
    if (state.isGenerating) {
        showStatus("请先停止当前生成，再清空历史")
        return
    }
    if (state.chatSessions.any { session -> hasActiveAssistantImageExecution(session.id) } ||
        state.imageJobs.any { job -> job.spec?.chatSessionId != null && !job.status.terminal }
    ) {
        showStatus("仍有角色图片任务正在执行，请等任务结束后再清空历史。")
        return
    }
    val rollback = state.conversationMutationRollbackState()
    val freshSession = state.selectedAssistant()?.newConversationSession(
        modelMode = state.selectedChatBackend.bindingValue(),
        modelId = state.currentChatModelId()
    )
    manualContextCompressionRequests.clearAll()
    store.update {
        it.copy(
            chatSessions = listOfNotNull(freshSession),
            messages = freshSession?.messages.orEmpty(),
            input = "",
            activeChatSessionId = freshSession?.id,
            browserTask = it.browserTask?.let { task ->
                com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation history cleared")
            },
            selectedKnowledgeBaseIds = emptySet(),
            promptContextUsage = null,
            contextCompressionPending = false,
            statusMessage = "已清空聊天记录"
        )
    }
    queueWorldBookCleanup(
        WorldBookScope.CHAT,
        state.chatSessions.mapTo(hashSetOf()) { it.id }
    )
    pendingAssistantImageToolTurns.clear()
    persistConversationMutation(listOfNotNull(freshSession), rollback)
    scope.launch(Dispatchers.IO) {
        chatImageToolAuthorizationStore.prune(emptyList())
    }
}

internal fun chatSessionExportFileNameFor(
    store: UiStateStore,
    sessionId: String
): String {
    val session = store.state.value.chatSessions.firstOrNull { it.id == sessionId }
    val title = safeChatExportTitle(session?.title)
    return "mca-$title-${System.currentTimeMillis()}.md"
}

internal fun exportChatSessionFor(
    store: UiStateStore,
    scope: CoroutineScope,
    application: Application,
    fail: (String) -> Unit,
    sessionId: String,
    uri: Uri
) {
    scope.launch(Dispatchers.IO) {
        runCatching {
            val session = store.state.value.chatSessions.firstOrNull { it.id == sessionId }
                ?: error("未找到要导出的对话")
            val markdown = session.toMarkdown()
            application.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(markdown.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选位置。")
            session
        }.onSuccess { session ->
            store.update {
                it.copy(statusMessage = "已导出对话：${session.title}")
            }
        }.onFailure { error ->
            fail("对话导出失败：${error.message}")
        }
    }
}
