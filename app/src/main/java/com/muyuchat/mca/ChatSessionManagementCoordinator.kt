package com.muyuchat.mca

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal class ChatSessionManagementCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: UiStateWriter<ConversationSessionFields>,
    val selectChatSessionPort: SelectChatSessionPort,
    private val rejectWhileConversationMutationInProgress: () -> Boolean,
    private val hasActiveAssistantImageExecutionIn: (MainUiState, String) -> Boolean,
    private val showStatus: (String?) -> Unit,
    private val fail: (String) -> Unit,
    private val invalidatePendingChatImagePreparation: () -> Unit,
    private val cancelActiveWebSearch: () -> Unit,
    private val bumpContentImportSelectionRevision: () -> Unit,
    private val markLocalConversationContextInvalid: () -> Unit,
    private val persistChatSessions: (List<ChatSessionRecord>) -> Job?
) {
    fun selectChatSession(sessionId: String) =
        com.muyuchat.mca.selectChatSession(port = selectChatSessionPort, sessionId = sessionId)

    fun newChatForAssistant(
        assistant: AssistantRecord?,
        bindDefaultModel: Boolean = false
    ): Boolean {
        if (rejectWhileConversationMutationInProgress()) return false
        return newChatForAssistantFor(
            store = store,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(store.current, it) },
            showStatus = showStatus,
            invalidatePendingChatImagePreparation = invalidatePendingChatImagePreparation,
            cancelActiveWebSearch = cancelActiveWebSearch,
            bumpContentImportSelectionRevision = bumpContentImportSelectionRevision,
            markLocalConversationContextInvalid = markLocalConversationContextInvalid,
            persistChatSessions = persistChatSessions,
            assistant = assistant,
            bindDefaultModel = bindDefaultModel
        )
    }

    fun branchMixedChatSession(sessionId: String, assistantId: String) =
        branchMixedChatSessionFor(
            store = store,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(store.current, it) },
            persistChatSessions = persistChatSessions,
            selectChatSession = ::selectChatSession,
            sessionId = sessionId,
            assistantId = assistantId
        )

    fun renameChatSession(sessionId: String, title: String) =
        renameChatSessionFor(
            store = store,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            showStatus = showStatus,
            persistChatSessions = persistChatSessions,
            sessionId = sessionId,
            title = title
        )

    fun toggleChatSessionPinned(sessionId: String) =
        toggleChatSessionPinnedFor(
            store = store,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            persistChatSessions = persistChatSessions,
            sessionId = sessionId
        )

    fun chatSessionExportFileName(sessionId: String): String =
        chatSessionExportFileNameFor(store, sessionId)

    fun exportChatSession(sessionId: String, uri: Uri) =
        exportChatSessionFor(
            store = store,
            scope = scope,
            application = application,
            fail = fail,
            sessionId = sessionId,
            uri = uri
        )
}
