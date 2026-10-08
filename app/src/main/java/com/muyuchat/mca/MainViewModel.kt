package com.muyuchat.mca

import android.app.Application
import com.muyuchat.mca.composition.MainCoordinatorAssembly
import com.muyuchat.mca.composition.MainCoordinatorHost
import com.muyuchat.mca.composition.McaAppContainer
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
    private val skipInitialModelDiscovery: Boolean = false,
    injectedEngine: McaInferenceService? = null,
    injectedLocalApiRuntimeOwner: Any? = null,
    injectedChatAppearanceCoordinator: Any? = null,
    injectedMainPostStartupCoordinator: Any? = null,
    injectedChatSessionManagementCoordinator: Any? = null,
    injectedChatInputAndAttachmentCoordinator: Any? = null,
    injectedChatImageWorkflowCoordinator: Any? = null,
    injectedChatWorkflowCoordinator: Any? = null
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

    private val modelIdentityReferenceMigration = ModelIdentityReferenceMigration(application)
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
    private val installationScopeId = runBlocking(Dispatchers.IO) {
        runtimeProfileStore.installationScopeId()
    }
    private val modelScopeClient = ModelScopeClient()
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
    private val localApiRuntimeOwner: LocalApiRuntimeOwner =
        (injectedLocalApiRuntimeOwner as? LocalApiRuntimeOwner) ?: container.createLocalApiRuntimeOwner()
    private val initialApiPreferences = loadApiPreferences(application)
    /** Listener, notification, persisted intent, and UI state share this single lifecycle gate. */
    private val apiLifecycleMutex = Mutex()
    private val apiLifecycleSequence = AtomicLong(0L)
    private val apiLifecycleClosed = AtomicBoolean(false)
    private var apiLifecycleRequestJob: Job? = null
    /** Deduplicates repeated process-foreground callbacks and their worker health probes. */
    private var foregroundRecoveryJob: Job? = null
    private val foregroundRecoverySequence = AtomicLong(0L)
    internal val chatSessionStore = container.chatSessionStore
    private val characterMemorySummaryCoordinator = CharacterMemorySummaryCoordinator()
    private val chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore
    private val worldBookStore = container.worldBookStore
    private val knowledgeBaseStore = container.knowledgeBaseStore
    private val knowledgeLibraryPort = StoreKnowledgeLibraryPort(worldBookStore, knowledgeBaseStore)
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
    private val offlinePromptTranslationService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OfflinePromptTranslationService(AppOfflinePromptTranslationRuntimeProvider(application))
    }
    private val cloudImageProvider = container.cloudImageProvider
    private var activeWebSearchDeferred: Deferred<WebSearchResult>?
        get() = container.runtimeAdmission.activeWebSearchDeferred
        set(value) {
            container.runtimeAdmission.activeWebSearchDeferred = value
        }
    @Volatile private var activeWebSearchTaskId: String? = null
    private val localImageModelStore = container.localImageModelStore
    private val localImageLoraStore = container.localImageLoraStore
    private val imageUpscalerPreferences: android.content.SharedPreferences
        get() = container.imageUpscalerPreferences
    private val contextCompressionPreferences = application.getSharedPreferences(
        CONTEXT_COMPRESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val manualContextCompressionRequests: ContextCompressionRequestStore
        get() = container.manualContextCompressionRequests
    private val localImageWorkerClient: LocalImageWorkerClient
        get() = container.localImageWorkerClient
    private val deviceProfileReader = container.deviceProfileReader
    private val benchmarkRunner = BenchmarkRunner(engine, deviceProfileReader)
    private val agentLogger = AgentDecisionLogger(application)
    private val benchmarkHistoryLogger = container.benchmarkHistoryLogger
    // R3/C1: 诊断族读写随 C1 裁定 B 移入 DiagnosticsOperations（体逐字、评测点不变）。
    private val diagnosticsOperations = DiagnosticsOperations(
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
    private val initialChatSessions = loadedChatSessions
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
    private fun appendAssistant(
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
    private fun flushPendingAssistantOutput(generationRunId: Long? = null): Boolean =
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
    private val persistentPrefixCacheOperationSequence = AtomicLong(0L)
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
    private val localApiIdempotencyJournalLock = Any()
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

    private val uiStateStore = UiStateStore(bootstrapResult.uiState)
    // R3/C3-S5：chatRuns/流缓冲累加器入容器；terminalSettlement 端口具名化。
    private val chatStreamAccumulatorInit = container.initializeChatStreamAccumulator(uiStateStore, viewModelScope)

    private val chatAppearanceCoordinator: ChatAppearanceCoordinator =
        (injectedChatAppearanceCoordinator as? ChatAppearanceCoordinator)
            ?: container.createChatAppearanceCoordinator(
                scope = viewModelScope,
                uiStateStore = uiStateStore,
                showStatus = ::showStatus
            )

    // R3/C3-S13：启动对账 owner（lazy async + await 状态写随 body 移出；start() 调用点不变；
    // 声明位于 init/startAfterConstruction 之前，保持与旧字段等价的初始化序）。
    private val imageLibraryStartupReconciliation = container.createImageLibraryStartupReconciliation(
        uiStateStore, viewModelScope, chatImageCommitRecoveryPort, bootstrapResult.initialKnowledgeBases
    )
    private val chatTerminalSettlementPort = container.createChatTerminalSettlementPort(uiStateStore)
    // R3/C3-S3：token 测量 host 具名化——容器工厂构造，两个缓存随迁（仅本端口使用）。
    private val imagePromptTokenMeasurementHost = container.createImagePromptTokenMeasurementHost(uiStateStore)
    private val webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator =
        container.createWebSearchWorkflowCoordinator(uiStateStore, chatTerminalSettlementPort, viewModelScope)
    internal val visionAttachmentPort = container.createVisionAttachmentPort(uiStateStore, engine)
    // R3/C2（插队裁定 2）：TuningOwner 由组合根工厂构造（真实依赖注入）；VM 仅 intent 委托。
    private val tuningOwner = container.createTuningOwner(
        store = uiStateStore,
        scope = viewModelScope,
        engine = engine
    )
    // R3/C2: 生图准入/执行唯一 owner（C0-a）：三处端口实现与两个直连调用点收敛到同一实例。
    // R3/C2-S3: host 由 McaAppContainer 工厂装配具名适配器，出清 97 行匿名 object 与 33 处 this 引用。
    private val imageGenerationOwner: ImageGenerationOwner = container.createImageGenerationOwner(
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
    private fun showStatus(message: String?) {
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
    private fun tryAcquireObservedImageGenerationLease(
        requestId: String
    ): LocalImageGenerationCoordinator.Lease? =
        tryAcquireObservedImageGenerationLeaseFor(
            store = uiStateStore,
            coordinator = localImageGenerationCoordinator,
            observationLock = localImageGenerationCoordinatorObservationLock,
            requestId = requestId
        )

    /** Delegates to [releaseObservedImageGenerationLeaseFor]. Signature kept verbatim. */
    private fun releaseObservedImageGenerationLease(
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

    private fun updateGenerationImageGrantReleaseDefer(defer: Boolean) =
        chatImageWorkflowCoordinator.updateGenerationImageGrantReleaseDefer(defer)

    private fun observeManagedDownloads() {
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

    private val coordinatorHost = object : MainCoordinatorHost {
        override val store get() = uiStateStore
        override val scope get() = viewModelScope
        override val application get() = getApplication<Application>()
        override val container get() = this@MainViewModel.container
        override val engine get() = this@MainViewModel.engine
        override val initialChatSessions get() = this@MainViewModel.initialChatSessions
        override val chatContextComposer get() = this@MainViewModel.chatContextComposer
        override val tuningOwner get() = this@MainViewModel.tuningOwner
        override val agentLogger get() = this@MainViewModel.agentLogger
        override val benchmarkRunner get() = this@MainViewModel.benchmarkRunner
        override val diagnosticsOperations get() = this@MainViewModel.diagnosticsOperations
        override val localApiRuntimeOwner get() = this@MainViewModel.localApiRuntimeOwner
        override val modelScopeClient get() = this@MainViewModel.modelScopeClient
        override val installationScopeId get() = this@MainViewModel.installationScopeId
        override val localApiIdempotencyJournalLock get() = this@MainViewModel.localApiIdempotencyJournalLock
        override val characterMemorySummaryCoordinator get() = this@MainViewModel.characterMemorySummaryCoordinator
        override val knowledgeLibraryPort get() = this@MainViewModel.knowledgeLibraryPort
        override val contextCompressionPreferences get() = this@MainViewModel.contextCompressionPreferences
        override val imageLibraryStartupReconciliation get() = this@MainViewModel.imageLibraryStartupReconciliation
        override val persistentPrefixCacheOperationSequence get() = this@MainViewModel.persistentPrefixCacheOperationSequence
        override val modelIdentityReferenceMigration get() = this@MainViewModel.modelIdentityReferenceMigration
        override val skipInitialModelDiscovery get() = this@MainViewModel.skipInitialModelDiscovery
        override val imagePromptTokenMeasurementHost get() = this@MainViewModel.imagePromptTokenMeasurementHost
        override val visionAttachmentPort get() = this@MainViewModel.visionAttachmentPort
        override val chatTerminalSettlementPort get() = this@MainViewModel.chatTerminalSettlementPort
        override val webSearchWorkflowCoordinator get() = this@MainViewModel.webSearchWorkflowCoordinator
        override val chatAppearanceCoordinator get() = this@MainViewModel.chatAppearanceCoordinator
        override val imageUpscaleOwner get() = this@MainViewModel.imageUpscaleOwner
        override val chatImageRequestPort get() = this@MainViewModel.chatImageRequestPort
        override val imageLibraryCommitPort get() = this@MainViewModel.imageLibraryCommitPort
        override val imageGenerationOwner get() = this@MainViewModel.imageGenerationOwner
        override val offlinePromptTranslationService get() = this@MainViewModel.offlinePromptTranslationService

        override var activeWebSearchTaskId: String?
            get() = this@MainViewModel.activeWebSearchTaskId
            set(value) { this@MainViewModel.activeWebSearchTaskId = value }
        override var foregroundRecoveryJob: Job?
            get() = this@MainViewModel.foregroundRecoveryJob
            set(value) { this@MainViewModel.foregroundRecoveryJob = value }

        override fun hostShowStatus(message: String?) = this@MainViewModel.showStatus(message)
        override fun hostFail(message: String) = this@MainViewModel.fail(message)
        override fun hostBusy(message: String) = this@MainViewModel.busy(message)
        override fun hostFailDownload(message: String) = this@MainViewModel.failDownload(message)
        override fun hostLoadModel(requestedModel: ModelManifest) = this@MainViewModel.loadModel(requestedModel)
        override fun hostLaunchModelOperation(message: String, operation: suspend CoroutineScope.() -> Unit) =
            this@MainViewModel.launchModelOperation(message, operation)
        override fun hostTryAcquireObservedImageGenerationLease(requestId: String) =
            this@MainViewModel.tryAcquireObservedImageGenerationLease(requestId)
        override fun hostReleaseObservedImageGenerationLease(lease: LocalImageGenerationCoordinator.Lease) =
            this@MainViewModel.releaseObservedImageGenerationLease(lease)
        override fun hostPersistFiles(files: List<FileAssetRecord>) {
            this@MainViewModel.persistFiles(files)
        }
        override fun hostPersistChatSessions(
            sessions: List<ChatSessionRecord>?,
            binding: Pair<String, Set<String>>?,
            generationRunId: Long?
        ) = this@MainViewModel.persistChatSessions(sessions, binding, generationRunId)
        override fun hostBuildChatImageRetrySnapshot(request: ChatGeneratedImageRequest, sessionId: String) =
            this@MainViewModel.buildChatImageRetrySnapshot(request, sessionId)
        override fun hostChatImageNegativePromptSupported(state: MainUiState) =
            this@MainViewModel.chatImageNegativePromptSupported(state)
        override fun hostCancelImageGenerationForJob(expectedJobId: String?) =
            this@MainViewModel.cancelImageGenerationForJob(expectedJobId)
        override fun hostReserveUiGenerationStart() = this@MainViewModel.reserveUiGenerationStart()
        override fun hostStartGeneration(
            requestMessages: List<ChatMessage>,
            reservation: UiGenerationReservation?,
            toolExchanges: List<ChatToolExchange>,
            paramsOverride: GenerationParams?,
            offerAssistantImageTool: Boolean,
            chatModelIdOverride: String?,
            continuationRequestId: String?,
            preflightCompression: com.muyuchat.core.engine.ContextCompressionResult?
        ) = this@MainViewModel.startGeneration(
            requestMessages, reservation, toolExchanges, paramsOverride,
            offerAssistantImageTool, chatModelIdOverride, continuationRequestId, preflightCompression
        )
        override fun hostSendChatImageRequestWithPromptBridge(
            visibleUserText: String,
            prompt: String,
            requestedOutputCount: Int?,
            allowCloudTranslationFallback: Boolean
        ) = this@MainViewModel.sendChatImageRequestWithPromptBridge(
            visibleUserText, prompt, requestedOutputCount, allowCloudTranslationFallback
        )
        override fun hostSendPreparedMessage(state: MainUiState, preparedInput: PreparedChatInput) =
            this@MainViewModel.sendPreparedMessage(state, preparedInput)
        override fun hostPersistModelGenerationParams(state: MainUiState, params: GenerationParams) =
            this@MainViewModel.persistModelGenerationParams(state, params)
        override fun hostPersistModelGenerationParams(backend: ChatBackend, modelId: String?, params: GenerationParams) =
            this@MainViewModel.persistModelGenerationParams(backend, modelId, params)
        override fun hostUpdateParams(params: GenerationParams) = this@MainViewModel.updateParams(params)
        override fun hostCancelGenerationJob() = this@MainViewModel.cancelGenerationJob()
        override fun hostPublishManagedChatCatalogAfterValidation() =
            this@MainViewModel.publishManagedChatCatalogAfterValidation()
        override fun hostLoadModelGenerationParams(backend: ChatBackend, modelId: String?, defaults: GenerationParams) =
            this@MainViewModel.loadModelGenerationParams(backend, modelId, defaults)
        override fun hostCurrentDeviceProfile() = this@MainViewModel.currentDeviceProfile()
        override fun hostMarkLocalConversationContextInvalid() = this@MainViewModel.markLocalConversationContextInvalid()
        override fun hostEnqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =
            this@MainViewModel.enqueueManagedDownload(request, useAfterDownload)
        override fun hostSelectChatSession(sessionId: String) = this@MainViewModel.selectChatSession(sessionId)
        override fun hostNewChatForAssistant(assistant: AssistantRecord, bindDefaultModel: Boolean) =
            this@MainViewModel.newChatForAssistant(assistant, bindDefaultModel)
        override fun hostCaptureContentImportOwner() = this@MainViewModel.captureContentImportOwner()
        override suspend fun hostStageContentImportPreview(preview: ContentImportPreview) =
            this@MainViewModel.stageContentImportPreview(preview)
        override fun hostReadAttachmentText(uri: Uri, maxBytes: Int) =
            com.muyuchat.mca.readAttachmentText(getApplication<Application>().contentResolver, uri, maxBytes)
        override fun hostObserveManagedDownloads() = this@MainViewModel.observeManagedDownloads()
        override fun hostSchedulePersistentPrefixCacheClear(
            operation: Long,
            successMessage: String,
            failureMessage: String
        ) = this@MainViewModel.schedulePersistentPrefixCacheClear(operation, successMessage, failureMessage)
        override fun hostAppendAssistant(
            delta: String,
            reasoningDelta: String,
            reasoningDurationMs: Long,
            forcePublish: Boolean,
            generationRunId: Long?
        ) = this@MainViewModel.appendAssistant(delta, reasoningDelta, reasoningDurationMs, forcePublish, generationRunId)
        override fun hostFlushPendingAssistantOutput(generationRunId: Long?) =
            this@MainViewModel.flushPendingAssistantOutput(generationRunId)
        override suspend fun hostEnsureLocalConversationContextInvalidated() =
            this@MainViewModel.ensureLocalConversationContextInvalidated()
        override fun hostPrepareChatInput(state: MainUiState) =
            this@MainViewModel.prepareChatInput(state)
    }

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
    private fun publishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot =
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
    private val imageUpscaleOwner: ImageUpscaleOwner
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

    private fun cancelImageGenerationForJob(expectedJobId: String?) =
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
    private suspend fun stageContentImportPreview(preview: ContentImportPreview) = contentImportPreviewCoordinator.stageContentImportPreview(preview)
    fun cancelContentImportPreview(id: String) = contentImportPreviewCoordinator.cancelContentImportPreview(id)
    fun confirmContentImportPreview(id: String) = contentImportPreviewCoordinator.confirmContentImportPreview(id) { confirmKnowledgeDocumentImport(it) }

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
    ) {
        if (uiStateStore.current.isGenerating) {
            showStatus("请先停止当前生成，再导入知识库文件")
            return
        }
        if (knowledgeBaseId in uiStateStore.current.knowledgeBaseImportingIds) {
            showStatus("该知识库正在导入文件，请稍候")
            return
        }
        val known = uiStateStore.current.knowledgeBases.any { it.id == knowledgeBaseId }
        if (!known) {
            showStatus("未找到要导入的知识库")
            return
        }
        val owner = importOwner ?: captureContentImportOwner()
        uiStateStore.update { state ->
            state.copy(
                knowledgeBaseImportingIds = state.knowledgeBaseImportingIds + knowledgeBaseId,
                statusMessage = "正在准备知识库文档预览…"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val preview = runCatching {
                    val uri = Uri.parse(uriString)
                    val name = displayNameForUri(getApplication<Application>().contentResolver, uri)
                    require(isSupportedTextAttachment(getApplication<Application>().contentResolver, uri, name)) { "知识库目前支持文本、Markdown、JSON、XML、CSV 与代码文件。" }
                    val text = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { KnowledgeDocumentImportCodec.readTextCancellable(it) }
                        ?: error("无法读取所选文档")
                    knowledgeDocumentPreview(text, name, knowledgeBaseId, uriString, owner)
                }.getOrElse { error ->
                    if (error is CancellationException) throw error
                    showStatus("知识库导入失败：${error.message ?: "无法读取文件"}")
                    return@launch
                }
                stageContentImportPreview(preview)
            } finally {
                uiStateStore.update { it.copy(knowledgeBaseImportingIds = it.knowledgeBaseImportingIds - knowledgeBaseId) }
            }
        }
    }

    private suspend fun confirmKnowledgeDocumentImport(preview: ContentImportPreview): Boolean {
        val baseId = requireNotNull(preview.knowledgeBaseId)
        require(contentImportSha256(preview.rawSource) == preview.sourceHash) {
            "导入预览内容已改变，请重新选择文档。"
        }
        require(uiStateStore.current.knowledgeBases.any { it.id == baseId }) {
            "原知识库已删除，请重新选择知识库。"
        }
        val document = withContext(Dispatchers.IO) {
            knowledgeBaseStore.importDocument(baseId, preview.title, preview.rawSource, preview.source ?: "local")
        }
        val owner = preview.owner
        chatSessionPersistenceSequence.incrementAndGet()
        withContext(Dispatchers.IO) {
            chatSessionPersistenceMutex.withLock {
                val snapshot = uiStateStore.current
                val ownerSessionId = owner.sessionId?.takeIf { id -> snapshot.chatSessions.any { it.id == id } }
                val binding = ownerSessionId?.let { sessionId ->
                    val selected = synchronized(chatSessionPersistenceStateLock) {
                        pendingKnowledgeBindings[sessionId]?.toSet()
                    } ?: if (owner.isCurrent(snapshot)) snapshot.selectedKnowledgeBaseIds
                        else knowledgeBaseStore.selectedKnowledgeBaseIds(sessionId)
                    sessionId to (selected + baseId)
                }
                if (binding != null) {
                    val bindings = synchronized(chatSessionPersistenceStateLock) {
                        pendingKnowledgeBindings[binding.first] = binding.second
                        pendingKnowledgeBindings.filterKeys { id -> snapshot.chatSessions.any { it.id == id } }
                            .mapValues { (_, ids) -> ids.toSet() }
                    }
                    chatSessionStore.save(snapshot.chatSessions, bindings)
                    durableChatSessions = snapshot.chatSessions
                    synchronized(chatSessionPersistenceStateLock) {
                        bindings.forEach { (sessionId, ids) ->
                            if (pendingKnowledgeBindings[sessionId] == ids) pendingKnowledgeBindings.remove(sessionId)
                        }
                    }
                }
                val bases = knowledgeBaseStore.loadBases()
                val count = knowledgeBaseStore.documents(baseId).size
                uiStateStore.update { current ->
                    val ownerIsCurrent = owner.isCurrent(current)
                    current.copy(
                        knowledgeBases = bases,
                        knowledgeDocumentCounts = current.knowledgeDocumentCounts + (baseId to count),
                        selectedKnowledgeBaseIds = if (ownerIsCurrent) current.selectedKnowledgeBaseIds + baseId
                            else current.selectedKnowledgeBaseIds,
                        statusMessage = "已导入知识库文档：${document.title}（${document.chunkCount} 段）" +
                            if (ownerIsCurrent) "，已启用" else if (binding != null) "；已绑定原对话" else "；已保存，原对话已不存在"
                    )
                }
            }
        }
        if (owner.isCurrent(uiStateStore.current)) markLocalConversationContextInvalid()
        return true
    }

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


    private fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =
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

    /**
     * The chat bridge may emit a quality negative branch even when the selected image topology
     * cannot condition on one. Keep that branch out of CFG-disabled/unsupported workers; an
     * explicit non-empty options.negativePrompt would otherwise be rejected as a user override.
     */
    /** Delegates to [chatImageNegativePromptSupportedFor]. Signature kept verbatim. */
    private fun chatImageNegativePromptSupported(state: MainUiState): Boolean =
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



    private fun reserveUiGenerationStart(): UiGenerationReservation? =
        chatRuns.reserveStartAndCancelSuperseded()

    private fun cancelGenerationJob(): UiGenerationCancellation =
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

    private fun startGeneration(
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

    private fun newChatForAssistant(
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


    fun setPersistentPrefixCacheEnabled(enabled: Boolean) {
        val current = uiStateStore.current.persistentPrefixCacheEnabled
        if (current == enabled) {
            refreshPersistentPrefixCacheSummary()
            return
        }
        val operation = persistentPrefixCacheOperationSequence.incrementAndGet()
        GenerationPreferences.persistPersistentPrefixCacheEnabled(getApplication(), enabled)
        engine.setPersistentPrefixCacheEnabled(enabled)
        // R3/S21: 前缀缓存族写入统一经 UiStateStore。
        uiStateStore.update {
            it.copy(
                persistentPrefixCacheEnabled = enabled,
                statusMessage = if (enabled) {
                    "已启用持久化前缀缓存"
                } else {
                    "已关闭持久化前缀缓存，正在清理本机缓存"
                }
            )
        }
        if (enabled) {
            refreshPersistentPrefixCacheSummary()
        } else {
            // A disk-backed prefix must never outlive a user opt-out. The
            // normal edit barrier also clears the live text KV before another
            // local request can begin.
            markLocalConversationContextInvalid()
            schedulePersistentPrefixCacheClear(
                operation = operation,
                successMessage = "已关闭持久化前缀缓存，并清空本机缓存",
                failureMessage = "已关闭持久化前缀缓存；缓存清理未完成，可稍后重试"
            )
        }
    }

    fun clearPersistentPrefixCache() {
        val operation = persistentPrefixCacheOperationSequence.incrementAndGet()
        schedulePersistentPrefixCacheClear(
            operation = operation,
            successMessage = "已清空持久化前缀缓存",
            failureMessage = "前缀缓存清理未完成，请在当前生成结束后重试"
        )
    }

    private fun schedulePersistentPrefixCacheClear(
        operation: Long,
        successMessage: String,
        failureMessage: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // A native state export owns the cache-store lock until prefill
            // finishes. Temporarily suppress new exports, then wait for the
            // in-flight UI turn so clear cannot race a late atomic commit.
            engine.setPersistentPrefixCacheEnabled(false)
            chatRuns.activePublishedJob()?.join()
            val cleared = engine.clearPersistentPrefixCache()
            val summary = engine.persistentPrefixCacheSummary()
            val enabledNow = uiStateStore.current.persistentPrefixCacheEnabled
            engine.setPersistentPrefixCacheEnabled(enabledNow)
            // R3/S21: 前缀缓存族写入统一经 UiStateStore。
            uiStateStore.update { state ->
                state.copy(
                    persistentPrefixCacheEntryCount = summary.entryCount,
                    persistentPrefixCacheBytes = summary.totalBytes,
                    statusMessage = if (operation == persistentPrefixCacheOperationSequence.get()) {
                        if (cleared) successMessage else failureMessage
                    } else {
                        state.statusMessage
                    }
                )
            }
        }
    }

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
    fun onAppForegrounded() {
        // A file removed or replaced while backgrounded must restore the download action.
        viewModelScope.launch { offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle() }
        chatRuns.foreground()
        // R3/S22: 前后台写入经 UiStateStore。
        uiStateStore.update { state ->
            if (state.statusMessage != BACKGROUND_TASK_STATUS) {
                state
            } else {
                val imageActive = state.imageJobs.any { job -> !job.status.terminal }
                state.copy(
                    statusMessage = if (state.isGenerating || imageActive) {
                        "已回到前台，任务仍在运行。"
                    } else {
                        null
                    }
                )
            }
        }
        maybeCheckForAppUpdate()
        assistantImageContinuationCoordinator.resumePendingAssistantImageToolTurnIfReady()
        val recovery = foregroundRecoverySequence.incrementAndGet()
        foregroundRecoveryJob?.cancel()
        foregroundRecoveryJob = viewModelScope.launch(Dispatchers.IO) {
            // Construction-time recovery and process-foreground recovery share
            // the same serialized operation. Waiting for an already queued
            // request avoids the old start -> cancel -> stop -> start sequence
            // that could stop LocalApiForegroundService before Android delivered
            // its first onStartCommand callback.
            apiLifecycleRequestJob?.let { pending ->
                if (pending !== coroutineContext[Job]) pending.join()
            }
            if (recovery != foregroundRecoverySequence.get()) return@launch
            val preferences = loadApiPreferences(getApplication<Application>())
            val apiOperation = apiLifecycleSequence.incrementAndGet()
            val apiResult = applyLocalApiState(
                operation = apiOperation,
                enabled = preferences.apiEnabled,
                restEnabled = preferences.restEnabled,
                failurePrefix = "本机 API 恢复失败"
            ) ?: return@launch
            if (recovery != foregroundRecoverySequence.get()) return@launch

            val stateBeforeProbe = uiStateStore.current
            val canProbeWorker = stateBeforeProbe.loadedModelId != null &&
                !stateBeforeProbe.busy &&
                !stateBeforeProbe.isGenerating &&
                stateBeforeProbe.engineLifecycle !in setOf(
                    AgentEngineLifecycle.LOADING,
                    AgentEngineLifecycle.RELOADING,
                    AgentEngineLifecycle.ROLLING_BACK,
                    AgentEngineLifecycle.STOPPING
                )
            val health = if (canProbeWorker) {
                engine.tryRuntimeHealthSnapshot()
            } else {
                null
            }
            if (recovery != foregroundRecoverySequence.get()) return@launch

            uiStateStore.update { state ->
                if (recovery != foregroundRecoverySequence.get()) {
                    state
                } else {
                    val healthStillCurrent = health != null &&
                        state.loadedModelId == stateBeforeProbe.loadedModelId &&
                        !state.busy &&
                        !state.isGenerating &&
                        engine.stats.value == health.runtimeStats
                    val workerSessionLost = healthStillCurrent &&
                        health.workerSessionLost
                    val status = when {
                        apiResult.failure != null -> state.statusMessage
                        workerSessionLost && state.loadedModelId != null ->
                            "本地推理进程已被系统回收，将在下一次发送时自动恢复模型。"
                        else -> state.statusMessage
                    }
                    state.copy(
                        apiEnabled = apiResult.running,
                        restEnabled = apiResult.restEnabled,
                        engineLifecycle = if (workerSessionLost && state.loadedModelId != null) {
                            AgentEngineLifecycle.UNLOADED
                        } else {
                            state.engineLifecycle
                        },
                        statusMessage = status
                    )
                }
            }
            if (recovery == foregroundRecoverySequence.get()) {
                assistantImageContinuationCoordinator.resumePendingAssistantImageToolTurnIfReady()
            }
        }
    }

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
    ) {
        apiLifecycleClosed.set(true)
        apiLifecycleSequence.incrementAndGet()
        foregroundRecoverySequence.incrementAndGet()
        foregroundRecoveryJob?.cancel()
        apiLifecycleRequestJob?.cancel()
        if (forceServerStop) stopApiServer()
        if (stopForegroundService) {
            releaseLocalApiProcessOwnership()
        }
    }

    private fun requestLocalApiState(
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ) {
        if (apiLifecycleClosed.get()) return
        foregroundRecoverySequence.incrementAndGet()
        foregroundRecoveryJob?.cancel()
        val operation = apiLifecycleSequence.incrementAndGet()
        apiLifecycleRequestJob?.cancel()
        apiLifecycleRequestJob = viewModelScope.launch(Dispatchers.IO) {
            applyLocalApiState(operation, enabled, restEnabled, failurePrefix)
        }
    }

    private suspend fun applyLocalApiState(
        operation: Long,
        enabled: Boolean,
        restEnabled: Boolean,
        failurePrefix: String
    ): LocalApiApplyResult? = apiLifecycleMutex.withLock {
        fun operationIsCurrent(): Boolean =
            !apiLifecycleClosed.get() && apiLifecycleSequence.get() == operation

        if (!operationIsCurrent()) return@withLock null
        var committed = false
        try {
            if (enabled) {
                // Android may return from startForegroundService before
                // onCreate/onStartCommand has promoted the service.  Starting
                // the socket first creates a window where the API advertises
                // itself but is not protected from process/background limits;
                // on some cold starts that race left port 11435 unbound after
                // the service request was lost.  Promote and wait first, then
                // publish the listener.
                check(ensureLocalApiForegroundServiceReady(restEnabled)) {
                    "本机 API 后台服务未就绪，请回到应用前台后重试。"
                }
                startApiServer(if (restEnabled) "0.0.0.0" else "127.0.0.1")
            } else {
                stopApiServer()
                setLocalApiForegroundService(false, false)
            }
            currentCoroutineContext().ensureActive()
            if (!operationIsCurrent()) return@withLock null

            persistApiPreferences(apiEnabled = enabled, restEnabled = restEnabled)
            uiStateStore.update { state ->
                if (!operationIsCurrent()) {
                    state
                } else {
                    state.copy(
                        apiEnabled = enabled,
                        restEnabled = enabled && restEnabled,
                        localApiAddress = apiUrl("127.0.0.1"),
                        openApiAddress = currentOpenApiAddress()
                    )
                }
            }
            if (!operationIsCurrent()) return@withLock null
            committed = true
            LocalApiApplyResult(
                running = enabled,
                restEnabled = enabled && restEnabled
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!operationIsCurrent()) return@withLock null
            stopApiServer()
            runCatching { setLocalApiForegroundService(false, false) }
            uiStateStore.update { state ->
                if (!operationIsCurrent()) {
                    state
                } else {
                    state.copy(
                        apiEnabled = false,
                        restEnabled = false,
                        statusMessage = "${failurePrefix}：${error.message ?: "端口不可用"}"
                    )
                }
            }
            committed = true
            LocalApiApplyResult(running = false, restEnabled = false, failure = error)
        } finally {
            // A superseded operation must not tear down a replacement operation's
            // listener or foreground service. The replacement owns cleanup after
            // it acquires the same mutex; onCleared() handles process teardown.
            if (!committed && operationIsCurrent()) {
                stopApiServer()
                runCatching { setLocalApiForegroundService(false, false) }
            }
        }
    }

    private suspend fun awaitLocalApiForegroundServiceReady(): Boolean {
        if (LocalApiForegroundService.isForegroundReady()) return true
        return withTimeoutOrNull(LOCAL_API_FOREGROUND_READY_TIMEOUT_MS) {
            while (!LocalApiForegroundService.isForegroundReady()) {
                currentCoroutineContext().ensureActive()
                delay(25L)
            }
            true
        } == true
    }

    private suspend fun ensureLocalApiForegroundServiceReady(restEnabled: Boolean): Boolean {
        repeat(LOCAL_API_FOREGROUND_READY_ATTEMPTS) { attempt ->
            if (LocalApiForegroundService.isForegroundReady()) return true
            if (attempt > 0) delay(100L)
            runCatching {
                setLocalApiForegroundService(true, restEnabled)
            }.getOrElse { error ->
                if (attempt == LOCAL_API_FOREGROUND_READY_ATTEMPTS - 1) throw error
            }
            if (awaitLocalApiForegroundServiceReady()) return true
            // A timed-out start can leave an OEM service request queued. Clear
            // that request before the bounded retry so the next attempt is a
            // fresh startForegroundService call instead of an idempotent no-op.
            runCatching { LocalApiForegroundService.stop(getApplication()) }
        }
        return false
    }

    private fun setLocalApiForegroundService(enabled: Boolean, restEnabled: Boolean) {
        synchronized(localApiProcessLifecycleLock) {
            // A cancelled operation from a replaced ViewModel may reach its finally block late.
            // Only the current process owner may mutate the shared notification service.
            if (localApiProcessOwnerToken !== localApiRuntimeOwner || apiLifecycleClosed.get()) return
            if (enabled) {
                check(LocalApiForegroundService.start(getApplication(), restEnabled)) {
                    "系统拒绝启动 API 后台服务，请回到应用前台后重新开启 API。"
                }
            } else {
                LocalApiForegroundService.stop(getApplication())
            }
        }
    }

    private fun claimLocalApiProcessOwnership() {
        synchronized(localApiProcessLifecycleLock) {
            localApiProcessOwnerToken = localApiRuntimeOwner
        }
    }

    private fun releaseLocalApiProcessOwnership() {
        synchronized(localApiProcessLifecycleLock) {
            if (localApiProcessOwnerToken !== localApiRuntimeOwner) return
            localApiProcessOwnerToken = null
            LocalApiForegroundService.stop(getApplication())
        }
    }

    private fun startApiServer(bindHost: String) {
        synchronized(localApiProcessLifecycleLock) {
            if (localApiProcessOwnerToken !== localApiRuntimeOwner || apiLifecycleClosed.get()) {
                throw CancellationException("Local API process ownership changed before server start")
            }
            if (!LocalApiForegroundService.isListenerRunning(bindHost)) {
                cancelActiveLocalApiImageGeneration("Local API server is restarting.")
            }
            LocalApiForegroundService.ensureListener(bindHost, apiKey)
        }
    }

    private fun stopApiServer() {
        cancelActiveLocalApiImageGeneration("Local API server stopped.")
        LocalApiForegroundService.stopListener()
    }

    // R3/C3-S11：会话持久化端口具名化——容器工厂构造（真实依赖全对象化）。
    private val chatSessionPersistencePort = container.createChatSessionPersistencePort(uiStateStore, viewModelScope, engine)

    // R3/C3-S12：图片请求簿记端口的具名适配器。
    private val chatImageRequestPort: ChatImageRequestPort =
        container.createChatImageRequestPort(uiStateStore, chatSessionPersistencePort)

    // R3/C3-S14：图片发送/重试两端口的具名适配器（owner/admission/coordinator 均为真实对象）。
    private val sendChatImageRequestPort: SendChatImageRequestPort =
        container.createSendChatImageRequestPort(
            uiStateStore, chatSessionPersistencePort, chatImageRequestPort, imageGenerationOwner
        )
    private val chatImageRetryPort: ChatImageRetryPort =
        container.createChatImageRetryPort(
            uiStateStore, sendChatImageRequestPort, { chatImageCommitJournal }
        )

    // R3/C3-S16：图片库提交/删除两端口的具名适配器（依赖全部对象化；journal 保持 lazy）。
    private val imageLibraryCommitPort: ImageLibraryCommitPort =
        container.createImageLibraryCommitPort(uiStateStore, chatImageRequestPort)
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


    private val localApiAuxProviderPort: LocalApiAuxProviderPort =
        container.createLocalApiAuxProviderPort(
            store = uiStateStore,
            engine = engine,
            localApiPayloadPortProvider = { localApiControlPlaneCoordinator.localApiPayloadPort },
            benchmarkRunner = benchmarkRunner,
            buildRecommendationAction = ::buildRecommendation,
            generateLocalApiImageAction = ::generateLocalApiImage,
        )

    private val chatLocalApiProviderPort: ChatLocalApiProviderPort =
        container.createChatLocalApiProviderPort(
            engine = engine,
            store = uiStateStore,
            cancelActiveLocalApiImageGenerationAction = ::cancelActiveLocalApiImageGeneration,
            localApiPayloadPortProvider = { localApiControlPlaneCoordinator.localApiPayloadPort },
        )
    private val chatExecutionPort: ChatGenerationExecutionPort get() = coordinatorAssembly.chatExecutionPort

    /** The ViewModel side of [ChatImagePromptBridgePort]. */
    private val chatImagePromptBridgePort: ChatImagePromptBridgePort =
        container.createChatImagePromptBridgePort(
            store = uiStateStore,
            chatContextComposer = chatContextComposer,
            engine = engine,
            currentBridgeGenerationRef = { container.runtimeAdmission.chatImagePromptBridgeGeneration.get() },
            incrementBridgeGenerationRef = { container.runtimeAdmission.chatImagePromptBridgeGeneration.incrementAndGet() },
            cancelBridgeJobRef = { container.runtimeAdmission.chatImagePromptBridgeJob?.cancel() },
            setBridgeJobRef = { container.runtimeAdmission.chatImagePromptBridgeJob = it },
            bridgeScopeRef = { viewModelScope },
            setPendingChatImageActionRef = { id, kind, visibleUserText, requestedOutputCount, stillOwned ->
                container.runtimeAdmission.pendingChatImageAction = PendingChatImageAction(
                    id = id,
                    kind = kind,
                    visibleUserText = visibleUserText,
                    requestedOutputCount = requestedOutputCount,
                    stillOwned = stillOwned
                )
            },
            setPendingImageTranslationRef = { container.runtimeAdmission.pendingImageTranslation = it },
            showStatusRef = { showStatus(it) },
            chatImageOptionsForCurrentSelectionRef = { chatImageOptionsForCurrentSelection(it) },
            offlinePromptTranslationServiceProvider = { offlinePromptTranslationService },
            verifiedOfflineTranslationBundleRef = { offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle() },
            sendChatImageRequestRef = { visibleUserText, prompt, optionsOverride, promptEnvelope, contextTraceJson ->
                SendChatImageRequestOperations.sendChatImageRequest(
                    port = sendChatImageRequestPort,
                    visibleUserText = visibleUserText,
                    prompt = prompt,
                    optionsOverride = optionsOverride,
                    promptEnvelope = promptEnvelope,
                    contextTraceJson = contextTraceJson
                )
            },
            negativePromptSupportedRef = { chatImageNegativePromptSupported(it) },
        )

    /** The ViewModel side of [ChatStopGenerationPort]. */
    private val chatStopGenerationPort: ChatStopGenerationPort =
        container.createChatStopGenerationPort(
            store = uiStateStore,
            engine = engine,
            stopScopeRef = { viewModelScope },
            rejectWhileConversationMutationInProgressRef = { rejectWhileConversationMutationInProgress() },
            stopGenerationInProgressRef = stopGenerationInProgress,
            currentReplyMetricsRef = { replyGenerationMetrics },
            cancelGenerationJobRef = { cancelGenerationJob() },
            flushPendingAssistantOutputRef = { flushPendingAssistantOutput() },
            persistConversationMutationRef = { sessions, rollback -> persistConversationMutation(sessions, rollback) },
        )


    private fun refreshPersistentPrefixCacheSummary() =
        mainPostStartupCoordinator.refreshPersistentPrefixCacheSummary()


    private fun markLocalConversationContextInvalid() =
        markLocalConversationContextInvalidInto(container.runtimeAdmission, uiStateStore, viewModelScope, engine)
    private fun rejectWhileConversationMutationInProgress(): Boolean =
        rejectWhileConversationMutationInProgressInto(uiStateStore, container.runtimeAdmission)

    private suspend fun ensureLocalConversationContextInvalidated(): Boolean {
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

    private fun persistFiles(files: List<FileAssetRecord> = uiStateStore.current.files) =
        chatInputAndAttachmentCoordinator.persistFiles(files)

    private fun busy(message: String) {
        uiStateStore.update { it.copy(busy = true, statusMessage = message) }
    }

    private fun failDownload(message: String) {
        uiStateStore.update { it.copy(busy = false, downloadStatus = DownloadStatus.FAILED,
            downloadFailureSource = com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.DOWNLOAD,
            downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null, statusMessage = message) }
    }

    private fun fail(message: String) {
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

    private fun loadModelGenerationParams(backend: ChatBackend, modelId: String?, defaults: GenerationParams): GenerationParams =
        loadModelGenerationParamsFor(modelGenerationProfileStore, backend, modelId, defaults)

    private fun persistModelGenerationParams(state: MainUiState, params: GenerationParams) =
        persistModelGenerationParams(backend = state.selectedChatBackend, modelId = state.currentChatModelId(), params = params)

    private fun persistModelGenerationParams(backend: ChatBackend, modelId: String?, params: GenerationParams) =
        GenerationPreferences.persistModelGenerationParams(modelGenerationProfileStore, backend, modelId, params)

    private fun loadGenerationParams(application: Application): GenerationParams =
        GenerationPreferences.loadGenerationParams(application)

    private fun loadPersistentPrefixCacheEnabled(application: Application): Boolean =
        GenerationPreferences.loadPersistentPrefixCacheEnabled(application)



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
