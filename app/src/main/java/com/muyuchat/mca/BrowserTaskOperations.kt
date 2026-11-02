package com.muyuchat.mca

import com.muyuchat.feature.chat.BrowserTaskAction
import com.muyuchat.feature.chat.BrowserTaskEvent
import com.muyuchat.feature.chat.BrowserTaskLaunch
import com.muyuchat.feature.chat.BrowserTaskStateMachine
import kotlinx.coroutines.CompletableDeferred

internal fun startBrowserTaskFor(
    store: UiStateWriter<BrowserTaskFields>,
    request: BrowserTaskLaunch
) {
    val state = store.current
    val session = request.sessionId?.let { id -> state.chatSessions.firstOrNull { it.id == id } }
    val ownerMessages = if (request.sessionId == state.activeChatSessionId) state.messages else session?.messages.orEmpty()
    if (request.sessionId != null && session == null && request.sessionId != state.activeChatSessionId) return
    if (request.messageId != null && ownerMessages.none { it.id == request.messageId }) return
    store.update {
        it.copy(browserTask = BrowserTaskStateMachine.create(request))
    }
}

internal fun onBrowserTaskEventFor(
    store: UiStateWriter<BrowserTaskFields>,
    getActiveWebSearchTaskId: () -> String?,
    cancelActiveWebSearch: () -> Unit,
    event: BrowserTaskEvent
) {
    val activeTask = store.current.browserTask
    if (event.action == BrowserTaskAction.CANCEL &&
        activeTask?.taskId == event.taskId && getActiveWebSearchTaskId() == event.taskId
    ) cancelActiveWebSearch()
    store.update { state ->
        val task = state.browserTask ?: return@update state
        val currentOwner = task.sessionId == null || task.sessionId == state.activeChatSessionId
        val reduced = if (!currentOwner) {
            BrowserTaskStateMachine.cancel(task, "Conversation owner changed")
        } else BrowserTaskStateMachine.reduce(task, event)
        state.copy(browserTask = reduced)
    }
}
