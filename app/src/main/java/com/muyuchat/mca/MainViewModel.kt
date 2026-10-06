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
import com.muyuchat.core.engine.ContextSummarizationService
import com.muyuchat.core.engine.ContextWindowAdmission
import com.muyuchat.mca.presentation.ChatContextSnapshot
import com.muyuchat.core.engine.ContextSummaryModel
import com.muyuchat.core.engine.ContextSummarySource
import com.muyuchat.core.engine.compressChatRequestContext
import com.muyuchat.core.engine.AuthorizedPendingSignatureVerification
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationPhase
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatExecutionContext
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
import com.muyuchat.core.engine.estimateLocalPromptTokens
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
private val imageAttachmentRegex = Regex("""\u3010上传图片：([^\u3011]+)\u3011(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")
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
    private val skipInitialModelDiscovery: Boolean = false
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
        private const val CONTEXT_COMPRESSION_THRESHOLD_KEY = "threshold_percent"
        private const val CONTEXT_SUMMARY_MODEL_KEY = "summary_model"
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
    private val isolatedLocalChatRunners = IsolatedLocalChatRunners(application)
    private val engine = McaInferenceService(
        context = application,
        runners = isolatedLocalChatRunners.runners,
        installationScopeId = installationScopeId
    )
    private val apiKey = GenerationPreferences.loadOrCreateApiKey(application)
    private val localApiRuntimeOwner = Any()
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
                runCatching { assistantStore.saveAssistants(_uiState.value.assistants) }
                    .onFailure { error ->
                        uiStateStore.update { it.copy(
                            statusMessage = "助手保存失败：${error.message ?: "请重试"}"
                        ) }
                    }
            }
        }
    }
    private val globalChatAppearanceStore = container.globalChatAppearanceStore
    private val backgroundImageStore = container.backgroundImageStore
    private val chatAppearanceMutex = Mutex()
    private val cloudApiStore = container.cloudApiStore
    private val cloudChatProvider = container.cloudChatProvider
    private val offlinePromptTranslationService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OfflinePromptTranslationService(AppOfflinePromptTranslationRuntimeProvider(application))
    }
    private val cloudImageProvider = container.cloudImageProvider
    private val webSearchStore = container.webSearchStore
    private val webSearchDiagnosticStore = container.webSearchDiagnosticStore
    private val webSearchProvider: WebSearchProvider
        get() = container.webSearchProvider
    private var activeWebSearchDeferred: Deferred<WebSearchResult>?
        get() = container.runtimeAdmission.activeWebSearchDeferred
        set(value) {
            container.runtimeAdmission.activeWebSearchDeferred = value
        }
    @Volatile private var activeWebSearchTaskId: String? = null
    private val localImageModelStore = container.localImageModelStore
    private val localImageLoraStore = container.localImageLoraStore
    private val localImageTextualInversionStore = container.localImageTextualInversionStore
    private val localImageUpscalerStore = container.localImageUpscalerStore
    private val imageUpscalerPreferences: android.content.SharedPreferences
        get() = container.imageUpscalerPreferences
    private val contextCompressionPreferences = application.getSharedPreferences(
        CONTEXT_COMPRESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val manualContextCompressionRequests: ContextCompressionRequestStore
        get() = container.manualContextCompressionRequests
    private val contextSummarizationService = ContextSummarizationService()
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
        currentState = { _uiState.value },
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
    private val initialGlobalChatAppearance = globalChatAppearanceStore.load()
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
    private val initialLocalImageUpscalers = localImageUpscalerStore.load()
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
    private val initialWebSearchConfig = webSearchStore.load()
    private val initialWebSearchDiagnostics = webSearchDiagnosticStore.load()
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
        sessionId = _uiState.value.activeChatSessionId,
        assistantId = _uiState.value.selectedAssistantId,
        selectionRevision = contentImportSelectionRevision.get()
    )
    private fun ContentImportOwner.isCurrent(state: MainUiState): Boolean =
        selectionRevision == contentImportSelectionRevision.get() &&
            sessionId == state.activeChatSessionId && assistantId == state.selectedAssistantId
    private val pendingAssistantImageToolTurns: java.util.concurrent.ConcurrentHashMap<String, PendingAssistantImageToolTurn>
        get() = container.runtimeAdmission.pendingAssistantImageToolTurns
    private val assistantImageContinuationLock: Any
        get() = container.runtimeAdmission.assistantImageContinuationLock
    private val assistantImageContinuationsInFlight: MutableSet<String>
        get() = container.runtimeAdmission.assistantImageContinuationsInFlight

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
            contextCompressionThresholdPercent = loadContextCompressionThreshold().percent,
            contextSummaryModelSelection = contextCompressionPreferences
                .getString(CONTEXT_SUMMARY_MODEL_KEY, "current") ?: "current",
            appUpdate = initialAppUpdateState
        )
    )
    // R3/C3-S5：chatRuns/流缓冲累加器入容器；terminalSettlement 端口具名化。
    private val chatStreamAccumulatorInit = container.initializeChatStreamAccumulator(uiStateStore, viewModelScope)

    // R3/C3-S13：启动对账 owner（lazy async + await 状态写随 body 移出；start() 调用点不变；
    // 声明位于 init/startAfterConstruction 之前，保持与旧字段等价的初始化序）。
    private val imageLibraryStartupReconciliation = container.createImageLibraryStartupReconciliation(
        uiStateStore, viewModelScope, chatImageCommitRecoveryPort, initialKnowledgeBases
    )
    private val chatTerminalSettlementPort = container.createChatTerminalSettlementPort(uiStateStore)
    // R3/C3-S3：token 测量 host 具名化——容器工厂构造，两个缓存随迁（仅本端口使用）。
    private val imagePromptTokenMeasurementHost = container.createImagePromptTokenMeasurementHost(uiStateStore)
    // R3/C3-S1（插队裁定 2）：三个小端口具名化为容器工厂构造（真实依赖注入），匿名实现已删除。
    private val localImageAdapterPort = container.createLocalImageAdapterPort(uiStateStore)
    private val webSearchConfigPort = container.createWebSearchConfigPort(uiStateStore)
    private val visionAttachmentPort = container.createVisionAttachmentPort(uiStateStore, engine)
    // R3/C2（插队裁定 2）：TuningOwner 由组合根工厂构造（真实依赖注入）；VM 仅 intent 委托。
    private val tuningOwner = container.createTuningOwner(
        store = uiStateStore,
        scope = viewModelScope,
        engine = engine
    )
    // R3/C2: 生图准入/执行唯一 owner（C0-a）：三处端口实现与两个直连调用点收敛到同一实例。
    // R3/C2-S3: host 由 McaAppContainer 工厂装配具名适配器，出清 97 行匿名 object 与 33 处 this 引用。
    private val imageGenerationOwner = container.createImageGenerationOwner(
        store = uiStateStore,
        scope = viewModelScope,
        engine = engine,
        imageLibraryStartupReconciliationProvider = { imageLibraryStartupReconciliation },
        imageLibraryCommitPortProvider = { imageLibraryCommitPort },
        chatImageRequestPortProvider = { chatImageRequestPort },
        imagePromptTranslationPortProvider = { imagePromptTranslationPort },
        completeAssistantImageToolTurnForJob = { completeAssistantImageToolTurnForJob(it) }
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

    private val offlinePromptTranslationAndChatImageCoordinator = OfflinePromptTranslationAndChatImageCoordinator(
        store = uiStateStore,
        admission = container.runtimeAdmission,
        scope = viewModelScope,
        installer = container.offlineTranslationInstaller,
        downloader = container.offlineTranslationDownloader,
        chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
        prepareChatInput = { it.prepareChatInput() },
        showStatus = ::showStatus,
        rejectWhileConversationMutationInProgress = ::rejectWhileConversationMutationInProgress,
        hasUnresolvedAssistantImageToolTurn = ::hasUnresolvedAssistantImageToolTurn,
        sendChatImageRequestWithPromptBridge = ::sendChatImageRequestWithPromptBridge,
        sendPreparedMessage = ::sendPreparedMessage
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
            val loaded = loadInitialTextualInversionLibraryState(localImageTextualInversionStore)
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
        installLocalApiAuxProviders(localApiAuxProviderPort)
        LocalApiRuntime.controlPlane = container.createLocalApiControlPlane(
            busyStateProvider = {
                localApiBusyStateFor(_uiState.value, tuningJson = { localApiTuningStateJson(localApiPayloadPort) })
            },
            preflightProvider = { request ->
                localApiPreflightFor(
                    request = request,
                    state = _uiState.value,
                    engine = engine,
                    publicModelId = { displayName, id -> localApiPublicModelId(displayName, id) },
                    runtimeProfileJson = { runtimeProfileJsonFor(_uiState.value, engine, activeRuntimeIdentity) }
                )
            },
            profileJsonProvider = {
                runtimeProfileJsonFor(_uiState.value, engine, activeRuntimeIdentity)
            },
            tuningJsonProvider = {
                localApiTuningStateJson(localApiPayloadPort)
            },
            tuningJobProvider = { jobId ->
                localApiTuningJobFor(
                    requestedJobId = jobId,
                    currentIdentityKey = { activeRuntimeIdentity?.identityHash },
                    currentActiveTuningJobId = { activeTuningJobId },
                    lookupJob = { id -> runtimeProfileStore.tuningJob(id) },
                    lookupActiveJob = { key -> runtimeProfileStore.activeTuningJob(key) },
                    tuningJson = { localApiTuningStateJson(localApiPayloadPort) },
                    publicJobJson = { job -> tuningJobPublicJson(job) }
                )
            },
            createTuningJobDelegate = { request, idempotencyKey ->
                localApiCreateTuningJobFor(
                    request = request,
                    idempotencyKey = idempotencyKey,
                    state = _uiState.value,
                    engine = engine,
                    port = localApiTuningControlPort
                )
            },
            pauseTuningJobDelegate = { jobId, idempotencyKey ->
                runTuningControl(jobId, "pause") {
                    agentTuningCoordinator.adaptiveTuningPauseRequested.set(true)
                    runtimeProfileStore.pauseTuningJob(jobId)
                }
            },
            resumeTuningJobDelegate = { jobId, idempotencyKey ->
                runTuningControl(jobId, "resume") {
                    agentTuningCoordinator.adaptiveTuningPauseRequested.set(false)
                    runtimeProfileStore.resumeTuningJob(jobId)
                }
            },
            cancelTuningJobDelegate = { jobId, idempotencyKey ->
                runTuningControl(jobId, "cancel") {
                    agentTuningCoordinator.adaptiveTuningCancelRequested.set(true)
                    cancelPersistedTuningJob(jobId)
                }
            },
            applyTuningJobDelegate = { jobId, idempotencyKey ->
                localApiApplyTuningJobFor(
                    jobId = jobId,
                    idempotencyKey = idempotencyKey,
                    port = localApiTuningControlPort
                )
            },
            rollbackTuningDelegate = { idempotencyKey ->
                localApiRollbackTuningFor(
                    idempotencyKey = idempotencyKey,
                    port = localApiTuningControlPort
                )
            },
            runDurableLocalApiControl = { idempotency, operation ->
                runDurableLocalApiControl(idempotency, operation)
            }
        )

        // Every provider above is now installed. Publish them as one immutable set so a runtime call
        // that starts from here on can never observe a half-installed owner (for example the new
        // chat stream paired with the previous owner's stop handle).
        LocalApiRuntime.publishBindings()

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


    /** Must be called from an IO coroutine. */


    private fun LocalImageModelRecord.isReadyForManagedSelection(
        qnnVerificationCurrentByModelId: Map<String, Boolean>
    ): Boolean = localImageReadinessForUi(qnnVerificationCurrentByModelId[id]) == null


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
        if (value != _uiState.value.input &&
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
        val state = _uiState.value
        val session = request.sessionId?.let { id -> state.chatSessions.firstOrNull { it.id == id } }
        val ownerMessages = if (request.sessionId == state.activeChatSessionId) state.messages else session?.messages.orEmpty()
        if (request.sessionId != null && session == null && request.sessionId != state.activeChatSessionId) return
        if (request.messageId != null && ownerMessages.none { it.id == request.messageId }) return
        uiStateStore.update {
            it.copy(browserTask = com.muyuchat.feature.chat.BrowserTaskStateMachine.create(request))
        }
    }

    fun onBrowserTaskEvent(event: com.muyuchat.feature.chat.BrowserTaskEvent) {
        val activeTask = _uiState.value.browserTask
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
    fun requestContextCompression() {
        val state = _uiState.value
        if (!state.canWriteToActiveAssistantSession()) return
        if (state.isGenerating) {
            showStatus("当前正在生成，请完成或停止后再压缩上下文。")
            return
        }
        if (state.messages.isEmpty()) {
            showStatus("当前对话还没有可压缩的内容。")
            return
        }
        val sessionId = state.activeChatSessionId
        if (sessionId.isNullOrBlank()) {
            showStatus("当前对话尚未保存，暂时无法安排压缩。")
            return
        }
        manualContextCompressionRequests.request(sessionId)
        uiStateStore.update {
            it.copy(
                contextCompressionPending = true,
                statusMessage = "已安排压缩；下一次发送时会保留角色设定和最近消息。"
            )
        }
    }

    fun toggleMessagePinned(messageId: String) {
        val owner = _uiState.value
        val sessionId = owner.activeChatSessionId ?: return
        if (owner.isGenerating || !owner.canWriteToActiveAssistantSession()) return
        var changed = false
        uiStateStore.update { state ->
            if (state.isGenerating || state.activeChatSessionId != sessionId ||
                !state.canWriteToActiveAssistantSession() ||
                state.messages.none { it.id == messageId }
            ) return@update state
            val messages = state.messages.map { message ->
                if (message.id == messageId) message.copy(pinned = !message.pinned) else message
            }
            changed = true
            state.copy(
                messages = messages,
                chatSessions = state.chatSessions.upsertSession(sessionId, messages).map { session ->
                    if (session.id != sessionId) session else session.copy(
                        contextSummaries = revokeContextSummaries(session.contextSummaries)
                    )
                }
            )
        }
        if (changed) {
            markLocalConversationContextInvalid()
            persistChatSessions()
        }
    }

    fun undoContextSummary() {
        val state = _uiState.value
        if (state.isGenerating || !state.canWriteToActiveAssistantSession()) return
        val sessionId = state.activeChatSessionId ?: return
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val summaryScope = (assistantSnapshot?.applyTo(state.params) ?: state.params).systemPrompt +
            "\n" + assistantSnapshot?.toJsonString().orEmpty()
        uiStateStore.update { current ->
            current.copy(
                chatSessions = current.chatSessions.map { session ->
                    if (session.id != sessionId) session else session.copy(
                        contextSummaries = restorePreviousContextSummaryRevision(
                            session.contextSummaries, session.messages, summaryScope
                        )
                    )
                },
                statusMessage = "已撤销当前摘要并恢复可用的上一版本；原始消息保留。"
            )
        }
        markLocalConversationContextInvalid()
        persistChatSessions()
    }

    fun contextSummaryEvidenceForQuery(query: String): List<com.muyuchat.feature.chat.ContextSummaryEvidenceUiItem> {
        val state = _uiState.value
        val session = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
            ?: return emptyList()
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val scope = (assistantSnapshot?.applyTo(state.params) ?: state.params).systemPrompt +
            "\n" + assistantSnapshot?.toJsonString().orEmpty()
        return ContextSummarySourceIndex.fromActiveRevision(
            session.contextSummaries, session.messages, scope
        ).search(query).map { hit ->
            com.muyuchat.feature.chat.ContextSummaryEvidenceUiItem(
                kind = hit.kind.name,
                text = hit.text,
                sourceMessageId = hit.sourceMessageId,
                sourceOffset = hit.sourceOffset
            )
        }
    }

    /** Persists one of the supported automatic thresholds and updates the UI. */
    fun setContextCompressionThreshold(percent: Int) {
        val threshold = ContextCompressionThreshold.values()
            .firstOrNull { it.percent == percent }
            ?: ContextCompressionThreshold.EIGHTY
        contextCompressionPreferences.edit()
            .putInt(CONTEXT_COMPRESSION_THRESHOLD_KEY, threshold.percent)
            .apply()
        uiStateStore.update {
            it.copy(
                contextCompressionThresholdPercent = threshold.percent,
                statusMessage = "自动压缩阈值已设为 ${threshold.percent}%。"
            )
        }
    }

    private fun loadContextCompressionThreshold(): ContextCompressionThreshold =
        ContextCompressionThreshold.values()
            .firstOrNull {
                it.percent == contextCompressionPreferences.getInt(
                    CONTEXT_COMPRESSION_THRESHOLD_KEY,
                    ContextCompressionThreshold.EIGHTY.percent
                )
            }
            ?: ContextCompressionThreshold.EIGHTY

    fun setContextSummaryModel(selection: String) {
        val state = _uiState.value
        if (state.isGenerating) return
        val valid = selection in setOf("current", "deterministic", "local") ||
            state.cloudModels.any {
                selection == "cloud:${it.id}" && it.kind == CloudModelKind.CHAT && it.configured
            }
        if (!valid) return
        contextCompressionPreferences.edit().putString(CONTEXT_SUMMARY_MODEL_KEY, selection).apply()
        uiStateStore.update { it.copy(contextSummaryModelSelection = selection) }
    }

    private suspend fun summarizeContextForGeneration(
        request: ChatRequest,
        settings: ContextCompressionSettings,
        trigger: ContextCompressionTrigger,
        initialState: MainUiState,
        effectiveBackend: ChatBackend,
        cloudModelIdOverride: String?,
        generationRunId: Long,
        useModel: Boolean
    ): com.muyuchat.core.engine.ContextCompressionResult {
        fun ownerCurrent(): Boolean {
            val current = _uiState.value
            val expectedSession = initialState.chatSessions.firstOrNull {
                it.id == initialState.activeChatSessionId
            }
            val actualSession = current.chatSessions.firstOrNull {
                it.id == initialState.activeChatSessionId
            }
            return chatRuns.matches(generationRunId) &&
                current.activeChatSessionId == initialState.activeChatSessionId &&
                current.messages == initialState.messages &&
                expectedSession != null && actualSession != null &&
                !actualSession.mixedAssistantHistory &&
                actualSession.assistantId == expectedSession.assistantId &&
                actualSession.assistantSnapshot == expectedSession.assistantSnapshot
        }
        val selection = initialState.contextSummaryModelSelection
        val local = selection == "local" || selection == "current" && effectiveBackend == ChatBackend.LOCAL
        val localIdentity = activeRuntimeIdentity
        val cloudId = if (selection.startsWith("cloud:")) selection.removePrefix("cloud:")
            else cloudModelIdOverride ?: initialState.selectedCloudChatModelId
        val cloudConfig = initialState.cloudModels.firstOrNull {
            it.id == cloudId && it.kind == CloudModelKind.CHAT && it.configured
        }?.toChatConfig()?.normalized()
        val model = when {
            !useModel || selection == "deterministic" -> null
            local && localIdentity != null && engine.stats.value.loaded -> ContextSummaryModel(
                modelId = initialState.loadedModelId ?: localIdentity.identityHash,
                runtimeIdentity = localIdentity.identityHash,
                params = engine.activeExecutionProfile()?.let { mergedExecutionProfile(request.params, it) }
                    ?: request.params
            )
            !local && cloudConfig != null && cloudId != null -> ContextSummaryModel(
                modelId = cloudId,
                runtimeIdentity = "cloud:" + contextSummaryDigest(JSONArray(listOf(
                    cloudId, cloudConfig.baseUrl.trimEnd('/'), cloudConfig.apiFormat.name, cloudConfig.chatModel
                )).toString()),
                params = modelGenerationProfileStore.load(ModelGenerationProfileKey.cloud(cloudId), request.params),
                source = ContextSummarySource.CLOUD_MODEL
            )
            else -> null
        }
        return contextSummarizationService.compress(
            request = request,
            settings = settings,
            trigger = trigger,
            model = model,
            isOwnerCurrent = ::ownerCurrent,
            infer = { summaryRequest ->
                if (!ownerCurrent()) throw CancellationException("Summary owner changed.")
                uiStateStore.update { current ->
                    if (ownerCurrent()) current.copy(statusMessage = "正在整理上下文摘要...") else current
                }
                if (local) {
                    check(localImageGenerationCoordinator.activeRequestId() == null) {
                        "Local image generation owns the runtime resources."
                    }
                    withContext(Dispatchers.IO) {
                        val lease = engine.acquireExclusiveLifecycleLease(stopActiveGeneration = false)
                        var inferenceStarted = false
                        try {
                            if (!ownerCurrent() || activeRuntimeIdentity?.identityHash != localIdentity?.identityHash) {
                                throw CancellationException("Summary model or owner changed.")
                            }
                            inferenceStarted = true
                            collectContextSummary(
                                engine.streamChat(
                                    summaryRequest,
                                    LocalChatExecutionContext(
                                        requestId = "context-summary-$generationRunId-${UUID.randomUUID()}",
                                        lifecycleLease = lease,
                                        includeDeviceClockContext = false
                                    )
                                ),
                                ::ownerCurrent
                            )
                        } finally {
                            if (inferenceStarted) {
                                localConversationContextNeedsInvalidation = true
                                localConversationContextInvalidationSequence.incrementAndGet()
                            }
                            withContext(NonCancellable) { lease.release() }
                        }
                    }
                } else {
                    collectContextSummary(cloudChatProvider.streamChat(checkNotNull(cloudConfig), summaryRequest), ::ownerCurrent)
                }
            }
        )
    }

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


    fun useImageAsset(imageId: String) {
        val image = _uiState.value.images.firstOrNull { it.id == imageId } ?: return
        uiStateStore.update { state ->
            val alreadyAttached = imageAttachmentRegex.findAll(state.input).any { match ->
                match.groupValues.getOrNull(2)?.trim() == image.uriString.trim()
            }
            if (alreadyAttached) {
                return@update state.copy(statusMessage = "这张图片已在当前消息中：${image.name}")
            }
            val separator = if (state.input.isBlank()) "" else "\n\n"
            state.copy(
                input = state.input + separator + image.toInputAttachment(),
                statusMessage = "已插入图片：${image.name}"
            )
        }
    }

    /**
     * Measures a prompt with the tokenizer declared by the selected local image
     * model. This is tokenizer-only and never loads a diffusion graph. Known
     * tokenizer adapters return exact counts; other stable-diffusion.cpp
     * profiles return an explicitly marked conservative estimate.
     */
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


    fun useFileAsset(fileId: String) {
        val file = _uiState.value.files.firstOrNull { it.id == fileId } ?: return
        uiStateStore.update { state ->
            val separator = if (state.input.isBlank()) "" else "\n\n"
            state.copy(
                input = state.input + separator + file.toInputAttachment(),
                statusMessage = "已插入文件：${file.name}"
            )
        }
    }

    fun deleteFileAsset(fileId: String) {
        deleteFileAssetFor(
            store = uiStateStore,
            persistFiles = ::persistFiles,
            fileId = fileId
        )
    }

    fun clearFileLibrary() {
        clearFileLibraryFor(
            store = uiStateStore,
            showStatus = ::showStatus,
            persistFiles = ::persistFiles
        )
    }

    /** Delegates to [importLocalImageLoraAdapter]. */
    fun importLocalImageLora(uriString: String) =
        importLocalImageLoraAdapter(localImageAdapterPort, viewModelScope, uriString)

    /** Delegates to [deleteLocalImageLoraAdapter]. */
    fun deleteLocalImageLora(id: String) =
        deleteLocalImageLoraAdapter(localImageAdapterPort, viewModelScope, id)

    /** Delegates to [importLocalImageTextualInversionArtifact]. Signature copied verbatim. */
    fun importLocalImageTextualInversion(uriString: String, trigger: String) =
        importLocalImageTextualInversionArtifact(localImageAdapterPort, viewModelScope, uriString, trigger)

    /** Delegates to [deleteLocalImageTextualInversionArtifact]. */
    fun deleteLocalImageTextualInversion(id: String) =
        deleteLocalImageTextualInversionArtifact(localImageAdapterPort, viewModelScope, id)

    /** Downloads the publisher-pinned Viggle v0.2.1 adapter into the same
     * private store used by user-imported LoRAs. Unknown device profiles do not
     * affect visibility or access; the store's byte and SHA checks are the
     * acceptance gate. */
    fun downloadOfficialViggleLora() =
        downloadOfficialViggleLoraFor(uiStateStore, viewModelScope, localImageLoraStore)

    fun reportMissingLocalImageLoraSelection() {
        uiStateStore.update { it.copy(chatImageLoraSelections = null) }
        uiStateStore.update {
            it.copy(
                localImageLoraMessage = "所选 LoRA 已删除，请重新选择。",
                statusMessage = "所选 LoRA 已删除，请重新选择。"
            )
        }
    }

    fun reportMissingLocalImageTextualInversionSelection() {
        uiStateStore.update {
            it.copy(
                localImageTextualInversionMessage =
                    "所选 Textual Inversion 已删除，请重新选择。",
                statusMessage = "所选 Textual Inversion 已删除，请重新选择。"
            )
        }
    }

    // R3-S32：图片放大模型目录端口具名适配器（容器工厂装配，真实依赖注入）。
    private val localImageUpscalerPort: LocalImageUpscalerPort =
        container.createLocalImageUpscalerPort(
            uiStateStore,
            { imageUpscaleOwner }
        )

    /** Delegates to [importLocalImageUpscalerModel]. */
    fun importLocalImageUpscaler(uriString: String) =
        importLocalImageUpscalerModel(localImageUpscalerPort, viewModelScope, uriString)

    /** Delegates to [selectLocalImageUpscalerModel]. */
    fun selectLocalImageUpscaler(id: String) =
        selectLocalImageUpscalerModel(localImageUpscalerPort, id)

    /** Delegates to [deleteLocalImageUpscalerModel]. */
    fun deleteLocalImageUpscaler(id: String) =
        deleteLocalImageUpscalerModel(localImageUpscalerPort, viewModelScope, id)





    fun generateImageAsset(
        prompt: String,
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ): Boolean {
        return imageGenerationOwner.submit(
            prompt = prompt,
            inputDraft = inputDraft,
            options = options,
            jobSnapshot = null
        )
    }

    fun retryImageGeneration(jobId: String) {
        val sourceJob = _uiState.value.imageJobs.firstOrNull { it.id == jobId }
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

    /** Delegates to [resolveCurrentLocalImageLorasFor]. Signature kept verbatim. */
    private fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord> = _uiState.value.localImageLoras
    ): List<LocalImagePreparedLora> = resolveCurrentLocalImageLorasFor(selections, records)

    /** Delegates to [resolveCurrentLocalImageTextualInversionIdsFor]. Signature kept verbatim. */
    private fun resolveCurrentLocalImageTextualInversionIds(
        ids: List<String>,
        records: List<TextualInversionArtifact> = _uiState.value.localImageTextualInversions
    ): List<String> = resolveCurrentLocalImageTextualInversionIdsFor(ids, records)

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

    /** Delegates to [canReadGenerationHistoryInputFor]. Signature kept verbatim. */
    private fun canReadGenerationHistoryInput(reference: String): Boolean =
        canReadGenerationHistoryInputFor(getApplication(), reference)

    /**
     * Prepares the prompt pair for a local image request.
     *
     * The decision moved to [prepareLocalImagePromptExecution]; what stays here is the port: the
     * verified bundle, the shared translation service, and the status publication that still reads
     * the live active image-job id at the same moment it always did.
     */
    private suspend fun prepareLocalImagePromptExecution(
        model: LocalImageModelRecord,
        prompt: String,
        options: LocalImageGenerationOptions,
        captured: LocalImagePromptExecution?
    ): LocalImagePromptExecution = prepareLocalImagePromptExecution(
        port = imagePromptTranslationPort,
        model = model,
        prompt = prompt,
        options = options,
        captured = captured
    )

    // R3-S27：translation 端口的具名适配器（service 以 provider 保 lazy 时机；verifiedBundle 为过渡
    // 注入——offline-translation 家族批落地后改为直接调用并删除该 lambda）。
    // R3-S27/S31：translation 端口的具名适配器（service 以 provider 保 lazy 时机；verifiedBundle 直调 operations）。
    private val imagePromptTranslationPort: ImagePromptTranslationPort =
        container.createImagePromptTranslationPort(
            uiStateStore,
            { offlinePromptTranslationService }
        )
    /** Delegates to [prepareLocalImageRuntimeMemoryFor]. Signature kept verbatim. */
    private suspend fun prepareLocalImageRuntimeMemory(): com.muyuchat.core.engine.EngineLifecycleLease =
        prepareLocalImageRuntimeMemoryFor(
            uiStateStore, container.runtimeAdmission, runtimeProfileStore, engine
        )

    /** Delegates to [startLocalImageGenerationWatchdogFor]. Signature kept verbatim. */
    private fun startLocalImageGenerationWatchdog(jobId: String) =
        startLocalImageGenerationWatchdogFor(uiStateStore, viewModelScope, jobId)

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

    /** Delegates to [canPublishLocalImagePreviewFor]. Signature kept verbatim. */
    private fun canPublishLocalImagePreview(jobId: String): Boolean =
        canPublishLocalImagePreviewFor(uiStateStore, container.runtimeAdmission, jobId)

    /** Delegates to [cleanupLocalImagePreviewsFor]. Signature kept verbatim. */
    private fun cleanupLocalImagePreviews(jobId: String) =
        cleanupLocalImagePreviewsFor(getApplication(), jobId)

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

    fun importLocalImageModel(uri: Uri) {
        launchModelOperation("模型操作未完成") {
            busy("正在导入本地图像生成引擎...")
            runCatching {
                localImageModelStore.importFromUri(uri)
            }
                .onSuccess { model ->
                    val selection = settleLocalImageSelection(model)
                    val readiness = model.localImageReadinessForUi(
                        selection.qnnVerificationCurrentByModelId[model.id]
                    )
                    val diagnostic = model.localImageVerificationDiagnosticMessage()
                    managedRuntimeReadinessRefreshGate.invalidate()
                    uiStateStore.update {
                        it.copy(
                            localImageModels = selection.models,
                            qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId,
                            selectedLocalImageModelId = selection.selectedId,
                            selectedImageBackend = selection.selectedBackend,
                            busy = false,
                            statusMessage = if (readiness != null) {
                                "已导入 ${model.displayName}，但暂不能生成：$readiness"
                            } else if (diagnostic != null) {
                                "已导入本地图像生成引擎：${model.displayName}；$diagnostic"
                            } else {
                                "已导入本地图像生成引擎：${model.displayName}"
                            }
                        )
                    }
                }
                .onFailure { error ->
                if (error is CancellationException) throw error
                    fail(error.message ?: "本地图像生成引擎导入失败")
                }
        }
    }

    /** Delegates to [rejectImageModelSwitchWhileGenerationIsActiveFor]. Signature kept verbatim. */
    private fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean =
        rejectImageModelSwitchWhileGenerationIsActiveFor(uiStateStore, container.runtimeAdmission)


    fun verifyLocalImageModel(modelId: String) {
        verifyLocalImageModelFor(
            store = uiStateStore,
            scope = viewModelScope,
            application = getApplication(),
            localImageModelStore = localImageModelStore,
            localImageWorkerClient = localImageWorkerClient,
            tryAcquireObservedImageGenerationLease = ::tryAcquireObservedImageGenerationLease,
            releaseObservedImageGenerationLease = ::releaseObservedImageGenerationLease,
            managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
            showStatus = ::showStatus,
            busy = ::busy,
            modelId = modelId
        )
    }


    /** Delegates to [executionWithQnnVerificationEvidenceFor]. Signature kept verbatim. */
    private fun executionWithQnnVerificationEvidence(
        model: LocalImageModelRecord,
        result: LocalImageResult,
        options: LocalImageGenerationOptions,
        requestId: String,
        attemptId: String,
        entryPoint: QnnImageEvidenceEntryPoint
    ): String = executionWithQnnVerificationEvidenceFor(
        getApplication(), model, result, options, requestId, attemptId, entryPoint
    )




    private data class LocalImageSelection(
        val models: List<LocalImageModelRecord>,
        val selectedId: String?,
        val selectedBackend: ImageBackend,
        val qnnVerificationCurrentByModelId: Map<String, Boolean>
    )

    /** Must be called from an IO coroutine. */
    private fun settleLocalImageSelection(
        preferred: LocalImageModelRecord
    ): LocalImageSelection {
        val models = localImageModelStore.loadModels()
        val preferredModel = models.firstOrNull { it.matchesCatalogId(preferred.id) } ?: preferred
        val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(getApplication(), models)
        val currentSelectedId = localImageModelStore.loadSelectedModelId()
        val currentBackend = localImageModelStore.loadSelectedBackend()
        val currentReadyId = currentSelectedId?.takeIf { id ->
            models.any {
                it.id == id && it.isReadyForManagedSelection(qnnVerificationCurrentByModelId)
            }
        }
        val preferredReady = preferredModel.isReadyForManagedSelection(qnnVerificationCurrentByModelId)
        val selectedId = when {
            preferredReady -> preferredModel.id
            currentReadyId != null -> currentReadyId
            else -> models.firstOrNull {
                it.isReadyForManagedSelection(qnnVerificationCurrentByModelId)
            }?.id
        }
        localImageModelStore.saveSelectedModelId(selectedId)
        val selectedBackend = when {
            preferredReady -> ImageBackend.LOCAL
            selectedId == null && currentBackend == ImageBackend.LOCAL -> ImageBackend.CLOUD
            else -> currentBackend
        }
        localImageModelStore.saveSelectedBackend(selectedBackend)
        return LocalImageSelection(
            models = models,
            selectedId = selectedId,
            selectedBackend = selectedBackend,
            qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId
        )
    }

    fun deleteLocalImageModel(modelId: String) {
        launchModelOperation("模型操作未完成") {
            if (activeImageGenerationModelId == modelId || activeLocalApiImageModelId == modelId) {
                uiStateStore.update {
                    it.copy(statusMessage = "当前图片任务正在使用该模型，请停止或等待任务完成后再删除")
                }
                return@launchModelOperation
            }
            val removed = _uiState.value.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
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
        val state = _uiState.value
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
        val hasLoadedModel = _uiState.value.loadedModelId != null
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
        hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
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

    /** Delegates to [persistWebSearchConfig]. */
    fun saveWebSearchConfig(
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) {
        persistWebSearchConfig(
            port = webSearchConfigPort,
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders
        )
    }

    /** Delegates to [runWebSearchConfigTest]. */
    fun testWebSearchConfig(query: String) {
        runWebSearchConfigTest(port = webSearchConfigPort, scope = viewModelScope, query = query)
    }

    /** Delegates to [runWebSearchConfigTest]. */
    fun testWebSearchConfig(
        query: String,
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) {
        runWebSearchConfigTest(
            port = webSearchConfigPort,
            scope = viewModelScope,
            query = query,
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders
        )
    }

    /** Delegates to [runWebSearchPreflight]. */
    fun preflightWebSearchConfig(
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ) {
        runWebSearchPreflight(
            port = webSearchConfigPort,
            scope = viewModelScope,
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders
        )
    }

    /** Delegates to [runWebSearchConfigTest]. */
    private fun testWebSearchConfig(query: String, config: WebSearchConfig) {
        runWebSearchConfigTest(
            port = webSearchConfigPort,
            scope = viewModelScope,
            query = query,
            config = config
        )
    }

    /** Delegates to [runWebSearchTurnTest]. */
    fun testWebSearchTurn(
        query: String,
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList(),
        allowPublicCheckSourceForProtocolTest: Boolean = false
    ) {
        runWebSearchTurnTest(
            port = webSearchConfigPort,
            scope = viewModelScope,
            query = query,
            enabled = enabled,
            provider = provider,
            endpoint = endpoint,
            apiKey = apiKey,
            maxResults = maxResults,
            fetchPageContent = fetchPageContent,
            triggerMode = triggerMode,
            researchMode = researchMode,
            backupProviders = backupProviders,
            allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
        )
    }

    /** Delegates to [runWebSearchTurnTest]. */
    private fun testWebSearchTurn(
        query: String,
        config: WebSearchConfig,
        allowPublicCheckSourceForProtocolTest: Boolean
    ) {
        runWebSearchTurnTest(
            port = webSearchConfigPort,
            scope = viewModelScope,
            query = query,
            config = config,
            allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
        )
    }









    fun toggleWebSearchForNextTurn() =
        applyToggleWebSearchForNextTurn(webSearchConfigPort)

    fun cycleWebSearchResearchModeForNextTurn() =
        applyCycleWebSearchResearchModeForNextTurn(webSearchConfigPort)

    fun selectWebSearchResearchModeForNextTurn(mode: String) =
        applySelectWebSearchResearchModeForNextTurn(webSearchConfigPort, mode)

    fun clearWebSearchDiagnostics() =
        applyClearWebSearchDiagnostics(webSearchConfigPort)

    private fun appendWebSearchDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> =
        webSearchConfigPort.appendDiagnostic(record)

    fun selectAssistant(assistantId: String) {
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val previousSession = state.chatSessions.latestSessionForAssistant(assistantId)
        if (previousSession != null) {
            if (previousSession.id != state.activeChatSessionId || state.selectedAssistantId != assistant.id) {
                selectChatSession(previousSession.id)
            }
            if (_uiState.value.activeChatSessionId == previousSession.id &&
                previousSession.modelMode.toChatBackendOrNull() == ChatBackend.LOCAL
            ) {
                previousSession.modelId?.let { modelId ->
                    if (_uiState.value.loadedModelId != modelId) {
                        _uiState.value.models.firstOrNull { it.id == modelId }?.let(::loadModel)
                    }
                }
            }
            return
        }
        if (!newChatForAssistant(assistant, bindDefaultModel = true)) return
        finishSelectingNewAssistant(assistant)
    }

    fun createChatForAssistant(assistantId: String) {
        val assistant = _uiState.value.assistants.firstOrNull { it.id == assistantId } ?: return
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
            currentModelGenerationProfileKey = { _uiState.value.modelGenerationProfileKey() },
            assistant = assistant
        )

    fun importChatBackground(scope: ChatAppearanceScope, uriString: String) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank()) {
            showStatus("无法读取所选背景图片")
            return
        }
        val target = captureChatAppearanceTarget(scope) ?: return
        uiStateStore.update { it.copy(chatBackgroundImporting = true, statusMessage = "正在处理背景图片…") }
        viewModelScope.launch {
            chatAppearanceMutex.withLock {
                runCatching {
                    val stored = withContext(Dispatchers.IO) { backgroundImageStore.import(uri) }
                    val current = currentAppearanceForTarget(target) ?: target.appearance
                    val appearance = current.copy(backgroundImagePath = stored.path)
                    withContext(Dispatchers.IO) { persistChatAppearanceTarget(target, appearance) }
                    applyPersistedChatAppearance(target, appearance)
                    cleanupUnusedChatBackgrounds()
                }.onSuccess {
                    uiStateStore.update {
                        it.copy(chatBackgroundImporting = false, statusMessage = "聊天背景已更新")
                    }
                }.onFailure { error ->
                    uiStateStore.update {
                        it.copy(
                            chatBackgroundImporting = false,
                            statusMessage = "背景图片设置失败：${error.message ?: "无法处理图片"}"
                        )
                    }
                }
            }
        }
    }

    fun setChatAppearance(scope: ChatAppearanceScope, appearance: ChatAppearance?) {
        val target = captureChatAppearanceTarget(scope) ?: return
        val normalized = when (scope) {
            ChatAppearanceScope.GLOBAL -> appearance ?: ChatAppearance()
            ChatAppearanceScope.ASSISTANT,
            ChatAppearanceScope.SESSION -> appearance
        }
        if (normalized?.backgroundImagePath != null &&
            backgroundImageStore.resolve(normalized.backgroundImagePath) == null
        ) {
            showStatus("背景图片已失效，请重新选择")
            return
        }
        viewModelScope.launch {
            chatAppearanceMutex.withLock {
                runCatching {
                    withContext(Dispatchers.IO) { persistChatAppearanceTarget(target, normalized) }
                    applyPersistedChatAppearance(target, normalized)
                    cleanupUnusedChatBackgrounds()
                }.onFailure { error ->
                    uiStateStore.update {
                        it.copy(statusMessage = "保存聊天外观失败：${error.message ?: "存储错误"}")
                    }
                }
            }
        }
    }

    private data class ChatAppearanceTarget(
        val scope: ChatAppearanceScope,
        val ownerId: String?,
        val appearance: ChatAppearance
    )

    private fun captureChatAppearanceTarget(scope: ChatAppearanceScope): ChatAppearanceTarget? {
        val state = _uiState.value
        return when (scope) {
            ChatAppearanceScope.GLOBAL -> ChatAppearanceTarget(scope, null, state.globalChatAppearance)
            ChatAppearanceScope.ASSISTANT -> {
                val assistant = state.assistants.firstOrNull { it.id == state.selectedAssistantId }
                if (assistant == null) {
                    showStatus("当前角色不可用")
                    null
                } else {
                    ChatAppearanceTarget(scope, assistant.id, assistant.appearance)
                }
            }
            ChatAppearanceScope.SESSION -> {
                val session = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
                if (session == null) {
                    showStatus("请先发送一条消息，再设置当前会话背景")
                    null
                } else {
                    ChatAppearanceTarget(scope, session.id, session.appearanceOverride ?: ChatAppearance())
                }
            }
        }
    }

    private fun currentAppearanceForTarget(target: ChatAppearanceTarget): ChatAppearance? {
        val state = _uiState.value
        return when (target.scope) {
            ChatAppearanceScope.GLOBAL -> state.globalChatAppearance
            ChatAppearanceScope.ASSISTANT -> state.assistants
                .firstOrNull { it.id == target.ownerId }
                ?.appearance
            ChatAppearanceScope.SESSION -> state.chatSessions
                .firstOrNull { it.id == target.ownerId }
                ?.appearanceOverride
        }
    }

    private fun persistChatAppearanceTarget(target: ChatAppearanceTarget, appearance: ChatAppearance?) {
        when (target.scope) {
            ChatAppearanceScope.GLOBAL -> {
                val value = appearance ?: ChatAppearance()
                if (value.isDefault) globalChatAppearanceStore.clear() else globalChatAppearanceStore.save(value)
            }
            ChatAppearanceScope.ASSISTANT -> {
                val ownerId = requireNotNull(target.ownerId)
                checkNotNull(assistantStore.updateAppearance(ownerId, appearance ?: ChatAppearance())) {
                    "角色已不存在"
                }
            }
            ChatAppearanceScope.SESSION -> {
                val ownerId = requireNotNull(target.ownerId)
                chatSessionStore.updateAppearance(ownerId, appearance)
            }
        }
    }

    private fun applyPersistedChatAppearance(target: ChatAppearanceTarget, appearance: ChatAppearance?) {
        uiStateStore.update { state ->
            when (target.scope) {
                ChatAppearanceScope.GLOBAL -> state.copy(globalChatAppearance = appearance ?: ChatAppearance())
                ChatAppearanceScope.ASSISTANT -> state.copy(
                    assistants = state.assistants.map { assistant ->
                        if (assistant.id == target.ownerId) {
                            assistant.copy(appearance = appearance ?: ChatAppearance())
                        } else {
                            assistant
                        }
                    }
                )
                ChatAppearanceScope.SESSION -> state.copy(
                    chatSessions = state.chatSessions.map { session ->
                        if (session.id == target.ownerId) session.copy(appearanceOverride = appearance) else session
                    }
                )
            }
        }
    }

    private suspend fun cleanupUnusedChatBackgrounds() {
        val state = _uiState.value
        val referenced = buildSet {
            state.globalChatAppearance.backgroundImagePath?.let(::add)
            state.assistants.mapNotNullTo(this) { it.appearance.backgroundImagePath }
            state.chatSessions.mapNotNullTo(this) { it.appearanceOverride?.backgroundImagePath }
        }
        withContext(Dispatchers.IO) { backgroundImageStore.cleanup(referenced) }
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
        hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
        showStatus = ::showStatus,
        queueWorldBookCleanup = ::queueWorldBookCleanup,
        cleanupPendingWorldBooksAfterOwnerCommit = ::cleanupPendingWorldBooksAfterOwnerCommit,
        selectAssistant = ::selectAssistant,
        persistChatSessions = ::persistChatSessions,
        chatAppearanceMutex = chatAppearanceMutex,
        cleanupUnusedChatBackgrounds = ::cleanupUnusedChatBackgrounds,
        currentModelGenerationProfileKey = { _uiState.value.modelGenerationProfileKey() },
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

    fun removeLocalImageModelRecord(modelId: String) {
        launchModelOperation("模型记录操作未完成") {
            if (activeImageGenerationModelId == modelId || activeLocalApiImageModelId == modelId) {
                showStatus("当前图片任务正在使用该模型，请结束后再移除记录。")
                return@launchModelOperation
            }
            val removed = _uiState.value.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            val success = localImageModelStore.removeModelRecord(modelId)
            settleAndApplyLocalImageModelCatalogUpdate(
                store = uiStateStore,
                localImageModelStore = localImageModelStore,
                context = getApplication(),
                managedRuntimeReadinessRefreshGate = managedRuntimeReadinessRefreshGate,
                statusMessage = if (success) "已移除目录记录：${removed?.displayName ?: "模型"}；文件仍保留。"
                    else "未找到本地图像模型记录。"
            )
        }
    }

    private suspend fun stageContentImportPreview(preview: ContentImportPreview) {
        contentImportCommitMutex.withLock {
            if (_uiState.value.contentImportCommitting) return
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
                if (_uiState.value.contentImportCommitting || _uiState.value.contentImportPreview?.id != id) return@withLock
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
                val preview = _uiState.value.contentImportPreview?.takeIf { it.id == id } ?: return@withLock
                if (_uiState.value.contentImportCommitting || _uiState.value.isGenerating) return@withLock
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
        hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
        currentOwnerIsCurrent = { owner.isCurrent(_uiState.value) },
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
        val valid = frozen?.let { runCatching { resolveCurrentLocalImageLoras(it) }.isSuccess } == true
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
        currentState = { _uiState.value },
        stageContentImportPreview = { stageContentImportPreview(it) },
        markLocalConversationContextInvalid = ::markLocalConversationContextInvalid,
        ownerIsCurrent = { it.isCurrent(_uiState.value) },
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
        isGenerating = { _uiState.value.isGenerating },
        showStatus = ::showStatus,
        captureContentImportOwner = ::captureContentImportOwner,
        importWorldBookOwned = ::importWorldBookOwned,
        readAttachmentText = ::readAttachmentText,
        uriString = uriString,
        scopeKind = scope,
        importOwner = importOwner
    )

    fun deleteWorldBook(worldBookId: String) =
        deleteWorldBookFor(knowledgeLibraryPort, uiStateStore, viewModelScope, { _uiState.value.knowledgeLibrarySnapshot() }, worldBookId)

    fun createKnowledgeBase(name: String, description: String = "") =
        createKnowledgeBaseFor(knowledgeLibraryPort, uiStateStore, viewModelScope, { _uiState.value.knowledgeLibrarySnapshot() }, name, description)

    fun importKnowledgeDocument(
        knowledgeBaseId: String,
        uriString: String,
        importOwner: ContentImportOwner? = null
    ) {
        if (_uiState.value.isGenerating) {
            showStatus("请先停止当前生成，再导入知识库文件")
            return
        }
        if (knowledgeBaseId in _uiState.value.knowledgeBaseImportingIds) {
            showStatus("该知识库正在导入文件，请稍候")
            return
        }
        val known = _uiState.value.knowledgeBases.any { it.id == knowledgeBaseId }
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
                    val name = displayNameForUri(uri)
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
        require(_uiState.value.knowledgeBases.any { it.id == baseId }) {
            "原知识库已删除，请重新选择知识库。"
        }
        val document = withContext(Dispatchers.IO) {
            knowledgeBaseStore.importDocument(baseId, preview.title, preview.rawSource, preview.source ?: "local")
        }
        val owner = preview.owner
        chatSessionPersistenceSequence.incrementAndGet()
        withContext(Dispatchers.IO) {
            chatSessionPersistenceMutex.withLock {
                val snapshot = _uiState.value
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
        if (owner.isCurrent(_uiState.value)) markLocalConversationContextInvalid()
        return true
    }

    fun setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) =
        setKnowledgeBaseSelectedFor(uiStateStore, { _uiState.value }, ::persistKnowledgeBaseBindings, knowledgeBaseId, selected)

    fun deleteKnowledgeBase(knowledgeBaseId: String) =
        deleteKnowledgeBaseFor(knowledgeLibraryPort, uiStateStore, viewModelScope, { _uiState.value.knowledgeLibrarySnapshot() }, knowledgeBaseId)

    fun updateReasoningMode(mode: ReasoningMode) =
        updateReasoningModeFor(
            _uiState.value,
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
        val target = _uiState.value.models.firstOrNull { it.id == modelId } ?: return
        val family = chatBackendFamilyForRuntime(target.runtime.storageValue)
        val current = loadModelGenerationParams(
            backend = ChatBackend.LOCAL,
            modelId = modelId,
            defaults = _uiState.value.params
        )
        val updated = current.withChatBackend(family, backendId)
        persistModelGenerationParams(ChatBackend.LOCAL, modelId, updated)
        if (_uiState.value.loadedModelId == modelId) {
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
        val state = _uiState.value
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
            _uiState.value.repoInput
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
            _uiState.value.loadedModelId,
            _uiState.value.selectedChatBackend
        )









    fun searchHubModels(reset: Boolean = true) =
        searchHubModelsFor(
            viewModelScope,
            uiStateStore,
            modelScopeClient,
            ::busy,
            ::fail,
            _uiState.value.hubQuery,
            _uiState.value.hubPage,
            _uiState.value.hubModels,
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
        modelImportAndDownloadCoordinator.download(remote, _uiState.value.loadedModelId)

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
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        if (hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
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
                    val current = _uiState.value
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
        if (hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
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
                            val current = _uiState.value
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
                threshold = loadContextCompressionThreshold(),
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
        val currentOwner = _uiState.value
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
                promptContextUsage = promptContextUsageFor(
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

    // R3-S33：提示词翻译预览端口具名适配器（容器工厂装配，真实依赖注入）。
    private val imagePromptTranslationPreviewPort: ImagePromptTranslationPreviewPort =
        container.createImagePromptTranslationPreviewPort(
            uiStateStore,
            { offlinePromptTranslationService }
        )

    suspend fun translateImagePromptPreview(prompt: String): String =
        translateImagePromptPreview(port = imagePromptTranslationPreviewPort, prompt = prompt)

    suspend fun translateImageNegativePromptPreview(prompt: String): String =
        translateImageNegativePromptPreview(port = imagePromptTranslationPreviewPort, prompt = prompt)

    private suspend fun translateImagePromptTextPreview(
        prompt: String,
        fieldLabel: String
    ): String = translateImagePromptTextPreview(
        port = imagePromptTranslationPreviewPort,
        prompt = prompt,
        fieldLabel = fieldLabel
    )

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

    /**
     * R3/C3-S14：端口具名化后，本成员只把参数交给容器适配器；决策体仍在
     * [SendChatImageRequestOperations.sendChatImageRequest]。
     */
    private fun sendChatImageRequest(
        visibleUserText: String,
        prompt: String,
        optionsOverride: LocalImageGenerationOptions? = null,
        promptEnvelope: PromptEnvelope? = null,
        contextTraceJson: String? = null
    ) {
        SendChatImageRequestOperations.sendChatImageRequest(
            port = sendChatImageRequestPort,
            visibleUserText = visibleUserText,
            prompt = prompt,
            optionsOverride = optionsOverride,
            promptEnvelope = promptEnvelope,
            contextTraceJson = contextTraceJson
        )
    }

    /** Delegates to [chatImageSubmissionBlockMessageFor]. Signature kept verbatim. */
    private fun chatImageSubmissionBlockMessage(state: MainUiState): String? =
        chatImageSubmissionBlockMessageFor(state, container.runtimeAdmission, localImageGenerationCoordinator)

    private suspend fun handleAssistantImageToolCalls(
        calls: List<ChatToolCall>,
        originalRequestMessages: List<ChatMessage>,
        paramsBeforeAssistant: GenerationParams,
        chatSessionId: String?,
        cloudModelId: String?,
        chatModelIdentity: String?,
        imageModelBinding: AssistantImageModelBinding?,
        chatConfig: CloudApiConfig?
    ): Boolean {
        // R3-S34：助手生图工具轮次端口具名适配器（容器工厂装配，出清 94 行局部匿名类与 7 处 this@VM）。
        val port = container.createAssistantImageToolTurnPort(
            store = uiStateStore,
            chatImageNegativePromptSupported = { chatImageNegativePromptSupported(it) },
            prepareAssistantImagePrompt = { prompt, config, params, ctx ->
                prepareAssistantImagePrompt(prompt, config, params, ctx)
            },
            prepareAssistantImageNegativePrompt = { prompt, config, params, ctx, preserve ->
                prepareAssistantImageNegativePrompt(prompt, config, params, ctx, preserve)
            },
            setPendingAssistantToolFailure = { setPendingAssistantToolFailure(it) },
            buildChatImageRetrySnapshot = { req, sId -> buildChatImageRetrySnapshot(req, sId) },
            attachAssistantImageRequestToTimeline = { sId, req -> attachAssistantImageRequestToTimeline(sId, req) },
            approveChatImageRequest = { approveChatImageRequest(it) }
        )
        return handleAssistantImageToolTurn(
            port = port,
            calls = calls,
            originalRequestMessages = originalRequestMessages,
            paramsBeforeAssistant = paramsBeforeAssistant,
            chatSessionId = chatSessionId,
            cloudModelId = cloudModelId,
            chatModelIdentity = chatModelIdentity,
            // The private binding stays private: only the fields the turn reads cross the port.
            // `authorizationIdentity` stays a provider so its read still happens at the
            // auto-approval gate, after the request and the pending turn were created.
            imageModelBinding = imageModelBinding?.let { binding ->
                AssistantImageToolModelBinding(
                    backend = binding.backend,
                    modelId = binding.modelId,
                    modelName = binding.modelName,
                    options = binding.options,
                    fingerprint = binding.fingerprint,
                    authorizationIdentity = { binding.authorizationIdentity() }
                )
            },
            chatConfig = chatConfig
        )
    }

    /**
     * Normalizes a tool-call negative branch without pretending it is a positive image request.
     * The bridge contract requires a positive branch, so a neutral subject is used only inside
     * the isolated translation request. Authored tool arguments fail closed when their Chinese
     * exclusion cannot be translated; a model-provided default may safely fall back to an empty
     * branch so the image backend can apply its own defaults.
     */
    private suspend fun prepareAssistantImageNegativePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage>,
        preserveAuthoredNegative: Boolean
    ): ChatImagePromptBridge.Result = prepareAssistantImageNegativePromptFor(
        prepareAssistantImagePrompt = ::prepareAssistantImagePrompt,
        prompt = prompt,
        chatConfig = chatConfig,
        params = params,
        conversationContext = conversationContext,
        preserveAuthoredNegative = preserveAuthoredNegative
    )

    /** Translate an assistant tool prompt with the exact chat connector that produced the call. */
    private suspend fun prepareAssistantImagePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage> = _uiState.value.messages
    ): ChatImagePromptBridge.Result = prepareAssistantImagePromptFor(
        store = uiStateStore,
        cloudChatProvider = cloudChatProvider,
        chatContextComposer = chatContextComposer,
        prompt = prompt,
        chatConfig = chatConfig,
        params = params,
        conversationContext = conversationContext
    )

    private fun setPendingAssistantToolFailure(message: String) =
        setPendingAssistantToolFailureFor(
            store = uiStateStore,
            persistChatSessions = ::persistChatSessions,
            message = message
        )

    private fun attachAssistantImageRequestToTimeline(
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    ) = attachAssistantImageRequestToTimelineFor(
        store = uiStateStore,
        persistChatSessions = ::persistChatSessions,
        chatSessionId = chatSessionId,
        request = request
    )


    private fun continueAssistantImageToolTurn(requestId: String, outputJson: String) {
        synchronized(assistantImageContinuationLock) {
            val current = findChatGeneratedImageRequest(requestId)?.second ?: return
            if (!current.canContinueAssistantImageTurn() ||
                !assistantImageContinuationsInFlight.add(requestId)
            ) return
            current
        }
        var reservation: UiGenerationReservation? = null
        var started = false
        try {
            val (sessionId, request) = findChatGeneratedImageRequest(requestId) ?: return
            val pending = pendingAssistantImageToolTurns[requestId]
                ?: restorePendingAssistantImageToolTurn(sessionId, request)
                ?: run {
                    applyChatGeneratedImageRequest(chatImageRequestPort,
                        sessionId,
                        requestId,
                        request.copy(
                            toolOutputJson = outputJson,
                            toolContinuationStatus = ChatImageToolContinuationStatus.COMPLETED,
                            toolContinuationStarted = true,
                            message = "图片已保存，但聊天上下文已变化，已跳过自动续答。你可以继续发送新消息。"
                        )
                    )
                    pendingAssistantImageToolTurns.remove(requestId)
                    return
                }
            val latest = findChatGeneratedImageRequest(requestId)?.second ?: request
            applyChatGeneratedImageRequest(chatImageRequestPort,
                sessionId,
                requestId,
                latest.copy(toolOutputJson = outputJson)
            )
            val state = _uiState.value
            if (state.activeChatSessionId != pending.chatSessionId) {
                showStatus("图片任务已结束；请回到原对话查看结果并继续聊天。")
                return
            }
            val cloudModelStillAvailable = state.cloudModels.any {
                it.id == pending.cloudModelId && it.kind == CloudModelKind.CHAT && it.configured
            }
            if (!cloudModelStillAvailable) {
                uiStateStore.update {
                    it.copy(statusMessage = "图片已处理，但原云端聊天模型不可用；请恢复配置后继续角色回复。")
                }
                return
            }
            if (state.isGenerating) {
                uiStateStore.update {
                    it.copy(statusMessage = "图片已完成；当前聊天仍有生成任务，请完成或停止后再继续角色回复。")
                }
                return
            }
            // Reserve foreground UI ownership before persisting RUNNING. A background completion
            // remains resumable and is retried by onAppForegrounded instead of being lost.
            reservation = reserveUiGenerationStart()
            if (reservation == null) {
                uiStateStore.update {
                    it.copy(statusMessage = "图片已完成；返回 MCA 后会继续角色回复。")
                }
                return
            }
            val call = pending.toolCalls.firstOrNull { it.id == pending.primaryToolCallId }
            if (call == null) {
                applyChatGeneratedImageRequest(chatImageRequestPort,
                    sessionId,
                    requestId,
                    latest.copy(
                        toolOutputJson = outputJson,
                        toolContinuationStatus = ChatImageToolContinuationStatus.FAILED,
                        toolContinuationStarted = false,
                        message = "找不到本次工具调用记录，无法恢复角色回复。"
                    )
                )
                return
            }
            applyChatGeneratedImageRequest(chatImageRequestPort,
                pending.chatSessionId,
                requestId,
                latest.copy(
                    toolOutputJson = outputJson,
                    toolContinuationStatus = ChatImageToolContinuationStatus.RUNNING,
                    toolContinuationStarted = true,
                    message = latest.assistantTranscriptText()
                )
            )
            val continuationState = _uiState.value
            val messages = continuationState.messages + ChatMessage(Role.ASSISTANT, "")
            val assistantSnapshot = continuationState.activeAssistantSnapshot()
                ?: continuationState.selectedAssistant()?.toConversationSnapshot()
            val sessions = continuationState.chatSessions.upsertSession(
                sessionId = pending.chatSessionId,
                messages = messages,
                assistantId = assistantSnapshot?.assistantId ?: continuationState.selectedAssistantId,
                assistantSnapshot = assistantSnapshot,
                modelMode = ChatBackend.CLOUD.bindingValue(),
                modelId = pending.cloudModelId
            )
            uiStateStore.update {
                it.copy(
                    input = "",
                    messages = messages,
                    chatSessions = sessions,
                    isGenerating = true,
                    statusMessage = null
                ).withGenerationProgressCleared()
            }
            persistChatSessions(sessions)
            started = startGeneration(
                requestMessages = pending.originalRequestMessages,
                reservation = reservation,
                toolExchanges = listOf(ChatToolExchange(call, outputJson)),
                paramsOverride = pending.params,
                offerAssistantImageTool = false,
                chatModelIdOverride = pending.cloudModelId,
                continuationRequestId = requestId
            )
            if (!started) {
                applyChatGeneratedImageRequest(chatImageRequestPort,
                    pending.chatSessionId,
                    requestId,
                    latest.copy(
                        toolOutputJson = outputJson,
                        toolContinuationStatus = ChatImageToolContinuationStatus.FAILED,
                        toolContinuationStarted = false,
                        message = "角色回复未能启动，请检查云端模型后重试。"
                    )
                )
                removeChatImageContinuationPlaceholder(pending.chatSessionId)
            }
        } finally {
            if (!started) reservation?.let { chatRuns.cancelPending(it) }
            synchronized(assistantImageContinuationLock) {
                assistantImageContinuationsInFlight.remove(requestId)
            }
        }
    }

    private fun finishAssistantImageContinuation(
        requestId: String,
        completed: Boolean,
        failureMessage: String
    ) {
        val owner = findChatGeneratedImageRequest(requestId) ?: return
        val (sessionId, request) = owner
        if (request.toolContinuationStatus != ChatImageToolContinuationStatus.RUNNING) return
        val updated = request.copy(
            toolContinuationStatus = if (completed) {
                ChatImageToolContinuationStatus.COMPLETED
            } else {
                ChatImageToolContinuationStatus.FAILED
            },
            toolContinuationStarted = completed,
            message = if (completed) request.assistantTranscriptText() else failureMessage
        )
        applyChatGeneratedImageRequest(chatImageRequestPort, sessionId, requestId, updated)
        if (completed) {
            pendingAssistantImageToolTurns.remove(requestId)
        }
    }

    private fun removeChatImageContinuationPlaceholder(chatSessionId: String) {
        var updatedSessions: List<ChatSessionRecord>? = null
        uiStateStore.update { state ->
            if (state.activeChatSessionId != chatSessionId) return@update state
            val last = state.messages.lastOrNull()
            if (last?.role != Role.ASSISTANT || last.content.isNotBlank() ||
                last.generatedImageRequest != null
            ) return@update state
            val messages = state.messages.dropLast(1)
            val owner = state.chatSessions.firstOrNull { it.id == chatSessionId } ?: return@update state
            val sessions = state.chatSessions.map { session ->
                if (session.id == chatSessionId) {
                    owner.copy(messages = messages, updatedAt = System.currentTimeMillis())
                } else session
            }.sortedForHistory()
            updatedSessions = sessions
            state.copy(
                messages = messages,
                chatSessions = sessions,
                isGenerating = false
            ).withGenerationProgressCleared()
        }
        updatedSessions?.let(::persistChatSessions)
    }

    /** Rebuild the volatile continuation envelope from the durable chat timeline after process death. */
    private fun restorePendingAssistantImageToolTurn(
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    ): PendingAssistantImageToolTurn? {
        if (request.origin != ChatGeneratedImageOrigin.ASSISTANT_TOOL ||
            !request.canContinueAssistantImageTurn()
        ) return null
        val callId = request.toolCallId?.takeIf(String::isNotBlank) ?: return null
        val modelId = request.toolChatModelId?.takeIf(String::isNotBlank) ?: return null
        val params = request.toolParamsJson?.let { encoded ->
            runCatching { GenerationParams.fromJson(JSONObject(encoded)) }.getOrNull()
        } ?: return null
        val state = _uiState.value
        val session = state.chatSessions.firstOrNull { it.id == chatSessionId } ?: return null
        val messages = if (state.activeChatSessionId == chatSessionId) state.messages else session.messages
        val requestIndex = messages.indexOfFirst { it.generatedImageRequest?.id == request.id }
        if (requestIndex < 0) return null
        val requestMessageCount = request.toolRequestMessageCount ?: requestIndex
        if (requestMessageCount !in 0..requestIndex) return null
        val originalMessages = messages.take(requestMessageCount)
        request.toolRequestMessagesFingerprint?.let { expected ->
            if (chatImageToolContextFingerprint(originalMessages) != expected) return null
        }
        val call = ChatToolCall(
            id = callId,
            name = CHAT_IMAGE_TOOL_NAME,
            argumentsJson = request.toolArgumentsJson ?: JSONObject().put("prompt", request.prompt).toString()
        )
        val pending = PendingAssistantImageToolTurn(
            requestId = request.id,
            chatSessionId = chatSessionId,
            originalRequestMessages = originalMessages,
            params = params,
            cloudModelId = modelId,
            toolCalls = listOf(call),
            primaryToolCallId = call.id,
            initialToolOutputs = emptyMap()
        )
        pendingAssistantImageToolTurns[request.id] = pending
        return pending
    }

    /** Delegates to [completeAssistantImageToolTurnForJobFor]. Signature kept verbatim. */
    private fun completeAssistantImageToolTurnForJob(jobId: String) =
        completeAssistantImageToolTurnForJobFor(
            uiStateStore,
            chatImageRequestPort,
            { findChatGeneratedImageRequest(it) },
            { requestId, output -> continueAssistantImageToolTurn(requestId, output) },
            jobId
        )

    /** R3-S35：聊天生图审批端口具名适配器（容器工厂装配，出清 66 行匿名类与 9 处 this@VM）。 */
    private val chatImageRequestApprovalPort: ChatImageRequestApprovalPort =
        container.createChatImageRequestApprovalPort(
            store = uiStateStore,
            imageGenerationOwnerProvider = { imageGenerationOwner },
            chatImageRequestPortProvider = { chatImageRequestPort },
            buildChatImageRetrySnapshot = { req, sId -> buildChatImageRetrySnapshot(req, sId) },
            continueAssistantImageToolTurn = { reqId, json -> continueAssistantImageToolTurn(reqId, json) }
        )

    fun retryChatImageGeneration(requestId: String) =
        retryChatImageGeneration(port = chatImageRequestApprovalPort, requestId = requestId)

    fun approveChatImageRequest(requestId: String) =
        approveChatImageRequest(port = chatImageRequestApprovalPort, requestId = requestId)

    fun rejectChatImageRequest(requestId: String) =
        rejectChatImageRequest(port = chatImageRequestApprovalPort, requestId = requestId)

    /** Resumes a terminal assistant tool turn if the process stopped before the follow-up reply. */
    fun continueAssistantImageTurn(requestId: String) =
        continueAssistantImageTurnFor(
            store = uiStateStore,
            findRequest = ::findChatGeneratedImageRequest,
            continueTurn = ::continueAssistantImageToolTurn,
            requestId = requestId
        )

    /** Delegates to [cancelChatImageGenerationFor]. Signature kept verbatim. */
    fun cancelChatImageGeneration(requestId: String) =
        cancelChatImageGenerationFor(
            uiStateStore,
            { findChatGeneratedImageRequest(it) },
            { rejectChatImageRequest(it) },
            { cancelImageGenerationForJob(it) },
            requestId
        )

    private fun findChatGeneratedImageRequest(
        requestId: String
    ): Pair<String, ChatGeneratedImageRequest>? =
        findChatGeneratedImageRequestIn(_uiState.value, requestId)

    private fun hasUnresolvedAssistantImageToolTurn(chatSessionId: String?): Boolean {
        if (chatSessionId.isNullOrBlank()) return false
        val state = _uiState.value
        val session = state.chatSessions.firstOrNull { it.id == chatSessionId }
        val messages = if (state.activeChatSessionId == chatSessionId) state.messages else session?.messages.orEmpty()
        return messages.any { message ->
            message.generatedImageRequest?.let { request ->
                request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                    request.toolContinuationStatus != ChatImageToolContinuationStatus.COMPLETED
            } == true
        } || pendingAssistantImageToolTurns.values.any { pending ->
            pending.chatSessionId == chatSessionId
        }
    }

    private fun hasActiveAssistantImageExecution(chatSessionId: String?): Boolean =
        hasActiveAssistantImageExecutionIn(_uiState.value, chatSessionId)
    private fun isChatImageRequestActive(request: ChatGeneratedImageRequest): Boolean =
        request.status in setOf(
            ChatGeneratedImageStatus.AWAITING_APPROVAL,
            ChatGeneratedImageStatus.QUEUED,
            ChatGeneratedImageStatus.GENERATING
        ) || request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING

    fun regenerateLastResponse() {
        val state = _uiState.value
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
            pendingAssistantImageToolTurns = pendingAssistantImageToolTurns,
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
            pendingAssistantImageToolTurns = pendingAssistantImageToolTurns,
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
        newChatForAssistant(_uiState.value.selectedAssistant())
    }

    private fun newChatForAssistant(
        assistant: AssistantRecord?,
        bindDefaultModel: Boolean = false
    ): Boolean {
        if (rejectWhileConversationMutationInProgress()) return false
        val created = newChatForAssistantFor(
            store = uiStateStore,
            rejectWhileConversationMutationInProgress = { rejectWhileConversationMutationInProgress() },
            hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
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
            hasActiveAssistantImageExecution = { hasActiveAssistantImageExecution(it) },
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
            hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
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
            hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
            manualContextCompressionRequests = manualContextCompressionRequests,
            pendingAssistantImageToolTurns = pendingAssistantImageToolTurns,
            queueWorldBookCleanup = ::queueWorldBookCleanup,
            persistConversationMutation = { remaining, rollback ->
                persistConversationMutation(remaining, rollback)
            },
            chatImageToolAuthorizationStore = chatImageToolAuthorizationStore,
            cleanupUnusedChatBackgrounds = { chatAppearanceMutex.withLock { cleanupUnusedChatBackgrounds() } },
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
            hasActiveAssistantImageExecution = ::hasActiveAssistantImageExecution,
            manualContextCompressionRequests = manualContextCompressionRequests,
            queueWorldBookCleanup = ::queueWorldBookCleanup,
            pendingAssistantImageToolTurns = pendingAssistantImageToolTurns,
            persistConversationMutation = { sessions, rollback -> persistConversationMutation(sessions, rollback) },
            chatImageToolAuthorizationStore = chatImageToolAuthorizationStore
        )
    }

    fun setPersistentPrefixCacheEnabled(enabled: Boolean) {
        val current = _uiState.value.persistentPrefixCacheEnabled
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
            val enabledNow = _uiState.value.persistentPrefixCacheEnabled
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
            manualContextCompressionRequests.clear(_uiState.value.activeChatSessionId)
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
        resumePendingAssistantImageToolTurnIfReady()
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

            val stateBeforeProbe = _uiState.value
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
                resumePendingAssistantImageToolTurnIfReady()
            }
        }
    }

    private fun resumePendingAssistantImageToolTurnIfReady() {
        val state = _uiState.value
        val sessionId = state.activeChatSessionId ?: return
        val requestId = state.messages.asSequence()
            .mapNotNull(ChatMessage::generatedImageRequest)
            .firstOrNull { request ->
                request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                    request.toolContinuationStatus == ChatImageToolContinuationStatus.NOT_STARTED &&
                    request.status in setOf(
                        ChatGeneratedImageStatus.DONE,
                        ChatGeneratedImageStatus.FAILED,
                        ChatGeneratedImageStatus.CANCELLED,
                        ChatGeneratedImageStatus.INTERRUPTED
                    )
            }
            ?.id
            ?: return
        continueAssistantImageTurn(requestId)
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
            apiSmoke = { runLoopbackApiStreamSmoke() },
            engineSmoke = { params -> runLoopbackEngineStreamSmoke(params) }
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
        agentTuningCoordinator.cancelAgentTuning(::cancelPersistedTuningJob, uiStateStore, viewModelScope)

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
        val restEnabled = if (enabled) _uiState.value.restEnabled else false
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
        val apiEnabled = enabled || _uiState.value.apiEnabled
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
        localImageWorkerClient.close()
        isolatedLocalChatRunners.close()
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
            localApiPayloadPortProvider = { localApiPayloadPort },
            benchmarkRunner = benchmarkRunner,
            buildRecommendationAction = ::buildRecommendation,
            generateLocalApiImageAction = ::generateLocalApiImage,
        )

    private val localApiTuningControlPort: LocalApiTuningControlPort =
        container.createLocalApiTuningControlPort(
            store = uiStateStore,
            tuningOwner = tuningOwner,
            localApiModelMatchesRef = ::localApiModelMatches,
            localApiPerformancePreferenceRef = ::localApiPerformancePreference,
            localApiTuningRejectedRef = ::localApiTuningRejected,
            localApiTuningConflictRef = ::localApiTuningConflict,
            localApiExclusiveLifecycleConflictRef = ::localApiExclusiveLifecycleConflict,
            assignActiveTuningJobIdRef = { activeTuningJobId = it },
            tuningJobPublicJsonRef = ::tuningJobPublicJson,
            setTuningCancelRequestedRef = { agentTuningCoordinator.adaptiveTuningCancelRequested.set(it) },
            setTuningPauseRequestedRef = { agentTuningCoordinator.adaptiveTuningPauseRequested.set(it) },
            launchTuningApplyRef = { pending ->
                adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        applyPendingRuntimeTransaction(pending)
                    } finally {
                        activeTuningJobId = null
                        adaptiveTuningJob = null
                        agentTuningCoordinator.adaptiveTuningCancelRequested.set(false)
                        agentTuningCoordinator.adaptiveTuningPauseRequested.set(false)
                    }
                }
            },
            launchTuningRollbackRef = { identity, model ->
                adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rollbackCommittedRuntimeProfile(identity, model)
                    } finally {
                        adaptiveTuningJob = null
                    }
                }
            }
        )

    private val chatLocalApiProviderPort: ChatLocalApiProviderPort =
        container.createChatLocalApiProviderPort(
            engine = engine,
            store = uiStateStore,
            cancelActiveLocalApiImageGenerationAction = ::cancelActiveLocalApiImageGeneration,
            localApiPayloadPortProvider = { localApiPayloadPort },
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
            removePendingAssistantPlaceholderRef = ::removePendingAssistantPlaceholder,
            ensureVisibleAssistantReplyAfterCompletionRef = ::ensureVisibleAssistantReplyAfterCompletion,
            applyWebSearchAnswerGuardsToLastAssistantRef = ::applyWebSearchAnswerGuardsToLastAssistant,
            recordWebSearchCitationAuditRef = ::recordWebSearchCitationAudit,
            attachWebSearchEvidenceToPendingAssistantRef = ::attachWebSearchEvidenceToPendingAssistant,
            attachContextAssemblyTraceRef = ::attachContextAssemblyTrace,
            promptContextUsageForRef = ::promptContextUsageFor,
            appendWebSearchDiagnosticRef = ::appendWebSearchDiagnostic,
            recordCompletedCharacterMemoryTurnRef = ::recordCompletedCharacterMemoryTurn,
            summarizeContextForGenerationRef = ::summarizeContextForGeneration,
            ensureLocalConversationContextInvalidatedRef = ::ensureLocalConversationContextInvalidated,
            loadContextCompressionThresholdRef = ::loadContextCompressionThreshold,
            handleAssistantImageToolCallsRef = ::handleAssistantImageToolCalls,
            finishAssistantImageContinuationRef = ::finishAssistantImageContinuation,
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
                sendChatImageRequest(
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

/** Delegates to [settleWebSearchAnswerGuards]. Signature kept verbatim. */
    fun applyWebSearchAnswerGuardsToLastAssistant(
        generationRunId: Long? = null
    ): WebSearchCitationAudit? {
        return settleWebSearchAnswerGuards(port = chatTerminalSettlementPort, generationRunId = generationRunId)
    }

/** Delegates to [settleVisibleAssistantReply]. Signature kept verbatim. */
    fun ensureVisibleAssistantReplyAfterCompletion(generationRunId: Long? = null) {
        settleVisibleAssistantReply(port = chatTerminalSettlementPort, generationRunId = generationRunId)
    }

/** Delegates to [settleWebSearchCitationAudit]. Signature kept verbatim. */
    fun recordWebSearchCitationAudit(
        recordId: String?,
        audit: WebSearchCitationAudit,
        generationRunId: Long? = null
    ) {
        settleWebSearchCitationAudit(port = chatTerminalSettlementPort, recordId = recordId, audit = audit, generationRunId = generationRunId)
    }

/** Delegates to [settlePendingAssistantPlaceholder]. Signature kept verbatim. */
    fun removePendingAssistantPlaceholder(generationRunId: Long? = null) {
        settlePendingAssistantPlaceholder(port = chatTerminalSettlementPort, generationRunId = generationRunId)
    }

/** Delegates to [settleWebSearchEvidenceAttachment]. Signature kept verbatim. */
    fun attachWebSearchEvidenceToPendingAssistant(
        sources: List<ChatSourceReference>,
        trace: ChatWebSearchTrace?,
        generationRunId: Long? = null
    ) {
        settleWebSearchEvidenceAttachment(port = chatTerminalSettlementPort, sources = sources, trace = trace, generationRunId = generationRunId)
    }



    /**
     * Drains only the output owned by the UI run that process backgrounding invalidated. A newer
     * foreground request advances the sequence and leaves its buffer untouched.
     */

    private fun GenerateEvent.Error.isConfigurationActionError(): Boolean = code in setOf(
        "model_reload_required",
        "model_reload_required_authorized",
        "execution_override_forbidden",
        "model_behavior_override_forbidden",
        "active_profile_drift",
        "model_mismatch"
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

    private fun promptContextUsageFor(
        plan: ChatRuntimeContextPlan,
        admission: com.muyuchat.core.engine.ContextWindowAdmission,
        params: GenerationParams
    ): PromptContextUsage {
        val historyRetention = admission.messageRetention.filter { it.role != Role.SYSTEM }
        return PromptContextUsage(
            retainedMessageCount = historyRetention.count { it.retained },
            trimmedMessageCount = historyRetention.count { !it.retained },
            roleTokens = estimateLocalPromptTokens(params.systemPrompt),
            worldBookTokens = plan.worldBook.estimatedTokens.coerceAtLeast(0),
            knowledgeTokens = plan.knowledge.estimatedTokens.coerceAtLeast(0),
            totalEstimatedTokens = admission.admittedUsage.estimatedTokens.coerceAtLeast(0L),
            messageRetention = historyRetention,
            selectedWorldBookEntryIds = plan.worldBook.selectedEntryIds,
            skippedWorldBookEntryIds = plan.worldBook.skippedEntryIds,
            selectedKnowledgeChunkIds = plan.knowledge.chunks.map { it.id },
            skippedKnowledgeChunkIds = plan.knowledge.skippedChunkIds
        )
    }

    private fun reconciledChatSessionsAfterSave(snapshot: List<ChatSessionRecord>): List<ChatSessionRecord> =
        reconciledChatSessionsAfterSaveFor(container.chatSessionPersistenceState, chatSessionStore, snapshot)

    private fun publishReconciledChatSessions(requested: List<ChatSessionRecord>, persisted: List<ChatSessionRecord>) =
        publishReconciledChatSessionsInto(uiStateStore, chatRuns, requested, persisted)

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
            sessions = _uiState.value.chatSessions,
            knowledgeBinding = sessionId to knowledgeBaseIds
        )
    }

    private fun persistFiles(files: List<FileAssetRecord> = _uiState.value.files) =
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
        state = _uiState.value,
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


    private fun displayNameForUri(uri: Uri): String =
        displayNameForUri(getApplication<Application>().contentResolver, uri)

    private fun isImageAttachment(uri: Uri, name: String): Boolean =
        isImageAttachment(getApplication<Application>().contentResolver, uri, name)

    private fun readAttachmentText(uri: Uri, maxBytes: Int = 64 * 1024): Pair<String, Boolean> =
        readAttachmentText(getApplication<Application>().contentResolver, uri, maxBytes)

    private fun createFileAsset(
        uri: Uri,
        displayName: String,
        text: String,
        truncated: Boolean,
        source: String,
        chatSessionId: String? = null
    ): FileAssetRecord = createFileAsset(
        contentResolver = getApplication<Application>().contentResolver,
        uri = uri,
        displayName = displayName,
        text = text,
        truncated = truncated,
        source = source,
        chatSessionId = chatSessionId
    )

    private suspend fun importImageAsset(
        uri: Uri,
        displayName: String,
        source: String,
        prompt: String = "",
        chatSessionId: String? = null
    ): ImageAssetRecord = importImageAsset(
        contentResolver = getApplication<Application>().contentResolver,
        imageAssetWriteMutex = imageAssetWriteMutex,
        imageAssetDirectory = imageAssetDirectory,
        uri = uri,
        displayName = displayName,
        source = source,
        prompt = prompt,
        chatSessionId = chatSessionId
    )



    /** Delegates to [createCloudGeneratedImageAssetsFor]. Signature kept verbatim. */
    private suspend fun createCloudGeneratedImageAssets(
        request: CloudImageRequest,
        config: CloudApiConfig,
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata
    ): List<ImageAssetRecord> =
        createCloudGeneratedImageAssetsFor(
            cloudImageProvider,
            imageAssetWriteMutex,
            imageAssetDirectory,
            request,
            config,
            chatSessionId,
            generationMetadata
        )

    // R3-S30：本地生图资产生成端口的具名适配器（真实客户端经网关注入；worker 代际经 holder）。
    private val createLocalGeneratedImageAssetPort: CreateLocalGeneratedImageAssetPort =
        container.createCreateLocalGeneratedImageAssetPort(uiStateStore, application)

    private suspend fun createLocalGeneratedImageAsset(
        requestId: String,
        prompt: String,
        model: LocalImageModelRecord,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata,
        batchLineage: ImageGenerationBatchLineage? = null,
        outputLineages: List<ImageGenerationBatchLineage>,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): List<ImageAssetRecord> =
        CreateLocalGeneratedImageAssetOperations.createLocalGeneratedImageAsset(
            port = createLocalGeneratedImageAssetPort,
            requestId = requestId,
            prompt = prompt,
            model = model,
            options = options,
            inputDraft = inputDraft,
            chatSessionId = chatSessionId,
            generationMetadata = generationMetadata,
            batchLineage = batchLineage,
            outputLineages = outputLineages,
            onProgress = onProgress
        )

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
            resolveCurrentLocalImageLorasRef1 = { resolveCurrentLocalImageLoras(it) },
            resolveCurrentLocalImageLorasRef2 = { s, r -> resolveCurrentLocalImageLoras(s, r) },
            resolveCurrentLocalImageTextualInversionIdsRef = { resolveCurrentLocalImageTextualInversionIds(it) },
            prepareLocalImagePromptExecutionRef = { m, p, o, c -> prepareLocalImagePromptExecution(m, p, o, c) },
            prepareLocalImageRuntimeMemoryRef = { prepareLocalImageRuntimeMemory() },
            executionWithQnnVerificationEvidenceRef = { m, r, o, req, att, ent ->
                executionWithQnnVerificationEvidence(m, r, o, req, att, ent)
            },
        )

    private suspend fun generateLocalApiImage(requestId: String, body: String): String =
        generateLocalApiImageOperations(
            requestId = requestId,
            body = body,
            port = localApiImageGenerationPort
        )

    /** Records actual worker execution only; this evidence is never an admission certificate. */
    /** Delegates to [recordLocalImageExecutionOutcomeFor]. Signature kept verbatim. */
    private fun recordLocalImageExecutionOutcome(
        model: LocalImageModelRecord,
        failureMessage: String?
    ) = recordLocalImageExecutionOutcomeFor(
        localImageModelStore, managedRuntimeReadinessRefreshGate, uiStateStore, model, failureMessage
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
        "http://$host:$REST_PORT/v1"

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
        apiUrl(detectLanIpAddress() ?: "本机局域网IP")


    private fun mergeUserRequestedExecutionProfile(
        base: ModelExecutionProfile,
        requested: ModelExecutionProfile,
        authoritativeFields: Set<String>
    ): ModelExecutionProfile =
        mergeUserRequestedIntoProfile(base, requested, authoritativeFields)

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

    private suspend fun runLoopbackApiStreamSmoke(): LocalApiStreamSmokeResult =
        runLoopbackApiStreamSmokeFor()

    private suspend fun runLoopbackEngineStreamSmoke(params: GenerationParams): LocalApiStreamSmokeResult =
        runLoopbackEngineStreamSmokeFor(params)

    private suspend fun recoverInterruptedRuntimeProfiles() =
        recoverInterruptedRuntimeProfilesFor(runtimeProfileStore, pendingRuntimeRecoveries, uiStateStore)




    /** Delegates to [persistBootstrapProfileFor]. Signature kept verbatim. */
    private suspend fun persistBootstrapProfile(
        profile: ModelExecutionProfile,
        sourceSummary: String
    ) = persistBootstrapProfileFor(runtimeProfileStore, engine, profile, sourceSummary)

    /** Delegates to [stageBootstrapProfileFor]. Signature kept verbatim. */
    private suspend fun stageBootstrapProfile(
        transactionId: String,
        profile: ModelExecutionProfile,
        rollbackTargetProfileId: String?
    ) = stageBootstrapProfileFor(runtimeProfileStore, transactionId, profile, rollbackTargetProfileId)

    /** Delegates to [recordBootstrapProbeMeasurementFor]. Signature kept verbatim. */
    private suspend fun recordBootstrapProbeMeasurement(
        result: TuningProbeWorkerProtocol.Result
    ) = recordBootstrapProbeMeasurementFor(runtimeProfileStore, result)










    private fun localApiModelMatches(
        requestedModel: String,
        state: MainUiState,
        model: ModelManifest
    ): Boolean {
        val requested = requestedModel.trim()
        return requested.isNotEmpty() && (
            requested == state.loadedModelId ||
                requested == state.loadedModelName ||
                requested == model.id ||
                requested == model.fileName ||
                requested == model.displayName ||
                requested == localApiPublicModelId(model.displayName, model.id)
            )
    }

    /**
     * Durable, app-owned idempotency journal for Local API control mutations.
     *
     * Only a SHA-256 of the caller key, the server-supplied request fingerprint, and the already
     * redacted structured response are persisted. A crash after the pending marker is committed
     * fails closed on replay instead of executing a possibly completed lifecycle mutation twice.
     */
    private fun runDurableLocalApiControl(
        idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext,
        operation: () -> com.muyuchat.api.local.LocalApiControlResult
    ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControlFor(
        preferences = getApplication<Application>().getSharedPreferences(
            "mca_local_api_idempotency_journal",
            Context.MODE_PRIVATE
        ),
        lock = localApiIdempotencyJournalLock,
        idempotency = idempotency,
        localApiTuningRejected = ::localApiTuningRejected,
        operation = operation
    )


    private fun localApiPerformancePreference(value: String?): UserPreference? {
        if (value.isNullOrBlank()) return null
        val mode = when (value.trim().lowercase(Locale.ROOT).replace("-", "_").replace(" ", "_")) {
            "balanced" -> PerformanceMode.Balanced
            "speed" -> PerformanceMode.Speed
            "quality" -> PerformanceMode.Quality
            "long_context", "longcontext" -> PerformanceMode.LongContext
            "power", "power_save", "powersave" -> PerformanceMode.PowerSave
            else -> return null
        }
        return _uiState.value.preference.copy(mode = mode)
    }

    private fun localApiExclusiveLifecycleConflict(
        action: String,
        allowedStagedJobId: String? = null
    ): com.muyuchat.api.local.LocalApiControlResult.Rejected? {
        val state = _uiState.value
        val selectedStagedJobIsAllowed = allowedStagedJobId != null &&
            (activeTuningJobId == null || activeTuningJobId == allowedStagedJobId) &&
            state.tuningJobState in setOf(AgentTuningJobState.PAUSED, AgentTuningJobState.RUNNING) &&
            adaptiveTuningJob?.isActive != true
        val tuningConflict = !selectedStagedJobIsAllowed && (
            adaptiveTuningJob?.isActive == true ||
                state.tuningJobState in setOf(
                    AgentTuningJobState.QUEUED,
                    AgentTuningJobState.RUNNING,
                    AgentTuningJobState.PAUSED,
                    AgentTuningJobState.CANCELING,
                    AgentTuningJobState.VALIDATING,
                    AgentTuningJobState.RECOVERING
                )
            )
        val engineConflict = state.engineLifecycle != AgentEngineLifecycle.READY ||
            state.busy ||
            state.isGenerating
        if (!tuningConflict && !engineConflict) return null
        return localApiTuningConflict(
            action = action,
            activeJobId = activeTuningJobId,
            message = "运行时正在执行独占生命周期操作，不能并发执行 tuning $action。"
        )
    }

    private fun localApiTuningConflict(
        action: String,
        activeJobId: String?,
        message: String
    ): com.muyuchat.api.local.LocalApiControlResult.Rejected = localApiTuningRejected(
        httpStatus = 409,
        code = "tuning_${action}_conflict",
        message = message,
        details = JSONObject()
            .put("activeJobId", activeJobId)
            .put("tuningJobState", _uiState.value.tuningJobState.name.lowercase())
            .put("engineLifecycle", _uiState.value.engineLifecycle.name.lowercase())
            .put("activeProfileId", engine.activeExecutionProfile()?.profileId)
    )

    private fun localApiTuningRejected(
        httpStatus: Int,
        code: String,
        message: String,
        details: JSONObject = JSONObject()
    ): com.muyuchat.api.local.LocalApiControlResult.Rejected =
        com.muyuchat.api.local.LocalApiControlResult.Rejected(
            httpStatus = httpStatus,
            code = code,
            message = message,
            detailsJson = details.toString(),
            retryAfterMs = if (httpStatus == 409) 1_000L else 0L
        )

    private fun tuningJobPublicJson(job: TuningJobEntity): JSONObject = JSONObject()
        .put("jobId", job.jobId)
        .put("state", job.state.lowercase(Locale.ROOT))
        .put("phase", job.phase)
        .put("candidateProfileId", job.candidateProfileId)
        .put("autoApply", job.autoApplyLoadChanges)
        .put("cancellationRequested", job.cancellationRequested)
        .put("failureCode", job.failureCode)
        .put("failureSummary", job.failureSummary)
        .put("createdAt", job.createdAt)
        .put("updatedAt", job.updatedAt)
        .put("lastHeartbeatAt", job.lastHeartbeatAt)

    private suspend fun cancelPersistedTuningJob(jobId: String): TuningJobEntity {
        var canceled = runtimeProfileStore.requestTuningJobCancellation(jobId)
        engine.stopGeneration()
        if (adaptiveTuningJob?.isActive != true) {
            val pending = runCatching { runtimeProfileStore.pendingTransaction(canceled.identityKey) }.getOrNull()
            if (pending?.journal?.jobId == jobId) {
                val recovery = runtimeProfileStore.rejectCandidate(
                    transactionId = pending.journal.transactionId,
                    failureStage = "USER_CANCEL",
                    failureCode = "USER_CANCELED",
                    failureSummary = "用户取消了等待应用的 staged candidate。"
                )
                recovery.rollbackProfileId?.let { rollbackProfileId ->
                    runtimeProfileStore.completeRecovery(
                        transactionId = pending.journal.transactionId,
                        restoredProfileId = rollbackProfileId
                    )
                }
                canceled = runtimeProfileStore.tuningJob(jobId) ?: canceled
                uiStateStore.update { it.copy(pendingProfile = null) }
            } else if (canceled.state !in setOf(
                    PersistedTuningJobState.SUCCEEDED.name,
                    PersistedTuningJobState.FAILED.name
                )
            ) {
                canceled = runtimeProfileStore.transitionTuningJob(
                    jobId = jobId,
                    state = PersistedTuningJobState.FAILED,
                    phase = "USER_CANCELED",
                    failureCode = "USER_CANCELED",
                    failureSummary = "用户取消了调优任务。"
                )
            }
            if (activeTuningJobId == jobId) activeTuningJobId = null
        }
        return canceled
    }

    /**
     * The ViewModel side of [LocalApiPayloadPort]. Eight members: the state the projections render,
     * the tuning flags they report, the two engine reads, the profile document, and the persisted
     * runtime summaries. No mutex, job, scope or atomics cross this boundary -- the request flags are
     * read through functions at render time.
     */
    // R3/C3-S2（插队裁定 2）：Local API payload 端口具名化——容器工厂构造（真实依赖），匿名实现删除。
    private val localApiPayloadPort = container.createLocalApiPayloadPort(uiStateStore, engine, tuningOwner)




    /**
     * Delegates to [localApiModelRuntimeStatesJson]. The blocking hop stays here: the projection is a
     * suspend function, and how the store is entered is the caller's decision.
     */
    private fun modelRuntimeStatesJson(): String = runBlocking(Dispatchers.IO) {
        localApiModelRuntimeStatesJson(localApiPayloadPort)
    }

    private fun runTuningControl(
        jobId: String,
        action: String,
        operation: suspend () -> TuningJobEntity
    ): com.muyuchat.api.local.LocalApiControlResult = runCatching {
        val job = runBlocking(Dispatchers.IO) { operation() }
        uiStateStore.update { state ->
            state.copy(
                tuningJobState = agentJobState(job),
                tuningPhase = job.phase,
                statusMessage = "Local API 已请求调优任务 ${action}。"
            )
        }
        com.muyuchat.api.local.LocalApiControlResult.Success(localApiTuningStateJson(localApiPayloadPort))
    }.getOrElse { error ->
        com.muyuchat.api.local.LocalApiControlResult.Rejected(
            httpStatus = 409,
            code = "tuning_${action}_rejected",
            message = error.message ?: "调优任务操作失败"
        )
    }




    private fun currentDeviceProfile(): DeviceProfile =
        deviceProfileReader.read()




    private fun buildRecommendation(
        preference: UserPreference,
        device: DeviceProfile = currentDeviceProfile(),
        lastDecodeTps: Double? = _uiState.value.benchmark?.decodeTps?.takeIf { it > 0.0 }
            ?: _uiState.value.stats.decodeTps.takeIf { it > 0.0 }
    ): AgentRecommendation =
        advisor.recommend(
            device = device,
            localModels = modelStore.listModels(),
            remoteFiles = _uiState.value.remoteFiles,
            preference = preference,
            lastDecodeTps = lastDecodeTps
        )
}
