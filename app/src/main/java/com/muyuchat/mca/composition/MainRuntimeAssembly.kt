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
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.ConversationMutationRollbackState
import com.muyuchat.mca.DiagnosticsOperations
import com.muyuchat.mca.ImageGenerationOwner
import com.muyuchat.mca.ImageLibraryCommitPort
import com.muyuchat.mca.ImageLibraryStartupReconciliation
import com.muyuchat.mca.ImagePromptTranslationPort
import com.muyuchat.mca.LocalApiPayloadPort
import com.muyuchat.mca.LocalApiServerCoordinator
import com.muyuchat.mca.LocalImageGenerationOptions
import com.muyuchat.mca.MainUiState
import com.muyuchat.mca.OfflinePromptTranslationAndChatImageCoordinator
import com.muyuchat.mca.OfflinePromptTranslationService
import com.muyuchat.mca.ReplyGenerationMetrics
import com.muyuchat.mca.TuningOwner
import com.muyuchat.mca.UiGenerationCancellation
import com.muyuchat.mca.UiStateStore
import com.muyuchat.mca.VisionAttachmentPort
import com.muyuchat.mca.markLocalConversationContextInvalidInto
import com.muyuchat.mca.VerifiedOfflinePromptTranslationBundle
import com.muyuchat.mca.dsh.createMcaDshGatewayBackend
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Composition-level owner of the concrete inference service.
 *
 * MainViewModel delegates production service creation to this assembly and may supply an explicitly
 * typed existing service. The production factory is evaluated only when that service is absent.
 * The assembly constructs the engine-dependent coordinators, ports and facade adapters.
 */
internal class MainRuntimeAssembly(
    private val application: Application,
    private val container: McaAppContainer,
    private val scope: CoroutineScope,
    installationScopeId: String,
    injectedEngine: McaInferenceService? = null
) {
    internal val engine: McaInferenceService =
        injectedEngine ?: container.createInferenceService(
            runners = container.isolatedLocalChatRunners.runners,
            installationScopeId = installationScopeId
        )

    /**
     * Native video workflow: one process-level owner behind the contract port (V1-02a). The
     * workspace UI, future API routes and the V1-02b worker all share this single instance;
     * execution stays honestly unavailable until the worker adapter is injected here.
     */
    internal val videoHost: VideoHostAssembly = VideoHostAssembly(
        application = application,
        scope = scope,
    )

    /**
     * Independent official-DSH mode: one app-process host assembly for the official runtime,
     * gateway, capability bridge, maintenance, console and account lane (D00-D27).
     */
    internal val dshHost: DshHostAssembly = DshHostAssembly(
        application = application,
        scope = scope,
        gatewayBackend = {
            createMcaDshGatewayBackend(
                engine = engine,
                facade = container.chatExecutionFacadeFor(engine),
                cloudStore = container.cloudApiStore,
                cloudProvider = container.cloudChatProvider,
                installedLocalModels = {
                    // Live read of the existing model store: every downloaded local model becomes
                    // selectable in DSH, not only the one loaded when the host started.
                    runCatching {
                        container.modelStore.listModels().map { model ->
                            val window = com.muyuchat.mca.dsh.DshInstalledContextWindow.of(model)
                            com.muyuchat.mca.dsh.DshLocalModel(
                                modelId = model.id,
                                displayName = model.displayName.ifBlank { model.fileName },
                                contextWindow = window,
                                maxOutputTokens = window?.let { (it / 4).coerceIn(256, 4096) } ?: 4096,
                                runtimeValuesJson = "{}"
                            )
                        }
                    }.getOrDefault(emptyList())
                }
            )
        }
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
        updateUiState = { transform -> store.diagnosticsWriter().update(transform) },
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
        store = store.cacheSettingsWriter(),
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
        uiStateStore = store.localApiSettingsWriter(),
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

    // --- Agent V1 composition ---------------------------------------------------------------

    /**
     * Native Kotlin Agent: one runtime owner with its data, model route, approval/tool chain and
     * management services. DSH and the Agent stay independent; neither creates the other.
     */
    internal val agentHost: AgentHostAssembly = AgentHostAssembly(
        application = application,
        parentScope = scope,
        engine = engine,
        chatExecutionFacade = { container.chatExecutionFacadeFor(engine) },
        runtimeProfileStore = container.runtimeProfileStore,
        cloudApiStore = container.cloudApiStore
    )

    private var closed = false

    /**
     * Settles the process-owned DSH and Agent hosts exactly once. DSH stops before the engine so an
     * in-flight apply cannot use a closed runtime; video runs share the process scope and are
     * cancelled by [McaProcessRuntimeRoot] after this returns.
     */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        ProcessCloseSequence(
            "dsh" to { dshHost.closeAndStopBlocking() },
            "agent" to { agentHost.close() }
        ).close().forEach { failure ->
            android.util.Log.e(
                "MainRuntimeAssembly",
                "close step '${failure.step}' failed",
                failure.error
            )
        }
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
        offlinePromptTranslationService: () -> OfflinePromptTranslationService,
        runtimeProfileStore: com.muyuchat.mca.ModelRuntimeProfileStore,
        selectCloudImageModel: (String) -> Unit,
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
        offlinePromptTranslationService = offlinePromptTranslationService,
        runtimeProfileStore = runtimeProfileStore,
        engine = engine,
        selectCloudImageModel = selectCloudImageModel,
    )

    fun createPorts(
        store: UiStateStore,
        imageGenerationOwner: ImageGenerationOwner,
        chatImageCommitJournalProvider: () -> ChatImageCommitJournal,
        localApiPayloadPortProvider: () -> LocalApiPayloadPort,
        benchmarkRunner: BenchmarkRunner,
        buildRecommendationAction: (UserPreference) -> AgentRecommendation,
        generateLocalApiImageAction: suspend (String, String) -> String,
        generateLocalApiVideoAction: suspend (String, String) -> String,
        stopLocalApiVideoAction: suspend (String) -> Boolean,
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
        generateLocalApiVideoAction = generateLocalApiVideoAction,
        stopLocalApiVideoAction = stopLocalApiVideoAction,
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
