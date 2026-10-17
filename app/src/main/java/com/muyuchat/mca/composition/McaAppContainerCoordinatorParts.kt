package com.muyuchat.mca.composition

import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.mca.AgentTuningCoordinator
import com.muyuchat.mca.AssistantProfileCoordinator
import com.muyuchat.mca.ManagedDownloadRequest
import com.muyuchat.mca.ModelScopeHubCoordinator
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.mca.DiagnosticsOperations
import com.muyuchat.mca.LocalGenerationSmokeResult
import com.muyuchat.mca.LocalApiRuntimeOwner
import com.muyuchat.mca.ChatAppearanceCoordinator
import com.muyuchat.mca.ChatImageWorkflowCoordinator
import com.muyuchat.mca.ChatWorkflowCoordinator
import com.muyuchat.mca.PendingAssistantImageToolTurn
import com.muyuchat.mca.LocalImageGenerationCoordinator
import com.muyuchat.mca.ImageLibraryBackup
import android.content.Context
import kotlinx.coroutines.sync.Mutex
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.mca.ChatImageRetryPort
import com.muyuchat.mca.ImageLibraryCommitPort
import com.muyuchat.mca.ManagedRuntimeReadinessPort
import com.muyuchat.mca.ChatBackend
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.mca.MainUiState
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.mca.MainPostStartupCoordinator
import com.muyuchat.mca.ContentImportPreviewCoordinator
import com.muyuchat.mca.ChatSessionManagementCoordinator
import com.muyuchat.mca.ChatInputAndAttachmentCoordinator
import com.muyuchat.mca.ModelImportAndDownloadCoordinator
import com.muyuchat.mca.OfflinePromptTranslationAndChatImageCoordinator
import com.muyuchat.mca.ModelIdentityReferenceMigration
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import java.io.File
import com.muyuchat.mca.LocalApiImageGenerationOwnership
import com.muyuchat.mca.ConversationMutationRollbackState
import com.muyuchat.mca.ChatSessionRecord
import com.muyuchat.mca.ImagePromptTokenMeasurementHost
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiStateStore
import kotlinx.coroutines.CoroutineScope
import com.muyuchat.mca.ChatSessionStore
import com.muyuchat.mca.ModelGenerationProfileStore
import com.muyuchat.mca.ModelRuntimeProfileStore
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.mca.TuningJobEntity
import com.muyuchat.mca.ChatModelCoordinator
import com.muyuchat.mca.ManagedChatCatalogSnapshot

internal fun McaAppContainer.createChatAppearanceCoordinatorImpl(
        scope: CoroutineScope,
        uiStateStore: UiStateStore,
        showStatus: (String?) -> Unit
    ): ChatAppearanceCoordinator =
        ChatAppearanceCoordinator(
            scope = scope,
            uiStateStore = uiStateStore,
            globalChatAppearanceStore = globalChatAppearanceStore,
            backgroundImageStore = backgroundImageStore,
            assistantStore = assistantStore,
            chatSessionStore = chatSessionStore,
            showStatus = showStatus
        )

internal fun McaAppContainer.createAgentTuningCoordinator(
        store: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService,
        tuningOwner: TuningOwner,
        agentLogger: AgentDecisionLogger,
        benchmarkRunner: BenchmarkRunner,
        diagnosticsOperations: DiagnosticsOperations,
        managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
        localApiRuntimeOwner: LocalApiRuntimeOwner,
        modelScopeClient: ModelScopeClient,
        cancelPersistedTuningJob: suspend (String) -> TuningJobEntity,
        activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
        activeAdaptiveRecommendation: () -> AdaptiveTuningRecommendation?,
        setActiveAdaptiveRecommendation: (AdaptiveTuningRecommendation?) -> Unit,
        busy: (String) -> Unit,
        fail: (String) -> Unit,
        showStatus: (String) -> Unit,
        persistModelGenerationParams: (ChatBackend, String?, GenerationParams) -> Unit,
        updateParams: (GenerationParams) -> Unit,
        loadModel: (ModelManifest) -> Unit,
        timedLoadModel: suspend (ModelManifest, LocalChatRuntime, LoadParams) -> Long,
        runLocalGenerationSmoke: suspend (GenerationParams) -> LocalGenerationSmokeResult,
        cancelGenerationJob: () -> Unit,
        activeModelForRuntimeProfile: () -> ModelManifest?,
        getPendingProfileTransactionId: () -> String?,
        setPendingProfileTransactionId: (String?) -> Unit
    ): AgentTuningCoordinator =
        AgentTuningCoordinator(
            application = application,
            store = store,
            scope = scope,
            engine = engine,
            tuningOwner = tuningOwner,
            modelStore = modelStore,
            cloudApiStore = cloudApiStore,
            runtimeProfileStore = runtimeProfileStore,
            modelScopeClient = modelScopeClient,
            advisor = advisor,
            agentLogger = agentLogger,
            benchmarkRunner = benchmarkRunner,
            benchmarkHistoryLogger = benchmarkHistoryLogger,
            deviceProfileReader = deviceProfileReader,
            diagnosticsOperations = diagnosticsOperations,
            managedRuntimeReadinessRefreshGate = readinessRefreshGate,
            managedRuntimeReadinessPort = managedRuntimeReadinessPortProvider,
            localApiRuntimeOwner = localApiRuntimeOwner,
            cancelPersistedTuningJob = cancelPersistedTuningJob,
            activeRuntimeIdentity = activeRuntimeIdentity,
            activeAdaptiveRecommendation = activeAdaptiveRecommendation,
            setActiveAdaptiveRecommendation = setActiveAdaptiveRecommendation,
            busy = busy,
            fail = fail,
            showStatus = showStatus,
            persistModelGenerationParams = persistModelGenerationParams,
            updateParams = updateParams,
            loadModel = loadModel,
            timedLoadModel = timedLoadModel,
            runLocalGenerationSmoke = runLocalGenerationSmoke,
            cancelGenerationJob = cancelGenerationJob,
            activeModelForRuntimeProfile = activeModelForRuntimeProfile,
            runtimeProfileTransactionPort = createRuntimeProfileTransactionPort(
                store = store,
                engine = engine,
                tuningOwner = tuningOwner,
                showStatusAction = showStatus
            ),
            getPendingProfileTransactionId = getPendingProfileTransactionId,
            setPendingProfileTransactionId = setPendingProfileTransactionId
        )

internal fun McaAppContainer.createModelScopeHubCoordinator(
        scope: kotlinx.coroutines.CoroutineScope,
        store: com.muyuchat.mca.UiStateStore,
        client: ModelScopeClient,
        currentDeviceProfile: () -> DeviceProfile,
        busy: (String) -> Unit,
        fail: (String) -> Unit,
        showStatus: (String) -> Unit,
        enqueueManagedDownload: (ManagedDownloadRequest, Boolean) -> Unit
    ): ModelScopeHubCoordinator =
        ModelScopeHubCoordinator(
            scope = scope,
            store = store,
            client = client,
            application = application,
            currentDeviceProfile = currentDeviceProfile,
            busy = busy,
            fail = fail,
            showStatus = showStatus,
            enqueueManagedDownload = enqueueManagedDownload
        )

internal fun McaAppContainer.createAssistantProfileCoordinator(
        store: com.muyuchat.mca.UiStateStore,
        scope: kotlinx.coroutines.CoroutineScope,
        modelGenerationProfileStore: com.muyuchat.mca.ModelGenerationProfileStore,
        chatSessionStore: com.muyuchat.mca.ChatSessionStore,
        knowledgeLibraryPort: com.muyuchat.mca.presentation.KnowledgeLibraryPort,
        chatSessionPersistenceStateLock: Any,
        pendingWorldBookCleanupOwners: MutableMap<com.muyuchat.mca.WorldBookScope, MutableSet<String>>,
        engine: com.muyuchat.core.engine.McaInferenceService,
        characterMemorySummaryCoordinator: com.muyuchat.mca.CharacterMemorySummaryCoordinator,
        chatSessionPersistenceMutex: kotlinx.coroutines.sync.Mutex,
        chatSessionPersistenceSequence: java.util.concurrent.atomic.AtomicLong,
        getPendingKnowledgeBindings: () -> Map<String, Set<String>>,
        setDurableChatSessions: (List<com.muyuchat.mca.ChatSessionRecord>) -> Unit,
        conversationMutationBarrierIsActive: () -> Boolean,
        contentImportSelectionRevision: java.util.concurrent.atomic.AtomicLong,
        activeRuntimeIdentity: () -> com.muyuchat.core.engine.ModelRuntimeIdentity?,
        runtimeUserOverrideFields: Set<String>,
        directParameterStageGeneration: java.util.concurrent.atomic.AtomicLong,
        getActiveDirectParameterStageJob: () -> kotlinx.coroutines.Job?,
        setActiveDirectParameterStageJob: (kotlinx.coroutines.Job?) -> Unit,
        directParameterStageMutex: kotlinx.coroutines.sync.Mutex,
        adaptiveTuningJobIsActive: () -> Boolean,
        getActiveTuningJobId: () -> String?,
        getPendingProfileTransactionId: () -> String?,
        setPendingProfileTransactionId: (String?) -> Unit,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        showStatus: (String) -> Unit,
        persistModelGenerationParams: (com.muyuchat.mca.MainUiState, com.muyuchat.core.engine.GenerationParams) -> Unit,
        persistChatSessions: (List<com.muyuchat.mca.ChatSessionRecord>?) -> Unit,
        markLocalConversationContextInvalid: () -> Unit,
        onLocalConversationContextNeedsInvalidation: () -> Unit,
        selectChatSession: (String) -> Unit,
        loadModel: (com.muyuchat.core.modelstore.ModelManifest) -> Unit,
        selectCloudChatModel: (String?) -> Unit,
        newChatForAssistant: (com.muyuchat.mca.AssistantRecord, Boolean) -> Boolean,
        invalidatePendingChatImagePreparation: () -> Unit,
        chatAppearanceMutex: kotlinx.coroutines.sync.Mutex,
        cleanupUnusedChatBackgrounds: suspend () -> Unit,
        captureContentImportOwner: () -> com.muyuchat.mca.ContentImportOwner,
        stageContentImportPreview: suspend (com.muyuchat.mca.ContentImportPreview) -> Unit,
        readAttachmentText: (android.net.Uri, Int) -> Pair<String, Boolean>,
        currentModelGenerationProfileKey: () -> String?
    ): AssistantProfileCoordinator =
        AssistantProfileCoordinator(
            store = store,
            scope = scope,
            application = application,
            assistantStore = assistantStore,
            modelGenerationProfileStore = modelGenerationProfileStore,
            chatSessionStore = chatSessionStore,
            worldBookStore = worldBookStore,
            runtimeProfileStore = runtimeProfileStore,
            engine = engine,
            cloudChatProvider = cloudChatProvider,
            characterMemorySummaryCoordinator = characterMemorySummaryCoordinator,
            knowledgeLibraryPort = knowledgeLibraryPort,
            chatSessionPersistenceState = chatSessionPersistenceState,
            chatSessionPersistenceStateLock = chatSessionPersistenceStateLock,
            pendingWorldBookCleanupOwners = pendingWorldBookCleanupOwners,
            chatSessionPersistenceMutex = chatSessionPersistenceMutex,
            chatSessionPersistenceSequence = chatSessionPersistenceSequence,
            getPendingKnowledgeBindings = getPendingKnowledgeBindings,
            setDurableChatSessions = setDurableChatSessions,
            conversationMutationBarrierIsActive = conversationMutationBarrierIsActive,
            contentImportSelectionRevision = contentImportSelectionRevision,
            activeRuntimeIdentity = activeRuntimeIdentity,
            runtimeUserOverrideFields = runtimeUserOverrideFields,
            directParameterStageGeneration = directParameterStageGeneration,
            getActiveDirectParameterStageJob = getActiveDirectParameterStageJob,
            setActiveDirectParameterStageJob = setActiveDirectParameterStageJob,
            directParameterStageMutex = directParameterStageMutex,
            adaptiveTuningJobIsActive = adaptiveTuningJobIsActive,
            getActiveTuningJobId = getActiveTuningJobId,
            getPendingProfileTransactionId = getPendingProfileTransactionId,
            setPendingProfileTransactionId = setPendingProfileTransactionId,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            showStatus = showStatus,
            persistModelGenerationParams = persistModelGenerationParams,
            persistChatSessions = persistChatSessions,
            markLocalConversationContextInvalid = markLocalConversationContextInvalid,
            onLocalConversationContextNeedsInvalidation = onLocalConversationContextNeedsInvalidation,
            selectChatSession = selectChatSession,
            loadModel = loadModel,
            selectCloudChatModel = selectCloudChatModel,
            newChatForAssistant = newChatForAssistant,
            invalidatePendingChatImagePreparation = invalidatePendingChatImagePreparation,
            chatAppearanceMutex = chatAppearanceMutex,
            cleanupUnusedChatBackgrounds = cleanupUnusedChatBackgrounds,
            captureContentImportOwner = captureContentImportOwner,
            stageContentImportPreview = stageContentImportPreview,
            readAttachmentText = readAttachmentText,
            currentModelGenerationProfileKey = currentModelGenerationProfileKey
        )

    /** Lane A - A7：模型生命周期与云端模型配置协调器工厂。 */
internal fun McaAppContainer.createChatModelCoordinator(
        store: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService,
        tuningOwner: TuningOwner,
        modelGenerationProfileStore: ModelGenerationProfileStore,
        managedRuntimeReadinessPort: () -> ManagedRuntimeReadinessPort,
        installationScopeId: String,
        cancelGenerationJob: () -> Unit,
        persistChatSessions: (List<ChatSessionRecord>?) -> Unit,
        persistChatSessionsWithBinding: (List<ChatSessionRecord>?, Pair<String, Set<String>>?) -> Unit,
        publishManagedChatCatalogAfterValidation: () -> ManagedChatCatalogSnapshot,
        busy: (String) -> Unit,
        loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
        persistModelGenerationParams: (ChatBackend, String, GenerationParams) -> Unit,
        updateParams: (GenerationParams) -> Unit,
        cancelDirectParameterStageJob: () -> Unit,
        rejectImageModelSwitchWhileGenerationIsActive: () -> Boolean,
        invalidatePendingChatImagePreparation: () -> Unit,
        activeRuntimeIdentity: () -> ModelRuntimeIdentity?,
        currentDeviceProfile: () -> DeviceProfile,
        currentQairtBundleSha256: (ModelManifest, List<ModelManifest>) -> String?,
        currentQairtVerifiedLocalModelIds: (android.content.Context, List<ModelManifest>) -> Set<String>
    ): ChatModelCoordinator {
        val cloudModelConfigPort = createCloudModelConfigPort(
            store = store,
            loadModelGenerationParams = loadModelGenerationParams,
            persistChatSessions = persistChatSessionsWithBinding
        )
        return ChatModelCoordinator(
            store = store,
            scope = scope,
            application = application,
            engine = engine,
            modelStore = modelStore,
            cloudApiStore = cloudApiStore,
            runtimeProfileStore = runtimeProfileStore,
            modelGenerationProfileStore = modelGenerationProfileStore,
            tuningOwner = tuningOwner,
            advisor = advisor,
            admission = runtimeAdmission,
            deviceProfileReader = deviceProfileReader,
            managedRuntimeReadinessRefreshGate = readinessRefreshGate,
            managedRuntimeReadinessPort = managedRuntimeReadinessPort,
            cloudModelConfigPort = cloudModelConfigPort,
            installationScopeId = installationScopeId,
            cancelGenerationJob = cancelGenerationJob,
            persistChatSessions = persistChatSessions,
            publishManagedChatCatalogAfterValidation = publishManagedChatCatalogAfterValidation,
            busy = busy,
            loadModelGenerationParams = loadModelGenerationParams,
            persistModelGenerationParams = persistModelGenerationParams,
            updateParams = updateParams,
            cancelDirectParameterStageJob = cancelDirectParameterStageJob,
            rejectImageModelSwitchWhileGenerationIsActive = rejectImageModelSwitchWhileGenerationIsActive,
            invalidatePendingChatImagePreparation = invalidatePendingChatImagePreparation,
            activeRuntimeIdentity = activeRuntimeIdentity,
            currentDeviceProfile = currentDeviceProfile,
            currentQairtBundleSha256 = currentQairtBundleSha256,
            currentQairtVerifiedLocalModelIds = currentQairtVerifiedLocalModelIds
        )
    }

    /** Lane A - A9/A10：启动后异步工作流与状态监控协调器工厂。 */
internal fun McaAppContainer.createMainPostStartupCoordinator(
        store: UiStateStore,
        scope: CoroutineScope,
        engine: McaInferenceService,
        imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
        modelImportAndDownloadCoordinator: ModelImportAndDownloadCoordinator,
        offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
        chatModelCoordinator: ChatModelCoordinator,
        restorePendingContentImportPreview: () -> Unit,
        observeManagedDownloadsAction: () -> Unit,
        schedulePersistentPrefixCacheClear: (Long, String, String) -> Unit,
        persistentPrefixCacheOperationSequence: AtomicLong,
        managedRuntimeReadinessPortProvider: () -> ManagedRuntimeReadinessPort,
        chatSessionPersistenceMutex: Mutex,
        modelIdentityReferenceMigration: ModelIdentityReferenceMigration,
        getDurableChatSessions: () -> List<ChatSessionRecord>,
        setDurableChatSessions: (List<ChatSessionRecord>) -> Unit,
        skipInitialModelDiscovery: Boolean
    ): MainPostStartupCoordinator =
        MainPostStartupCoordinator(
            store = store,
            scope = scope,
            application = application,
            container = this,
            engine = engine,
            modelStore = modelStore,
            imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
            modelImportAndDownloadCoordinator = modelImportAndDownloadCoordinator,
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
            chatModelCoordinator = chatModelCoordinator,
            restorePendingContentImportPreview = restorePendingContentImportPreview,
            observeManagedDownloadsAction = observeManagedDownloadsAction,
            schedulePersistentPrefixCacheClear = schedulePersistentPrefixCacheClear,
            persistentPrefixCacheOperationSequence = persistentPrefixCacheOperationSequence,
            managedRuntimeReadinessPortProvider = managedRuntimeReadinessPortProvider,
            chatSessionPersistenceMutex = chatSessionPersistenceMutex,
            modelIdentityReferenceMigration = modelIdentityReferenceMigration,
            getDurableChatSessions = getDurableChatSessions,
            setDurableChatSessions = setDurableChatSessions,
            skipInitialModelDiscovery = skipInitialModelDiscovery
        )

internal fun McaAppContainer.createContentImportPreviewCoordinator(
        store: UiStateStore,
        scope: CoroutineScope,
        assistantProfileCoordinator: AssistantProfileCoordinator,
        showStatus: (String?) -> Unit
    ): ContentImportPreviewCoordinator =
        ContentImportPreviewCoordinator(
            store = store,
            scope = scope,
            contentImportPreviewStore = contentImportPreviewStore,
            assistantProfileCoordinator = assistantProfileCoordinator,
            showStatus = showStatus
        )

internal fun McaAppContainer.createChatSessionManagementCoordinator(
        store: UiStateStore,
        scope: CoroutineScope,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        hasActiveAssistantImageExecutionIn: (MainUiState, String) -> Boolean,
        showStatus: (String?) -> Unit,
        fail: (String) -> Unit,
        invalidatePendingChatImagePreparation: () -> Unit,
        cancelActiveWebSearch: () -> Unit,
        bumpContentImportSelectionRevision: () -> Unit,
        loadModelGenerationParams: (ChatBackend, String?, GenerationParams) -> GenerationParams,
        markLocalConversationContextInvalid: () -> Unit,
        continueAssistantImageTurn: (String) -> Unit,
        persistChatSessions: (List<ChatSessionRecord>) -> kotlinx.coroutines.Job?
    ): ChatSessionManagementCoordinator =
        ChatSessionManagementCoordinator(
            application = application,
            scope = scope,
            store = store,
            container = this,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            hasActiveAssistantImageExecutionIn = hasActiveAssistantImageExecutionIn,
            showStatus = showStatus,
            fail = fail,
            invalidatePendingChatImagePreparation = invalidatePendingChatImagePreparation,
            cancelActiveWebSearch = cancelActiveWebSearch,
            bumpContentImportSelectionRevision = bumpContentImportSelectionRevision,
            loadModelGenerationParams = loadModelGenerationParams,
            markLocalConversationContextInvalid = markLocalConversationContextInvalid,
            continueAssistantImageTurn = continueAssistantImageTurn,
            persistChatSessions = persistChatSessions
        )

internal fun McaAppContainer.createChatInputAndAttachmentCoordinator(
        store: UiStateStore,
        scope: CoroutineScope,
        showStatus: (String?) -> Unit,
        fail: (String) -> Unit,
        invalidatePendingChatImagePreparation: () -> Unit,
        awaitImageLibraryStartupReconciliation: suspend () -> Unit,
        imageAssetWriteMutex: Mutex,
        imageAssetDirectory: java.io.File,
        imageLibraryCommitPort: () -> ImageLibraryCommitPort,
        imagePromptTokenMeasurementHost: ImagePromptTokenMeasurementHost,
        visionAttachmentPort: VisionAttachmentPort,
        chatSessionStore: ChatSessionStore
    ): ChatInputAndAttachmentCoordinator =
        ChatInputAndAttachmentCoordinator(
            application = application,
            scope = scope,
            store = store,
            container = this,
            showStatus = showStatus,
            fail = fail,
            invalidatePendingChatImagePreparation = invalidatePendingChatImagePreparation,
            awaitImageLibraryStartupReconciliation = awaitImageLibraryStartupReconciliation,
            imageAssetWriteMutex = imageAssetWriteMutex,
            imageAssetDirectory = imageAssetDirectory,
            imageLibraryCommitPort = imageLibraryCommitPort,
            imagePromptTokenMeasurementHost = imagePromptTokenMeasurementHost,
            visionAttachmentPort = visionAttachmentPort,
            chatSessionStore = chatSessionStore
        )

internal fun McaAppContainer.createChatImageWorkflowCoordinatorImpl(
        scope: CoroutineScope,
        store: UiStateStore,
        imageAssetWriteMutex: Mutex,
        imageLibraryMutationMutex: Mutex,
        imageLibraryBackup: ImageLibraryBackup,
        chatSessionStore: ChatSessionStore,
        imageGenerationOwner: ImageGenerationOwner,
        imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
        chatImageRetryPort: ChatImageRetryPort,
        showStatus: (String?) -> Unit,
        tryAcquireObservedImageGenerationLease: (String) -> LocalImageGenerationCoordinator.Lease?,
        releaseObservedImageGenerationLease: (LocalImageGenerationCoordinator.Lease) -> Boolean,
        offlinePromptTranslationService: () -> OfflinePromptTranslationService,
        runtimeProfileStore: ModelRuntimeProfileStore,
        engine: McaInferenceService,
        selectCloudImageModel: (String) -> Unit,
        registerLocalApiImageGenerationOwnership: (LocalApiImageGenerationOwnership) -> Unit,
        unregisterLocalApiImageGenerationOwnership: (LocalApiImageGenerationOwnership) -> Boolean,
        cancelOwnedLocalApiImageWorker: (LocalApiImageGenerationOwnership) -> Boolean
    ): ChatImageWorkflowCoordinator =
        ChatImageWorkflowCoordinator(
            application = application,
            scope = scope,
            store = store,
            container = this,
            imageAssetWriteMutex = imageAssetWriteMutex,
            imageLibraryMutationMutex = imageLibraryMutationMutex,
            imageLibraryBackup = imageLibraryBackup,
            chatSessionStore = chatSessionStore,
            imageGenerationOwner = imageGenerationOwner,
            imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
            chatImageRetryPort = chatImageRetryPort,
            showStatus = showStatus,
            tryAcquireObservedImageGenerationLease = tryAcquireObservedImageGenerationLease,
            releaseObservedImageGenerationLease = releaseObservedImageGenerationLease,
            offlinePromptTranslationService = offlinePromptTranslationService,
            runtimeProfileStore = runtimeProfileStore,
            engine = engine,
            selectCloudImageModel = selectCloudImageModel,
            registerLocalApiImageGenerationOwnership = registerLocalApiImageGenerationOwnership,
            unregisterLocalApiImageGenerationOwnership = unregisterLocalApiImageGenerationOwnership,
            cancelOwnedLocalApiImageWorker = cancelOwnedLocalApiImageWorker
        )

internal fun McaAppContainer.createChatWorkflowCoordinatorImpl(
        store: UiStateStore,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        showStatus: (String) -> Unit,
        isChatImageRequestActive: (ChatGeneratedImageRequest) -> Boolean,
        pendingAssistantImageToolTurns: MutableMap<String, PendingAssistantImageToolTurn>,
        persistConversationMutation: (List<ChatSessionRecord>, ConversationMutationRollbackState, Boolean) -> kotlinx.coroutines.Job?
    ): ChatWorkflowCoordinator =
        ChatWorkflowCoordinator(
            store = store,
            rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
            showStatus = showStatus,
            isChatImageRequestActive = isChatImageRequestActive,
            pendingAssistantImageToolTurns = pendingAssistantImageToolTurns,
            persistConversationMutation = persistConversationMutation
        )
