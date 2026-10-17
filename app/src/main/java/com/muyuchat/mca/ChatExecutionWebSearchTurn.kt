package com.muyuchat.mca

import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import com.muyuchat.mca.presentation.ChatGenerationUiPublisher

internal suspend fun ChatGenerationExecutionPort.runWebSearchTurnFor(
    initialState: MainUiState,
    generationUiPublisher: ChatGenerationUiPublisher,
    effectiveRequestMessages: List<ChatMessage>,
    requestMessages: List<ChatMessage>,
    assistantSnapshot: AssistantConversationSnapshot?,
    generationRunId: Long
): WebSearchTurnOutcome {
    val webSearchTurnMode = if (initialState.webSearchOneShotEnabled) {
        WebSearchTurnMode.ON
    } else {
        initialState.webSearchTurnMode
    }
    val webSearchConfigForTurn = initialState.webSearchResearchModeOverride?.let { researchMode ->
        initialState.webSearchConfig.copy(researchMode = researchMode)
    } ?: initialState.webSearchConfig
    if (
        initialState.webSearchOneShotEnabled ||
        initialState.webSearchTurnMode != WebSearchTurnMode.FOLLOW ||
        initialState.webSearchResearchModeOverride != null
    ) {
        generationUiPublisher.publishWebSearchTurnReset()
    }
    var browserSearchTaskId: String? = null
    val rawWebSearchTurn = executeWebSearchForChatTurn(
        messages = effectiveRequestMessages,
        config = webSearchConfigForTurn,
        oneShotEnabled = webSearchTurnMode == WebSearchTurnMode.ON,
        assistantWebSearchEnabled = assistantSnapshot?.webSearchEnabled
            ?: (selectedAssistant(initialState)?.webSearchEnabled == true),
        turnMode = webSearchTurnMode,
        search = { plan, config ->
            supervisorScope {
                val taskId = browserSearchTaskId
                if (taskId != null && uiState.value.browserTask?.let { task ->
                        task.taskId == taskId && task.phase ==
                            com.muyuchat.feature.chat.BrowserTaskPhase.CANCELLED
                    } == true
                ) throw CancellationException("Browser search task cancelled")
                val deferred = async(Dispatchers.IO) { webSearchProvider.search(plan, config) }
                activeWebSearchDeferred = deferred
                activeWebSearchTaskId = taskId
                try {
                    deferred.await()
                } finally {
                    if (activeWebSearchDeferred === deferred) {
                        activeWebSearchDeferred = null
                        activeWebSearchTaskId = null
                    }
                }
            }
        },
        beforeSearch = { plan, triggerReasons ->
            val task = com.muyuchat.feature.chat.BrowserTaskStateMachine.beginBackgroundSearch(
                owner = "chat-web-search",
                sessionId = initialState.activeChatSessionId,
                messageId = initialState.messages.lastOrNull()
                    ?.takeIf { it.role == Role.ASSISTANT && it.content.isBlank() }?.id
                    ?: requestMessages.lastOrNull { it.role == Role.USER }?.id,
                query = plan.displayQuery
            )
            if (generationUiPublisher.publishBrowserSearchTaskStart(task)) {
                browserSearchTaskId = task.taskId
            }
            attachWebSearchEvidenceToPendingAssistant(
                sources = emptyList(),
                trace = plan.toPendingChatWebSearchTrace(
                    config = webSearchConfigForTurn,
                    triggerReasons = triggerReasons
                ),
                generationRunId = generationRunId
            )
            generationUiPublisher.publishWebSearchStartStatus(plan.displayQuery)
        }
    )
    val searchTaskCancelled = browserSearchTaskId?.let { taskId ->
        uiState.value.browserTask?.let { task ->
            task.taskId == taskId &&
                task.phase == com.muyuchat.feature.chat.BrowserTaskPhase.CANCELLED
        }
    } == true
    val webSearchTurn = if (searchTaskCancelled) rawWebSearchTurn.copy(
        success = false,
        promptContext = "",
        sourceReferences = emptyList(),
        webSearchStatusMessage = "联网检索已取消",
        statusMessage = "联网检索已取消，本轮继续聊天。"
    ) else rawWebSearchTurn
    browserSearchTaskId?.let { taskId ->
        generationUiPublisher.publishBrowserSearchResults(
            taskId = taskId,
            urls = webSearchTurn.sourceReferences.map(ChatSourceReference::url),
            message = webSearchTurn.webSearchStatusMessage
        )
    }
    return webSearchTurn
}

internal fun ChatGenerationExecutionPort.publishWebSearchOutcomeFor(
    webSearchTurn: WebSearchTurnOutcome,
    generationUiPublisher: ChatGenerationUiPublisher
) {
    if (webSearchTurn.requested) {
        val diagnostics = webSearchTurn.diagnostic?.let(::appendWebSearchDiagnostic)
        generationUiPublisher.publishWebSearchOutcome(
            webSearchStatusMessage = webSearchTurn.webSearchStatusMessage,
            diagnostics = diagnostics,
            statusMessage = webSearchTurn.statusMessage
        )
    }
}
