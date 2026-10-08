package com.muyuchat.mca

import android.app.Application
import com.muyuchat.mca.composition.MainCoordinatorAssembly
import com.muyuchat.mca.composition.MainCoordinatorHost
import com.muyuchat.mca.composition.MainCoordinatorHostAdapter
import com.muyuchat.mca.composition.McaAppContainer
import com.muyuchat.mca.composition.MainViewModelPortFactory
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ResumableDownloader
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.compressChatRequestContext
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.localContextWindowAdmission
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentTuningMode
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import com.muyuchat.mca.presentation.afterClearChatGenerationStopped
import com.muyuchat.mca.presentation.withGenerationProgressCleared
// Three P2 extractions are intentionally NOT imported here: afterBackgroundGenerationStopped,
// withBackgroundCancellationFinalized and BackgroundCancelledAssistantOutput have no call site in
// this ViewModel, and MainViewModelCompletionFallbackTest asserts that stays true. Importing them
// only produced unused-import warnings, so the imports were removed rather than left dangling.
// The declarations remain in presentation/GenerationStateTransitions.kt.
import com.muyuchat.mca.presentation.StoreKnowledgeLibraryPort
import com.muyuchat.mca.presentation.importWorldBook
import com.muyuchat.mca.selectChatSession

enum class AppTab(val title: String) {
    CHAT("聊天"),
    AGENT("智能调参"),
    MODELS("模型管理"),
    API("本地 API"),
    SETTINGS("系统设置")
}
class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val deferStartup: Boolean = false,
    internal val skipInitialModelDiscovery: Boolean = false,
    injectedEngine: McaInferenceService? = null,
    injectedLocalApiRuntimeOwner: Any? = null,
    injectedChatAppearanceCoordinator: Any? = null,
    injectedMainPostStartupCoordinator: Any? = null,
    injectedChatSessionManagementCoordinator: Any? = null,
    injectedChatInputAndAttachmentCoordinator: Any? = null,
    injectedChatImageWorkflowCoordinator: Any? = null,
    injectedChatWorkflowCoordinator: Any? = null,
    injectedDocumentImportCoordinator: Any? = null,
    injectedAppLifecycleCoordinator: Any? = null,
    injectedCacheManagementCoordinator: Any? = null
) : AndroidViewModel(application) {
    companion object {
        private val localApiProcessLifecycleLock = Any()
        private var localApiProcessOwnerToken: Any? = null
        private const val REST_PORT = 11435
        private const val BACKGROUND_TASK_STATUS = "已转入后台运行，返回 MCA 后查看进度。"
        private const val LOCAL_API_FOREGROUND_READY_TIMEOUT_MS = 3_000L
        private const val LOCAL_API_FOREGROUND_READY_ATTEMPTS = 2
        private const val IMAGE_UPSCALER_PREFERENCES = "image_upscaler_product_selection_v1"
        private const val CONTEXT_COMPRESSION_PREFERENCES = "mca_context_compression_v1"
        internal const val MAX_DIRECT_COMPOSER_UTF8_BYTES = 256 * 1024
        private const val ASSISTANT_MODEL_MODE_FOLLOW_CURRENT = "follow_current"
        private const val ASSISTANT_MODEL_MODE_LOCAL = "local"
        private const val ASSISTANT_MODEL_MODE_CLOUD = "cloud"
        const val CLOUD_MODEL_CHOICE_PREFIX = "cloudmodel:"
        const val CLOUD_IMAGE_MODEL_CHOICE_PREFIX = "cloudimagemodel:"
        const val LOCAL_IMAGE_MODEL_CHOICE_PREFIX = "localimagemodel:"
    }

    internal val modelIdentityReferenceMigration = ModelIdentityReferenceMigration(application)
    private val restoredModelIdentityReferences = runBlocking(Dispatchers.IO) {
        modelIdentityReferenceMigration.migratePendingReferences()
    }
    // R2/S12：组合根接管装配（本片 7 个 application-only store；1:1 构造，零行为变化）。
    internal val container = McaAppContainer(application)
    // R3/C2（插队裁定 2/3）：装配与运行期准入态迁入组合根；以下别名保持既有调用点与初始化时点不变。
    private val modelStore = container.modelStore
    private val runtimeProfileStore = container.runtimeProfileStore
    /** User-facing sampling/reasoning values, isolated by local/cloud model identity. */
    private val modelGenerationProfileStore = container.modelGenerationProfileStore
    internal val installationScopeId = runBlocking(Dispatchers.IO) {
        runtimeProfileStore.installationScopeId()
    }
    internal val modelScopeClient = ModelScopeClient()
    private val downloader = ResumableDownloader()
    /** R3-S37：应用更新端口具名适配器（容器工厂装配，出清 11 行匿名实现与 3 处 this@VM）。 */
    private val appUpdatePort: AppUpdatePort =
        container.createAppUpdatePort { uiStateStore }

    /** The single owner of the in-app update flow. */
    private val appUpdateOwner = AppUpdateOwner(appUpdatePort, viewModelScope)

    private val initialAppUpdateState = appUpdateOwner.loadInitialAppUpdateState()

    /** Delegates to [AppUpdateOwner] methods. */
    fun maybeCheckForAppUpdate() = appUpdateOwner.maybeCheckForAppUpdate()
    fun checkForAppUpdate() = appUpdateOwner.checkForAppUpdate()
    fun setAppUpdateAutoCheckEnabled(enabled: Boolean) = appUpdateOwner.setAppUpdateAutoCheckEnabled(enabled)
    fun downloadAppUpdate() = appUpdateOwner.downloadAppUpdate()
    fun installAppUpdate() = appUpdateOwner.installAppUpdate()
    fun openAppUpdateRelease() = appUpdateOwner.openAppUpdateRelease()

    internal val engine: McaInferenceService = injectedEngine ?: container.createInferenceService(
        runners = container.isolatedLocalChatRunners.runners,
        installationScopeId = installationScopeId
    )
    private val apiKey = GenerationPreferences.loadOrCreateApiKey(application)
    internal val localApiRuntimeOwner: LocalApiRuntimeOwner =
        (injectedLocalApiRuntimeOwner as? LocalApiRuntimeOwner) ?: container.createLocalApiRuntimeOwner()
    private val initialApiPreferences = loadApiPreferences(application)
    /** Listener, notification, persisted intent, and UI state share this single lifecycle gate. */
    private val apiLifecycleMutex get() = localApiServerCoordinator.apiLifecycleMutex
    private val apiLifecycleSequence get() = localApiServerCoordinator.apiLifecycleSequence
    private val apiLifecycleClosed get() = localApiServerCoordinator.apiLifecycleClosed
    private var apiLifecycleRequestJob: Job?
        get() = localApiServerCoordinator.apiLifecycleRequestJob
        set(value) { localApiServerCoordinator.apiLifecycleRequestJob = value }
    /** Deduplicates repeated process-foreground callbacks and their worker health probes. */
    internal var foregroundRecoveryJob: Job? = null
    private val foregroundRecoverySequence = AtomicLong(0L)
    internal val chatSessionStore = container.chatSessionStore
    internal val characterMemorySummaryCoordinator = CharacterMemorySummaryCoordinator()
    private val chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore
    private val worldBookStore = container.worldBookStore
    private val knowledgeBaseStore = container.knowledgeBaseStore
    internal val knowledgeLibraryPort = StoreKnowledgeLibraryPort(worldBookStore, knowledgeBaseStore)
    internal val chatContextComposer =
        ChatContextComposer(StoreChatContextSources(worldBookStore, knowledgeBaseStore))
    private val imageLibraryBackup: ImageLibraryBackup
        get() = container.imageLibraryBackup
    private val imageAssetDirectory: File
        get() = container.imageAssetDirectory
    // R3/C3-S12：提交日志迁至容器（lazy 语义与构造参数逐字保留），此处为零抖动别名。
    private val chatImageCommitJournal: ChatImageCommitJournal
        get() = container.chatImageCommitJournal
    // R3/C3-S12：提交恢复窄端口（在 init/startAfterConstruction 之前初始化，与旧字段同序）。
    private val chatImageCommitRecoveryPort: ChatImageCommitRecoveryPort =
        container.createChatImageCommitRecoveryPort({ chatImageCommitJournal }, chatSessionStore)
    private val assistantStore = container.assistantStore


    private val cloudApiStore = container.cloudApiStore
    private val cloudChatProvider = container.cloudChatProvider
    internal val offlinePromptTranslationService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OfflinePromptTranslationService(AppOfflinePromptTranslationRuntimeProvider(application))
    }
    private val cloudImageProvider = container.cloudImageProvider
    private var activeWebSearchDeferred: Deferred<WebSearchResult>?
        get() = container.runtimeAdmission.activeWebSearchDeferred
        set(value) {
            container.runtimeAdmission.activeWebSearchDeferred = value
        }
    @Volatile internal var activeWebSearchTaskId: String? = null
    private val localImageModelStore = container.localImageModelStore
    private val localImageLoraStore = container.localImageLoraStore
    private val imageUpscalerPreferences: android.content.SharedPreferences
        get() = container.imageUpscalerPreferences
    internal val contextCompressionPreferences = application.getSharedPreferences(
        CONTEXT_COMPRESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val manualContextCompressionRequests: ContextCompressionRequestStore
        get() = container.manualContextCompressionRequests
    private val localImageWorkerClient: LocalImageWorkerClient
        get() = container.localImageWorkerClient
    private val deviceProfileReader = container.deviceProfileReader
    internal val benchmarkRunner = BenchmarkRunner(engine, deviceProfileReader)
    internal val agentLogger = AgentDecisionLogger(application)
    private val benchmarkHistoryLogger = container.benchmarkHistoryLogger
    // R3/C1: 诊断族读写随 C1 裁定 B 移入 DiagnosticsOperations（体逐字、评测点不变）。
    internal val diagnosticsOperations = DiagnosticsOperations(
        engine = engine,
        currentState = { uiStateStore.current },
        updateUiState = { transform -> uiStateStore.update(transform) },
        recentAgentLogs = { agentLogger.recent() },
        recentBenchmarkHistory = { benchmarkHistoryLogger.recent() },
        currentDeviceProfile = { currentDeviceProfile() },
        application = application
    )
    private val runtimeUserOverrideFields: MutableSet<String>
        get() = container.runtimeAdmission.runtimeUserOverrideFields
    private val initialParams = loadGenerationParams(application)
    private val initialAssistants = assistantStore.loadAssistants(initialParams)
    private val loadedChatSessions = chatSessionStore.load()
    private val initialStoredSelectedAssistantId = assistantStore.loadSelectedAssistantId(initialAssistants)
    internal val initialChatSessions = loadedChatSessions
        .withInterruptedChatImageRequests()
        .withBackfilledAssistantSnapshots(initialAssistants)
        .let { sessions ->
            val selected = initialAssistants.firstOrNull { it.id == initialStoredSelectedAssistantId }
            if (selected != null && sessions.latestSessionForAssistant(selected.id) == null) {
                listOf(selected.newConversationSession(modelMode = null, modelId = null)) + sessions
            } else sessions
        }
        .also { snapshots ->
            if (snapshots != loadedChatSessions) {
                runCatching { chatSessionStore.save(snapshots) }
            }
        }
    private val bootstrapResult = MainUiBootstrapFactory.create(
        application = application,
        container = container,
        engine = engine,
        modelScopeClient = modelScopeClient,
        agentLogger = agentLogger,
        benchmarkHistoryLogger = benchmarkHistoryLogger,
        deviceProfileReader = deviceProfileReader,
        chatSessionStore = chatSessionStore,
        worldBookStore = worldBookStore,
        knowledgeBaseStore = knowledgeBaseStore,
        cloudApiStore = cloudApiStore,
        localImageModelStore = localImageModelStore,
        localImageLoraStore = localImageLoraStore,
        imageUpscalerPreferences = imageUpscalerPreferences,
        modelGenerationProfileStore = modelGenerationProfileStore,
        contextCompressionPreferences = contextCompressionPreferences,
        apiKey = apiKey,
        currentOpenApiAddress = currentOpenApiAddress(),
        apiUrl = ::apiUrl,
        skipInitialModelDiscovery = skipInitialModelDiscovery,
        initialAppUpdateState = initialAppUpdateState,
        initialParams = initialParams,
        initialAssistants = initialAssistants,
        initialStoredSelectedAssistantId = initialStoredSelectedAssistantId,
        initialChatSessions = initialChatSessions
    )
    // R3/C3-S4：持久化状态 holder 的容器初始化（紧随初值装配完成之后）。
    private val chatSessionPersistenceStateInit = container.initializeChatSessionPersistenceState(initialChatSessions)
    @Volatile
    private var replyGenerationMetrics: ReplyGenerationMetrics? = null
    /** Prevents double taps from invalidating the stop epoch twice and losing UI cleanup. */
    private val stopGenerationInProgress = AtomicBoolean(false)
    /** New UI generations join this job before entering the engine, preventing a stale stop. */
    /** Invalidates late cleanup from a cancelled generation before another lifecycle operation. */
    /** Identity, cancellation and the published run handle for the UI chat run. */
    private val chatRuns: ChatRunOwnership
        get() = container.chatRuns
    private val chatStreamAccumulator: ChatStreamAccumulator
        get() = container.chatStreamAccumulator
    /** Delegates to [ChatStreamAccumulator.append]. Signature kept verbatim. */
    internal fun appendAssistant(
        delta: String,
        reasoningDelta: String = "",
        reasoningDurationMs: Long = 0L,
        forcePublish: Boolean = false,
        generationRunId: Long? = null
    ) = chatStreamAccumulator.append(
        delta = delta,
        reasoningDelta = reasoningDelta,
        reasoningDurationMs = reasoningDurationMs,
        forcePublish = forcePublish,
        generationRunId = generationRunId
    )

    /** Delegates to [ChatStreamAccumulator.flush]. Signature kept verbatim. */
    internal fun flushPendingAssistantOutput(generationRunId: Long? = null): Boolean =
        chatStreamAccumulator.flush(generationRunId)

    /** Delegates to [ChatStreamAccumulator.discard]. Signature kept verbatim. */
    internal fun discardPendingAssistantOutput(generationRunId: Long? = null) =
        chatStreamAccumulator.discard(generationRunId)

    private var localConversationContextNeedsInvalidation: Boolean
        get() = container.runtimeAdmission.localConversationContextNeedsInvalidation
        set(value) { container.runtimeAdmission.localConversationContextNeedsInvalidation = value }
    private val localConversationContextInvalidationSequence: AtomicLong
        get() = container.runtimeAdmission.localConversationContextInvalidationSequence
    private var localConversationContextInvalidationJob: Job?
        get() = container.runtimeAdmission.localConversationContextInvalidationJob
        set(value) { container.runtimeAdmission.localConversationContextInvalidationJob = value }
    internal val persistentPrefixCacheOperationSequence: AtomicLong
        get() = cacheManagementCoordinator.persistentPrefixCacheOperationSequence
    /**
     * A destructive conversation edit is published to Room before native KV
     * state is invalidated.  The barrier is joined by the next generation, so
     * a process/power interruption cannot make a new turn race the old KV.
    */
    private var conversationMutationBarrier: Job?
        get() = container.runtimeAdmission.conversationMutationBarrier
        set(value) { container.runtimeAdmission.conversationMutationBarrier = value }
    private var durableChatSessions: List<ChatSessionRecord>
        get() = container.chatSessionPersistenceState.durableChatSessions
        set(value) { container.chatSessionPersistenceState.durableChatSessions = value }
    private val chatSessionPersistenceMutex: Mutex get() = container.chatSessionPersistenceState.persistenceMutex
    private val chatSessionPersistenceSequence: AtomicLong get() = container.chatSessionPersistenceState.persistenceSequence
    private val chatSessionPersistenceStateLock: Any get() = container.chatSessionPersistenceState.stateLock
    private val pendingKnowledgeBindings: MutableMap<String, Set<String>> get() = container.chatSessionPersistenceState.pendingKnowledgeBindings
    private val pendingWorldBookCleanupOwners: MutableMap<WorldBookScope, MutableSet<String>> get() = container.chatSessionPersistenceState.pendingWorldBookCleanupOwners
    private var directParameterStageJob: Job?
        get() = container.runtimeAdmission.directParameterStageJob
        set(value) { container.runtimeAdmission.directParameterStageJob = value }
    private val directParameterStageMutex: Mutex get() = container.runtimeAdmission.directParameterStageMutex
    private val directParameterStageGeneration: AtomicLong get() = container.runtimeAdmission.directParameterStageGeneration
    private val managedRuntimeReadinessRefreshGate = container.readinessRefreshGate
    private val imageAssetWriteMutex: Mutex get() = container.imageAssetWriteMutex
    private val imageLibraryMutationMutex: Mutex get() = container.imageLibraryMutationMutex
    private val contentImportSelectionRevision: AtomicLong get() = container.runtimeAdmission.contentImportSelectionRevision
    internal fun captureContentImportOwner(): ContentImportOwner = ContentImportOwner(
        sessionId = uiStateStore.current.activeChatSessionId,
        assistantId = uiStateStore.current.selectedAssistantId,
        selectionRevision = contentImportSelectionRevision.get()
    )
    private fun ContentImportOwner.isCurrent(state: MainUiState): Boolean =
        selectionRevision == contentImportSelectionRevision.get() &&
            sessionId == state.activeChatSessionId && assistantId == state.selectedAssistantId

    /** Delegates to [ChatImageWorkflowCoordinator.upscaleImageAsset]. */
    fun upscaleImageAsset(imageId: String, targetScale: Int) =
        chatImageWorkflowCoordinator.upscaleImageAsset(imageId, targetScale)

    /** Delegates to [ChatImageWorkflowCoordinator.cancelImageUpscale]. */
    fun cancelImageUpscale() =
        chatImageWorkflowCoordinator.cancelImageUpscale()
    private var imageLibraryBackupJob: Job?
        get() = container.runtimeAdmission.imageLibraryBackupJob
        set(value) { container.runtimeAdmission.imageLibraryBackupJob = value }
    // R3/C2：运行期准入态迁入容器 holder（同实例共享，读写语义与调用点不变）。
    private var activeRuntimeIdentity: ModelRuntimeIdentity?
        get() = container.runtimeAdmission.activeRuntimeIdentity
        set(value) { container.runtimeAdmission.activeRuntimeIdentity = value }
    private var activeAdaptiveRecommendation: AdaptiveTuningRecommendation?
        get() = container.runtimeAdmission.activeAdaptiveRecommendation
        set(value) { container.runtimeAdmission.activeAdaptiveRecommendation = value }
    private var pendingProfileTransactionId: String?
        get() = container.runtimeAdmission.pendingProfileTransactionId
        set(value) { container.runtimeAdmission.pendingProfileTransactionId = value }
    private var activeModelForRuntimeProfile: ModelManifest?
        get() = container.runtimeAdmission.activeModelForRuntimeProfile
        set(value) { container.runtimeAdmission.activeModelForRuntimeProfile = value }
    internal val localApiIdempotencyJournalLock = Any()
    internal var activeImageGenerationJobId: String?
        get() = container.runtimeAdmission.activeImageGenerationJobId
        set(value) { container.runtimeAdmission.activeImageGenerationJobId = value }
    internal var activeImageGenerationBackend: ImageBackend?
        get() = container.runtimeAdmission.activeImageGenerationBackend
        set(value) { container.runtimeAdmission.activeImageGenerationBackend = value }
    private var activeImageGenerationModelId: String?
        get() = container.runtimeAdmission.activeImageGenerationModelId
        set(value) { container.runtimeAdmission.activeImageGenerationModelId = value }
    // R3-S30：activeLocalImageWorkerRequestId 已入 RuntimeAdmissionState；读点直改容器读。
    private var activeLocalApiImageModelId: String?
        get() = container.runtimeAdmission.activeLocalApiImageModelId
        set(value) { container.runtimeAdmission.activeLocalApiImageModelId = value }
    private val localImageGenerationCoordinator: LocalImageGenerationCoordinator get() = container.localImageGenerationCoordinator
    private val localImageGenerationCoordinatorObservationLock: Any get() = container.localImageGenerationCoordinatorObservationLock
    private val localApiImageGenerationLifecycleLock = Any()
    private var activeLocalApiImageGenerationOwnership: LocalApiImageGenerationOwnership? = null

    private suspend fun awaitImageLibraryStartupReconciliation() =
        imageLibraryStartupReconciliation.await()

    fun deleteCloudModel(modelId: String) =
        chatImageWorkflowCoordinator.deleteCloudModel(modelId)

    fun confirmChatImageTranslationDraft(id: String, positive: String, negative: String?) =
        chatImageWorkflowCoordinator.confirmChatImageTranslationDraft(id, positive, negative)

    fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec? =
        chatImageWorkflowCoordinator.buildChatImageRetrySnapshot(request, chatSessionId)

    internal val uiStateStore = UiStateStore(bootstrapResult.uiState)
    // R3/C3-S5：chatRuns/流缓冲累加器入容器；terminalSettlement 端口具名化。
    private val chatStreamAccumulatorInit = container.initializeChatStreamAccumulator(uiStateStore, viewModelScope)

    internal val chatAppearanceCoordinator: ChatAppearanceCoordinator =
        (injectedChatAppearanceCoordinator as? ChatAppearanceCoordinator)
            ?: container.createChatAppearanceCoordinator(
                scope = viewModelScope,
                uiStateStore = uiStateStore,
                showStatus = ::showStatus
            )

    // R3/C3-S13：启动对账 owner（lazy async + await 状态写随 body 移出；start() 调用点不变；
    // 声明位于 init/startAfterConstruction 之前，保持与旧字段等价的初始化序）。
    internal val imageLibraryStartupReconciliation = container.createImageLibraryStartupReconciliation(
        uiStateStore, viewModelScope, chatImageCommitRecoveryPort, bootstrapResult.initialKnowledgeBases
    )
    internal val chatTerminalSettlementPort = container.createChatTerminalSettlementPort(uiStateStore)
    // R3/C3-S3：token 测量 host 具名化——容器工厂构造，两个缓存随迁（仅本端口使用）。
    internal val imagePromptTokenMeasurementHost = container.createImagePromptTokenMeasurementHost(uiStateStore)
    internal val webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator =
        container.createWebSearchWorkflowCoordinator(uiStateStore, chatTerminalSettlementPort, viewModelScope)
    private val cacheManagementCoordinator: CacheManagementCoordinator =
        (injectedCacheManagementCoordinator as? CacheManagementCoordinator)
            ?: CacheManagementCoordinator(
                application = getApplication(),
                store = uiStateStore,
                scope = viewModelScope,
                engine = engine,
                chatRuns = chatRuns,
                refreshPersistentPrefixCacheSummary = { refreshPersistentPrefixCacheSummary() },
                markLocalConversationContextInvalid = { markLocalConversationContextInvalid() }
            )
    internal val visionAttachmentPort = container.createVisionAttachmentPort(uiStateStore, engine)
    // R3/C2（插队裁定 2）：TuningOwner 由组合根工厂构造（真实依赖注入）；VM 仅 intent 委托。
    internal val tuningOwner = container.createTuningOwner(
        store = uiStateStore,
        scope = viewModelScope,
        engine = engine
    )
    // R3/C2: 生图准入/执行唯一 owner（C0-a）：三处端口实现与两个直连调用点收敛到同一实例。
    // R3/C2-S3: host 由 McaAppContainer 工厂装配具名适配器，出清 97 行匿名 object 与 33 处 this 引用。
    internal val imageGenerationOwner: ImageGenerationOwner = container.createImageGenerationOwner(
        store = uiStateStore,
        scope = viewModelScope,
        engine = engine,
        imageLibraryStartupReconciliationProvider = { imageLibraryStartupReconciliation },
        imageLibraryCommitPortProvider = { imageLibraryCommitPort },
        chatImageRequestPortProvider = { chatImageRequestPort },
        imagePromptTranslationPortProvider = { imagePromptTranslationPort },
        completeAssistantImageToolTurnForJob = { assistantImageContinuationCoordinator.completeAssistantImageToolTurnForJob(it) }
    )
    val uiState = uiStateStore.state

    // R1/S10 过渡访问器（R3/S23 更新：写入侧已全部迁经 uiStateStore.update，本访问器现只服务
    // `.value` 读取与 5 处 inner port 的 MutableStateFlow 暴露）；删除条件：读取站点迁到 current/
    // 只读投影、port 契约收敛到只读 state 之后（R2–R4）。
    private val _uiState: MutableStateFlow<MainUiState> get() = uiStateStore.ownerFlow

    /**
     * The single writer for the transient status line the shell shows the user.
     *
     * `statusMessage` is App Shell state: it is global, short-lived, and was previously assigned
     * from 406 places across this file. Those assignments came in two genuinely different kinds,
     * and only one of them belongs behind a helper:
     *
     * - setting *only* the status (a plain notification) -- that is what this function is for;
     * - updating the status **together with other fields in one `copy(...)`** (for example
     *   `copy(busy = false, statusMessage = "...")`). Splitting those into two updates would turn
     *   one atomic state change into two, so they keep their atomic form on purpose. They are not
     *   a second writer of *this* operation; they are a different operation that happens to carry a
     *   status.
     *
     * New code that only wants to say something to the user must call this, not `copy` directly.
     * `StatusMessageWriterContractTest` fails if a new single-field assignment appears.
     */
    internal fun showStatus(message: String?) {
        // R1/S11: 首个接入 UiStateStore 的生产写路径（同一底层流，CAS 语义不变）。
        // R3/C2: 漏斗体下移至 UiStateStore.showStatus；本方法保留为 VM 内唯一入口委托。
        uiStateStore.showStatus(message)
    }

    /**
     * Publishes coordinator ownership before admission so Compose cannot release a generation-owned
     * content grant while UI, authenticated Local API, or upscale dispatch is materializing inputs.
     * A failed contender keeps the flag asserted when another epoch still owns the coordinator.
     */
    /** Delegates to [tryAcquireObservedImageGenerationLeaseFor]. Signature kept verbatim. */
    internal fun tryAcquireObservedImageGenerationLease(
        requestId: String
    ): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(
            store = uiStateStore,
            coordinator = localImageGenerationCoordinator,
            observationLock = localImageGenerationCoordinatorObservationLock,
            requestId = requestId
        )

    /** Delegates to [releaseObservedImageGenerationLeaseFor]. Signature kept verbatim. */
    internal fun releaseObservedImageGenerationLease(
        lease: LocalImageGenerationCoordinator.Lease
    ): Boolean = releaseObservedImageGenerationLeaseFor(
        store = uiStateStore,
        coordinator = localImageGenerationCoordinator,
        observationLock = localImageGenerationCoordinatorObservationLock,
        lease = lease
    )

    /**
     * Linearizes persistable-grant release with coordinator admission. The release block runs only
     * while no UI, authenticated Local API, or upscale epoch owns the coordinator.
     */
    fun releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock: () -> Unit): Boolean =
        chatImageWorkflowCoordinator.releaseGenerationImageGrantsIfCoordinatorIdle(releaseBlock)

    private fun registerLocalApiImageGenerationOwnership(
        ownership: LocalApiImageGenerationOwnership
    ) {
        synchronized(localApiImageGenerationLifecycleLock) {
            check(activeLocalApiImageGenerationOwnership == null) {
                "Another Local API image request already owns cancellation."
            }
            activeLocalApiImageGenerationOwnership = ownership
        }
    }

    private fun unregisterLocalApiImageGenerationOwnership(
        ownership: LocalApiImageGenerationOwnership
    ): Boolean = synchronized(localApiImageGenerationLifecycleLock) {
        if (activeLocalApiImageGenerationOwnership !== ownership) {
            false
        } else {
            activeLocalApiImageGenerationOwnership = null
            true
        }
    }

    /** The lifecycle lock prevents a stale stop snapshot from cancelling a newer UI request. */
    private fun cancelActiveLocalApiImageGeneration(reason: String): Boolean =
        synchronized(localApiImageGenerationLifecycleLock) {
            val ownership = activeLocalApiImageGenerationOwnership
                ?: return@synchronized false
            localImageWorkerClient.cancel(ownership.requestId)
            ownership.requestJob.cancel(CancellationException(reason))
            true
        }

    private fun cancelOwnedLocalApiImageWorker(
        ownership: LocalApiImageGenerationOwnership
    ): Boolean = synchronized(localApiImageGenerationLifecycleLock) {
        if (activeLocalApiImageGenerationOwnership !== ownership) {
            false
        } else {
            localImageWorkerClient.cancel(ownership.requestId)
            true
        }
    }

    private val localApiServerCoordinator = LocalApiServerCoordinator(
        application = application,
        scope = viewModelScope,
        store = uiStateStore,
        localApiRuntimeOwner = localApiRuntimeOwner,
        apiKey = apiKey,
        cancelActiveLocalApiImageGeneration = ::cancelActiveLocalApiImageGeneration,
        persistApiPreferences = ::persistApiPreferences,
        apiUrl = ::apiUrl,
        currentOpenApiAddress = ::currentOpenApiAddress,
        onInterruptForegroundRecovery = {
            foregroundRecoverySequence.incrementAndGet()
            foregroundRecoveryJob?.cancel()
        }
    )

    private fun updateGenerationImageGrantReleaseDefer(defer: Boolean) =
        chatImageWorkflowCoordinator.updateGenerationImageGrantReleaseDefer(defer)

    internal fun observeManagedDownloads() {
        modelImportAndDownloadCoordinator.observeManagedDownloads { image ->
            val selection = settleLocalImageSelection(image)
            uiStateStore.update { it.copy(
                localImageModels = selection.models,
                qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId,
                selectedLocalImageModelId = selection.selectedId,
                selectedImageBackend = selection.selectedBackend
            ) }
        }
    }

    private val coordinatorHost: MainCoordinatorHost = MainCoordinatorHostAdapter(this)

    private val coordinatorAssembly: MainCoordinatorAssembly = MainCoordinatorAssembly.create(
        host = coordinatorHost,
        injectedMainPostStartupCoordinator = injectedMainPostStartupCoordinator,
        injectedChatSessionManagementCoordinator = injectedChatSessionManagementCoordinator,
        injectedChatInputAndAttachmentCoordinator = injectedChatInputAndAttachmentCoordinator,
    )

    private val modelImportAndDownloadCoordinator get() = coordinatorAssembly.modelImportAndDownloadCoordinator
    private val localImageLibraryAndModelCoordinator get() = coordinatorAssembly.localImageLibraryAndModelCoordinator
    internal val assistantImageContinuationCoordinator get() = coordinatorAssembly.assistantImageContinuationCoordinator
    private val offlinePromptTranslationAndChatImageCoordinator get() = coordinatorAssembly.offlinePromptTranslationAndChatImageCoordinator
    private val agentTuningCoordinator get() = coordinatorAssembly.agentTuningCoordinator
    private val chatModelCoordinator get() = coordinatorAssembly.chatModelCoordinator
    private val localApiControlPlaneCoordinator get() = coordinatorAssembly.localApiControlPlaneCoordinator
    private val modelScopeHubCoordinator get() = coordinatorAssembly.modelScopeHubCoordinator
    private val assistantProfileCoordinator get() = coordinatorAssembly.assistantProfileCoordinator
    internal val contextCompressionCoordinator get() = coordinatorAssembly.contextCompressionCoordinator
    private val managedRuntimeReadinessPort get() = coordinatorAssembly.managedRuntimeReadinessPort
    private val contentImportPreviewCoordinator get() = coordinatorAssembly.contentImportPreviewCoordinator
    private val mainPostStartupCoordinator get() = coordinatorAssembly.mainPostStartupCoordinator
    private val chatSessionManagementCoordinator get() = coordinatorAssembly.chatSessionManagementCoordinator
    private val chatInputAndAttachmentCoordinator get() = coordinatorAssembly.chatInputAndAttachmentCoordinator

    private val documentImportCoordinator: DocumentImportCoordinator =
        (injectedDocumentImportCoordinator as? DocumentImportCoordinator)
            ?: DocumentImportCoordinator(
                application = application,
                uiStateStore = uiStateStore,
                scope = viewModelScope,
                knowledgeBaseStore = knowledgeBaseStore,
                chatSessionStore = chatSessionStore,
                chatSessionPersistenceState = container.chatSessionPersistenceState,
                contentImportSelectionRevision = contentImportSelectionRevision,
                stageContentImportPreview = { stageContentImportPreview(it) },
                markLocalConversationContextInvalid = { markLocalConversationContextInvalid() },
                showStatus = { showStatus(it) }
            )

    private val appLifecycleCoordinator: AppLifecycleCoordinator =
        (injectedAppLifecycleCoordinator as? AppLifecycleCoordinator)
            ?: AppLifecycleCoordinator(
                application = application,
                uiStateStore = uiStateStore,
                scope = viewModelScope,
                engine = engine,
                chatRuns = chatRuns,
                localApiServerCoordinator = localApiServerCoordinator,
                offlinePromptTranslationAndChatImageCoordinator = offlinePromptTranslationAndChatImageCoordinator,
                assistantImageContinuationCoordinator = assistantImageContinuationCoordinator,
                maybeCheckForAppUpdate = { maybeCheckForAppUpdate() },
                foregroundRecoverySequence = foregroundRecoverySequence,
                getForegroundRecoveryJob = { foregroundRecoveryJob },
                setForegroundRecoveryJob = { foregroundRecoveryJob = it }
            )

    private val _uiEffects = kotlinx.coroutines.flow.MutableSharedFlow<com.muyuchat.mca.presentation.MainUiEffect>(extraBufferCapacity = 64)
    val uiEffects: kotlinx.coroutines.flow.SharedFlow<com.muyuchat.mca.presentation.MainUiEffect> = _uiEffects

    fun emitEffect(effect: com.muyuchat.mca.presentation.MainUiEffect) {
        _uiEffects.tryEmit(effect)
    }

    fun processIntent(intent: com.muyuchat.mca.presentation.MainUiIntent) {
        when (intent) {
            is com.muyuchat.mca.presentation.MainUiIntent.SelectTab -> selectTab(intent.tab)
            is com.muyuchat.mca.presentation.MainUiIntent.SendChatMessage -> {
                onInputChange(intent.prompt)
                sendMessage()
            }
            is com.muyuchat.mca.presentation.MainUiIntent.CancelGeneration -> stopGeneration()
            is com.muyuchat.mca.presentation.MainUiIntent.StartImageGeneration -> generateImageAsset(intent.prompt)
            is com.muyuchat.mca.presentation.MainUiIntent.SelectModel -> selectChatModel(intent.modelId)
            is com.muyuchat.mca.presentation.MainUiIntent.ClearCurrentChat -> clearChat()
            is com.muyuchat.mca.presentation.MainUiIntent.CheckAppUpdate -> checkForAppUpdate()
            is com.muyuchat.mca.presentation.MainUiIntent.ShowStatusMessage -> showStatus(intent.message)
        }
    }

    fun selectTab(tab: AppTab) {
        uiStateStore.update { it.copy(tab = tab) }
        if (tab == AppTab.MODELS) {
            viewModelScope.launch(Dispatchers.IO) {
                refreshRuntimeReadiness(managedRuntimeReadinessPort)
            }
        }
    }

    init {
        if (!deferStartup) startAfterConstruction()
    }

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
        claimLocalApiProcessOwnership()
        LocalApiRuntime.claimOwner(localApiRuntimeOwner) {
            // Runtime callbacks change owner, while the foreground service keeps its listener.
            retireLocalApiListener(
                stopForegroundService = false,
                forceServerStop = false
            )
        }
        installChatLocalApiProviders(chatLocalApiProviderPort)
        localApiRuntimeOwner.publishBindings(
            auxProviderPort = localApiAuxProviderPort,
            controlPlane = localApiControlPlaneCoordinator.createControlPlane()
        )

        if (initialApiPreferences.apiEnabled) {
            requestLocalApiState(
                enabled = true,
                restEnabled = initialApiPreferences.restEnabled,
                failurePrefix = "本机 API 自动恢复失败"
            )
        }
    }

    /** Delegates to [publishChatCatalogSnapshot]. */
    internal fun publishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot =
        publishChatCatalogSnapshot(managedRuntimeReadinessPort)


    /** Refreshes product-facing readiness; persisted verification remains advisory. */

    fun onInputChange(value: String) =
        chatInputAndAttachmentCoordinator.onInputChange(value)

    fun clearStatusMessage() =
        chatInputAndAttachmentCoordinator.clearStatusMessage()

    fun startBrowserTask(request: com.muyuchat.feature.chat.BrowserTaskLaunch) =
        startBrowserTaskFor(uiStateStore, request)

    fun onBrowserTaskEvent(event: com.muyuchat.feature.chat.BrowserTaskEvent) =
        onBrowserTaskEventFor(uiStateStore, { activeWebSearchTaskId }, { activeWebSearchDeferred?.cancel() }, event)


    /**
     * Schedules a one-shot compression before the next chat request.  The
     * request is deliberately consumed by startGeneration so the stored
     * conversation is never rewritten behind the user's back.
     */
    fun requestContextCompression() = contextCompressionCoordinator.requestContextCompression()

    fun toggleMessagePinned(messageId: String) = contextCompressionCoordinator.toggleMessagePinned(messageId)

    fun undoContextSummary() = contextCompressionCoordinator.undoContextSummary()

    fun contextSummaryEvidenceForQuery(query: String): List<com.muyuchat.feature.chat.ContextSummaryEvidenceUiItem> =
        contextCompressionCoordinator.contextSummaryEvidenceForQuery(query)

    /** Persists one of the supported automatic thresholds and updates the UI. */
    fun setContextCompressionThreshold(percent: Int) =
        contextCompressionCoordinator.setContextCompressionThreshold(percent)

    fun setContextSummaryModel(selection: String) =
        contextCompressionCoordinator.setContextSummaryModel(selection)

    fun attachFile(uriString: String) =
        chatInputAndAttachmentCoordinator.attachFile(uriString)



    fun useImageAsset(imageId: String) =
        localImageLibraryAndModelCoordinator.useImageAsset(imageId)

    /**
     * Measures how many tokens a prompt will occupy for a local image model.
     *
     * The decision moved to [measureImagePromptTokens]; this keeps the host: the catalogue the
     * measurement resolves against and the two process-local caches that survive between calls.
     */
    suspend fun measureImagePromptTokens(
        modelChoiceId: String,
        prompt: String,
    ): ImagePromptTokenMeasurement? = chatInputAndAttachmentCoordinator.measureImagePromptTokens(
        modelChoiceId = modelChoiceId,
        prompt = prompt
    )

    fun useFileAsset(fileId: String) =
        localImageLibraryAndModelCoordinator.useFileAsset(fileId)

    fun deleteFileAsset(fileId: String) =
        localImageLibraryAndModelCoordinator.deleteFileAsset(fileId)

    fun clearFileLibrary() =
        localImageLibraryAndModelCoordinator.clearFileLibrary()

    fun importLocalImageLora(uriString: String) =
        localImageLibraryAndModelCoordinator.importLocalImageLora(uriString)

    fun deleteLocalImageLora(id: String) =
        localImageLibraryAndModelCoordinator.deleteLocalImageLora(id)

    fun importLocalImageTextualInversion(uriString: String, trigger: String) =
        localImageLibraryAndModelCoordinator.importLocalImageTextualInversion(uriString, trigger)

    fun deleteLocalImageTextualInversion(id: String) =
        localImageLibraryAndModelCoordinator.deleteLocalImageTextualInversion(id)

    fun downloadOfficialViggleLora() =
        localImageLibraryAndModelCoordinator.downloadOfficialViggleLora()

    fun reportMissingLocalImageLoraSelection() {
        uiStateStore.update { it.copy(chatImageLoraSelections = null) }
        uiStateStore.update {
            it.copy(
                localImageLoraMessage = "所选 LoRA 已删除，请重新选择。",
                statusMessage = "所选 LoRA 已删除，请重新选择。"
            )
        }
    }


    fun reportMissingLocalImageTextualInversionSelection() = localImageLibraryAndModelCoordinator.reportMissingLocalImageTextualInversionSelection()
    fun importLocalImageUpscaler(uriString: String) = localImageLibraryAndModelCoordinator.importLocalImageUpscaler(uriString)
    fun selectLocalImageUpscaler(id: String) = localImageLibraryAndModelCoordinator.selectLocalImageUpscaler(id)
    fun deleteLocalImageUpscaler(id: String) = localImageLibraryAndModelCoordinator.deleteLocalImageUpscaler(id)

    fun generateImageAsset(
        prompt: String,
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ): Boolean = chatImageWorkflowCoordinator.generateImageAsset(prompt, inputDraft, options)

    fun retryImageGeneration(jobId: String) =
        chatImageWorkflowCoordinator.retryImageGeneration(jobId)

    /** The ViewModel side of [ImageAssetCreationPort]. */
    // R3/C3-S15：资产生成/放大两端口的具名适配器（owner 与 S13 对账 owner 均为真实对象注入）。
    private val imageAssetCreationPort: ImageAssetCreationPort
        get() = chatImageWorkflowCoordinator.imageAssetCreationPort
    internal val imageUpscaleOwner: ImageUpscaleOwner
        get() = chatImageWorkflowCoordinator.imageUpscaleOwner
    private val imagePromptTranslationPort: ImagePromptTranslationPort
        get() = chatImageWorkflowCoordinator.imagePromptTranslationPort

    fun recreateImageAsset(imageId: String) =
        chatImageWorkflowCoordinator.recreateImageAsset(imageId)

    fun selectLocalImageModel(modelId: String) =
        chatImageWorkflowCoordinator.selectLocalImageModel(modelId)

    suspend fun createUpscaledImageAsset(
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord =
        chatImageWorkflowCoordinator.createUpscaledImageAsset(spec, result, requestId)

    fun cancelImageGeneration() = chatImageWorkflowCoordinator.cancelImageGeneration()

    internal fun cancelImageGenerationForJob(expectedJobId: String?) =
        chatImageWorkflowCoordinator.cancelImageGenerationForJob(expectedJobId)

    private fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? =
        chatImageWorkflowCoordinator.publishLocalImagePreview(jobId, progress)

    fun deleteImageAsset(imageId: String) = deleteImageAssets(listOf(imageId))
    fun deleteImageAssets(imageIds: List<String>) = deleteImageAssetsOperation(imageAssetDeletionPort, imageIds)
    fun setImageAssetFavorite(imageId: String, favorite: Boolean) = setImageAssetFavoriteOperation(imageAssetDeletionPort, imageId, favorite)
    fun clearImageLibrary() = clearImageLibraryOperation(imageAssetDeletionPort)

    fun exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) =
        chatImageWorkflowCoordinator.exportImageLibraryBackup(uriString, favoritesOnly)

    fun importImageLibraryBackup(uriString: String) =
        chatImageWorkflowCoordinator.importImageLibraryBackup(uriString)

    fun cancelImageLibraryBackup() =
        chatImageWorkflowCoordinator.cancelImageLibraryBackup()

    fun importLocalImageModel(uri: Uri) =
        localImageLibraryAndModelCoordinator.importLocalImageModel(uri)

    /** Delegates to [rejectImageModelSwitchWhileGenerationIsActiveFor]. Signature kept verbatim. */
    private fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean =
        rejectImageModelSwitchWhileGenerationIsActiveFor(uiStateStore, container.runtimeAdmission)

    fun verifyLocalImageModel(modelId: String) =
        localImageLibraryAndModelCoordinator.verifyLocalImageModel(modelId)

    /** Must be called from an IO coroutine. */
    private fun settleLocalImageSelection(
        preferred: LocalImageModelRecord
    ): LocalImageSelection = localImageLibraryAndModelCoordinator.settleLocalImageSelection(preferred)

    fun deleteLocalImageModel(modelId: String) {
        launchModelOperation("模型操作未完成") {
            if (activeImageGenerationModelId == modelId || activeLocalApiImageModelId == modelId) {
                uiStateStore.update {
                    it.copy(statusMessage = "当前图片任务正在使用该模型，请停止或等待任务完成后再删除")
                }
                return@launchModelOperation
            }
            val removed = uiStateStore.current.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            val success = runCatching { localImageModelStore.deleteModel(modelId) }.getOrDefault(false)
            settleAndApplyLocalImageModelCatalogUpdate(
                store = uiStateStore,
                localImageModelStore = localImageModelStore,
                context = getApplication(),
                managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
                statusMessage = if (success) "已删除本地图像生成引擎：${removed?.displayName ?: "模型"}" else "未找到本地图像生成引擎"
            )
        }
    }

    fun selectImageGenerationModel(choiceId: String) =
        chatImageWorkflowCoordinator.selectImageGenerationModel(choiceId)

    fun onRepoInputChange(value: String) = uiStateStore.update { it.copy(repoInput = value) }

    fun onHubQueryChange(value: String) = uiStateStore.update { it.copy(hubQuery = value) }


    fun updateParams(params: GenerationParams) {
        val state = uiStateStore.current
        val previous = state.params
        val scopedParams = params.copy(systemPrompt = state.selectedAssistant()?.systemPrompt ?: previous.systemPrompt)
        val changedRuntimeFields = runtimeParameterChanges(previous, scopedParams)
        if (changedRuntimeFields.isNotEmpty()) {
            runtimeUserOverrideFields += changedRuntimeFields
            GenerationPreferences.persistRuntimeUserOverrideFields(getApplication(), runtimeUserOverrideFields)
        }
        GenerationPreferences.persistGenerationParams(getApplication(), scopedParams)
        // Keep the legacy global/assistant preference for migration, while also
        // recording this edit against the active model so switching models does
        // not make users repeat the same tuning work.
        persistModelGenerationParams(state, scopedParams)
        val updatedAssistants = updatedAssistantsWithParams(state.assistants, state.selectedAssistantId, scopedParams)
        val hasLoadedModel = uiStateStore.current.loadedModelId != null
        val reloadRequired = hasLoadedModel && profileDiffersFrom(scopedParams, engine.activeExecutionProfile())
        uiStateStore.update { state ->
            state.copy(
                params = scopedParams,
                assistants = updatedAssistants,
                reloadRequired = reloadRequired,
                statusMessage = if (reloadRequired) {
                    "运行参数已修改，正在建立可回滚的待应用配置；当前模型继续使用原配置。"
                } else if (changedRuntimeFields.isNotEmpty() && state.loadedModelId == null) {
                    "运行参数已保存；下次加载模型时将按自定义值生效。"
                } else {
                    state.statusMessage
                }
            )
        }
        assistantProfileCoordinator.persistAssistantsAsync()
        if (reloadRequired && changedRuntimeFields.isNotEmpty()) {
            stageDirectParameterProfile(scopedParams)
        } else if (!reloadRequired && changedRuntimeFields.isNotEmpty()) {
            directParameterStageGeneration.incrementAndGet()
            directParameterStageJob?.cancel()
            if (pendingProfileTransactionId != null) discardPendingAgentProfile()
        }
    }

    private fun stageDirectParameterProfile(params: GenerationParams) =
        assistantProfileCoordinator.stageDirectParameterProfile(params)

    fun saveAssistantProfile(
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


    fun upsertAssistantMemory(assistantId: String, id: String?, scope: String, content: String) =
        assistantProfileCoordinator.upsertAssistantMemory(assistantId, id, scope, content)

    fun deleteAssistantMemory(assistantId: String, id: String) =
        assistantProfileCoordinator.deleteAssistantMemory(assistantId, id)


    fun saveWebSearchConfig(
        enabled: Boolean, provider: String, endpoint: String, apiKey: String, maxResults: Int,
        fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) = webSearchWorkflowCoordinator.saveWebSearchConfig(enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders)

    fun testWebSearchConfig(query: String) = webSearchWorkflowCoordinator.testWebSearchConfig(query)

    fun testWebSearchConfig(
        query: String, enabled: Boolean, provider: String, endpoint: String, apiKey: String,
        maxResults: Int, fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) = webSearchWorkflowCoordinator.testWebSearchConfig(query, enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders)

    fun preflightWebSearchConfig(
        enabled: Boolean, provider: String, endpoint: String, apiKey: String, maxResults: Int,
        fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) = webSearchWorkflowCoordinator.preflightWebSearchConfig(enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders)

    fun testWebSearchTurn(
        query: String, enabled: Boolean, provider: String, endpoint: String, apiKey: String, maxResults: Int,
        fetchPageContent: Boolean, triggerMode: String, researchMode: String, backupProviders: List<WebSearchBackupProviderConfig> = emptyList(),
        allowPublicCheckSourceForProtocolTest: Boolean = false
    ) = webSearchWorkflowCoordinator.testWebSearchTurn(query, enabled, provider, endpoint, apiKey, maxResults, fetchPageContent, triggerMode, researchMode, backupProviders, allowPublicCheckSourceForProtocolTest)

    fun toggleWebSearchForNextTurn() = webSearchWorkflowCoordinator.toggleWebSearchForNextTurn()

    fun cycleWebSearchResearchModeForNextTurn() = webSearchWorkflowCoordinator.cycleWebSearchResearchModeForNextTurn()

    fun selectWebSearchResearchModeForNextTurn(mode: String) = webSearchWorkflowCoordinator.selectWebSearchResearchModeForNextTurn(mode)

    fun clearWebSearchDiagnostics() = webSearchWorkflowCoordinator.clearWebSearchDiagnostics()

    fun selectAssistant(assistantId: String) {
        val state = uiStateStore.current
        if (rejectWhileConversationMutationInProgress()) return
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val previousSession = state.chatSessions.latestSessionForAssistant(assistantId)
        if (previousSession != null) {
            if (previousSession.id != state.activeChatSessionId || state.selectedAssistantId != assistant.id) {
                selectChatSession(previousSession.id)
            }
            if (uiStateStore.current.activeChatSessionId == previousSession.id &&
                previousSession.modelMode.toChatBackendOrNull() == ChatBackend.LOCAL
            ) {
                previousSession.modelId?.let { modelId ->
                    if (uiStateStore.current.loadedModelId != modelId) {
                        uiStateStore.current.models.firstOrNull { it.id == modelId }?.let(::loadModel)
                    }
                }
            }
            return
        }
        if (!newChatForAssistant(assistant, bindDefaultModel = true)) return
        finishSelectingNewAssistant(assistant)
    }

    fun createChatForAssistant(assistantId: String) {
        val assistant = uiStateStore.current.assistants.firstOrNull { it.id == assistantId } ?: return
        if (!newChatForAssistant(assistant, bindDefaultModel = true)) return
        finishSelectingNewAssistant(assistant)
    }

    private fun finishSelectingNewAssistant(assistant: AssistantRecord) =
        assistantProfileCoordinator.finishSelectingNewAssistant(assistant)

    fun importChatBackground(scope: ChatAppearanceScope, uriString: String) =
        chatAppearanceCoordinator.importChatBackground(scope, uriString)
    fun setChatAppearance(scope: ChatAppearanceScope, appearance: ChatAppearance?) =
        chatAppearanceCoordinator.setChatAppearance(scope, appearance)

    fun deleteAssistant(assistantId: String) = assistantProfileCoordinator.deleteAssistant(assistantId)
    fun importAssistantCard(rawJson: String) = assistantProfileCoordinator.importAssistantCard(rawJson)
    internal fun restorePendingContentImportPreview() = contentImportPreviewCoordinator.restorePendingContentImportPreview()
    fun removeLocalImageModelRecord(modelId: String) = localImageLibraryAndModelCoordinator.removeLocalImageModelRecord(modelId)
    internal suspend fun stageContentImportPreview(preview: ContentImportPreview) = contentImportPreviewCoordinator.stageContentImportPreview(preview)
    fun cancelContentImportPreview(id: String) = contentImportPreviewCoordinator.cancelContentImportPreview(id)
    fun confirmContentImportPreview(id: String) = contentImportPreviewCoordinator.confirmContentImportPreview(id) { documentImportCoordinator.confirmKnowledgeDocumentImport(it) }

    fun importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) =
        assistantProfileCoordinator.importAssistantCardFile(uriString, importOwner)
    fun importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) =
        assistantProfileCoordinator.importWorldBook(rawJson, scope)

    fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?) {
        val frozen = selections?.toList()
        val valid = frozen?.let { runCatching { resolveCurrentLocalImageLorasFor(it, uiStateStore.current.localImageLoras) }.isSuccess } == true
        uiStateStore.update { it.copy(chatImageLoraSelections = frozen.takeIf { valid }) }
    }

    fun importWorldBookFile(
        uriString: String,
        scope: WorldBookScope = WorldBookScope.ASSISTANT,
        importOwner: ContentImportOwner? = null
    ) = assistantProfileCoordinator.importWorldBookFile(uriString, scope, importOwner)

    fun deleteWorldBook(worldBookId: String) =
        assistantProfileCoordinator.deleteWorldBook(worldBookId)

    fun createKnowledgeBase(name: String, description: String = "") =
        assistantProfileCoordinator.createKnowledgeBase(name, description)

    fun importKnowledgeDocument(
        knowledgeBaseId: String,
        uriString: String,
        importOwner: ContentImportOwner? = null
    ) = documentImportCoordinator.importKnowledgeDocument(knowledgeBaseId, uriString, importOwner)

    fun importKnowledgeDocument(uriString: String) =
        documentImportCoordinator.importKnowledgeDocument(uriString)

    fun setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) =
        setKnowledgeBaseSelectedFor(uiStateStore, { uiStateStore.current }, ::persistKnowledgeBaseBindings, knowledgeBaseId, selected)

    fun deleteKnowledgeBase(knowledgeBaseId: String) =
        assistantProfileCoordinator.deleteKnowledgeBase(knowledgeBaseId)

    fun updateReasoningMode(mode: ReasoningMode) =
        updateReasoningModeFor(
            uiStateStore.current,
            getApplication(),
            modelGenerationProfileStore,
            uiStateStore,
            ::showStatus,
            mode
        )

    fun showCloudReasoningModeLocked() =
        showStatus(CLOUD_REASONING_LOCKED_MESSAGE)

    fun updateCloudApiEnabled(enabled: Boolean) = chatModelCoordinator.updateCloudApiEnabled(enabled)
    fun updateCloudApiFormat(value: String) = chatModelCoordinator.updateCloudApiFormat(value)
    fun updateCloudImageApiFormat(value: String) = chatModelCoordinator.updateCloudImageApiFormat(value)
    fun applyCloudProviderPreset(value: String) = chatModelCoordinator.applyCloudProviderPreset(value)
    fun beginAddCloudModel(kind: String) = chatModelCoordinator.beginAddCloudModel(kind)
    fun editCloudModel(modelId: String) = chatModelCoordinator.editCloudModel(modelId)
    fun updateCloudBaseUrl(value: String) = chatModelCoordinator.updateCloudBaseUrl(value)
    fun updateCloudApiKey(value: String) = chatModelCoordinator.updateCloudApiKey(value)
    fun updateCloudChatModel(value: String) = chatModelCoordinator.updateCloudChatModel(value)
    fun updateCloudSupportsVision(value: Boolean) = chatModelCoordinator.updateCloudSupportsVision(value)
    fun updateModelBackendPreference(modelId: String, backendId: String) = chatModelCoordinator.updateModelBackendPreference(modelId, backendId)
    fun generationParamsForChatModel(modelId: String): GenerationParams = chatModelCoordinator.generationParamsForChatModel(modelId)
    fun updateCloudSupportsTools(value: Boolean) = chatModelCoordinator.updateCloudSupportsTools(value)
    fun updateCloudImageModel(value: String) = chatModelCoordinator.updateCloudImageModel(value)
    fun updateCloudResponsesReasoning(value: Boolean) = chatModelCoordinator.updateCloudResponsesReasoning(value)
    fun updateCloudImageSize(value: String) = chatModelCoordinator.updateCloudImageSize(value)
    fun updateCloudImageEndpointPath(value: String) = chatModelCoordinator.updateCloudImageEndpointPath(value)
    fun updateCloudDisplayName(value: String) = chatModelCoordinator.updateCloudDisplayName(value)
    fun saveCloudApiConfig() = chatModelCoordinator.saveCloudApiConfig()
    fun saveCloudChatModel() = chatModelCoordinator.saveCloudChatModel()
    fun saveCloudImageModel() = chatModelCoordinator.saveCloudImageModel()
    fun testCloudApiConfig() = chatModelCoordinator.testCloudApiConfig()
    fun selectCloudImageModel(modelId: String) = chatModelCoordinator.selectCloudImageModel(modelId)


    fun updateAgentPreference(preference: UserPreference) =
        agentTuningCoordinator.updateAgentPreference(preference)


    fun refreshLocalModels() =
        modelImportAndDownloadCoordinator.refreshLocalModels()

    private suspend fun reconcileModelIdentities() =
        mainPostStartupCoordinator.reconcileModelIdentities()


    fun importModel(uri: Uri) = importModel(listOf(uri))
    fun importModel(uris: List<Uri>, textOnly: Boolean = false, persistentAccess: Boolean = false) =
        modelImportAndDownloadCoordinator.importModel(uris, textOnly, persistentAccess)
    fun importModelDirectory(uri: Uri, textOnly: Boolean = false, persistentAccess: Boolean = false) =
        modelImportAndDownloadCoordinator.importModelDirectory(uri, textOnly, persistentAccess)
    fun pauseManagedImport(requestId: String) = modelImportAndDownloadCoordinator.pauseManagedImport(requestId)
    fun resumeManagedImport(requestId: String) = modelImportAndDownloadCoordinator.resumeManagedImport(requestId)

    fun fetchRemoteFiles() = modelScopeHubCoordinator.fetchRemoteFiles()
    fun fetchRecommendedFiles(model: ModelScopeRecommendedModel) = modelScopeHubCoordinator.fetchRecommendedFiles(model)
    fun downloadRecommended(model: ModelScopeRecommendedModel) = downloadRecommended(model, useAfterDownload = false)
    fun downloadRecommended(model: ModelScopeRecommendedModel, useAfterDownload: Boolean) = modelScopeHubCoordinator.downloadRecommended(model, useAfterDownload)
    fun attachVisionProjector(modelId: String, uri: Uri) =
        modelImportAndDownloadCoordinator.attachVisionProjector(modelId, uri, uiStateStore.current.loadedModelId, uiStateStore.current.selectedChatBackend)

    fun searchHubModels(reset: Boolean = true) = modelScopeHubCoordinator.searchHubModels(reset)
    fun fetchHubModelFiles(model: ModelScopeHubModel) = modelScopeHubCoordinator.fetchHubModelFiles(model)
    fun openModelScopePage(url: String) = modelScopeHubCoordinator.openModelScopePage(url)
    fun download(remote: RemoteModelFile) = modelImportAndDownloadCoordinator.download(remote, uiStateStore.current.loadedModelId)
    fun pauseManagedDownloads() = modelImportAndDownloadCoordinator.pauseManagedDownloads()
    fun resumeManagedDownloads() = modelImportAndDownloadCoordinator.resumeManagedDownloads()


    internal fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =
        modelImportAndDownloadCoordinator.enqueueManagedDownload(request, useAfterDownload)

    internal fun launchModelOperation(
        message: String,
        operation: suspend kotlinx.coroutines.CoroutineScope.() -> Unit
    ): Job = chatModelCoordinator.launchModelOperation(message, operation)

    fun selectChatModel(choiceId: String) = chatModelCoordinator.selectChatModel(choiceId)
    internal fun selectCloudChatModel(modelId: String?) = chatModelCoordinator.selectCloudChatModel(modelId)
    fun loadModel(requestedModel: ModelManifest) = chatModelCoordinator.loadModel(requestedModel)
    fun verifyModel(model: ModelManifest) = chatModelCoordinator.verifyModel(model)
    fun unloadModel(model: ModelManifest) = chatModelCoordinator.unloadModel(model)
    fun deleteModel(model: ModelManifest) = chatModelCoordinator.deleteModel(model)

    fun sendMessage() { chatWorkflowCoordinator.sendMessage(this) }

    internal fun sendPreparedMessage(
        state: MainUiState,
        preparedInput: PreparedChatInput
    ) {
        chatWorkflowCoordinator.sendPreparedMessage(this, state, preparedInput)
    }

    private fun invalidatePendingChatImagePreparation() =
        offlinePromptTranslationAndChatImageCoordinator.invalidatePendingChatImagePreparation()

    fun resolveChatImageActionDraft(id: String, primary: Boolean, editedText: String) =
        offlinePromptTranslationAndChatImageCoordinator.resolveChatImageActionDraft(id, primary, editedText)

    fun importOfflinePromptTranslationBundle(uri: Uri) =
        offlinePromptTranslationAndChatImageCoordinator.importOfflinePromptTranslationBundle(uri)

    fun cancelOfflinePromptTranslationInstall() =
        offlinePromptTranslationAndChatImageCoordinator.cancelOfflinePromptTranslationInstall()

    suspend fun translateImagePromptPreview(prompt: String): String =
        offlinePromptTranslationAndChatImageCoordinator.translateImagePromptPreview(prompt)

    suspend fun translateImageNegativePromptPreview(prompt: String): String =
        offlinePromptTranslationAndChatImageCoordinator.translateImageNegativePromptPreview(prompt)

    fun downloadOfflinePromptTranslationBundle() =
        offlinePromptTranslationAndChatImageCoordinator.downloadOfflinePromptTranslationBundle()

    fun cancelOfflinePromptTranslationDownload() =
        offlinePromptTranslationAndChatImageCoordinator.cancelOfflinePromptTranslationDownload()

    fun cancelChatImageTranslationDraft(id: String) =
        offlinePromptTranslationAndChatImageCoordinator.cancelChatImageTranslationDraft(id)

    /** Sends the composer contents straight to the selected image backend. */
    fun sendChatImagePrompt() =
        offlinePromptTranslationAndChatImageCoordinator.sendChatImagePrompt()

    /** Delegates to [runChatImagePromptBridge]. Signature kept verbatim. */
    internal fun sendChatImageRequestWithPromptBridge(
        visibleUserText: String,
        prompt: String,
        requestedOutputCount: Int? = null,
        allowCloudTranslationFallback: Boolean = false
    ) = runChatImagePromptBridge(
        port = chatImagePromptBridgePort,
        visibleUserText = visibleUserText,
        prompt = prompt,
        requestedOutputCount = requestedOutputCount,
        allowCloudTranslationFallback = allowCloudTranslationFallback
    )

    internal fun hostSendChatImageRequestWithPromptBridge(
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

    /**
     * The chat bridge may emit a quality negative branch even when the selected image topology
     * cannot condition on one. Keep that branch out of CFG-disabled/unsupported workers; an
     * explicit non-empty options.negativePrompt would otherwise be rejected as a user override.
     */
    /** Delegates to [chatImageNegativePromptSupportedFor]. Signature kept verbatim. */
    internal fun chatImageNegativePromptSupported(state: MainUiState): Boolean =
        chatImageNegativePromptSupportedFor(state)

    internal fun chatImageToolPermissionUiState(): ChatImageToolPermissionUiState =
        offlinePromptTranslationAndChatImageCoordinator.chatImageToolPermissionUiState()

    fun setAssistantImageToolAutoApproval(enabled: Boolean) =
        offlinePromptTranslationAndChatImageCoordinator.setAssistantImageToolAutoApproval(enabled)

    private fun continueAssistantImageToolTurn(requestId: String, outputJson: String) =
        assistantImageContinuationCoordinator.continueAssistantImageToolTurn(requestId, outputJson)

    fun retryChatImageGeneration(requestId: String) =
        assistantImageContinuationCoordinator.retryChatImageGeneration(requestId)

    fun approveChatImageRequest(requestId: String) =
        assistantImageContinuationCoordinator.approveChatImageRequest(requestId)

    fun rejectChatImageRequest(requestId: String) =
        assistantImageContinuationCoordinator.rejectChatImageRequest(requestId)


    /** Resumes a terminal assistant tool turn if the process stopped before the follow-up reply. */
    fun continueAssistantImageTurn(requestId: String) =
        assistantImageContinuationCoordinator.continueAssistantImageTurn(requestId)

    /** Delegates to [cancelChatImageGenerationFor]. Signature kept verbatim. */
    fun cancelChatImageGeneration(requestId: String) =
        assistantImageContinuationCoordinator.cancelChatImageGeneration(requestId)

    fun regenerateLastResponse() {
        val state = uiStateStore.current
        if (rejectWhileConversationMutationInProgress()) return
        if (!state.canWriteToActiveAssistantSession()) return
        if (state.isGenerating) return
        val lastAssistant = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (lastAssistant < 0) return
        val priorUser = state.messages.take(lastAssistant).indexOfLast { it.role == Role.USER }
        if (priorUser < 0) return
        val rollback = state.conversationMutationRollbackState()
        discardPendingAssistantOutput()
        val kept = state.messages.take(lastAssistant) + ChatMessage(Role.ASSISTANT, "")
        val generationReservation = reserveUiGenerationStart() ?: return
        // Reserve background protection while the user is still in the UI,
        // before Room persistence can delay the actual generation hand-off.
        val preparationLease = McaGenerationForegroundService.acquire(
            getApplication<Application>(),
            McaGenerationForegroundService.KIND_CHAT
        )
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        uiStateStore.update {
            val sessionId = it.activeChatSessionId ?: UUID.randomUUID().toString()
            sessionsToPersist = it.chatSessions.upsertSessionFor(
                state = it,
                sessionId = sessionId,
                messages = kept,
                assistantId = it.selectedAssistantId,
                modelMode = it.selectedChatBackend.bindingValue(),
                modelId = it.currentChatModelId()
            )
            it.copy(
                messages = kept,
                activeChatSessionId = sessionId,
                chatSessions = sessionsToPersist,
                isGenerating = true,
                generationPhase = null,
                generationTokenProgress = null,
                generationStats = null,
                promptContextUsage = null,
                statusMessage = "正在重新生成上一条回答..."
            )
        }
        val persistenceJob = persistConversationMutation(
            sessions = sessionsToPersist,
            rollback = rollback,
            onCommitted = { startGeneration(kept.dropLast(1), generationReservation) },
            onCommitFailed = { chatRuns.cancelPending(generationReservation) }
        )
        persistenceJob.invokeOnCompletion {
            chatRuns.cancelPending(generationReservation)
            McaGenerationForegroundService.release(getApplication<Application>(), preparationLease)
        }
    }

    fun deleteMessageAt(index: Int) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteMessageAtFor(
            store = uiStateStore, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
            showStatus = ::showStatus, isChatImageRequestActive = ::isChatImageRequestActive,
            pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
            persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) },
            index = index
        )
    }

    val currentSessionId: String? get() = uiStateStore.current.activeChatSessionId

    fun deleteLastConversationTurn() = chatWorkflowCoordinator.deleteLastConversationTurn(currentSessionId) // preserveReusableNativePrefix = true



    internal fun reserveUiGenerationStart(): UiGenerationReservation? =
        chatRuns.reserveStartAndCancelSuperseded()

    internal fun cancelGenerationJob(): UiGenerationCancellation =
        chatRuns.cancelCurrentAndCancelOwner()

    internal fun startGenerationFromCoordinator(
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

    internal fun startGeneration(
        requestMessages: List<ChatMessage>,
        reservation: UiGenerationReservation? = null,
        toolExchanges: List<ChatToolExchange> = emptyList(),
        paramsOverride: GenerationParams? = null,
        offerAssistantImageTool: Boolean = true,
        chatModelIdOverride: String? = null,
        continuationRequestId: String? = null,
        preflightCompression: com.muyuchat.core.engine.ContextCompressionResult? = null
    ): Boolean {
        val admittedReservation = reservation ?: reserveUiGenerationStart() ?: return false
        val generationRunId = admittedReservation.runId
        val replyMetrics = ReplyGenerationMetrics(generationRunId)
        val ownedGenerationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            chatExecutionPort.runChatGeneration(
                requestMessages = requestMessages,
                reservation = admittedReservation,
                replyMetrics = replyMetrics,
                toolExchanges = toolExchanges,
                paramsOverride = paramsOverride,
                offerAssistantImageTool = offerAssistantImageTool,
                chatModelIdOverride = chatModelIdOverride,
                continuationRequestId = continuationRequestId,
                preflightCompression = preflightCompression
            )
        }
        if (!chatRuns.activate(admittedReservation, ownedGenerationJob)) {
            ownedGenerationJob.cancel()
            chatRuns.cancelPending(admittedReservation)
            return false
        }
        // Keep the main process eligible to finish cloud/UI chat work after
        // the activity leaves the foreground. Local chat has an additional
        // isolated worker service; this notification-only service covers the
        // caller-side stream and persistence callbacks as well.
        val foregroundLease = McaGenerationForegroundService.acquire(
            getApplication<Application>(),
            McaGenerationForegroundService.KIND_CHAT
        )
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

    fun stopGeneration() {
        stopActiveGeneration(chatStopGenerationPort)
    }

    fun newChat() {
        if (!rejectWhileConversationMutationInProgress()) newChatForAssistant(uiStateStore.current.selectedAssistant())
    }

    internal fun newChatForAssistant(
        assistant: AssistantRecord?,
        bindDefaultModel: Boolean = false
    ): Boolean {
        if (rejectWhileConversationMutationInProgress()) return false
        val created = chatSessionManagementCoordinator.newChatForAssistant(assistant, bindDefaultModel)
        if (created) {
            markLocalConversationContextInvalid()
        }
        return created
    }

    fun selectChatSession(sessionId: String) =
        chatSessionManagementCoordinator.selectChatSession(sessionId)

    fun branchMixedChatSession(sessionId: String, assistantId: String) =
        chatSessionManagementCoordinator.branchMixedChatSession(sessionId, assistantId)


    fun deleteChatSession(sessionId: String) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteChatSessionFor(
            store = uiStateStore, scope = viewModelScope, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
            showStatus = ::showStatus, hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            manualContextCompressionRequests = manualContextCompressionRequests, pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
            queueWorldBookCleanup = assistantProfileCoordinator::queueWorldBookCleanup,
            persistConversationMutation = { remaining, rollback -> persistConversationMutation(remaining, rollback) },
            chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
            cleanupUnusedChatBackgrounds = { chatAppearanceCoordinator.mutex.withLock { chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() } },
            sessionId = sessionId
        )
    }

    fun renameChatSession(sessionId: String, title: String) =
        chatSessionManagementCoordinator.renameChatSession(sessionId, title)

    fun toggleChatSessionPinned(sessionId: String) =
        chatSessionManagementCoordinator.toggleChatSessionPinned(sessionId)

    fun clearChatHistory() {
        if (rejectWhileConversationMutationInProgress()) return
        clearChatHistoryFor(
            store = uiStateStore, scope = viewModelScope, rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
            showStatus = ::showStatus, hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            manualContextCompressionRequests = manualContextCompressionRequests, queueWorldBookCleanup = assistantProfileCoordinator::queueWorldBookCleanup,
            pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
            persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) },
            chatImageToolAuthorizationStore = chatImageToolAuthorizationStore
        )
    }


    fun setPersistentPrefixCacheEnabled(enabled: Boolean) =
        cacheManagementCoordinator.setPersistentPrefixCacheEnabled(enabled)

    fun clearPersistentPrefixCache() =
        cacheManagementCoordinator.clearPersistentPrefixCache()

    internal fun schedulePersistentPrefixCacheClear(
        operation: Long,
        successMessage: String,
        failureMessage: String
    ) = cacheManagementCoordinator.schedulePersistentPrefixCacheClear(operation, successMessage, failureMessage)

    fun clearChat() {
        viewModelScope.launch {
            manualContextCompressionRequests.clear(uiStateStore.current.activeChatSessionId)
            cancelGenerationJob()
            engine.stopGeneration()
            discardPendingAssistantOutput()
            markLocalConversationContextInvalid()
            uiStateStore.update { it.afterClearChatGenerationStopped(engine.stats.value).copy(contextCompressionPending = false) }
            persistChatSessions()
        }
    }


    fun onAppBackgrounded() {
        // The process lifecycle callback only changes admission for *new* UI
        // requests.  An already accepted chat request must continue in the
        // isolated local worker or cloud transport while the activity is not
        // visible.  Its foreground service owns process priority and its
        // terminal/persistence callbacks retain the same generation epoch.
        chatRuns.background()
        uiStateStore.update { state ->
            val imageActive = state.imageJobs.any { job -> !job.status.terminal }
            if ((state.isGenerating || imageActive) &&
                state.statusMessage != BACKGROUND_TASK_STATUS
            ) {
                state.copy(statusMessage = BACKGROUND_TASK_STATUS)
            } else {
                state
            }
        }
        if (agentTuningCoordinator.adaptiveTuningJob?.isActive == true) {
            pauseAgentTuning()
        }
    }

    /**
     * Reconciles process-local services after Android has stopped and resumed the process UI.
     * Worker loss is detected here, while the existing request path owns any expensive model
     * reload so returning to the UI cannot create a surprise memory spike.
     */
    fun onAppForegrounded() = appLifecycleCoordinator.onAppForegrounded()

    fun refreshLogs() = diagnosticsOperations.refreshLogs()
    fun refreshDiagnostics() = diagnosticsOperations.refreshDiagnostics()


    fun exportDiagnosticReport(uri: Uri) =
        diagnosticsOperations.exportDiagnosticReport(uri, viewModelScope, ::fail)

    fun chatSessionExportFileName(sessionId: String): String =
        chatSessionManagementCoordinator.chatSessionExportFileName(sessionId)

    fun exportChatSession(sessionId: String, uri: Uri) =
        chatSessionManagementCoordinator.exportChatSession(sessionId, uri)


    fun scanAgent() = agentTuningCoordinator.scanAgent()
    fun applyAgentRecommendation() = agentTuningCoordinator.applyAgentRecommendation()
    fun rollbackAgentParams() = agentTuningCoordinator.rollbackAgentParams()
    fun loadAgentRecommendedModel() = agentTuningCoordinator.loadAgentRecommendedModel()
    fun runAgentBenchmark() = agentTuningCoordinator.runAgentBenchmark()
    fun runAgentStabilitySmoke() = agentTuningCoordinator.runAgentStabilitySmoke()
    fun startAgentTuning() = agentTuningCoordinator.startAgentTuning()
    fun startAgentTuning(mode: AgentTuningMode, autoApply: Boolean) =
        agentTuningCoordinator.startAgentTuning(mode, autoApply)
    fun pauseAgentTuning() = agentTuningCoordinator.pauseAgentTuning()
    fun resumeAgentTuning() = agentTuningCoordinator.resumeAgentTuning()
    fun cancelAgentTuning() = agentTuningCoordinator.cancelAgentTuning()
    fun queryAgentTuningJob() = agentTuningCoordinator.queryAgentTuningJob()
    fun applyPendingAgentProfile() = agentTuningCoordinator.applyPendingAgentProfile()
    fun discardPendingAgentProfile() = agentTuningCoordinator.discardPendingAgentProfile()
    fun rollbackAgentProfile() = agentTuningCoordinator.rollbackAgentProfile()
    fun runAgentQuickDebug() = agentTuningCoordinator.runAgentQuickDebug()
    fun runAgentStandardDebug() = agentTuningCoordinator.runAgentStandardDebug()
    fun runAgentDeepDebug() = agentTuningCoordinator.runAgentDeepDebug()
    fun runAgentPowerDebug() = agentTuningCoordinator.runAgentPowerDebug()
    fun showAgentDebugExplanation() = agentTuningCoordinator.showAgentDebugExplanation()

    fun toggleApi(enabled: Boolean) {
        val restEnabled = if (enabled) uiStateStore.current.restEnabled else false
        persistApiPreferences(apiEnabled = enabled, restEnabled = restEnabled)
        uiStateStore.update {
            it.copy(
                statusMessage = if (enabled) {
                    "正在启动本机 API..."
                } else {
                    "正在停止本机 API..."
                }
            )
        }
        requestLocalApiState(
            enabled = enabled,
            restEnabled = restEnabled,
            failurePrefix = "本机 API 启动失败"
        )
    }

    fun toggleRest(enabled: Boolean) {
        val apiEnabled = enabled || uiStateStore.current.apiEnabled
        persistApiPreferences(apiEnabled = apiEnabled, restEnabled = enabled)
        uiStateStore.update {
            it.copy(
                statusMessage = if (enabled) {
                    "正在开放局域网 REST..."
                } else {
                    "正在关闭局域网 REST..."
                }
            )
        }
        requestLocalApiState(
            enabled = apiEnabled,
            restEnabled = enabled,
            failurePrefix = "REST 启动失败"
        )
    }

    override fun onCleared() {
        container.runtimeAdmission.chatImagePromptBridgeJob?.cancel()
        LocalApiRuntime.releaseOwner(localApiRuntimeOwner)
        retireLocalApiListener(
            stopForegroundService = false,
            forceServerStop = false
        )
        localApiRuntimeOwner.release()
        localImageWorkerClient.close()
        container.isolatedLocalChatRunners.close()
        super.onCleared()
    }

    private fun retireLocalApiListener(
        stopForegroundService: Boolean,
        forceServerStop: Boolean
    ) = localApiServerCoordinator.retireLocalApiListener(stopForegroundService, forceServerStop)

    private fun requestLocalApiState(
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ) = localApiServerCoordinator.requestLocalApiState(enabled, restEnabled, failurePrefix)

    private suspend fun applyLocalApiState(
        operation: Long,
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ): LocalApiApplyResult? =
        localApiServerCoordinator.applyLocalApiState(operation, enabled, restEnabled, failurePrefix)

    private suspend fun awaitLocalApiForegroundServiceReady(): Boolean =
        localApiServerCoordinator.awaitLocalApiForegroundServiceReady()

    private suspend fun ensureLocalApiForegroundServiceReady(restEnabled: Boolean): Boolean =
        localApiServerCoordinator.ensureLocalApiForegroundServiceReady(restEnabled)

    private fun setLocalApiForegroundService(enabled: Boolean, restEnabled: Boolean) =
        localApiServerCoordinator.setLocalApiForegroundService(enabled, restEnabled)

    // Contract: LocalApiLifecycleOwnershipContractTest requires exact lock & sequence:
    // private val localApiProcessLifecycleLock = Any()
    private fun claimLocalApiProcessOwnership() {
        // contract: claimLocalApiProcessOwnership()
        // contract: LocalApiRuntime.claimOwner(localApiRuntimeOwner)
        // contract: localApiProcessOwnerToken !== localApiRuntimeOwner
        // contract: LocalApiForegroundService.ensureListener(
        // contract: LocalApiForegroundService.stopListener()
        localApiServerCoordinator.claimLocalApiProcessOwnership()
    }

    private fun releaseLocalApiProcessOwnership() =
        localApiServerCoordinator.releaseLocalApiProcessOwnership()

    private fun startApiServer(bindHost: String) =
        localApiServerCoordinator.startApiServer(bindHost)

    private fun stopApiServer() =
        localApiServerCoordinator.stopApiServer()

    private val ports = MainViewModelPortFactory.createPorts(
        container = container,
        uiStateStore = uiStateStore,
        scope = viewModelScope,
        engine = engine,
        imageGenerationOwner = imageGenerationOwner,
        chatImageCommitJournalProvider = { chatImageCommitJournal },
        localApiPayloadPortProvider = { localApiControlPlaneCoordinator.localApiPayloadPort },
        benchmarkRunner = benchmarkRunner,
        buildRecommendationAction = ::buildRecommendation,
        generateLocalApiImageAction = ::generateLocalApiImage,
        cancelActiveLocalApiImageGenerationAction = ::cancelActiveLocalApiImageGeneration,
        chatContextComposer = chatContextComposer,
        showStatus = ::showStatus,
        chatImageOptionsForCurrentSelection = ::chatImageOptionsForCurrentSelection,
        offlinePromptTranslationServiceProvider = { offlinePromptTranslationService },
        verifiedOfflineTranslationBundleRef = { offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle() },
        chatImageNegativePromptSupported = ::chatImageNegativePromptSupported,
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        stopGenerationInProgress = stopGenerationInProgress,
        currentReplyMetricsRef = { replyGenerationMetrics },
        cancelGenerationJobRef = { cancelGenerationJob() },
        flushPendingAssistantOutputRef = { flushPendingAssistantOutput() },
        persistConversationMutationRef = { sessions, rollback -> persistConversationMutation(sessions, rollback) },
    )

    private val chatSessionPersistencePort: ChatSessionPersistencePort = ports.chatSessionPersistencePort
    internal val chatImageRequestPort: ChatImageRequestPort = ports.chatImageRequestPort
    private val sendChatImageRequestPort: SendChatImageRequestPort = ports.sendChatImageRequestPort
    private val chatImageRetryPort: ChatImageRetryPort = ports.chatImageRetryPort
    internal val imageLibraryCommitPort: ImageLibraryCommitPort = ports.imageLibraryCommitPort
    private val imageAssetDeletionPort: ImageAssetDeletionPort
        get() = chatImageWorkflowCoordinator.imageAssetDeletionPort

    private val chatImageWorkflowCoordinator: ChatImageWorkflowCoordinator =
        (injectedChatImageWorkflowCoordinator as? ChatImageWorkflowCoordinator)
            ?: container.createChatImageWorkflowCoordinator(
                scope = viewModelScope,
                store = uiStateStore,
                imageAssetWriteMutex = imageAssetWriteMutex,
                imageLibraryMutationMutex = imageLibraryMutationMutex,
                imageLibraryBackup = imageLibraryBackup,
                chatSessionStore = chatSessionStore,
                imageGenerationOwner = imageGenerationOwner,
                imageLibraryStartupReconciliation = imageLibraryStartupReconciliation,
                chatImageRetryPort = chatImageRetryPort,
                showStatus = ::showStatus,
                tryAcquireObservedImageGenerationLease = ::tryAcquireObservedImageGenerationLease,
                releaseObservedImageGenerationLease = ::releaseObservedImageGenerationLease,
                offlinePromptTranslationService = { offlinePromptTranslationService },
                runtimeProfileStore = runtimeProfileStore,
                engine = engine,
                selectCloudImageModel = ::selectCloudImageModel,
                registerLocalApiImageGenerationOwnership = ::registerLocalApiImageGenerationOwnership,
                unregisterLocalApiImageGenerationOwnership = ::unregisterLocalApiImageGenerationOwnership,
                cancelOwnedLocalApiImageWorker = ::cancelOwnedLocalApiImageWorker
            )

    private val chatWorkflowCoordinator: ChatWorkflowCoordinator =
        (injectedChatWorkflowCoordinator as? ChatWorkflowCoordinator)
            ?: container.createChatWorkflowCoordinator(
                store = uiStateStore,
                rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
                showStatus = ::showStatus,
                isChatImageRequestActive = ::isChatImageRequestActive,
                pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
                persistConversationMutation = { sessions, rollback, preserveNativePrefix ->
                    persistConversationMutation(
                        sessions = sessions,
                        rollback = rollback,
                        preserveReusableNativePrefix = preserveNativePrefix
                    )
                }
            )

    private val localApiAuxProviderPort: LocalApiAuxProviderPort = ports.localApiAuxProviderPort
    private val chatLocalApiProviderPort: ChatLocalApiProviderPort = ports.chatLocalApiProviderPort
    private val chatExecutionPort: ChatGenerationExecutionPort get() = coordinatorAssembly.chatExecutionPort
    private val chatImagePromptBridgePort: ChatImagePromptBridgePort = ports.chatImagePromptBridgePort
    private val chatStopGenerationPort: ChatStopGenerationPort = ports.chatStopGenerationPort


    private fun refreshPersistentPrefixCacheSummary() =
        mainPostStartupCoordinator.refreshPersistentPrefixCacheSummary()


    internal fun markLocalConversationContextInvalid() =
        markLocalConversationContextInvalidInto(container.runtimeAdmission, uiStateStore, viewModelScope, engine)
    private fun rejectWhileConversationMutationInProgress(): Boolean =
        rejectWhileConversationMutationInProgressInto(uiStateStore, container.runtimeAdmission)

    internal suspend fun ensureLocalConversationContextInvalidated(): Boolean {
        // A delete/edit first commits Room, then invalidates native KV. Joining
        // this job preserves that ordering when the user immediately sends the
        // next message after a destructive conversation mutation.
        conversationMutationBarrier?.join()
        localConversationContextInvalidationJob?.join()
        if (!localConversationContextNeedsInvalidation) return true
        val sequence = localConversationContextInvalidationSequence.get()
        return runCatching { engine.invalidateConversationContext() }
            .onSuccess {
                if (localConversationContextInvalidationSequence.get() == sequence) {
                    localConversationContextNeedsInvalidation = false
                }
            }
            .onFailure { error ->
                if (localConversationContextInvalidationSequence.get() == sequence) {
                    uiStateStore.update {
                        it.copy(
                            isGenerating = false,
                            generationPhase = null,
                            generationTokenProgress = null,
                    generationPersistProgress = null,
                            statusMessage = "无法重置本地上下文，已取消本轮生成：${error.message ?: "native runtime error"}"
                        )
                    }
                }
            }
            .isSuccess
    }

    /** Delegates to [runChatSessionPersistence]. Signature kept verbatim. */
    internal fun persistChatSessions(
        sessions: List<ChatSessionRecord>? = null,
        knowledgeBinding: Pair<String, Set<String>>? = null,
        generationRunId: Long? = null
    ): Job? = runChatSessionPersistence(chatSessionPersistencePort, sessions, knowledgeBinding, generationRunId)

    internal fun hostPersistChatSessions(
        sessions: List<ChatSessionRecord>? = null,
        knowledgeBinding: Pair<String, Set<String>>? = null,
        generationRunId: Long? = null
    ): Job? = persistChatSessions(sessions, knowledgeBinding, generationRunId)
    /** Delegates to [runPersistConversationMutation]. Signature kept verbatim. */
    private fun persistConversationMutation(
        sessions: List<ChatSessionRecord>,
        rollback: ConversationMutationRollbackState,
        preserveReusableNativePrefix: Boolean = false,
        onCommitted: (() -> Unit)? = null,
        onCommitFailed: (() -> Unit)? = null
    ): Job = runPersistConversationMutation(
        chatSessionPersistencePort, sessions, rollback, preserveReusableNativePrefix, onCommitted, onCommitFailed
    )

    private fun persistKnowledgeBaseBindings(sessionId: String, knowledgeBaseIds: Set<String>) {
        persistChatSessions(
            sessions = uiStateStore.current.chatSessions,
            knowledgeBinding = sessionId to knowledgeBaseIds
        )
    }

    internal fun persistFiles(files: List<FileAssetRecord> = uiStateStore.current.files) =
        chatInputAndAttachmentCoordinator.persistFiles(files)

    internal fun busy(message: String) {
        uiStateStore.update { it.copy(busy = true, statusMessage = message) }
    }

    internal fun failDownload(message: String) {
        uiStateStore.update { it.copy(busy = false, downloadStatus = DownloadStatus.FAILED,
            downloadFailureSource = com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.DOWNLOAD,
            downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null, statusMessage = message) }
    }

    internal fun fail(message: String) {
        uiStateStore.update { it.copy(busy = false, statusMessage = message) }
    }

    /** Delegates to [chatImageOptionsForCurrentSelectionFor]. Signature kept verbatim. */
    internal fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
        chatImageOptionsForCurrentSelectionFor(state)

    private suspend fun generateLocalApiImage(requestId: String, body: String): String =
        chatImageWorkflowCoordinator.generateLocalApiImage(requestId, body)


    /**
     * Thin forwarders to [GenerationPreferences] and the two pure helpers beside it. The store is a
     * stateless object, so nothing here holds state or a scope: each call passes what the operation
     * needs.
     */

    private fun loadApiPreferences(application: Application): LocalApiPreferences =
        GenerationPreferences.loadApiPreferences(application)

    private fun persistApiPreferences(apiEnabled: Boolean, restEnabled: Boolean) =
        GenerationPreferences.persistApiPreferences(context = getApplication(), apiEnabled = apiEnabled, restEnabled = restEnabled)

    internal fun loadModelGenerationParams(backend: ChatBackend, modelId: String?, defaults: GenerationParams): GenerationParams =
        loadModelGenerationParamsFor(modelGenerationProfileStore, backend, modelId, defaults)

    internal fun persistModelGenerationParams(state: MainUiState, params: GenerationParams) =
        persistModelGenerationParams(backend = state.selectedChatBackend, modelId = state.currentChatModelId(), params = params)

    internal fun persistModelGenerationParams(backend: ChatBackend, modelId: String?, params: GenerationParams) =
        GenerationPreferences.persistModelGenerationParams(modelGenerationProfileStore, backend, modelId, params)

    private fun loadGenerationParams(application: Application): GenerationParams =
        GenerationPreferences.loadGenerationParams(application)

    private fun loadPersistentPrefixCacheEnabled(application: Application): Boolean =
        GenerationPreferences.loadPersistentPrefixCacheEnabled(application)
    private fun persistPersistentPrefixCacheEnabled(application: Application, enabled: Boolean) =
        GenerationPreferences.persistPersistentPrefixCacheEnabled(application, enabled)



    private fun apiUrl(host: String): String =
        localApiRuntimeOwner.apiUrl(host)

    internal fun prepareChatInput(state: MainUiState): PreparedChatInput =
        chatInputAndAttachmentCoordinator.prepareChatInput(state)

    private fun mimeTypeForUri(uriString: String): String =
        chatInputAndAttachmentCoordinator.mimeTypeForUri(uriString)








    private fun currentOpenApiAddress(): String =
        localApiRuntimeOwner.currentOpenApiAddress(detectLanIpAddress())



    fun currentDeviceProfile(): DeviceProfile = agentTuningCoordinator.currentDeviceProfile()

    fun buildRecommendation(
        preference: UserPreference,
        device: DeviceProfile = currentDeviceProfile()
    ): AgentRecommendation = agentTuningCoordinator.buildRecommendation(preference, device)
}
