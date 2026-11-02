package com.muyuchat.mca

import androidx.lifecycle.viewModelScope
import com.muyuchat.mca.composition.modelCatalogWriter
import com.muyuchat.mca.composition.navigationWriter
import com.muyuchat.mca.composition.uiOperationFeedbackWriter
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun MainViewModel.sendPreparedMessage(
    state: MainUiState,
    preparedInput: PreparedChatInput
) {
    chatWorkflowCoordinator.sendPreparedMessage(this, state, preparedInput)
}

internal fun MainViewModel.startGenerationFromCoordinator(
    requestMessages: List<ChatMessage>,
    reservation: UiGenerationReservation? = null,
    toolExchanges: List<ChatToolExchange> = emptyList(),
    paramsOverride: GenerationParams? = null,
    offerAssistantImageTool: Boolean = true,
    chatModelIdOverride: String? = null,
    continuationRequestId: String? = null,
    preflightCompression: com.muyuchat.core.engine.ContextCompressionResult? = null
): Boolean = startGeneration(
    requestMessages = requestMessages,
    reservation = reservation,
    toolExchanges = toolExchanges,
    paramsOverride = paramsOverride,
    offerAssistantImageTool = offerAssistantImageTool,
    chatModelIdOverride = chatModelIdOverride,
    continuationRequestId = continuationRequestId,
    preflightCompression = preflightCompression
)

internal fun MainViewModel.prepareChatInput(state: MainUiState): PreparedChatInput =
    chatInputAndAttachmentCoordinator.prepareChatInput(state)

internal fun MainViewModel.mimeTypeForUri(uriString: String): String =
    chatInputAndAttachmentCoordinator.mimeTypeForUri(uriString)

internal fun MainViewModel.busy(message: String) {
    uiStateStore.uiOperationFeedbackWriter().update { it.copy(busy = true, statusMessage = message) }
}

internal fun MainViewModel.failDownload(message: String) {
    uiStateStore.modelCatalogWriter().update {
        it.copy(
            busy = false,
            downloadStatus = DownloadStatus.FAILED,
            downloadFailureSource = com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.DOWNLOAD,
            downloadSpeedBytesPerSecond = 0L,
            downloadRemainingSeconds = null,
            statusMessage = message
        )
    }
}

internal fun MainViewModel.fail(message: String) {
    uiStateStore.uiOperationFeedbackWriter().update { it.copy(busy = false, statusMessage = message) }
}

internal fun MainViewModel.persistFiles(files: List<FileAssetRecord> = uiStateStore.current.files) =
    chatInputAndAttachmentCoordinator.persistFiles(files)

internal fun MainViewModel.appendAssistant(
    delta: String,
    reasoningDelta: String = "",
    reasoningDurationMs: Long = 0L,
    forcePublish: Boolean = false,
    generationRunId: Long? = null
) = container.chatStreamAccumulator.append(
    delta = delta,
    reasoningDelta = reasoningDelta,
    reasoningDurationMs = reasoningDurationMs,
    forcePublish = forcePublish,
    generationRunId = generationRunId
)

internal fun MainViewModel.flushPendingAssistantOutput(generationRunId: Long? = null): Boolean =
    container.chatStreamAccumulator.flush(generationRunId)

internal fun MainViewModel.discardPendingAssistantOutput(generationRunId: Long? = null) =
    container.chatStreamAccumulator.discard(generationRunId)

internal fun MainViewModel.captureContentImportOwner(): ContentImportOwner = ContentImportOwner(
    sessionId = uiStateStore.current.activeChatSessionId,
    assistantId = uiStateStore.current.selectedAssistantId,
    selectionRevision = container.runtimeAdmission.contentImportSelectionRevision.get()
)

fun MainViewModel.selectTab(tab: AppTab) {
    uiStateStore.navigationWriter().update { it.copy(tab = tab) }
    if (tab == AppTab.MODELS) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshRuntimeReadiness(managedRuntimeReadinessPort)
        }
    }
}

internal fun MainViewModel.restorePendingContentImportPreview() =
    contentImportPreviewCoordinator.restorePendingContentImportPreview()

/** Canonical app entry; preview ownership and persistence remain with the content coordinator. */
internal suspend fun MainViewModel.stageContentImportPreview(preview: ContentImportPreview) =
    contentImportPreviewCoordinator.stageContentImportPreview(preview)

internal fun MainViewModel.finishSelectingNewAssistant(assistant: AssistantRecord) {
    assistantProfileCoordinator.finishSelectingNewAssistant(assistant)
}

internal fun MainViewModel.invalidatePendingChatImagePreparation() =
    offlinePromptTranslationAndChatImageCoordinator.invalidatePendingChatImagePreparation()

suspend fun MainViewModel.translateImagePromptPreview(prompt: String): String =
    offlinePromptTranslationAndChatImageCoordinator.translateImagePromptPreview(prompt)

suspend fun MainViewModel.translateImageNegativePromptPreview(prompt: String): String =
    offlinePromptTranslationAndChatImageCoordinator.translateImageNegativePromptPreview(prompt)

internal fun MainViewModel.hostSendChatImageRequestWithPromptBridge(
    visibleUserText: String,
    prompt: String,
    requestedOutputCount: Int? = null,
    allowCloudTranslationFallback: Boolean = false
) = sendChatImageRequestWithPromptBridge(
    visibleUserText = visibleUserText,
    prompt = prompt,
    requestedOutputCount = requestedOutputCount,
    allowCloudTranslationFallback = allowCloudTranslationFallback
)

internal fun MainViewModel.chatImageToolPermissionUiState(): ChatImageToolPermissionUiState =
    offlinePromptTranslationAndChatImageCoordinator.chatImageToolPermissionUiState()

internal fun MainViewModel.continueAssistantImageToolTurn(requestId: String, outputJson: String) =
    assistantImageContinuationCoordinator.continueAssistantImageToolTurn(requestId, outputJson)

internal fun MainViewModel.hostPersistChatSessions(
    sessions: List<ChatSessionRecord>? = null,
    knowledgeBinding: Pair<String, Set<String>>? = null,
    generationRunId: Long? = null
): kotlinx.coroutines.Job? = persistChatSessions(sessions, knowledgeBinding, generationRunId)

internal fun MainViewModel.persistKnowledgeBaseBindings(sessionId: String, knowledgeBaseIds: Set<String>) {
    persistChatSessions(
        sessions = uiStateStore.current.chatSessions,
        knowledgeBinding = sessionId to knowledgeBaseIds
    )
}

internal fun MainViewModel.chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
    chatImageOptionsForCurrentSelectionFor(state)

internal suspend fun MainViewModel.reconcileModelIdentities() =
    mainPostStartupCoordinator.reconcileModelIdentities()

suspend fun MainViewModel.measureImagePromptTokens(
    modelChoiceId: String,
    prompt: String,
): ImagePromptTokenMeasurement? = chatInputAndAttachmentCoordinator.measureImagePromptTokens(
    modelChoiceId = modelChoiceId,
    prompt = prompt
)

suspend fun MainViewModel.createUpscaledImageAsset(
    spec: ImageUpscaleJobSpec,
    result: LocalImageResult,
    requestId: String
): ImageAssetRecord =
    chatImageWorkflowCoordinator.createUpscaledImageAsset(spec, result, requestId)
