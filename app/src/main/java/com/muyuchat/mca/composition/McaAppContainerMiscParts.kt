package com.muyuchat.mca.composition

import com.muyuchat.mca.ChatImageRequestPort
import com.muyuchat.mca.ChatImageRequestApprovalPort
import com.muyuchat.mca.ChatImageRequestApprovalPortAdapter
import com.muyuchat.mca.ManagedRuntimeReadinessPort
import com.muyuchat.mca.ManagedRuntimeReadinessPortAdapter
import com.muyuchat.mca.AppUpdatePort
import com.muyuchat.mca.AppUpdatePortAdapter
import com.muyuchat.mca.CloudModelConfigPort
import com.muyuchat.mca.CloudModelConfigPortAdapter
import com.muyuchat.mca.SelectChatSessionPort
import com.muyuchat.mca.SelectChatSessionPortAdapter
import com.muyuchat.mca.ChatBackend
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.mca.ImageGenerationJobSpec
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ChatTerminalSettlementPort
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.ImagePromptTokenMeasurementHostAdapter
import com.muyuchat.mca.ImagePromptTokenMeasurementHost
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.mca.LocalApiPayloadPortAdapter
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.mca.LocalImageAdapterPortAdapter
import com.muyuchat.mca.WebSearchConfigPortAdapter
import com.muyuchat.mca.VisionAttachmentPortAdapter
import com.muyuchat.mca.WebSearchConfigPort
import com.muyuchat.mca.WebSearchWorkflowCoordinator
import com.muyuchat.mca.LocalImageAdapterPort
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope

internal fun McaAppContainer.createTuningOwnerImpl(store: UiStateStore, scope: CoroutineScope, engine: McaInferenceService): TuningOwner =
        TuningOwner(
            store = store,
            scope = scope,
            engine = engine,
            runtimeProfileStore = runtimeProfileStore,
            modelStore = modelStore,
            benchmarkHistoryLogger = benchmarkHistoryLogger,
            readinessGate = readinessRefreshGate,
            deviceProfileReader = deviceProfileReader,
            application = application,
            advisor = advisor,
            admission = runtimeAdmission
        )

internal fun McaAppContainer.createLocalImageAdapterPortImpl(store: UiStateStore): LocalImageAdapterPort =
        LocalImageAdapterPortAdapter(
            store = store,
            loraStoreRef = localImageLoraStore,
            textualInversionStoreRef = localImageTextualInversionStore,
            loraLifecycleLock = localImageLoraLifecycleLock,
            textualInversionLifecycleLock = localImageTextualInversionLifecycleLock
        )

internal fun McaAppContainer.createWebSearchConfigPort(store: UiStateStore): WebSearchConfigPort =
        WebSearchConfigPortAdapter(
            store = store,
            appContext = application,
            webSearchStoreRef = webSearchStore,
            diagnosticStoreRef = webSearchDiagnosticStore,
            providerRef = webSearchProvider
        )

internal fun McaAppContainer.createWebSearchWorkflowCoordinatorImpl(
        store: UiStateStore,
        terminalPort: ChatTerminalSettlementPort,
        scope: CoroutineScope
    ): WebSearchWorkflowCoordinator =
        WebSearchWorkflowCoordinator(
            port = createWebSearchConfigPort(store),
            terminalPort = terminalPort,
            scope = scope
        )

internal fun McaAppContainer.createVisionAttachmentPortImpl(store: UiStateStore, engine: McaInferenceService): VisionAttachmentPort =
        VisionAttachmentPortAdapter(
            store = store,
            appContext = application,
            engineRef = engine,
            admissionRef = runtimeAdmission
        )

internal fun McaAppContainer.createLocalApiPayloadPortImpl(store: UiStateStore, engine: McaInferenceService, tuningOwner: TuningOwner): LocalApiPayloadPort =
        LocalApiPayloadPortAdapter(
            store = store,
            engineRef = engine,
            runtimeProfileStoreRef = runtimeProfileStore,
            tuningRef = tuningOwner,
            admissionRef = runtimeAdmission
        )

internal fun McaAppContainer.createImagePromptTokenMeasurementHostImpl(store: UiStateStore): ImagePromptTokenMeasurementHost =
        ImagePromptTokenMeasurementHostAdapter(
            store = store,
            measurementCacheRef = imagePromptTokenMeasurementCache,
            tokenizerDescriptorsRef = imagePromptTokenizerDescriptors
        )

internal fun McaAppContainer.createChatImageRequestApprovalPortImpl(
        store: UiStateStore,
        imageGenerationOwnerProvider: () -> ImageGenerationOwner,
        chatImageRequestPortProvider: () -> ChatImageRequestPort,
        buildChatImageRetrySnapshot: (ChatGeneratedImageRequest, String) -> ImageGenerationJobSpec?,
        continueAssistantImageToolTurn: (String, String) -> Unit
    ): ChatImageRequestApprovalPort =
        ChatImageRequestApprovalPortAdapter(
            store = store,
            admission = runtimeAdmission,
            imageGenerationOwnerProvider = imageGenerationOwnerProvider,
            chatImageRequestPortProvider = chatImageRequestPortProvider,
            buildChatImageRetrySnapshotRef = buildChatImageRetrySnapshot,
            continueAssistantImageToolTurnRef = continueAssistantImageToolTurn
        )

    /** R3-S36：运行时就绪刷新端口工厂（B 族第 1 端口，破万里程碑）。 */
internal fun McaAppContainer.createManagedRuntimeReadinessPort(
        store: UiStateStore,
        imageLibraryBackupJobActive: () -> Boolean,
        hasActiveImageUpscale: () -> Boolean
    ): ManagedRuntimeReadinessPort =
        ManagedRuntimeReadinessPortAdapter(
            context = application,
            store = store,
            modelStore = modelStore,
            localImageModelStore = localImageModelStore,
            refreshGate = readinessRefreshGate,
            admission = runtimeAdmission,
            coordinator = localImageGenerationCoordinator,
            imageLibraryBackupJobActiveProvider = imageLibraryBackupJobActive,
            hasActiveImageUpscaleProvider = hasActiveImageUpscale
        )

internal fun McaAppContainer.createAppUpdatePortImpl(storeProvider: () -> UiStateStore): AppUpdatePort =
        AppUpdatePortAdapter(
            context = application,
            storeProvider = storeProvider
        )

    /** R3-S38：云模型配置端口工厂（B 族第 3 端口）。 */
internal fun McaAppContainer.createCloudModelConfigPort(
        store: UiStateStore,
        loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
        persistChatSessions: (List<ChatSessionRecord>?, Pair<String, Set<String>>?) -> Unit
    ): CloudModelConfigPort =
        CloudModelConfigPortAdapter(
            application = application,
            store = store,
            cloudApiStore = cloudApiStore,
            cloudChatProvider = cloudChatProvider,
            localImageModelStore = localImageModelStore,
            loadModelGenerationParamsRef = loadModelGenerationParams,
            persistChatSessionsRef = persistChatSessions
        )

internal fun McaAppContainer.createSelectChatSessionPortImpl(
        store: UiStateStore,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        hasActiveAssistantImageExecution: (String?) -> Boolean,
        invalidatePendingChatImagePreparation: () -> Unit,
        cancelActiveWebSearch: () -> Unit,
        bumpContentImportSelectionRevision: () -> Unit,
        loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
        markLocalConversationContextInvalid: () -> Unit,
        continueAssistantImageTurn: (String) -> Unit
    ): SelectChatSessionPort =
        SelectChatSessionPortAdapter(
            application = application,
            store = store,
            knowledgeBaseStore = knowledgeBaseStore,
            assistantStore = assistantStore,
            cloudApiStore = cloudApiStore,
            manualContextCompressionRequests = manualContextCompressionRequests,
            rejectWhileConversationMutationInProgressRef = rejectWhileConversationMutationInProgress,
            hasActiveAssistantImageExecutionRef = hasActiveAssistantImageExecution,
            invalidatePendingChatImagePreparationRef = invalidatePendingChatImagePreparation,
            cancelActiveWebSearchRef = cancelActiveWebSearch,
            bumpContentImportSelectionRevisionRef = bumpContentImportSelectionRevision,
            loadModelGenerationParamsRef = loadModelGenerationParams,
            markLocalConversationContextInvalidRef = markLocalConversationContextInvalid,
            continueAssistantImageTurnRef = continueAssistantImageTurn
        )
