package com.muyuchat.mca

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.muyuchat.core.engine.ReasoningMode

fun MainViewModel.saveAssistantProfile(
    id: String?, name: String, avatar: String, tag: String, systemPrompt: String,
    defaultModelMode: String, defaultModelId: String?, temperature: Float, topP: Float,
    nPredict: Int, topK: Int, minP: Float, repeatPenalty: Float, presencePenalty: Float,
    frequencyPenalty: Float, stopWords: List<String>, reasoningMode: ReasoningMode,
    memoryEnabled: Boolean, memorySummaryInterval: Int, webSearchEnabled: Boolean, fileContextEnabled: Boolean
) = assistantProfileCoordinator.saveAssistantProfile(
    id, name, avatar, tag, systemPrompt, defaultModelMode, defaultModelId,
    temperature, topP, nPredict, topK, minP, repeatPenalty, presencePenalty,
    frequencyPenalty, stopWords, reasoningMode, memoryEnabled, memorySummaryInterval,
    webSearchEnabled, fileContextEnabled
)

fun MainViewModel.upsertAssistantMemory(assistantId: String, id: String?, scope: String, content: String) =
    assistantProfileCoordinator.upsertAssistantMemory(assistantId, id, scope, content)

fun MainViewModel.deleteAssistantMemory(assistantId: String, id: String) =
    assistantProfileCoordinator.deleteAssistantMemory(assistantId, id)

fun MainViewModel.createChatForAssistant(assistantId: String) {
    val assistant = uiStateStore.current.assistants.firstOrNull { it.id == assistantId }
        ?: return
    newChatForAssistant(assistant, bindDefaultModel = true)
}

fun MainViewModel.importChatBackground(scope: ChatAppearanceScope, uriString: String) =
    chatAppearanceCoordinator.importChatBackground(scope, uriString)

fun MainViewModel.setChatAppearance(scope: ChatAppearanceScope, appearance: ChatAppearance?) =
    chatAppearanceCoordinator.setChatAppearance(scope, appearance)

fun MainViewModel.deleteAssistant(assistantId: String) = assistantProfileCoordinator.deleteAssistant(assistantId)
fun MainViewModel.importAssistantCard(rawJson: String) = assistantProfileCoordinator.importAssistantCard(rawJson)

fun MainViewModel.cancelContentImportPreview(id: String) = contentImportPreviewCoordinator.cancelContentImportPreview(id)
fun MainViewModel.confirmContentImportPreview(id: String) = contentImportPreviewCoordinator.confirmContentImportPreview(id) { documentImportCoordinator.confirmKnowledgeDocumentImport(it) }

fun MainViewModel.importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) =
    assistantProfileCoordinator.importAssistantCardFile(uriString, importOwner)

fun MainViewModel.importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) =
    assistantProfileCoordinator.importWorldBook(rawJson, scope)

fun MainViewModel.importWorldBookFile(
    uriString: String,
    scope: WorldBookScope = WorldBookScope.ASSISTANT,
    importOwner: ContentImportOwner? = null
) = assistantProfileCoordinator.importWorldBookFile(uriString, scope, importOwner)

fun MainViewModel.deleteWorldBook(worldBookId: String) =
    assistantProfileCoordinator.deleteWorldBook(worldBookId)

fun MainViewModel.createKnowledgeBase(name: String, description: String = "") =
    assistantProfileCoordinator.createKnowledgeBase(name, description)

fun MainViewModel.importKnowledgeDocument(
    knowledgeBaseId: String,
    uriString: String,
    importOwner: ContentImportOwner? = null
) = documentImportCoordinator.importKnowledgeDocument(knowledgeBaseId, uriString, importOwner)

fun MainViewModel.importKnowledgeDocument(uriString: String) =
    documentImportCoordinator.importKnowledgeDocument(uriString)

fun MainViewModel.setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) =
    setKnowledgeBaseSelectedFor(uiStateStore, { uiStateStore.current }, ::persistKnowledgeBaseBindings, knowledgeBaseId, selected)

fun MainViewModel.deleteKnowledgeBase(knowledgeBaseId: String) =
    assistantProfileCoordinator.deleteKnowledgeBase(knowledgeBaseId)

fun MainViewModel.updateReasoningMode(mode: ReasoningMode) =
    updateReasoningModeFor(
        uiStateStore.current,
        getApplication(),
        modelGenerationProfileStore,
        uiStateStore,
        ::showStatus,
        mode
    )

fun MainViewModel.showCloudReasoningModeLocked() =
    showStatus(CLOUD_REASONING_LOCKED_MESSAGE)

fun MainViewModel.saveWebSearchConfig(
    enabled: Boolean, provider: String, endpoint: String, apiKey: String, maxResults: Int,
    fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
) = webSearchWorkflowCoordinator.saveWebSearchConfig(enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders)

fun MainViewModel.testWebSearchConfig(query: String) = webSearchWorkflowCoordinator.testWebSearchConfig(query)

fun MainViewModel.testWebSearchConfig(
    query: String, enabled: Boolean, provider: String, endpoint: String, apiKey: String,
    maxResults: Int, fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
) = webSearchWorkflowCoordinator.testWebSearchConfig(query, enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders)

fun MainViewModel.preflightWebSearchConfig(
    enabled: Boolean, provider: String, endpoint: String, apiKey: String, maxResults: Int,
    fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
) = webSearchWorkflowCoordinator.preflightWebSearchConfig(enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders)

fun MainViewModel.testWebSearchTurn(
    query: String, enabled: Boolean, provider: String, endpoint: String, apiKey: String, maxResults: Int,
    fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList(),
    allowPublicCheckSourceForProtocolTest: Boolean = false
) = webSearchWorkflowCoordinator.testWebSearchTurn(query, enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders, allowPublicCheckSourceForProtocolTest)

fun MainViewModel.toggleWebSearchForNextTurn() = webSearchWorkflowCoordinator.toggleWebSearchForNextTurn()
fun MainViewModel.cycleWebSearchResearchModeForNextTurn() = webSearchWorkflowCoordinator.cycleWebSearchResearchModeForNextTurn()
fun MainViewModel.selectWebSearchResearchModeForNextTurn(mode: String) = webSearchWorkflowCoordinator.selectWebSearchResearchModeForNextTurn(mode)
fun MainViewModel.clearWebSearchDiagnostics() = webSearchWorkflowCoordinator.clearWebSearchDiagnostics()

fun MainViewModel.exportDiagnosticReport(uri: Uri) =
    diagnosticsOperations.exportDiagnosticReport(uri, viewModelScope, ::fail)

fun MainViewModel.chatSessionExportFileName(sessionId: String): String =
    chatSessionManagementCoordinator.chatSessionExportFileName(sessionId)

fun MainViewModel.exportChatSession(sessionId: String, uri: Uri) =
    chatSessionManagementCoordinator.exportChatSession(sessionId, uri)

fun MainViewModel.refreshLogs() = diagnosticsOperations.refreshLogs()
fun MainViewModel.refreshDiagnostics() = diagnosticsOperations.refreshDiagnostics()
