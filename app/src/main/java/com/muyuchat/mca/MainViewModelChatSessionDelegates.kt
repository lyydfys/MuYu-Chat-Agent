package com.muyuchat.mca

fun MainViewModel.onInputChange(value: String) = chatInputAndAttachmentCoordinator.onInputChange(value)
fun MainViewModel.clearStatusMessage() = chatInputAndAttachmentCoordinator.clearStatusMessage()

fun MainViewModel.startBrowserTask(request: com.muyuchat.feature.chat.BrowserTaskLaunch) =
    startBrowserTaskFor(uiStateStore, request)

fun MainViewModel.onBrowserTaskEvent(event: com.muyuchat.feature.chat.BrowserTaskEvent) =
    onBrowserTaskEventFor(uiStateStore, { activeWebSearchTaskId }, { activeWebSearchDeferred?.cancel() }, event)

fun MainViewModel.requestContextCompression() = contextCompressionCoordinator.requestContextCompression()
fun MainViewModel.toggleMessagePinned(messageId: String) = contextCompressionCoordinator.toggleMessagePinned(messageId)
fun MainViewModel.undoContextSummary() = contextCompressionCoordinator.undoContextSummary()
fun MainViewModel.contextSummaryEvidenceForQuery(query: String): List<com.muyuchat.feature.chat.ContextSummaryEvidenceUiItem> =
    contextCompressionCoordinator.contextSummaryEvidenceForQuery(query)
fun MainViewModel.setContextCompressionThreshold(percent: Int) =
    contextCompressionCoordinator.setContextCompressionThreshold(percent)
fun MainViewModel.setContextSummaryModel(selection: String) =
    contextCompressionCoordinator.setContextSummaryModel(selection)

fun MainViewModel.branchMixedChatSession(sessionId: String, assistantId: String) =
    chatSessionManagementCoordinator.branchMixedChatSession(sessionId, assistantId)
fun MainViewModel.renameChatSession(sessionId: String, title: String) =
    chatSessionManagementCoordinator.renameChatSession(sessionId, title)
fun MainViewModel.toggleChatSessionPinned(sessionId: String) =
    chatSessionManagementCoordinator.toggleChatSessionPinned(sessionId)
