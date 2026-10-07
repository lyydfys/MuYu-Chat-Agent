package com.muyuchat.mca

import android.app.Application
import com.muyuchat.mca.composition.McaAppContainer
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.api.local.LocalApiRuntime
import com.muyuchat.api.local.LocalApiBusyState
import com.muyuchat.api.local.LocalApiControlPlane
import com.muyuchat.api.local.LocalApiPreflightRequest
import com.muyuchat.api.local.LocalApiPreflightResult
import com.muyuchat.api.local.ImageGenerationApiContract
import com.muyuchat.api.local.ImageGenerationProviderException
import com.muyuchat.api.local.McaLoopbackServer
import com.muyuchat.api.local.imagePromptTranslationProofFingerprint
import com.muyuchat.api.local.localApiPublicModelId
import com.muyuchat.core.advisor.AgentAdvisor
import com.muyuchat.core.advisor.AgentDecisionLog
import com.muyuchat.core.advisor.AgentDecisionLogger
import com.muyuchat.core.advisor.AgentRecommendation
import com.muyuchat.core.benchmark.BenchmarkHistoryLogger
import com.muyuchat.core.benchmark.BenchmarkHistoryRecord
import com.muyuchat.core.benchmark.BenchmarkResult
import com.muyuchat.core.benchmark.BenchmarkRunner
import com.muyuchat.core.benchmark.BenchmarkSweepConfig
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ImageEngineAccelerator
import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.ImageEngineBundleSpec
import com.muyuchat.core.download.ImageEngineBundleRuntime
import com.muyuchat.core.download.ImageEngineMinDeviceTier
import com.muyuchat.core.download.LocalImageEngineTier
import com.muyuchat.core.download.BundleComponentDownloader
import com.muyuchat.core.download.MnnModelBundleComponentRole
import com.muyuchat.core.download.ModelBundleInstaller
import com.muyuchat.core.download.ModelBundleInstallPlan
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RemoteModelFileKind
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.DownloadTaskSnapshot
import com.muyuchat.core.download.ResumableDownloader
import com.muyuchat.core.download.VisionModelAccelerator
import com.muyuchat.core.download.VisionModelBundleSpec
import com.muyuchat.core.download.VisionModelBundleRuntime
import com.muyuchat.core.download.VisionModelBundleComponentRole
import com.muyuchat.core.download.fileKind
import com.muyuchat.core.download.isImageModelCandidate
import com.muyuchat.core.download.kindLabel
import com.muyuchat.core.download.stagedTransformer
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.deduplicateVisionAttachments
import com.muyuchat.core.engine.deduplicateVisionAttachmentsForDisplay
import com.muyuchat.core.engine.visionDeduplicationKey
import com.muyuchat.core.engine.ChatGeneratedImageRequest
import com.muyuchat.core.engine.ChatGeneratedImageOrigin
import com.muyuchat.core.engine.ChatGeneratedImageStatus
import com.muyuchat.core.engine.ChatImageToolContinuationStatus
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatToolCall
import com.muyuchat.core.engine.ChatToolExchange
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.ContextCompressionSettings
import com.muyuchat.core.engine.ContextCompressionThreshold
import com.muyuchat.core.engine.ContextCompressionTrigger
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.mca.presentation.ChatContextSnapshot
import com.muyuchat.core.engine.compressChatRequestContext
import com.muyuchat.core.engine.AuthorizedPendingSignatureVerification
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
import com.muyuchat.core.engine.liteRtVisionInputAvailable
import com.muyuchat.core.engine.inlineVisionImageWithinLimit
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.PromptContextUsage
import com.muyuchat.core.engine.RuntimeOverrideSignature
import com.muyuchat.core.engine.CompletionPreflight
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.TokenProgress
import com.muyuchat.core.engine.PersistProgress
import com.muyuchat.core.engine.QairtExecutionVerificationStore
import com.muyuchat.core.engine.localContextWindowAdmission
import com.muyuchat.core.engine.qairtRuntimeIdentityFor
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.modelstore.QairtBundleRuntimeIdentity
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import com.muyuchat.core.tuning.AdaptiveTuningRecommendation
import com.muyuchat.core.tuning.CandidateHardGate
import com.muyuchat.core.tuning.CandidateProcessBoundary
import com.muyuchat.core.tuning.CandidateScore
import com.muyuchat.core.tuning.CandidateScorer
import com.muyuchat.core.tuning.StagedCandidateSelectionPolicy
import com.muyuchat.core.tuning.BootstrapLoadCanaryPolicy
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.HotExecutionParams
import com.muyuchat.core.tuning.LoadBoundExecutionParams
import com.muyuchat.core.tuning.MeasurementPoint
import com.muyuchat.core.tuning.MeasurementEnvelope
import com.muyuchat.core.tuning.ModelTuningCapabilities
import com.muyuchat.core.tuning.MinimumTextCanaryPolicy
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.PerformanceSample
import com.muyuchat.core.tuning.SafeBaselineFactory
import com.muyuchat.core.tuning.SafetyEnvelope
import com.muyuchat.core.tuning.TuningExecutionProfile
import com.muyuchat.core.tuning.TuningCandidatePolicy
import com.muyuchat.core.tuning.TuningCandidateCanaryPlanner
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningMode
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.feature.chat.ImageGenerationUiTaskMode
import com.muyuchat.feature.chat.ImagePromptTokenMeasurement
import com.muyuchat.feature.chat.ChatBackendFamily
import com.muyuchat.feature.chat.chatBackendFamilyForRuntime
import com.muyuchat.feature.chat.withChatBackend
import com.muyuchat.core.nativebridge.NativeMnnDiffusionBridge
import com.muyuchat.core.sdnative.NativeStableDiffusionBridge
import com.muyuchat.core.telemetry.SocFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.zip.ZipInputStream
import com.muyuchat.mca.presentation.lifecycleAfterGeneration
import com.muyuchat.mca.presentation.afterClearChatGenerationStopped
import com.muyuchat.mca.presentation.ChatGenerationUiPublisher
import com.muyuchat.mca.presentation.withGenerationProgressCleared
// Three P2 extractions are intentionally NOT imported here: afterBackgroundGenerationStopped,
// withBackgroundCancellationFinalized and BackgroundCancelledAssistantOutput have no call site in
// this ViewModel, and MainViewModelCompletionFallbackTest asserts that stays true. Importing them
// only produced unused-import warnings, so the imports were removed rather than left dangling.
// The declarations remain in presentation/GenerationStateTransitions.kt.
import com.muyuchat.mca.presentation.afterGenerationStarted
import com.muyuchat.mca.presentation.afterGenerationCompleted
import com.muyuchat.mca.presentation.afterGenerationTerminated
import com.muyuchat.mca.presentation.afterNativeRuntimeReleased
import com.muyuchat.mca.presentation.nativeRuntimeReleaseObserved
import com.muyuchat.mca.presentation.restoreAfterConversationMutationFailure
import com.muyuchat.mca.presentation.KnowledgeLibraryOperations
import com.muyuchat.mca.presentation.StoreKnowledgeLibraryPort
import com.muyuchat.mca.presentation.knowledgeLibrarySnapshot
import com.muyuchat.mca.presentation.withKnowledgeLibraryChange
import com.muyuchat.mca.presentation.KnowledgeLibraryChange
import com.muyuchat.mca.presentation.WorldBookImportOutcome
import com.muyuchat.mca.presentation.WorldBookImportRequest
import com.muyuchat.mca.presentation.importWorldBook
import com.muyuchat.mca.presentation.ChatContextOperations
import com.muyuchat.mca.presentation.chatContextSnapshot
import com.muyuchat.mca.presentation.LocalApiImageGenerationPort
import com.muyuchat.mca.presentation.generateLocalApiImageOperations
import kotlinx.coroutines.flow.StateFlow
import com.muyuchat.mca.SelectChatSessionPort
import com.muyuchat.mca.selectChatSession
import com.muyuchat.mca.CandidateCanaryResultPort
import com.muyuchat.mca.toCandidateCanaryResult
import com.muyuchat.core.tuning.CanaryEvaluationParams
import com.muyuchat.core.engine.EngineLifecycleLease
import com.muyuchat.core.engine.LoadAuthorization
import com.muyuchat.core.engine.PendingProfileDisposition

enum class AppTab(val title: String) {
    CHAT("聊天"),
    AGENT("智能调参"),
    MODELS("模型管理"),
    API("本地 API"),
    SETTINGS("系统设置")
}
// R3/C2: private → internal：随 enqueue 体移入 ImageGenerationOwner 所需。
internal fun chatImageCommitJournalJobId(jobId: String, imageId: String): String {
    val safeImageId = imageId.filter { it.isLetterOrDigit() || it in "._:-" }
        .take(128)
        .ifBlank { "image" }
    return "${jobId.take(120)}.$safeImageId"
}

/**
 * Widened from `private` so the conversion that builds it can live in its own file; only the
 * modifier changed. The type is still not part of any public surface.
 */
internal data class CandidateCanaryResult(
    val passed: Boolean,
    val output: String,
    val stats: RuntimeStats,
    val detail: String,
    val measurement: MeasurementEnvelope,
    val safetyPassed: Boolean,
    val signatureVerification: AuthorizedPendingSignatureVerification?,
    val crashCount: Int = 0,
    val anrCount: Int = 0,
    val nativeFatalSignalCount: Int = 0,
    val testedProfileId: String? = null,
    val testedResolvedLoadSignature: String? = null,
    val testedCommittedExecutionSignature: String? = null,
    val evidenceJson: String = "{}"
)

// R3/C1: private → internal：随 captureLoadedRuntimeSnapshotFor 移入运行时缝（C1 裁定 B）。
internal data class LoadedRuntimeSnapshot(
    val model: ModelManifest,
    val profile: ModelExecutionProfile,
    val uiState: MainUiState,
    val adaptiveRecommendation: AdaptiveTuningRecommendation?
)

private data class LocalApiApplyResult(
    val running: Boolean,
    val restEnabled: Boolean,
    val failure: Throwable? = null
)
internal val imageAttachmentRegex = Regex("""\u3010上传图片：([^\u3011]+)\u3011(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")
private val oldImagePlaceholderRegex = Regex("""\s*（当前文本模型会收到图片占位信息；完整识图能力后续接入多模态模型。）""")
internal const val FILE_ATTACHMENT_MARKER = "\u3010\u4e0a\u4f20\u6587\u4ef6\uff1a"
// `internal`, not `private`: the vision preparation that enforces this cap moved to
// VisionAttachmentSupport.kt, and one shared cap is safer than two copies drifting apart.
internal const val MAX_CHAT_IMAGES_PER_MESSAGE = 4
// `internal`, not `private`: the send-boundary deduplication that reads this budget moved to
// VisionAttachmentSupport.kt, and one shared bound is safer than two copies drifting apart.
internal const val CHAT_SEND_ATTACHMENT_IDENTITY_MAX_BYTES = 64L * 1024L * 1024L

internal data class LocalImagePromptTokenizerDescriptor(
    val modelKey: String,
    val bundleRoot: File,
    val backend: ImageTokenizerBackend,
    val route: ImagePromptTokenMeasurementRoute,
    val tokenizerJsonPath: File?,
    val bosId: Int,
    val eosId: Int,
    val padId: Int,
    val maxTokens: Int,
    val promptWeightingEnabled: Boolean,
)

/** Keeps UI-only tokenizer results bounded without ever caching an unavailable backend. */
// R3/C3-S3: private → internal：随端口具名化迁入容器。
internal class ImagePromptTokenMeasurementCache(
    private val maximumEntries: Int = 96
) {
    private val entries = object : LinkedHashMap<String, ImagePromptTokenMeasurement>(
        maximumEntries,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImagePromptTokenMeasurement>?
        ): Boolean = size > maximumEntries
    }

    init {
        require(maximumEntries > 0)
    }

    fun get(key: String): ImagePromptTokenMeasurement? = synchronized(entries) { entries[key] }

    fun put(key: String, value: ImagePromptTokenMeasurement) {
        synchronized(entries) { entries[key] = value }
    }
}
internal sealed interface DownloadedModelRegistration {
    data class Chat(val model: ModelManifest) : DownloadedModelRegistration
    data class Image(val model: LocalImageModelRecord) : DownloadedModelRegistration
    data class VisionProjector(val model: ModelManifest, val shouldReload: Boolean) : DownloadedModelRegistration
}

internal sealed interface VisionBundleDownloadResult {
    data class ChatModel(val model: ModelManifest) : VisionBundleDownloadResult
    data class EngineBundle(
        val displayName: String,
        val bundleDir: File,
        val report: LocalVisionNpuReport
    ) : VisionBundleDownloadResult
}


class MainViewModel @JvmOverloads constructor(
    application: Application,
    private val deferStartup: Boolean = false,
    private val skipInitialModelDiscovery: Boolean = false,
    injectedEngine: McaInferenceService? = null,
    injectedLocalApiRuntimeOwner: Any? = null,
    injectedChatAppearanceCoordinator: Any? = null
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
    private val container = McaAppContainer(application)
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

    /** Delegates to [AppUpdateOwner.maybeCheckForAppUpdate]. */
    fun maybeCheckForAppUpdate() {
        appUpdateOwner.maybeCheckForAppUpdate()
    }

    /** Delegates to [AppUpdateOwner.checkForAppUpdate]. */
    fun checkForAppUpdate() {
        appUpdateOwner.checkForAppUpdate()
    }

    /** Delegates to [AppUpdateOwner.setAppUpdateAutoCheckEnabled]. */
    fun setAppUpdateAutoCheckEnabled(enabled: Boolean) {
        appUpdateOwner.setAppUpdateAutoCheckEnabled(enabled)
    }

    /** Delegates to [AppUpdateOwner.downloadAppUpdate]. */
    fun downloadAppUpdate() {
        appUpdateOwner.downloadAppUpdate()
    }

    /** Delegates to [AppUpdateOwner.installAppUpdate]. */
    fun installAppUpdate() {
        appUpdateOwner.installAppUpdate()
    }

    /** Delegates to [AppUpdateOwner.openAppUpdateRelease]. */
    fun openAppUpdateRelease() {
        appUpdateOwner.openAppUpdateRelease()
    }
    private val engine: McaInferenceService = injectedEngine ?: container.createInferenceService(
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
    private val chatSessionStore = container.chatSessionStore
    private val characterMemorySummaryCoordinator = CharacterMemorySummaryCoordinator()
    private val chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore
    private val worldBookStore = container.worldBookStore
    private val knowledgeBaseStore = container.knowledgeBaseStore
    private val knowledgeLibraryPort = StoreKnowledgeLibraryPort(worldBookStore, knowledgeBaseStore)
    private val chatContextComposer =
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
    private val assistantSaveMutex = Mutex()

    private fun persistAssistantsAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            assistantSaveMutex.withLock {
                runCatching { assistantStore.saveAssistants(uiStateStore.current.assistants) }
                    .onFailure { error ->
                        uiStateStore.update { it.copy(
                            statusMessage = "助手保存失败：${error.message ?: "请重试"}"
                        ) }
                    }
            }
        }
    }
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
    private val advisor = container.advisor
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
    private val initialDeviceProfile = deviceProfileReader.read()
    private val initialParams = loadGenerationParams(application)
    private val initialPersistentPrefixCacheEnabled = loadPersistentPrefixCacheEnabled(application)
    private val runtimeUserOverrideFields: MutableSet<String>
        get() = container.runtimeAdmission.runtimeUserOverrideFields
    private val initialAssistants = assistantStore.loadAssistants(initialParams)
    private val initialAssistantMemories = initialAssistants.flatMap { assistant ->
        chatSessionStore.loadMemories(assistant.id)
    }
    private val initialGlobalChatAppearance = container.globalChatAppearanceStore.load()
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
    // R3/C3-S4：持久化状态 holder 的容器初始化（紧随初值装配完成之后）。
    private val chatSessionPersistenceStateInit = container.initializeChatSessionPersistenceState(initialChatSessions)
    private val initialWorldBooks = worldBookStore.load()
    private val initialKnowledgeBases = knowledgeBaseStore.loadBases()
    private val initialStartupSession = initialChatSessions
        .latestSessionForAssistant(initialStoredSelectedAssistantId)
        ?: initialChatSessions.firstOrNull()
    // Per-base document counts are refreshed after the first frame.  Querying every document
    // table here made startup time grow with the number of imported knowledge bases.
    private val initialKnowledgeDocumentCounts: Map<String, Int> = emptyMap()
    private val initialKnowledgeBaseIds = initialStartupSession
        ?.id
        ?.let(knowledgeBaseStore::selectedKnowledgeBaseIds)
        .orEmpty()
    // Image/file history is hydrated after the shell is visible; it is not needed to construct
    // the first chat screen and can be large on devices with many generated images.
    private val initialImages: List<ImageAssetRecord> = emptyList()
    private val initialFiles: List<FileAssetRecord> = emptyList()
    private val initialSelectedAssistantId = initialStartupSession
        ?.assistantSnapshot
        ?.assistantId
        ?.takeIf { assistantId -> initialAssistants.any { it.id == assistantId } }
        ?: initialStartupSession
        ?.assistantId
        ?.takeIf { assistantId -> initialAssistants.any { it.id == assistantId } }
        ?: initialStoredSelectedAssistantId
    private val initialSelectedAssistant = initialAssistants.firstOrNull { it.id == initialSelectedAssistantId }
    private val initialEffectiveParams = initialSelectedAssistant?.toGenerationParams(initialParams) ?: initialParams
    private val initialLocalImageModels = if (skipInitialModelDiscovery) emptyList() else localImageModelStore.loadModels(discover = false)
    private val initialLocalImageLoras = localImageLoraStore.load()
    private val initialLocalImageUpscalers = container.localImageUpscalerStore.load()
    private val initialSelectedLocalImageUpscalerId = imageUpscalerPreferences
        .getString(IMAGE_UPSCALER_SELECTED_ID, null)
        ?.takeIf { id -> initialLocalImageUpscalers.any { it.id == id } }
        ?: initialLocalImageUpscalers.firstOrNull()?.id
    private val initialSelectedLocalImageModelId = localImageModelStore.loadSelectedModelId()
        ?.takeIf { id ->
            initialLocalImageModels.any {
                it.id == id &&
                    it.isReadyForLocalImageGeneration()
            }
        }
        ?: initialLocalImageModels.firstOrNull {
            it.isReadyForLocalImageGeneration()
        }?.id
    private val initialCloudModels = cloudApiStore.loadModels()
    private val initialSessionCloudChatModelId = initialStartupSession
        ?.takeIf { it.modelMode.equals("cloud", ignoreCase = true) }
        ?.modelId
        ?.takeIf { id -> initialCloudModels.any { it.id == id && it.kind == CloudModelKind.CHAT } }
    private val initialSelectedCloudChatModelId = initialSessionCloudChatModelId
        ?: cloudApiStore.loadSelectedCloudChatModelId()
        ?.takeIf { id -> initialCloudModels.any { it.id == id && it.kind == CloudModelKind.CHAT } }
        ?: initialCloudModels.firstOrNull { it.kind == CloudModelKind.CHAT }?.id
    private val initialSelectedCloudImageModelId = cloudApiStore.loadSelectedCloudImageModelId()
        ?.takeIf { id -> initialCloudModels.any { it.id == id && it.kind == CloudModelKind.IMAGE } }
        ?: initialCloudModels.firstOrNull { it.kind == CloudModelKind.IMAGE }?.id
    private val initialCloudConfig = initialCloudModels
        .firstOrNull { it.id == initialSelectedCloudChatModelId && it.kind == CloudModelKind.CHAT }
        ?.toChatConfig()
        ?: cloudApiStore.load()
    private val initialWebSearchConfig = container.webSearchStore.load()
    private val initialWebSearchDiagnostics = container.webSearchDiagnosticStore.load()
    private val initialSelectedBackend = initialStartupSession
        ?.modelMode
        ?.toChatBackendOrNull()
        ?: cloudApiStore.loadSelectedBackend()
    private val initialSelectedImageBackend = localImageModelStore.loadSelectedBackend()
    @Volatile
    private var replyGenerationMetrics: ReplyGenerationMetrics? = null
    /** Prevents double taps from invalidating the stop epoch twice and losing UI cleanup. */
    private val stopGenerationInProgress = AtomicBoolean(false)
    /** Serializes the final content-hash check for multi-image chat turns off the UI thread. */
    private val chatImageSubmissionPreflight = AtomicBoolean(false)
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
    private fun discardPendingAssistantOutput(generationRunId: Long? = null) =
        chatStreamAccumulator.discard(generationRunId)

    private var localConversationContextNeedsInvalidation: Boolean
        get() = container.runtimeAdmission.localConversationContextNeedsInvalidation
        set(value) {
            container.runtimeAdmission.localConversationContextNeedsInvalidation = value
        }
    private val localConversationContextInvalidationSequence: AtomicLong
        get() = container.runtimeAdmission.localConversationContextInvalidationSequence
    private var localConversationContextInvalidationJob: Job?
        get() = container.runtimeAdmission.localConversationContextInvalidationJob
        set(value) {
            container.runtimeAdmission.localConversationContextInvalidationJob = value
        }
    private val persistentPrefixCacheOperationSequence = AtomicLong(0L)
    /**
     * A destructive conversation edit is published to Room before native KV
     * state is invalidated.  The barrier is joined by the next generation, so
     * a process/power interruption cannot make a new turn race the old KV.
    */
    private var conversationMutationBarrier: Job?
        get() = container.runtimeAdmission.conversationMutationBarrier
        set(value) {
            container.runtimeAdmission.conversationMutationBarrier = value
        }
    private var durableChatSessions: List<ChatSessionRecord>
        get() = container.chatSessionPersistenceState.durableChatSessions
        set(value) {
            container.chatSessionPersistenceState.durableChatSessions = value
        }
    private val chatSessionPersistenceMutex: Mutex
        get() = container.chatSessionPersistenceState.persistenceMutex
    private val chatSessionPersistenceSequence: AtomicLong
        get() = container.chatSessionPersistenceState.persistenceSequence
    private val chatSessionPersistenceStateLock: Any
        get() = container.chatSessionPersistenceState.stateLock
    private val pendingKnowledgeBindings: MutableMap<String, Set<String>>
        get() = container.chatSessionPersistenceState.pendingKnowledgeBindings
    private val pendingWorldBookCleanupOwners: MutableMap<WorldBookScope, MutableSet<String>>
        get() = container.chatSessionPersistenceState.pendingWorldBookCleanupOwners
    private var directParameterStageJob: Job?
        get() = container.runtimeAdmission.directParameterStageJob
        set(value) {
            container.runtimeAdmission.directParameterStageJob = value
        }
    private val directParameterStageMutex: Mutex
        get() = container.runtimeAdmission.directParameterStageMutex
    private val directParameterStageGeneration: AtomicLong
        get() = container.runtimeAdmission.directParameterStageGeneration
    private val managedRuntimeReadinessRefreshGate = container.readinessRefreshGate
    private val imageAssetWriteMutex: Mutex
        get() = container.imageAssetWriteMutex
    private val imageLibraryMutationMutex: Mutex
        get() = container.imageLibraryMutationMutex
    private val imageFavoriteMutationSequence: AtomicLong
        get() = container.runtimeAdmission.imageFavoriteMutationSequence
    private val latestImageFavoriteMutations: java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>
        get() = container.runtimeAdmission.latestImageFavoriteMutations
    private var imageGenerationJob: Job?
        get() = container.runtimeAdmission.imageGenerationJob
        set(value) {
            container.runtimeAdmission.imageGenerationJob = value
        }
    private val contentImportPreviewStore = ContentImportPreviewStore(application)
    private val contentImportCommitMutex = Mutex()
    private val contentImportSelectionRevision: AtomicLong
        get() = container.runtimeAdmission.contentImportSelectionRevision
    internal fun captureContentImportOwner(): ContentImportOwner = ContentImportOwner(
        sessionId = uiStateStore.current.activeChatSessionId,
        assistantId = uiStateStore.current.selectedAssistantId,
        selectionRevision = contentImportSelectionRevision.get()
    )
    private fun ContentImportOwner.isCurrent(state: MainUiState): Boolean =
        selectionRevision == contentImportSelectionRevision.get() &&
            sessionId == state.activeChatSessionId && assistantId == state.selectedAssistantId

    /** Delegates to [ImageUpscaleOwner.upscaleImageAsset]. */
    fun upscaleImageAsset(imageId: String, targetScale: Int) {
        imageUpscaleOwner.upscaleImageAsset(imageId, targetScale, viewModelScope)
    }

    /** Delegates to [ImageUpscaleOwner.cancelImageUpscale]. */
    fun cancelImageUpscale() {
        imageUpscaleOwner.cancelImageUpscale(viewModelScope)
    }
    private var imageLibraryBackupJob: Job?
        get() = container.runtimeAdmission.imageLibraryBackupJob
        set(value) {
            container.runtimeAdmission.imageLibraryBackupJob = value
        }
    private val imageLibraryBackupCoordinator = ImageLibraryBackupCoordinator()
    private val agentTuningCoordinator = AgentTuningCoordinator()
    private var adaptiveTuningJob: Job?
        get() = agentTuningCoordinator.adaptiveTuningJob
        set(value) {
            agentTuningCoordinator.adaptiveTuningJob = value
        }
    // R3/C2：运行期准入态迁入容器 holder（同实例共享，读写语义与调用点不变）。
    private var activeRuntimeIdentity: ModelRuntimeIdentity?
        get() = container.runtimeAdmission.activeRuntimeIdentity
        set(value) {
            container.runtimeAdmission.activeRuntimeIdentity = value
        }
    private var activeVisionProjectorStamp: LoadedVisionProjectorStamp?
        get() = container.runtimeAdmission.activeVisionProjectorStamp
        set(value) {
            container.runtimeAdmission.activeVisionProjectorStamp = value
        }
    private var activeAdaptiveRecommendation: AdaptiveTuningRecommendation?
        get() = container.runtimeAdmission.activeAdaptiveRecommendation
        set(value) {
            container.runtimeAdmission.activeAdaptiveRecommendation = value
        }
    private var pendingAdaptiveRecommendation: AdaptiveTuningRecommendation?
        get() = container.runtimeAdmission.pendingAdaptiveRecommendation
        set(value) {
            container.runtimeAdmission.pendingAdaptiveRecommendation = value
        }
    private var pendingProfileTransactionId: String?
        get() = container.runtimeAdmission.pendingProfileTransactionId
        set(value) {
            container.runtimeAdmission.pendingProfileTransactionId = value
        }
    private var activeProfileTransactionId: String?
        get() = container.runtimeAdmission.activeProfileTransactionId
        set(value) {
            container.runtimeAdmission.activeProfileTransactionId = value
        }
    private var activeTuningJobId: String?
        get() = agentTuningCoordinator.activeTuningJobId
        set(value) {
            agentTuningCoordinator.activeTuningJobId = value
        }
    private var activeModelForRuntimeProfile: ModelManifest?
        get() = container.runtimeAdmission.activeModelForRuntimeProfile
        set(value) {
            container.runtimeAdmission.activeModelForRuntimeProfile = value
        }
    private val localApiIdempotencyJournalLock = Any()
    private val pendingRuntimeRecoveries = linkedMapOf<String, RuntimeRecoveryPlan>()
    private var activeImageGenerationJobId: String?
        get() = container.runtimeAdmission.activeImageGenerationJobId
        set(value) {
            container.runtimeAdmission.activeImageGenerationJobId = value
        }
    private var activeImageGenerationBackend: ImageBackend?
        get() = container.runtimeAdmission.activeImageGenerationBackend
        set(value) {
            container.runtimeAdmission.activeImageGenerationBackend = value
        }
    private var activeImageGenerationModelId: String?
        get() = container.runtimeAdmission.activeImageGenerationModelId
        set(value) {
            container.runtimeAdmission.activeImageGenerationModelId = value
        }
    // R3-S30：activeLocalImageWorkerRequestId 已入 RuntimeAdmissionState；读点直改容器读。
    private var activeLocalApiImageModelId: String?
        get() = container.runtimeAdmission.activeLocalApiImageModelId
        set(value) {
            container.runtimeAdmission.activeLocalApiImageModelId = value
        }
    private val localImageGenerationCoordinator: LocalImageGenerationCoordinator
        get() = container.localImageGenerationCoordinator
    private val localImageGenerationCoordinatorObservationLock: Any
        get() = container.localImageGenerationCoordinatorObservationLock
    private val localApiImageGenerationLifecycleLock = Any()
    private var activeLocalApiImageGenerationOwnership: LocalApiImageGenerationOwnership? = null
    private val localImageLoraLifecycleLock: Any
        get() = container.localImageLoraLifecycleLock
    private val localImageTextualInversionLifecycleLock: Any
        get() = container.localImageTextualInversionLifecycleLock

    private suspend fun awaitImageLibraryStartupReconciliation() {
        imageLibraryStartupReconciliation.await()
    }

/** Delegates to [deleteCloudModelRecord]. Signature kept verbatim. */
    fun deleteCloudModel(modelId: String) {
        deleteCloudModelRecord(port = chatImageRetryPort, modelId = modelId)
    }

/** Delegates to [applyChatImageTranslationDraft]. Signature kept verbatim. */
    fun confirmChatImageTranslationDraft(id: String, positive: String, negative: String?) {
        applyChatImageTranslationDraft(port = chatImageRetryPort, id = id, positive = positive, negative = negative)
    }

/** Delegates to [chatImageRetrySnapshot]. Signature kept verbatim. */
    fun buildChatImageRetrySnapshot(
        request: ChatGeneratedImageRequest,
        chatSessionId: String
    ): ImageGenerationJobSpec? {
        return chatImageRetrySnapshot(port = chatImageRetryPort, request = request, chatSessionId = chatSessionId)
    }

    private val uiStateStore = UiStateStore(
        MainUiState(
            messages = initialStartupSession?.messages.orEmpty(),
            chatSessions = initialChatSessions,
            activeChatSessionId = initialStartupSession?.id,
            images = initialImages,
            files = initialFiles,
            localImageModels = initialLocalImageModels,
            localImageLoras = initialLocalImageLoras,
            localImageTextualInversionLoading = true,
            localImageTextualInversionMessage = "正在加载 Textual Inversion 库…",
            localImageUpscalers = initialLocalImageUpscalers,
            selectedLocalImageUpscalerId = initialSelectedLocalImageUpscalerId,
            selectedLocalImageModelId = initialSelectedLocalImageModelId,
            selectedImageBackend = when (initialSelectedImageBackend) {
                ImageBackend.LOCAL -> if (initialSelectedLocalImageModelId != null) ImageBackend.LOCAL else ImageBackend.CLOUD
                ImageBackend.CLOUD -> ImageBackend.CLOUD
            },
            cloudApiConfig = initialCloudConfig,
            cloudModels = initialCloudModels,
            selectedCloudChatModelId = initialSelectedCloudChatModelId,
            selectedCloudImageModelId = initialSelectedCloudImageModelId,
            assistants = initialAssistants,
            assistantMemories = initialAssistantMemories,
            selectedAssistantId = initialSelectedAssistantId,
            globalChatAppearance = initialGlobalChatAppearance,
            worldBooks = initialWorldBooks,
            knowledgeBases = initialKnowledgeBases,
            knowledgeDocumentCounts = initialKnowledgeDocumentCounts,
            selectedKnowledgeBaseIds = initialKnowledgeBaseIds,
            selectedChatBackend = if (initialSelectedBackend == ChatBackend.CLOUD && initialCloudConfig.configured) {
                ChatBackend.CLOUD
            } else {
                ChatBackend.LOCAL
            },
            // Managed model discovery may walk multi-gigabyte package trees. The IO refresh in
            // init populates this list without blocking the first MainActivity frame.
            models = emptyList(),
            mnnRuntimeAvailable = engine.isRuntimeAvailable(LocalChatRuntime.MNN_CPU),
            recommendedRemoteModels = sortRecommendedModels(
                modelScopeClient.userFacingRecommendedModels(),
                initialDeviceProfile
            ),
            params = loadModelGenerationParams(
                backend = if (initialSelectedBackend == ChatBackend.CLOUD) {
                    ChatBackend.CLOUD
                } else {
                    ChatBackend.LOCAL
                },
                modelId = if (initialSelectedBackend == ChatBackend.CLOUD) {
                    initialSelectedCloudChatModelId
                } else {
                    initialStartupSession?.modelId
                },
                defaults = initialEffectiveParams
            ),
            agentLogs = agentLogger.recent(),
            benchmarkHistory = benchmarkHistoryLogger.recent(),
            deviceProfile = initialDeviceProfile,
            // These fields report the actual listener, not the persisted desired state. Startup
            // restoration publishes RUNNING only after both the socket and foreground service work.
            apiEnabled = false,
            restEnabled = false,
            apiKey = apiKey,
            localApiAddress = apiUrl("127.0.0.1"),
            openApiAddress = currentOpenApiAddress(),
            webSearchConfig = initialWebSearchConfig,
            webSearchDiagnostics = initialWebSearchDiagnostics,
            persistentPrefixCacheEnabled = initialPersistentPrefixCacheEnabled,
            contextCompressionThresholdPercent = ContextCompressionCoordinator.loadThreshold(contextCompressionPreferences).percent,
            contextSummaryModelSelection = ContextCompressionCoordinator.loadSummaryModelSelection(contextCompressionPreferences),
            appUpdate = initialAppUpdateState
        )
    )
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
        uiStateStore, viewModelScope, chatImageCommitRecoveryPort, initialKnowledgeBases
    )
    private val chatTerminalSettlementPort = container.createChatTerminalSettlementPort(uiStateStore)
    // R3/C3-S3：token 测量 host 具名化——容器工厂构造，两个缓存随迁（仅本端口使用）。
    private val imagePromptTokenMeasurementHost = container.createImagePromptTokenMeasurementHost(uiStateStore)
    private val webSearchWorkflowCoordinator: WebSearchWorkflowCoordinator =
        container.createWebSearchWorkflowCoordinator(uiStateStore, chatTerminalSettlementPort, viewModelScope)
    private val visionAttachmentPort = container.createVisionAttachmentPort(uiStateStore, engine)
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
        synchronized(localImageGenerationCoordinatorObservationLock) {
            if (localImageGenerationCoordinator.activeRequestId() != null) {
                false
            } else {
                releaseBlock()
                true
            }
        }

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
        updateGenerationImageGrantReleaseDeferInto(uiStateStore, defer)

    private val modelImportAndDownloadCoordinator = ModelImportAndDownloadCoordinator(
        application = getApplication(),
        scope = viewModelScope,
        store = uiStateStore,
        modelStore = modelStore,
        localImageModelStore = localImageModelStore,
        getManagedRuntimeReadinessRefreshGate = { managedRuntimeReadinessRefreshGate },
        getManagedRuntimeReadinessPort = { managedRuntimeReadinessPort },
        chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
        getInitialChatSessionIds = { initialChatSessions.map { it.id } },
        showStatus = ::showStatus,
        failDownload = ::failDownload,
        busy = ::busy,
        fail = ::fail,
        loadModel = ::loadModel,
        reconcileModelIdentities = { reconcileModelIdentities() }
    )

    private val localImageLibraryAndModelCoordinator = LocalImageLibraryAndModelCoordinator(
        store = uiStateStore,
        scope = viewModelScope,
        application = getApplication(),
        container = container,
        localImageModelStore = localImageModelStore,
        localImageWorkerClient = localImageWorkerClient,
        localImageLoraStore = localImageLoraStore,
        managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
        launchModelOperation = ::launchModelOperation,
        tryAcquireObservedImageGenerationLease = ::tryAcquireObservedImageGenerationLease,
        releaseObservedImageGenerationLease = ::releaseObservedImageGenerationLease,
        showStatus = ::showStatus,
        busy = ::busy,
        fail = ::fail,
        persistFiles = ::persistFiles,
        activeImageGenerationModelId = { activeImageGenerationModelId },
        activeLocalApiImageModelId = { activeLocalApiImageModelId },
        imageUpscaleOwnerProvider = { imageUpscaleOwner }
    )

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

    private val assistantImageContinuationCoordinator: AssistantImageToolContinuationCoordinator = AssistantImageToolContinuationCoordinator(
        store = uiStateStore,
        admission = container.runtimeAdmission,
        container = container,
        cloudChatProvider = cloudChatProvider,
        chatContextComposer = chatContextComposer,
        imageGenerationOwnerProvider = { imageGenerationOwner },
        chatImageRequestPort = { chatImageRequestPort },
        persistChatSessions = { persistChatSessions(it) },
        buildChatImageRetrySnapshot = { req, sId -> buildChatImageRetrySnapshot(req, sId) },
        chatImageNegativePromptSupported = { chatImageNegativePromptSupported(it) },
        cancelImageGenerationForJob = { cancelImageGenerationForJob(it) },
        reserveUiGenerationStart = ::reserveUiGenerationStart,
        cancelPendingReservation = { chatRuns.cancelPending(it) },
        showStatus = ::showStatus,
        startGeneration = { requestMessages, reservation, toolExchanges, paramsOverride, offerAssistantImageTool, chatModelIdOverride, continuationRequestId ->
            startGeneration(
                requestMessages = requestMessages,
                reservation = reservation,
                toolExchanges = toolExchanges,
                paramsOverride = paramsOverride,
                offerAssistantImageTool = offerAssistantImageTool,
                chatModelIdOverride = chatModelIdOverride,
                continuationRequestId = continuationRequestId
            )
        }
    )

    private val offlinePromptTranslationAndChatImageCoordinator: OfflinePromptTranslationAndChatImageCoordinator = OfflinePromptTranslationAndChatImageCoordinator(
        store = uiStateStore,
        admission = container.runtimeAdmission,
        scope = viewModelScope,
        container = container,
        installer = container.offlineTranslationInstaller,
        downloader = container.offlineTranslationDownloader,
        offlinePromptTranslationServiceProvider = { offlinePromptTranslationService },
        chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
        prepareChatInput = { it.prepareChatInput() },
        showStatus = ::showStatus,
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        hasUnresolvedAssistantImageToolTurn = assistantImageContinuationCoordinator::hasUnresolvedAssistantImageToolTurn,
        sendChatImageRequestWithPromptBridge = ::sendChatImageRequestWithPromptBridge,
        sendPreparedMessage = ::sendPreparedMessage
    )

    private val localApiControlPlaneCoordinator = LocalApiControlPlaneCoordinator(
        container = container,
        store = uiStateStore,
        engine = engine,
        tuningOwner = tuningOwner,
        runtimeProfileStore = runtimeProfileStore,
        agentTuningCoordinator = agentTuningCoordinator,
        getActiveTuningJobId = { activeTuningJobId },
        setActiveTuningJobId = { activeTuningJobId = it },
        getAdaptiveTuningJob = { adaptiveTuningJob },
        setAdaptiveTuningJob = { adaptiveTuningJob = it },
        getActiveRuntimeIdentity = { activeRuntimeIdentity },
        applyPendingRuntimeTransaction = ::applyPendingRuntimeTransaction,
        rollbackCommittedRuntimeProfile = ::rollbackCommittedRuntimeProfile,
        idempotencyJournalLock = localApiIdempotencyJournalLock,
        application = getApplication(),
        scope = viewModelScope
    )

    private val contextCompressionCoordinator = ContextCompressionCoordinator(
        store = uiStateStore,
        engine = engine,
        cloudChatProvider = cloudChatProvider,
        modelGenerationProfileStore = modelGenerationProfileStore,
        hasActiveImageRequest = { localImageGenerationCoordinator.activeRequestId() != null },
        manualContextCompressionRequests = manualContextCompressionRequests,
        preferences = contextCompressionPreferences,
        chatRuns = container.chatRuns,
        getActiveRuntimeIdentity = { activeRuntimeIdentity },
        markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
        persistChatSessions = { persistChatSessions() },
        showStatus = ::showStatus,
        onLocalInferenceStarted = {
            localConversationContextNeedsInvalidation = true
            localConversationContextInvalidationSequence.incrementAndGet()
        }
    )

    init {
        if (!deferStartup) startAfterConstruction()
    }

    internal fun startAfterConstruction() {
        restorePendingContentImportPreview()
        imageLibraryStartupReconciliation.start()
        observeManagedDownloads()
        modelImportAndDownloadCoordinator.observeManagedImports()
        viewModelScope.launch {
            offlinePromptTranslationAndChatImageCoordinator.verifiedOfflineTranslationBundle()?.let { bundle ->
                uiStateStore.update { state ->
                    if (state.offlineTranslationDownloading || state.offlineTranslationInstalling) state
                    else state.copy(
                        offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                        offlineTranslationStatus = offlinePromptTranslationAndChatImageCoordinator.offlineTranslationBundleReadyStatus(bundle)
                    )
                }
            }
        }
        viewModelScope.launch {
            ProcessUiLifecycleEvents.events.collect { event ->
                when (event) {
                    ProcessUiLifecycleEvent.FOREGROUNDED -> onAppForegrounded()
                    ProcessUiLifecycleEvent.BACKGROUNDED -> onAppBackgrounded()
                }
            }
        }
        engine.setPersistentPrefixCacheEnabled(initialPersistentPrefixCacheEnabled)
        if (initialPersistentPrefixCacheEnabled) {
            refreshPersistentPrefixCacheSummary()
        } else {
            val operation = persistentPrefixCacheOperationSequence.incrementAndGet()
            schedulePersistentPrefixCacheClear(
                operation = operation,
                successMessage = "已保持关闭持久化前缀缓存，并清空本机缓存",
                failureMessage = "持久化前缀缓存已关闭；旧缓存清理未完成，可稍后重试"
            )
        }
        runCatching {
            File(getApplication<Application>().cacheDir, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY)
                .deleteRecursively()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadInitialTextualInversionLibraryState(container.localImageTextualInversionStore)
            uiStateStore.update { state ->
                if (!state.localImageTextualInversionLoading) {
                    state
                } else {
                    state.copy(
                        localImageTextualInversions = loaded.records,
                        localImageTextualInversionLoading = false,
                        localImageTextualInversionMessage = loaded.message
                    )
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

        launchModelOperation("恢复运行记录失败") {
            recoverInterruptedRuntimeProfiles()
        }

        // Always enqueue one construction-time recovery when the persisted
        // preference says the API was enabled.  A ViewModel can be created
        // after the process foreground replay has already been consumed; in
        // that case waiting for the replay leaves the port closed indefinitely
        // (the intermittent cold-start failure observed on device).  The
        // lifecycle callback remains a second idempotent recovery path.
        if (initialApiPreferences.apiEnabled) {
            requestLocalApiState(
                enabled = true,
                restEnabled = initialApiPreferences.restEnabled,
                failurePrefix = "本机 API 自动恢复失败"
            )
        }

        viewModelScope.launch {
            // StateFlow already conflates updates while the diagnostic query runs.
            engine.stats.collect { stats ->
                // nativeStatsJson may cross a Binder/JNI boundary and can be
                // slow while a worker is loading. Never execute that query
                // inside the Main dispatcher or inside StateFlow.update's
                // retryable lambda; a stale diagnostic snapshot is preferable
                // to blocking frames or re-running the side effect.
                val nativeStats = withContext(Dispatchers.IO) {
                    runCatching { engine.nativeStatsJson() }.getOrDefault("{}")
                }
                if (engine.stats.value != stats) return@collect
                uiStateStore.update { state ->
                    val lifecycle = when {
                        state.isGenerating -> AgentEngineLifecycle.GENERATING
                        stats.loaded -> AgentEngineLifecycle.READY
                        state.engineLifecycle in setOf(
                            AgentEngineLifecycle.LOADING,
                            AgentEngineLifecycle.RELOADING,
                            AgentEngineLifecycle.ROLLING_BACK,
                            AgentEngineLifecycle.STOPPING
                        ) -> state.engineLifecycle
                        stats.lastError?.isNotBlank() == true -> AgentEngineLifecycle.ERROR
                        else -> AgentEngineLifecycle.UNLOADED
                    }
                    state.copy(
                        stats = stats,
                        nativeStatsJson = nativeStats,
                        engineLifecycle = lifecycle
                    )
                }
            }
        }

        // Publish the persisted catalog before either QAIRT recovery or stable-identity
        // reconciliation takes the model-store lock for multi-gigabyte hashing.
        viewModelScope.launch(Dispatchers.IO) {
            refreshRuntimeReadiness(managedRuntimeReadinessPort)
            // GenieX recovery is still needed when its installed package survived a lost
            // product manifest, but its directory hashing must follow catalog publication.
            if (!skipInitialModelDiscovery) {
                recoverModelOperation(
                    operation = { modelStore.recoverInstalledQairtBundles() },
                    onSuccess = { recovered ->
                        if (recovered.isNotEmpty()) {
                            refreshRuntimeReadiness(managedRuntimeReadinessPort)
                            uiStateStore.update { state ->
                                state.copy(statusMessage =
                                    "已恢复 ${recovered.size} 个现有 QAIRT NPU 模型；首次加载会自动隔离安全启动。")
                            }
                        }
                    },
                    onFailure = { error ->
                        Log.e("McaModelOperation", "恢复模型目录失败", error)
                        uiStateStore.update { state ->
                            state.copy(statusMessage =
                                "恢复模型目录失败：${error.message.orEmpty()}。请检查模型文件和存储空间后重试。")
                        }
                    }
                )
            }
            reconcileModelIdentities()
            refreshRuntimeReadiness(managedRuntimeReadinessPort)
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



    /**
     * validateForLoad may rewrite a QAIRT directory fingerprint. Invalidate any in-flight
     * readiness refresh first, then publish the exact persisted catalog and advisory evidence
     * before a later canary, load, cancellation, or failure can return.
     *
     * Must be called from an IO coroutine.
     */
    /** R3-S36：运行时就绪刷新端口具名适配器（容器工厂装配，出清 48 行匿名实现与 14 处 this@VM，破万里程碑）。 */
    private val managedRuntimeReadinessPort: ManagedRuntimeReadinessPort =
        container.createManagedRuntimeReadinessPort(
            store = uiStateStore,
            imageLibraryBackupJobActive = { imageLibraryBackupJob?.isCompleted == false },
            hasActiveImageUpscale = { imageUpscaleOwner.hasActiveUpscale() }
        )

    /** Delegates to [publishChatCatalogSnapshot]. */
    private fun publishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot =
        publishChatCatalogSnapshot(managedRuntimeReadinessPort)


    /** Refreshes product-facing readiness; persisted verification remains advisory. */

    fun onInputChange(value: String) {
        if (value != uiStateStore.current.input &&
            (container.runtimeAdmission.pendingImageTranslation != null || container.runtimeAdmission.pendingChatImageAction != null)
        ) invalidatePendingChatImagePreparation()
        val bounded = value.takeUtf8Prefix(MAX_DIRECT_COMPOSER_UTF8_BYTES)
        uiStateStore.update {
            it.copy(
                input = bounded.text,
                statusMessage = if (bounded.truncated) {
                    "输入超过 256 KiB，已保留前半部分；较长资料请导入知识库或作为文件使用。"
                } else {
                    it.statusMessage
                }
            )
        }
    }

    fun clearStatusMessage() {
        showStatus(null)
    }

    fun startBrowserTask(request: com.muyuchat.feature.chat.BrowserTaskLaunch) {
        val state = uiStateStore.current
        val session = request.sessionId?.let { id -> state.chatSessions.firstOrNull { it.id == id } }
        val ownerMessages = if (request.sessionId == state.activeChatSessionId) state.messages else session?.messages.orEmpty()
        if (request.sessionId != null && session == null && request.sessionId != state.activeChatSessionId) return
        if (request.messageId != null && ownerMessages.none { it.id == request.messageId }) return
        uiStateStore.update {
            it.copy(browserTask = com.muyuchat.feature.chat.BrowserTaskStateMachine.create(request))
        }
    }

    fun onBrowserTaskEvent(event: com.muyuchat.feature.chat.BrowserTaskEvent) {
        val activeTask = uiStateStore.current.browserTask
        if (event.action == com.muyuchat.feature.chat.BrowserTaskAction.CANCEL &&
            activeTask?.taskId == event.taskId && activeWebSearchTaskId == event.taskId
        ) activeWebSearchDeferred?.cancel()
        uiStateStore.update { state ->
            val task = state.browserTask ?: return@update state
            val currentOwner = task.sessionId == null || task.sessionId == state.activeChatSessionId
            val reduced = if (!currentOwner) {
                com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation owner changed")
            } else com.muyuchat.feature.chat.BrowserTaskStateMachine.reduce(task, event)
            state.copy(browserTask = reduced)
        }
    }

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

    fun attachFile(uriString: String) {
        attachFileFor(
            store = uiStateStore,
            scope = viewModelScope,
            contentResolver = getApplication<Application>().contentResolver,
            imageAssetWriteMutex = imageAssetWriteMutex,
            imageAssetDirectory = imageAssetDirectory,
            imageLibraryCommitPort = imageLibraryCommitPort,
            awaitImageLibraryStartupReconciliation = ::awaitImageLibraryStartupReconciliation,
            prepareChatInput = { it.prepareChatInput() },
            persistFiles = ::persistFiles,
            showStatus = ::showStatus,
            fail = ::fail,
            uriString = uriString
        )
    }


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
    ): ImagePromptTokenMeasurement? = measureImagePromptTokens(
        host = imagePromptTokenMeasurementHost,
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

    fun reportMissingLocalImageTextualInversionSelection() =
        localImageLibraryAndModelCoordinator.reportMissingLocalImageTextualInversionSelection()

    fun importLocalImageUpscaler(uriString: String) =
        localImageLibraryAndModelCoordinator.importLocalImageUpscaler(uriString)

    fun selectLocalImageUpscaler(id: String) =
        localImageLibraryAndModelCoordinator.selectLocalImageUpscaler(id)

    fun deleteLocalImageUpscaler(id: String) =
        localImageLibraryAndModelCoordinator.deleteLocalImageUpscaler(id)

    fun generateImageAsset(
        prompt: String,
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ): Boolean = imageGenerationOwner.submit(
        prompt = prompt,
        inputDraft = inputDraft,
        options = options,
        jobSnapshot = null
    )

    fun retryImageGeneration(jobId: String) {
        val sourceJob = uiStateStore.current.imageJobs.firstOrNull { it.id == jobId }
        val originalSnapshot = sourceJob?.spec
        if (sourceJob == null || originalSnapshot == null) {
            showStatus("原图片任务参数已不可用，无法按原任务重试。")
            return
        }
        if (!sourceJob.status.terminal) {
            showStatus("当前图片任务仍在运行，无需重复提交。")
            return
        }
        // A retry is a new admission. Re-resolve the current model record and discard prompt
        // execution evidence captured by the failed/previous worker; the profile, tokenizer and
        // language gate must be evaluated again before a new worker is bound.
        val currentModel = originalSnapshot.localModelSnapshot?.let { previous ->
            _uiState.value.localImageModels.firstOrNull { it.matchesCatalogId(previous.id) } ?: previous
        }
        val retrySnapshot = originalSnapshot.copy(
            localModelSnapshot = currentModel,
            promptExecution = null
        )
        val jobSnapshot = retrySnapshot
        val snapshot = jobSnapshot
        imageGenerationOwner.submit(
            prompt = snapshot.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot
        )
    }

    /** The ViewModel side of [ImageAssetCreationPort]. */
    // R3/C3-S15：资产生成/放大两端口的具名适配器（owner 与 S13 对账 owner 均为真实对象注入）。
    private val imageAssetCreationPort: ImageAssetCreationPort =
        container.createImageAssetCreationPort(uiStateStore, application, imageGenerationOwner)
    private val imageUpscalePort: ImageUpscalePort =
        container.createImageUpscalePort(
            uiStateStore, application, imageAssetCreationPort, imageLibraryStartupReconciliation
        )

    /** The single owner of the image-upscale execution. */
    private val imageUpscaleOwner = ImageUpscaleOwner(imageUpscalePort)

/** Delegates to [recreateAsset]. Signature kept verbatim. */
    fun recreateImageAsset(imageId: String) {
        recreateAsset(port = imageAssetCreationPort, imageId = imageId)
    }

/** Delegates to [applyLocalImageModelSelection]. Signature kept verbatim. */
    fun selectLocalImageModel(modelId: String) {
        applyLocalImageModelSelection(port = imageAssetCreationPort, modelId = modelId, scope = viewModelScope)
    }

/** Delegates to [createUpscaledAsset]. Signature kept verbatim. */
    suspend fun createUpscaledImageAsset(
        spec: ImageUpscaleJobSpec,
        result: LocalImageResult,
        requestId: String
    ): ImageAssetRecord = imageAssetWriteMutex.withLock {
        return createUpscaledAsset(port = imageAssetCreationPort, spec = spec, result = result, requestId = requestId)
    }

    private val imagePromptTranslationPort: ImagePromptTranslationPort =
        container.createImagePromptTranslationPort(
            uiStateStore,
            { offlinePromptTranslationService }
        )

    fun cancelImageGeneration() = cancelImageGenerationForJob(expectedJobId = null)

    /** Delegates to [cancelImageGenerationForJobFor]. Signature kept verbatim. */
    private fun cancelImageGenerationForJob(expectedJobId: String?) =
        cancelImageGenerationForJobFor(
            viewModelScope,
            uiStateStore,
            container.runtimeAdmission,
            localImageWorkerClient,
            container.runtimeAdmission.activeLocalImageWorkerRequestId,
            expectedJobId
        )

    /** Delegates to [publishLocalImagePreviewTo]. Signature kept verbatim. */
    private fun publishLocalImagePreview(jobId: String, progress: LocalImageProgress): PublishedLocalImagePreview? =
        publishLocalImagePreviewTo(
            uiStateStore,
            container.runtimeAdmission,
            getApplication(),
            imageAssetWriteMutex,
            jobId,
            progress
        )

    fun deleteImageAsset(imageId: String) = deleteImageAssets(listOf(imageId))

    fun deleteImageAssets(imageIds: List<String>) =
        deleteImageAssetsOperation(imageAssetDeletionPort, imageIds)

    fun setImageAssetFavorite(imageId: String, favorite: Boolean) =
        setImageAssetFavoriteOperation(imageAssetDeletionPort, imageId, favorite)

    fun clearImageLibrary() =
        clearImageLibraryOperation(imageAssetDeletionPort)

    fun exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) =
        imageLibraryBackupCoordinator.exportBackup(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication(),
            imageLibraryBackup = imageLibraryBackup,
            imageLibraryMutationMutex = imageLibraryMutationMutex,
            awaitStartupReconciliation = ::awaitImageLibraryStartupReconciliation,
            getActiveJob = { imageLibraryBackupJob },
            setActiveJob = { imageLibraryBackupJob = it },
            showStatus = ::showStatus,
            uriString = uriString,
            favoritesOnly = favoritesOnly
        )

    fun importImageLibraryBackup(uriString: String) =
        imageLibraryBackupCoordinator.importBackup(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication(),
            imageLibraryBackup = imageLibraryBackup,
            chatSessionStore = chatSessionStore,
            imageAssetWriteMutex = imageAssetWriteMutex,
            imageLibraryMutationMutex = imageLibraryMutationMutex,
            awaitStartupReconciliation = ::awaitImageLibraryStartupReconciliation,
            getActiveJob = { imageLibraryBackupJob },
            setActiveJob = { imageLibraryBackupJob = it },
            showStatus = ::showStatus,
            uriString = uriString
        )

    fun cancelImageLibraryBackup() =
        imageLibraryBackupCoordinator.cancelBackup { imageLibraryBackupJob }

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

    fun selectImageGenerationModel(choiceId: String) {
        when {
            choiceId.startsWith(LOCAL_IMAGE_MODEL_CHOICE_PREFIX) -> {
                selectLocalImageModel(choiceId.removePrefix(LOCAL_IMAGE_MODEL_CHOICE_PREFIX))
            }
            choiceId.startsWith(CLOUD_IMAGE_MODEL_CHOICE_PREFIX) -> {
                val id = choiceId.removePrefix(CLOUD_IMAGE_MODEL_CHOICE_PREFIX)
                selectCloudImageModel(id)
            }
        }
    }

    fun onRepoInputChange(value: String) {
        uiStateStore.update { it.copy(repoInput = value) }
    }

    fun onHubQueryChange(value: String) {
        uiStateStore.update { it.copy(hubQuery = value) }
    }

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
        persistAssistantsAsync()
        if (reloadRequired && changedRuntimeFields.isNotEmpty()) {
            stageDirectParameterProfile(scopedParams)
        } else if (!reloadRequired && changedRuntimeFields.isNotEmpty()) {
            directParameterStageGeneration.incrementAndGet()
            directParameterStageJob?.cancel()
            if (pendingProfileTransactionId != null) discardPendingAgentProfile()
        }
    }

    private fun stageDirectParameterProfile(params: GenerationParams) {
        stageDirectParameterProfileFor(
            store = uiStateStore,
            scope = viewModelScope,
            engine = engine,
            runtimeProfileStore = runtimeProfileStore,
            activeRuntimeIdentity = { activeRuntimeIdentity },
            runtimeUserOverrideFields = runtimeUserOverrideFields,
            directParameterStageGeneration = directParameterStageGeneration,
            getActiveDirectParameterStageJob = { directParameterStageJob },
            setActiveDirectParameterStageJob = { directParameterStageJob = it },
            directParameterStageMutex = directParameterStageMutex,
            adaptiveTuningJobIsActive = { adaptiveTuningJob?.isActive == true },
            getActiveTuningJobId = { activeTuningJobId },
            getPendingProfileTransactionId = { pendingProfileTransactionId },
            setPendingProfileTransactionId = { pendingProfileTransactionId = it },
            params = params
        )
    }

    fun saveAssistantProfile(
        id: String?,
        name: String,
        avatar: String,
        tag: String,
        systemPrompt: String,
        defaultModelMode: String,
        defaultModelId: String?,
        temperature: Float,
        topP: Float,
        nPredict: Int,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        presencePenalty: Float,
        frequencyPenalty: Float,
        stopWords: List<String>,
        reasoningMode: ReasoningMode,
        memoryEnabled: Boolean,
        memorySummaryInterval: Int,
        webSearchEnabled: Boolean,
        fileContextEnabled: Boolean
    ) = saveAssistantProfileFor(
        store = uiStateStore,
        assistantStore = assistantStore,
        application = getApplication(),
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
        showStatus = ::showStatus,
        persistModelGenerationParams = ::persistModelGenerationParams,
        persistAssistantsAsync = ::persistAssistantsAsync,
        persistChatSessions = ::persistChatSessions,
        markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
        selectAssistant = ::selectAssistant,
        applyAssistantDefaultModel = ::applyAssistantDefaultModel,
        id = id, name = name, avatar = avatar, tag = tag, systemPrompt = systemPrompt,
        defaultModelMode = defaultModelMode, defaultModelId = defaultModelId,
        temperature = temperature, topP = topP, nPredict = nPredict, topK = topK,
        minP = minP, repeatPenalty = repeatPenalty, presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty, stopWords = stopWords,
        reasoningMode = reasoningMode, memoryEnabled = memoryEnabled,
        memorySummaryInterval = memorySummaryInterval, webSearchEnabled = webSearchEnabled,
        fileContextEnabled = fileContextEnabled
    )

    fun upsertAssistantMemory(assistantId: String, id: String?, scope: String, content: String) =
        upsertAssistantMemoryFor(uiStateStore, viewModelScope, chatSessionStore, ::showStatus, ::markLocalConversationContextInvalid, assistantId, id, scope, content)

    fun deleteAssistantMemory(assistantId: String, id: String) =
        deleteAssistantMemoryFor(uiStateStore, viewModelScope, chatSessionStore, ::showStatus, ::markLocalConversationContextInvalid, assistantId, id)

    private fun recordCompletedCharacterMemoryTurn(
        assistantId: String,
        sessionId: String,
        assistantSnapshot: AssistantConversationSnapshot,
        user: ChatMessage,
        assistant: ChatMessage,
        conversationPersistJob: Job?,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) = recordCompletedCharacterMemoryTurnFor(
        store = uiStateStore, scope = viewModelScope, chatSessionStore = chatSessionStore,
        scheduleCharacterMemorySummary = ::scheduleCharacterMemorySummary,
        assistantId = assistantId, sessionId = sessionId, assistantSnapshot = assistantSnapshot,
        user = user, assistant = assistant, conversationPersistJob = conversationPersistJob,
        backend = backend, params = params, cloudConfig = cloudConfig, localIdentity = localIdentity
    )

    private fun scheduleCharacterMemorySummary(
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) = characterMemorySummaryCoordinator.schedule(
        scope = viewModelScope, summarizeCharacterMemory = ::summarizeCharacterMemory,
        assistantId = assistantId, backend = backend, params = params, cloudConfig = cloudConfig, localIdentity = localIdentity
    )

    private suspend fun summarizeCharacterMemory(
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) = summarizeCharacterMemoryFor(
        store = uiStateStore, chatSessionStore = chatSessionStore, cloudChatProvider = cloudChatProvider,
        engine = engine, chatSessionPersistenceMutex = chatSessionPersistenceMutex,
        activeRuntimeIdentity = { activeRuntimeIdentity },
        markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
        onLocalConversationContextNeedsInvalidation = {
            localConversationContextNeedsInvalidation = true
            localConversationContextInvalidationSequence.incrementAndGet()
        },
        recordReschedule = characterMemorySummaryCoordinator::recordReschedule,
        assistantId = assistantId, backend = backend, params = params, cloudConfig = cloudConfig, localIdentity = localIdentity
    )

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
        finishSelectingNewAssistantFor(
            store = uiStateStore,
            assistantStore = assistantStore,
            modelGenerationProfileStore = modelGenerationProfileStore,
            application = getApplication(),
            invalidatePendingChatImagePreparation = ::invalidatePendingChatImagePreparation,
            contentImportSelectionRevision = contentImportSelectionRevision,
            markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
            applyAssistantDefaultModel = ::applyAssistantDefaultModel,
            currentModelGenerationProfileKey = { uiStateStore.current.modelGenerationProfileKey() },
            assistant = assistant
        )

    fun importChatBackground(scope: ChatAppearanceScope, uriString: String) {
        chatAppearanceCoordinator.importChatBackground(scope, uriString)
    }

    fun setChatAppearance(scope: ChatAppearanceScope, appearance: ChatAppearance?) {
        chatAppearanceCoordinator.setChatAppearance(scope, appearance)
    }

    private fun queueWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) {
        require(scope != WorldBookScope.GLOBAL) { "Global world books are not owner-scoped." }
        val normalizedOwnerIds = ownerIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (normalizedOwnerIds.isEmpty()) return
        synchronized(chatSessionPersistenceStateLock) {
            pendingWorldBookCleanupOwners
                .getOrPut(scope) { linkedSetOf() }
                .addAll(normalizedOwnerIds)
        }
    }

    private fun cancelPendingWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) =
        cancelPendingWorldBookCleanupIn(container.chatSessionPersistenceState, scope, ownerIds)

    /**
     * World books are secondary data.  They are removed only after the Room
     * owner snapshot has committed; failed/skipped writes keep this queue for
     * the next authoritative snapshot.
     */
    private fun cleanupPendingWorldBooksAfterOwnerCommit(scope: WorldBookScope, retainedOwnerIds: Set<String>) =
        cleanupPendingWorldBooksAfterOwnerCommitFor(container.chatSessionPersistenceState, worldBookStore, uiStateStore, scope, retainedOwnerIds)

    fun deleteAssistant(assistantId: String) = deleteAssistantFor(
        store = uiStateStore,
        assistantStore = assistantStore,
        modelGenerationProfileStore = modelGenerationProfileStore,
        chatSessionStore = chatSessionStore,
        application = getApplication(),
        scope = viewModelScope,
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
        showStatus = ::showStatus,
        queueWorldBookCleanup = ::queueWorldBookCleanup,
        cleanupPendingWorldBooksAfterOwnerCommit = ::cleanupPendingWorldBooksAfterOwnerCommit,
        selectAssistant = ::selectAssistant,
        persistChatSessions = ::persistChatSessions,
        chatAppearanceMutex = chatAppearanceCoordinator.mutex,
        cleanupUnusedChatBackgrounds = { chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() },
        currentModelGenerationProfileKey = { uiStateStore.current.modelGenerationProfileKey() },
        assistantId = assistantId
    )

    fun importAssistantCard(rawJson: String) =
        importAssistantCardFor(uiStateStore, viewModelScope, ::showStatus, ::captureContentImportOwner, ::finishCharacterCardImport, rawJson)

    internal fun restorePendingContentImportPreview() {
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) { contentImportPreviewStore.load() } ?: return@launch
            uiStateStore.update { state ->
                if (state.contentImportPreview == null) state.copy(contentImportPreview = preview) else state
            }
        }
    }

    fun removeLocalImageModelRecord(modelId: String) =
        localImageLibraryAndModelCoordinator.removeLocalImageModelRecord(modelId)

    private suspend fun stageContentImportPreview(preview: ContentImportPreview) {
        contentImportCommitMutex.withLock {
            if (uiStateStore.current.contentImportCommitting) return
            try {
                withContext(Dispatchers.IO) { contentImportPreviewStore.save(preview) }
                uiStateStore.update { it.copy(contentImportPreview = preview) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showStatus("导入预览保存失败：${error.message}")
            }
        }
    }

    fun cancelContentImportPreview(id: String) {
        viewModelScope.launch {
            contentImportCommitMutex.withLock {
                if (uiStateStore.current.contentImportCommitting || uiStateStore.current.contentImportPreview?.id != id) return@withLock
                try {
                    withContext(Dispatchers.IO) { contentImportPreviewStore.clear(id) }
                    uiStateStore.update { it.copy(contentImportPreview = null) }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showStatus("无法关闭导入预览：${error.message}")
                }
            }
        }
    }

    fun confirmContentImportPreview(id: String) {
        viewModelScope.launch {
            contentImportCommitMutex.withLock {
                val preview = uiStateStore.current.contentImportPreview?.takeIf { it.id == id } ?: return@withLock
                if (uiStateStore.current.contentImportCommitting || uiStateStore.current.isGenerating) return@withLock
                uiStateStore.update { it.copy(contentImportCommitting = true) }
                try {
                    val committed = when (preview.kind) {
                        "character_card" -> {
                            val parsed = withContext(Dispatchers.Default) { CharacterCardCodec.parseJson(preview.rawSource) }
                            val originalSource = (parsed as? CharacterCardParseResult.Success)?.copy(
                                source = preview.sourceKind ?: CharacterCardSource.JSON
                            ) ?: parsed
                            finishCharacterCardImport(originalSource, preview.owner, preview.id)
                        }
                        "world_book" -> importWorldBookOwned(
                            preview.rawSource, requireNotNull(preview.scope), preview.owner, preview.id
                        )
                        "knowledge_document" -> confirmKnowledgeDocumentImport(preview)
                        else -> error("未知的导入预览类型")
                    }
                    if (committed) {
                        withContext(Dispatchers.IO) { contentImportPreviewStore.clear(id) }
                        uiStateStore.update { it.copy(contentImportPreview = null) }
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    showStatus("导入尚未完整提交：${error.message}；预览已保留，可重试。")
                } finally {
                    uiStateStore.update { it.copy(contentImportCommitting = false) }
                }
            }
        }
    }

    fun importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) =
        importAssistantCardFileFor(uiStateStore, viewModelScope, getApplication(), ::showStatus, ::captureContentImportOwner, ::finishCharacterCardImport, uriString, importOwner)

    private suspend fun finishCharacterCardImport(
        result: CharacterCardParseResult,
        owner: ContentImportOwner,
        commitId: String? = null
    ): Boolean = finishCharacterCardImportFor(
        store = uiStateStore,
        assistantStore = assistantStore,
        worldBookStore = worldBookStore,
        chatSessionStore = chatSessionStore,
        chatSessionPersistenceMutex = chatSessionPersistenceMutex,
        chatSessionPersistenceSequence = chatSessionPersistenceSequence,
        getPendingKnowledgeBindings = { pendingKnowledgeBindings },
        setDurableChatSessions = { durableChatSessions = it },
        chatSessionPersistenceStateLock = chatSessionPersistenceStateLock,
        stageContentImportPreview = { stageContentImportPreview(it) },
        conversationMutationBarrierIsActive = { conversationMutationBarrier?.isActive == true },
        hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
        currentOwnerIsCurrent = { owner.isCurrent(uiStateStore.current) },
        selectAssistant = ::selectAssistant,
        result = result,
        owner = owner,
        commitId = commitId
    )

    fun importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) {
        val owner = captureContentImportOwner()
        viewModelScope.launch { importWorldBookOwned(rawJson, scope, owner) }
    }

    fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?) {
        val frozen = selections?.toList()
        val valid = frozen?.let { runCatching { resolveCurrentLocalImageLorasFor(it, uiStateStore.current.localImageLoras) }.isSuccess } == true
        uiStateStore.update { it.copy(chatImageLoraSelections = frozen.takeIf { valid }) }
    }

    private suspend fun importWorldBookOwned(
        rawJson: String,
        scope: WorldBookScope,
        owner: ContentImportOwner,
        commitId: String? = null
    ): Boolean = importWorldBookOwnedFor(
        knowledgeLibraryPort = knowledgeLibraryPort,
        uiStateStore = uiStateStore,
        currentState = { uiStateStore.current },
        stageContentImportPreview = { stageContentImportPreview(it) },
        markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
        ownerIsCurrent = { it.isCurrent(uiStateStore.current) },
        rawJson = rawJson,
        scope = scope,
        owner = owner,
        commitId = commitId
    )

    fun importWorldBookFile(
        uriString: String,
        scope: WorldBookScope = WorldBookScope.ASSISTANT,
        importOwner: ContentImportOwner? = null
    ) = importWorldBookFileFor(
        scope = viewModelScope,
        isGenerating = { uiStateStore.current.isGenerating },
        showStatus = ::showStatus,
        captureContentImportOwner = ::captureContentImportOwner,
        importWorldBookOwned = ::importWorldBookOwned,
        readAttachmentText = ::readAttachmentText,
        uriString = uriString,
        scopeKind = scope,
        importOwner = importOwner
    )

    fun deleteWorldBook(worldBookId: String) =
        deleteWorldBookFor(knowledgeLibraryPort, uiStateStore, viewModelScope, { uiStateStore.current.knowledgeLibrarySnapshot() }, worldBookId)

    fun createKnowledgeBase(name: String, description: String = "") =
        createKnowledgeBaseFor(knowledgeLibraryPort, uiStateStore, viewModelScope, { uiStateStore.current.knowledgeLibrarySnapshot() }, name, description)

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
        deleteKnowledgeBaseFor(knowledgeLibraryPort, uiStateStore, viewModelScope, { uiStateStore.current.knowledgeLibrarySnapshot() }, knowledgeBaseId)

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

    fun updateCloudApiEnabled(enabled: Boolean) = applyCloudApiEnabled(cloudModelConfigPort, enabled)
    fun updateCloudApiFormat(value: String) = applyCloudApiFormat(cloudModelConfigPort, value)
    fun updateCloudImageApiFormat(value: String) = applyCloudImageApiFormat(cloudModelConfigPort, value)
    fun applyCloudProviderPreset(value: String) = applyCloudProviderPreset(cloudModelConfigPort, value)
    fun beginAddCloudModel(kind: String) = applyBeginAddCloudModel(cloudModelConfigPort, kind)
    fun editCloudModel(modelId: String) = applyEditCloudModel(cloudModelConfigPort, modelId)
    fun updateCloudBaseUrl(value: String) = applyCloudBaseUrl(cloudModelConfigPort, value)
    fun updateCloudApiKey(value: String) = applyCloudApiKey(cloudModelConfigPort, value)
    fun updateCloudChatModel(value: String) = applyCloudChatModel(cloudModelConfigPort, value)
    fun updateCloudSupportsVision(value: Boolean) = applyCloudSupportsVision(cloudModelConfigPort, value)

    /**
     * Persists a GGUF backend choice against the target model before it is loaded.
     * A choice made for another model must never leak through the global parameter
     * object; loading the model later reads this profile and applies it to native.
     */
    fun updateModelBackendPreference(modelId: String, backendId: String) {
        val target = uiStateStore.current.models.firstOrNull { it.id == modelId } ?: return
        val family = chatBackendFamilyForRuntime(target.runtime.storageValue)
        val current = loadModelGenerationParams(
            backend = ChatBackend.LOCAL,
            modelId = modelId,
            defaults = uiStateStore.current.params
        )
        val updated = current.withChatBackend(family, backendId)
        persistModelGenerationParams(ChatBackend.LOCAL, modelId, updated)
        if (uiStateStore.current.loadedModelId == modelId) {
            // Backend selection is load-bound for llama.cpp, MNN, LiteRT-LM,
            // and QAIRT. Updating only the hot generation parameters leaves
            // the old native session running and makes the chip lie about the
            // selected transport. Persist the model-scoped profile, then use
            // the normal transactional loader so the old session is stopped,
            // the requested backend is initialized, and failures can restore
            // the previous stable model.
            updateParams(updated)
            directParameterStageJob?.cancel()
            uiStateStore.update {
                it.copy(
                    statusMessage = "正在重新加载 ${target.displayName}（${backendLabel(family, backendId)}）…"
                )
            }
            loadModel(target)
        } else {
            uiStateStore.update { state ->
                state.copy(
                    statusMessage = "已保存 ${target.displayName} 的 " +
                        "${backendLabel(family, backendId)} 设置；下次加载该模型时生效。"
                )
            }
        }
    }

    /** Read a target model's persisted generation profile for the model selector UI. */
    /** R3-S38：云模型配置端口具名适配器（容器工厂装配，出清 50 行匿名实现与 11 处 this@VM）。 */
    private val cloudModelConfigPort: CloudModelConfigPort =
        container.createCloudModelConfigPort(
            store = uiStateStore,
            loadModelGenerationParams = { backend, modelId, defaults ->
                loadModelGenerationParams(backend, modelId, defaults)
            },
            persistChatSessions = { sessions, binding ->
                persistChatSessions(sessions, binding)
            }
        )


/** Delegates to [cloudChatModelGenerationParams]. Signature kept verbatim. */
    fun generationParamsForChatModel(modelId: String): GenerationParams = cloudChatModelGenerationParams(port = cloudModelConfigPort, modelId = modelId)

    fun updateCloudSupportsTools(value: Boolean) = applyCloudSupportsTools(port = cloudModelConfigPort, value = value)
    fun updateCloudImageModel(value: String) = applyCloudImageModel(port = cloudModelConfigPort, value = value)
    fun updateCloudResponsesReasoning(value: Boolean) = applyCloudResponsesReasoning(port = cloudModelConfigPort, value = value)
    fun updateCloudImageSize(value: String) = applyCloudImageSize(port = cloudModelConfigPort, value = value)
    fun updateCloudImageEndpointPath(value: String) = applyCloudImageEndpointPath(port = cloudModelConfigPort, value = value)
    fun updateCloudDisplayName(value: String) = applyCloudDisplayName(port = cloudModelConfigPort, value = value)
    fun saveCloudApiConfig() = persistCloudApiConfig(port = cloudModelConfigPort)
    fun saveCloudChatModel() = saveCloudChatModelConfig(port = cloudModelConfigPort)
    fun saveCloudImageModel() = saveCloudImageModelConfig(port = cloudModelConfigPort)
    fun testCloudApiConfig() = runCloudApiConfigTest(port = cloudModelConfigPort, scope = viewModelScope)

    fun selectCloudImageModel(modelId: String) {
        if (rejectImageModelSwitchWhileGenerationIsActive()) return
        invalidatePendingChatImagePreparation()
        selectCloudImageModelFor(cloudModelConfigPort, modelId)
    }


    fun updateAgentPreference(preference: UserPreference) {
        val state = uiStateStore.current
        val recommendation = runCatching { buildRecommendation(preference) }.getOrNull()
        val updatedParams = recommendation?.tuningPlan?.applyTo(state.params)
        val paramsChanged = updatedParams != null && updatedParams != state.params
        // Agent presets are user-selected runtime changes. Route them through
        // the same transactional entry point as manual edits so load-bound
        // values establish reloadRequired + pending/LKG state instead of only
        // changing the persisted/UI projection.
        if (updatedParams != null) updateParams(updatedParams)
        uiStateStore.update { current ->
            current.copy(
                preference = preference,
                agentRecommendation = recommendation,
                rollbackParams = if (paramsChanged) {
                    state.rollbackParams ?: state.params
                } else {
                    current.rollbackParams
                },
                statusMessage = when {
                    updatedParams == null -> recommendation?.explanation ?: current.statusMessage
                    current.reloadRequired ->
                        "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}；需要重新加载，当前模型继续使用原配置。"
                    paramsChanged && current.loadedModelId == null ->
                        "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}；已保存，下次加载模型时生效。"
                    else ->
                        "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}，n_predict=${updatedParams.nPredict}。"
                }
            )
        }
    }

    fun refreshLocalModels() =
        modelImportAndDownloadCoordinator.refreshLocalModels()

    private suspend fun reconcileModelIdentities() =
        reconcileModelIdentitiesFor(
            chatSessionPersistenceMutex = chatSessionPersistenceMutex,
            modelIdentityReferenceMigration = modelIdentityReferenceMigration,
            store = uiStateStore,
            getDurableChatSessions = { durableChatSessions },
            setDurableChatSessions = { durableChatSessions = it }
        )

    fun importModel(uri: Uri) = importModel(listOf(uri))

    fun importModel(uris: List<Uri>, textOnly: Boolean = false, persistentAccess: Boolean = false) =
        modelImportAndDownloadCoordinator.importModel(uris, textOnly, persistentAccess)

    fun importModelDirectory(uri: Uri, textOnly: Boolean = false, persistentAccess: Boolean = false) =
        modelImportAndDownloadCoordinator.importModelDirectory(uri, textOnly, persistentAccess)

    fun pauseManagedImport(requestId: String) =
        modelImportAndDownloadCoordinator.pauseManagedImport(requestId)

    fun resumeManagedImport(requestId: String) =
        modelImportAndDownloadCoordinator.resumeManagedImport(requestId)

    fun fetchRemoteFiles() =
        fetchRemoteFilesFor(
            viewModelScope,
            uiStateStore,
            modelScopeClient,
            ::busy,
            ::fail,
            uiStateStore.current.repoInput
        )

    fun fetchRecommendedFiles(model: ModelScopeRecommendedModel) =
        fetchRecommendedFilesFor(
            viewModelScope,
            uiStateStore,
            modelScopeClient,
            ::busy,
            ::fail,
            ::showStatus,
            currentDeviceProfile(),
            model
        )

    fun downloadRecommended(model: ModelScopeRecommendedModel) =
        downloadRecommended(model, useAfterDownload = false)

    fun downloadRecommended(model: ModelScopeRecommendedModel, useAfterDownload: Boolean) =
        downloadRecommendedFor(
            model,
            useAfterDownload,
            ::fail,
            ::enqueueManagedDownload
        )

    fun attachVisionProjector(modelId: String, uri: Uri) =
        modelImportAndDownloadCoordinator.attachVisionProjector(
            modelId,
            uri,
            uiStateStore.current.loadedModelId,
            uiStateStore.current.selectedChatBackend
        )









    fun searchHubModels(reset: Boolean = true) =
        searchHubModelsFor(
            viewModelScope,
            uiStateStore,
            modelScopeClient,
            ::busy,
            ::fail,
            uiStateStore.current.hubQuery,
            uiStateStore.current.hubPage,
            uiStateStore.current.hubModels,
            reset
        )

    fun fetchHubModelFiles(model: ModelScopeHubModel) =
        fetchHubModelFilesFor(
            viewModelScope,
            uiStateStore,
            modelScopeClient,
            ::busy,
            ::fail,
            model
        )

    fun openModelScopePage(url: String) =
        openModelScopePageFor(
            getApplication(),
            ::fail,
            url
        )

    fun download(remote: RemoteModelFile) =
        modelImportAndDownloadCoordinator.download(remote, uiStateStore.current.loadedModelId)

    fun pauseManagedDownloads() =
        modelImportAndDownloadCoordinator.pauseManagedDownloads()

    fun resumeManagedDownloads() =
        modelImportAndDownloadCoordinator.resumeManagedDownloads()

    private fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) =
        modelImportAndDownloadCoordinator.enqueueManagedDownload(request, useAfterDownload)

    private fun launchModelOperation(message: String, operation: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            recoverModelOperation(operation = { operation() }, onFailure = { error ->
                Log.e("McaModelOperation", message, error)
                uiStateStore.update { state -> state.copy(busy = false,
                    statusMessage = "$message：${error.message.orEmpty()}。请检查模型文件和存储空间后重试。",
                    engineLifecycle = if (state.engineLifecycle == AgentEngineLifecycle.LOADING) {
                        if (state.stats.loaded) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR
                    } else state.engineLifecycle) }
            })
        }


    /** Delegates to [selectChatModelFor]. Signature kept verbatim. */
    fun selectChatModel(choiceId: String) =
        selectChatModelFor(uiStateStore, ::loadModel, { selectCloudChatModel(it) }, choiceId)

    /** Delegates to [selectCloudChatModelFor]. Signature kept verbatim. */
    private fun selectCloudChatModel(modelId: String?) =
        selectCloudChatModelFor(
            uiStateStore,
            cloudApiStore,
            engine,
            viewModelScope,
            getApplication(),
            modelGenerationProfileStore,
            { persistChatSessions(it) },
            modelId
        )


    /** Delegates to [runLoadModelBody]. Signature kept verbatim. */
    private suspend fun CoroutineScope.loadModelBody(requestedModel: ModelManifest) =
        runLoadModelBody(
            requestedModel,
            uiStateStore,
            container.runtimeAdmission,
            pendingRuntimeRecoveries,
            engine,
            modelStore,
            runtimeProfileStore,
            modelGenerationProfileStore,
            cloudApiStore,
            tuningOwner,
            advisor,
            deviceProfileReader,
            managedRuntimeReadinessRefreshGate,
            managedRuntimeReadinessPort,
            getApplication(),
            installationScopeId,
            cancelGeneration = { cancelGenerationJob() },
            persistChatSessions = { persistChatSessions(it) }
        )
    fun loadModel(requestedModel: ModelManifest) {
        launchModelOperation("模型操作未完成") {
            loadModelBody(requestedModel)
        }
    }

    fun verifyModel(model: ModelManifest) {
        verifyModelFor(
            store = uiStateStore,
            application = getApplication(),
            modelStore = modelStore,
            managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
            publishManagedChatCatalogAfterValidation = ::publishManagedChatCatalogAfterValidation,
            busy = ::busy,
            launchModelOperation = ::launchModelOperation,
            model = model
        )
    }


    /** Delegates to [clearNativeRuntimeSessionStateFor]. Signature kept verbatim. */
    private fun clearNativeRuntimeSessionState(
        lifecycle: AgentEngineLifecycle,
        statusMessage: String,
        busy: Boolean = false
    ) = clearNativeRuntimeSessionStateFor(
        uiStateStore, container.runtimeAdmission, engine, lifecycle, statusMessage, busy
    )

    /** Delegates to [restoreLoadedRuntimeSnapshotFor]. Signature kept verbatim. */
    private suspend fun restoreLoadedRuntimeSnapshot(
        snapshot: LoadedRuntimeSnapshot,
        statusMessage: String
    ): Boolean = restoreLoadedRuntimeSnapshotFor(
        uiStateStore,
        container.runtimeAdmission,
        engine,
        runtimeProfileStore,
        modelStore,
        tuningOwner,
        managedRuntimeReadinessRefreshGate,
        snapshot,
        statusMessage
    )

    /** Delegates to [unloadModelFor]. Signature kept verbatim. */
    fun unloadModel(model: ModelManifest) = unloadModelFor(
        viewModelScope,
        uiStateStore,
        container.runtimeAdmission,
        engine,
        runtimeProfileStore,
        model
    ) { cancelGenerationJob() }

    /** Delegates to [deleteModelFor]. Signature kept verbatim. */
    fun deleteModel(model: ModelManifest) = deleteModelFor(
        viewModelScope,
        uiStateStore,
        container.runtimeAdmission,
        engine,
        runtimeProfileStore,
        modelStore,
        managedRuntimeReadinessRefreshGate,
        getApplication(),
        model
    ) { cancelGenerationJob() }

    /** Delegates to [clearPendingRuntimeTransactionForLifecycleFor]. Signature kept verbatim. */
    private suspend fun clearPendingRuntimeTransactionForLifecycle(
        reason: String,
        identity: ModelRuntimeIdentity? = activeRuntimeIdentity
    ) = clearPendingRuntimeTransactionForLifecycleFor(
        runtimeProfileStore, container.runtimeAdmission, reason, identity
    )

    fun sendMessage() {
        val state = uiStateStore.current
        if (rejectWhileConversationMutationInProgress()) return
        if (assistantImageContinuationCoordinator.hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
            showStatus("请先确认、取消或等待上一张角色请求的图片完成。")
            return
        }
        val preparedInput = state.prepareChatInput()
        if ((preparedInput.text.isBlank() && preparedInput.imageAttachments.isEmpty()) || state.isGenerating) return
        if (preparedInput.imageAttachments.size > 1) {
            if (!chatImageSubmissionPreflight.compareAndSet(false, true)) return
            viewModelScope.launch(Dispatchers.IO) {
                // The composer already performs an asynchronous dedup pass, but keep a final
                // model-boundary check here as well. This protects message persistence and the
                // actual runner input if a picker/import callback races that UI preflight.
                val uniqueAttachments = preparedInput.imageAttachments
                    .deduplicatedVisionAttachmentsForSend(getApplication<Application>())
                withContext(Dispatchers.Main.immediate) {
                    val current = uiStateStore.current
                    if (current.input != state.input || current.isGenerating) return@withContext
                    sendPreparedMessageAsync(current, preparedInput.copy(imageAttachments = uniqueAttachments))
                }
            }.invokeOnCompletion {
                chatImageSubmissionPreflight.set(false)
            }
            return
        }
        sendPreparedMessage(state, preparedInput)
    }

    private fun sendPreparedMessage(
        state: MainUiState,
        preparedInput: PreparedChatInput
    ) {
        if (!chatImageSubmissionPreflight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                sendPreparedMessageAsync(state, preparedInput)
            } finally {
                chatImageSubmissionPreflight.set(false)
            }
        }
    }

    private suspend fun sendPreparedMessageAsync(
        state: MainUiState,
        preparedInput: PreparedChatInput
    ) {
        if (rejectWhileConversationMutationInProgress()) return
        if (!state.canWriteToActiveAssistantSession()) {
            showStatus("此对话的角色归属不明确或角色已删除，请在历史中查看并手动分支。")
            return
        }
        if (assistantImageContinuationCoordinator.hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
            showStatus("请先确认、取消或等待上一张角色请求的图片完成。")
            return
        }
        if ((preparedInput.text.isBlank() && preparedInput.imageAttachments.isEmpty()) || state.isGenerating) return
        // Reject unsupported image input before creating/persisting a chat turn.
        // Previously the request was first saved and marked as generating, then
        // failed deeper in startGeneration. Besides confusing users, that sent
        // stale/unsupported image state closer to native runtimes and made a
        // repeated tap look like a duplicate-image submission.
        if (preparedInput.imageAttachments.isNotEmpty()) {
            if (state.selectedChatBackend == ChatBackend.CLOUD) {
                val cloud = state.selectedChatCloudConfig()?.normalized()
                val unavailable = when {
                    cloud == null || !cloud.configured -> "请先配置并选择云端聊天模型，再发送图片。"
                    !cloud.supportsVision -> "当前云端模型未开启图片输入。请在模型管理中为该模型启用图片输入后重试。"
                    else -> null
                }
                if (unavailable != null) {
                    showStatus(unavailable)
                    return
                }
            } else if (!isLocalVisionRunnerAvailable(visionAttachmentPort)) {
                uiStateStore.update {
                    it.copy(statusMessage = localVisionUnavailableReason(state, runCatching { engine.nativeStatsJson() }.getOrNull()))
                }
                return
            }
        }
        if (hasExplicitChatImageCommand(preparedInput.text)) {
            if (preparedInput.imageAttachments.isNotEmpty() ||
                preparedInput.text.contains(FILE_ATTACHMENT_MARKER)
            ) {
                uiStateStore.update {
                    it.copy(statusMessage = "聊天生图暂不支持把已附加的图片或文件作为参考。请先移除附件，再发送 /image 图片描述。")
                }
                return
            }
            val imageIntent = parseExplicitChatImageIntent(preparedInput.text)
            if (imageIntent == null) {
                showStatus("请在 /image 后输入图片描述，再发送。")
                return
            }
            sendChatImageRequestWithPromptBridge(
                visibleUserText = preparedInput.text,
                prompt = imageIntent.sourceText,
                requestedOutputCount = classifyChatImageIntent(preparedInput.text).requestedOutputCount
            )
            return
        }
        if (preparedInput.imageAttachments.isEmpty() &&
            !preparedInput.text.contains(FILE_ATTACHMENT_MARKER)
        ) {
            val lastImageRequest = state.messages.asReversed().firstNotNullOfOrNull {
                it.generatedImageRequest
            }
            val imageDecision = classifyChatImageIntent(
                preparedInput.text,
                ChatImageIntentContext(
                    sessionId = state.activeChatSessionId,
                    lastImageRequestId = lastImageRequest?.id,
                    lastImageSessionId = state.activeChatSessionId,
                    recentText = state.messages.takeLast(8).map { it.content },
                    characterName = state.activeAssistantSnapshot()?.name ?: state.selectedAssistant()?.name
                )
            )
            when (imageDecision.route) {
                ChatImageIntentRoute.GENERATE -> {
                    imageDecision.intent?.let { intent ->
                        sendChatImageRequestWithPromptBridge(
                            visibleUserText = preparedInput.text,
                            prompt = intent.sourceText,
                            requestedOutputCount = imageDecision.requestedOutputCount
                        )
                        return
                    }
                }
                ChatImageIntentRoute.AMBIGUOUS -> {
                    val actionId = UUID.randomUUID().toString()
                    container.runtimeAdmission.pendingChatImageAction = PendingChatImageAction(
                        id = actionId,
                        kind = com.muyuchat.feature.chat.ChatImageActionKind.AMBIGUOUS,
                        visibleUserText = preparedInput.text,
                        requestedOutputCount = imageDecision.requestedOutputCount,
                        stillOwned = {
                            val current = uiStateStore.current
                            current.activeChatSessionId == state.activeChatSessionId &&
                                current.messages == state.messages &&
                                current.input == state.input &&
                                current.selectedChatBackend == state.selectedChatBackend &&
                                current.selectedImageBackend == state.selectedImageBackend
                        }
                    )
                    uiStateStore.update {
                        it.copy(
                            pendingChatImageActionDraft = com.muyuchat.feature.chat.ChatImageActionDraftUi(
                                id = actionId,
                                sourceText = preparedInput.text,
                                kind = com.muyuchat.feature.chat.ChatImageActionKind.AMBIGUOUS,
                                reason = "这句话可能是图片请求。请选择本轮操作，也可先编辑描述。"
                            ),
                            statusMessage = null
                        )
                    }
                    return
                }
                ChatImageIntentRoute.CHAT -> Unit
            }
        }
        if (activeImageGenerationBackend == ImageBackend.LOCAL &&
            activeImageGenerationJobId != null &&
            state.selectedChatBackend == ChatBackend.LOCAL
        ) {
            uiStateStore.update {
                it.copy(statusMessage = "本地生图正在运行，请等图片完成或停止后再启动本地聊天，避免设备内存冲突。")
            }
            return
        }
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val conversationParams = assistantSnapshot?.applyTo(state.params) ?: state.params
        val conversationAssistantId = assistantSnapshot?.assistantId ?: state.selectedAssistantId
        val characterMemoryContext = if (state.assistants.any {
                it.id == conversationAssistantId && it.memoryEnabled
            }) {
            withContext(Dispatchers.IO) {
                buildCharacterMemoryContext(
                    assistantId = conversationAssistantId,
                    memories = chatSessionStore.loadMemories(conversationAssistantId),
                    pendingTurns = chatSessionStore.loadMemoryTurns(conversationAssistantId),
                    currentSessionId = state.activeChatSessionId
                )
            }
        } else ""
        val user = ChatMessage(
            role = Role.USER,
            content = preparedInput.text.ifBlank {
                if (preparedInput.imageAttachments.isNotEmpty()) "请描述这张图片。" else ""
            },
            imageAttachments = preparedInput.imageAttachments
        )
        // Context compression must run before the first admission decision.  The
        // old ordering rejected an over-budget request (or let a trimmed
        // request proceed without using that trimmed list) before the automatic
        // compression policy could fold historical turns.  Build a logical
        // request first, compress it in memory, then rebuild runtime context
        // and perform the final safety admission.  The persisted conversation
        // remains untouched; only the request crossing the runtime boundary is
        // compressed/trimmed.
        val preflightMessages = state.messages + user
        val preflightContext = withContext(Dispatchers.IO) { chatContextComposer.compose(
            messages = preflightMessages,
            params = conversationParams,
            assistantId = conversationAssistantId,
            chatSessionId = state.activeChatSessionId,
            knowledgeBaseIds = state.selectedKnowledgeBaseIds,
            fileContextEnabled = assistantSnapshot?.fileContextEnabled
                ?: state.selectedAssistant()?.fileContextEnabled
                ?: true
        ) }
        val preflightRequest = ChatRequest(
            messages = preflightMessages,
            params = conversationParams,
            protectedMessageIds = preflightMessages.filter(ChatMessage::pinned).mapTo(hashSetOf()) { it.id },
            runtimeSystemContext = listOf(preflightContext.runtimeSystemContext, characterMemoryContext)
                .filter(String::isNotBlank).joinToString("\n\n")
        )
        val initialAdmission = localContextWindowAdmission(preflightRequest)
        val preflightCompression = compressChatRequestContext(
            request = preflightRequest,
            settings = ContextCompressionSettings(
                threshold = contextCompressionCoordinator.loadContextCompressionThreshold(),
                keepRecentMessages = 8
            ),
            // Force a compression attempt when the first safety decision is a
            // rejection.  This matters when the conservative token estimate is
            // below the configured threshold but runtime bytes/code points are
            // already over the window.
            trigger = if (initialAdmission.isAccepted) {
                ContextCompressionTrigger.AUTOMATIC
            } else {
                ContextCompressionTrigger.MANUAL
            }
        )
        val compressedPreflightMessages = preflightCompression.request.messages
        val rebuiltPreflightContext = withContext(Dispatchers.IO) { chatContextComposer.compose(
            messages = compressedPreflightMessages,
            params = conversationParams,
            assistantId = conversationAssistantId,
            chatSessionId = state.activeChatSessionId,
            knowledgeBaseIds = state.selectedKnowledgeBaseIds,
            fileContextEnabled = assistantSnapshot?.fileContextEnabled
                ?: state.selectedAssistant()?.fileContextEnabled
                ?: true
        ) }
        val finalPreflightRequest = preflightCompression.request.copy(
            runtimeSystemContext = listOf(rebuiltPreflightContext.runtimeSystemContext, characterMemoryContext)
                .filter(String::isNotBlank).joinToString("\n\n")
        )
        val admission = localContextWindowAdmission(finalPreflightRequest)
        if (!admission.isAccepted) {
            uiStateStore.update {
                it.copy(
                    statusMessage = if (state.input.contains(FILE_ATTACHMENT_MARKER)) {
                        "当前文件内容超过模型上下文，消息未发送。请将长文件导入知识库后再提问。"
                    } else {
                        admission.userMessage
                            ?: "当前输入无法放入本机安全上下文，请缩短后重试。"
                    }
                )
            }
            return
        }
        val currentOwner = uiStateStore.current
        if (currentOwner.activeChatSessionId != state.activeChatSessionId ||
            !currentOwner.canWriteToActiveAssistantSession() ||
            currentOwner.input != state.input || currentOwner.messages != state.messages ||
            currentOwner.selectedAssistantId != state.selectedAssistantId ||
            currentOwner.assistants != state.assistants ||
            currentOwner.assistantMemories != state.assistantMemories ||
            currentOwner.selectedKnowledgeBaseIds != state.selectedKnowledgeBaseIds ||
            currentOwner.worldBooks != state.worldBooks || currentOwner.params != state.params ||
            currentOwner.selectedChatBackend != state.selectedChatBackend ||
            currentOwner.loadedModelId != state.loadedModelId ||
            currentOwner.selectedCloudChatModelId != state.selectedCloudChatModelId || currentOwner.isGenerating
        ) return
        discardPendingAssistantOutput()
        val assistant = ChatMessage(Role.ASSISTANT, "")
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        var chatSessionIdForKnowledgeBinding: String? = null
        var selectedKnowledgeBaseIdsForBinding: Set<String> = emptySet()
        uiStateStore.update {
            val sessionId = it.activeChatSessionId ?: UUID.randomUUID().toString()
            chatSessionIdForKnowledgeBinding = sessionId
            selectedKnowledgeBaseIdsForBinding = it.selectedKnowledgeBaseIds
            val messages = it.messages + user + assistant
            sessionsToPersist = it.chatSessions.upsertSession(
                sessionId = sessionId,
                messages = messages,
                assistantId = conversationAssistantId,
                assistantSnapshot = assistantSnapshot,
                modelMode = it.selectedChatBackend.bindingValue(),
                modelId = it.currentChatModelId()
            )
            it.copy(
                input = "",
                messages = messages,
                activeChatSessionId = sessionId,
                chatSessions = sessionsToPersist,
                isGenerating = true,
                promptContextUsage = contextCompressionCoordinator.promptContextUsageFor(
                    plan = rebuiltPreflightContext,
                    admission = admission,
                    params = conversationParams
                ),
                statusMessage = null
            ).withGenerationProgressCleared()
        }
        persistChatSessions(
            sessions = sessionsToPersist,
            knowledgeBinding = chatSessionIdForKnowledgeBinding?.let { sessionId ->
                sessionId to selectedKnowledgeBaseIdsForBinding
            }
        )

        // Retain originals for source IDs while carrying the exact admitted preflight snapshot.
        startGeneration(
            preflightMessages,
            preflightCompression = preflightCompression.copy(request = finalPreflightRequest)
        )
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
    private fun sendChatImageRequestWithPromptBridge(
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
            sessionsToPersist = it.chatSessions.upsertSession(
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
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            showStatus = ::showStatus,
            isChatImageRequestActive = ::isChatImageRequestActive,
            pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
            persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) },
            index = index
        )
    }

    fun deleteLastConversationTurn() {
        if (rejectWhileConversationMutationInProgress()) return
        deleteLastConversationTurnFor(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            showStatus = ::showStatus,
            isChatImageRequestActive = ::isChatImageRequestActive,
            pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
            persistConversationMutation = { sessions, rollback, _ ->
                persistConversationMutation(
                    sessions = sessions,
                    rollback = rollback,
                    preserveReusableNativePrefix = true
                )
            }
        )
    }


    private fun reserveUiGenerationStart(): UiGenerationReservation? =
        chatRuns.reserveStartAndCancelSuperseded()

    private fun cancelGenerationJob(): UiGenerationCancellation =
        chatRuns.cancelCurrentAndCancelOwner()

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
        if (rejectWhileConversationMutationInProgress()) return
        newChatForAssistant(uiStateStore.current.selectedAssistant())
    }

    private fun newChatForAssistant(
        assistant: AssistantRecord?,
        bindDefaultModel: Boolean = false
    ): Boolean {
        if (rejectWhileConversationMutationInProgress()) return false
        val created = newChatForAssistantFor(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            showStatus = ::showStatus,
            invalidatePendingChatImagePreparation = ::invalidatePendingChatImagePreparation,
            cancelActiveWebSearch = { activeWebSearchDeferred?.cancel() },
            bumpContentImportSelectionRevision = { contentImportSelectionRevision.incrementAndGet() },
            markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
            persistChatSessions = { persistChatSessions(it) },
            assistant = assistant,
            bindDefaultModel = bindDefaultModel
        )
        if (created) {
            markLocalConversationContextInvalid()
        }
        return created
    }

    fun selectChatSession(sessionId: String) {
        selectChatSession(port = selectChatSessionPort, sessionId = sessionId)
    }

    /** R3-S39：选择聊天会话端口具名适配器（容器工厂装配，出清 84 行匿名实现与 18 处 this@VM）。 */
    private val selectChatSessionPort: SelectChatSessionPort =
        container.createSelectChatSessionPort(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            invalidatePendingChatImagePreparation = { invalidatePendingChatImagePreparation() },
            cancelActiveWebSearch = { activeWebSearchDeferred?.cancel() },
            bumpContentImportSelectionRevision = { contentImportSelectionRevision.incrementAndGet() },
            loadModelGenerationParams = { backend, modelId, defaults ->
                loadModelGenerationParams(backend, modelId, defaults)
            },
            markLocalConversationContextInvalid = { markLocalConversationContextInvalid() },
            continueAssistantImageTurn = { continueAssistantImageTurn(it) }
        )

    fun branchMixedChatSession(sessionId: String, assistantId: String) {
        branchMixedChatSessionFor(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            persistChatSessions = { persistChatSessions(it) },
            selectChatSession = ::selectChatSession,
            sessionId = sessionId,
            assistantId = assistantId
        )
    }

    fun deleteChatSession(sessionId: String) {
        if (rejectWhileConversationMutationInProgress()) return
        deleteChatSessionFor(
            store = uiStateStore,
            scope = viewModelScope,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            showStatus = ::showStatus,
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            manualContextCompressionRequests = manualContextCompressionRequests,
            pendingAssistantImageToolTurns = container.runtimeAdmission.pendingAssistantImageToolTurns,
            queueWorldBookCleanup = ::queueWorldBookCleanup,
            persistConversationMutation = { remaining, rollback ->
                persistConversationMutation(remaining, rollback)
            },
            chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
            cleanupUnusedChatBackgrounds = { chatAppearanceCoordinator.mutex.withLock { chatAppearanceCoordinator.cleanupUnusedChatBackgrounds() } },
            sessionId = sessionId
        )
    }

    fun renameChatSession(sessionId: String, title: String) {
        renameChatSessionFor(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
            showStatus = ::showStatus,
            persistChatSessions = { persistChatSessions(it) },
            sessionId = sessionId,
            title = title
        )
    }

    fun toggleChatSessionPinned(sessionId: String) {
        toggleChatSessionPinnedFor(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
            persistChatSessions = { persistChatSessions(it) },
            sessionId = sessionId
        )
    }

    fun clearChatHistory() {
        if (rejectWhileConversationMutationInProgress()) return
        clearChatHistoryFor(
            store = uiStateStore,
            scope = viewModelScope,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            showStatus = ::showStatus,
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecutionIn(uiStateStore.current, it) },
            manualContextCompressionRequests = manualContextCompressionRequests,
            queueWorldBookCleanup = ::queueWorldBookCleanup,
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
            // R3/S22: 清会话写入经 UiStateStore。
            uiStateStore.update {
                it.afterClearChatGenerationStopped(engine.stats.value).copy(contextCompressionPending = false)
            }
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
        if (adaptiveTuningJob?.isActive == true) {
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

    fun refreshLogs() {
        // R3/C1: 读写体移入 DiagnosticsOperations（零行为：同一 store 流与 CAS 语义）。
        diagnosticsOperations.refreshLogs()
    }

    fun refreshDiagnostics() {
        // R3/C1: 读写体移入 DiagnosticsOperations（零行为：同一 store 流与 CAS 语义）。
        diagnosticsOperations.refreshDiagnostics()
    }

    fun exportDiagnosticReport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val report = diagnosticsOperations.buildDiagnosticReport()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(report.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置。")
                report
            }.onSuccess { report ->
                uiStateStore.update {
                    it.copy(
                        diagnosticReport = report,
                        statusMessage = "诊断报告已导出"
                    )
                }
            }.onFailure { error ->
                fail("诊断报告导出失败：${error.message}")
            }
        }
    }

    fun chatSessionExportFileName(sessionId: String): String =
        chatSessionExportFileNameFor(uiStateStore, sessionId)

    fun exportChatSession(sessionId: String, uri: Uri) {
        exportChatSessionFor(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication(),
            fail = ::fail,
            sessionId = sessionId,
            uri = uri
        )
    }

    fun scanAgent() = scanAgentFor(
        store = uiStateStore,
        scope = viewModelScope,
        tuningOwner = tuningOwner,
        modelScopeClient = modelScopeClient,
        agentLogger = agentLogger,
        currentDeviceProfile = ::currentDeviceProfile,
        buildRecommendation = ::buildRecommendation,
        activeRuntimeIdentity = { activeRuntimeIdentity },
        setActiveAdaptiveRecommendation = { activeAdaptiveRecommendation = it },
        busy = ::busy,
        fail = ::fail
    )

    fun applyAgentRecommendation() = applyAgentRecommendationFor(
        store = uiStateStore,
        application = getApplication(),
        agentLogger = agentLogger,
        activeAdaptiveRecommendation = activeAdaptiveRecommendation,
        currentDeviceProfile = ::currentDeviceProfile,
        persistModelGenerationParams = ::persistModelGenerationParams
    )

    fun rollbackAgentParams() = rollbackAgentParamsFor(
        store = uiStateStore,
        application = getApplication(),
        persistModelGenerationParams = ::persistModelGenerationParams
    )

    fun loadAgentRecommendedModel() = loadAgentRecommendedModelFor(
        store = uiStateStore,
        loadModel = ::loadModel
    )

    fun runAgentBenchmark() = runAgentBenchmarkFor(
        store = uiStateStore,
        scope = viewModelScope,
        engine = engine,
        benchmarkRunner = benchmarkRunner,
        benchmarkHistoryLogger = benchmarkHistoryLogger,
        currentDeviceProfile = ::currentDeviceProfile,
        busy = ::busy,
        fail = ::fail
    )

    /** Delegates to [runAgentStabilitySmokeFor]. Signature kept verbatim. */
    fun runAgentStabilitySmoke() =
        runAgentStabilitySmokeFor(
            viewModelScope,
            uiStateStore,
            engine,
            modelStore,
            cloudApiStore,
            managedRuntimeReadinessRefreshGate,
            managedRuntimeReadinessPort,
            deviceProfileReader,
            diagnosticsOperations,
            cancelGeneration = { cancelGenerationJob() },
            busy = { busy(it) },
            loadSmoke = { model, runtime, params -> timedLoadModel(model, runtime, params) },
            generationSmoke = { params -> runLocalGenerationSmoke(params) },
            apiSmoke = { localApiRuntimeOwner.runLoopbackApiStreamSmoke() },
            engineSmoke = { params -> localApiRuntimeOwner.runLoopbackEngineStreamSmoke(params) }
        )

    fun startAgentTuning() =
        agentTuningCoordinator.startAgentTuning(tuningOwner, AgentTuningMode.QUICK, false, uiStateStore)

    fun startAgentTuning(mode: AgentTuningMode, autoApply: Boolean) =
        agentTuningCoordinator.startAgentTuning(tuningOwner, mode, autoApply, uiStateStore)

    fun pauseAgentTuning() =
        agentTuningCoordinator.pauseAgentTuning(runtimeProfileStore, uiStateStore, viewModelScope)

    fun resumeAgentTuning() =
        agentTuningCoordinator.resumeAgentTuning(runtimeProfileStore, uiStateStore, viewModelScope)

    fun cancelAgentTuning() =
        agentTuningCoordinator.cancelAgentTuning(localApiControlPlaneCoordinator::cancelPersistedTuningJob, uiStateStore, viewModelScope)

    fun queryAgentTuningJob() =
        agentTuningCoordinator.queryAgentTuningJob(runtimeProfileStore, uiStateStore, viewModelScope, activeRuntimeIdentity)

    fun applyPendingAgentProfile() =
        agentTuningCoordinator.applyPendingAgentProfile(
            runtimeProfileStore = runtimeProfileStore,
            scope = viewModelScope,
            activeRuntimeIdentity = activeRuntimeIdentity,
            showStatus = ::showStatus,
            applyPendingRuntimeTransaction = ::applyPendingRuntimeTransaction
        )

    fun discardPendingAgentProfile() =
        agentTuningCoordinator.discardPendingAgentProfile(
            runtimeProfileStore = runtimeProfileStore,
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication(),
            engine = engine,
            activeRuntimeIdentity = activeRuntimeIdentity,
            getPendingProfileTransactionId = { pendingProfileTransactionId },
            setPendingProfileTransactionId = { pendingProfileTransactionId = it }
        )

    fun rollbackAgentProfile() =
        agentTuningCoordinator.rollbackAgentProfile(
            scope = viewModelScope,
            activeRuntimeIdentity = activeRuntimeIdentity,
            activeModelForRuntimeProfile = activeModelForRuntimeProfile,
            rollbackCommittedRuntimeProfile = ::rollbackCommittedRuntimeProfile
        )

    fun runAgentQuickDebug() =
        agentTuningCoordinator.startAgentTuning(tuningOwner, AgentTuningMode.QUICK, false, uiStateStore)

    fun runAgentStandardDebug() =
        agentTuningCoordinator.startAgentTuning(tuningOwner, AgentTuningMode.STANDARD, false, uiStateStore)

    fun runAgentDeepDebug() =
        agentTuningCoordinator.startAgentTuning(tuningOwner, AgentTuningMode.DEEP, false, uiStateStore)

    fun runAgentPowerDebug() =
        agentTuningCoordinator.startAgentTuning(tuningOwner, AgentTuningMode.POWER_SAVE, false, uiStateStore)

    private val runtimeProfileTransactionPort: RuntimeProfileTransactionPort =
        container.createRuntimeProfileTransactionPort(
            store = uiStateStore,
            engine = engine,
            tuningOwner = tuningOwner,
            showStatusAction = ::showStatus
        )

    private suspend fun applyPendingRuntimeTransaction(pending: PendingRuntimeProfileTransaction) =
        applyPendingRuntimeTransaction(port = runtimeProfileTransactionPort, pending = pending)

    private suspend fun rollbackCommittedRuntimeProfile(
        identity: ModelRuntimeIdentity,
        model: ModelManifest
    ) = rollbackCommittedRuntimeProfile(port = runtimeProfileTransactionPort, identity = identity, model = model)

    fun showAgentDebugExplanation() =
        uiStateStore.update {
            it.copy(
                statusMessage = "智能调试由规则包执行参数搜索，当前已加载模型只用于解释调试结果。"
            )
        }

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
    private val imageAssetDeletionPort: ImageAssetDeletionPort =
        container.createImageAssetDeletionPort(
            uiStateStore, viewModelScope, imageUpscaleOwner, imageLibraryStartupReconciliation
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

    private val chatExecutionPort: ChatGenerationExecutionPort =
        container.createChatGenerationExecutionPort(
            store = uiStateStore,
            engine = engine,
            chatContextComposer = chatContextComposer,
            visionAttachmentPort = visionAttachmentPort,
            getActiveWebSearchTaskIdRef = { activeWebSearchTaskId },
            setActiveWebSearchTaskIdRef = { activeWebSearchTaskId = it },
            getForegroundRecoveryJobRef = { foregroundRecoveryJob },
            persistChatSessionsRef = ::persistChatSessions,
            appendAssistantRef = ::appendAssistant,
            flushPendingAssistantOutputRef = ::flushPendingAssistantOutput,
            removePendingAssistantPlaceholderRef = { settlePendingAssistantPlaceholder(port = chatTerminalSettlementPort, generationRunId = it) },
            ensureVisibleAssistantReplyAfterCompletionRef = { settleVisibleAssistantReply(port = chatTerminalSettlementPort, generationRunId = it) },
            applyWebSearchAnswerGuardsToLastAssistantRef = webSearchWorkflowCoordinator::applyWebSearchAnswerGuardsToLastAssistant,
            recordWebSearchCitationAuditRef = webSearchWorkflowCoordinator::recordWebSearchCitationAudit,
            attachWebSearchEvidenceToPendingAssistantRef = webSearchWorkflowCoordinator::attachWebSearchEvidenceToPendingAssistant,
            attachContextAssemblyTraceRef = ::attachContextAssemblyTrace,
            promptContextUsageForRef = contextCompressionCoordinator::promptContextUsageFor,
            appendWebSearchDiagnosticRef = webSearchWorkflowCoordinator::appendDiagnostic,
            recordCompletedCharacterMemoryTurnRef = ::recordCompletedCharacterMemoryTurn,
            summarizeContextForGenerationRef = contextCompressionCoordinator::summarizeContextForGeneration,
            ensureLocalConversationContextInvalidatedRef = ::ensureLocalConversationContextInvalidated,
            loadContextCompressionThresholdRef = contextCompressionCoordinator::loadContextCompressionThreshold,
            handleAssistantImageToolCallsRef = assistantImageContinuationCoordinator::handleAssistantImageToolCalls,
            finishAssistantImageContinuationRef = assistantImageContinuationCoordinator::finishAssistantImageContinuation,
        )

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


    private fun refreshPersistentPrefixCacheSummary() {
        viewModelScope.launch(Dispatchers.IO) {
            val summary = engine.persistentPrefixCacheSummary()
            // R3/S21: 前缀缓存族写入统一经 UiStateStore。
            uiStateStore.update {
                it.copy(
                    persistentPrefixCacheEntryCount = summary.entryCount,
                    persistentPrefixCacheBytes = summary.totalBytes
                )
            }
        }
    }

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
    private fun persistChatSessions(
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
        persistFilesFor(chatSessionStore, uiStateStore, viewModelScope, files)

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



    private fun List<ChatSessionRecord>.upsertSession(
        sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String? = null,
        assistantSnapshot: AssistantConversationSnapshot? = null,
        replaceAssistantSnapshot: Boolean = false,
        modelMode: String? = null,
        modelId: String? = null
    ): List<ChatSessionRecord> = upsertSessionFor(
        state = uiStateStore.current,
        sessionId = sessionId,
        messages = messages,
        assistantId = assistantId,
        assistantSnapshot = assistantSnapshot,
        replaceAssistantSnapshot = replaceAssistantSnapshot,
        modelMode = modelMode,
        modelId = modelId
    )

    /** Secret-free identity for the exact Responses chat endpoint and model configuration. */
    private fun attachContextAssemblyTrace(
        traceJson: String?,
        generationRunId: Long,
        sessionId: String?,
        assistantMessageId: String?
    ) {
        if (traceJson == null || sessionId == null || assistantMessageId == null ||
            !chatRuns.isCurrent(generationRunId)
        ) return
        uiStateStore.update { state ->
            if (!chatRuns.isCurrent(generationRunId) ||
                state.activeChatSessionId != sessionId
            ) return@update state
            val index = state.messages.indexOfLast {
                it.id == assistantMessageId && it.role == Role.ASSISTANT
            }
            val sessionIndex = state.chatSessions.indexOfFirst { it.id == sessionId }
            if (index < 0 || sessionIndex < 0 ||
                state.chatSessions[sessionIndex].messages.none { it.id == assistantMessageId }
            ) return@update state
            val updated = state.messages.mapIndexed { position, message ->
                if (position == index) message.copy(contextAssemblyTraceJson = traceJson) else message
            }
            state.copy(
                messages = updated,
                chatSessions = state.chatSessions.mapIndexed { position, session ->
                    if (position != sessionIndex) session else session.copy(messages = updated)
                }
            )
        }
    }


    /** Delegates to [chatImageOptionsForCurrentSelectionFor]. Signature kept verbatim. */
    internal fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
        chatImageOptionsForCurrentSelectionFor(state)


    private fun readAttachmentText(uri: Uri, maxBytes: Int = 64 * 1024): Pair<String, Boolean> =
        readAttachmentText(getApplication<Application>().contentResolver, uri, maxBytes)





    private val localApiImageGenerationPort: LocalApiImageGenerationPort =
        container.createLocalApiImageGenerationPort(
            store = uiStateStore,
            getActiveLocalApiImageModelIdRef = { activeLocalApiImageModelId },
            setActiveLocalApiImageModelIdRef = { activeLocalApiImageModelId = it },
            tryAcquireObservedImageGenerationLeaseRef = { tryAcquireObservedImageGenerationLease(it) },
            releaseObservedImageGenerationLeaseRef = { releaseObservedImageGenerationLease(it) },
            registerLocalApiImageGenerationOwnershipRef = { registerLocalApiImageGenerationOwnership(it) },
            unregisterLocalApiImageGenerationOwnershipRef = { unregisterLocalApiImageGenerationOwnership(it) },
            cancelOwnedLocalApiImageWorkerRef = { cancelOwnedLocalApiImageWorker(it) },
            resolveCurrentLocalImageLorasRef1 = { resolveCurrentLocalImageLorasFor(it, uiStateStore.current.localImageLoras) },
            resolveCurrentLocalImageLorasRef2 = { s, r -> resolveCurrentLocalImageLorasFor(s, r) },
            resolveCurrentLocalImageTextualInversionIdsRef = { resolveCurrentLocalImageTextualInversionIdsFor(it, uiStateStore.current.localImageTextualInversions) },
            prepareLocalImagePromptExecutionRef = { m, p, o, c -> prepareLocalImagePromptExecution(imagePromptTranslationPort, m, p, o, c) },
            prepareLocalImageRuntimeMemoryRef = { prepareLocalImageRuntimeMemoryFor(uiStateStore, container.runtimeAdmission, runtimeProfileStore, engine) },
            executionWithQnnVerificationEvidenceRef = { m, r, o, req, att, ent ->
                executionWithQnnVerificationEvidenceFor(getApplication(), m, r, o, req, att, ent)
            },
        )

    private suspend fun generateLocalApiImage(requestId: String, body: String): String =
        generateLocalApiImageOperations(
            requestId = requestId,
            body = body,
            port = localApiImageGenerationPort
        )


    /**
     * Thin forwarders to [GenerationPreferences] and the two pure helpers beside it. The store is a
     * stateless object, so nothing here holds state or a scope: each call passes what the operation
     * needs.
     */

    private fun loadApiPreferences(application: Application): LocalApiPreferences =
        GenerationPreferences.loadApiPreferences(application)

    private fun persistApiPreferences(apiEnabled: Boolean, restEnabled: Boolean) {
        GenerationPreferences.persistApiPreferences(
            context = getApplication(),
            apiEnabled = apiEnabled,
            restEnabled = restEnabled
        )
    }

    private fun modelGenerationProfileKey(backend: ChatBackend, modelId: String?): String? =
        GenerationPreferences.modelGenerationProfileKey(backend, modelId)

    private fun MainUiState.modelGenerationProfileKey(): String? =
        modelGenerationProfileKey(selectedChatBackend, currentChatModelId())

    private fun loadModelGenerationParams(
        backend: ChatBackend,
        modelId: String?,
        defaults: GenerationParams
    ): GenerationParams = loadModelGenerationParamsFor(modelGenerationProfileStore, backend, modelId, defaults)
    private fun persistModelGenerationParams(state: MainUiState, params: GenerationParams) {
        persistModelGenerationParams(
            backend = state.selectedChatBackend,
            modelId = state.currentChatModelId(),
            params = params
        )
    }

    private fun persistModelGenerationParams(
        backend: ChatBackend,
        modelId: String?,
        params: GenerationParams
    ) {
        GenerationPreferences.persistModelGenerationParams(
            profileStore = modelGenerationProfileStore,
            backend = backend,
            modelId = modelId,
            params = params
        )
    }

    private fun loadGenerationParams(application: Application): GenerationParams =
        GenerationPreferences.loadGenerationParams(application)


    private fun loadPersistentPrefixCacheEnabled(application: Application): Boolean =
        GenerationPreferences.loadPersistentPrefixCacheEnabled(application)



    private fun applyAssistantDefaultModel(assistant: AssistantRecord) =
        applyAssistantDefaultModelFor(uiStateStore, ::showStatus, ::selectCloudChatModel, ::loadModel, assistant)

    private fun apiUrl(host: String): String =
        localApiRuntimeOwner.apiUrl(host)

    private fun MainUiState.prepareChatInput(): PreparedChatInput {
        val matches = imageAttachmentRegex.findAll(input).toList()
        val attachments = matches.mapNotNull { match ->
            val name = match.groupValues.getOrNull(1).orEmpty().trim()
            val uriString = match.groupValues.getOrNull(2).orEmpty().trim()
            if (uriString.isBlank()) return@mapNotNull null
            val asset = images.firstOrNull { it.uriString == uriString || it.name == name }
            asset?.toChatAttachment(mimeTypeForUri(uriString))
                ?: ChatImageAttachment(
                    name = name,
                    uriString = uriString,
                    mimeType = mimeTypeForUri(uriString)
                )
        }
        val textWithoutAttachments = imageAttachmentRegex
            .replace(input, "")
            .replace(oldImagePlaceholderRegex, "")
            .trim()
        return PreparedChatInput(
            text = textWithoutAttachments,
            // Keep composer parsing cheap: the send callback runs on the UI
            // thread. Content hashes are computed by the IO-bound vision and
            // persistence paths; display also collapses imported file copies.
            imageAttachments = attachments.deduplicateVisionAttachmentsForDisplay()
        )
    }

    /** Delegates to [visionMimeTypeForUri]; the Context read stays on the owner side. */
    private fun mimeTypeForUri(uriString: String): String =
        visionMimeTypeForUri(visionAttachmentPort, uriString)








    private fun currentOpenApiAddress(): String =
        localApiRuntimeOwner.currentOpenApiAddress(detectLanIpAddress())



    private suspend fun timedLoadModel(
        model: ModelManifest,
        runtime: LocalChatRuntime,
        loadParams: LoadParams
    ): Long = timedLoadModelFor(
        engine = engine,
        activeRuntimeIdentity = activeRuntimeIdentity,
        modelStore = modelStore,
        context = getApplication(),
        device = currentDeviceProfile(),
        installationScopeId = installationScopeId,
        model = model,
        runtime = runtime,
        loadParams = loadParams,
        currentQairtBundleSha256 = ::currentQairtBundleSha256,
        currentQairtVerifiedLocalModelIds = ::currentQairtVerifiedLocalModelIds
    )

    private suspend fun runLocalGenerationSmoke(params: GenerationParams): LocalGenerationSmokeResult =
        runLocalGenerationSmokeFor(engine, params)

    private suspend fun recoverInterruptedRuntimeProfiles() =
        recoverInterruptedRuntimeProfilesFor(runtimeProfileStore, pendingRuntimeRecoveries, uiStateStore)





    private fun currentDeviceProfile(): DeviceProfile =
        deviceProfileReader.read()




    private fun buildRecommendation(
        preference: UserPreference,
        device: DeviceProfile = currentDeviceProfile(),
        lastDecodeTps: Double? = uiStateStore.current.benchmark?.decodeTps?.takeIf { it > 0.0 }
            ?: uiStateStore.current.stats.decodeTps.takeIf { it > 0.0 }
    ): AgentRecommendation =
        advisor.recommend(
            device = device,
            localModels = modelStore.listModels(),
            remoteFiles = uiStateStore.current.remoteFiles,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
}
