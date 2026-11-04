package com.muyuchat.mca

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.feature.chat.ChatMessageIdentity
import com.muyuchat.feature.chat.ChatMessageIntent
import com.muyuchat.mca.composition.MainCoordinatorAssembly
import com.muyuchat.mca.composition.createModelScopeHubCoordinator
import com.muyuchat.mca.composition.createManagedRuntimeReadinessPort
import com.muyuchat.mca.composition.createContentImportPreviewCoordinator
import com.muyuchat.mca.composition.navigationWriter
import com.muyuchat.mca.composition.runtimeProfileWriter as mcaRuntimeProfileWriter
import com.muyuchat.mca.composition.settingsOperationWriter
import com.muyuchat.mca.composition.imageGenerationWriter
import com.muyuchat.mca.composition.localApiSettingsWriter
import com.muyuchat.mca.composition.imageModelSelectionWriter
import com.muyuchat.mca.composition.knowledgeLibraryWriter
import com.muyuchat.mca.composition.conversationRegenerationWriter
import com.muyuchat.mca.composition.conversationSessionWriter
import com.muyuchat.mca.composition.conversationResetWriter
import com.muyuchat.mca.composition.statusWriter
import com.muyuchat.mca.composition.chatLoraSelectionWriter
import com.muyuchat.mca.composition.generationLifecycleWriter
import com.muyuchat.mca.composition.generationPresetWriter
import com.muyuchat.mca.composition.chatToolSettingsWriter
import com.muyuchat.mca.composition.cacheSettingsWriter
import com.muyuchat.mca.composition.assembleModelImportAndDownloadCoordinator
import com.muyuchat.mca.composition.assembleLocalImageLibraryAndModelCoordinator
import com.muyuchat.mca.composition.assembleAssistantImageContinuationCoordinator
import com.muyuchat.mca.composition.assembleOfflinePromptTranslationAndChatImageCoordinator
import com.muyuchat.mca.composition.assembleAgentTuningCoordinator
import com.muyuchat.mca.composition.assembleChatModelCoordinator
import com.muyuchat.mca.composition.assembleLocalApiControlPlaneCoordinator
import com.muyuchat.mca.composition.assembleAssistantProfileCoordinator
import com.muyuchat.mca.composition.assembleContextCompressionCoordinator
import com.muyuchat.mca.composition.assembleMainPostStartupCoordinator
import com.muyuchat.mca.composition.assembleChatSessionManagementCoordinator
import com.muyuchat.mca.composition.assembleChatInputAndAttachmentCoordinator
import com.muyuchat.mca.composition.assembleChatExecutionPort
import com.muyuchat.mca.composition.McaAppContainer
import com.muyuchat.mca.presentation.LocalApiVideoOperations
import com.muyuchat.mca.presentation.OwnerBackedLocalApiVideoExecutionPort
import com.muyuchat.mca.presentation.StoreKnowledgeLibraryPort
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

internal data class MainViewModelStartupContext(
    val container: McaAppContainer,
    val installationScopeId: String,
    val modelIdentityReferencesMigrated: Boolean,
    val runtimeRoot: com.muyuchat.mca.composition.McaProcessRuntimeRoot
)

class MainViewModel @JvmOverloads internal constructor(
    application: Application,
    private val deferStartup: Boolean = false,
    internal val skipInitialModelDiscovery: Boolean = false,
    injectedLocalApiRuntimeOwner: LocalApiRuntimeOwner? = null,
    injectedChatAppearanceCoordinator: ChatAppearanceCoordinator? = null,
    injectedMainPostStartupCoordinator: MainPostStartupCoordinator? = null,
    injectedChatSessionManagementCoordinator: ChatSessionManagementCoordinator? = null,
    injectedChatInputAndAttachmentCoordinator: ChatInputAndAttachmentCoordinator? = null,
    injectedChatImageWorkflowCoordinator: ChatImageWorkflowCoordinator? = null,
    injectedChatWorkflowCoordinator: ChatWorkflowCoordinator? = null,
    injectedDocumentImportCoordinator: DocumentImportCoordinator? = null,
    injectedAppLifecycleCoordinator: AppLifecycleCoordinator? = null,
    injectedCacheManagementCoordinator: CacheManagementCoordinator? = null,
    injectedStartupContext: MainViewModelStartupContext? = null
) : AndroidViewModel(application) {
    companion object {
        private val localApiProcessLifecycleLock = Any()
        private var localApiProcessOwnerToken: Any? = null
        private const val BACKGROUND_TASK_STATUS = "已转入后台运行，返回 MCA 后查看进度。"
        private const val CONTEXT_COMPRESSION_PREFERENCES = "mca_context_compression_v1"
        internal const val MAX_DIRECT_COMPOSER_UTF8_BYTES = 256 * 1024
        const val CLOUD_MODEL_CHOICE_PREFIX = "cloudmodel:"
        const val CLOUD_IMAGE_MODEL_CHOICE_PREFIX = "cloudimagemodel:"
        const val LOCAL_IMAGE_MODEL_CHOICE_PREFIX = "localimagemodel:"
    }

    private val startupContext = requireNotNull(injectedStartupContext) {
        "MainViewModel requires precomputed startup context"
    }
    internal val container = startupContext.container
    internal val modelIdentityReferenceMigration = ModelIdentityReferenceMigration(application)
    internal val modelGenerationProfileStore = container.modelGenerationProfileStore
    internal val generationPresetStore = container.generationPresetStore
    internal val installationScopeId = startupContext.installationScopeId
    internal val runtimeAssembly = startupContext.runtimeRoot.assembly
    /**
     * Single app-facing port of the native video workflow (V1-02a). Pages send intents and
     * observe projections only; the process-level owner behind this port is created once per
     * process by the runtime assembly and outlives every page.
     */
    internal val videoWorkflowPort get() = runtimeAssembly.videoHost.port
    internal val videoImportPort get() = runtimeAssembly.videoHost.importPort
    internal val videoRemoteProbePort get() = runtimeAssembly.videoHost.remoteProbePort
    internal val videoModelAssetPort get() = runtimeAssembly.videoHost.modelAssetPort

    /**
     * The one guarded call site that reconciles a changed model catalog with a running DSH host.
     * Lazy: a user who never changes a model after opening DSH never pays for it, and the
     * coordinator itself does nothing unless the runtime phase is READY.
     */
    internal val dshBindingRefresh: DshBindingRefreshCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createDshBindingRefreshCoordinator()
    }

    /**
     * Typed state of the one screen-capture consent request. The console reads it through the
     * console snapshot; the Activity is the only place that launches the system dialog.
     */
    internal val dshScreenCaptureConsent get() = runtimeAssembly.dshHost.screenCaptureConsent

    /**
     * Hands the system dialog result to the app capture owner. A cancelled/empty result is not a
     * grant: the owner releases the prepared capture service and returns false.
     */
    internal fun grantDshScreenCapture(resultCode: Int, data: Intent?): Boolean =
        runtimeAssembly.dshHost.grantScreenCapture(resultCode, data)

    /** The DSH owner claims and prepares the request before the Activity may launch. */
    internal fun beginDshScreenCaptureConsent(requestId: Long): Boolean =
        runtimeAssembly.dshHost.screenCaptureConsentOwner.begin(requestId)

    internal fun rejectDshScreenCaptureConsent(requestId: Long, code: String) =
        runtimeAssembly.dshHost.screenCaptureConsentOwner.reject(requestId, code)

    /**
     * Hands the system dialog result to the app capture owner. The owner starts the capture
     * foreground service and waits for its `startForeground` callback before calling
     * `getMediaProjection`; that wait cannot run on the main thread, so the transaction is
     * dispatched to IO. A cancelled/empty result is not a grant.
     */
    internal fun onDshScreenCaptureResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch(Dispatchers.IO) {
            runtimeAssembly.dshHost.screenCaptureConsentOwner.acceptResult(resultCode, data)
        }
    }

    internal val modelScopeClient = container.modelScopeClient
    private val appUpdatePort: AppUpdatePort = container.createAppUpdatePort { uiStateStore }
    internal val appUpdateOwner = AppUpdateOwner(appUpdatePort, viewModelScope)
    private val initialAppUpdateState = appUpdateOwner.loadInitialAppUpdateState()
    private val apiKey = GenerationPreferences.loadOrCreateApiKey(application)
    internal val localApiRuntimeOwner: LocalApiRuntimeOwner = injectedLocalApiRuntimeOwner ?: container.localApiRuntimeOwner
    private val initialApiPreferences = GenerationPreferences.loadApiPreferences(application)
    internal var foregroundRecoveryJob: Job? = null
    private val foregroundRecoverySequence = AtomicLong(0L)
    internal val chatSessionStore = container.chatSessionStore
    internal val characterMemorySummaryCoordinator = CharacterMemorySummaryCoordinator()
    internal val knowledgeLibraryPort = StoreKnowledgeLibraryPort(container.worldBookStore, container.knowledgeBaseStore)
    internal val chatContextComposer = ChatContextComposer(StoreChatContextSources(container.worldBookStore, container.knowledgeBaseStore))
    private val chatImageCommitRecoveryPort: ChatImageCommitRecoveryPort = container.createChatImageCommitRecoveryPort({ container.chatImageCommitJournal }, chatSessionStore)
    internal val offlinePromptTranslationService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { OfflinePromptTranslationService(AppOfflinePromptTranslationRuntimeProvider(application)) }
    internal var activeWebSearchDeferred: Deferred<WebSearchResult>? get() = container.runtimeAdmission.activeWebSearchDeferred; set(v) { container.runtimeAdmission.activeWebSearchDeferred = v }
    @Volatile internal var activeWebSearchTaskId: String? = null
    internal val contextCompressionPreferences = application.getSharedPreferences(CONTEXT_COMPRESSION_PREFERENCES, Context.MODE_PRIVATE)
    internal val benchmarkRunner = runtimeAssembly.benchmarkRunner
    internal val agentLogger = AgentDecisionLogger(application)
    internal val diagnosticsOperations by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runtimeAssembly.createDiagnosticsOperations(
            store = uiStateStore,
            agentLogger = agentLogger,
            currentDeviceProfile = { currentDeviceProfile() }
        )
    }
    private val initialParams = loadGenerationParams(application)
    private val initialAssistants = container.assistantStore.loadAssistants(initialParams)
    private val loadedChatSessions = chatSessionStore.load()
    private val initialStoredSelectedAssistantId = container.assistantStore.loadSelectedAssistantId(initialAssistants)
    internal val initialChatSessions = loadedChatSessions.withInterruptedChatImageRequests().withBackfilledAssistantSnapshots(initialAssistants).let { sessions ->
        val selected = initialAssistants.firstOrNull { it.id == initialStoredSelectedAssistantId }
        if (selected != null && sessions.latestSessionForAssistant(selected.id) == null) listOf(selected.newConversationSession(null, null)) + sessions else sessions
    }.also { snapshots -> if (snapshots != loadedChatSessions) { runCatching { chatSessionStore.save(snapshots) } } }
    private val bootstrapResult = MainUiBootstrapFactory.create(
        application = application, isRuntimeAvailable = runtimeAssembly::isRuntimeAvailable, modelScopeClient = modelScopeClient, agentLogger = agentLogger,
        globalChatAppearanceStore = container.globalChatAppearanceStore, localImageUpscalerStore = container.localImageUpscalerStore,
        webSearchStore = container.webSearchStore, webSearchDiagnosticStore = container.webSearchDiagnosticStore,
        benchmarkHistoryLogger = container.benchmarkHistoryLogger, deviceProfileReader = container.deviceProfileReader, chatSessionStore = chatSessionStore,
        worldBookStore = container.worldBookStore, knowledgeBaseStore = container.knowledgeBaseStore, cloudApiStore = container.cloudApiStore, localImageModelStore = container.localImageModelStore,
        localImageLoraStore = container.localImageLoraStore, imageUpscalerPreferences = container.imageUpscalerPreferences, modelGenerationProfileStore = modelGenerationProfileStore,
        generationPresetStore = container.generationPresetStore,
        contextCompressionPreferences = contextCompressionPreferences, apiKey = apiKey, currentOpenApiAddress = currentOpenApiAddress(), apiUrl = ::apiUrl,
        skipInitialModelDiscovery = skipInitialModelDiscovery, initialAppUpdateState = initialAppUpdateState, initialParams = initialParams, initialAssistants = initialAssistants,
        initialStoredSelectedAssistantId = initialStoredSelectedAssistantId, initialChatSessions = initialChatSessions
    )
    private val chatSessionPersistenceStateInit = container.initializeChatSessionPersistenceState(initialChatSessions)
    @Volatile private var replyGenerationMetrics: ReplyGenerationMetrics? = null
    private val stopGenerationInProgress = AtomicBoolean(false)
    internal val chatRuns: ChatRunOwnership get() = container.chatRuns
    internal val localApiIdempotencyJournalLock = Any()
    internal val uiStateStore = UiStateStore(bootstrapResult.uiState)
    internal val generationPresetWriter = uiStateStore.generationPresetWriter()
    internal val chatToolSettingsWriter = uiStateStore.chatToolSettingsWriter()
    internal val cacheSettingsWriter = uiStateStore.cacheSettingsWriter()
    private val prefixCacheTelemetryStore by lazy { PrefixCacheTelemetryStore(getApplication()) }

    /**
     * DSH ball model panel: a read projection of the same model list/loaded id the chat screen
     * uses, plus the existing select owner as its only write intent. No second loader exists.
     */
    internal val dshModelSwitch: com.muyuchat.feature.dsh.workspace.DshModelSwitchPort by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        DshModelSwitchAdapter(
            uiStateStore.state,
            { choice ->
                val previousTab = uiStateStore.current.tab
                selectChatModel(choice)
                if (previousTab == AppTab.DSH_WORKSPACE) {
                    uiStateStore.navigationWriter().update { it.copy(tab = previousTab) }
                    viewModelScope.launch {
                        withTimeoutOrNull(600_000L) {
                            uiStateStore.state.first { it.loadedModelId == choice }
                        }
                        if (uiStateStore.current.tab == AppTab.CHAT) {
                            uiStateStore.navigationWriter().update { it.copy(tab = previousTab) }
                        }
                    }
                }
            },
            viewModelScope
        )
    }

    internal val agentWorkspacePorts by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MainViewModelAgentPorts(application, runtimeAssembly.agentHost, runtimeAssembly.engine, uiStateStore.state)
    }
    private val settingsCleanupLock = Any()
    private var activeSettingsCleanupJob: Job? = null
    private val chatStreamAccumulatorInit = container.initializeChatStreamAccumulator(uiStateStore, viewModelScope)
    internal val chatAppearanceCoordinator: ChatAppearanceCoordinator = injectedChatAppearanceCoordinator ?: container.createChatAppearanceCoordinator(scope = viewModelScope, uiStateStore = uiStateStore, showStatus = ::showStatus)
    internal val imageLibraryStartupReconciliation = container.createImageLibraryStartupReconciliation(uiStateStore, viewModelScope, chatImageCommitRecoveryPort, bootstrapResult.initialKnowledgeBases)
    internal val chatTerminalSettlementPort = container.createChatTerminalSettlementPort(uiStateStore)
    internal val imagePromptTokenMeasurementHost = container.createImagePromptTokenMeasurementHost(uiStateStore)
    internal val webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator = container.createWebSearchWorkflowCoordinator(uiStateStore, chatTerminalSettlementPort, viewModelScope)
    internal val cacheManagementCoordinator: CacheManagementCoordinator = injectedCacheManagementCoordinator ?: runtimeAssembly.createCacheManagementCoordinator(store = uiStateStore, chatRuns = chatRuns, refreshPersistentPrefixCacheSummary = { refreshPersistentPrefixCacheSummary() }, markLocalConversationContextInvalid = { markLocalConversationContextInvalid() })
    internal val visionAttachmentPort = runtimeAssembly.createVisionAttachmentPort(uiStateStore)
    internal val tuningOwner = runtimeAssembly.createTuningOwner(uiStateStore)
    init {
        runtimeAssembly.videoHost.installRuntimeLeaseProvider {
            prepareLocalImageRuntimeMemoryFor(
                uiStateStore.mcaRuntimeProfileWriter(),
                container.runtimeAdmission,
                container.runtimeProfileStore,
                runtimeAssembly.engine,
            )
        }
        runtimeAssembly.initializeImageGenerationOwner(
            store = uiStateStore,
            imageLibraryStartupReconciliationProvider = { imageLibraryStartupReconciliation },
            imageLibraryCommitPortProvider = { imageLibraryCommitPort },
            chatImageRequestPortProvider = { chatImageRequestPort },
            imagePromptTranslationPortProvider = { imagePromptTranslationPort },
            completeAssistantImageToolTurnForJob = { assistantImageContinuationCoordinator.completeAssistantImageToolTurnForJob(it) }
        )
        // Idempotent Agent reconciliation runs off the main thread; it never auto-resumes a run:
        // interrupted work settles into PAUSED/NEEDS_REVIEW, and force-stopped schedule wakes re-arm here.
        viewModelScope.launch(Dispatchers.IO) { runCatching { runtimeAssembly.agentHost.reconcileProcessStartup() } }
    }
    internal val imageGenerationOwner get() = runtimeAssembly.imageGenerationOwner
    val uiState = uiStateStore.state

    internal fun showStatus(message: String?) { uiStateStore.showStatus(message) }

    internal fun launchSettingsCleanup(
        operation: SettingsCleanupOperation,
        start: () -> Job?
    ): Job? = synchronized(settingsCleanupLock) {
        if (activeSettingsCleanupJob?.isActive == true) return null
        uiStateStore.settingsOperationWriter().update { state ->
            state.copy(
                settingsOperations = state.settingsOperations.copy(cleanup = operation)
            )
        }
        val job = start()
        if (job == null) {
            activeSettingsCleanupJob = null
            uiStateStore.settingsOperationWriter().update { state ->
                if (state.settingsOperations.cleanup == operation) {
                    state.copy(settingsOperations = state.settingsOperations.copy(cleanup = null))
                } else {
                    state
                }
            }
            return null
        }
        activeSettingsCleanupJob = job
        job.invokeOnCompletion {
            uiStateStore.settingsOperationWriter().update { state ->
                if (state.settingsOperations.cleanup == operation) {
                    state.copy(settingsOperations = state.settingsOperations.copy(cleanup = null))
                } else {
                    state
                }
            }
            synchronized(settingsCleanupLock) {
                if (activeSettingsCleanupJob === job) activeSettingsCleanupJob = null
            }
        }
        job
    }

    internal fun tryAcquireObservedImageGenerationLease(requestId: String): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(setDeferGenerationImageGrantRelease = { defer -> uiStateStore.imageGenerationWriter().update { it.copy(deferGenerationImageGrantRelease = defer) } }, coordinator = localImageGenerationCoordinator, observationLock = localImageGenerationCoordinatorObservationLock, requestId = requestId)

    internal fun releaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease): Boolean =
        releaseObservedImageGenerationLeaseFor(setDeferGenerationImageGrantRelease = { defer -> uiStateStore.imageGenerationWriter().update { it.copy(deferGenerationImageGrantRelease = defer) } }, coordinator = localImageGenerationCoordinator, observationLock = localImageGenerationCoordinatorObservationLock, lease = lease)

    fun releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock: () -> Unit): Boolean = chatImageWorkflowCoordinator.releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock)

    private fun cancelActiveLocalApiImageGeneration(reason: String): Boolean =
        imageGenerationOwner.cancelActiveLocalApiImageGeneration(reason)

    internal val localApiServerCoordinator = LocalApiServerCoordinator(
        application = application, scope = viewModelScope, store = uiStateStore.localApiSettingsWriter(), localApiRuntimeOwner = localApiRuntimeOwner, apiKey = apiKey,
        cancelActiveLocalApiImageGeneration = ::cancelActiveLocalApiImageGeneration, persistApiPreferences = ::persistApiPreferences, apiUrl = ::apiUrl,
        currentOpenApiAddress = ::currentOpenApiAddress, onInterruptForegroundRecovery = { foregroundRecoverySequence.incrementAndGet(); foregroundRecoveryJob?.cancel() }
    )

    private fun updateGenerationImageGrantReleaseDefer(defer: Boolean) = chatImageWorkflowCoordinator.updateGenerationImageGrantReleaseDefer(defer)

    internal fun observeManagedDownloads() {
        modelImportAndDownloadCoordinator.observeManagedDownloads { image ->
            val selection = settleLocalImageSelection(image)
            uiStateStore.imageModelSelectionWriter().update { it.copy(localImageModels = selection.models, qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId, selectedLocalImageModelId = selection.selectedId, selectedImageBackend = selection.selectedBackend) }
        }
    }

    internal val coordinatorAssembly: MainCoordinatorAssembly = run {
        lateinit var managedRuntimeReadinessPortRef: ManagedRuntimeReadinessPort
        lateinit var chatModelCoordinatorRef: ChatModelCoordinator
        lateinit var localApiControlPlaneCoordinatorRef: LocalApiControlPlaneCoordinator

        val modelImportAndDownloadCoordinator = assembleModelImportAndDownloadCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication<Application>(),
            container = container,
            initialChatSessions = initialChatSessions,
            modelIdentityReferenceMigration = modelIdentityReferenceMigration,
            showStatus = ::showStatus,
            fail = ::fail,
            busy = ::busy,
            failDownload = ::failDownload,
            loadModel = ::loadModel,
            managedRuntimeReadinessPortProvider = { managedRuntimeReadinessPortRef },
        )

        val localImageLibraryAndModelCoordinator = assembleLocalImageLibraryAndModelCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication<Application>(),
            container = container,
            imageUpscaleOwner = { imageUpscaleOwner },
            showStatus = ::showStatus,
            fail = ::fail,
            busy = ::busy,
            launchModelOperation = ::launchModelOperation,
            tryAcquireObservedImageGenerationLease = ::tryAcquireObservedImageGenerationLease,
            releaseObservedImageGenerationLease = ::releaseObservedImageGenerationLease,
            persistFiles = ::persistFiles,
        )

        val assistantImageContinuationCoordinator = assembleAssistantImageContinuationCoordinator(
            store = uiStateStore,
            container = container,
            chatContextComposer = chatContextComposer,
            chatImageRequestPort = { chatImageRequestPort },
            imageGenerationOwner = { imageGenerationOwner },
            showStatus = ::showStatus,
            persistChatSessions = ::persistChatSessions,
            buildChatImageRetrySnapshot = ::buildChatImageRetrySnapshot,
            chatImageNegativePromptSupported = ::chatImageNegativePromptSupported,
            cancelImageGenerationForJob = ::cancelImageGenerationForJob,
            reserveUiGenerationStart = ::reserveUiGenerationStart,
            startGeneration = { messages, reservation, exchanges, params, offerImage, modelId, requestId -> startGeneration(messages, reservation, exchanges, params, offerImage, modelId, requestId) },
        )

        val offlinePromptTranslationAndChatImageCoordinator = assembleOfflinePromptTranslationAndChatImageCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            container = container,
            offlinePromptTranslationService = { offlinePromptTranslationService },
            showStatus = ::showStatus,
            sendChatImageRequestWithPromptBridge = ::sendChatImageRequestWithPromptBridge,
            sendPreparedMessage = ::sendPreparedMessage,
            prepareChatInput = ::prepareChatInput,
            assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
        )

        val agentTuningCoordinator = assembleAgentTuningCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            container = container,
            engine = runtimeAssembly.engine,
            tuningOwner = tuningOwner,
            agentLogger = agentLogger,
            benchmarkRunner = benchmarkRunner,
            diagnosticsOperations = diagnosticsOperations,
            localApiRuntimeOwner = localApiRuntimeOwner,
            modelScopeClient = modelScopeClient,
            showStatus = ::showStatus,
            fail = ::fail,
            busy = ::busy,
            loadModel = ::loadModel,
            persistModelGenerationParams = { backend, modelId, params -> persistModelGenerationParams(backend, modelId, params) },
            updateParams = ::updateParams,
            cancelGenerationJob = ::cancelGenerationJob,
            managedRuntimeReadinessPortProvider = { managedRuntimeReadinessPortRef },
            chatModelCoordinatorProvider = { chatModelCoordinatorRef },
            localApiControlPlaneCoordinatorProvider = { localApiControlPlaneCoordinatorRef },
        )

        val chatModelCoordinator = assembleChatModelCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            container = container,
            engine = runtimeAssembly.engine,
            tuningOwner = tuningOwner,
            installationScopeId = installationScopeId,
            busy = ::busy,
            persistChatSessions = ::persistChatSessions,
            persistModelGenerationParams = { backend, modelId, params -> persistModelGenerationParams(backend, modelId, params) },
            updateParams = ::updateParams,
            cancelGenerationJob = ::cancelGenerationJob,
            publishManagedChatCatalogAfterValidation = ::publishManagedChatCatalogAfterValidation,
            loadModelGenerationParams = ::loadModelGenerationParams,
            currentDeviceProfile = ::currentDeviceProfile,
            managedRuntimeReadinessPortProvider = { managedRuntimeReadinessPortRef },
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
        )
        chatModelCoordinatorRef = chatModelCoordinator

        val localApiControlPlaneCoordinator = assembleLocalApiControlPlaneCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication<Application>(),
            container = container,
            engine = runtimeAssembly.engine,
            tuningOwner = tuningOwner,
            localApiIdempotencyJournalLock = localApiIdempotencyJournalLock,
            agentTuningCoordinator = agentTuningCoordinator,
        )
        localApiControlPlaneCoordinatorRef = localApiControlPlaneCoordinator

        val modelScopeHubCoordinator = container.createModelScopeHubCoordinator(
            scope = viewModelScope, store = uiStateStore, client = modelScopeClient,
            currentDeviceProfile = ::currentDeviceProfile, busy = ::busy, fail = ::fail,
            showStatus = ::showStatus, enqueueManagedDownload = ::enqueueManagedDownload,
        )

        val assistantProfileCoordinator = assembleAssistantProfileCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication<Application>(),
            container = container,
            engine = runtimeAssembly.engine,
            characterMemorySummaryCoordinator = characterMemorySummaryCoordinator,
            knowledgeLibraryPort = knowledgeLibraryPort,
            chatAppearanceCoordinator = chatAppearanceCoordinator,
            showStatus = ::showStatus,
            loadModel = ::loadModel,
            persistChatSessions = ::persistChatSessions,
            markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
            selectChatSession = ::selectChatSession,
            newChatForAssistant = ::newChatForAssistant,
            captureContentImportOwner = ::captureContentImportOwner,
            stageContentImportPreview = ::stageContentImportPreview,
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
            agentTuningCoordinator = agentTuningCoordinator,
            chatModelCoordinator = chatModelCoordinator,
        )

        val contextCompressionCoordinator = assembleContextCompressionCoordinator(
            store = uiStateStore,
            container = container,
            engine = runtimeAssembly.engine,
            contextCompressionPreferences = contextCompressionPreferences,
            showStatus = ::showStatus,
            persistChatSessions = ::persistChatSessions,
            markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
        )

        val managedRuntimeReadinessPort = container.createManagedRuntimeReadinessPort(
            store = uiStateStore,
            imageLibraryBackupJobActive = { container.runtimeAdmission.imageLibraryBackupJob?.isCompleted == false },
            hasActiveImageUpscale = { imageUpscaleOwner.hasActiveUpscale() },
        )
        managedRuntimeReadinessPortRef = managedRuntimeReadinessPort

        val contentImportPreviewCoordinator = container.createContentImportPreviewCoordinator(
            store = uiStateStore, scope = viewModelScope,
            assistantProfileCoordinator = assistantProfileCoordinator, showStatus = ::showStatus,
        )

        val mainPostStartupCoordinator = assembleMainPostStartupCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            container = container,
            engine = runtimeAssembly.engine,
            imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
            persistentPrefixCacheOperationSequence = persistentPrefixCacheOperationSequence,
            modelIdentityReferenceMigration = modelIdentityReferenceMigration,
            skipInitialModelDiscovery = skipInitialModelDiscovery,
            observeManagedDownloads = ::observeManagedDownloads,
            schedulePersistentPrefixCacheClear = ::schedulePersistentPrefixCacheClear,
            managedRuntimeReadinessPortProvider = { managedRuntimeReadinessPortRef },
            modelImportAndDownloadCoordinator = modelImportAndDownloadCoordinator,
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
            chatModelCoordinator = chatModelCoordinator,
            contentImportPreviewCoordinator = contentImportPreviewCoordinator,
            injectedMainPostStartupCoordinator = injectedMainPostStartupCoordinator,
        )

        val chatSessionManagementCoordinator = assembleChatSessionManagementCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            container = container,
            showStatus = ::showStatus,
            fail = ::fail,
            persistChatSessions = ::persistChatSessions,
            loadModelGenerationParams = ::loadModelGenerationParams,
            markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
            assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
            injectedChatSessionManagementCoordinator = injectedChatSessionManagementCoordinator,
        )

        val chatInputAndAttachmentCoordinator = assembleChatInputAndAttachmentCoordinator(
            store = uiStateStore,
            scope = viewModelScope,
            container = container,
            imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
            imagePromptTokenMeasurementHost = imagePromptTokenMeasurementHost,
            visionAttachmentPort = visionAttachmentPort,
            imageLibraryCommitPort = { imageLibraryCommitPort },
            showStatus = ::showStatus,
            fail = ::fail,
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
            injectedChatInputAndAttachmentCoordinator = injectedChatInputAndAttachmentCoordinator,
        )

        val chatExecutionPort = assembleChatExecutionPort(
            store = uiStateStore,
            container = container,
            engine = runtimeAssembly.engine,
            chatContextComposer = chatContextComposer,
            visionAttachmentPort = visionAttachmentPort,
            chatTerminalSettlementPort = chatTerminalSettlementPort,
            webSearchWorkflowCoordinator = webSearchWorkflowCoordinator,
            getActiveWebSearchTaskId = { activeWebSearchTaskId },
            setActiveWebSearchTaskId = { activeWebSearchTaskId = it },
            getForegroundRecoveryJob = { foregroundRecoveryJob },
            persistChatSessions = ::persistChatSessions,
            appendAssistant = ::appendAssistant,
            flushPendingAssistantOutput = ::flushPendingAssistantOutput,
            ensureLocalConversationContextInvalidated = ::ensureLocalConversationContextInvalidated,
            assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
            assistantProfileCoordinator = assistantProfileCoordinator,
            contextCompressionCoordinator = contextCompressionCoordinator,
            recordPrefixCacheTelemetry = ::recordPrefixCacheTelemetry,
        )

        MainCoordinatorAssembly(
            modelImportAndDownloadCoordinator = modelImportAndDownloadCoordinator,
            localImageLibraryAndModelCoordinator = localImageLibraryAndModelCoordinator,
            assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
            offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
            agentTuningCoordinator = agentTuningCoordinator,
            chatModelCoordinator = chatModelCoordinator,
            localApiControlPlaneCoordinator = localApiControlPlaneCoordinator,
            modelScopeHubCoordinator = modelScopeHubCoordinator,
            assistantProfileCoordinator = assistantProfileCoordinator,
            contextCompressionCoordinator = contextCompressionCoordinator,
            managedRuntimeReadinessPort = managedRuntimeReadinessPort,
            contentImportPreviewCoordinator = contentImportPreviewCoordinator,
            mainPostStartupCoordinator = mainPostStartupCoordinator,
            chatSessionManagementCoordinator = chatSessionManagementCoordinator,
            chatInputAndAttachmentCoordinator = chatInputAndAttachmentCoordinator,
            chatExecutionPort = chatExecutionPort,
        )
    }
    internal val documentImportCoordinator: DocumentImportCoordinator = injectedDocumentImportCoordinator ?: DocumentImportCoordinator(application = application, uiStateStore = uiStateStore.knowledgeLibraryWriter(), scope = viewModelScope, knowledgeBaseStore = container.knowledgeBaseStore, chatSessionStore = chatSessionStore, chatSessionPersistenceState = container.chatSessionPersistenceState, contentImportSelectionRevision = container.runtimeAdmission.contentImportSelectionRevision, stageContentImportPreview = { contentImportPreviewCoordinator.stageContentImportPreview(it) }, markLocalConversationContextInvalid = { markLocalConversationContextInvalid() }, showStatus = { showStatus(it) })
    private val appLifecycleCoordinator: AppLifecycleCoordinator = injectedAppLifecycleCoordinator ?: runtimeAssembly.createAppLifecycleCoordinator(store = uiStateStore, chatRuns = chatRuns, localApiServerCoordinator = localApiServerCoordinator, offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator, assistantImageContinuationCoordinator = assistantImageContinuationCoordinator, maybeCheckForAppUpdate = { maybeCheckForAppUpdate() }, foregroundRecoverySequence = foregroundRecoverySequence, getForegroundRecoveryJob = { foregroundRecoveryJob }, setForegroundRecoveryJob = { foregroundRecoveryJob = it })
    internal val _uiEffects = kotlinx.coroutines.flow.MutableSharedFlow<com.muyuchat.mca.presentation.MainUiEffect>(extraBufferCapacity = 64)
    val uiEffects: kotlinx.coroutines.flow.SharedFlow<com.muyuchat.mca.presentation.MainUiEffect> = _uiEffects

    init { if (!deferStartup) startAfterConstruction() }

    internal fun startAfterConstruction() {
        mainPostStartupCoordinator.runPostStartupWork()
        viewModelScope.launch {
            ProcessUiLifecycleEvents.events.collect { event ->
                when (event) {
                    ProcessUiLifecycleEvent.FOREGROUNDED -> onAppForegrounded()
                    ProcessUiLifecycleEvent.BACKGROUNDED -> onAppBackgrounded()
                }
            }
        }
        localApiServerCoordinator.claimLocalApiProcessOwnership()
        // Bind the App-outside display port at startup, not only when the DSH tab composes: a user
        // who enabled the window must get it back after an update/relaunch without visiting DSH
        // again. It runs on IO because building the host reads preferences and asks the platform
        // about the overlay grant; doing that inline blocked the very startup this restores.
        viewModelScope.launch(Dispatchers.IO) { runCatching { dshOverlayPort } }
        LocalApiRuntime.claimOwner(localApiRuntimeOwner) {
            localApiServerCoordinator.retireLocalApiListener(stopForegroundService = false, forceServerStop = false)
        }
        installChatLocalApiProviders(chatLocalApiProviderPort)
        localApiRuntimeOwner.publishBindings(auxProviderPort = localApiAuxProviderPort, controlPlane = localApiControlPlaneCoordinator.createControlPlane())
        if (initialApiPreferences.apiEnabled) {
            requestLocalApiState(enabled = true, restEnabled = initialApiPreferences.restEnabled, failurePrefix = "本机 API 自动恢复失败")
        }
        // Model catalog / cloud-provider changes are the only inputs of the DSH model overlay.
        // One guarded, debounced call site reconciles them with a READY host; it never starts DSH.
        startDshModelCatalogWatching()
    }

    internal fun publishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot = publishChatCatalogSnapshot(managedRuntimeReadinessPort)
    fun retryImageGeneration(jobId: String) = chatImageWorkflowCoordinator.retryImageGeneration(jobId)
    private val imageAssetCreationPort: ImageAssetCreationPort get() = chatImageWorkflowCoordinator.imageAssetCreationPort
    internal val imageUpscaleOwner: ImageUpscaleOwner get() = chatImageWorkflowCoordinator.imageUpscaleOwner
    private val imagePromptTranslationPort: ImagePromptTranslationPort get() = chatImageWorkflowCoordinator.imagePromptTranslationPort
    private fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? = chatImageWorkflowCoordinator.publishLocalImagePreview(jobId, progress)
    private fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean = rejectImageModelSwitchWhileGenerationIsActiveFor(uiStateStore, container.runtimeAdmission)
    private fun settleLocalImageSelection(preferred: LocalImageModelRecord): LocalImageSelection = localImageLibraryAndModelCoordinator.settleLocalImageSelection(preferred)

    fun deleteLocalImageModel(modelId: String) {
        launchModelOperation("模型操作未完成") {
            if (activeImageGenerationModelId == modelId || activeLocalApiImageModelId == modelId) {
                showStatus("当前图片任务正在使用该模型，请停止或等待任务完成后再删除")
                return@launchModelOperation
            }
            val removed = uiStateStore.current.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            val success = runCatching { container.localImageModelStore.deleteModel(modelId) }.getOrDefault(false)
            settleAndApplyLocalImageModelCatalogUpdate(store = uiStateStore.imageModelSelectionWriter(), localImageModelStore = container.localImageModelStore, context = getApplication(), managedRuntimeReadinessRefreshGate = container.readinessRefreshGate, statusMessage = if (success) "已删除本地图像生成引擎：${removed?.displayName ?: "模型"}" else "未找到本地图像生成引擎")
        }
    }

    fun selectImageGenerationModel(choiceId: String) = chatImageWorkflowCoordinator.selectImageGenerationModel(choiceId)

    fun selectAssistant(assistantId: String) {
        val state = uiStateStore.current
        if (rejectWhileConversationMutationInProgress()) return
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val previousSession = state.chatSessions.latestSessionForAssistant(assistantId)
        if (previousSession != null) {
            if (previousSession.id != state.activeChatSessionId || state.selectedAssistantId != assistant.id) selectChatSession(previousSession.id)
            if (uiStateStore.current.activeChatSessionId == previousSession.id && previousSession.modelMode.toChatBackendOrNull() == ChatBackend.LOCAL) {
                previousSession.modelId?.let { modelId ->
                    if (uiStateStore.current.loadedModelId != modelId) uiStateStore.current.models.firstOrNull { it.id == modelId }?.let(::loadModel)
                }
            }
            return
        }
        if (!newChatForAssistant(assistant, bindDefaultModel = true)) return
        finishSelectingNewAssistant(assistant)
    }

    fun updateAgentPreference(preference: UserPreference) = agentTuningCoordinator.updateAgentPreference(preference)
    fun sendMessage() { chatWorkflowCoordinator.sendMessage(this) }

    internal fun sendChatImageRequestWithPromptBridge(
        visibleUserText: String, prompt: String, requestedOutputCount: Int? = null, allowCloudTranslationFallback: Boolean = false
    ) = runChatImagePromptBridge(port = chatImagePromptBridgePort, visibleUserText = visibleUserText, prompt = prompt, requestedOutputCount = requestedOutputCount, allowCloudTranslationFallback = allowCloudTranslationFallback)

    internal fun chatImageNegativePromptSupported(state: MainUiState): Boolean = chatImageNegativePromptSupportedFor(state)

    fun regenerateLastResponse() = regenerateLastResponseFor(null)

    private fun regenerateLastResponseFor(target: ChatMessageIdentity?) = regenerateConversationResponseFor(
        application = getApplication(),
        store = uiStateStore.conversationRegenerationWriter(),
        target = target,
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        discardPendingAssistantOutput = { discardPendingAssistantOutput() },
        reserveGeneration = ::reserveUiGenerationStart,
        cancelPendingReservation = { chatRuns.cancelPending(it) },
        persistMutation = { sessions, rollback, onCommitted, onCommitFailed ->
            persistConversationMutation(sessions, rollback, onCommitted = onCommitted, onCommitFailed = onCommitFailed)
        },
        startGeneration = { messages, reservation -> startGeneration(messages, reservation) },
    )

    fun deleteMessageAt(index: Int) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteMessageAtFor(store = uiStateStore.conversationSessionWriter(), rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, isChatImageRequestActive = ::isChatImageRequestActive, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) }, index = index)
    }

    fun deleteMessage(target: ChatMessageIdentity) {
        deleteMessageFor(store = uiStateStore.conversationSessionWriter(), rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, isChatImageRequestActive = ::isChatImageRequestActive, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) }, target = target)
    }

    fun handleChatMessageIntent(intent: ChatMessageIntent) {
        when (intent) {
            is ChatMessageIntent.Delete -> deleteMessage(intent.target)
            is ChatMessageIntent.Regenerate -> regenerateLastResponseFor(intent.target)
            is ChatMessageIntent.DeleteLastTurn ->
                chatWorkflowCoordinator.deleteLastConversationTurn(currentSessionId, intent.target)
        }
    }

    val currentSessionId: String? get() = uiStateStore.current.activeChatSessionId
    fun deleteLastConversationTurn() = chatWorkflowCoordinator.deleteLastConversationTurn(currentSessionId) // preserveReusableNativePrefix = true
    internal fun reserveUiGenerationStart(): UiGenerationReservation? = chatRuns.reserveStartAndCancelSuperseded()
    internal fun cancelGenerationJob(): UiGenerationCancellation = chatRuns.cancelCurrentAndCancelOwner()

    internal fun startGeneration(
        requestMessages: List<ChatMessage>, reservation: UiGenerationReservation? = null, toolExchanges: List<ChatToolExchange> = emptyList(),
        paramsOverride: GenerationParams? = null, offerAssistantImageTool: Boolean = true, chatModelIdOverride: String? = null,
        continuationRequestId: String? = null, preflightCompression: com.muyuchat.core.engine.ContextCompressionResult? = null
    ): Boolean {
        val admittedReservation = reservation ?: reserveUiGenerationStart() ?: return false
        val generationRunId = admittedReservation.runId
        val replyMetrics = ReplyGenerationMetrics(generationRunId)
        val ownedGenerationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            chatExecutionPort.runChatGeneration(
                requestMessages = requestMessages, reservation = admittedReservation, replyMetrics = replyMetrics, toolExchanges = toolExchanges,
                paramsOverride = paramsOverride, offerAssistantImageTool = offerAssistantImageTool, chatModelIdOverride = chatModelIdOverride,
                continuationRequestId = continuationRequestId, preflightCompression = preflightCompression
            )
        }
        if (!chatRuns.activate(admittedReservation, ownedGenerationJob)) {
            ownedGenerationJob.cancel(); chatRuns.cancelPending(admittedReservation); return false
        }
        val foregroundLease = McaGenerationForegroundService.acquire(getApplication<Application>(), McaGenerationForegroundService.KIND_CHAT)
        chatRuns.publishJob(ownedGenerationJob)
        replyGenerationMetrics = replyMetrics
        ownedGenerationJob.invokeOnCompletion {
            try {
                chatRuns.finish(generationRunId, ownedGenerationJob)
                chatRuns.retirePublishedJob(ownedGenerationJob)
            } finally {
                McaGenerationForegroundService.release(getApplication<Application>(), foregroundLease)
            }
        }
        ownedGenerationJob.start()
        return true
    }

    fun stopGeneration() { stopActiveGeneration(chatStopGenerationPort) }
    fun selectChatSession(sessionId: String) { chatSessionManagementCoordinator.selectChatSession(sessionId) }
    fun newChat() { if (!rejectWhileConversationMutationInProgress()) newChatForAssistant(uiStateStore.current.selectedAssistant()) }

    internal fun newChatForAssistant(assistant: AssistantRecord?, bindDefaultModel: Boolean = false): Boolean {
        if (rejectWhileConversationMutationInProgress()) return false
        return chatSessionManagementCoordinator.newChatForAssistant(assistant, bindDefaultModel)
    }

    fun deleteChatSession(sessionId: String) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteChatSessionFor(store = uiStateStore.conversationSessionWriter(), scope = viewModelScope, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) }, manualContextCompressionRequests = manualContextCompressionRequests, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, queueWorldBookCleanup = assistantProfileCoordinator::queueWorldBookCleanup, persistConversationMutation = { remaining, rollback -> persistConversationMutation(remaining, rollback) }, chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore, cleanupUnusedChatBackgrounds = { chatAppearanceCoordinator.mutex.withLock { chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() } }, sessionId = sessionId)
    }

    fun clearChatHistory() {
        if (rejectWhileConversationMutationInProgress()) return
        launchSettingsCleanup(SettingsCleanupOperation.CHAT_HISTORY) {
            clearChatHistoryFor(store = uiStateStore.conversationSessionWriter(), scope = viewModelScope, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus, hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) }, manualContextCompressionRequests = manualContextCompressionRequests, queueWorldBookCleanup = assistantProfileCoordinator::queueWorldBookCleanup, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns, persistConversationMutation = { sessions, rollback, onCommitted, onCommitFailed -> persistConversationMutation(sessions, rollback, onCommitted = onCommitted, onCommitFailed = onCommitFailed) }, chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore)
        }
    }

    /**
     * Persists and applies the chat-page local tool toggle. It only changes what the next request
     * offers; a turn already streaming is never mutated underneath the user.
     */
    fun setChatLocalToolsEnabled(enabled: Boolean) {        if (uiStateStore.current.chatLocalToolsEnabled == enabled) return
        GenerationPreferences.persistChatLocalToolsEnabled(getApplication(), enabled)
        chatToolSettingsWriter.update {
            it.copy(
                chatLocalToolsEnabled = enabled,
                statusMessage = if (enabled) {
                    "已开启本地工具：时间、计算与掷骰"
                } else {
                    "已关闭本地工具"
                }
            )
        }
    }

    /**
     * Records one finished turn's cache evidence off the main thread, then publishes the aggregate.
     *
     * Accounting is best effort by design: a failure here must never surface as a chat error, and
     * the write happens on IO because it touches the app-private telemetry file.
     */
    internal fun recordPrefixCacheTelemetry(stats: RuntimeStats) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val updated = prefixCacheTelemetryStore.record(stats)
                cacheSettingsWriter.update { it.copy(prefixCacheTelemetry = updated) }
            }
        }
    }

    fun setPersistentPrefixCacheEnabled(enabled: Boolean) {
        if (enabled) {
            // Enabling is a cheap local flip; only the opt-out has to wipe the disk-backed cache.
            cacheManagementCoordinator.setPersistentPrefixCacheEnabled(true)
            return
        }
        val started = launchSettingsCleanup(SettingsCleanupOperation.PERSISTENT_PREFIX_CACHE) {
            cacheManagementCoordinator.setPersistentPrefixCacheEnabled(false)
        }
        // The single-flight slot is shared with the other cleanups. Staying silent here left the
        // switch on with no explanation, which reads as a stuck control.
        if (started == null && uiStateStore.current.persistentPrefixCacheEnabled) {
            showStatus("本机数据正在清理，请稍后再关闭持久化前缀缓存")
        }
    }

    fun clearPersistentPrefixCache() {
        launchSettingsCleanup(SettingsCleanupOperation.PERSISTENT_PREFIX_CACHE) {
            cacheManagementCoordinator.clearPersistentPrefixCache()
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            clearConversationFor(
                store = uiStateStore.conversationResetWriter(),
                clearManualCompressionRequest = manualContextCompressionRequests::clear,
                cancelGeneration = { cancelGenerationJob() },
                stopGenerationAndReadStats = runtimeAssembly::stopGenerationAndReadStats,
                discardPendingAssistantOutput = { discardPendingAssistantOutput() },
                invalidateConversationContext = ::markLocalConversationContextInvalid,
                persistChatSessions = { persistChatSessions() },
            )
        }
    }

    fun onAppBackgrounded() {
        chatRuns.background()
        uiStateStore.statusWriter().update { state ->
            val imageActive = state.imageJobs.any { job -> !job.status.terminal }
            if ((state.isGenerating || imageActive) && state.statusMessage != BACKGROUND_TASK_STATUS) {
                state.copy(statusMessage = BACKGROUND_TASK_STATUS)
            } else state
        }
        if (agentTuningCoordinator.adaptiveTuningJob?.isActive == true) pauseAgentTuning()
    }

    fun onAppForegrounded() = appLifecycleCoordinator.onAppForegrounded()

    override fun onCleared() {
        // The Agent/engine root is process-owned (ADR-0008) and settles on the last Activity finish.
        // Page teardown only stops UI-owned work; it must not close the shared runtime.
        container.runtimeAdmission.chatImagePromptBridgeJob?.cancel()
        // The Local API runtime owner is process-scoped (ADR-0008): page teardown retires this
        // page's listener requests but must not clear the bindings a recreated page reuses.
        localApiServerCoordinator.retireLocalApiListener(stopForegroundService = false, forceServerStop = false)
        runtimeAssembly.dshHost.screenCaptureConsentOwner.closePending()
        super.onCleared()
    }

    private val ports = runtimeAssembly.createPorts(
        store = uiStateStore, imageGenerationOwner = imageGenerationOwner,
        chatImageCommitJournalProvider = { container.chatImageCommitJournal }, localApiPayloadPortProvider = { localApiControlPlaneCoordinator.localApiPayloadPort },
        benchmarkRunner = benchmarkRunner, buildRecommendationAction = ::buildRecommendation, generateLocalApiImageAction = ::generateLocalApiImage,
        generateLocalApiVideoAction = ::generateLocalApiVideo, stopLocalApiVideoAction = ::stopLocalApiVideo,
        cancelActiveLocalApiImageGenerationAction = ::cancelActiveLocalApiImageGeneration, chatContextComposer = chatContextComposer, showStatus = ::showStatus,
        chatImageOptionsForCurrentSelection = ::chatImageOptionsForCurrentSelection, offlinePromptTranslationServiceProvider = { offlinePromptTranslationService },
        verifiedOfflineTranslationBundleRef = { offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle() },
        chatImageNegativePromptSupported = ::chatImageNegativePromptSupported, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        stopGenerationInProgress = stopGenerationInProgress, currentReplyMetricsRef = { replyGenerationMetrics }, cancelGenerationJobRef = { cancelGenerationJob() },
        flushPendingAssistantOutputRef = { flushPendingAssistantOutput() }, persistConversationMutationRef = { sessions, rollback -> persistConversationMutation(sessions, rollback) }
    )

    private val chatSessionPersistencePort: ChatSessionPersistencePort = ports.chatSessionPersistencePort
    internal val chatImageRequestPort: ChatImageRequestPort = ports.chatImageRequestPort
    private val sendChatImageRequestPort: SendChatImageRequestPort = ports.sendChatImageRequestPort
    private val chatImageRetryPort: ChatImageRetryPort = ports.chatImageRetryPort
    internal val imageLibraryCommitPort: ImageLibraryCommitPort = ports.imageLibraryCommitPort
    internal val imageAssetDeletionPort: ImageAssetDeletionPort get() = chatImageWorkflowCoordinator.imageAssetDeletionPort

    internal val chatImageWorkflowCoordinator: ChatImageWorkflowCoordinator = injectedChatImageWorkflowCoordinator ?: runtimeAssembly.createChatImageWorkflowCoordinator(
        store = uiStateStore, imageAssetWriteMutex = container.imageAssetWriteMutex, imageLibraryMutationMutex = container.imageLibraryMutationMutex,
        imageLibraryBackup = imageLibraryBackup, chatSessionStore = chatSessionStore, imageGenerationOwner = imageGenerationOwner, imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
        chatImageRetryPort = chatImageRetryPort, showStatus = ::showStatus,
        offlinePromptTranslationService = { offlinePromptTranslationService }, runtimeProfileStore = container.runtimeProfileStore,
        selectCloudImageModel = ::selectCloudImageModel
    )

    internal val chatWorkflowCoordinator: ChatWorkflowCoordinator = injectedChatWorkflowCoordinator ?: container.createChatWorkflowCoordinator(
        store = uiStateStore, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress, showStatus = ::showStatus,
        isChatImageRequestActive = ::isChatImageRequestActive, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
        persistConversationMutation = { sessions, rollback, preserveNativePrefix -> persistConversationMutation(sessions = sessions, rollback = rollback, preserveReusableNativePrefix = preserveNativePrefix) }
    )

    fun deleteImageAsset(imageId: String) = deleteImageAssets(listOf(imageId))
    fun deleteImageAssets(imageIds: List<String>) = deleteImageAssetsOperation(imageAssetDeletionPort, imageIds)
    fun setImageAssetFavorite(imageId: String, favorite: Boolean) = setImageAssetFavoriteOperation(imageAssetDeletionPort, imageId, favorite)
    fun clearImageLibrary() {
        launchSettingsCleanup(SettingsCleanupOperation.IMAGE_LIBRARY) {
            clearImageLibraryOperation(imageAssetDeletionPort)
        }
    }

    fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?) {
        val frozen = selections?.toList()
        val valid = frozen?.let { runCatching { resolveCurrentLocalImageLorasFor(it, uiStateStore.current.localImageLoras) }.isSuccess } == true
        uiStateStore.chatLoraSelectionWriter().update { it.copy(chatImageLoraSelections = frozen.takeIf { valid }) }
    }

    fun reportMissingLocalImageLoraSelection() {
        val state = uiStateStore.current
        val currentSelections = state.chatImageLoraSelections
        if (!currentSelections.isNullOrEmpty()) {
            val valid = runCatching { resolveCurrentLocalImageLorasFor(currentSelections, state.localImageLoras) }.isSuccess
            if (!valid) {
                uiStateStore.chatLoraSelectionWriter().update { it.copy(chatImageLoraSelections = null) }
                showStatus("所选 LoRA 已删除，请重新选择。")
            }
        }
    }

    private val localApiAuxProviderPort: LocalApiAuxProviderPort = ports.localApiAuxProviderPort
    private val chatLocalApiProviderPort: ChatLocalApiProviderPort = ports.chatLocalApiProviderPort
    private val chatExecutionPort: ChatGenerationExecutionPort get() = coordinatorAssembly.chatExecutionPort
    private val chatImagePromptBridgePort: ChatImagePromptBridgePort = ports.chatImagePromptBridgePort
    private val chatStopGenerationPort: ChatStopGenerationPort = ports.chatStopGenerationPort

    internal fun markLocalConversationContextInvalid() = runtimeAssembly.markLocalConversationContextInvalid(container.runtimeAdmission, uiStateStore)
    internal fun rejectWhileConversationMutationInProgress(): Boolean = rejectWhileConversationMutationInProgressInto(uiStateStore, container.runtimeAdmission)

    internal suspend fun ensureLocalConversationContextInvalidated(): Boolean {
        conversationMutationBarrier?.join()
        localConversationContextInvalidationJob?.join()
        if (!localConversationContextNeedsInvalidation) return true
        val sequence = localConversationContextInvalidationSequence.get()
        return runtimeAssembly.invalidateConversationContext()
            .onSuccess { if (localConversationContextInvalidationSequence.get() == sequence) localConversationContextNeedsInvalidation = false }
            .onFailure { error ->
                if (localConversationContextInvalidationSequence.get() == sequence) {
                    uiStateStore.generationLifecycleWriter().update { it.copy(isGenerating = false, generationPhase = null, generationTokenProgress = null, generationPersistProgress = null, statusMessage = "无法重置本地上下文，已取消本轮生成：${error.message ?: "native runtime error"}") }
                }
            }.isSuccess
    }

    internal fun persistChatSessions(
        sessions: List<ChatSessionRecord>? = null, knowledgeBinding: Pair<String, Set<String>>? = null, generationRunId: Long? = null
    ): Job? = runChatSessionPersistence(chatSessionPersistencePort, sessions, knowledgeBinding, generationRunId)

    private fun persistConversationMutation(
        sessions: List<ChatSessionRecord>, rollback: ConversationMutationRollbackState, preserveReusableNativePrefix: Boolean = false,
        onCommitted: (() -> Unit)? = null, onCommitFailed: (() -> Unit)? = null
    ): Job = runPersistConversationMutation(chatSessionPersistencePort, sessions, rollback, preserveReusableNativePrefix, onCommitted, onCommitFailed)

    private suspend fun generateLocalApiImage(requestId: String, body: String): String = chatImageWorkflowCoordinator.generateLocalApiImage(requestId, body)

    /**
     * Authenticated `POST /v1/video`. It lowers the request onto the canonical MCA-native workflow
     * and waits on the single process video owner; it never creates a second owner or worker.
     */
    private val localApiVideoOperations: LocalApiVideoOperations by lazy {
        LocalApiVideoOperations(
            port = OwnerBackedLocalApiVideoExecutionPort(videoWorkflowPort),
            modelPaths = { runtimeAssembly.videoHost.localModelPaths },
            mediaByRun = { runId -> runtimeAssembly.videoHost.mediaByRun(runId) },
            executionDetailByRun = { runId -> runtimeAssembly.videoHost.effectiveExecutionDetail(runId) },
        )
    }

    private suspend fun generateLocalApiVideo(requestId: String, body: String): String =
        localApiVideoOperations.generate(requestId, body)

    private suspend fun stopLocalApiVideo(requestId: String): Boolean =
        localApiVideoOperations.stop(requestId)
    internal fun persistApiPreferences(apiEnabled: Boolean, restEnabled: Boolean) = GenerationPreferences.persistApiPreferences(context = getApplication(), apiEnabled = apiEnabled, restEnabled = restEnabled)
    private fun loadPersistentPrefixCacheEnabled(application: Application): Boolean = GenerationPreferences.loadPersistentPrefixCacheEnabled(application)
    private fun persistPersistentPrefixCacheEnabled(application: Application, enabled: Boolean) = GenerationPreferences.persistPersistentPrefixCacheEnabled(application, enabled)
    private fun apiUrl(host: String): String = localApiRuntimeOwner.apiUrl(host)
    private fun currentOpenApiAddress(): String = localApiRuntimeOwner.currentOpenApiAddress(detectLanIpAddress())
}
