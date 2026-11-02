package com.muyuchat.mca.composition

import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.mca.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Composition-only factories: each receives only the dependencies of its result. */
internal fun assembleChatSessionManagementCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    container: McaAppContainer,
    showStatus: (String?) -> Unit,
    fail: (String) -> Unit,
    persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?, Long?) -> Job?,
    loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
    markLocalConversationContextInvalid: () -> Unit,
    assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
    offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    injectedChatSessionManagementCoordinator: ChatSessionManagementCoordinator?,
): ChatSessionManagementCoordinator = injectedChatSessionManagementCoordinator
    ?: container.createChatSessionManagementCoordinator(
        store = store,
        scope = scope,
        rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgressInto(store, container.runtimeAdmission) },
        hasActiveAssistantImageExecutionIn = { state, assistantId -> hasActiveAssistantImageExecutionIn(state, assistantId) },
        showStatus = showStatus,
        fail = fail,
        invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
        cancelActiveWebSearch = { container.runtimeAdmission.activeWebSearchDeferred?.cancel() },
        bumpContentImportSelectionRevision = { container.runtimeAdmission.contentImportSelectionRevision.incrementAndGet() },
        loadModelGenerationParams = { backend, modelId, defaults -> loadModelGenerationParams(backend, modelId, defaults) },
        markLocalConversationContextInvalid = markLocalConversationContextInvalid,
        continueAssistantImageTurn = assistantImageContinuationCoordinator::continueAssistantImageTurn,
        persistChatSessions = { persistChatSessions(it, null, null) }
    )

internal fun assembleChatInputAndAttachmentCoordinator(
    store: UiStateStore,
    scope: CoroutineScope,
    container: McaAppContainer,
    imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
    imagePromptTokenMeasurementHost: ImagePromptTokenMeasurementHost,
    visionAttachmentPort: VisionAttachmentPort,
    imageLibraryCommitPort: () -> ImageLibraryCommitPort,
    showStatus: (String?) -> Unit,
    fail: (String) -> Unit,
    offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
    injectedChatInputAndAttachmentCoordinator: ChatInputAndAttachmentCoordinator?,
): ChatInputAndAttachmentCoordinator = injectedChatInputAndAttachmentCoordinator
    ?: container.createChatInputAndAttachmentCoordinator(
        store = store,
        scope = scope,
        showStatus = showStatus,
        fail = fail,
        invalidatePendingChatImagePreparation = offlinePromptTranslationAndChatImageCoordinator::invalidatePendingChatImagePreparation,
        awaitImageLibraryStartupReconciliation = { imageLibraryStartupReconciliation.await() },
        imageAssetWriteMutex = container.imageAssetWriteMutex,
        imageAssetDirectory = container.imageAssetDirectory,
        imageLibraryCommitPort = { imageLibraryCommitPort() },
        imagePromptTokenMeasurementHost = imagePromptTokenMeasurementHost,
        visionAttachmentPort = visionAttachmentPort,
        chatSessionStore = container.chatSessionStore
    )

