package com.muyuchat.mca.composition

import android.app.Application
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.mca.AppLifecycleCoordinator
import com.muyuchat.mca.AssistantImageToolContinuationCoordinator
import com.muyuchat.mca.CacheManagementCoordinator
import com.muyuchat.mca.ChatContextComposer
import com.muyuchat.mca.ChatImageCommitJournal
import com.muyuchat.mca.ChatImageRequestPort
import com.muyuchat.mca.ChatImageRetryPort
import com.muyuchat.mca.ChatLocalApiProviderPort
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.ChatSessionPersistencePort
import com.muyuchat.mca.ChatStopGenerationPort
import com.muyuchat.mca.ConversationMutationRollbackState
import com.muyuchat.mca.DiagnosticsOperations
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ImageLibraryCommitPort
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.mca.ImagePromptTranslationPort
import com.muyuchat.mca.LocalApiAuxProviderPort
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.mca.LocalApiRuntimeOwner
import com.muyuchat.mca.LocalApiServerCoordinator
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.OfflinePromptTranslationAndChatImageCoordinator
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.ReplyGenerationMetrics
import com.muyuchat.mca.SendChatImageRequestPort
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiGenerationCancellation
import com.muyuchat.mca.UiStateStore
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.mca.markLocalConversationContextInvalidInto
import com.muyuchat.mca.VerifiedOfflinePromptTranslationBundle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope

/**
 * Composition-level owner of the concrete inference service.
 *
 * MainViewModel and its public command methods depend on this assembly rather than constructing or
 * naming the native-backed engine. The assembly is the only app-side construction point for engine
 * dependent coordinators, ports and facade adapters.
 */
internal class MainRuntimeAssembly(
    private val application: Application,
    private val container: McaAppContainer,
    private val scope: CoroutineScope,
    installationScopeId: String,
    injectedEngine: Any? = null
) {
    internal val engine: McaInferenceService =
        (injectedEngine as? McaInferenceService) ?: container.createInferenceService(
            runners = container.isolatedLocalChatRunners.runners,
            installationScopeId = installationScopeId
        )

    val benchmarkRunner: BenchmarkRunner by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BenchmarkRunner(engine, container.deviceProfileReader)
    }

    fun isRuntimeAvailable(runtime: LocalChatRuntime): Boolean = engine.isRuntimeAvailable(runtime)

    fun nativeStatsJson(): String = engine.nativeStatsJson()

    fun activeExecutionProfile(): com.muyuchat.core.engine.ModelExecutionProfile? = engine.activeExecutionProfile()

    fun createDiagnosticsOperations(
        store: UiStateStore,
        agentLogger: AgentDecisionLogger,
        currentDeviceProfile: () -> com.muyuchat.core.deviceprofile.DeviceProfile
    ): DiagnosticsOperations = DiagnosticsOperations(
        engine = engine,
        currentState = { store.current },
        updateUiState = { transform -> store.update(transform) },
        recentAgentLogs = { agentLogger.recent() },
        recentBenchmarkHistory = { container.benchmarkHistoryLogger.recent() },
        currentDeviceProfile = currentDeviceProfile,
        application = application
    )

    fun createCacheManagementCoordinator(
        store: UiStateStore,
        chatRuns: ChatRunOwnership,
        refreshPersistentPrefixCacheSummary: () -> Unit,
        markLocalConversationContextInvalid: () -> Unit
    ): CacheManagementCoordinator = CacheManagementCoordinator(
        application = application,
        store = store,
        scope = scope,
        engine = engine,
        chatRuns = chatRuns,
        refreshPersistentPrefixCacheSummary = refreshPersistentPrefixCacheSummary,
        markLocalConversationContextInvalid = markLocalConversationContextInvalid
    )

    fun createVisionAttachmentPort(store: UiStateStore): VisionAttachmentPort =
        container.createVisionAttachmentPort(store, engine)

    fun createTuningOwner(store: UiStateStore): TuningOwner =
        container.createTuningOwner(store = store, scope = scope, engine = engine)

    private var imageGenerationOwnerRef: ImageGenerationOwner? = null

    val imageGenerationOwner: ImageGenerationOwner
        get() = checkNotNull(imageGenerationOwnerRef) { "Image generation owner is not initialized" }

    fun initializeImageGenerationOwner(
        store: UiStateStore,
        imageLibraryStartupReconciliationProvider: () -> ImageLibraryStartupReconciliation,
        imageLibraryCommitPortProvider: () -> ImageLibraryCommitPort,
        chatImageRequestPortProvider: () -> ChatImageRequestPort,
        imagePromptTranslationPortProvider: () -> ImagePromptTranslationPort,
        completeAssistantImageToolTurnForJob: (String) -> Unit
    ) {
        imageGenerationOwnerRef = container.createImageGenerationOwner(
        store = store,
        scope = scope,
        engine = engine,
        imageLibraryStartupReconciliationProvider = imageLibraryStartupReconciliationProvider,
        imageLibraryCommitPortProvider = imageLibraryCommitPortProvider,
        chatImageRequestPortProvider = chatImageRequestPortProvider,
        imagePromptTranslationPortProvider = imagePromptTranslationPortProvider,
        completeAssistantImageToolTurnForJob = completeAssistantImageToolTurnForJob
        )
    }

    fun createAppLifecycleCoordinator(
        store: UiStateStore,
        chatRuns: ChatRunOwnership,
        localApiServerCoordinator: LocalApiServerCoordinator,
        offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator,
        assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator,
        maybeCheckForAppUpdate: () -> Unit,
        foregroundRecoverySequence: AtomicLong,
        getForegroundRecoveryJob: () -> kotlinx.coroutines.Job?,
        setForegroundRecoveryJob: (kotlinx.coroutines.Job?) -> Unit
    ): AppLifecycleCoordinator = AppLifecycleCoordinator(
        application = application,
        uiStateStore = store,
        scope = scope,
        engine = engine,
        chatRuns = chatRuns,
        localApiServerCoordinator = localApiServerCoordinator,
        offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
        assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
        maybeCheckForAppUpdate = maybeCheckForAppUpdate,
        foregroundRecoverySequence = foregroundRecoverySequence,
        getForegroundRecoveryJob = getForegroundRecoveryJob,
        setForegroundRecoveryJob = setForegroundRecoveryJob
    )

    suspend fun stopGenerationAndReadStats(): RuntimeStats {
        engine.stopGeneration()
        return engine.stats.value
    }

    suspend fun invalidateConversationContext(): Result<Unit> = runCatching {
        engine.invalidateConversationContext()
    }

    fun markLocalConversationContextInvalid(
        admission: com.muyuchat.mca.RuntimeAdmissionState,
        store: UiStateStore
    ) = markLocalConversationContextInvalidInto(admission, store, scope, engine)

    fun createChatImageWorkflowCoordinator(
        store: UiStateStore,
        imageAssetWriteMutex: kotlinx.coroutines.sync.Mutex,
        imageLibraryMutationMutex: kotlinx.coroutines.sync.Mutex,
        imageLibraryBackup: com.muyuchat.mca.ImageLibraryBackup,
        chatSessionStore: com.muyuchat.mca.ChatSessionStore,
        imageGenerationOwner: ImageGenerationOwner,
        imageLibraryStartupReconciliation: ImageLibraryStartupReconciliation,
        chatImageRetryPort: ChatImageRetryPort,
        showStatus: (String?) -> Unit,
        tryAcquireObservedImageGenerationLease: (String) -> com.muyuchat.mca.LocalImageGenerationCoordinator.Lease?,
        releaseObservedImageGenerationLease: (com.muyuchat.mca.LocalImageGenerationCoordinator.Lease) -> Boolean,
        offlinePromptTranslationService: () -> OfflinePromptTranslationService,
        runtimeProfileStore: com.muyuchat.mca.ModelRuntimeProfileStore,
        selectCloudImageModel: (String) -> Unit,
        registerLocalApiImageGenerationOwnership: (com.muyuchat.mca.LocalApiImageGenerationOwnership) -> Unit,
        unregisterLocalApiImageGenerationOwnership: (com.muyuchat.mca.LocalApiImageGenerationOwnership) -> Boolean,
        cancelOwnedLocalApiImageWorker: (com.muyuchat.mca.LocalApiImageGenerationOwnership) -> Boolean
    ): com.muyuchat.mca.ChatImageWorkflowCoordinator = container.createChatImageWorkflowCoordinator(
        scope = scope,
        store = store,
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

    fun createPorts(
        store: UiStateStore,
        imageGenerationOwner: ImageGenerationOwner,
        chatImageCommitJournalProvider: () -> ChatImageCommitJournal,
        localApiPayloadPortProvider: () -> LocalApiPayloadPort,
        benchmarkRunner: BenchmarkRunner,
        buildRecommendationAction: (UserPreference) -> AgentRecommendation,
        generateLocalApiImageAction: suspend (String, String) -> String,
        cancelActiveLocalApiImageGenerationAction: (String) -> Boolean,
        chatContextComposer: ChatContextComposer,
        showStatus: (String?) -> Unit,
        chatImageOptionsForCurrentSelection: (MainUiState) -> LocalImageGenerationOptions,
        offlinePromptTranslationServiceProvider: () -> OfflinePromptTranslationService,
        verifiedOfflineTranslationBundleRef: suspend () -> VerifiedOfflinePromptTranslationBundle?,
        chatImageNegativePromptSupported: (MainUiState) -> Boolean,
        rejectWhileConversationMutationInProgress: () -> Boolean,
        stopGenerationInProgress: AtomicBoolean,
        currentReplyMetricsRef: () -> ReplyGenerationMetrics?,
        cancelGenerationJobRef: () -> UiGenerationCancellation,
        flushPendingAssistantOutputRef: () -> Unit,
        persistConversationMutationRef: (List<com.muyuchat.mca.ChatSessionRecord>, ConversationMutationRollbackState) -> Unit
    ): MainViewModelPorts = MainViewModelPortFactory.createPorts(
        container = container,
        uiStateStore = store,
        scope = scope,
        engine = engine,
        imageGenerationOwner = imageGenerationOwner,
        chatImageCommitJournalProvider = chatImageCommitJournalProvider,
        localApiPayloadPortProvider = localApiPayloadPortProvider,
        benchmarkRunner = benchmarkRunner,
        buildRecommendationAction = buildRecommendationAction,
        generateLocalApiImageAction = generateLocalApiImageAction,
        cancelActiveLocalApiImageGenerationAction = cancelActiveLocalApiImageGenerationAction,
        chatContextComposer = chatContextComposer,
        showStatus = showStatus,
        chatImageOptionsForCurrentSelection = chatImageOptionsForCurrentSelection,
        offlinePromptTranslationServiceProvider = offlinePromptTranslationServiceProvider,
        verifiedOfflineTranslationBundleRef = verifiedOfflineTranslationBundleRef,
        chatImageNegativePromptSupported = chatImageNegativePromptSupported,
        rejectWhileConversationMutationInProgress = rejectWhileConversationMutationInProgress,
        stopGenerationInProgress = stopGenerationInProgress,
        currentReplyMetricsRef = currentReplyMetricsRef,
        cancelGenerationJobRef = cancelGenerationJobRef,
        flushPendingAssistantOutputRef = flushPendingAssistantOutputRef,
        persistConversationMutationRef = persistConversationMutationRef
    )
}