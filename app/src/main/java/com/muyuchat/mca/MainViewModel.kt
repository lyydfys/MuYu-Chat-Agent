package com.muyuchat.mca

import android.app.Application
import com.muyuchat.mca.composition.McaAppContainer
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Build
import android.util.Base64
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
import com.muyuchat.mca.presentation.LocalApiImageBounds
import com.muyuchat.mca.presentation.LocalApiImageGenerationPort
import com.muyuchat.mca.presentation.LocalApiImageLoraGateway
import com.muyuchat.mca.presentation.LocalApiImageWorkerGateway
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
private data class PendingAssistantImageToolTurn(
    val requestId: String,
    val chatSessionId: String,
    val originalRequestMessages: List<ChatMessage>,
    val params: GenerationParams,
    val cloudModelId: String,
    val toolCalls: List<ChatToolCall>,
    val primaryToolCallId: String,
    val initialToolOutputs: Map<String, String>
)

private fun chatImageCommitJournalJobId(jobId: String, imageId: String): String {
    val safeImageId = imageId.filter { it.isLetterOrDigit() || it in "._:-" }
        .take(128)
        .ifBlank { "image" }
    return "${jobId.take(120)}.$safeImageId"
}

private data class LocalGenerationSmokeResult(
    val visibleChars: Int,
    val completionTokens: Int,
    val decodeTps: Double
)

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

private data class LoadedRuntimeSnapshot(
    val model: ModelManifest,
    val profile: ModelExecutionProfile,
    val uiState: MainUiState,
    val adaptiveRecommendation: AdaptiveTuningRecommendation?
)
private data class LocalApiStreamSmokeResult(
    val visibleContentSeen: Boolean,
    val doneSeen: Boolean,
    val stopCalls: Int
)


private data class LocalApiApplyResult(
    val running: Boolean,
    val restEnabled: Boolean,
    val failure: Throwable? = null
)
private val imageAttachmentRegex = Regex("""\u3010上传图片：([^\u3011]+)\u3011(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")
private val oldImagePlaceholderRegex = Regex("""\s*（当前文本模型会收到图片占位信息；完整识图能力后续接入多模态模型。）""")
private const val FILE_ATTACHMENT_MARKER = "\u3010\u4e0a\u4f20\u6587\u4ef6\uff1a"
// `internal`, not `private`: the vision preparation that enforces this cap moved to
// VisionAttachmentSupport.kt, and one shared cap is safer than two copies drifting apart.
internal const val MAX_CHAT_IMAGES_PER_MESSAGE = 4
// `internal`, not `private`: the send-boundary deduplication that reads this budget moved to
// VisionAttachmentSupport.kt, and one shared bound is safer than two copies drifting apart.
internal const val CHAT_SEND_ATTACHMENT_IDENTITY_MAX_BYTES = 64L * 1024L * 1024L

data class ImageLibraryBackupState(
    val running: Boolean = false,
    val importing: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val failed: Boolean = false
)

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
private class ImagePromptTokenMeasurementCache(
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
        private const val CLOUD_REASONING_LOCKED_MESSAGE = "云端模型的思考模式由服务商和具体模型决定，MCA 会默认按开启处理；如需关闭或调整，请在云端模型配置中设置相关参数。"
        private const val LOCAL_IMAGE_GENERATION_WATCHDOG_MS = 8 * 60 * 1000L
        private const val LOCAL_IMAGE_UI_PREVIEW_DIRECTORY = "local_image_ui_previews"
        private const val MAX_LOCAL_IMAGE_PREVIEW_BYTES = 16L * 1024L * 1024L
        private const val IMAGE_UPSCALER_PREFERENCES = "image_upscaler_product_selection_v1"
        private const val IMAGE_UPSCALER_SELECTED_ID = "selected_id"
        private const val CONTEXT_COMPRESSION_PREFERENCES = "mca_context_compression_v1"
        private const val CONTEXT_COMPRESSION_THRESHOLD_KEY = "threshold_percent"
        private const val CONTEXT_SUMMARY_MODEL_KEY = "summary_model"
        private const val DOWNLOAD_REQUESTS_PREFERENCES = "mca_download_requests"
        private const val MAX_DIRECT_COMPOSER_UTF8_BYTES = 256 * 1024
        private const val ASSISTANT_MODEL_MODE_FOLLOW_CURRENT = "follow_current"
        private const val ASSISTANT_MODEL_MODE_LOCAL = "local"
        private const val ASSISTANT_MODEL_MODE_CLOUD = "cloud"
        const val CLOUD_MODEL_CHOICE_PREFIX = "cloudmodel:"
        const val CLOUD_IMAGE_MODEL_CHOICE_PREFIX = "cloudimagemodel:"
        const val LOCAL_IMAGE_MODEL_CHOICE_PREFIX = "localimagemodel:"
    }

    private val modelStore = ModelStoreRepository(application)
    private val modelIdentityReferenceMigration = ModelIdentityReferenceMigration(application)
    private val restoredModelIdentityReferences = runBlocking(Dispatchers.IO) {
        modelIdentityReferenceMigration.migratePendingReferences()
    }
    // R2/S12：组合根接管装配（本片 7 个 application-only store；1:1 构造，零行为变化）。
    private val container = McaAppContainer(application)
    private val runtimeProfileStore = container.runtimeProfileStore
    /** User-facing sampling/reasoning values, isolated by local/cloud model identity. */
    private val modelGenerationProfileStore = container.modelGenerationProfileStore
    private val installationScopeId = runBlocking(Dispatchers.IO) {
        runtimeProfileStore.installationScopeId()
    }
    private val modelScopeClient = ModelScopeClient()
    private val downloader = ResumableDownloader()
    /**
     * The ViewModel side of [AppUpdatePort]. The three update jobs live in the owner, not here.
     */
    private val appUpdatePort: AppUpdatePort =
        object : AppUpdatePort {
            override val context: Application
                get() = this@MainViewModel.getApplication()

            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }
        }

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
    private val characterMemorySummaryJobs = mutableMapOf<String, Job>()
    private val characterMemorySummaryReschedule = mutableSetOf<String>()
    private val chatImageToolAuthorizationStore = container.chatImageToolAuthorizationStore
    private val worldBookStore = container.worldBookStore
    private val knowledgeBaseStore = container.knowledgeBaseStore
    private val knowledgeLibraryPort = StoreKnowledgeLibraryPort(worldBookStore, knowledgeBaseStore)
    private val chatContextComposer =
        ChatContextComposer(StoreChatContextSources(worldBookStore, knowledgeBaseStore))
    private val imageLibraryBackup = ImageLibraryBackup(application, chatSessionStore)
    private val imageAssetDirectory = canonicalImageAssetDirectory(application.filesDir)
    private val chatImageCommitJournal by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatImageCommitJournal(
            ownedRoot = File(application.filesDir, "pending-chat-image-commits"),
            assetDirectory = imageAssetDirectory
        )
    }
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
    private val cloudChatProvider = OpenAiCompatibleChatProvider()
    /** Optional, independently verified Hy-MT2 bundle. A missing bundle stays an explicit
     * fallback state and never masquerades as a completed translation. */
    @Volatile private var offlinePromptTranslationBundle: VerifiedOfflinePromptTranslationBundle? = null
    private val offlineTranslationBundleMutex = Mutex()

    private fun offlineTranslationBundleReadyStatus(bundle: VerifiedOfflinePromptTranslationBundle): String =
        if (bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2) {
            "Hy-MT2 已安装；可预览英文译文，英文模型自动翻译"
        } else {
            "翻译包已安装，但当前 runtime 尚未接入该模型；可下载 Hy-MT2。"
        }

    private suspend fun cacheVerifiedOfflineTranslationBundle(bundle: VerifiedOfflinePromptTranslationBundle) {
        offlineTranslationBundleMutex.withLock { offlinePromptTranslationBundle = bundle }
    }

    private suspend fun verifiedOfflineTranslationBundle(): VerifiedOfflinePromptTranslationBundle? = withContext(Dispatchers.IO) {
        offlineTranslationBundleMutex.withLock {
            val cached = offlinePromptTranslationBundle
            if (cached?.matchesVerifiedFiles() == true) return@withLock cached
            offlinePromptTranslationBundle = null
            val verification = try {
                offlineTranslationDownloader.verifyInstalledOrRecoverCompleteDownload()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                OfflinePromptTranslationBundleVerification.Rejected(
                    OfflinePromptTranslationBundleRejectionCode.ROOT_UNAVAILABLE,
                    error.message ?: "离线翻译包无法校验或启用"
                )
            }
            val bundle = (verification as? OfflinePromptTranslationBundleVerification.Verified)?.bundle
            offlinePromptTranslationBundle = bundle
            uiStateStore.update { state ->
                val wasInstalled = cached != null || state.offlineTranslationInstalled
                state.copy(
                    offlineTranslationInstalled = bundle?.identity?.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                    offlineTranslationStatus = when {
                        bundle != null -> offlineTranslationBundleReadyStatus(bundle)
                        wasInstalled -> {
                            val reason = (verification as? OfflinePromptTranslationBundleVerification.Rejected)?.message
                                ?: "模型文件或清单已丢失"
                            "Hy-MT2 安装已失效：$reason。请重新导入或下载。"
                        }
                        else -> state.offlineTranslationStatus
                    }
                )
            }
            bundle
        }
    }
    private val offlineTranslationInstaller = container.offlineTranslationInstaller
    private val offlineTranslationDownloader = OfflinePromptTranslationBundleDownloader(application)
    private var offlineTranslationInstallJob: Job? = null
    private var offlineTranslationDownloadJob: Job? = null
    private var pendingImageTranslation: PendingImageTranslation? = null
    private data class PendingChatImageAction(
        val id: String,
        val kind: com.muyuchat.feature.chat.ChatImageActionKind,
        val visibleUserText: String,
        val requestedOutputCount: Int?,
        val stillOwned: () -> Boolean
    )
    private var pendingChatImageAction: PendingChatImageAction? = null
    private val offlinePromptTranslationService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OfflinePromptTranslationService(AppOfflinePromptTranslationRuntimeProvider(application))
    }
    private val cloudImageProvider = CloudImageProvider()
    private val webSearchStore = container.webSearchStore
    private val webSearchDiagnosticStore = container.webSearchDiagnosticStore
    private val webSearchProvider = WebSearchProvider()
    @Volatile private var activeWebSearchDeferred: Deferred<WebSearchResult>? = null
    @Volatile private var activeWebSearchTaskId: String? = null
    private val localImageModelStore = container.localImageModelStore
    private val localImageLoraStore = container.localImageLoraStore
    private val localImageTextualInversionStore = container.localImageTextualInversionStore
    private val localImageUpscalerStore = container.localImageUpscalerStore
    private val imagePromptTokenizerDescriptors =
        java.util.concurrent.ConcurrentHashMap<String, LocalImagePromptTokenizerDescriptor>()
    private val imagePromptTokenMeasurementCache = ImagePromptTokenMeasurementCache()
    private val imageUpscalerPreferences = application.getSharedPreferences(
        IMAGE_UPSCALER_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val contextCompressionPreferences = application.getSharedPreferences(
        CONTEXT_COMPRESSION_PREFERENCES,
        Context.MODE_PRIVATE
    )
    private val manualContextCompressionRequests = ContextCompressionRequestStore()
    private val contextSummarizationService = ContextSummarizationService()
    private val localImageWorkerClient = LocalImageWorkerClient(application)
    private val deviceProfileReader = DeviceProfileReader(application)
    private val advisor = AgentAdvisor()
    private val benchmarkRunner = BenchmarkRunner(engine, deviceProfileReader)
    private val agentLogger = AgentDecisionLogger(application)
    private val benchmarkHistoryLogger = BenchmarkHistoryLogger(application)
    private val initialDeviceProfile = deviceProfileReader.read()
    private val initialParams = loadGenerationParams(application)
    private val initialPersistentPrefixCacheEnabled = loadPersistentPrefixCacheEnabled(application)
    private val runtimeUserOverrideFields = loadRuntimeUserOverrideFields(application).toMutableSet()
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
    private val chatRuns = ChatRunOwnership()
    /**
     * Owns the streamed assistant output: the buffer lock, the buffered text, the deferred publish
     * job and the generation id they belong to. See [ChatStreamAccumulator].
     */
    private val chatStreamAccumulator = ChatStreamAccumulator(
        scope = viewModelScope,
        currentRunId = { chatRuns.currentRunId() },
        activeSessionId = { _uiState.value.activeChatSessionId },
        hasAssistantMessage = { _uiState.value.messages.any { it.role == Role.ASSISTANT } },
        commit = { batch, isStillCurrent ->
            uiStateStore.update { state ->
                if (!isStillCurrent()) {
                    state
                } else {
                    val updated = state.messages.toMutableList()
                    val index = updated.indexOfLast { it.role == Role.ASSISTANT }
                    if (index >= 0) {
                        val current = updated[index]
                        updated[index] = current.copy(
                            content = current.content + batch.content,
                            reasoningContent = current.reasoningContent + batch.reasoning,
                            reasoningDurationMs = maxOf(
                                current.reasoningDurationMs,
                                batch.reasoningDurationMs
                            )
                        )
                    }
                    val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
                    state.copy(
                        messages = updated,
                        activeChatSessionId = sessionId,
                        chatSessions = state.chatSessions.upsertSession(
                            sessionId = sessionId,
                            messages = updated,
                            assistantId = state.selectedAssistantId,
                            modelMode = state.selectedChatBackend.bindingValue(),
                            modelId = state.currentChatModelId()
                        )
                    )
                }
            }
        }
    )

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

    @Volatile
    private var localConversationContextNeedsInvalidation = false
    private val localConversationContextInvalidationSequence = AtomicLong(0L)
    private var localConversationContextInvalidationJob: Job? = null
    private val persistentPrefixCacheOperationSequence = AtomicLong(0L)
    /**
     * A destructive conversation edit is published to Room before native KV
     * state is invalidated.  The barrier is joined by the next generation, so
     * a process/power interruption cannot make a new turn race the old KV.
    */
    private var conversationMutationBarrier: Job? = null
    @Volatile
    private var durableChatSessions: List<ChatSessionRecord> = initialChatSessions
    private val chatSessionPersistenceMutex = Mutex()
    private val chatSessionPersistenceSequence = AtomicLong(0L)
    private val chatSessionPersistenceStateLock = Any()
    private val pendingKnowledgeBindings = linkedMapOf<String, Set<String>>()
    private val pendingWorldBookCleanupOwners =
        linkedMapOf<WorldBookScope, MutableSet<String>>()
    private var directParameterStageJob: Job? = null
    private val directParameterStageMutex = Mutex()
    private val directParameterStageGeneration = AtomicLong(0L)
    private val managedRuntimeReadinessRefreshGate = ManagedRuntimeReadinessRefreshGate()
    private val imageAssetWriteMutex = Mutex()
    private val imageLibraryMutationMutex = Mutex()
    private val imageLibraryStartupReconciliation = viewModelScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        try {
            val loadedImages = chatSessionStore.loadImages()
            val files = chatSessionStore.loadFiles()
            val (recoveredSessions, initialImages) = recoverPendingChatImageCommits(
                sessions = durableChatSessions,
                images = loadedImages
            )
            durableChatSessions = recoveredSessions
            uiStateStore.update { state ->
                val activeMessages = recoveredSessions.firstOrNull { it.id == state.activeChatSessionId }?.messages
                state.copy(
                    chatSessions = recoveredSessions,
                    messages = activeMessages ?: state.messages,
                    images = initialImages,
                    files = files
                )
            }
            imageLibraryMutationMutex.withLock {
                imageLibraryBackup.reconcile(initialImages)
                val report = reconcileImageAssetDirectory(imageAssetDirectory, initialImages)
                check(report.failed == 0) { "Image asset startup reconciliation was incomplete." }
            }
            val counts = initialKnowledgeBases.associate { knowledgeBase ->
                knowledgeBase.id to knowledgeBaseStore.documents(knowledgeBase.id).size
            }
            uiStateStore.update { state -> state.copy(knowledgeDocumentCounts = counts) }
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            error
        }
    }
    private val imageFavoriteMutationSequence = AtomicLong(0L)
    private val latestImageFavoriteMutations =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()
    private var imageGenerationJob: Job? = null
    /** Serializes the optional chat-model prompt bridge used by chat-page image requests. */
    private var chatImagePromptBridgeJob: Job? = null
    /** Invalidates a cancelled bridge before a slow provider can enqueue its stale result. */
    private val chatImagePromptBridgeGeneration = AtomicLong(0L)
    private val contentImportPreviewStore = ContentImportPreviewStore(application)
    private val contentImportCommitMutex = Mutex()
    private val contentImportSelectionRevision = AtomicLong(0L)
    internal fun captureContentImportOwner(): ContentImportOwner = ContentImportOwner(
        sessionId = _uiState.value.activeChatSessionId,
        assistantId = _uiState.value.selectedAssistantId,
        selectionRevision = contentImportSelectionRevision.get()
    )
    private fun ContentImportOwner.isCurrent(state: MainUiState): Boolean =
        selectionRevision == contentImportSelectionRevision.get() &&
            sessionId == state.activeChatSessionId && assistantId == state.selectedAssistantId
    private val pendingAssistantImageToolTurns = java.util.concurrent.ConcurrentHashMap<String, PendingAssistantImageToolTurn>()
    private val assistantImageContinuationLock = Any()
    private val assistantImageContinuationsInFlight = mutableSetOf<String>()
    /**
     * The ViewModel side of [ImageUpscalePort]. The owner holds the job and the lock; this object only
     * supplies the app-level collaborators the owner needs.
     */
    private val imageUpscalePort: ImageUpscalePort =
        object : ImageUpscalePort {
            override val context: Application
                get() = this@MainViewModel.getApplication()

            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val imageAssetDirectory: File
                get() = this@MainViewModel.imageAssetDirectory

            override val localImageWorkerClient: LocalImageWorkerClient
                get() = this@MainViewModel.localImageWorkerClient

            override val chatSessionStore: ChatSessionStore
                get() = this@MainViewModel.chatSessionStore

            override val localImageUpscalerStore: LocalImageUpscalerStore
                get() = this@MainViewModel.localImageUpscalerStore

            override suspend fun <T> withImageLibraryMutationLock(block: suspend () -> T): T =
                this@MainViewModel.imageLibraryMutationMutex.withLock { block() }

            override fun showStatus(message: String?) {
                this@MainViewModel.showStatus(message)
            }

            override suspend fun awaitImageLibraryStartupReconciliation() {
                this@MainViewModel.awaitImageLibraryStartupReconciliation()
            }

            override suspend fun createUpscaledImageAsset(
                spec: ImageUpscaleJobSpec,
                result: LocalImageResult,
                requestId: String
            ): ImageAssetRecord = this@MainViewModel.createUpscaledImageAsset(spec, result, requestId)

            override fun tryAcquireObservedImageGenerationLease(
                requestId: String
            ): LocalImageGenerationCoordinator.Lease? =
                this@MainViewModel.tryAcquireObservedImageGenerationLease(requestId)

            override fun releaseObservedImageGenerationLease(
                lease: LocalImageGenerationCoordinator.Lease
            ): Boolean = this@MainViewModel.releaseObservedImageGenerationLease(lease)
        }

    /** The single owner of the image-upscale execution. */
    private val imageUpscaleOwner = ImageUpscaleOwner(imageUpscalePort)

    /** Delegates to [ImageUpscaleOwner.upscaleImageAsset]. */
    fun upscaleImageAsset(imageId: String, targetScale: Int) {
        imageUpscaleOwner.upscaleImageAsset(imageId, targetScale, viewModelScope)
    }

    /** Delegates to [ImageUpscaleOwner.cancelImageUpscale]. */
    fun cancelImageUpscale() {
        imageUpscaleOwner.cancelImageUpscale(viewModelScope)
    }
    @Volatile private var imageLibraryBackupJob: Job? = null
    private val imageLibraryBackupSequence = AtomicLong(0L)
    private val imageLibraryBackupLifecycleLock = Any()
    private var adaptiveTuningJob: Job? = null
    private val adaptiveTuningPauseRequested = AtomicBoolean(false)
    private val adaptiveTuningCancelRequested = AtomicBoolean(false)
    private var activeRuntimeIdentity: ModelRuntimeIdentity? = null
    private var activeVisionProjectorStamp: LoadedVisionProjectorStamp? = null
    private var activeAdaptiveRecommendation: AdaptiveTuningRecommendation? = null
    private var pendingAdaptiveRecommendation: AdaptiveTuningRecommendation? = null
    private var pendingProfileTransactionId: String? = null
    private var activeProfileTransactionId: String? = null
    private var activeTuningJobId: String? = null
    private var activeModelForRuntimeProfile: ModelManifest? = null
    private val localApiIdempotencyJournalLock = Any()
    private val pendingRuntimeRecoveries = linkedMapOf<String, RuntimeRecoveryPlan>()
    @Volatile private var activeImageGenerationJobId: String? = null
    @Volatile private var activeImageGenerationBackend: ImageBackend? = null
    @Volatile private var activeImageGenerationModelId: String? = null
    @Volatile private var activeLocalImageWorkerRequestId: String? = null
    @Volatile private var activeLocalApiImageModelId: String? = null
    private val localImageGenerationCoordinator = LocalImageGenerationCoordinator()
    private val localImageGenerationCoordinatorObservationLock = Any()
    private val localApiImageGenerationLifecycleLock = Any()
    private var activeLocalApiImageGenerationOwnership: LocalApiImageGenerationOwnership? = null
    private val localImageLoraLifecycleLock = Any()
    private val localImageTextualInversionLifecycleLock = Any()

    private suspend fun awaitImageLibraryStartupReconciliation() {
        if (imageLibraryStartupReconciliation.await() != null) {
            uiStateStore.update { state ->
                state.copy(statusMessage = "图片库启动清理未完全完成，将在下次启动重试。")
            }
        }
    }

    /** The ViewModel side of [ChatImageRetryPort]. */
    private val chatImageRetryPort: ChatImageRetryPort =
        object : ChatImageRetryPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val cloudApiStore: CloudApiStore
                get() = this@MainViewModel.cloudApiStore

            override val localImageModelStore: LocalImageModelStore
                get() = this@MainViewModel.localImageModelStore

            override val chatImageCommitJournal: ChatImageCommitJournal
                get() = this@MainViewModel.chatImageCommitJournal

            override val chatSessionStore: ChatSessionStore
                get() = this@MainViewModel.chatSessionStore

            override fun showStatus(message: String?) =
                this@MainViewModel.showStatus(message = message)

            override fun invalidatePendingChatImagePreparation() =
                this@MainViewModel.invalidatePendingChatImagePreparation()

            override fun sendChatImageRequest(
visibleUserText: String,
prompt: String,
optionsOverride: LocalImageGenerationOptions?,
promptEnvelope: PromptEnvelope?,
contextTraceJson: String?
) =
                this@MainViewModel.sendChatImageRequest(visibleUserText = visibleUserText, prompt = prompt, optionsOverride = optionsOverride, promptEnvelope = promptEnvelope, contextTraceJson = contextTraceJson)

            override fun pendingImageTranslation(): PendingImageTranslation? =
                this@MainViewModel.pendingImageTranslation


            override fun setPendingImageTranslation(value: PendingImageTranslation?) {
                this@MainViewModel.pendingImageTranslation = value
            }

        }


/** Delegates to [recoverPendingImageCommits]. Signature kept verbatim. */
    suspend fun recoverPendingChatImageCommits(
        sessions: List<ChatSessionRecord>,
        images: List<ImageAssetRecord>
    ): Pair<List<ChatSessionRecord>, List<ImageAssetRecord>> {
        return recoverPendingImageCommits(port = chatImageRetryPort, sessions = sessions, images = images)
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

    private fun startImageLibraryBackupJob(
        reservation: ImageLibraryBackupState,
        operation: suspend () -> Unit
    ): Boolean =
        synchronized(imageLibraryBackupLifecycleLock) {
            if (imageLibraryBackupJob?.isCompleted == false) return@synchronized false
            val epoch = imageLibraryBackupSequence.incrementAndGet()
            lateinit var launchedJob: Job
            launchedJob = viewModelScope.launch(
                context = Dispatchers.IO,
                start = CoroutineStart.LAZY
            ) {
                try {
                    operation()
                } finally {
                    synchronized(imageLibraryBackupLifecycleLock) {
                        if (imageLibraryBackupSequence.get() == epoch &&
                            imageLibraryBackupJob === launchedJob
                        ) {
                            imageLibraryBackupJob = null
                        }
                    }
                }
            }
            imageLibraryBackupJob = launchedJob
            uiStateStore.update { state -> state.copy(imageLibraryBackup = reservation) }
            launchedJob.start()
            true
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
        uiStateStore.update { it.copy(statusMessage = message) }
    }

    /**
     * Publishes coordinator ownership before admission so Compose cannot release a generation-owned
     * content grant while UI, authenticated Local API, or upscale dispatch is materializing inputs.
     * A failed contender keeps the flag asserted when another epoch still owns the coordinator.
     */
    private fun tryAcquireObservedImageGenerationLease(
        requestId: String
    ): LocalImageGenerationCoordinator.Lease? =
        synchronized(localImageGenerationCoordinatorObservationLock) {
            updateGenerationImageGrantReleaseDefer(true)
            localImageGenerationCoordinator.tryAcquire(requestId).also { lease ->
                if (lease == null && localImageGenerationCoordinator.activeRequestId() == null) {
                    updateGenerationImageGrantReleaseDefer(false)
                }
            }
        }

    /** A stale epoch cannot clear the observable protection for the current lease holder. */
    private fun releaseObservedImageGenerationLease(
        lease: LocalImageGenerationCoordinator.Lease
    ): Boolean = synchronized(localImageGenerationCoordinatorObservationLock) {
        val released = localImageGenerationCoordinator.release(lease)
        updateGenerationImageGrantReleaseDefer(
            localImageGenerationCoordinator.activeRequestId() != null
        )
        released
    }

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











    private fun updateGenerationImageGrantReleaseDefer(defer: Boolean) {
        uiStateStore.update { state ->
            if (state.deferGenerationImageGrantRelease == defer) {
                state
            } else {
                state.copy(deferGenerationImageGrantRelease = defer)
            }
        }
    }

    private val autoUseManagedDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var managedDownloadOwnedBusy = false


    init {
        if (!deferStartup) startAfterConstruction()
    }

    internal fun startAfterConstruction() {
        restorePendingContentImportPreview()
        imageLibraryStartupReconciliation.start()
        observeManagedDownloads()
        observeManagedImports()
        viewModelScope.launch {
            verifiedOfflineTranslationBundle()?.let { bundle ->
                uiStateStore.update { state ->
                    if (state.offlineTranslationDownloading || state.offlineTranslationInstalling) state
                    else state.copy(
                        offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                        offlineTranslationStatus = offlineTranslationBundleReadyStatus(bundle)
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
        LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
            override fun busyState(): LocalApiBusyState =
                localApiBusyStateFor(_uiState.value, tuningJson = { localApiTuningStateJson(localApiPayloadPort) })

            override fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult =
                localApiPreflightFor(
                    request = request,
                    state = _uiState.value,
                    engine = engine,
                    publicModelId = { displayName, id -> localApiPublicModelId(displayName, id) },
                    runtimeProfileJson = { runtimeProfileJsonFor(this@MainViewModel._uiState.value, this@MainViewModel.engine, this@MainViewModel.activeRuntimeIdentity) }
                )

            override fun profileJson(): String = runtimeProfileJsonFor(this@MainViewModel._uiState.value, this@MainViewModel.engine, this@MainViewModel.activeRuntimeIdentity)

            override fun tuningJson(): String = localApiTuningStateJson(localApiPayloadPort)

            override fun tuningJob(jobId: String?): com.muyuchat.api.local.LocalApiControlResult =
                localApiTuningJobFor(
                    requestedJobId = jobId,
                    currentIdentityKey = { activeRuntimeIdentity?.identityHash },
                    currentActiveTuningJobId = { activeTuningJobId },
                    lookupJob = { id -> runtimeProfileStore.tuningJob(id) },
                    lookupActiveJob = { key -> runtimeProfileStore.activeTuningJob(key) },
                    tuningJson = { localApiTuningStateJson(localApiPayloadPort) },
                    publicJobJson = { job -> tuningJobPublicJson(job) }
                )

            override fun createTuningJob(
                request: com.muyuchat.api.local.LocalApiTuningJobCreateRequest,
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult = localApiCreateTuningJobFor(
                request = request,
                idempotencyKey = idempotencyKey,
                state = _uiState.value,
                engine = engine,
                port = localApiTuningControlPort
            )

            override fun createTuningJob(
                request: com.muyuchat.api.local.LocalApiTuningJobCreateRequest,
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                createTuningJob(request, idempotency.key)
            }

            override fun pauseTuningJob(
                jobId: String,
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult = runTuningControl(jobId, "pause") {
                adaptiveTuningPauseRequested.set(true)
                runtimeProfileStore.pauseTuningJob(jobId)
            }

            override fun pauseTuningJob(
                jobId: String,
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                pauseTuningJob(jobId, idempotency.key)
            }

            override fun resumeTuningJob(
                jobId: String,
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult = runTuningControl(jobId, "resume") {
                adaptiveTuningPauseRequested.set(false)
                runtimeProfileStore.resumeTuningJob(jobId)
            }

            override fun resumeTuningJob(
                jobId: String,
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                resumeTuningJob(jobId, idempotency.key)
            }

            override fun cancelTuningJob(
                jobId: String,
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult = runTuningControl(jobId, "cancel") {
                adaptiveTuningCancelRequested.set(true)
                cancelPersistedTuningJob(jobId)
            }

            override fun cancelTuningJob(
                jobId: String,
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                cancelTuningJob(jobId, idempotency.key)
            }

            override fun applyTuningJob(
                jobId: String,
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult = localApiApplyTuningJobFor(
                jobId = jobId,
                idempotencyKey = idempotencyKey,
                port = localApiTuningControlPort
            )

            override fun applyTuningJob(
                jobId: String,
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                applyTuningJob(jobId, idempotency.key)
            }

            override fun rollbackTuning(
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult = localApiRollbackTuningFor(
                idempotencyKey = idempotencyKey,
                port = localApiTuningControlPort
            )

            override fun rollbackTuning(
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                rollbackTuning(idempotency.key)
            }
        }

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
    /**
     * The ViewModel side of [ManagedRuntimeReadinessPort]. Every concurrency primitive stays here and
     * is reduced to a yes/no answer, so nothing that a caller could misuse crosses the boundary.
     */
    private val managedRuntimeReadinessPort: ManagedRuntimeReadinessPort =
        object : ManagedRuntimeReadinessPort {
            override val context: Application
                get() = this@MainViewModel.getApplication()

            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun listLocalModels(): List<ModelManifest> =
                this@MainViewModel.modelStore.listModels()

            override fun loadLocalImageModels(): List<LocalImageModelRecord> =
                this@MainViewModel.localImageModelStore.loadModels()

            override fun loadPersistedSelectedImageModelId(): String? =
                this@MainViewModel.localImageModelStore.loadSelectedModelId()

            override fun saveSelectedImageModelId(id: String?) {
                this@MainViewModel.localImageModelStore.saveSelectedModelId(id)
            }

            override fun beginReadinessRefresh(): Long =
                this@MainViewModel.managedRuntimeReadinessRefreshGate.begin()

            override fun invalidateReadinessRefresh() {
                this@MainViewModel.managedRuntimeReadinessRefreshGate.invalidate()
            }

            override fun isReadinessRefreshCurrent(token: Long): Boolean =
                this@MainViewModel.managedRuntimeReadinessRefreshGate.isCurrent(token)

            override fun imageLibraryBackupJobActive(): Boolean =
                this@MainViewModel.imageLibraryBackupJob?.isCompleted == false

            override fun hasActiveImageGeneration(): Boolean =
                this@MainViewModel.activeImageGenerationJobId != null

            override fun hasActiveImageUpscale(): Boolean =
                this@MainViewModel.imageUpscaleOwner.hasActiveUpscale()

            override fun hasActiveLocalApiImageGeneration(): Boolean =
                this@MainViewModel.activeLocalApiImageModelId != null

            override fun hasActiveImageGenerationRequest(): Boolean =
                this@MainViewModel.localImageGenerationCoordinator.activeRequestId() != null
        }

    /** Delegates to [publishChatCatalogSnapshot]. */
    private fun publishManagedChatCatalogAfterValidation(): ManagedChatCatalogSnapshot =
        publishChatCatalogSnapshot(managedRuntimeReadinessPort)


    /** Refreshes product-facing readiness; persisted verification remains advisory. */

    fun onInputChange(value: String) {
        if (value != _uiState.value.input &&
            (pendingImageTranslation != null || pendingChatImageAction != null)
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
        if (_uiState.value.isGenerating) {
            showStatus("请先停止当前生成，再上传文件")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val uri = Uri.parse(uriString)
                val name = displayNameForUri(uri)
                if (isImageAttachment(uri, name)) {
                    awaitImageLibraryStartupReconciliation()
                    val image = importImageAsset(
                        uri = uri,
                        displayName = name,
                        source = "uploaded",
                        chatSessionId = _uiState.value.activeChatSessionId
                    )
                    return@runCatching AttachmentImportResult(name, image.toInputAttachment(), false, image)
                }
                if (!isSupportedTextAttachment(getApplication<Application>().contentResolver, uri, name)) {
                    error("当前上传入口暂支持文本、Markdown、JSON、XML 等文本类文件")
                }
                val (text, truncated) = readAttachmentText(uri)
                if (text.isBlank()) error("文件内容为空或无法作为文本读取")
                val file = createFileAsset(
                    uri = uri,
                    displayName = name,
                    text = text,
                    truncated = truncated,
                    source = "uploaded",
                    chatSessionId = _uiState.value.activeChatSessionId
                )
                AttachmentImportResult(name, file.text, file.truncated, fileAsset = file)
            }
            result.exceptionOrNull()?.let { error ->
                if (error is CancellationException) throw error
            }
            result.onSuccess { imported ->
                fun publishImportedAttachment(): List<FileAssetRecord>? {
                    var filesToPersist: List<FileAssetRecord>? = null
                    uiStateStore.update { state ->
                        val importedAttachment = imported.imageAsset?.toChatAttachment()
                        // The system picker can deliver the same selection more than once and
                        // every import is copied to a fresh private file.  Compare the actual
                        // image bytes through the shared vision identity contract before adding
                        // another marker to the composer; otherwise the outgoing bubble can
                        // visibly contain the same photo twice even though the user picked it
                        // only once.
                        val alreadyAttached = importedAttachment != null &&
                            state.prepareChatInput().imageAttachments.any { existing ->
                                listOf(existing, importedAttachment)
                                    .deduplicateVisionAttachments()
                                    .size == 1
                            }
                        val attachment = buildString {
                            if (!alreadyAttached) {
                                if (state.input.isNotBlank()) append("\n\n")
                                if (imported.imageAsset != null) {
                                    append(imported.text.trim())
                                } else if (imported.fileAsset != null) {
                                    append(imported.fileAsset.toInputAttachment())
                                } else {
                                    append("【上传文件：").append(imported.name).append("】\n")
                                    append(imported.text.trim())
                                    if (imported.truncated) append("\n\n（文件较大，已截取前 64KB）")
                                }
                            }
                        }
                        val updatedImages = imported.imageAsset?.let { image ->
                            (listOf(image) + state.images.filterNot { it.id == image.id })
                                .sortedImagesForLibrary()
                        }
                        val updatedFiles = imported.fileAsset?.let { file ->
                            (listOf(file) + state.files.filterNot { it.id == file.id })
                                .sortedFilesForLibrary()
                        }
                        if (updatedFiles != null) filesToPersist = updatedFiles
                        state.copy(
                            input = state.input + attachment,
                            images = updatedImages ?: state.images,
                            files = updatedFiles ?: state.files,
                            statusMessage = if (imported.imageAsset != null && alreadyAttached) {
                                "这张图片已在当前消息中，未重复添加：${imported.name}"
                            } else if (imported.imageAsset != null) {
                                "已添加图片：${imported.name}"
                            } else {
                                "已添加文件：${imported.name}"
                            }
                        )
                    }
                    return filesToPersist
                }

                val filesToPersist = imported.imageAsset?.let { image ->
                    val commit = runCatching {
                        upsertImportedImageUnderMutationLock(imageLibraryCommitPort, image) {
                            publishImportedAttachment()
                        }
                    }
                    val error = commit.exceptionOrNull()
                    if (error != null) {
                        image.deleteLocalCopy(imageAssetDirectory)
                        if (error is CancellationException) throw error
                        fail("图片库保存失败：${error.message ?: "数据库提交失败"}")
                        return@onSuccess
                    }
                    commit.getOrThrow()
                } ?: publishImportedAttachment()
                filesToPersist?.let { persistFiles(it) }
            }.onFailure { error ->
                showStatus(error.message ?: "文件上传失败")
            }
        }
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

    private val imagePromptTokenMeasurementHost = object : ImagePromptTokenMeasurementHost {
        override val models: List<LocalImageModelRecord>
            get() = _uiState.value.localImageModels

        override fun cachedMeasurement(cacheKey: String): ImagePromptTokenMeasurement? =
            imagePromptTokenMeasurementCache.get(cacheKey)

        override fun cacheMeasurement(cacheKey: String, measurement: ImagePromptTokenMeasurement) {
            imagePromptTokenMeasurementCache.put(cacheKey, measurement)
        }

        override fun cachedDescriptor(modelKey: String): LocalImagePromptTokenizerDescriptor? =
            imagePromptTokenizerDescriptors[modelKey]

        override fun cacheDescriptor(modelKey: String, descriptor: LocalImagePromptTokenizerDescriptor) {
            imagePromptTokenizerDescriptors.putIfAbsent(modelKey, descriptor)
        }
    }

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
        var removed: FileAssetRecord? = null
        var filesToPersist: List<FileAssetRecord> = emptyList()
        uiStateStore.update { state ->
            removed = state.files.firstOrNull { it.id == fileId }
            filesToPersist = state.files.filterNot { it.id == fileId }
            state.copy(files = filesToPersist, statusMessage = "已从文件库移除：${removed?.name ?: "文件"}")
        }
        persistFiles(filesToPersist)
    }

    fun clearFileLibrary() {
        val filesToRemove = _uiState.value.files
        if (filesToRemove.isEmpty()) {
            showStatus("文件库已经为空")
            return
        }
        uiStateStore.update {
            it.copy(
                files = emptyList(),
                statusMessage = "已清空文件库：${filesToRemove.size} 个文件"
            )
        }
        persistFiles(emptyList())
    }

    /**
     * The ViewModel side of [LocalImageAdapterPort]. Both lifecycle locks stay here and are only ever
     * entered through the two locked-section methods.
     */
    private val localImageAdapterPort: LocalImageAdapterPort =
        object : LocalImageAdapterPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val loraStore: LocalImageLoraStore
                get() = this@MainViewModel.localImageLoraStore

            override val textualInversionStore: TextualInversionStore
                get() = this@MainViewModel.localImageTextualInversionStore

            override fun <T> withLoraLifecycleLock(block: () -> T): T =
                synchronized(this@MainViewModel.localImageLoraLifecycleLock) { block() }

            override fun <T> withTextualInversionLifecycleLock(block: () -> T): T =
                synchronized(this@MainViewModel.localImageTextualInversionLifecycleLock) { block() }
        }

    /** Delegates to [importLocalImageLoraAdapter]. */
    fun importLocalImageLora(uriString: String) {
        importLocalImageLoraAdapter(
            port = localImageAdapterPort,
            scope = viewModelScope,
            uriString = uriString
        )
    }

    /** Delegates to [deleteLocalImageLoraAdapter]. */
    fun deleteLocalImageLora(id: String) {
        deleteLocalImageLoraAdapter(
            port = localImageAdapterPort,
            scope = viewModelScope,
            id = id
        )
    }

    /** Delegates to [importLocalImageTextualInversionArtifact]. Signature copied verbatim. */
    fun importLocalImageTextualInversion(uriString: String, trigger: String) {
        importLocalImageTextualInversionArtifact(
            port = localImageAdapterPort,
            scope = viewModelScope,
            uriString = uriString,
            trigger = trigger
        )
    }

    /** Delegates to [deleteLocalImageTextualInversionArtifact]. */
    fun deleteLocalImageTextualInversion(id: String) {
        deleteLocalImageTextualInversionArtifact(
            port = localImageAdapterPort,
            scope = viewModelScope,
            id = id
        )
    }

    /** Downloads the publisher-pinned Viggle v0.2.1 adapter into the same
     * private store used by user-imported LoRAs. Unknown device profiles do not
     * affect visibility or access; the store's byte and SHA checks are the
     * acceptance gate. */
    fun downloadOfficialViggleLora() {
        if (_uiState.value.localImageLoraImporting) return
        uiStateStore.update {
            it.copy(
                localImageLoraImporting = true,
                localImageLoraMessage = "正在下载 Viggle v0.2.1 LoRA…"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            var lastReported = -1L
            runCatching {
                ViggleV021LoraDownloader.download(localImageLoraStore) { bytes ->
                    val mb = bytes / (1024L * 1024L)
                    if (mb != lastReported) {
                        lastReported = mb
                        uiStateStore.update {
                            it.copy(
                                localImageLoraMessage =
                                    "正在下载 Viggle v0.2.1 LoRA… ${bytes * 100 / ViggleV021LoraDownloader.EXPECTED_SIZE_BYTES}%"
                            )
                        }
                    }
                }
            }.onSuccess { record ->
                uiStateStore.update {
                    it.copy(
                        localImageLoras = localImageLoraStore.load(),
                        localImageLoraImporting = false,
                        localImageLoraMessage = "已下载并校验 Viggle v0.2.1 LoRA：${record.name}"
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                uiStateStore.update {
                    it.copy(
                        localImageLoraImporting = false,
                        localImageLoraMessage = "Viggle LoRA 下载失败：${error.message ?: "网络或文件校验错误"}"
                    )
                }
            }
        }
    }


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

    /**
     * The ViewModel side of [LocalImageUpscalerPort]. The lifecycle lock stays here and is only ever
     * entered through [withUpscaleLifecycleLock].
     */
    private val localImageUpscalerPort: LocalImageUpscalerPort =
        object : LocalImageUpscalerPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val upscalerStore: LocalImageUpscalerStore
                get() = this@MainViewModel.localImageUpscalerStore

            override fun persistSelectedUpscalerId(id: String?): Boolean =
                this@MainViewModel.imageUpscalerPreferences.edit()
                    .putString(IMAGE_UPSCALER_SELECTED_ID, id)
                    .commit()

            override fun <T> withUpscaleLifecycleLock(block: (upscaleRunning: Boolean) -> T): T =
                this@MainViewModel.imageUpscaleOwner.withUpscaleLifecycleLock(block)
        }

    /** Delegates to [importLocalImageUpscalerModel]. */
    fun importLocalImageUpscaler(uriString: String) {
        importLocalImageUpscalerModel(
            port = localImageUpscalerPort,
            scope = viewModelScope,
            uriString = uriString
        )
    }

    /** Delegates to [selectLocalImageUpscalerModel]. */
    fun selectLocalImageUpscaler(id: String) {
        selectLocalImageUpscalerModel(port = localImageUpscalerPort, id = id)
    }

    /** Delegates to [deleteLocalImageUpscalerModel]. */
    fun deleteLocalImageUpscaler(id: String) {
        deleteLocalImageUpscalerModel(
            port = localImageUpscalerPort,
            scope = viewModelScope,
            id = id
        )
    }





    fun generateImageAsset(
        prompt: String,
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ): Boolean {
        return enqueueImageGeneration(
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
        enqueueImageGeneration(
            prompt = snapshot.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot
        )
    }

    private fun resolveCurrentLocalImageLoras(
        selections: List<Pair<String, Double>>,
        records: List<LocalImageLoraRecord> = _uiState.value.localImageLoras
    ): List<LocalImagePreparedLora> {
        require(selections.size <= LocalImagePreparedLora.MAX_COUNT) {
            "单次最多使用 ${LocalImagePreparedLora.MAX_COUNT} 个 LoRA。"
        }
        require(selections.map { it.first }.distinct().size == selections.size) {
            "同一个 LoRA 不能重复选择。"
        }
        val recordsById = records.associateBy(LocalImageLoraRecord::id)
        return selections.map { (id, multiplier) ->
            val record = recordsById[id]
                ?: throw LocalImageProductContractException(
                    "image_lora_not_found",
                    "所选 LoRA 已删除或不可用：$id"
                )
            record.toPrepared(multiplier)
        }
    }

    private fun resolveCurrentLocalImageTextualInversionIds(
        ids: List<String>,
        records: List<TextualInversionArtifact> = _uiState.value.localImageTextualInversions
    ): List<String> {
        require(ids.size <= TextualInversionContract.MAX_COUNT) {
            "单次最多使用 ${TextualInversionContract.MAX_COUNT} 个 Textual Inversion。"
        }
        val normalizedIds = ids.map { id -> UUID.fromString(id.trim()).toString() }
        require(normalizedIds.distinct().size == normalizedIds.size) {
            "同一个 Textual Inversion 不能重复选择。"
        }
        val availableIds = records.mapTo(mutableSetOf(), TextualInversionArtifact::id)
        normalizedIds.firstOrNull { it !in availableIds }?.let { missingId ->
            throw LocalImageProductContractException(
                "image_textual_inversion_not_found",
                "所选 Textual Inversion 已删除或不可用：$missingId"
            )
        }
        return normalizedIds
    }

    /** The ViewModel side of [ImageAssetCreationPort]. */
    private val imageAssetCreationPort: ImageAssetCreationPort =
        object : ImageAssetCreationPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val context: Application
                get() = this@MainViewModel.getApplication()

            override suspend fun <T> withImageAssetWriteLock(block: suspend () -> T): T =
                this@MainViewModel.imageAssetWriteMutex.withLock { block() }

            override val imageAssetDirectory: File
                get() = this@MainViewModel.imageAssetDirectory

            override val localImageModelStore: LocalImageModelStore
                get() = this@MainViewModel.localImageModelStore

            override fun showStatus(message: String?)
                = this@MainViewModel.showStatus(message = message)

            override fun invalidatePendingChatImagePreparation()
                = this@MainViewModel.invalidatePendingChatImagePreparation()

            override fun canReadGenerationHistoryInput(reference: String): Boolean
                = this@MainViewModel.canReadGenerationHistoryInput(reference = reference)

            override fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean
                = this@MainViewModel.rejectImageModelSwitchWhileGenerationIsActive()

            override fun enqueueImageGeneration(
            prompt: String,
            inputDraft: LocalImageInputDraft,
            options: LocalImageGenerationOptions,
            jobSnapshot: ImageGenerationJobSpec?,
            // Keep the generated UI id in the function body so callers that retry
            // or continue an assistant tool turn can provide their own stable id.
            // This also keeps the public admission signature free of interpolation
            // braces, which makes source-level lifecycle checks unambiguous.
            requestedJobId: String?,
            requestedChatSessionId: String?,
            requestedChatMessageId: String?
            ): Boolean
                = this@MainViewModel.enqueueImageGeneration(prompt = prompt, inputDraft = inputDraft, options = options, jobSnapshot = jobSnapshot, requestedChatSessionId = requestedChatSessionId, requestedChatMessageId = requestedChatMessageId)

            override fun resolveCurrentLocalImageLoras(
            selections: List<Pair<String, Double>>,
            records: List<LocalImageLoraRecord>
            ): List<LocalImagePreparedLora>
                = this@MainViewModel.resolveCurrentLocalImageLoras(selections = selections, records = records)

            override fun activeImageGenerationJobId(): String? =
                this@MainViewModel.activeImageGenerationJobId

            override fun activeImageGenerationModelId(): String? =
                this@MainViewModel.activeImageGenerationModelId
        }

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

    private fun canReadGenerationHistoryInput(reference: String): Boolean {
        val uri = runCatching { Uri.parse(reference.trim()) }.getOrNull()
            ?.takeIf { it.scheme.equals("content", ignoreCase = true) }
            ?: return false
        return runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { stream -> stream.read() >= 0 }
                ?: false
        }.getOrDefault(false)
    }

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

    private val imagePromptTranslationPort = object : ImagePromptTranslationPort {
        override suspend fun verifiedBundle(): VerifiedOfflinePromptTranslationBundle? =
            this@MainViewModel.verifiedOfflineTranslationBundle()

        override suspend fun translate(
            bundle: VerifiedOfflinePromptTranslationBundle?,
            request: OfflinePromptTranslationRequest
        ): OfflinePromptTranslationResolution = offlinePromptTranslationService.translate(bundle, request)

        override fun publishTranslationStatus(status: String, taskMessage: String) {
            uiStateStore.update { state ->
                val activeJobId = activeImageGenerationJobId
                state.copy(
                    offlineTranslationStatus = status,
                    imageJobs = if (activeJobId.isNullOrBlank()) state.imageJobs else {
                        state.imageJobs.map { job ->
                            if (job.id == activeJobId &&
                                job.status == ImageGenerationStatusRecord.GENERATING
                            ) job.copy(message = taskMessage) else job
                        }
                    },
                    statusMessage = taskMessage
                )
            }
        }
    }
    private suspend fun prepareLocalImageRuntimeMemory(): com.muyuchat.core.engine.EngineLifecycleLease {
        // Wait for a genuine in-flight chat/load to finish; never stop another request here.
        val lease = engine.acquireExclusiveLifecycleLease(stopActiveGeneration = false)
        try {
            val hadLoadedModel = engine.stats.value.loaded || _uiState.value.loadedModelId != null
            val releasedIdentity = activeRuntimeIdentity
            engine.unloadModel(lease = lease)
            if (hadLoadedModel) {
                clearNativeRuntimeSessionState(
                    lifecycle = AgentEngineLifecycle.UNLOADED,
                    statusMessage = "已释放聊天模型内存，正在准备本地生图；聊天模型可在生图结束后重新加载。"
                )
                clearPendingRuntimeTransactionForLifecycle(
                    reason = "LOCAL_IMAGE_MEMORY_RECLAIM",
                    identity = releasedIdentity
                )
            }
            return lease
        } catch (error: Throwable) {
            withContext(NonCancellable) { lease.release() }
            throw error
        }
    }

    private fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?,
        // Keep the generated UI id in the function body so callers that retry
        // or continue an assistant tool turn can provide their own stable id.
        // This also keeps the public admission signature free of interpolation
        // braces, which makes source-level lifecycle checks unambiguous.
        requestedJobId: String? = null,
        requestedChatSessionId: String? = null,
        requestedChatMessageId: String? = null
    ): Boolean {
        val cleanPrompt = (jobSnapshot?.prompt ?: prompt).trim()
        if (cleanPrompt.isBlank()) {
            showStatus("请输入图片描述")
            return false
        }
        if (cleanPrompt.length > LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS) {
            uiStateStore.update {
                it.copy(
                    statusMessage = "图片提示词超过 " +
                        "${LocalImagePromptExecution.MAX_ORIGINAL_PROMPT_CHARS} 字符上限。"
                )
            }
            return false
        }
        if (imageGenerationJob?.isActive == true) {
            showStatus("已有图片生成任务正在运行，请先停止当前任务")
            return false
        }
        val jobId = requestedJobId ?: "ui-img-${UUID.randomUUID()}"
        val enqueueState = _uiState.value
        val requestedBackend = jobSnapshot?.backend ?: enqueueState.selectedImageBackend
        val requestedLocalModel = if (requestedBackend == ImageBackend.LOCAL) {
            jobSnapshot?.localModelSnapshot ?: enqueueState.selectedLocalImageModel()
        } else {
            null
        }
        if (enqueueState.isGenerating && requestedBackend == ImageBackend.LOCAL &&
            enqueueState.selectedChatBackend == ChatBackend.LOCAL
        ) {
            uiStateStore.update {
                it.copy(statusMessage = "本地聊天正在生成，请等本轮结束后再启动本地生图，避免设备内存冲突。")
            }
            return false
        }
        val requestedCloudConfig = if (requestedBackend == ImageBackend.CLOUD) {
            jobSnapshot?.cloudConfigSnapshot ?: enqueueState.selectedImageCloudConfig()?.normalized()
        } else {
            null
        }
        if (requestedBackend == ImageBackend.LOCAL && requestedLocalModel == null) {
            showStatus("请先在模型管理的本地页导入并选择图像生成引擎。")
            return false
        }
        requestedLocalModel?.localImageStructuralReadinessMessage()?.let { readiness ->
            uiStateStore.update {
                it.copy(statusMessage = "图片生成尚未启动：$readiness")
            }
            return false
        }
        if (requestedBackend == ImageBackend.CLOUD && requestedCloudConfig == null) {
            showStatus("请先在模型管理的云端页接入并选择图像生成模型。")
            return false
        }
        val requestedModelId = jobSnapshot?.modelId
            ?: requestedLocalModel?.id
            ?: enqueueState.selectedCloudImageModelId
        val requestedModelName = jobSnapshot?.modelName
            ?: requestedLocalModel?.displayName
            ?: requestedCloudConfig?.displayName?.takeIf(String::isNotBlank)
            ?: requestedCloudConfig?.imageModel.orEmpty()
        val owningChatSessionId = jobSnapshot?.chatSessionId
            ?: requestedChatSessionId
            ?: enqueueState.activeChatSessionId
        val owningChatMessageId = jobSnapshot?.chatMessageId ?: requestedChatMessageId
        val baseOptions = jobSnapshot?.options ?: options
        if (requestedBackend == ImageBackend.LOCAL &&
            baseOptions.textualInversionIds.isNotEmpty() &&
            enqueueState.localImageTextualInversionLoading
        ) {
            uiStateStore.update {
                it.copy(statusMessage = "Textual Inversion 库仍在加载，请稍后重试。")
            }
            return false
        }
        val currentOptions = if (requestedBackend == ImageBackend.LOCAL) {
            try {
                baseOptions.copy(
                    loras = resolveCurrentLocalImageLoras(
                        baseOptions.loras.map { adapter -> adapter.id to adapter.multiplier }
                    ),
                    textualInversionIds = resolveCurrentLocalImageTextualInversionIds(
                        baseOptions.textualInversionIds
                    )
                ).also { resolvedOptions ->
                    requireTextualInversionPromptTriggers(
                        prompt = cleanPrompt,
                        negativePrompt = resolvedOptions.negativePrompt,
                        ids = resolvedOptions.textualInversionIds,
                        records = enqueueState.localImageTextualInversions
                    )
                }
            } catch (error: Exception) {
                uiStateStore.update {
                    it.copy(statusMessage = "图片任务的本地扩展不可用：${error.message ?: "请重新选择"}")
                }
                return false
            }
        } else {
            if (baseOptions.loras.isNotEmpty() || baseOptions.textualInversionIds.isNotEmpty() ||
                baseOptions.ultraFix != null
            ) {
                showStatus("云端图片连接器不接受本地模型扩展。")
                return false
            }
            baseOptions
        }
        // Preview is an explicit per-job UI/API option. Do not silently turn it back on here:
        // retries and history recreation must preserve the captured job snapshot exactly.
        var queuedOptions = currentOptions
        val queuedInputDraft = jobSnapshot?.inputDraft ?: inputDraft
        if (requestedBackend == ImageBackend.LOCAL) {
            try {
                val resolvedSampler = requireNotNull(requestedLocalModel).validateProductTaskSampler(
                    taskMode = queuedInputDraft.taskMode,
                    sampleMethod = queuedOptions.sampleMethod
                )
                // Capture the effective task-aware sampler in the retryable job spec. In
                // particular, an omitted sampler on a generic shared-QNN img2img request must
                // not fall through to that profile's legacy PNDM default.
                queuedOptions = queuedOptions.copy(
                    sampleMethod = imageSchedulerProductName(resolvedSampler)
                )
            } catch (error: Exception) {
                uiStateStore.update {
                    it.copy(
                        statusMessage = "图片采样器不可用：${error.message ?: "请重新选择采样器"}"
                    )
                }
                return false
            }
        }
        var queuedJobSpec = (jobSnapshot ?: ImageGenerationJobSpec(
            prompt = cleanPrompt,
            backend = requestedBackend,
            localModelSnapshot = requestedLocalModel,
            cloudConfigSnapshot = requestedCloudConfig,
            modelId = requireNotNull(requestedModelId) { "The queued image model id is missing." },
            modelName = requestedModelName,
            inputDraft = queuedInputDraft,
            options = queuedOptions,
            chatSessionId = owningChatSessionId,
            chatMessageId = owningChatMessageId
        )).copy(
            localModelSnapshot = requestedLocalModel,
            cloudConfigSnapshot = requestedCloudConfig,
            options = queuedOptions,
            chatSessionId = owningChatSessionId,
            chatMessageId = owningChatMessageId
        )
        val generationLease = tryAcquireObservedImageGenerationLease(jobId)
        if (generationLease == null) {
            uiStateStore.update {
                it.copy(statusMessage = "已有 UI 或 Local API 图片任务正在运行，请等待完成或先停止当前任务")
            }
            return false
        }
        val textualInversionReservationError =
            synchronized(localImageTextualInversionLifecycleLock) {
                val state = _uiState.value
                val ids = queuedOptions.textualInversionIds.toSet()
                when {
                    ids.any(state.deletingLocalImageTextualInversionIds::contains) ->
                        "所选 Textual Inversion 正在删除，请重新选择。"
                    ids.any { requestedId ->
                        state.localImageTextualInversions.none { it.id == requestedId }
                    } -> "所选 Textual Inversion 已删除，请重新选择。"
                    else -> {
                        uiStateStore.update {
                            it.copy(activeLocalImageTextualInversionIds = ids)
                        }
                        null
                    }
                }
            }
        if (textualInversionReservationError != null) {
            check(releaseObservedImageGenerationLease(generationLease)) {
                "UI image generation lease was replaced during Textual Inversion reservation."
            }
            uiStateStore.update {
                it.copy(
                    activeLocalImageTextualInversionIds = emptySet(),
                    localImageTextualInversionMessage = textualInversionReservationError,
                    statusMessage = textualInversionReservationError
                )
            }
            return false
        }
        val leasedLoraRefreshError = synchronized(localImageLoraLifecycleLock) {
            runCatching {
                val currentLoras = localImageLoraStore.load()
                queuedOptions = queuedOptions.copy(
                    loras = resolveCurrentLocalImageLoras(
                        queuedOptions.loras.map { adapter -> adapter.id to adapter.multiplier },
                        currentLoras
                    )
                )
                queuedJobSpec = queuedJobSpec.copy(options = queuedOptions)
                uiStateStore.update {
                    it.copy(
                        localImageLoras = currentLoras,
                        activeLocalImageLoraIds = queuedOptions.loras
                            .mapTo(mutableSetOf()) { adapter -> adapter.id }
                    )
                }
            }.exceptionOrNull()
        }
        if (leasedLoraRefreshError != null) {
            check(releaseObservedImageGenerationLease(generationLease)) {
                "UI image generation lease was replaced during LoRA refresh."
            }
            synchronized(localImageLoraLifecycleLock) {
                uiStateStore.update {
                    it.copy(
                        activeLocalImageLoraIds = emptySet(),
                        activeLocalImageTextualInversionIds = emptySet(),
                        statusMessage = "图片任务的 LoRA 不可用：${leasedLoraRefreshError.message ?: "请重新选择"}"
                    )
                }
            }
            return false
        }
        val localBatchPlan = requestedLocalModel?.let { model ->
            runCatching {
                planLocalImageBatch(
                    parentRequestId = jobId,
                    runtime = model.runtime,
                    requestedOptions = queuedOptions
                )
            }.getOrElse { error ->
                check(releaseObservedImageGenerationLease(generationLease)) {
                    "UI image generation lease was replaced during batch planning."
                }
                synchronized(localImageLoraLifecycleLock) {
                    uiStateStore.update {
                        it.copy(
                            activeLocalImageLoraIds = emptySet(),
                            activeLocalImageTextualInversionIds = emptySet(),
                            statusMessage = "图片批次参数无效：${error.message ?: "请检查数量和 seed"}"
                        )
                    }
                }
                return false
            }
        }
        // Nothing after the lease-protected refresh may observe another value. Keeping these
        // snapshots immutable also makes the coroutine capture explicit for retry/history parity.
        val executionOptions = localBatchPlan?.parentOptions ?: queuedOptions
        val executionJobSpec = queuedJobSpec.copy(options = executionOptions)
        activeImageGenerationJobId = jobId
        activeImageGenerationBackend = requestedBackend
        activeImageGenerationModelId = requestedModelId
        uiStateStore.update { state ->
            state.copy(
                imageJobs = (
                    listOf(
                        ImageGenerationJobRecord(
                            id = jobId,
                            prompt = cleanPrompt,
                            status = ImageGenerationStatusRecord.QUEUED,
                            backend = requestedBackend,
                            modelId = requestedModelId,
                            modelName = requestedModelName,
                            spec = executionJobSpec,
                            startedAtElapsedMs = android.os.SystemClock.elapsedRealtime(),
                            message = if (requestedBackend == ImageBackend.LOCAL) "等待本地生图" else "等待云端生图"
                        )
                    ) + state.imageJobs
                ).take(8),
                statusMessage = "图片任务已排队"
            )
        }
        // Protect preparation, worker callbacks and the final library commit,
        // including cloud images which do not have a local worker service.
        val foregroundLease = McaGenerationForegroundService.acquire(
            getApplication<Application>(),
            McaGenerationForegroundService.KIND_IMAGE
        )
        val executionJob = viewModelScope.launch(Dispatchers.IO) {
            var preparedJobSpec = executionJobSpec
            var chatRuntimeLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
            try {
                awaitImageLibraryStartupReconciliation()
                uiStateStore.update { state ->
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            ImageGenerationStatusRecord.GENERATING,
                            if (requestedBackend == ImageBackend.LOCAL) "正在调用本地图像生成引擎，本地生成可能需要数分钟" else "正在调用云端图片模型"
                        )
                    )
                }
                if (requestedBackend == ImageBackend.LOCAL) {
                    val model = requireNotNull(requestedLocalModel) {
                        "The queued local image model snapshot is missing."
                    }
                    model.localImageStructuralReadinessMessage()?.let { readiness ->
                        val message = "图片生成尚未启动：$readiness"
                        uiStateStore.update { state ->
                            val updatedJobs = state.imageJobs.withLocalImagePromptPreparationFailureIfActive(
                                activeJobId = activeImageGenerationJobId,
                                jobId = jobId,
                                message = message
                            )
                            if (updatedJobs === state.imageJobs) state
                            else state.copy(
                                imageJobs = updatedJobs,
                                statusMessage = message
                            )
                        }
                        return@launch
                    }
                    val promptExecution = try {
                        prepareLocalImagePromptExecution(
                            model = model,
                            prompt = executionJobSpec.prompt,
                            options = executionJobSpec.options,
                            captured = executionJobSpec.promptExecution
                        ).also { execution ->
                            requireTextualInversionPromptTriggers(
                                prompt = execution.effectivePrompt,
                                negativePrompt = execution.effectiveNegativePrompt,
                                ids = executionJobSpec.options.textualInversionIds,
                                records = _uiState.value.localImageTextualInversions
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        val message = localImagePromptPreparationFailureMessage(error)
                        uiStateStore.update { state ->
                            val updatedJobs =
                                state.imageJobs.withLocalImagePromptPreparationFailureIfActive(
                                    activeJobId = activeImageGenerationJobId,
                                    jobId = jobId,
                                    message = message
                                )
                            if (updatedJobs === state.imageJobs) {
                                state
                            } else {
                                state.copy(
                                    imageJobs = updatedJobs,
                                    statusMessage = "图片生成失败：$message"
                                )
                            }
                        }
                        return@launch
                    }
                    preparedJobSpec = executionJobSpec.copy(promptExecution = promptExecution)
                    currentCoroutineContext().ensureActive()
                    chatRuntimeLease = prepareLocalImageRuntimeMemory()
                    currentCoroutineContext().ensureActive()
                    localImageWorkerClient.begin(model.runtime)
                    currentCoroutineContext().ensureActive()
                    val translationStatusMessage = if (
                        promptExecution.method in setOf(
                            LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN,
                            LocalImagePromptTransformationMethod.OFFLINE_HY_MT2_ZH_TO_EN
                        )
                    ) {
                        val (label, translatedText) = if (promptExecution.originalPrompt.containsHanScript()) {
                            "英文译文" to promptExecution.effectivePrompt
                        } else {
                            "负向词英文译文" to promptExecution.effectiveNegativePrompt
                        }
                        val preview = translatedText
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .trim()
                            .take(96)
                        "$label：$preview${if (translatedText.length > 96) "..." else ""}"
                    } else {
                        null
                    }
                    uiStateStore.update { state ->
                        val currentJob = state.imageJobs.firstOrNull { it.id == jobId }
                        if (currentJob?.status != ImageGenerationStatusRecord.GENERATING) {
                            state
                        } else {
                            state.copy(
                                imageJobs = state.imageJobs.map { job ->
                                    if (job.id == jobId) {
                                        job.copy(
                                            spec = preparedJobSpec,
                                            message = translationStatusMessage ?: job.message
                                        )
                                    } else {
                                        job
                                    }
                                }
                            )
                        }
                    }
                    startLocalImageGenerationWatchdog(jobId)
                }
                val generatedImages = runCatching {
                    when (requestedBackend) {
                        ImageBackend.LOCAL -> {
                            val model = requireNotNull(requestedLocalModel) {
                                "The queued local image model snapshot is missing."
                            }
                            val batchPlan = requireNotNull(localBatchPlan) {
                                "The queued local image batch plan is missing."
                            }
                            executeLocalImageBatchPlan(
                                plan = batchPlan,
                                cancellationRequested = {
                                    activeImageGenerationJobId != jobId ||
                                        _uiState.value.imageJobs.firstOrNull { it.id == jobId }
                                            ?.status != ImageGenerationStatusRecord.GENERATING
                                },
                                execute = { child ->
                                    createLocalGeneratedImageAsset(
                                        requestId = child.requestId,
                                        prompt = preparedJobSpec.effectivePrompt(),
                                        model = model,
                                        options = preparedJobSpec.effectiveOptions(child.options),
                                        inputDraft = queuedInputDraft,
                                        chatSessionId = owningChatSessionId,
                                        generationMetadata = preparedJobSpec.toHistoryMetadata(),
                                        batchLineage = child.batchLineage,
                                        outputLineages = child.outputLineages,
                                        onProgress = { childProgress ->
                                            val progress = child.parentProgress(childProgress)
                                            val message = progress.toImageGenerationMessage()
                                            val publishedPreview = publishLocalImagePreview(jobId, progress)
                                            uiStateStore.update { state ->
                                                val job = state.imageJobs.firstOrNull { it.id == jobId }
                                                if (job?.status == ImageGenerationStatusRecord.GENERATING) {
                                                    state.copy(
                                                        imageJobs = state.imageJobs.updateImageJob(
                                                            jobId,
                                                            ImageGenerationStatusRecord.GENERATING,
                                                            message,
                                                            preview = publishedPreview
                                                        )
                                                    )
                                                } else {
                                                    state
                                                }
                                            }
                                        }
                                    )
                                },
                                onChildCompleted = { _, completedCount ->
                                    uiStateStore.update { state -> state.copy(
                                        imageJobs = state.imageJobs.map { job ->
                                            if (job.id == jobId && job.status == ImageGenerationStatusRecord.GENERATING) {
                                                job.copy(
                                                    completedProgressCount = completedCount,
                                                    message = "已完成 $completedCount/${batchPlan.outputCount} 张，正在准备下一张"
                                                )
                                            } else job
                                        }
                                    ) }
                                },
                                cleanup = { candidates ->
                                    candidates.forEach { image ->
                                        image.deleteLocalCopy(imageAssetDirectory)
                                    }
                                }
                            )
                        }
                        ImageBackend.CLOUD -> {
                            require(queuedInputDraft.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE) {
                                "The selected cloud image connector does not implement ${queuedInputDraft.taskMode.wireName}; no request was sent."
                            }
                            require(executionOptions.clipSkip == null &&
                                executionOptions.vaeTiling == null &&
                                executionOptions.preview == null
                            ) {
                                "The selected cloud image connector does not implement local advanced image controls."
                            }
                            val imageConfig = requireNotNull(requestedCloudConfig) {
                                "The queued cloud image model snapshot is missing."
                            }
                            createCloudGeneratedImageAssets(
                                request = CloudImageRequest(
                                    positivePrompt = cleanPrompt,
                                    negativePrompt = executionOptions.negativePrompt,
                                    count = executionOptions.batchCount,
                                    size = executionOptions.width?.let { width ->
                                        executionOptions.height?.let { height -> "$width" + "x" + "$height" }
                                    }
                                ),
                                config = imageConfig,
                                chatSessionId = owningChatSessionId,
                                generationMetadata = executionJobSpec.toHistoryMetadata()
                            )
                        }
                    }
                }.getOrElse { error ->
                    val cancelled = error is CancellationException ||
                        error is LocalImageWorkerCancelledException
                    val message = if (cancelled) {
                        "已取消图片生成"
                    } else {
                        val nativeMessage = localImageGenerationFailureMessage(error)
                        val translatedPrompt = preparedJobSpec.promptExecution
                            ?.effectivePrompt
                            ?.takeIf { it.isNotBlank() && it != preparedJobSpec.prompt }
                        if (translatedPrompt != null) {
                            "$nativeMessage\n建议使用英文提示词重试：$translatedPrompt"
                        } else {
                            nativeMessage
                        }
                    }
                    uiStateStore.update { state ->
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                jobId,
                                if (cancelled) {
                                    ImageGenerationStatusRecord.CANCELLED
                                } else {
                                    ImageGenerationStatusRecord.FAILED
                                },
                                message
                            ),
                            statusMessage = if (cancelled) message else "图片生成失败：$message"
                        )
                    }
                    return@launch
                }
                // A worker/provider is allowed to return an empty list when it has
                // cancelled or failed after creating no artifact.  Treat that as a
                // normal task failure before the commit code dereferences
                // generatedImages.first(); the old path crashed the coroutine (and
                // on some native worker builds the process) while publishing the
                // result, which looked like an immediate app crash to the user.
                if (generatedImages.isEmpty()) {
                    val message = "图像引擎未返回图片文件，请检查模型完整性后重试。"
                    uiStateStore.update { state ->
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                jobId,
                                ImageGenerationStatusRecord.FAILED,
                                message
                            ),
                            statusMessage = message
                        )
                    }
                    return@launch
                }
                val commitError = commitImageLibraryTransaction(
                    imageLibraryCommitPort, jobId, generatedImages
                )
                if (commitError != null) {
                    generatedImages.forEach { image ->
                        val rolledBack = image.deleteLocalCopy(imageAssetDirectory)
                        val requestId = _uiState.value.imageJobs.firstOrNull { it.id == jobId }
                            ?.spec?.chatMessageId
                        if (rolledBack && !requestId.isNullOrBlank()) {
                            runCatching {
                                chatImageCommitJournal.removeAfterConfirmedAssetRollback(
                                    jobId = chatImageCommitJournalJobId(jobId, image.id),
                                    requestId = requestId,
                                    rollbackConfirmed = true
                                )
                            }
                        }
                    }
                    val cancelled = commitError is CancellationException ||
                        _uiState.value.imageJobs.firstOrNull { it.id == jobId }?.status ==
                        ImageGenerationStatusRecord.CANCEL_REQUESTED
                    val message = if (cancelled) {
                        "已取消图片生成"
                    } else {
                        commitError.message ?: "图片库数据库提交失败"
                    }
                    uiStateStore.update { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        if (job?.status == ImageGenerationStatusRecord.DONE) {
                            state
                        } else {
                            state.copy(
                                imageJobs = state.imageJobs.updateImageJob(
                                    jobId,
                                    if (cancelled) {
                                        ImageGenerationStatusRecord.CANCELLED
                                    } else {
                                        ImageGenerationStatusRecord.FAILED
                                    },
                                    message
                                ),
                                statusMessage = if (cancelled) {
                                    message
                                } else {
                                    "图片生成完成，但保存失败：$message"
                                }
                            )
                        }
                    }
                    return@launch
                }
            } finally {
                withContext(NonCancellable) {
                    // The image client unbinds before returning, but the disposable worker may
                    // still hold native mappings until its process dies. Confirm that boundary
                    // before allowing the chat engine to map its weights again.
                    if (chatRuntimeLease != null) {
                        runCatching { localImageWorkerClient.awaitOwnedWorkerRelease() }
                        chatRuntimeLease?.release()
                    }
                }
                cleanupLocalImagePreviews(jobId)
            }
        }
        imageGenerationJob = executionJob
        executionJob.invokeOnCompletion { completion ->
            try {
                synchronized(localImageLoraLifecycleLock) {
                    val ownsActiveGeneration = activeImageGenerationJobId == jobId
                    if (ownsActiveGeneration) {
                        activeImageGenerationJobId = null
                        activeImageGenerationBackend = null
                        activeImageGenerationModelId = null
                    }
                    uiStateStore.update { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        val status = when {
                            job == null || job.status.terminal -> null
                            completion is CancellationException -> ImageGenerationStatusRecord.CANCELLED
                            else -> ImageGenerationStatusRecord.FAILED
                        }
                        val fallbackMessage = when {
                            completion is CancellationException -> "已取消图片生成"
                            completion != null -> "图片任务异常结束：${completion.message ?: completion::class.java.simpleName}"
                            else -> "图片任务提前结束，未返回生成结果。"
                        }
                        state.copy(
                            activeLocalImageLoraIds = if (ownsActiveGeneration) emptySet() else state.activeLocalImageLoraIds,
                            activeLocalImageTextualInversionIds = if (ownsActiveGeneration) {
                                emptySet()
                            } else {
                                state.activeLocalImageTextualInversionIds
                            },
                            imageJobs = if (status != null) {
                                state.imageJobs.updateImageJob(jobId, status, fallbackMessage)
                            } else {
                                state.imageJobs
                            },
                            statusMessage = if (status == ImageGenerationStatusRecord.FAILED) {
                                fallbackMessage
                            } else {
                                state.statusMessage
                            }
                        )
                    }
                    check(releaseObservedImageGenerationLease(generationLease)) {
                        "UI image generation lease was replaced before request completion."
                    }
                }
                persistFinalChatImageRequest(chatImageRequestPort, jobId)
                completeAssistantImageToolTurnForJob(jobId)
                if (imageGenerationJob === executionJob) imageGenerationJob = null
            } finally {
                McaGenerationForegroundService.release(getApplication<Application>(), foregroundLease)
            }
        }
        return true
    }

    private fun startLocalImageGenerationWatchdog(jobId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            delay(LOCAL_IMAGE_GENERATION_WATCHDOG_MS)
            uiStateStore.update { state ->
                val job = state.imageJobs.firstOrNull { it.id == jobId }
                if (job?.status == ImageGenerationStatusRecord.QUEUED || job?.status == ImageGenerationStatusRecord.GENERATING) {
                    val message = "本地生图耗时超过 8 分钟，native 后端仍在运行。可点击停止，当前图执行结束后会退出。"
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(jobId, ImageGenerationStatusRecord.GENERATING, message),
                        statusMessage = message
                    )
                } else {
                    state
                }
            }
        }
    }

    fun cancelImageGeneration() = cancelImageGenerationForJob(expectedJobId = null)

    private fun cancelImageGenerationForJob(expectedJobId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val jobId = activeImageGenerationJobId
            if (expectedJobId != null && jobId != expectedJobId) return@launch
            val localGeneration = activeImageGenerationBackend == ImageBackend.LOCAL
            if (jobId != null) {
                uiStateStore.update { state ->
                    val job = state.imageJobs.firstOrNull { it.id == jobId }
                    if (job == null || job.status.terminal) return@update state
                    val message = if (localGeneration) {
                        "正在停止本地生图，等待 worker 释放本次执行"
                    } else {
                        "已停止图片生成"
                    }
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            if (localGeneration) {
                                ImageGenerationStatusRecord.CANCEL_REQUESTED
                            } else {
                                ImageGenerationStatusRecord.CANCELLED
                            },
                            message
                        ),
                        statusMessage = if (localGeneration) "正在停止本地生图…" else message
                    )
                }
                val nativeCancelRequested = if (localGeneration) {
                    localImageWorkerClient.cancel(activeLocalImageWorkerRequestId ?: jobId)
                } else {
                    false
                }
                if (!(localGeneration && nativeCancelRequested)) {
                    uiStateStore.update { state ->
                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                        if (job?.status != ImageGenerationStatusRecord.CANCEL_REQUESTED) {
                            state
                        } else {
                            state.copy(
                                imageJobs = state.imageJobs.updateImageJob(
                                    jobId,
                                    ImageGenerationStatusRecord.CANCELLED,
                                    "已停止图片生成"
                                ),
                                statusMessage = "已停止图片生成"
                            )
                        }
                    }
                    imageGenerationJob
                        ?.takeIf { activeImageGenerationJobId == jobId }
                        ?.cancel()
                }
            } else {
                imageGenerationJob?.cancel()
                uiStateStore.update { state ->
                    val working = state.imageJobs.firstOrNull {
                        it.status == ImageGenerationStatusRecord.QUEUED ||
                            it.status == ImageGenerationStatusRecord.GENERATING ||
                            it.status == ImageGenerationStatusRecord.CANCEL_REQUESTED
                    }
                    if (working != null) {
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                working.id,
                                ImageGenerationStatusRecord.CANCELLED,
                                "已停止图片生成"
                            ),
                            statusMessage = "已停止图片生成"
                        )
                    } else {
                        state.copy(statusMessage = "当前没有正在生成的图片任务")
                    }
                }
            }
        }
    }

    private fun LocalImageProgress.toImageGenerationMessage(): String {
        val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
        val progress = if (steps > 0) "第 ${step.coerceIn(0, steps)}/$steps 步" else "准备中"
        val size = if (width > 0 && height > 0) " · ${width}x$height" else ""
        val threadText = if (threads > 0) " · ${threads} 线程" else ""
        val batchPrefix = message.substringBefore(" · ")
            .takeIf { prefix -> Regex("^第 [1-8]/[1-8] 张$").matches(prefix) }
        val detailMessage = batchPrefix
            ?.let { prefix -> message.removePrefix("$prefix · ") }
            ?: message
        val status = when {
            cancelRequested || phase == "cancelling" -> "正在停止本地生图 · ${elapsedSeconds}s"
            phase == "loading" -> "正在加载本地图像引擎 · ${elapsedSeconds}s$threadText"
            phase == "conditioning" -> "正在编码提示词 · ${elapsedSeconds}s$threadText"
            phase == "encoding" -> "正在准备初始图像 · ${elapsedSeconds}s$size$threadText"
            phase == "decoding" -> "正在解码图片 · ${elapsedSeconds}s$size"
            phase == "preparing" -> "正在准备采样 · ${elapsedSeconds}s$size$threadText"
            phase == "sampling" && step <= 0 -> {
                if (elapsedSeconds >= 60L) {
                    "首步计算中 · ${elapsedSeconds}s$size$threadText"
                } else {
                    "即将开始采样 · ${elapsedSeconds}s$size$threadText"
                }
            }
            phase == "sampling" -> "本地生图 $progress · ${elapsedSeconds}s$size$threadText"
            phase == "writing" -> "正在写入图片 · ${elapsedSeconds}s"
            phase == "completed" -> "本地生图完成 · ${elapsedSeconds}s"
            phase == "failed" -> detailMessage.ifBlank { "本地生图失败" }
            else -> "正在准备本地生图 · ${elapsedSeconds}s$threadText"
        }
        return appendLocalImagePreviewDegradationMessage(
            message = batchPrefix?.let { "$it · $status" } ?: status,
            previewFailureCode = previewFailureCode
        )
    }


    private fun publishLocalImagePreview(
        jobId: String,
        progress: LocalImageProgress
    ): PublishedLocalImagePreview? = runCatching {
        if (!canPublishLocalImagePreview(jobId)) return@runCatching null
        if (progress.previewRevision <= 0L || progress.previewPath.isBlank()) return@runCatching null
        require(progress.previewMimeType == "image/png") {
            "Native image preview must be a PNG."
        }
        require(progress.previewMode in setOf("projection", "tae", "vae")) {
            "Native image preview reported an unknown mode."
        }
        require(
            progress.previewStep > 0 && progress.previewWidth > 0 &&
                progress.previewHeight > 0 && progress.previewFrameCount > 0 &&
                !progress.previewNoisy
        ) { "Native image preview metadata is incomplete." }

        val app = getApplication<Application>()
        val cacheRoot = app.cacheDir.canonicalFile
        val source = validatedLocalImagePreviewSource(
            cacheRoot = cacheRoot,
            previewPath = progress.previewPath,
            coordinatedRevision = progress.previewRevision
        )
        require(
            source.isFile && source.length() in 1..MAX_LOCAL_IMAGE_PREVIEW_BYTES
        ) { "Native image preview path is not a bounded app-cache file." }
        val bytes = source.readBytes()
        require(bytes.size.toLong() == source.length()) {
            "Native image preview changed while it was being copied."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(
            bounds.outWidth == progress.previewWidth && bounds.outHeight == progress.previewHeight
        ) { "Native image preview dimensions do not match the decoded PNG." }

        // Copying and decoding can take long enough for a cancellation to win. Do not create a
        // new UI-cache artifact once the job has left the only state that may show previews.
        if (!canPublishLocalImagePreview(jobId)) return@runCatching null

        val root = File(cacheRoot, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY).canonicalFile
        require(root.isDirectory || root.mkdirs()) { "Unable to create the image preview directory." }
        val requestToken = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val requestDir = File(root, requestToken).canonicalFile
        require(requestDir.parentFile == root && (requestDir.isDirectory || requestDir.mkdirs())) {
            "Unable to create the request preview directory."
        }
        val fileName = "preview-${progress.previewRevision}.png"
        var createdForThisPublication = false
        val published = File(requestDir, fileName).takeIf(File::isFile)
            ?: run {
                if (!imageAssetWriteMutex.tryLock()) return@runCatching null
                try {
                    if (!canPublishLocalImagePreview(jobId)) return@runCatching null
                    val created = writeImageAssetBytesAtomically(requestDir, fileName, bytes)
                    createdForThisPublication = true
                    created
                } finally {
                    imageAssetWriteMutex.unlock()
                }
            }
        if (!canPublishLocalImagePreview(jobId)) {
            if (createdForThisPublication) {
                published.delete()
            }
            return@runCatching null
        }
        PublishedLocalImagePreview(
            uriString = Uri.fromFile(published).toString(),
            mode = progress.previewMode,
            step = progress.previewStep,
            revision = progress.previewRevision,
            width = progress.previewWidth,
            height = progress.previewHeight
        )
    }.getOrNull()

    private fun canPublishLocalImagePreview(jobId: String): Boolean =
        activeImageGenerationJobId == jobId &&
            _uiState.value.imageJobs.firstOrNull { job -> job.id == jobId }?.status ==
            ImageGenerationStatusRecord.GENERATING

    private fun cleanupLocalImagePreviews(jobId: String) {
        runCatching {
            val cacheRoot = getApplication<Application>().cacheDir.canonicalFile
            val root = File(cacheRoot, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY).canonicalFile
            val requestToken = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
            val requestDir = File(root, requestToken).canonicalFile
            if (requestDir.parentFile == root && requestDir.exists()) {
                requestDir.deleteRecursively()
            }
        }
    }

    fun deleteImageAsset(imageId: String) {
        deleteImageAssets(listOf(imageId))
    }

    fun deleteImageAssets(imageIds: List<String>) {
        deleteImageAssetsOperation(imageAssetDeletionPort, imageIds)
    }

    fun setImageAssetFavorite(imageId: String, favorite: Boolean) {
        setImageAssetFavoriteOperation(imageAssetDeletionPort, imageId, favorite)
    }

    fun clearImageLibrary() {
        clearImageLibraryOperation(imageAssetDeletionPort)
    }

    fun exportImageLibraryBackup(uriString: String, favoritesOnly: Boolean) {
        val destination = runCatching { Uri.parse(uriString) }
            .getOrNull()
            ?.takeIf { !it.scheme.isNullOrBlank() }
        if (destination == null) {
            uiStateStore.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(
                        message = "备份目标无效",
                        failed = true
                    )
                )
            }
            return
        }
        val started = startImageLibraryBackupJob(
            reservation = ImageLibraryBackupState(
                running = true,
                importing = false,
                total = 0
            )
        ) {
            try {
                awaitImageLibraryStartupReconciliation()
                val result = imageLibraryMutationMutex.withLock {
                    val snapshot = _uiState.value.images
                    val total = snapshot.count { !favoritesOnly || it.favorite }
                    uiStateStore.update { state ->
                        state.copy(
                            imageLibraryBackup = state.imageLibraryBackup.copy(total = total)
                        )
                    }
                    imageLibraryBackup.export(
                        destination = destination,
                        images = snapshot,
                        favoritesOnly = favoritesOnly
                    ) { done, progressTotal ->
                        uiStateStore.update { state ->
                            state.copy(
                                imageLibraryBackup = state.imageLibraryBackup.copy(
                                    done = done,
                                    total = progressTotal
                                )
                            )
                        }
                    }
                }
                val message = buildString {
                    append("已导出 ")
                    append(result.exported)
                    append(" 张图片")
                    if (result.skipped > 0) {
                        append("；跳过 ")
                        append(result.skipped)
                        append(" 个缺失或无效文件")
                    }
                }
                uiStateStore.update {
                    it.copy(
                        imageLibraryBackup = ImageLibraryBackupState(message = message),
                        statusMessage = message
                    )
                }
            } catch (error: CancellationException) {
                val deleted = withContext(NonCancellable + Dispatchers.IO) {
                    deleteIncompleteImageLibraryBackup(destination)
                }
                uiStateStore.update {
                    it.copy(
                        imageLibraryBackup = ImageLibraryBackupState(
                            message = if (deleted) {
                                "已取消导出"
                            } else {
                                "已取消导出；未能删除不完整的备份文件"
                            },
                            failed = !deleted
                        )
                    )
                }
                throw error
            } catch (error: Throwable) {
                val deleted = withContext(Dispatchers.IO) {
                    deleteIncompleteImageLibraryBackup(destination)
                }
                val message = buildString {
                    append(error.imageLibraryBackupFailureMessage())
                    if (!deleted) append("；未能删除不完整的备份文件")
                }
                uiStateStore.update {
                    it.copy(
                        imageLibraryBackup = ImageLibraryBackupState(message = message, failed = true),
                        statusMessage = message
                    )
                }
            }
        }
        if (!started) {
            showStatus("图片库备份任务正在进行")
        }
    }

    private fun deleteIncompleteImageLibraryBackup(destination: Uri): Boolean =
        runCatching {
            DocumentsContract.deleteDocument(
                getApplication<Application>().contentResolver,
                destination
            )
        }.getOrDefault(false)

    fun importImageLibraryBackup(uriString: String) {
        val source = runCatching { Uri.parse(uriString) }
            .getOrNull()
            ?.takeIf { !it.scheme.isNullOrBlank() }
        if (source == null) {
            uiStateStore.update {
                it.copy(
                    imageLibraryBackup = ImageLibraryBackupState(
                        message = "备份文件无效",
                        failed = true
                    )
                )
            }
            return
        }
        val started = startImageLibraryBackupJob(
            reservation = ImageLibraryBackupState(
                running = true,
                importing = true
            )
        ) {
            try {
                awaitImageLibraryStartupReconciliation()
                val installedModelIds = _uiState.value.localImageModels.mapTo(mutableSetOf()) { it.id }
                    .apply {
                        addAll(
                            _uiState.value.cloudModels
                                .filter { it.kind == CloudModelKind.IMAGE }
                                .map { it.id }
                        )
                    }
                val result = imageAssetWriteMutex.withLock {
                    imageLibraryMutationMutex.withLock {
                        val imported = imageLibraryBackup.import(
                            source = source,
                            existingImages = _uiState.value.images,
                            installedModelIds = installedModelIds
                        ) { done, total ->
                            uiStateStore.update { state ->
                                state.copy(
                                    imageLibraryBackup = state.imageLibraryBackup.copy(
                                        done = done,
                                        total = total
                                    )
                                )
                            }
                        }
                        val restoredImages = chatSessionStore.loadImages().sortedImagesForLibrary()
                        uiStateStore.update { it.copy(images = restoredImages) }
                        imported
                    }
                }
                val message = buildString {
                    append("已恢复 ")
                    append(result.imported)
                    append(" 张图片")
                    if (result.duplicates > 0) {
                        append("；跳过重复 ")
                        append(result.duplicates)
                        append(" 张")
                    }
                    if (result.failed > 0) {
                        append("；失败 ")
                        append(result.failed)
                        append(" 张")
                    }
                    if (result.missingModelIds.isNotEmpty()) {
                        append("；")
                        append(result.missingModelIds.size)
                        append(" 个原模型当前未安装，历史仍已保留")
                    }
                }
                uiStateStore.update {
                    it.copy(
                        imageLibraryBackup = ImageLibraryBackupState(message = message),
                        statusMessage = message
                    )
                }
            } catch (error: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    val restoredImages = chatSessionStore.loadImages().sortedImagesForLibrary()
                    uiStateStore.update {
                        it.copy(
                            images = restoredImages,
                            imageLibraryBackup = ImageLibraryBackupState(
                                message = "已取消恢复；已完成提交的图片仍会保留"
                            )
                        )
                    }
                }
                throw error
            } catch (error: Throwable) {
                val message = error.imageLibraryBackupFailureMessage()
                uiStateStore.update {
                    it.copy(
                        imageLibraryBackup = ImageLibraryBackupState(message = message, failed = true),
                        statusMessage = message
                    )
                }
            }
        }
        if (!started) {
            showStatus("图片库备份任务正在进行")
        }
    }

    fun cancelImageLibraryBackup() {
        synchronized(imageLibraryBackupLifecycleLock) { imageLibraryBackupJob }?.cancel()
    }

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

    private fun rejectImageModelSwitchWhileGenerationIsActive(): Boolean {
        val activeJobId = activeImageGenerationJobId ?: return false
        val activeModelName = _uiState.value.imageJobs
            .firstOrNull { job -> job.id == activeJobId }
            ?.modelName
            ?.takeIf(String::isNotBlank)
        uiStateStore.update { state ->
            state.copy(
                statusMessage = buildString {
                    append("当前图片任务已锁定")
                    activeModelName?.let { append("使用 $it") }
                    append("；完成或取消后再切换模型。")
                }
            )
        }
        return true
    }


    fun verifyLocalImageModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val model = _uiState.value.localImageModels.firstOrNull { it.matchesCatalogId(modelId) }
            if (model == null) {
                showStatus("未找到本地图像生成引擎")
                return@launch
            }
            val verificationRequestId = "verify-image-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            val generationLease = tryAcquireObservedImageGenerationLease(verificationRequestId)
            if (generationLease == null) {
                uiStateStore.update {
                    it.copy(statusMessage = "已有图片任务正在运行，请等待完成后再校验模型。")
                }
                return@launch
            }
            try {
                model.localImageStructuralReadinessMessage()?.let { readiness ->
                    uiStateStore.update {
                        it.copy(statusMessage = "图像引擎校验未开始：$readiness")
                    }
                    return@launch
                }
                busy("正在校验 ${model.displayName}...")
                val primary = File(model.path)
                val readiness = model.localImageReadinessMessage()
                val structuralReadiness = model.localImageStructuralReadinessMessage()
                val readable = model.configured && primary.isFile && primary.canRead()
                var qnnExecution: QnnImageExecutionEvidence? = null
                var verification = when {
                    !readable -> false to "主模型文件不可读，请重新导入或重新下载。"
                    model.runtime == LocalImageRuntime.MNN_DIFFUSION && structuralReadiness != null ->
                        false to structuralReadiness
                    model.runtime == LocalImageRuntime.MNN_DIFFUSION ->
                        verifyMnnDiffusionImageRuntime(localImageWorkerClient, model)
                    model.runtime == LocalImageRuntime.QNN_HTP && structuralReadiness != null ->
                        false to structuralReadiness
                    model.runtime == LocalImageRuntime.QNN_HTP ->
                        verifyQnnImageRuntime(localImageWorkerClient, model).also { qnnExecution = it.execution }.verification
                    readiness != null -> false to readiness
                    else -> {
                        val componentText = if (model.componentCount > 1) "，组件 ${model.componentCount} 个" else ""
                        true to "主模型可读$componentText，可用于图片页本地生图。"
                    }
                }
                val qnnPersistence = qnnVerificationPersistence(model, verification, qnnExecution)
                verification = qnnPersistence.verification
                val verifiedModel = model.copy(
                    verificationStatus = verification.toLocalImageVerificationStatus(model.runtime, model.family),
                    verificationMessage = verification.second,
                    verifiedAt = System.currentTimeMillis(),
                    qnnVerificationStamp = qnnPersistence.stamp
                )
                val models = localImageModelStore.updateModel(verifiedModel)
                val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(getApplication(), models)
                val selectedId = selectedReadyLocalImageModelId(
                    models = models,
                    qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                    preferredId = localImageModelStore.loadSelectedModelId()
                )
                if (selectedId != localImageModelStore.loadSelectedModelId()) {
                    localImageModelStore.saveSelectedModelId(selectedId)
                }
                val message = if (verification.first) {
                    "图像引擎校验通过：${verification.second}"
                } else {
                    "图像引擎校验失败：${verification.second}"
                }
                managedRuntimeReadinessRefreshGate.invalidate()
                uiStateStore.update {
                    it.copy(
                        localImageModels = models,
                        qnnImageVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                        selectedLocalImageModelId = selectedId,
                        selectedImageBackend = if (selectedId == null && it.selectedImageBackend == ImageBackend.LOCAL) {
                            ImageBackend.CLOUD
                        } else {
                            it.selectedImageBackend
                        },
                        busy = false,
                        statusMessage = message
                    )
                }
            } finally {
                check(releaseObservedImageGenerationLease(generationLease)) {
                    "Image verification lease was replaced before request completion."
                }
            }
        }
    }

    private fun Pair<Boolean, String>.toLocalImageVerificationStatus(
        runtime: LocalImageRuntime,
        family: LocalImageModelFamily
    ): LocalImageVerificationStatus =
        when {
            !first -> LocalImageVerificationStatus.FAILED
            runtime == LocalImageRuntime.MNN_DIFFUSION -> LocalImageVerificationStatus.MNN_SMOKE_PASSED
            runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL ->
                LocalImageVerificationStatus.QNN_IMAGE_SMOKE_PASSED
            else -> LocalImageVerificationStatus.PASSED
        }

    private data class QnnVerificationPersistence(
        val verification: Pair<Boolean, String>,
        val stamp: String
    )


    private fun executionWithQnnVerificationEvidence(
        model: LocalImageModelRecord,
        result: LocalImageResult,
        options: LocalImageGenerationOptions,
        requestId: String,
        attemptId: String,
        entryPoint: QnnImageEvidenceEntryPoint
    ): String {
        if (model.runtime != LocalImageRuntime.QNN_HTP) return result.executionMetadataJson
        val execution = JSONObject(result.executionMetadataJson)
        runCatching {
            val bundleRoot = requireNotNull(model.qnnBundleRootForVerification())
            val resolved = resolveLocalImageExecutionProfile(
                model, options.normalizedForPromptExecutionProfile(model.runtime), bundleRoot
            ).layers.resolved
            val fullRegression = options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
                resolved.width == 512 && resolved.height == 512 && resolved.steps == 20
            val evidence = QnnImageExecutionEvidence.fromNativeImageExecution(
                level = if (fullRegression) QnnImageEvidenceLevel.FULL_IMAGE_REGRESSION
                    else QnnImageEvidenceLevel.PRODUCT_IMAGE_SMOKE,
                entryPoint = entryPoint,
                requestId = requestId,
                attemptId = attemptId,
                expected = QnnImageRunParameters(resolved.width, resolved.height, resolved.steps, resolved.seed),
                executionMetadataJson = result.executionMetadataJson,
                output = result.bytes
            )
            execution.put("qnnVerificationStamp", JSONObject(qnnImageVerificationStampFor(
                getApplication<Application>(), bundleRoot, execution = evidence
            )))
        }.onFailure { error ->
            execution.put("qnnVerificationEvidenceError", error.message ?: error.javaClass.simpleName)
        }
        return execution.toString()
    }

    private fun qnnVerificationPersistence(
        model: LocalImageModelRecord,
        verification: Pair<Boolean, String>,
        execution: QnnImageExecutionEvidence? = null
    ): QnnVerificationPersistence {
        if (model.runtime != LocalImageRuntime.QNN_HTP || !verification.first) {
            return QnnVerificationPersistence(verification, "")
        }
        return runCatching {
            val bundleRoot = model.bundleRoot
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?: File(model.path).parentFile
                ?: error("QNN 图像引擎缺少完整组件目录。")
            QnnVerificationPersistence(
                verification = verification,
                stamp = qnnImageVerificationStampFor(
                    getApplication<Application>(), bundleRoot,
                    execution = requireNotNull(execution) { "QNN execution evidence is missing." }
                )
            )
        }.getOrElse { error ->
            QnnVerificationPersistence(
                verification = false to "QNN 生图校验完成，但无法保存当前设备/运行时校验戳：${error.message ?: "未知错误"}",
                stamp = ""
            )
        }
    }



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
            val models = localImageModelStore.loadModels()
            val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(getApplication(), models)
            val selectedId = selectedReadyLocalImageModelId(
                models = models,
                qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                preferredId = localImageModelStore.loadSelectedModelId()
            )
            managedRuntimeReadinessRefreshGate.invalidate()
            uiStateStore.update {
                it.copy(
                    localImageModels = models,
                    qnnImageVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                    selectedLocalImageModelId = selectedId,
                    selectedImageBackend = if (selectedId == null && it.selectedImageBackend == ImageBackend.LOCAL) ImageBackend.CLOUD else it.selectedImageBackend,
                    statusMessage = if (success) "已删除本地图像生成引擎：${removed?.displayName ?: "模型"}" else "未找到本地图像生成引擎"
                )
            }
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
        val updatedAssistants = updatedAssistantsWithParams(scopedParams)
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
        val identity = activeRuntimeIdentity ?: return
        val active = engine.activeExecutionProfile() ?: return
        if (_uiState.value.loadedModelId == null || active.runtimeIdentity.identityHash != identity.identityHash) return
        val stageGeneration = directParameterStageGeneration.incrementAndGet()
        directParameterStageJob?.cancel()
        directParameterStageJob = viewModelScope.launch(Dispatchers.IO) {
            delay(120L)
            directParameterStageMutex.withLock {
                withContext(NonCancellable) stage@{
                    if (stageGeneration != directParameterStageGeneration.get()) return@stage
                    if (adaptiveTuningJob?.isActive == true || activeTuningJobId != null) {
                        uiStateStore.update {
                            it.copy(statusMessage = "当前调优任务尚未结束；完成或取消后再应用自定义运行参数。")
                        }
                        return@stage
                    }
                    val latestParams = _uiState.value.params
                    if (latestParams != params) return@stage
                    runCatching {
                val nextRevision = nextExecutionProfileRevision(
                    committedRevision = active.revision,
                    persistedRevisions = runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
                )
                val requested = engine.resolveExecutionProfile(
                    identity = identity,
                    requestedParamsJson = latestParams.toJson(),
                    profileId = "manual-${UUID.randomUUID().toString().replace("-", "").take(20)}",
                    revision = nextRevision
                ).profile
                val candidate = mergeUserRequestedExecutionProfile(
                    base = active,
                    requested = requested,
                    authoritativeFields = runtimeUserOverrideFields
                )
                if (candidate.resolvedLoadSignature.digest == active.resolvedLoadSignature.digest &&
                    candidate.committedExecutionSignature.digest == active.committedExecutionSignature.digest
                ) {
                    pendingProfileTransactionId = null
                    uiStateStore.update {
                        it.copy(
                            reloadRequired = false,
                            pendingProfile = null,
                            statusMessage = "自定义参数与当前生效配置一致。"
                        )
                    }
                    return@runCatching
                }

                runtimeProfileStore.pendingTransaction(identity.identityHash)?.let { existing ->
                    if (existing.journal.transactionId != pendingProfileTransactionId) {
                        error("已有调优候选等待处理，请先应用、回滚或取消该候选。")
                    }
                    val recovery = runtimeProfileStore.rejectCandidate(
                        transactionId = existing.journal.transactionId,
                        failureStage = "USER_EDIT_REPLACED",
                        failureCode = "SUPERSEDED",
                        failureSummary = "用户继续修改运行参数，旧候选已被新候选替换。"
                    )
                    runtimeProfileStore.completeRecovery(
                        transactionId = existing.journal.transactionId,
                        restoredProfileId = recovery.rollbackProfileId
                    )
                }

                val transactionId = "manual-${UUID.randomUUID()}"
                runtimeProfileStore.stageCandidate(
                    snapshot = candidate.toPersistedExecutionProfileSnapshot(
                        parentCommittedProfileId = active.profileId,
                        verificationLevel = PersistedProfileVerificationLevel.SAFE,
                        sourceSummaryJson = JSONObject()
                            .put("kind", "user_parameter_edit")
                            .put("authoritativeFields", JSONArray(runtimeUserOverrideFields.sorted()))
                            .toString()
                    ),
                    transactionId = transactionId,
                    rollbackTargetProfileId = active.profileId
                )
                if (stageGeneration != directParameterStageGeneration.get() || _uiState.value.params != params) {
                    val recovery = runtimeProfileStore.rejectCandidate(
                        transactionId = transactionId,
                        failureStage = "USER_EDIT_SUPERSEDED",
                        failureCode = "SUPERSEDED",
                        failureSummary = "候选建立期间用户再次修改参数，失效候选已撤销。"
                    )
                    runtimeProfileStore.completeRecovery(transactionId, recovery.rollbackProfileId)
                    return@runCatching
                }
                pendingProfileTransactionId = transactionId
                val currentCtx = active.resolvedLoadBoundValues.toJsonObject().optInt("n_ctx", _uiState.value.stats.nCtx)
                val pendingCtx = candidate.resolvedLoadBoundValues.toJsonObject().optInt("n_ctx", latestParams.nCtx)
                uiStateStore.update {
                    it.copy(
                        reloadRequired = true,
                        pendingProfile = AgentPendingProfile(
                            profileId = candidate.profileId,
                            revision = candidate.revision,
                            summary = "当前 n_ctx=$currentCtx · 待应用 n_ctx=$pendingCtx",
                            readyToApply = true
                        ),
                        tuningJobState = AgentTuningJobState.PAUSED,
                        tuningPhase = "自定义参数等待应用",
                        statusMessage = "上下文已修改为 $pendingCtx，需要重新加载后生效；当前聊天仍使用 $currentCtx。"
                    )
                }
                    }.onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        uiStateStore.update {
                            it.copy(
                                pendingProfile = null,
                                reloadRequired = true,
                                statusMessage = "自定义参数候选建立失败：${error.message ?: "未知错误"}"
                            )
                        }
                    }
                }
            }
        }
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
    ) {
        val cleanName = name.trim().take(36).ifBlank { "未命名助手" }
        val cleanAvatar = avatar.trim().take(4)
        val cleanTag = tag.trim().take(24)
        val cleanPrompt = boundedManualAssistantSystemPrompt(systemPrompt)
        val cleanDefaultModelMode = defaultModelMode.normalizedAssistantModelMode()
        val cleanDefaultModelId = defaultModelId
            ?.trim()
            ?.takeIf { it.isNotBlank() && cleanDefaultModelMode != ASSISTANT_MODEL_MODE_FOLLOW_CURRENT }
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress() || state.isGenerating ||
            hasActiveAssistantImageExecution(state.activeChatSessionId)) {
            showStatus("请先结束当前角色请求，再保存角色卡。")
            return
        }
        val now = System.currentTimeMillis()
        val existing = id?.let { assistantId -> state.assistants.firstOrNull { it.id == assistantId } }
        val assistant = (existing ?: AssistantRecord(name = cleanName, createdAt = now)).copy(
            name = cleanName,
            avatar = cleanAvatar,
            tag = cleanTag,
            systemPrompt = cleanPrompt,
            defaultModelMode = cleanDefaultModelMode,
            defaultModelId = cleanDefaultModelId,
            characterCardJson = existing?.characterCardJson,
            paramsJson = state.params.copy(
                systemPrompt = cleanPrompt,
                temperature = temperature.coerceIn(0f, 2f),
                topP = topP.coerceIn(0f, 1f),
                nPredict = nPredict.coerceIn(16, 65_536),
                topK = topK.coerceIn(0, 1000),
                minP = minP.coerceIn(0f, 1f),
                repeatPenalty = repeatPenalty.coerceIn(0.5f, 2f),
                presencePenalty = presencePenalty.coerceIn(-2f, 2f),
                frequencyPenalty = frequencyPenalty.coerceIn(-2f, 2f),
                stopWords = stopWords.asSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it.length <= 128 }
                    .distinct()
                    .take(32)
                    .toList(),
                reasoningMode = reasoningMode,
                hideReasoning = reasoningMode == ReasoningMode.OFF
            ).toAssistantGenerationJson()
                .withAssistantPromptProvenance(AssistantPromptProvenance.USER_AUTHORED),
            memoryEnabled = memoryEnabled,
            memorySummaryInterval = memorySummaryInterval.coerceIn(2, 100),
            webSearchEnabled = webSearchEnabled,
            fileContextEnabled = fileContextEnabled,
            updatedAt = now
        )
        val updatedAssistants = if (existing == null) {
            (state.assistants + assistant).distinctBy { it.id }
        } else {
            state.assistants.map { if (it.id == assistant.id) assistant else it }
        }
        val shouldSelectAssistant = existing == null || existing.id == state.selectedAssistantId
        if (shouldSelectAssistant && existing != null) assistantStore.saveSelectedAssistantId(assistant.id)
        val updatedParams = assistant.toGenerationParams(state.params)
        if (shouldSelectAssistant && existing != null) {
            GenerationPreferences.persistGenerationParams(getApplication(), updatedParams)
            // Editing the active assistant is an explicit user change for the
            // currently selected model; retain it in that model's profile.
            persistModelGenerationParams(state, updatedParams)
        }
        // An explicit edit applies to this role's active conversation only. Other
        // conversations keep the prompt version they captured when created.
        val activeSession = state.chatSessions.firstOrNull { it.id == state.activeChatSessionId }
        val applyToActiveConversation = existing != null && shouldSelectAssistant &&
            activeSession?.assistantId == assistant.id &&
            activeSession?.assistantSnapshot?.assistantId == assistant.id &&
            activeSession?.mixedAssistantHistory == false
        val updatedSessions = if (applyToActiveConversation) state.chatSessions.map { session ->
            val previousSnapshot = session.assistantSnapshot
            if (session.id != state.activeChatSessionId || session.mixedAssistantHistory ||
                session.assistantId != assistant.id ||
                previousSnapshot?.assistantId != assistant.id) session else {
                val revisedSnapshot = assistant.toConversationSnapshot(previousSnapshot.capturedAt).copy(
                    priorSystemPromptHashes = (
                        previousSnapshot.priorSystemPromptHashes +
                            com.muyuchat.core.engine.PrefixCacheKey.sha256Utf8(previousSnapshot.systemPrompt)
                        ).distinct().takeLast(32)
                )
                session.copy(assistantSnapshot = revisedSnapshot)
            }
        } else state.chatSessions
        uiStateStore.update {
            it.copy(
                assistants = updatedAssistants,
                selectedAssistantId = if (shouldSelectAssistant && existing != null) assistant.id else state.selectedAssistantId,
                params = if (shouldSelectAssistant && existing != null) updatedParams else state.params,
                chatSessions = updatedSessions,
                statusMessage = when {
                    existing == null -> "已创建助手：${assistant.name}"
                    applyToActiveConversation ->
                        "已更新助手：${assistant.name}；当前对话已应用新的角色卡。"
                    else -> "已更新助手：${assistant.name}"
                }
            )
        }
        persistAssistantsAsync()
        if (existing != null) persistChatSessions(updatedSessions)
        if (applyToActiveConversation) {
            // The persona prefix changed. Do not allow a local runner to continue from the
            // previous role's KV cache, regardless of whether the assistant was newly created.
            markLocalConversationContextInvalid()
        }
        if (existing == null) {
            selectAssistant(assistant.id)
            persistChatSessions()
            showStatus("已创建助手：${assistant.name}")
        } else if (shouldSelectAssistant) {
            applyAssistantDefaultModel(assistant)
        }
    }

    fun upsertAssistantMemory(assistantId: String, id: String?, scope: String, content: String) {
        val state = _uiState.value
        if (state.assistants.none { it.id == assistantId }) return
        if (scope !in setOf("user_profile", "role_progress")) return
        val cleanContent = content.trim().take(4_096)
        if (cleanContent.isBlank()) return
        val existing = id?.let { memoryId ->
            state.assistantMemories.firstOrNull { it.id == memoryId && it.assistantId == assistantId }
        }
        if (id != null && existing == null) return
        val record = MemoryRecord(
            id = existing?.id ?: UUID.randomUUID().toString(),
            assistantId = assistantId,
            scope = scope,
            content = cleanContent,
            source = "manual",
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(chatSessionStore.upsertMemory(record)) { "记忆与角色不匹配" }
                val refreshed = chatSessionStore.loadMemories(assistantId)
                uiStateStore.update { current ->
                    current.copy(
                        assistantMemories = current.assistantMemories.filterNot { it.assistantId == assistantId } + refreshed,
                        statusMessage = "角色记忆已保存"
                    )
                }
                markLocalConversationContextInvalid()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showStatus("保存角色记忆失败：${error.message ?: "存储错误"}")
            }
        }
    }

    fun deleteAssistantMemory(assistantId: String, id: String) {
        if (_uiState.value.assistantMemories.none { it.id == id && it.assistantId == assistantId }) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                check(chatSessionStore.deleteMemory(id, assistantId)) { "记忆不存在或不属于该角色" }
                uiStateStore.update { current ->
                    current.copy(
                        assistantMemories = current.assistantMemories.filterNot {
                            it.id == id && it.assistantId == assistantId
                        },
                        statusMessage = "角色记忆已删除"
                    )
                }
                markLocalConversationContextInvalid()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showStatus("删除角色记忆失败：${error.message ?: "存储错误"}")
            }
        }
    }

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
    ) {
        if (user.content.isBlank() || assistant.content.isBlank()) return
        val turn = AssistantMemoryTurnRecord(
            id = assistant.id,
            assistantId = assistantId,
            sessionId = sessionId,
            userText = user.content,
            assistantText = assistant.content,
            createdAt = assistant.createdAt
        )
        viewModelScope.launch(Dispatchers.IO) {
            conversationPersistJob?.join()
            val state = _uiState.value
            if (state.assistants.none { it.id == assistantId && it.memoryEnabled } ||
                state.chatSessions.none { it.id == sessionId && it.assistantSnapshot == assistantSnapshot } ||
                !state.chatSessions.containsCompletedMemoryTurn(turn)) return@launch
            val inserted = runCatching { chatSessionStore.appendMemoryTurn(turn) }.getOrDefault(false)
            if (inserted) {
                if (_uiState.value.assistants.none { it.id == assistantId }) {
                    chatSessionStore.deleteAssistantMemoryData(assistantId)
                } else if (_uiState.value.chatSessions.containsCompletedMemoryTurn(turn)) {
                    scheduleCharacterMemorySummary(assistantId, backend, params, cloudConfig, localIdentity)
                }
            }
        }
    }

    private fun scheduleCharacterMemorySummary(
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) {
        synchronized(characterMemorySummaryJobs) {
            if (characterMemorySummaryJobs[assistantId]?.isActive == true) {
                characterMemorySummaryReschedule.add(assistantId)
                return
            }
            lateinit var job: Job
            job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                summarizeCharacterMemory(assistantId, backend, params, cloudConfig, localIdentity)
            }
            characterMemorySummaryJobs[assistantId] = job
            job.invokeOnCompletion {
                val reschedule = synchronized(characterMemorySummaryJobs) {
                    if (characterMemorySummaryJobs[assistantId] === job) {
                        characterMemorySummaryJobs.remove(assistantId)
                    }
                    characterMemorySummaryReschedule.remove(assistantId)
                }
                if (reschedule) {
                    scheduleCharacterMemorySummary(assistantId, backend, params, cloudConfig, localIdentity)
                }
            }
            job.start()
        }
    }

    private suspend fun summarizeCharacterMemory(
        assistantId: String,
        backend: ChatBackend,
        params: GenerationParams,
        cloudConfig: CloudApiConfig?,
        localIdentity: String?
    ) {
        val selected = _uiState.value.assistants.firstOrNull { it.id == assistantId && it.memoryEnabled }
            ?: return
        var pending = chatSessionStore.loadMemoryTurns(assistantId)
        var remaining = pending.size
        if (remaining < selected.memorySummaryInterval.coerceIn(2, 100)) return
        while (remaining > 0 && pending.isNotEmpty()) {
            val previousAuto = chatSessionStore.loadMemories(assistantId)
                .firstOrNull { it.source == "automatic" || it.source == "auto" }
            val prepared = listOf(24, 12, 6, 3, 1)
                .map { minOf(it, remaining, pending.size) }
                .distinct()
                .firstNotNullOfOrNull { size ->
                    val turns = pending.take(size)
                    buildCharacterMemorySummaryRequest(turns, previousAuto, params)
                        ?.let { turns to it }
                } ?: return
            val (batch, request) = prepared
            fun stillCurrent(): Boolean = _uiState.value.assistants.any {
                it.id == assistantId && it.memoryEnabled
            } && (backend != ChatBackend.LOCAL ||
                localIdentity != null && activeRuntimeIdentity?.identityHash == localIdentity)
            if (!stillCurrent()) return
            val raw = try {
                withTimeoutOrNull(30_000L) {
                    if (backend == ChatBackend.CLOUD) {
                        val config = cloudConfig ?: return@withTimeoutOrNull null
                        collectContextSummary(cloudChatProvider.streamChat(config, request), ::stillCurrent)
                    } else {
                        withContext(Dispatchers.IO) {
                            val lease = engine.acquireExclusiveLifecycleLease(stopActiveGeneration = false)
                            try {
                                if (!stillCurrent()) return@withContext null
                                collectContextSummary(
                                    engine.streamChat(
                                        request,
                                        LocalChatExecutionContext(
                                            requestId = "character-memory-${UUID.randomUUID()}",
                                            lifecycleLease = lease,
                                            includeDeviceClockContext = false
                                        )
                                    ),
                                    ::stillCurrent
                                )
                            } finally {
                                localConversationContextNeedsInvalidation = true
                                localConversationContextInvalidationSequence.incrementAndGet()
                                withContext(NonCancellable) { lease.release() }
                            }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return
            val validated = validateCharacterMemorySummary(raw, batch, previousAuto) ?: return
            if (!stillCurrent()) return
            val currentAuto = chatSessionStore.loadMemories(assistantId)
                .firstOrNull { it.source == "automatic" || it.source == "auto" }
            if (currentAuto != previousAuto) return
            val summary = MemoryRecord(
                assistantId = assistantId,
                scope = "auto_summary",
                content = validated,
                source = "automatic"
            )
            val committed = chatSessionPersistenceMutex.withLock {
                if (!stillCurrent() || batch.any { turn ->
                        !_uiState.value.chatSessions.containsCompletedMemoryTurn(turn)
                    }) false
                else chatSessionStore.commitMemorySummary(assistantId, batch.map { it.id }, summary)
            }
            if (!committed) return
            val refreshed = chatSessionStore.loadMemories(assistantId)
            uiStateStore.update { state ->
                state.copy(assistantMemories = state.assistantMemories.filterNot {
                    it.assistantId == assistantId
                } + refreshed)
            }
            markLocalConversationContextInvalid()
            remaining -= batch.size
            pending = chatSessionStore.loadMemoryTurns(assistantId)
        }
        val interval = _uiState.value.assistants.firstOrNull { it.id == assistantId && it.memoryEnabled }
            ?.memorySummaryInterval?.coerceIn(2, 100) ?: return
        if (pending.size >= interval) {
            synchronized(characterMemorySummaryJobs) {
                characterMemorySummaryReschedule.add(assistantId)
            }
        }
    }

    /**
     * The ViewModel side of [WebSearchConfigPort]. Six members; the coroutine scope is passed to the
     * operations as a parameter instead, because a scope belongs to the caller.
     */
    private val webSearchConfigPort: WebSearchConfigPort =
        object : WebSearchConfigPort {
            override val context: Application
                get() = this@MainViewModel.getApplication()

            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun saveConfig(config: WebSearchConfig) {
                this@MainViewModel.webSearchStore.save(config)
            }

            override fun appendDiagnostic(
                record: WebSearchDiagnosticRecord
            ): List<WebSearchDiagnosticRecord> =
                this@MainViewModel.appendWebSearchDiagnostic(record)

            override val provider: WebSearchProvider
                get() = this@MainViewModel.webSearchProvider
        }

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









    fun toggleWebSearchForNextTurn() {
        val state = _uiState.value
        val assistantDefault = state.selectedAssistant()?.webSearchEnabled == true
        if (!state.webSearchConfig.enabled) {
            uiStateStore.update {
                it.copy(
                    webSearchTurnMode = WebSearchTurnMode.FOLLOW,
                    webSearchOneShotEnabled = false,
                    webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                    statusMessage = "请先启用联网检索"
                )
            }
            return
        }
        uiStateStore.update {
            val nextMode = when (it.webSearchTurnMode) {
                WebSearchTurnMode.FOLLOW -> if (assistantDefault) WebSearchTurnMode.OFF else WebSearchTurnMode.ON
                WebSearchTurnMode.ON -> WebSearchTurnMode.OFF
                WebSearchTurnMode.OFF -> WebSearchTurnMode.FOLLOW
            }
            it.copy(
                webSearchTurnMode = nextMode,
                webSearchOneShotEnabled = nextMode == WebSearchTurnMode.ON,
                webSearchStatusMessage = when (nextMode) {
                    WebSearchTurnMode.ON -> when {
                        it.webSearchConfig.realSearchConfigured -> "本轮将联网检索"
                        it.webSearchConfig.isPublicCheckSource -> "当前是协议自检源；本轮仅可读取网页链接"
                        else -> "本轮仅可读取网页链接"
                    }
                    WebSearchTurnMode.OFF -> "本轮不使用联网检索"
                    WebSearchTurnMode.FOLLOW -> if (assistantDefault) "跟随助手默认联网" else "跟随智能联网策略"
                },
                statusMessage = when (nextMode) {
                    WebSearchTurnMode.ON -> "本轮已开启联网检索"
                    WebSearchTurnMode.OFF -> "本轮已关闭联网检索"
                    WebSearchTurnMode.FOLLOW -> "联网检索跟随默认策略"
                }
            )
        }
    }

    fun cycleWebSearchResearchModeForNextTurn() {
        val state = _uiState.value
        if (!state.webSearchConfig.enabled) {
            uiStateStore.update {
                it.copy(
                    webSearchResearchModeOverride = null,
                    webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                    statusMessage = "请先启用联网检索"
                )
            }
            return
        }
        uiStateStore.update {
            val current = it.webSearchResearchModeOverride ?: it.webSearchConfig.researchMode
            val next = when (current) {
                WebSearchResearchMode.AUTO -> WebSearchResearchMode.DEEP
                WebSearchResearchMode.DEEP -> WebSearchResearchMode.OFF
                WebSearchResearchMode.OFF -> WebSearchResearchMode.AUTO
            }
            it.copy(
                webSearchResearchModeOverride = next.takeUnless { mode -> mode == it.webSearchConfig.researchMode },
                webSearchStatusMessage = "本轮研究模式：${next.label}",
                statusMessage = "本轮研究模式：${next.label}"
            )
        }
    }

    fun selectWebSearchResearchModeForNextTurn(mode: String) {
        val state = _uiState.value
        if (!state.webSearchConfig.enabled) {
            uiStateStore.update {
                it.copy(
                    webSearchResearchModeOverride = null,
                    webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                    statusMessage = "请先启用联网检索"
                )
            }
            return
        }
        val selected = WebSearchResearchMode.from(mode)
        uiStateStore.update {
            it.copy(
                webSearchResearchModeOverride = selected.takeUnless { next -> next == it.webSearchConfig.researchMode },
                webSearchStatusMessage = "本轮研究模式：${selected.label}",
                statusMessage = "本轮研究模式：${selected.label}"
            )
        }
    }

    fun clearWebSearchDiagnostics() {
        val records = webSearchDiagnosticStore.clear()
        uiStateStore.update {
            it.copy(
                webSearchDiagnostics = records,
                webSearchStatusMessage = null,
                statusMessage = "已清空联网检索记录"
            )
        }
    }

    private fun appendWebSearchDiagnostic(record: WebSearchDiagnosticRecord): List<WebSearchDiagnosticRecord> =
        webSearchDiagnosticStore.add(record)

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

    private fun finishSelectingNewAssistant(assistant: AssistantRecord) {
        invalidatePendingChatImagePreparation()
        contentImportSelectionRevision.incrementAndGet()
        assistantStore.saveSelectedAssistantId(assistant.id)
        val current = _uiState.value
        val assistantParams = assistant.toGenerationParams(current.params)
        val updatedParams = current.modelGenerationProfileKey()
            ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, assistantParams) }
            ?: assistantParams
        GenerationPreferences.persistGenerationParams(getApplication(), updatedParams)
        uiStateStore.update {
            it.copy(
                selectedAssistantId = assistant.id,
                params = updatedParams,
                statusMessage = "已切换助手：${assistant.name}"
            )
        }
        markLocalConversationContextInvalid()
        applyAssistantDefaultModel(assistant)
    }

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

    private fun cancelPendingWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) {
        if (ownerIds.isEmpty()) return
        synchronized(chatSessionPersistenceStateLock) {
            pendingWorldBookCleanupOwners[scope]?.let { pending ->
                pending.removeAll(ownerIds)
                if (pending.isEmpty()) pendingWorldBookCleanupOwners.remove(scope)
            }
        }
    }

    /**
     * World books are secondary data.  They are removed only after the Room
     * owner snapshot has committed; failed/skipped writes keep this queue for
     * the next authoritative snapshot.
     */
    private fun cleanupPendingWorldBooksAfterOwnerCommit(
        scope: WorldBookScope,
        retainedOwnerIds: Set<String>
    ) {
        val ownersToRemove = synchronized(chatSessionPersistenceStateLock) {
            pendingWorldBookCleanupOwners[scope]
                ?.filterNotTo(linkedSetOf()) { it in retainedOwnerIds }
                .orEmpty()
        }
        if (ownersToRemove.isEmpty()) return
        runCatching {
            worldBookStore.removeScopedOwners(scope, ownersToRemove)
        }.onSuccess { updatedWorldBooks ->
            synchronized(chatSessionPersistenceStateLock) {
                pendingWorldBookCleanupOwners[scope]?.let { pending ->
                    pending.removeAll(ownersToRemove)
                    if (pending.isEmpty()) pendingWorldBookCleanupOwners.remove(scope)
                }
            }
            uiStateStore.update { it.copy(worldBooks = updatedWorldBooks) }
        }.onFailure { error ->
            uiStateStore.update {
                it.copy(
                    statusMessage = "主体数据已保存，但关联世界书清理失败，原数据已保留：${error.message ?: "存储错误"}"
                )
            }
        }
    }

    fun deleteAssistant(assistantId: String) {
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        if (state.isGenerating || hasActiveAssistantImageExecution(state.activeChatSessionId)) {
            showStatus("请先结束当前角色请求，再删除助手。")
            return
        }
        if (assistantId == AssistantRecord.DEFAULT_ID || state.assistants.size <= 1) {
            showStatus("默认助手不能删除")
            return
        }
        val removed = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val remaining = state.assistants.filterNot { it.id == assistantId }
        val next = if (state.selectedAssistantId == assistantId) {
            remaining.firstOrNull { it.id == AssistantRecord.DEFAULT_ID } ?: remaining.first()
        } else {
            remaining.firstOrNull { it.id == state.selectedAssistantId } ?: remaining.first()
        }
        try {
            assistantStore.saveAssistants(remaining)
        } catch (error: Throwable) {
            uiStateStore.update {
                it.copy(
                    statusMessage = "删除助手失败，助手和关联世界书均未修改：${error.message ?: "存储错误"}"
                )
            }
            return
        }
        val deletingSelectedAssistant = state.selectedAssistantId == assistantId
        if (deletingSelectedAssistant) assistantStore.saveSelectedAssistantId(next.id)
        val nextAssistantParams = next.toGenerationParams(state.params)
        val updatedParams = if (deletingSelectedAssistant) state.modelGenerationProfileKey()
            ?.let { key -> modelGenerationProfileStore.loadOrCreate(key, nextAssistantParams) }
            ?: nextAssistantParams else state.params
        if (deletingSelectedAssistant) GenerationPreferences.persistGenerationParams(getApplication(), updatedParams)
        val updatedSessions = state.chatSessions
        uiStateStore.update {
            it.copy(
                assistants = remaining,
                assistantMemories = it.assistantMemories.filterNot { memory ->
                    memory.assistantId == assistantId
                },
                selectedAssistantId = if (deletingSelectedAssistant) next.id else it.selectedAssistantId,
                params = updatedParams,
                chatSessions = updatedSessions,
                statusMessage = "已删除助手：${removed.name}"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatSessionStore.deleteAssistantMemoryData(assistantId) }
                .onFailure { error ->
                    uiStateStore.update {
                        it.copy(statusMessage = "助手已删除，但关联记忆清理失败：${error.message ?: "存储错误"}")
                    }
                }
        }
        queueWorldBookCleanup(WorldBookScope.ASSISTANT, setOf(assistantId))
        // AssistantStore's canonical Room transaction has already committed.
        // The file-backed world-book cleanup is therefore safe to run now.
        cleanupPendingWorldBooksAfterOwnerCommit(
            scope = WorldBookScope.ASSISTANT,
            retainedOwnerIds = remaining.mapTo(hashSetOf()) { it.id }
        )
        if (deletingSelectedAssistant) {
            selectAssistant(next.id)
            persistChatSessions()
        } else {
            persistChatSessions(updatedSessions)
        }
        viewModelScope.launch { chatAppearanceMutex.withLock { cleanupUnusedChatBackgrounds() } }
    }

    fun importAssistantCard(rawJson: String) {
        if (_uiState.value.isGenerating) {
            showStatus("请先停止当前生成，再导入角色卡")
            return
        }
        val owner = captureContentImportOwner()
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                CharacterCardCodec.parseJson(rawJson)
            }
            finishCharacterCardImport(result, owner)
        }
    }

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
            val models = localImageModelStore.loadModels()
            val verification = currentQnnImageVerificationByModelId(getApplication(), models)
            val selectedId = selectedReadyLocalImageModelId(
                models = models,
                qnnVerificationCurrentByModelId = verification,
                preferredId = localImageModelStore.loadSelectedModelId()
            )
            managedRuntimeReadinessRefreshGate.invalidate()
            uiStateStore.update { state -> state.copy(
                localImageModels = models,
                qnnImageVerificationCurrentByModelId = verification,
                selectedLocalImageModelId = selectedId,
                selectedImageBackend = if (selectedId == null && state.selectedImageBackend == ImageBackend.LOCAL)
                    ImageBackend.CLOUD else state.selectedImageBackend,
                statusMessage = if (success) "已移除目录记录：${removed?.displayName ?: "模型"}；文件仍保留。"
                    else "未找到本地图像模型记录。"
            ) }
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

    fun importAssistantCardFile(uriString: String, importOwner: ContentImportOwner? = null) {
        if (_uiState.value.isGenerating) {
            showStatus("请先停止当前生成，再导入角色卡")
            return
        }
        val owner = importOwner ?: captureContentImportOwner()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = Uri.parse(uriString)
                    val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: error("无法打开所选文件")
                    stream.use(CharacterCardCodec::parse)
                }.getOrElse { error ->
                    CharacterCardParseResult.Failure(
                        CharacterCardParseError(
                            code = CharacterCardParseErrorCode.IO_ERROR,
                            message = error.message ?: "无法读取所选文件",
                            cause = error
                        )
                    )
                }
            }
            finishCharacterCardImport(result, owner)
        }
    }

    private suspend fun finishCharacterCardImport(
        result: CharacterCardParseResult,
        owner: ContentImportOwner,
        commitId: String? = null
    ): Boolean {
        val success = result as? CharacterCardParseResult.Success ?: run {
            val failure = result as CharacterCardParseResult.Failure
            val nextStep = when (failure.error.code) {
                CharacterCardParseErrorCode.MISSING_CARD_METADATA ->
                    "请确认选择的是带 chara/ccv3 角色卡元数据的 PNG，而不是普通图片。"
                CharacterCardParseErrorCode.INVALID_PNG,
                CharacterCardParseErrorCode.PNG_TOO_LARGE ->
                    "请重新导出 PNG 角色卡，或改用同一角色卡的 JSON 文件。"
                CharacterCardParseErrorCode.INVALID_CARD_METADATA,
                CharacterCardParseErrorCode.CARD_METADATA_TOO_LARGE ->
                    "请使用标准 Character Card v2/v3 导出文件；如果来自其他应用，请先重新导出。"
                CharacterCardParseErrorCode.INVALID_JSON,
                CharacterCardParseErrorCode.UNSUPPORTED_CARD,
                CharacterCardParseErrorCode.MISSING_CARD_DATA ->
                    "请导入标准角色卡 JSON（chara_card_v2/chara_card_v3）后重试。"
                CharacterCardParseErrorCode.IO_ERROR ->
                    "请重新选择文件并允许文件读取权限。"
                else -> "请换用标准 PNG/JSON 角色卡后重试。"
            }
            uiStateStore.update {
                it.copy(statusMessage = "角色卡导入失败：${failure.error.message} 下一步：$nextStep")
            }
            return false
        }
        if (commitId == null) {
            stageContentImportPreview(characterCardPreview(success, owner))
            return true
        }
        val state = _uiState.value
        val imported = success.card.toAssistantRecord()
        val now = System.currentTimeMillis()
        val assistant = imported.copy(
            id = commitId,
            name = imported.name.ifBlank { "导入助手" }.take(36),
            systemPrompt = imported.systemPrompt.ifBlank { characterCardFallbackSystemPrompt(imported.name) },
            createdAt = now,
            updatedAt = now
        )
        val embeddedWorldBookImport = parseEmbeddedCharacterBook(success.card, assistant.id).let { parsed ->
            parsed.copy(book = parsed.book?.copy(id = "$commitId:world-book"))
        }
        val updatedAssistants = state.assistants.filterNot { it.id == assistant.id } + assistant
        try {
            withContext(Dispatchers.IO) { assistantStore.saveAssistants(updatedAssistants) }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            uiStateStore.update {
                it.copy(statusMessage = "角色卡导入失败：${error.message ?: "无法保存角色"}")
            }
            return false
        }
        var updatedWorldBooks = state.worldBooks
        var embeddedWorldBookSaveError: String? = null
        embeddedWorldBookImport.book?.let { book ->
            try {
                updatedWorldBooks = withContext(Dispatchers.IO) { worldBookStore.upsert(book) }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                embeddedWorldBookSaveError = error.message?.trim()?.takeIf(String::isNotBlank)
                    ?: "无法保存到本机"
            }
        }
        val currentState = _uiState.value
        val currentOwnerIsCurrent = owner.isCurrent(currentState)
        val selectImportedAssistant = currentOwnerIsCurrent &&
            !currentState.isGenerating && conversationMutationBarrier?.isActive != true &&
            !hasActiveAssistantImageExecution(currentState.activeChatSessionId)
        val updatedSessions = currentState.chatSessions
        uiStateStore.update {
            it.copy(
                assistants = it.assistants.filterNot { record -> record.id == assistant.id } + assistant,
                chatSessions = updatedSessions,
                worldBooks = updatedWorldBooks,
                statusMessage = buildString {
                    append("已导入")
                    append(if (success.source == CharacterCardSource.JSON) " JSON " else " PNG ")
                    append("角色卡：")
                    append(assistant.name)
                    if (!selectImportedAssistant) {
                        append("；当前对话未切换角色")
                    }
                    append(embeddedCharacterBookStatusSuffix(embeddedWorldBookImport, embeddedWorldBookSaveError))
                }
            )
        }
        if (selectImportedAssistant) {
            val importStatus = _uiState.value.statusMessage
            try {
                selectAssistant(assistant.id)
                if (_uiState.value.selectedAssistantId == assistant.id &&
                    _uiState.value.activeChatSessionId != currentState.activeChatSessionId) {
                    showStatus(importStatus)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uiStateStore.update {
                    it.copy(statusMessage = "$importStatus；角色卡已保存，自动切换会话失败：${error.message ?: "请手动选择角色卡"}")
                }
            }
        }
        val createdSessionId = _uiState.value.activeChatSessionId?.takeIf { activeId ->
            currentState.chatSessions.none { it.id == activeId }
        }
        if (createdSessionId != null) {
            try {
                chatSessionPersistenceSequence.incrementAndGet()
                withContext(Dispatchers.IO) {
                    chatSessionPersistenceMutex.withLock {
                        val snapshot = _uiState.value.chatSessions
                        val liveIds = snapshot.mapTo(hashSetOf()) { it.id }
                        val bindings = synchronized(chatSessionPersistenceStateLock) {
                            pendingKnowledgeBindings.filterKeys { it in liveIds }.mapValues { (_, ids) -> ids.toSet() }
                        }
                        chatSessionStore.save(snapshot, bindings)
                        durableChatSessions = snapshot
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                uiStateStore.update {
                    it.copy(statusMessage = "${it.statusMessage.orEmpty()}；角色卡已保存，但新会话保存失败：${error.message ?: "存储错误"}")
                }
            }
        }
        // Import success depends on the role and its book, not on optional chat navigation.
        return embeddedWorldBookSaveError == null
    }

    fun importWorldBook(rawJson: String, scope: WorldBookScope = WorldBookScope.ASSISTANT) {
        val owner = captureContentImportOwner()
        viewModelScope.launch { importWorldBookOwned(rawJson, scope, owner) }
    }

    fun updateChatImageLoraSelection(selections: List<Pair<String, Double>>?) {
        val frozen = selections?.toList()
        val valid = frozen?.let { runCatching { resolveCurrentLocalImageLoras(it) }.isSuccess } == true
        uiStateStore.update { it.copy(chatImageLoraSelections = frozen.takeIf { valid }) }
    }

    /**
     * Imports a world book, delegating the decision to [KnowledgeLibraryOperations.importWorldBook].
     *
     * What stays here is what only this class can do: read the live selection revision, stage a
     * preview through [stageContentImportPreview], publish the resulting change atomically, and
     * invalidate the local conversation context through [markLocalConversationContextInvalid].
     * Parsing, the owner-existence check, the store write and the message are decided in the use case.
     */
    private suspend fun importWorldBookOwned(
        rawJson: String,
        scope: WorldBookScope,
        owner: ContentImportOwner,
        commitId: String? = null
    ): Boolean {
        val state = _uiState.value
        val request = WorldBookImportRequest(
            rawJson = rawJson,
            scope = scope,
            owner = owner,
            commitId = commitId,
            hasActiveSession = owner.sessionId != null,
            ownerStillExists = when (scope) {
                WorldBookScope.GLOBAL -> true
                WorldBookScope.ASSISTANT -> state.assistants.any { it.id == owner.assistantId }
                WorldBookScope.CHAT -> state.chatSessions.any { it.id == owner.sessionId }
            },
            // Read after the store write, exactly as the original did.
            ownerIsCurrent = { owner.isCurrent(_uiState.value) }
        )
        return when (val outcome = knowledgeLibraryPort.importWorldBook(request)) {
            is WorldBookImportOutcome.NeedsPreview -> {
                stageContentImportPreview(outcome.preview)
                true
            }

            is WorldBookImportOutcome.Committed -> {
                uiStateStore.update { it.withKnowledgeLibraryChange(outcome.change) }
                if (outcome.invalidateContext) markLocalConversationContextInvalid()
                true
            }

            is WorldBookImportOutcome.Rejected -> {
                uiStateStore.update {
                    it.withKnowledgeLibraryChange(KnowledgeLibraryChange(statusMessage = outcome.statusMessage))
                }
                false
            }
        }
    }

    fun importWorldBookFile(
        uriString: String,
        scope: WorldBookScope = WorldBookScope.ASSISTANT,
        importOwner: ContentImportOwner? = null
    ) {
        if (_uiState.value.isGenerating) {
            showStatus("请先停止当前生成，再导入世界书")
            return
        }
        val owner = importOwner ?: captureContentImportOwner()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val (text, truncated) = readAttachmentText(Uri.parse(uriString), maxBytes = 1_048_576)
                require(!truncated) { "世界书文件超过 1 MiB。" }
                text
            } }
            result.onSuccess { text -> importWorldBookOwned(text, scope, owner) }
                .onFailure { error ->
                    showStatus("世界书导入失败：${error.message ?: "无法读取文件"}")
                }
        }
    }

    fun deleteWorldBook(worldBookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val change = KnowledgeLibraryOperations.deleteWorldBook(
                knowledgeLibraryPort,
                _uiState.value.knowledgeLibrarySnapshot(),
                worldBookId
            ) ?: return@launch
            uiStateStore.update { it.withKnowledgeLibraryChange(change) }
        }
    }

    fun createKnowledgeBase(name: String, description: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val change = KnowledgeLibraryOperations.createKnowledgeBase(
                knowledgeLibraryPort,
                _uiState.value.knowledgeLibrarySnapshot(),
                name,
                description
            )
            uiStateStore.update { it.withKnowledgeLibraryChange(change) }
        }
    }

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

    fun setKnowledgeBaseSelected(knowledgeBaseId: String, selected: Boolean) {
        val state = _uiState.value
        if (knowledgeBaseId !in state.knowledgeBases.mapTo(mutableSetOf()) { it.id }) return
        val updatedIds = state.selectedKnowledgeBaseIds.toMutableSet().apply {
            if (selected) add(knowledgeBaseId) else remove(knowledgeBaseId)
        }.toSet()
        uiStateStore.update { it.copy(selectedKnowledgeBaseIds = updatedIds) }
        state.activeChatSessionId?.let { sessionId ->
            persistKnowledgeBaseBindings(sessionId, updatedIds)
        }
    }

    fun deleteKnowledgeBase(knowledgeBaseId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val change = KnowledgeLibraryOperations.deleteKnowledgeBase(
                knowledgeLibraryPort,
                _uiState.value.knowledgeLibrarySnapshot(),
                knowledgeBaseId
            ) ?: return@launch
            uiStateStore.update { it.withKnowledgeLibraryChange(change) }
        }
    }

    fun updateReasoningMode(mode: ReasoningMode) {
        val state = _uiState.value
        if (state.selectedChatBackend == ChatBackend.CLOUD) {
            showStatus(CLOUD_REASONING_LOCKED_MESSAGE)
            return
        }
        val updatedParams = state.params.copy(
            reasoningMode = mode,
            hideReasoning = mode == ReasoningMode.OFF
        ).let { params ->
            params.copy(nPredict = params.effectiveNPredict())
        }
        GenerationPreferences.persistGenerationParams(getApplication(), updatedParams)
        persistModelGenerationParams(state, updatedParams)
        uiStateStore.update {
            it.copy(
                params = updatedParams,
                statusMessage = "思考模式已切换为：${mode.label}"
            )
        }
    }

    fun showCloudReasoningModeLocked() {
        showStatus(CLOUD_REASONING_LOCKED_MESSAGE)
    }

    fun updateCloudApiEnabled(enabled: Boolean) {
        uiStateStore.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(enabled = enabled))
        }
    }

    fun updateCloudApiFormat(value: String) {
        val format = CloudApiFormat.entries
            .firstOrNull { it.name == value || it.label == value }
            ?: CloudApiFormat.OPENAI_COMPATIBLE
        val defaultBaseUrls = CloudApiFormat.entries.map { it.defaultBaseUrl }.filter { it.isNotBlank() }
        val defaultChatModels = CloudApiFormat.entries.map { it.defaultModel }.filter { it.isNotBlank() }
        uiStateStore.update { state ->
            val current = state.cloudApiConfig
            state.copy(
                cloudApiConfig = current.copy(
                    apiFormat = format,
                    providerName = format.label,
                    displayName = current.displayName.takeUnless {
                        it == current.apiFormat.label || it == current.chatModel || it == "自定义推理引擎"
                    }.orEmpty(),
                    baseUrl = current.baseUrl.takeUnless { it in defaultBaseUrls }.orEmpty(),
                    chatModel = current.chatModel.takeUnless { it in defaultChatModels }.orEmpty()
                )
            )
        }
    }

    fun updateCloudImageApiFormat(value: String) {
        val format = CloudImageApiFormat.from(value)
        val defaultBaseUrls = CloudImageApiFormat.entries.map { it.defaultBaseUrl }.filter { it.isNotBlank() }
        val defaultImageModels = CloudImageApiFormat.entries.map { it.defaultImageModel }.filter { it.isNotBlank() }
        val defaultImagePaths = CloudImageApiFormat.entries.map { it.defaultEndpointPath }.filter { it.isNotBlank() }
        uiStateStore.update { state ->
            val current = state.cloudApiConfig
            state.copy(
                cloudApiConfig = current.copy(
                    imageApiFormat = format,
                    providerName = format.label,
                    baseUrl = current.baseUrl.takeUnless { it in defaultBaseUrls }.orEmpty(),
                    imageModel = current.imageModel.takeUnless { it in defaultImageModels }.orEmpty(),
                    imageEndpointPath = current.imageEndpointPath.takeUnless { it.trim().trim('/') in defaultImagePaths }.orEmpty(),
                    imageSize = current.imageSize.takeUnless { it == "1024x1024" }.orEmpty()
                )
            )
        }
    }

    fun applyCloudProviderPreset(value: String) {
        val preset = findCloudProviderPreset(value) ?: return
        uiStateStore.update { state ->
            state.copy(
                cloudApiConfig = state.cloudApiConfig.copy(
                    enabled = true,
                    apiFormat = preset.format,
                    providerName = preset.providerName,
                    displayName = preset.displayName,
                    baseUrl = preset.baseUrl,
                    chatModel = preset.chatModel
                ),
                statusMessage = "已选择 ${preset.providerName}，请填写自定义 Base URL、模型名和 API Key。"
            )
        }
    }

    fun beginAddCloudModel(kind: String) {
        val modelKind = runCatching { CloudModelKind.valueOf(kind) }.getOrDefault(CloudModelKind.CHAT)
        val current = _uiState.value.cloudApiConfig
        uiStateStore.update { state ->
            state.copy(
                editingCloudModelId = null,
                cloudApiConfig = CloudApiConfig(
                    enabled = true,
                    apiFormat = current.apiFormat,
                    imageApiFormat = current.imageApiFormat,
                    displayName = "",
                    providerName = if (modelKind == CloudModelKind.IMAGE) current.imageApiFormat.label else current.apiFormat.label,
                    baseUrl = "",
                    apiKey = "",
                    chatModel = "",
                    imageModel = "",
                    imageEndpointPath = "",
                    imageSize = ""
                )
            )
        }
    }

    fun editCloudModel(modelId: String) {
        val model = _uiState.value.cloudModels.firstOrNull { it.id == modelId } ?: return
        uiStateStore.update { state ->
            state.copy(
                editingCloudModelId = model.id,
                cloudApiConfig = when (model.kind) {
                    CloudModelKind.CHAT -> model.toChatConfig()
                    CloudModelKind.IMAGE -> model.toImageConfig()
                },
                statusMessage = "正在编辑：${model.displayName}"
            )
        }
    }

    fun updateCloudBaseUrl(value: String) {
        uiStateStore.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(baseUrl = value))
        }
    }

    fun updateCloudApiKey(value: String) {
        uiStateStore.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(apiKey = value))
        }
    }

    fun updateCloudChatModel(value: String) {
        uiStateStore.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(chatModel = value))
        }
    }

    fun updateCloudSupportsVision(value: Boolean) {
        uiStateStore.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(supportsVision = value))
        }
    }

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
    /**
     * The ViewModel side of [CloudModelConfigPort].
     */
    private val cloudModelConfigPort: CloudModelConfigPort =
        object : CloudModelConfigPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val cloudApiStore: CloudApiStore
                get() = this@MainViewModel.cloudApiStore

            override val cloudChatProvider: OpenAiCompatibleChatProvider
                get() = this@MainViewModel.cloudChatProvider

            override val localImageModelStore: LocalImageModelStore
                get() = this@MainViewModel.localImageModelStore

            override fun loadModelGenerationParams(
                backend: ChatBackend,
                modelId: String?,
                defaults: GenerationParams
            ): GenerationParams =
                this@MainViewModel.loadModelGenerationParams(backend, modelId, defaults)

            override fun persistChatSessions(
                sessions: List<ChatSessionRecord>?,
                knowledgeBinding: Pair<String, Set<String>>?
            ) {
                this@MainViewModel.persistChatSessions(
                    sessions = sessions,
                    knowledgeBinding = knowledgeBinding
                )
            }

            override fun persistGenerationParams(params: GenerationParams) {
                GenerationPreferences.persistGenerationParams(getApplication(), params)
            }

            override fun showStatus(message: String?) {
                this@MainViewModel.showStatus(message)
            }

            override fun busy(message: String) {
                this@MainViewModel.busy(message)
            }

            override fun fail(message: String) {
                this@MainViewModel.fail(message)
            }
        }


/** Delegates to [cloudChatModelGenerationParams]. Signature kept verbatim. */
    fun generationParamsForChatModel(modelId: String): GenerationParams = cloudChatModelGenerationParams(port = cloudModelConfigPort, modelId = modelId)

/** Delegates to [applyCloudSupportsTools]. Signature kept verbatim. */
    fun updateCloudSupportsTools(value: Boolean) {
        applyCloudSupportsTools(port = cloudModelConfigPort, value = value)
    }

/** Delegates to [applyCloudImageModel]. Signature kept verbatim. */
    fun updateCloudImageModel(value: String) {
        applyCloudImageModel(port = cloudModelConfigPort, value = value)
    }

/** Delegates to [applyCloudResponsesReasoning]. Signature kept verbatim. */
    fun updateCloudResponsesReasoning(value: Boolean) {
        applyCloudResponsesReasoning(port = cloudModelConfigPort, value = value)
    }

/** Delegates to [applyCloudImageSize]. Signature kept verbatim. */
    fun updateCloudImageSize(value: String) {
        applyCloudImageSize(port = cloudModelConfigPort, value = value)
    }

/** Delegates to [applyCloudImageEndpointPath]. Signature kept verbatim. */
    fun updateCloudImageEndpointPath(value: String) {
        applyCloudImageEndpointPath(port = cloudModelConfigPort, value = value)
    }

/** Delegates to [applyCloudDisplayName]. Signature kept verbatim. */
    fun updateCloudDisplayName(value: String) {
        applyCloudDisplayName(port = cloudModelConfigPort, value = value)
    }

/** Delegates to [persistCloudApiConfig]. Signature kept verbatim. */
    fun saveCloudApiConfig() {
        persistCloudApiConfig(port = cloudModelConfigPort)
    }

/** Delegates to [saveCloudChatModelConfig]. Signature kept verbatim. */
    fun saveCloudChatModel() {
        saveCloudChatModelConfig(port = cloudModelConfigPort)
    }

/** Delegates to [saveCloudImageModelConfig]. Signature kept verbatim. */
    fun saveCloudImageModel() {
        saveCloudImageModelConfig(port = cloudModelConfigPort)
    }

/** Delegates to [runCloudApiConfigTest]. Signature kept verbatim. */
    fun testCloudApiConfig() {
        runCloudApiConfigTest(port = cloudModelConfigPort, scope = viewModelScope)
    }











    fun selectCloudImageModel(modelId: String) {
        if (rejectImageModelSwitchWhileGenerationIsActive()) return
        invalidatePendingChatImagePreparation()
        val model = _uiState.value.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.IMAGE }
        if (model == null) {
            showStatus("未找到图像生成模型")
            return
        }
        cloudApiStore.saveSelectedCloudImageModelId(model.id)
        localImageModelStore.saveSelectedBackend(ImageBackend.CLOUD)
        uiStateStore.update {
            it.copy(
                selectedCloudImageModelId = model.id,
                selectedImageBackend = ImageBackend.CLOUD,
                cloudApiConfig = model.toImageConfig(),
                statusMessage = "图片页已切换到：${model.modelName}"
            )
        }
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

    fun refreshLocalModels() {
        viewModelScope.launch(Dispatchers.IO) {
            reconcileModelIdentities()
            refreshRuntimeReadiness(managedRuntimeReadinessPort)
        }
    }

    private suspend fun reconcileModelIdentities() = chatSessionPersistenceMutex.withLock {
        val result = modelIdentityReferenceMigration.reconcileAndMigrate()
        if (result.idMapping.isNotEmpty()) {
            fun canonical(id: String?): String? = result.idMapping[id] ?: id
            uiStateStore.update { state -> state.copy(
                models = result.models,
                loadedModelId = canonical(state.loadedModelId),
                chatSessions = state.chatSessions.map { it.copy(modelId = canonical(it.modelId)) },
                assistants = state.assistants.map { it.copy(defaultModelId = canonical(it.defaultModelId)) }
            ) }
            durableChatSessions = durableChatSessions.map { it.copy(modelId = canonical(it.modelId)) }
        }
    }

    fun importModel(uri: Uri) {
        importModel(listOf(uri))
    }

    fun importModel(uris: List<Uri>, textOnly: Boolean = false, persistentAccess: Boolean = false) {
        if (uris.isEmpty()) return
        enqueueManagedImport(ManagedModelImportRequest(uris = uris.distinct().map(Uri::toString),
            textOnly = textOnly, persistentAccess = persistentAccess))
    }

    fun importModelDirectory(uri: Uri, textOnly: Boolean = false, persistentAccess: Boolean = false) {
        enqueueManagedImport(ManagedModelImportRequest(uris = listOf(uri.toString()), directory = true,
            textOnly = textOnly, persistentAccess = persistentAccess))
    }

    private fun enqueueManagedImport(request: ManagedModelImportRequest) {
        launchImportOperation {
            ManagedModelImportWorker.enqueue(getApplication(), request)
            uiStateStore.update { it.copy(statusMessage = if (request.persistentAccess)
                "导入任务已保存，可在本地模型页查看进度或暂停；已完成组件可恢复。"
                else "导入已开始。此文件提供方不支持保留访问权限，重启后可能需要重新选择源文件。") }
        }
    }

    fun pauseManagedImport(requestId: String) {
        launchImportOperation {
            androidx.work.WorkManager.getInstance(getApplication<Application>())
                .cancelUniqueWork("${ManagedModelImportWorker.TAG}-$requestId")
        }
    }

    fun resumeManagedImport(requestId: String) {
        launchImportOperation {
            val app = getApplication<Application>()
            ManagedModelImportWorker.enqueue(app, ManagedModelImportRequest.read(app.filesDir, requestId))
        }
    }

    private fun launchImportOperation(operation: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { operation() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                showStatus("导入未完成：${error.message}。请在本地模型页重试或重新选择源文件。")
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            chatImageToolAuthorizationStore.prune(initialChatSessions.map { it.id })
        }
    }

    private fun observeManagedImports() {
        launchImportOperation {
            val app = getApplication<Application>()
            val receipts = app.getSharedPreferences("mca_import_receipts", Context.MODE_PRIVATE)
            val handled = receipts.getStringSet("handled", emptySet()).orEmpty().toMutableSet()
            androidx.work.WorkManager.getInstance(app).getWorkInfosByTagFlow(ManagedModelImportWorker.TAG).collect { infos ->
                val tasks = infos.sortedByDescending { info ->
                    info.tags.firstOrNull { it.startsWith("created:") }?.substringAfter(':')?.toLongOrNull() ?: 0L
                }.mapNotNull { info ->
                    info.tags.firstOrNull { it.startsWith(ManagedModelImportWorker.REQUEST_TAG_PREFIX) }
                        ?.removePrefix(ManagedModelImportWorker.REQUEST_TAG_PREFIX)?.let { it to info }
                }.distinctBy { it.first }
                val rows = tasks.map { (requestId, info) ->
                    val active = !info.state.isFinished
                    val message = when (info.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> info.outputData.getString("message") ?: "模型导入完成"
                        androidx.work.WorkInfo.State.FAILED -> info.outputData.getString("error") ?: "导入未完成，请检查源文件和存储空间后重试。"
                        androidx.work.WorkInfo.State.CANCELLED -> "导入已暂停，可恢复已完成组件"
                        else -> info.progress.getString("message") ?: "正在等待导入"
                    }
                    com.muyuchat.feature.modelhub.ModelImportTaskUi(
                        id = requestId, message = message, file = info.progress.getString("file"),
                        bytes = info.progress.getLong("bytes", 0), totalBytes = info.progress.getLong("total", 0),
                        active = active, resumable = info.state == androidx.work.WorkInfo.State.FAILED || info.state == androidx.work.WorkInfo.State.CANCELLED
                    )
                }
                // Task state is independent of chat/load/download busy ownership.
                uiStateStore.update { it.copy(importTasks = rows.filter { row -> row.active || row.resumable } +
                    rows.filter { row -> !row.active && !row.resumable }.take(3)) }
                val completed = tasks.map { it.second }.filter {
                    it.state == androidx.work.WorkInfo.State.SUCCEEDED && it.id.toString() !in handled
                }
                if (completed.isNotEmpty()) {
                    managedRuntimeReadinessRefreshGate.invalidate()
                    reconcileModelIdentities()
                    val models = modelStore.listModels()
                    uiStateStore.update { it.copy(models = models) }
                    handled.addAll(completed.map { it.id.toString() })
                    receipts.edit().putStringSet("handled", handled.toSet()).apply()
                }
            }
        }
    }

    fun fetchRemoteFiles() {
        val input = _uiState.value.repoInput
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在查询 ModelScope 本地推理文件...")
            runCatching {
                modelScopeClient.listEngineFiles(input)
            }.onSuccess { files ->
                uiStateStore.update {
                    it.copy(remoteFiles = files, busy = false, statusMessage = "找到 ${files.size} 个本地推理文件")
                }
            }.onFailure { error ->
                fail("查询失败：${error.message}")
            }
        }
    }

    fun fetchRecommendedFiles(model: ModelScopeRecommendedModel) {
        recommendedDownloadBlockReason(model)?.let { reason ->
            showStatus(reason)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在读取推荐模型：${model.title}...")
            runCatching {
                modelScopeClient.listRecommendedFiles(
                    model = model,
                    preferredQairtChipsets = preferredQairtChipsets(currentDeviceProfile())
                )
            }.onSuccess { files ->
                uiStateStore.update {
                    it.copy(
                        repoInput = model.repoId,
                        remoteFiles = files,
                        busy = false,
                        statusMessage = "已列出 ${model.title} 的 ${files.size} 个模型组件"
                    )
                }
            }.onFailure { error ->
                fail("推荐模型读取失败：${error.message}")
            }
        }
    }

    fun downloadRecommended(model: ModelScopeRecommendedModel) {
        downloadRecommended(model, useAfterDownload = false)
    }

    fun downloadRecommended(model: ModelScopeRecommendedModel, useAfterDownload: Boolean) {
        val blocked = recommendedDownloadBlockReason(model)
        if (blocked != null) { fail(blocked); return }
        enqueueManagedDownload(ManagedDownloadRequest.recommended(model), useAfterDownload)
    }

    private fun recommendedDownloadBlockReason(model: ModelScopeRecommendedModel): String? {
        return if (model.downloadable) null else model.downloadBlockReason ?: "该模型暂不提供下载。"
    }

    fun attachVisionProjector(modelId: String, uri: Uri) {
        launchModelOperation("模型操作未完成") {
            busy("正在绑定本地视觉投影器...")
            val shouldReload = _uiState.value.loadedModelId == modelId &&
                _uiState.value.selectedChatBackend == ChatBackend.LOCAL
            runCatching {
                modelStore.attachVisionProjector(modelId, uri)
            }.onSuccess { model ->
                managedRuntimeReadinessRefreshGate.invalidate()
                uiStateStore.update {
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                        statusMessage = if (shouldReload) {
                            "已绑定视觉投影器：${model.visionProjectorFileName ?: "mmproj"}，正在重新加载模型以启用本地识图。"
                        } else {
                            "已绑定视觉投影器：${model.visionProjectorFileName ?: "mmproj"}，下次加载该模型后可本地识图。"
                        }
                    )
                }
                if (shouldReload) {
                    loadModel(model)
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                fail("视觉文件绑定失败：${error.message}")
            }
        }
    }









    fun searchHubModels(reset: Boolean = true) {
        val state = _uiState.value
        val query = state.hubQuery.ifBlank { "MNN Qwen3.5" }
        val nextPage = if (reset) 1 else state.hubPage + 1
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在从魔塔 API 拉取模型列表...")
            runCatching {
                modelScopeClient.searchModels(query = query, pageNumber = nextPage)
            }.onSuccess { result ->
                uiStateStore.update {
                    it.copy(
                        hubModels = if (reset) result.models else it.hubModels + result.models,
                        hubPage = result.pageNumber,
                        hubTotalCount = result.totalCount,
                        busy = false,
                        statusMessage = "魔塔模型：第 ${result.pageNumber} 页，已显示 ${if (reset) result.models.size else it.hubModels.size + result.models.size}/${result.totalCount}"
                    )
                }
            }.onFailure { error ->
                fail("魔塔模型搜索失败：${error.message}")
            }
        }
    }

    fun fetchHubModelFiles(model: ModelScopeHubModel) {
        uiStateStore.update { it.copy(repoInput = model.id) }
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在读取 ${model.displayName} 的本地推理文件...")
            runCatching {
                modelScopeClient.listEngineFiles(model.id)
            }.onSuccess { files ->
                uiStateStore.update {
                    it.copy(
                        repoInput = model.id,
                        remoteFiles = files,
                        busy = false,
                        statusMessage = "已列出 ${model.displayName} 的 ${files.size} 个本地推理文件"
                    )
                }
            }.onFailure { error ->
                fail("读取模型文件失败：${error.message}")
            }
        }
    }

    fun openModelScopePage(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        }.onFailure { error ->
            fail("无法打开浏览器：${error.message}")
        }
    }

    fun download(remote: RemoteModelFile) {
        enqueueManagedDownload(ManagedDownloadRequest(remote = remote,
            projectorTargetId = if (remote.fileKind() == RemoteModelFileKind.PROJECTOR) _uiState.value.loadedModelId else null), false)
    }




    fun pauseManagedDownloads() {
        launchModelOperation("暂停下载失败") {
            androidx.work.WorkManager.getInstance(getApplication<Application>())
                .cancelAllWorkByTag(ManagedModelDownloadWorker.TAG).result.get()
        }
    }

    /** Resume paused/failed downloads from their durable request records. */
    fun resumeManagedDownloads() {
        launchModelOperation("继续下载失败") {
            val app = getApplication<Application>()
            val workManager = androidx.work.WorkManager.getInstance(app)
            val requestPreferences = app.getSharedPreferences(DOWNLOAD_REQUESTS_PREFERENCES, Context.MODE_PRIVATE)
            val requests = requestPreferences.all.mapNotNull { (key, raw) ->
                if (key.startsWith("use:")) return@mapNotNull null
                (raw as? String)?.let { value ->
                    runCatching { ManagedDownloadRequest.fromJson(value) }.getOrNull()
                }
            }
            require(requests.isNotEmpty()) { "没有可恢复的下载任务。" }
            var resumed = 0
            requests.forEach { request ->
                val infos = workManager
                    .getWorkInfosForUniqueWork("${ManagedModelDownloadWorker.TAG}-${request.identity}")
                    .get()
                val hasLiveWork = infos.any { info ->
                    info.state == androidx.work.WorkInfo.State.RUNNING ||
                        info.state == androidx.work.WorkInfo.State.ENQUEUED ||
                        info.state == androidx.work.WorkInfo.State.BLOCKED
                }
                if (!hasLiveWork) {
                    if (requestPreferences.getBoolean("use:${request.identity}", false)) {
                        autoUseManagedDownloads.add(request.identity)
                    }
                    ManagedModelDownloadWorker.enqueue(app, request)
                    resumed++
                }
            }
            require(resumed > 0) { "下载任务仍在运行，或没有可恢复任务。" }
            showStatus("已继续下载，保留的临时文件将尝试断点续传。")
        }
    }

    private fun enqueueManagedDownload(request: ManagedDownloadRequest, useAfterDownload: Boolean) {
        launchModelOperation("无法开始下载") {
            getApplication<Application>().getSharedPreferences(DOWNLOAD_REQUESTS_PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(request.identity, request.toJson())
                .putBoolean("use:${request.identity}", useAfterDownload)
                .apply()
            if (useAfterDownload) autoUseManagedDownloads.add(request.identity)
            uiStateStore.update { it.copy(downloadStatus = DownloadStatus.QUEUED,
                downloadTaskId = request.identity,
                downloadPhase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.QUEUED,
                downloadFailureSource = null,
                downloadedBytes = 0L, downloadTotalBytes = 0L,
                downloadSpeedBytesPerSecond = 0L, downloadRemainingSeconds = null,
                downloadFileName = request.remote?.name ?: request.recommendationId,
                downloadIntegrityStatus = "RUNNING",
                downloadIntegrityMessage = "等待文件下载完成后校验。",
                downloadExecutionStatus = "PENDING",
                downloadExecutionMessage = "等待安装阶段读取本机兼容性。",
                statusMessage = "下载任务已保存，网络可用时会继续；可暂停并保留进度。") }
            ManagedModelDownloadWorker.enqueue(getApplication(), request)
        }
    }

    private fun observeManagedDownloads() {
        viewModelScope.launch {
            BackgroundDownloadHealth.failure.collect { message ->
                if (message != null) failDownload(message)
            }
        }
        launchModelOperation("下载任务恢复失败，请在模型页重试") {
            val app = getApplication<Application>()
            val acknowledgements = app.getSharedPreferences("mca_download_receipts", Context.MODE_PRIVATE)
            val handled = acknowledgements.getStringSet("handled", emptySet()).orEmpty().toMutableSet()
            androidx.work.WorkManager.getInstance(app)
                .getWorkInfosByTagFlow(ManagedModelDownloadWorker.TAG).collect { infos ->
                    val active = infos.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                        ?: infos.firstOrNull { !it.state.isFinished }
                    if (active != null) {
                        val progress = active.progress
                        uiStateStore.update { state ->
                            if (!state.busy) managedDownloadOwnedBusy = true
                            state.copy(busy = true,
                                downloadTaskId = active.tags.firstOrNull { tag ->
                                    tag.matches(Regex("[0-9a-f]{64}"))
                                } ?: active.id.toString(),
                                downloadStatus = if (active.state == androidx.work.WorkInfo.State.RUNNING) DownloadStatus.RUNNING else DownloadStatus.QUEUED,
                                downloadPhase = progress.getString("phase")?.let { name ->
                                    runCatching { com.muyuchat.feature.modelhub.ModelHubDownloadPhase.valueOf(name) }.getOrNull()
                                } ?: if (active.state == androidx.work.WorkInfo.State.RUNNING) {
                                    com.muyuchat.feature.modelhub.ModelHubDownloadPhase.DOWNLOADING
                                } else {
                                    com.muyuchat.feature.modelhub.ModelHubDownloadPhase.QUEUED
                                },
                                downloadFailureSource = progress.getString("failureSource")?.let { name ->
                                    runCatching { com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.valueOf(name) }.getOrNull()
                                },
                                downloadFileName = progress.getString("file") ?: state.downloadFileName,
                                downloadedBytes = progress.getLong("bytes", 0),
                                downloadTotalBytes = progress.getLong("total", 0),
                                downloadSpeedBytesPerSecond = if (active.state == androidx.work.WorkInfo.State.RUNNING) progress.getLong("speed", 0) else 0L,
                                downloadRemainingSeconds = if (active.state == androidx.work.WorkInfo.State.RUNNING) {
                                    progress.getLong("remainingSeconds", -1L).takeIf { it >= 0L }
                                } else null,
                                statusMessage = progress.getString("message") ?: "下载任务已保留，正在等待网络或其他下载完成…",
                                downloadIntegrityStatus = progress.getString("integrityStatus") ?: state.downloadIntegrityStatus,
                                downloadIntegrityMessage = progress.getString("integrityMessage") ?: state.downloadIntegrityMessage,
                                downloadExecutionStatus = progress.getString("executionStatus") ?: state.downloadExecutionStatus,
                                downloadExecutionMessage = progress.getString("executionMessage") ?: state.downloadExecutionMessage)
                        }
                    }
                    infos.filter { it.state.isFinished && it.id.toString() !in handled }
                        .sortedBy { work -> work.tags.firstOrNull { it.startsWith("created:") }?.removePrefix("created:")?.toLongOrNull() ?: 0L }.forEach { info ->
                        recoverModelOperation(operation = {
                            val error = info.outputData.getString("error")
                            if (info.state == androidx.work.WorkInfo.State.SUCCEEDED && error == null) {
                                info.tags.firstOrNull { tag -> tag.matches(Regex("[0-9a-f]{64}")) }?.let { identity ->
                                    app.getSharedPreferences(DOWNLOAD_REQUESTS_PREFERENCES, Context.MODE_PRIVATE)
                                        .edit().remove(identity).remove("use:$identity").apply()
                                }
                                reconcileModelIdentities()
                                refreshRuntimeReadiness(managedRuntimeReadinessPort)
                                val imageId = info.outputData.getString("imageId")
                                val image = imageId?.let { id -> localImageModelStore.loadModels(discover = false).firstOrNull { it.matchesCatalogId(id) } }
                                if (image != null) {
                                    val selection = settleLocalImageSelection(image)
                                    uiStateStore.update { it.copy(localImageModels = selection.models,
                                        qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId,
                                        selectedLocalImageModelId = selection.selectedId, selectedImageBackend = selection.selectedBackend) }
                                }
                                if (active == null) {
                                    uiStateStore.update { it.copy(busy = if (managedDownloadOwnedBusy) false else it.busy,
                                        downloadStatus = DownloadStatus.DONE, downloadSpeedBytesPerSecond = 0,
                                        downloadTaskId = info.tags.firstOrNull { tag -> tag.matches(Regex("[0-9a-f]{64}")) }
                                            ?: info.id.toString(),
                                        downloadPhase = com.muyuchat.feature.modelhub.ModelHubDownloadPhase.COMPLETED,
                                        downloadFailureSource = null,
                                        downloadRemainingSeconds = null, statusMessage = info.outputData.getString("message"),
                                        downloadIntegrityStatus = info.outputData.getString("integrityStatus") ?: it.downloadIntegrityStatus,
                                        downloadIntegrityMessage = info.outputData.getString("integrityMessage") ?: it.downloadIntegrityMessage,
                                        downloadExecutionStatus = info.outputData.getString("executionStatus") ?: it.downloadExecutionStatus,
                                        downloadExecutionMessage = info.outputData.getString("executionMessage") ?: it.downloadExecutionMessage) }
                                    managedDownloadOwnedBusy = false
                                }
                                val model = info.outputData.getString("modelId")?.let(modelStore::getModel)
                                if (model != null) {
                                    val projectorDownloaded = info.outputData.getBoolean("projector", false)
                                    val reloadLoadedVisionModel = projectorDownloaded &&
                                        _uiState.value.loadedModelId == model.id &&
                                        _uiState.value.selectedChatBackend == ChatBackend.LOCAL
                                    if (reloadLoadedVisionModel) {
                                        // A projector downloaded through the model hub is
                                        // persisted after the worker finishes.  Reload the
                                        // active GGUF immediately so the native runner binds
                                        // the new mmproj instead of advertising a stale text
                                        // session until the next manual model load.
                                        uiStateStore.update {
                                            it.copy(statusMessage = "视觉投影器已下载，正在重新加载模型以启用识图…")
                                        }
                                        loadModel(model)
                                    } else {
                                        useDownloadedChatModel(model, info.tags.any { autoUseManagedDownloads.remove(it) })
                                    }
                                }
                            } else if (active == null) {
                                uiStateStore.update { it.copy(busy = if (managedDownloadOwnedBusy) false else it.busy,
                                    downloadStatus = if (info.state == androidx.work.WorkInfo.State.CANCELLED) DownloadStatus.PAUSED else DownloadStatus.FAILED,
                                    downloadTaskId = info.tags.firstOrNull { tag -> tag.matches(Regex("[0-9a-f]{64}")) }
                                        ?: info.id.toString(),
                                    downloadPhase = info.outputData.getString("phase")?.let { name ->
                                        runCatching { com.muyuchat.feature.modelhub.ModelHubDownloadPhase.valueOf(name) }.getOrNull()
                                    } ?: it.downloadPhase,
                                    downloadFailureSource = info.outputData.getString("failureSource")?.let { name ->
                                        runCatching { com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.valueOf(name) }.getOrNull()
                                    } ?: if (info.state == androidx.work.WorkInfo.State.CANCELLED) null
                                    else com.muyuchat.feature.modelhub.ModelHubDownloadFailureSource.DOWNLOAD,
                                    downloadSpeedBytesPerSecond = 0, downloadRemainingSeconds = null,
                                    statusMessage = error ?: "下载已暂停，进度已保留。再次点击下载可继续。",
                                    downloadIntegrityStatus = info.outputData.getString("integrityStatus") ?: it.downloadIntegrityStatus,
                                    downloadIntegrityMessage = info.outputData.getString("integrityMessage") ?: it.downloadIntegrityMessage,
                                    downloadExecutionStatus = info.outputData.getString("executionStatus") ?: it.downloadExecutionStatus,
                                    downloadExecutionMessage = info.outputData.getString("executionMessage") ?: it.downloadExecutionMessage) }
                                managedDownloadOwnedBusy = false
                            }
                        }, onFailure = { error ->
                            failDownload("模型文件已保留，但刷新列表失败：${error.message}。请释放存储空间后刷新本地模型。")
                        })
                        handled.add(info.id.toString())
                        acknowledgements.edit().putStringSet("handled", handled.toList().takeLast(128).toSet()).apply()
                    }
                }
        }
    }

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

    private fun useDownloadedChatModel(model: ModelManifest, requested: Boolean) {
        val state = _uiState.value
        if (requested && !state.busy && !state.isGenerating && state.loadedModelId == null &&
            state.selectedChatBackend == ChatBackend.LOCAL && state.tab == AppTab.MODELS) {
            loadModel(model)
        }
    }

    fun selectChatModel(choiceId: String) {
        if (choiceId.startsWith(CLOUD_MODEL_CHOICE_PREFIX)) {
            selectCloudChatModel(choiceId.removePrefix(CLOUD_MODEL_CHOICE_PREFIX))
            return
        }
        if (choiceId == _uiState.value.cloudApiConfig.chatChoiceId) {
            selectCloudChatModel(_uiState.value.selectedCloudChatModelId)
            return
        }
        _uiState.value.models.firstOrNull { it.id == choiceId }?.let(::loadModel)
    }

    private fun selectCloudChatModel(modelId: String?) {
        val model = _uiState.value.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.CHAT }
        val config = model?.toChatConfig()?.normalized()
        if (config == null || !config.configured) {
            uiStateStore.update {
                it.copy(statusMessage = "云端推理模型未配置。请到模型管理 > 云端 接入并保存对话推理模型。")
            }
            return
        }
        cloudApiStore.save(config)
        cloudApiStore.saveSelectedBackend(ChatBackend.CLOUD)
        cloudApiStore.saveSelectedCloudChatModelId(model.id)
        val modelParams = loadModelGenerationParams(
            backend = ChatBackend.CLOUD,
            modelId = model.id,
            defaults = _uiState.value.params
        )
        GenerationPreferences.persistGenerationParams(getApplication(), modelParams)
        viewModelScope.launch(Dispatchers.IO) {
            engine.stopGeneration()
        }
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        uiStateStore.update {
            sessionsToPersist = it.chatSessions.bindSession(
                sessionId = it.activeChatSessionId,
                assistantId = it.selectedAssistantId,
                modelMode = ChatBackend.CLOUD.bindingValue(),
                modelId = model.id
            )
            it.copy(
                cloudApiConfig = config,
                selectedCloudChatModelId = model.id,
                selectedChatBackend = ChatBackend.CLOUD,
                params = modelParams,
                chatSessions = sessionsToPersist,
                busy = false,
                statusMessage = "已切换到云端模型：${config.safeDisplayName()}",
                tab = AppTab.CHAT
            )
        }
        persistChatSessions(sessionsToPersist)
    }


    /**
     * The body of [loadModel], moved out of its inline lambda.
     *
     * That lambda was 579 lines and compiled into a single `invokeSuspend` method, which sat
     * right at the JVM 64 KB per-method limit: any edit to this file could tip it over and
     * crash the Kotlin compiler with "Exception while generating code for". Moving the body
     * into this function leaves `loadModel` with one call, so neither method is near a limit.
     *
     * Moved verbatim: no statement, condition, ordering or message was edited. Because the
     * lambda's receiver was the launch's CoroutineScope, this is an extension function on
     * CoroutineScope, and the two `return@launchModelOperation` early exits became `return`.
     */
    private suspend fun CoroutineScope.loadModelBody(requestedModel: ModelManifest) {
    val runtimeBeforeLoad = captureLoadedRuntimeSnapshot()
    fun failBeforeNativeReplacement(message: String) {
        // Release the input lock before diagnostics. Native/Binder reads can
        // be slow or fail, and a preflight failure must not leave a cancelled
        // generation projected as active in the UI.
        val stats = engine.stats.value
        uiStateStore.update {
            it.afterGenerationTerminated(
                stats = stats,
                statusMessage = message
            ).copy(
                busy = false,
                engineLifecycle = if (stats.loaded) {
                    AgentEngineLifecycle.READY
                } else {
                    AgentEngineLifecycle.ERROR
                }
            )
        }
        val nativeStats = engine.nativeStatsJsonOrEmpty()
        val recentLogs = engine.recentEngineLogsOrEmpty()
        uiStateStore.update {
            it.copy(
                nativeStatsJson = nativeStats,
                logs = recentLogs,
            )
        }
    }
    cancelGenerationJob()
    engine.stopGeneration()
    uiStateStore.update {
        it.copy(
            busy = true,
            engineLifecycle = AgentEngineLifecycle.LOADING,
            statusMessage = "正在加载 ${requestedModel.displayName}...",
            modelLoadStage = "正在检查模型文件"
        )
    }
    val preflight = modelStore.validateForLoad(requestedModel.id)
    val validatedCatalog = publishManagedChatCatalogAfterValidation()
    if (!preflight.canLoad) {
        // Keep model-load failures observable without leaking prompts, API keys,
        // or model contents.  The UI message remains user friendly while this
        // structured event gives support tooling a stable runtime/code boundary.
        Log.e(
            "MCA-ModelLoad",
            "preflight_failed modelId=${requestedModel.id} runtime=${requestedModel.runtime.storageValue} " +
                "title=${preflight.title} details=${preflight.details.take(512)}"
        )
        failBeforeNativeReplacement("加载前检查失败：${preflight.message}")
        return
    }
    // Resolve the semantic profile before any isolated bootstrap/load work.  A model
    // switch must never probe the new native runtime with the previous model's sampling
    // budget or model-specific overrides; the formal publish below resolves it again
    // after the committed execution profile is known.
    val params = loadModelGenerationParams(
        backend = ChatBackend.LOCAL,
        modelId = requestedModel.id,
        defaults = _uiState.value.params
    )
    var persistedModels = validatedCatalog.models
    uiStateStore.update { it.copy(modelLoadStage = "正在准备运行环境") }
    var model = persistedModels.firstOrNull { it.id == requestedModel.id } ?: requestedModel
    val qairtVerifiedIds = if (model.runtime == ChatModelRuntime.GENIEX_QAIRT) {
        validatedCatalog.qairtVerifiedLocalModelIds
    } else {
        emptySet()
    }
    val device = currentDeviceProfile()
    val memoryAdmission = LocalModelMemoryAdmissionPolicy.evaluate(model, device)
    memoryAdmission.blocker?.let { message ->
        failBeforeNativeReplacement("加载前内存检查失败：$message")
        return
    }
    var nativeReplacementOccurred = false
    var stagedBootstrapTransactionId: String? = null
    var stagedBootstrapIdentityKey: String? = null

    suspend fun recoverAfterNativeReplacement(
        message: String,
        emptyLifecycle: AgentEngineLifecycle = AgentEngineLifecycle.ERROR
    ): String? = withContext(NonCancellable) {
        if (!nativeReplacementOccurred) {
            failBeforeNativeReplacement(message)
            return@withContext null
        }
        val restored = runtimeBeforeLoad?.let { snapshot ->
            runCatching {
                restoreLoadedRuntimeSnapshot(
                    snapshot,
                    "$message；已恢复此前加载的 ${snapshot.model.displayName}。"
                )
            }.getOrDefault(false)
        } ?: false
        if (!restored) {
            val unloadError = if (engine.stats.value.loaded) {
                runCatching { engine.unloadModel() }.exceptionOrNull()
            } else {
                null
            }
            clearNativeRuntimeSessionState(
                lifecycle = emptyLifecycle,
                statusMessage = buildString {
                    append(message)
                    if (runtimeBeforeLoad != null) append("；此前稳定模型恢复失败")
                    if (unloadError != null) append("；原生卸载同时失败：${unloadError.message}")
                }
            )
        }
        if (restored) runtimeBeforeLoad.profile.profileId else null
    }

    suspend fun rejectStagedBootstrap(
        error: Throwable,
        failureStage: String
    ): RuntimeRecoveryPlan? = withContext(NonCancellable) {
        val transactionId = stagedBootstrapTransactionId ?: return@withContext null
        val identityKey = stagedBootstrapIdentityKey ?: return@withContext null
        val pending = runtimeProfileStore.pendingTransaction(identityKey)
        if (pending?.journal?.transactionId != transactionId) return@withContext null
        val remoteCode = (error as? TuningProbeWorkerRemoteException)?.code
        val recovery = runtimeProfileStore.rejectCandidate(
            transactionId = transactionId,
            failureStage = failureStage,
            failureCode = remoteCode?.takeIf(String::isNotBlank) ?: "BOOTSTRAP_LOAD_FAILED",
            failureSummary = error.message ?: "The isolated bootstrap load failed."
        )
        stagedBootstrapTransactionId = null
        stagedBootstrapIdentityKey = null
        recovery.rollbackProfileId?.let {
            synchronized(pendingRuntimeRecoveries) {
                pendingRuntimeRecoveries[recovery.identityKey] = recovery
            }
        }
        recovery
    }

    suspend fun completeBootstrapRecoveryIfRestored(
        recovery: RuntimeRecoveryPlan?,
        restoredProfileId: String?
    ) = withContext(NonCancellable) {
        val rollbackId = recovery?.rollbackProfileId ?: return@withContext
        val restoredSnapshot = runtimeBeforeLoad
        if (restoredProfileId != rollbackId ||
            restoredSnapshot?.profile?.runtimeIdentity?.identityHash != recovery.identityKey
        ) {
            return@withContext
        }
        runtimeProfileStore.completeRecovery(recovery.transactionId, rollbackId)
        synchronized(pendingRuntimeRecoveries) {
            pendingRuntimeRecoveries.remove(recovery.identityKey)
        }
    }

    try {
        if (memoryAdmission.mode == LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP) {
            uiStateStore.update {
                it.copy(statusMessage = "正在以稀疏 MoE mmap 模式加载 ${model.displayName}…")
            }
        }
    val runtime = model.runtime.toLocalChatRuntime()
    val qairtBundleSha256 = currentQairtBundleSha256(
        requested = model,
        persistedModels = persistedModels
    )
    val qairtAdmissionPassed = model.runtime == ChatModelRuntime.GENIEX_QAIRT &&
        model.id in qairtVerifiedIds
    val identity = RuntimeIdentityFactory.create(
        context = getApplication(),
        model = model,
        runtime = runtime,
        device = device,
        installationScopeId = installationScopeId,
        qairtAdmissionPassed = qairtAdmissionPassed
    )
    runtimeProfileStore.upsertIdentity(identity.toRuntimeIdentityEntity())

    val persistedState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
    val recoveryPlan = synchronized(pendingRuntimeRecoveries) {
        pendingRuntimeRecoveries[identity.identityHash]
    }
    val persistedProfile = recoveryPlan?.rollbackProfileId?.let {
        runtimeProfileStore.reconstructedProfile(it)
    } ?: persistedState?.activeExecutionProfile
    val capabilities = modelTuningCapabilities(model, identity, qairtAdmissionPassed)
    val adaptive = buildAdaptiveTuningRecommendation(model, identity, device, capabilities)
    // A profile persisted before variant-aware LiteRT transport
    // selection may contain the generic CPU default. Do not let that
    // stale profile silently force a Qualcomm/GPU artifact onto CPU;
    // retain it only when it agrees with the model-declared advisory
    // backend (or when the user explicitly overrode backend).
    val reusablePersistedProfile = persistedProfile?.takeIf { persisted ->
        val preferred = capabilities.preferredBackend
        preferred == null || "backend" in runtimeUserOverrideFields ||
            persisted.resolvedLoadBoundValues.value("backend")?.toString()
                ?.trim()?.lowercase()?.replace('-', '_') == preferred
    }
    var bootstrapProfile = reusablePersistedProfile ?: adaptive.executionProfile.engineProfile
    val persistedRevisions = runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
    if (runtimeUserOverrideFields.isNotEmpty()) {
        val requested = engine.resolveExecutionProfile(
            identity = identity,
            requestedParamsJson = params.toJson(),
            profileId = "manual-load-${UUID.randomUUID().toString().replace("-", "").take(20)}",
            revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
        ).profile
        bootstrapProfile = mergeUserRequestedExecutionProfile(
            base = bootstrapProfile,
            requested = requested,
            authoritativeFields = runtimeUserOverrideFields
        )
    }
    // Older persisted llama profiles left prompt batching at one thread
    // while decode used the tuned worker count. Upgrade that implicit
    // default once so GGUF prefill does not run effectively single-
    // threaded. Explicit n_threads_batch selections are preserved.
    bootstrapProfile = bootstrapProfile.migrateLegacyLlamaBatchThreads(
        profileId = "${bootstrapProfile.profileId}-prefill-${UUID.randomUUID().toString().take(8)}",
        revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
    )
    // Snapshots written before desired hot/behavior values were persisted
    // cannot reproduce their original desired digest after normalization.
    // Keep the effective settings, but write a complete successor after
    // this formal native load rather than reusing the incomplete record.
    val persistedBootstrapSnapshot = persistedProfile
        ?.takeIf { it.profileId == bootstrapProfile.profileId }
        ?.let { runtimeProfileStore.profile(it.profileId) }
    if (persistedBootstrapSnapshot?.requiresDesiredExecutionSnapshotMigration() == true) {
        bootstrapProfile = bootstrapProfile.copy(
            profileId = "${bootstrapProfile.profileId}-snapshot-${UUID.randomUUID().toString().take(8)}",
            revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
        )
    }
    if (persistedProfile == null && runtimeProfileStore.profile(bootstrapProfile.profileId)
            ?.recordState == PersistedProfileRecordState.REJECTED.name
    ) {
        bootstrapProfile = bootstrapProfile.copy(
            profileId = "${bootstrapProfile.profileId}-retry-${UUID.randomUUID().toString().take(8)}",
            revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
        )
    }
    val activeProfile = persistedState?.activeProfile
    val reusableBootstrapProof = activeProfile != null &&
        activeProfile.recordState == PersistedProfileRecordState.COMMITTED.name &&
        activeProfile.verificationLevel in setOf(
            PersistedProfileVerificationLevel.SAFE.name,
            PersistedProfileVerificationLevel.COMPATIBLE.name,
            PersistedProfileVerificationLevel.DEVICE_VERIFIED.name
        ) &&
        activeProfile.profileId == bootstrapProfile.profileId &&
        activeProfile.resolvedLoadSignature == bootstrapProfile.resolvedLoadSignature.digest &&
        activeProfile.committedExecutionSignature ==
            bootstrapProfile.committedExecutionSignature.digest
    val needsBootstrapCommit = !reusableBootstrapProof
    // The formal MainActivity load is the real native admission path.
    // Running a full isolated load + canary here and then loading the
    // same multi-gigabyte GGUF again doubles cold-start latency and
    // gives no additional capability to the user-facing action.
    val ordinaryBootstrapRuntime = false
    if (needsBootstrapCommit && ordinaryBootstrapRuntime) {
        val transactionId = "bootstrap-load-${UUID.randomUUID()}"
        stagedBootstrapTransactionId = transactionId
        stagedBootstrapIdentityKey = identity.identityHash
        stageBootstrapProfile(
            transactionId = transactionId,
            profile = bootstrapProfile,
            rollbackTargetProfileId = activeProfile
                ?.takeIf { it.recordState == PersistedProfileRecordState.COMMITTED.name }
                ?.profileId
        )

        if (engine.stats.value.loaded) {
            try {
                engine.stopGeneration()
                engine.unloadModel()
            } catch (error: Throwable) {
                if (engine.nativeRuntimeReleaseObservedNow()) {
                    nativeReplacementOccurred = true
                    clearNativeRuntimeSessionState(
                        lifecycle = AgentEngineLifecycle.LOADING,
                        statusMessage = "当前模型已释放，但隔离安全启动准备失败。",
                        busy = true
                    )
                }
                throw IllegalStateException(
                    "隔离安全启动前无法释放当前模型：${error.message ?: "未知错误"}",
                    error
                )
            }
            nativeReplacementOccurred = true
            clearNativeRuntimeSessionState(
                lifecycle = AgentEngineLifecycle.LOADING,
                statusMessage = "已释放当前模型内存，正在隔离进程验证 ${model.displayName}…",
                busy = true
            )
        }

        val workerResult = TuningProbeWorkerClient(getApplication<Application>()).probe(
            probeKind = TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD,
            transactionId = transactionId,
            identityKey = identity.identityHash,
            modelId = model.id,
            profileId = bootstrapProfile.profileId,
            resolvedLoadSignature = bootstrapProfile.resolvedLoadSignature.digest,
            committedExecutionSignature = bootstrapProfile.committedExecutionSignature.digest
        ) { progress ->
            uiStateStore.update { state ->
                if (state.busy) {
                    state.copy(statusMessage = "隔离安全启动：${progress.message}")
                } else {
                    state
                }
            }
        }
        recordBootstrapProbeMeasurement(workerResult)
        require(
            workerResult.passed &&
                workerResult.signatureMatched &&
                BootstrapLoadCanaryPolicy.matches(workerResult.output)
        ) {
            "隔离安全启动未通过：${workerResult.detail}"
        }
        uiStateStore.update {
            it.copy(statusMessage = "隔离安全启动通过，正在正式加载 ${model.displayName}…")
        }
    }
    val loadParams = model.loadParamsForExecutionProfile(bootstrapProfile)
    // LLAMA, MNN, and QAIRT keep the long-lived native handle in a worker process.
    // The load Result still owns concrete runtime failures and recovery decisions.
    uiStateStore.update { it.copy(modelLoadStage = "正在加载模型，首次加载可能较慢") }
    val nativeLoad = engine.loadModel(
        modelPath = model.path,
        runtime = runtime,
        params = loadParams,
        qairtBundleSha256 = qairtBundleSha256,
        runtimeIdentity = identity,
        executionProfile = bootstrapProfile
    )
    if (nativeLoad.isFailure && runtimeBeforeLoad != null &&
        engine.nativeRuntimeReleaseObservedNow()
    ) {
        nativeReplacementOccurred = true
    }
    nativeLoad.getOrThrow()
    nativeReplacementOccurred = true
    val nativeVisionLoadStats = engine.nativeStatsJsonOrEmpty()
    val baseRunnerLoaded = runCatching {
        JSONObject(nativeVisionLoadStats).optBoolean("loaded", false)
    }.getOrDefault(false)
    require(baseRunnerLoaded) {
        "本地聊天引擎未确认模型已加载，已安全阻止请求。请重试加载；如果仍失败，请重新校验模型文件。"
    }
    val visionReadinessWarning = when (model.runtime) {
        ChatModelRuntime.LLAMA_CPP -> llamaCppVisionReadinessWarning(
            runtime = model.runtime,
            configuredProjectorPath = loadParams.visionProjectorPath,
            nativeStatsJson = nativeVisionLoadStats
        )
        ChatModelRuntime.LITERT_LM -> {
            val stats = runCatching { JSONObject(nativeVisionLoadStats) }.getOrNull()
            when {
                stats?.optBoolean("visionModelKnownTextOnly", false) == true ->
                    LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
                stats?.has("visionModelVisualComponentsPresent") == true &&
                    !stats.optBoolean("visionModelVisualComponentsPresent", false) ->
                    LITERT_LM_VISION_COMPONENTS_UNAVAILABLE_MESSAGE
                !liteRtVisionInputAvailable(nativeVisionLoadStats) ->
                    LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
                else -> null
            }
        }
        else -> null
    }
    uiStateStore.update { it.copy(modelLoadStage = "正在确认模型运行配置") }
    val formalProfile = engine.activeExecutionProfile() ?: bootstrapProfile
    require(formalProfile.profileId == bootstrapProfile.profileId) {
        "正式加载激活了非预期 profile。"
    }
    val formalSignatures = engine.parameterSignatureSnapshot()
        ?: error("正式加载未发布参数签名快照。")
    require(bootstrapProfile.matchesExactParameterSignatures(formalSignatures)) {
        "正式加载的参数签名与隔离进程验证的 profile 不一致。"
    }

    stagedBootstrapTransactionId?.let { transactionId ->
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.VALIDATING,
            stage = "BOOTSTRAP_FORMAL_LOAD_VERIFIED"
        )
        runtimeProfileStore.commitCandidate(
            transactionId = transactionId,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            activeLoadedSignature = requireNotNull(formalSignatures.active).digest,
            effectiveExecutionSignature = requireNotNull(formalSignatures.effective).digest
        )
        stagedBootstrapTransactionId = null
        stagedBootstrapIdentityKey = null
    }
    if (needsBootstrapCommit && !ordinaryBootstrapRuntime) {
        persistBootstrapProfile(
            profile = formalProfile,
            sourceSummary = JSONObject()
                .put("kind", "formal_load")
                .put("runtime", model.runtime.name)
                .toString()
        )
    }

    // The new model is not user-visible until the exact formal
    // signatures and any staged bootstrap transaction are committed.
    clearNativeRuntimeSessionState(
        lifecycle = AgentEngineLifecycle.LOADING,
        statusMessage = "正式加载已验证，正在发布运行状态…",
        busy = true
    )
    activeRuntimeIdentity = identity
    activeVisionProjectorStamp = loadedVisionProjectorStamp(model, identity)
    activeModelForRuntimeProfile = model
    uiStateStore.update { it.copy(modelLoadStage = "正在完成准备") }
    val modelParams = loadModelGenerationParams(
        backend = ChatBackend.LOCAL,
        modelId = model.id,
        defaults = params
    )
    GenerationPreferences.persistGenerationParams(getApplication(), modelParams)
    val effectiveParams = mergedExecutionProfile(modelParams, formalProfile)
    uiStateStore.update { state ->
        state.copy(
            params = effectiveParams,
            reloadRequired = false,
            engineLifecycle = AgentEngineLifecycle.LOADING,
            statusMessage = "正式加载已验证，正在发布运行状态…"
        )
    }
        if (recoveryPlan != null) {
            runtimeProfileStore.completeRecovery(
                transactionId = recoveryPlan.transactionId,
                restoredProfileId = recoveryPlan.rollbackProfileId
            )
            synchronized(pendingRuntimeRecoveries) {
                pendingRuntimeRecoveries.remove(identity.identityHash)
            }
        }
        modelStore.markLoaded(model.id)
        activeAdaptiveRecommendation = adaptive
        val recommendation = runCatching {
            advisor.recommend(
                device = device,
                localModels = listOf(model),
                remoteFiles = emptyList(),
                preference = _uiState.value.preference
            )
        }.getOrNull()
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        val profileState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
        val pendingTransaction = runtimeProfileStore.pendingTransaction(identity.identityHash)
        val nativeStatsAfterLoad = engine.nativeStatsJsonOrEmpty()
        val visionReadyAfterLoad = runCatching {
            JSONObject(nativeStatsAfterLoad).optBoolean("visionReady", false)
        }.getOrDefault(false)
        val logsAfterLoad = engine.recentEngineLogsOrEmpty()
        managedRuntimeReadinessRefreshGate.invalidate()
        uiStateStore.update { state ->
            cloudApiStore.saveSelectedBackend(ChatBackend.LOCAL)
            sessionsToPersist = state.chatSessions.bindSession(
                sessionId = state.activeChatSessionId,
                assistantId = state.selectedAssistantId,
                modelMode = ChatBackend.LOCAL.bindingValue(),
                modelId = model.id
            )
            state.copy(
                loadedModelId = model.id,
                loadedModelName = model.displayName,
                selectedChatBackend = ChatBackend.LOCAL,
                models = modelStore.listModels(),
                chatSessions = sessionsToPersist,
                busy = false,
                stats = engine.stats.value,
                engineLifecycle = AgentEngineLifecycle.READY,
                autoTuningInProgress = false,
                deviceProfile = device,
                agentRecommendation = recommendation ?: state.agentRecommendation,
                profileId = engine.activeExecutionProfile()?.profileId,
                revision = engine.activeExecutionProfile()?.revision,
                profileRecordState = if (profileState?.activeProfile != null) {
                    AgentProfileRecordState.COMMITTED
                } else {
                    AgentProfileRecordState.NONE
                },
                verification = profileState?.activeProfile?.verificationLevel
                    ?.let(::agentVerification)
                    ?: AgentProfileVerification.SAFE,
                pendingProfile = pendingTransaction?.let { pending ->
                    AgentPendingProfile(
                        profileId = pending.pendingProfile.profileId,
                        revision = pending.pendingProfile.revision,
                        summary = "${pending.journal.stage} · ${pending.pendingProfile.resolvedLoadSignature.take(12)}",
                        readyToApply = pending.journal.state in setOf(
                            TuningJournalState.STAGED.name,
                            TuningJournalState.VALIDATING.name
                        )
                    )
                },
                rollbackProfile = profileState?.pointers?.rollbackTargetProfileId?.let { targetId ->
                    AgentRollbackProfile(targetId, null, "事务锁定的稳定回退目标", true)
                },
                tuningJobState = profileState?.activeJob?.let(::agentJobState)
                    ?: if (pendingTransaction != null) AgentTuningJobState.VALIDATING else AgentTuningJobState.IDLE,
                tuningPhase = pendingTransaction?.journal?.stage,
                tuningCandidateProgress = AgentCandidateProgress(),
                logs = logsAfterLoad,
                nativeStatsJson = nativeStatsAfterLoad,
                statusMessage = buildString {
                    append("已加载：").append(model.displayName)
                    if (memoryAdmission.mode == LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP) {
                        append("，稀疏 MoE mmap 模式已启用")
                    }
                    if (visionReadyAfterLoad) {
                        append("，本地视觉组件已就绪")
                    } else if (visionReadinessWarning != null) {
                        // A failed projector must disable image input, not discard a
                        // perfectly usable text runner. Vision readiness is an
                        // independent capability from chat-model readiness.
                        append("。图片输入未启用：").append(visionReadinessWarning)
                    }
                    if (visionReadinessWarning == null || visionReadyAfterLoad) {
                        append("。安全基线和正确性校准通过，可直接聊天；性能调优可在 Agent 页单独启动。")
                    } else {
                        append("。安全基线和正确性校准通过，可继续文字聊天；图片输入需修复投影器后再用。性能调优可在 Agent 页单独启动。")
                    }
                },
                tab = AppTab.CHAT
            )
        }
    persistChatSessions(sessionsToPersist)
    } catch (error: CancellationException) {
        if (runtimeBeforeLoad != null &&
            engine.nativeRuntimeReleaseObservedNow()
        ) {
            nativeReplacementOccurred = true
        }
        val bootstrapRecovery = runCatching {
            rejectStagedBootstrap(error, "BOOTSTRAP_LOAD_CANCELLED")
        }.getOrNull()
        val restoredProfileId = recoverAfterNativeReplacement(
            message = "模型加载已取消。",
            emptyLifecycle = AgentEngineLifecycle.UNLOADED
        )
        runCatching {
            completeBootstrapRecoveryIfRestored(bootstrapRecovery, restoredProfileId)
        }
        throw error
    } catch (error: Throwable) {
        Log.e(
            "McaMainViewModel",
            "formal model load failed after native handoff: ${error.message.orEmpty()}",
            error
        )
        if (runtimeBeforeLoad != null &&
            engine.nativeRuntimeReleaseObservedNow()
        ) {
            nativeReplacementOccurred = true
        }
        val nativeStats = engine.nativeStatsJsonOrEmpty()
        val failure = LocalModelLoadFailureClassifier.classify(error.message, nativeStats)
        val bootstrapRecovery = runCatching {
            rejectStagedBootstrap(error, "BOOTSTRAP_LOAD")
        }.getOrNull()
        val restoredProfileId = recoverAfterNativeReplacement("加载失败：${failure.userMessage}")
        runCatching {
            completeBootstrapRecoveryIfRestored(bootstrapRecovery, restoredProfileId)
        }
    }
    }
    fun loadModel(requestedModel: ModelManifest) {
        launchModelOperation("模型操作未完成") {
            loadModelBody(requestedModel)
        }
    }

    fun verifyModel(model: ModelManifest) {
        launchModelOperation("模型操作未完成") {
            busy("正在校验 ${model.displayName}...")
            val result = modelStore.validateForLoad(model.id)
            val validatedCatalog = publishManagedChatCatalogAfterValidation()
            val validatedModel = validatedCatalog.models.firstOrNull { it.id == model.id } ?: model
            val qairtDryRun = if (
                result.canLoad &&
                validatedModel.runtime == ChatModelRuntime.GENIEX_QAIRT &&
                validatedModel.id !in validatedCatalog.qairtVerifiedLocalModelIds
            ) {
                uiStateStore.update {
                    it.copy(statusMessage = "模型包校验通过，正在独立进程执行 QAIRT create/generate/destroy 诊断…")
                }
                runCatching {
                    val params = _uiState.value.params
                    QairtDryRunWorkerClient(getApplication<Application>()).certify(
                        modelId = validatedModel.id,
                        nCtx = params.nCtx,
                        nThreads = params.nThreads
                    ) { progress ->
                        uiStateStore.update { state ->
                            if (state.busy) state.copy(statusMessage = progress.message) else state
                        }
                    }
                }
            } else {
                null
            }
            val localModels = modelStore.listModels()
            val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(getApplication(), localModels)
            val qairtVerified = validatedModel.id in qairtVerifiedIds
            managedRuntimeReadinessRefreshGate.invalidate()
            uiStateStore.update {
                it.copy(
                    busy = false,
                    models = localModels,
                    qairtVerifiedLocalModelIds = qairtVerifiedIds,
                    qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                        models = localModels,
                        verifiedLocalModelIds = qairtVerifiedIds,
                        recommendations = it.recommendedRemoteModels
                    ),
                    statusMessage = when {
                        !result.canLoad -> "模型校验失败：${result.message}"
                        qairtDryRun?.isFailure == true ->
                            "QAIRT 运行诊断失败：${qairtDryRun.exceptionOrNull()?.message.orEmpty()}。仍可直接尝试加载，实际 native 结果决定兼容性。"
                        qairtDryRun?.isSuccess == true && !qairtVerified ->
                            "QAIRT 运行诊断完成但未写入证据记录；这不会阻止模型加载。"
                        validatedModel.runtime == ChatModelRuntime.GENIEX_QAIRT && !qairtVerified ->
                            "模型包完整性校验通过；可直接在隔离 native worker 中尝试加载。"
                        qairtDryRun?.isSuccess == true ->
                            "QAIRT 运行诊断通过：已确认骁龙 NPU、固定回答与干净卸载。"
                        validatedModel.runtime == ChatModelRuntime.GENIEX_QAIRT ->
                            "模型包完整性校验通过，且已有当前设备运行诊断证据。"
                        else -> "模型校验通过：${result.message}"
                    }
                )
            }
        }
    }

    private fun captureLoadedRuntimeSnapshot(): LoadedRuntimeSnapshot? {
        if (!engine.stats.value.loaded) return null
        val state = _uiState.value
        val model = activeModelForRuntimeProfile
            ?: state.models.firstOrNull { it.id == state.loadedModelId }
            ?: return null
        val profile = engine.activeExecutionProfile() ?: return null
        return LoadedRuntimeSnapshot(
            model = model,
            profile = profile,
            uiState = state,
            adaptiveRecommendation = activeAdaptiveRecommendation
        )
    }

    private fun clearNativeRuntimeSessionState(
        lifecycle: AgentEngineLifecycle,
        statusMessage: String,
        busy: Boolean = false
    ) {
        directParameterStageGeneration.incrementAndGet()
        directParameterStageJob?.cancel()
        activeRuntimeIdentity = null
        activeVisionProjectorStamp = null
        activeModelForRuntimeProfile = null
        activeAdaptiveRecommendation = null
        pendingAdaptiveRecommendation = null
        pendingProfileTransactionId = null
        activeProfileTransactionId = null
        val nativeStats = engine.nativeStatsJsonOrEmpty()
        val recentLogs = engine.recentEngineLogsOrEmpty()
        uiStateStore.update { state ->
            state.afterNativeRuntimeReleased(
                lifecycle = lifecycle,
                statusMessage = statusMessage,
                stats = engine.stats.value,
                busy = busy
            ).copy(
                nativeStatsJson = nativeStats,
                logs = recentLogs
            )
        }
    }

    private suspend fun restoreLoadedRuntimeSnapshot(
        snapshot: LoadedRuntimeSnapshot,
        statusMessage: String
    ): Boolean {
        if (!restoreExactRuntimeProfile(snapshot.model, snapshot.profile)) return false
        val activeProfile = engine.activeExecutionProfile() ?: snapshot.profile
        val profileState = runtimeProfileStore.currentRuntimeState(activeProfile.runtimeIdentity.identityHash)
        activeRuntimeIdentity = activeProfile.runtimeIdentity
        activeVisionProjectorStamp = loadedVisionProjectorStamp(snapshot.model, activeProfile.runtimeIdentity)
        activeModelForRuntimeProfile = snapshot.model
        activeAdaptiveRecommendation = snapshot.adaptiveRecommendation
        pendingAdaptiveRecommendation = null
        pendingProfileTransactionId = null
        activeProfileTransactionId = null
        val nativeStats = engine.nativeStatsJsonOrEmpty()
        val recentLogs = engine.recentEngineLogsOrEmpty()
        managedRuntimeReadinessRefreshGate.invalidate()
        uiStateStore.update { state ->
            state.copy(
                models = modelStore.listModels(),
                loadedModelId = snapshot.model.id,
                loadedModelName = snapshot.model.displayName,
                selectedChatBackend = snapshot.uiState.selectedChatBackend,
                busy = false,
                isGenerating = false,
                params = mergedExecutionProfile(snapshot.uiState.params, activeProfile),
                stats = engine.stats.value,
                autoTuningInProgress = false,
                rollbackParams = null,
                profileId = activeProfile.profileId,
                revision = activeProfile.revision,
                profileRecordState = if (profileState?.activeProfile != null) {
                    AgentProfileRecordState.COMMITTED
                } else {
                    AgentProfileRecordState.NONE
                },
                verification = profileState?.activeProfile?.verificationLevel
                    ?.let(::agentVerification)
                    ?: snapshot.uiState.verification,
                engineLifecycle = AgentEngineLifecycle.READY,
                tuningJobState = AgentTuningJobState.IDLE,
                reloadRequired = false,
                pendingProfile = null,
                rollbackProfile = snapshot.uiState.rollbackProfile,
                tuningEtaSeconds = null,
                tuningPhase = null,
                tuningCandidateProgress = AgentCandidateProgress(),
                nativeStatsJson = nativeStats,
                logs = recentLogs,
                statusMessage = statusMessage
            )
        }
        return true
    }

    fun unloadModel(model: ModelManifest) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.loadedModelId != model.id) return@launch
            directParameterStageJob?.cancel()
            cancelGenerationJob()
            val releasedIdentity = activeRuntimeIdentity
            uiStateStore.update {
                it.copy(
                    busy = true,
                    engineLifecycle = AgentEngineLifecycle.STOPPING,
                    statusMessage = "正在卸载 ${model.displayName}…"
                )
            }
            val nativeRelease = runCatching {
                engine.stopGeneration()
                engine.unloadModel()
            }
            if (nativeRelease.isFailure &&
                !engine.nativeRuntimeReleaseObservedNow()
            ) {
                val error = nativeRelease.exceptionOrNull()
                val message = "卸载失败：${error?.message ?: "未知错误"}"
                val stats = engine.stats.value
                uiStateStore.update {
                    it.afterGenerationTerminated(
                        stats = stats,
                        statusMessage = message
                    ).copy(busy = false)
                }
                return@launch
            }
            // Native ownership is gone. Clear every active projection before
            // transaction cleanup, which may independently fail.
            clearNativeRuntimeSessionState(
                lifecycle = AgentEngineLifecycle.UNLOADED,
                statusMessage = "已卸载：${model.displayName}。模型文件和自定义参数已保留。"
            )
            val cleanupError = runCatching {
                clearPendingRuntimeTransactionForLifecycle(
                    reason = "USER_UNLOAD",
                    identity = releasedIdentity
                )
            }.exceptionOrNull()
            if (cleanupError != null || nativeRelease.isFailure) {
                uiStateStore.update {
                    it.copy(
                        statusMessage = buildString {
                            append("已卸载：${model.displayName}。模型文件和自定义参数已保留")
                            nativeRelease.exceptionOrNull()?.let { error ->
                                append("；原生销毁返回异常但已确认 handle 释放：${error.message}")
                            }
                            cleanupError?.let { error ->
                                append("；待应用参数清理失败，将在下次加载时恢复：${error.message}")
                            }
                        }
                    )
                }
            }
        }
    }

    fun deleteModel(model: ModelManifest) {
        viewModelScope.launch(Dispatchers.IO) {
            directParameterStageJob?.cancel()
            val wasLoaded = _uiState.value.loadedModelId == model.id
            var nativeReleased = false
            var lifecycleWarning: String? = null
            val releasedIdentity = activeRuntimeIdentity
            uiStateStore.update { it.copy(busy = true, statusMessage = "正在删除 ${model.displayName}…") }
            runCatching {
                if (wasLoaded) {
                    cancelGenerationJob()
                    engine.stopGeneration()
                    val release = runCatching { engine.unloadModel() }
                    if (release.isFailure &&
                        !engine.nativeRuntimeReleaseObservedNow()
                    ) {
                        throw requireNotNull(release.exceptionOrNull())
                    }
                    nativeReleased = true
                    // Native ownership is gone now. Clear the public runtime
                    // projection before pending/file cleanup, either of which
                    // may fail independently.
                    clearNativeRuntimeSessionState(
                        lifecycle = AgentEngineLifecycle.UNLOADED,
                        statusMessage = "已卸载 ${model.displayName}，正在删除模型文件…",
                        busy = true
                    )
                    val cleanupError = runCatching {
                        clearPendingRuntimeTransactionForLifecycle(
                            reason = "USER_DELETE",
                            identity = releasedIdentity
                        )
                    }.exceptionOrNull()
                    lifecycleWarning = buildList {
                        release.exceptionOrNull()?.let { error ->
                            add("原生销毁返回异常但已确认 handle 释放：${error.message}")
                        }
                        cleanupError?.let { error ->
                            add("待应用参数清理失败，将在下次加载时恢复：${error.message}")
                        }
                    }.joinToString("；").takeIf { it.isNotBlank() }
                }
                check(modelStore.deleteModel(model.id)) { "模型文件或记录未能删除" }
            }.onSuccess {
                val remainingModels = modelStore.listModels()
                val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(getApplication(), remainingModels)
                managedRuntimeReadinessRefreshGate.invalidate()
                uiStateStore.update {
                    it.copy(
                        models = remainingModels,
                        qairtVerifiedLocalModelIds = qairtVerifiedIds,
                        qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                            models = remainingModels,
                            verifiedLocalModelIds = qairtVerifiedIds,
                            recommendations = it.recommendedRemoteModels
                        ),
                        loadedModelId = it.loadedModelId?.takeUnless { id -> id == model.id },
                        loadedModelName = if (wasLoaded) null else it.loadedModelName,
                        busy = false,
                        isGenerating = if (wasLoaded) false else it.isGenerating,
                        engineLifecycle = if (wasLoaded) AgentEngineLifecycle.UNLOADED else it.engineLifecycle,
                        reloadRequired = if (wasLoaded) false else it.reloadRequired,
                        pendingProfile = if (wasLoaded) null else it.pendingProfile,
                        statusMessage = buildString {
                            append("已删除：${model.displayName}")
                            lifecycleWarning?.let { append("；").append(it) }
                        }
                    )
                }
            }.onFailure { error ->
                val currentModels = modelStore.listModels()
                val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(getApplication(), currentModels)
                val failureMessage = if (nativeReleased) {
                    buildString {
                        append("删除失败，但模型已安全卸载：")
                        append(error.message ?: "请检查文件权限或存储状态")
                        lifecycleWarning?.let { append("；").append(it) }
                    }
                } else {
                    "删除失败：${error.message ?: "请检查文件权限或存储状态"}"
                }
                val stats = engine.stats.value
                managedRuntimeReadinessRefreshGate.invalidate()
                uiStateStore.update { state ->
                    val releasedGeneration = if (wasLoaded && !nativeReleased) {
                        state.afterGenerationTerminated(
                            stats = stats,
                            statusMessage = failureMessage
                        )
                    } else {
                        state
                    }
                    releasedGeneration.copy(
                        models = currentModels,
                        qairtVerifiedLocalModelIds = qairtVerifiedIds,
                        qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                            models = currentModels,
                            verifiedLocalModelIds = qairtVerifiedIds,
                            recommendations = releasedGeneration.recommendedRemoteModels
                        ),
                        busy = false,
                        statusMessage = failureMessage
                    )
                }
            }
        }
    }

    private suspend fun clearPendingRuntimeTransactionForLifecycle(
        reason: String,
        identity: ModelRuntimeIdentity? = activeRuntimeIdentity
    ) {
        identity ?: return
        val pending = runtimeProfileStore.pendingTransaction(identity.identityHash) ?: return
        val recovery = runtimeProfileStore.rejectCandidate(
            transactionId = pending.journal.transactionId,
            failureStage = reason,
            failureCode = "LIFECYCLE_CLOSED",
            failureSummary = "模型生命周期结束，未应用候选已安全撤销。"
        )
        runtimeProfileStore.completeRecovery(
            transactionId = pending.journal.transactionId,
            restoredProfileId = recovery.rollbackProfileId
        )
        if (pendingProfileTransactionId == pending.journal.transactionId) {
            pendingProfileTransactionId = null
        }
    }

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
                    pendingChatImageAction = PendingChatImageAction(
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

    private fun invalidatePendingChatImagePreparation() {
        chatImagePromptBridgeGeneration.incrementAndGet()
        chatImagePromptBridgeJob?.cancel()
        chatImagePromptBridgeJob = null
        pendingImageTranslation = null
        pendingChatImageAction = null
        uiStateStore.update { it.copy(
            pendingChatImageTranslationDraft = null,
            pendingChatImageActionDraft = null
        ) }
    }

    fun resolveChatImageActionDraft(id: String, primary: Boolean, editedText: String) {
        val pending = pendingChatImageAction?.takeIf { it.id == id } ?: return
        if (!pending.stillOwned()) {
            invalidatePendingChatImagePreparation()
            showStatus("会话、模型或原始草稿已变化，请重新发送。")
            return
        }
        val text = editedText.takeUtf8Prefix(MAX_DIRECT_COMPOSER_UTF8_BYTES).text
        invalidatePendingChatImagePreparation()
        uiStateStore.update { it.copy(input = text) }
        when (pending.kind) {
            com.muyuchat.feature.chat.ChatImageActionKind.AMBIGUOUS -> {
                if (primary) {
                    val prompt = parseExplicitChatImageIntent(text)?.sourceText ?: text
                    sendChatImageRequestWithPromptBridge(
                        visibleUserText = text,
                        prompt = prompt,
                        requestedOutputCount = classifyChatImageIntent(text).requestedOutputCount
                            ?: pending.requestedOutputCount
                    )
                } else {
                    val current = _uiState.value
                    sendPreparedMessage(current, current.prepareChatInput())
                }
            }
            com.muyuchat.feature.chat.ChatImageActionKind.CLOUD_TRANSLATION -> {
                if (primary) {
                    sendChatImageRequestWithPromptBridge(
                        visibleUserText = text,
                        prompt = parseExplicitChatImageIntent(text)?.sourceText ?: text,
                        requestedOutputCount = classifyChatImageIntent(text).requestedOutputCount
                            ?: pending.requestedOutputCount,
                        allowCloudTranslationFallback = true
                    )
                } else {
                    showStatus("已取消云端整理，原始描述仍在输入框。")
                }
            }
        }
    }

    fun importOfflinePromptTranslationBundle(uri: Uri) {
        if (offlineTranslationInstallJob?.isActive == true || offlineTranslationDownloadJob?.isActive == true) return
        offlineTranslationInstallJob = viewModelScope.launch {
            uiStateStore.update { it.copy(offlineTranslationInstalling = true, offlineTranslationStatus = "正在导入并校验 Hy-MT2") }
            try {
                val bundle = offlineTranslationInstaller.install(uri)
                cacheVerifiedOfflineTranslationBundle(bundle)
                uiStateStore.update { it.copy(
                    offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                    offlineTranslationStatus = offlineTranslationBundleReadyStatus(bundle),
                    statusMessage = "离线翻译包已导入。"
                ) }
            } catch (cancelled: CancellationException) {
                uiStateStore.update { it.copy(offlineTranslationStatus = "导入已取消") }
                throw cancelled
            } catch (error: Exception) {
                uiStateStore.update { it.copy(offlineTranslationStatus = error.message ?: "离线翻译包导入失败") }
            } finally {
                uiStateStore.update { it.copy(offlineTranslationInstalling = false) }
            }
        }
    }

    fun cancelOfflinePromptTranslationInstall() {
        offlineTranslationInstallJob?.cancel()
    }

    /**
     * The ViewModel side of [ImagePromptTranslationPreviewPort]: one route per member, no logic of
     * its own. The previews moved to [translateImagePromptPreview], which is now the only place an
     * offline prompt translation is decided.
     */
    private val imagePromptTranslationPreviewPort: ImagePromptTranslationPreviewPort =
        object : ImagePromptTranslationPreviewPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val offlinePromptTranslationService: OfflinePromptTranslationService
                get() = this@MainViewModel.offlinePromptTranslationService

            override suspend fun verifiedOfflineTranslationBundle():
                VerifiedOfflinePromptTranslationBundle? =
                this@MainViewModel.verifiedOfflineTranslationBundle()
        }

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

    fun downloadOfflinePromptTranslationBundle() {
        if (offlineTranslationDownloadJob?.isActive == true || offlineTranslationInstallJob?.isActive == true) return
        offlineTranslationDownloadJob = viewModelScope.launch {
            uiStateStore.update { it.copy(
                offlineTranslationDownloading = true,
                offlineTranslationDownloadBytes = 0L,
                offlineTranslationDownloadTotalBytes = 0L,
                offlineTranslationDownloadProgress = null,
                offlineTranslationStatus = "正在准备下载 Hy-MT2"
            ) }
            try {
                val bundle = offlineTranslationDownloader.download { progress ->
                    uiStateStore.update { state -> state.copy(
                        offlineTranslationDownloadBytes = progress.downloadedBytes,
                        offlineTranslationDownloadTotalBytes = progress.totalBytes,
                        offlineTranslationDownloadProgress =
                            (progress.downloadedBytes.toDouble() / progress.totalBytes.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f),
                        offlineTranslationStatus = progress.detail?.takeIf { it != "LICENSE" && it != "LICENSE.txt" && it != HyMt2PromptTranslationContract.MODEL_FILE }
                            ?: "正在下载并校验 Hy-MT2：${progress.downloadedBytes / 1_000_000} / ${progress.totalBytes / 1_000_000} MB"
                    ) }
                }
                cacheVerifiedOfflineTranslationBundle(bundle)
                uiStateStore.update { it.copy(
                    offlineTranslationInstalled = bundle.identity.translatorFamily == OfflineTranslatorFamily.HY_MT2,
                    offlineTranslationStatus = offlineTranslationBundleReadyStatus(bundle),
                    statusMessage = "Hy-MT2 已下载并安装。"
                ) }
            } catch (cancelled: CancellationException) {
                uiStateStore.update { it.copy(offlineTranslationStatus = "下载已暂停，可继续下载") }
                throw cancelled
            } catch (error: Exception) {
                uiStateStore.update { it.copy(
                    offlineTranslationStatus = "Hy-MT2 下载失败：${error.message ?: "请重试"}"
                ) }
            } finally {
                uiStateStore.update { it.copy(offlineTranslationDownloading = false) }
            }
        }
    }

    fun cancelOfflinePromptTranslationDownload() {
        offlineTranslationDownloadJob?.cancel()
    }

    fun cancelChatImageTranslationDraft(id: String) {
        if (pendingImageTranslation?.id != id) return
        invalidatePendingChatImagePreparation()
        showStatus("译文草稿已取消，原文已保留。")
    }


    /** Sends the composer contents straight to the selected image backend. */
    fun sendChatImagePrompt() {
        val state = _uiState.value
        // A second tap or a switch to an English prompt must cancel an older bridge job before
        // it can enqueue a stale image request after the newer request.
        invalidatePendingChatImagePreparation()
        if (rejectWhileConversationMutationInProgress()) return
        if (hasUnresolvedAssistantImageToolTurn(state.activeChatSessionId)) {
            showStatus("请先处理上一张角色请求的图片，再开始新的聊天生图。")
            return
        }
        if (state.isGenerating) {
            uiStateStore.update {
                it.copy(statusMessage = "当前对话仍在生成，结束或停止本轮后再发送生图请求。")
            }
            return
        }
        val preparedInput = state.prepareChatInput()
        if (preparedInput.imageAttachments.isNotEmpty() ||
            preparedInput.text.contains(FILE_ATTACHMENT_MARKER)
        ) {
            uiStateStore.update {
                it.copy(statusMessage = "聊天内生图暂不使用上传附件作为参考图。请移除附件后重试，或在图片页使用图生图。")
            }
            return
        }
        val prompt = preparedInput.text.trim()
        if (prompt.isBlank()) {
            showStatus("先写下图片描述，再点击生成图片。")
            return
        }
        val imagePrompt = parseExplicitChatImageIntent(prompt)?.sourceText ?: prompt
        val requestedOutputCount = classifyChatImageIntent(
            prompt,
            ChatImageIntentContext(imageSkillSelected = true)
        ).requestedOutputCount
        sendChatImageRequestWithPromptBridge(
            visibleUserText = preparedInput.text,
            prompt = imagePrompt,
            requestedOutputCount = requestedOutputCount
        )
    }

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
    private fun chatImageNegativePromptSupported(state: MainUiState): Boolean {
        if (state.selectedImageBackend != ImageBackend.LOCAL) return true
        val model = state.selectedLocalImageModel() ?: return false
        val bundleRoot = model.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
            ?: File(model.path).parentFile?.takeIf(File::isDirectory)
        return runCatching {
            val profile = resolveLocalImageExecutionProfile(
                model = model,
                options = LocalImageGenerationOptions()
                    .normalizedForPromptExecutionProfile(model.runtime),
                bundleRoot = bundleRoot
            ).profile
            profile.capabilities.supportsNegativePrompt && profile.defaults.useCfg
        }.getOrElse {
            // Imported/legacy records can be usable before their sidecar is materialized. Keep
            // the same conservative family gate used for default conditioning instead of
            // silently dropping the negative branch for a valid SD/SDXL model.
            chatImageFallbackNegativePromptForModel(model.family, model.runtime) != null
        }
    }

    internal fun chatImageToolPermissionUiState(): ChatImageToolPermissionUiState {
        val state = _uiState.value
        val cloudModelId = state.selectedCloudChatModelId
        val config = state.selectedChatCloudConfig()?.normalized()
        val imageModelBinding = state.chatImageModelBinding()
        val chatModelIdentity = chatImageToolChatModelIdentity(cloudModelId, config)
        val imageModelIdentity = imageModelBinding?.authorizationIdentity()
        val unavailableReason = when {
            state.selectedChatBackend != ChatBackend.CLOUD ->
                "角色自主生图目前仅支持 OpenAI Responses 云端聊天；本地模型可用输入框的聊天内生图。"
            cloudModelId.isNullOrBlank() || config == null || !config.configured ->
                "请先配置并选择一个云端聊天模型。"
            config.apiFormat != CloudApiFormat.OPENAI_RESPONSES ->
                "当前聊天协议不支持角色生图工具调用，请切换到 OpenAI Responses。"
            !config.supportsTools ->
                "当前 Responses 模型尚未启用工具调用，请到模型管理 > 云端编辑该模型并开启“支持工具调用”。"
            imageModelBinding == null ->
                "请先选择一个可用的本地或云端生图模型。"
            else -> ""
        }
        val sessionId = state.activeChatSessionId
        val authorizationMode = chatImageToolAuthorizationStore.getMode(
            sessionId,
            chatModelIdentity,
            imageModelIdentity
        )
        return ChatImageToolPermissionUiState(
            available = unavailableReason.isBlank(),
            unavailableReason = unavailableReason,
            autoApproval = unavailableReason.isBlank() &&
                authorizationMode == ChatImageToolAuthorizationMode.AUTO_APPROVE,
            canChange = unavailableReason.isBlank() && !sessionId.isNullOrBlank()
        )
    }

    fun setAssistantImageToolAutoApproval(enabled: Boolean) {
        val state = _uiState.value
        val permission = chatImageToolPermissionUiState()
        if (!permission.available) {
            showStatus(permission.unavailableReason)
            return
        }
        val sessionId = state.activeChatSessionId
        val chatModelId = state.selectedCloudChatModelId
        val chatModelIdentity = chatImageToolChatModelIdentity(
            chatModelId,
            state.selectedChatCloudConfig()?.normalized()
        )
        val imageModelIdentity = state.chatImageModelBinding()?.authorizationIdentity()
        if (sessionId.isNullOrBlank() || chatModelIdentity == null || imageModelIdentity == null) {
            uiStateStore.update {
                it.copy(statusMessage = "先发送一条消息创建对话，再设置本对话的角色生图授权。")
            }
            return
        }
        val target = if (enabled) {
            ChatImageToolAuthorizationMode.AUTO_APPROVE
        } else {
            ChatImageToolAuthorizationMode.ASK_EVERY_TIME
        }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = chatImageToolAuthorizationStore.setMode(
                sessionId,
                chatModelIdentity,
                imageModelIdentity,
                target
            )
            uiStateStore.update { current ->
                val currentChatIdentity = chatImageToolChatModelIdentity(
                    current.selectedCloudChatModelId,
                    current.selectedChatCloudConfig()?.normalized()
                )
                val currentImageIdentity = current.chatImageModelBinding()?.authorizationIdentity()
                if (current.activeChatSessionId != sessionId ||
                    currentChatIdentity != chatModelIdentity ||
                    currentImageIdentity != imageModelIdentity
                ) {
                    current
                } else {
                    current.copy(
                        statusMessage = if (saved) {
                            if (target == ChatImageToolAuthorizationMode.AUTO_APPROVE) {
                                "已允许当前对话中的 Responses 角色请求自动生图；可随时改回每次询问。"
                            } else {
                                "已改为每次询问；之后的角色生图请求会先等待你确认。"
                            }
                        } else {
                            "授权设置未能保存，请稍后重试。"
                        }
                    )
                }
            }
        }
    }

    /**
     * The ViewModel side of [SendChatImageRequestPort]: one route per member, no logic of its own.
     * The decision body moved to [SendChatImageRequestOperations.sendChatImageRequest], which is now
     * the only place the send path is decided.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name and signature with the ViewModel member they forward to, and an unqualified call
     * would recurse into the override instead of reaching the original implementation.
     */
    private fun sendChatImageRequest(
        visibleUserText: String,
        prompt: String,
        optionsOverride: LocalImageGenerationOptions? = null,
        promptEnvelope: PromptEnvelope? = null,
        contextTraceJson: String? = null
    ) {
        val port = object : SendChatImageRequestPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun showStatus(message: String) {
                this@MainViewModel.showStatus(message)
            }

            override fun rejectWhileConversationMutationInProgress(): Boolean =
                this@MainViewModel.rejectWhileConversationMutationInProgress()

            override fun chatImageSubmissionBlockMessage(state: MainUiState): String? =
                this@MainViewModel.chatImageSubmissionBlockMessage(state)

            override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
                with(this@MainViewModel) { state.canWriteToActiveAssistantSession() }

            override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot? =
                with(this@MainViewModel) { state.activeAssistantSnapshot() }

            override fun selectedAssistant(state: MainUiState): AssistantRecord? =
                with(this@MainViewModel) { state.selectedAssistant() }

            override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord? =
                with(this@MainViewModel) { state.selectedLocalImageModel() }

            override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig? =
                with(this@MainViewModel) { state.selectedImageCloudConfig() }

            override fun currentChatModelId(state: MainUiState): String? =
                with(this@MainViewModel) { state.currentChatModelId() }

            override fun chatImageOptionsForCurrentSelection(
                state: MainUiState
            ): LocalImageGenerationOptions =
                this@MainViewModel.chatImageOptionsForCurrentSelection(state)

            override fun upsertSession(
                sessions: List<ChatSessionRecord>,
                sessionId: String,
                messages: List<ChatMessage>,
                assistantId: String?,
                assistantSnapshot: AssistantConversationSnapshot?,
                modelMode: String?,
                modelId: String?
            ): List<ChatSessionRecord> = with(this@MainViewModel) {
                sessions.upsertSession(
                    sessionId = sessionId,
                    messages = messages,
                    assistantId = assistantId,
                    assistantSnapshot = assistantSnapshot,
                    modelMode = modelMode,
                    modelId = modelId
                )
            }

            override fun persistChatSessions(
                sessions: List<ChatSessionRecord>?,
                knowledgeBinding: Pair<String, Set<String>>?
            ) {
                this@MainViewModel.persistChatSessions(
                    sessions = sessions,
                    knowledgeBinding = knowledgeBinding
                )
            }

            override fun enqueueImageGeneration(
                prompt: String,
                inputDraft: LocalImageInputDraft,
                options: LocalImageGenerationOptions,
                jobSnapshot: ImageGenerationJobSpec?,
                requestedJobId: String?,
                requestedChatSessionId: String?,
                requestedChatMessageId: String?
            ): Boolean = this@MainViewModel.enqueueImageGeneration(
                prompt = prompt,
                inputDraft = inputDraft,
                options = options,
                jobSnapshot = jobSnapshot,
                requestedJobId = requestedJobId,
                requestedChatSessionId = requestedChatSessionId,
                requestedChatMessageId = requestedChatMessageId
            )

            override fun updateChatGeneratedImageRequest(
                chatSessionId: String,
                requestId: String,
                request: ChatGeneratedImageRequest
            ) {
                applyChatGeneratedImageRequest(chatImageRequestPort, chatSessionId, requestId, request)
            }

            override fun updateChatImageRequestFromJob(
                chatSessionId: String,
                requestId: String,
                jobId: String
            ) {
                applyChatImageRequestFromJob(chatImageRequestPort, chatSessionId, requestId, jobId)
            }
        }
        SendChatImageRequestOperations.sendChatImageRequest(
            port = port,
            visibleUserText = visibleUserText,
            prompt = prompt,
            optionsOverride = optionsOverride,
            promptEnvelope = promptEnvelope,
            contextTraceJson = contextTraceJson
        )
    }

    private fun chatImageSubmissionBlockMessage(state: MainUiState): String? {
        val modelMessage = when (state.selectedImageBackend) {
            ImageBackend.LOCAL -> {
                val model = state.selectedLocalImageModel()
                    ?: return "尚未选择可用的本地图像模型，请前往模型管理选择并配置一个模型。"
                model.localImageStructuralReadinessMessage()?.let { "本地图像模型暂不可用：$it" }
            }
            ImageBackend.CLOUD -> {
                val config = state.selectedImageCloudConfig()
                    ?: return "尚未选择云端生图模型，请先在模型管理中添加并配置一个模型。"
                if (!config.imageConfigured) {
                    "当前云端生图模型尚未配置完整，请检查接口地址、模型名称和认证信息。"
                } else null
            }
        }
        if (modelMessage != null) return modelMessage
        if (imageGenerationJob?.isActive == true || activeImageGenerationJobId != null ||
            activeLocalApiImageModelId != null || localImageGenerationCoordinator.activeRequestId() != null
        ) {
            return "已有图片任务正在运行，请等它完成或取消后再发送新的生图请求。"
        }
        return null
    }

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
        // The decision moved to AssistantImageToolTurnOperations.kt. Every override below keeps the
        // `this@MainViewModel.` prefix on purpose: each port method shares its name and signature
        // with the ViewModel member it forwards to, and an unqualified call would recurse into the
        // override instead of reaching the original implementation.
        val port = object : AssistantImageToolTurnPort {
            override fun activeChatSessionId(): String? = _uiState.value.activeChatSessionId

            override fun chatImageNegativePromptSupported(): Boolean =
                this@MainViewModel.chatImageNegativePromptSupported(_uiState.value)

            override suspend fun prepareAssistantImagePrompt(
                prompt: String,
                chatConfig: CloudApiConfig?,
                params: GenerationParams,
                conversationContext: List<ChatMessage>
            ): ChatImagePromptBridge.Result = this@MainViewModel.prepareAssistantImagePrompt(
                prompt = prompt,
                chatConfig = chatConfig,
                params = params,
                conversationContext = conversationContext
            )

            override suspend fun prepareAssistantImageNegativePrompt(
                prompt: String,
                chatConfig: CloudApiConfig?,
                params: GenerationParams,
                conversationContext: List<ChatMessage>,
                preserveAuthoredNegative: Boolean
            ): ChatImagePromptBridge.Result = this@MainViewModel.prepareAssistantImageNegativePrompt(
                prompt = prompt,
                chatConfig = chatConfig,
                params = params,
                conversationContext = conversationContext,
                preserveAuthoredNegative = preserveAuthoredNegative
            )

            override fun setPendingAssistantToolFailure(message: String) {
                this@MainViewModel.setPendingAssistantToolFailure(message)
            }

            override fun buildChatImageRetrySnapshot(
                request: ChatGeneratedImageRequest,
                chatSessionId: String
            ): ImageGenerationJobSpec? =
                this@MainViewModel.buildChatImageRetrySnapshot(request, chatSessionId)

            override fun registerPendingAssistantImageToolTurn(turn: AssistantImageToolPendingTurn) {
                pendingAssistantImageToolTurns[turn.requestId] = PendingAssistantImageToolTurn(
                    requestId = turn.requestId,
                    chatSessionId = turn.chatSessionId,
                    originalRequestMessages = turn.originalRequestMessages,
                    params = turn.params,
                    cloudModelId = turn.cloudModelId,
                    toolCalls = turn.toolCalls,
                    primaryToolCallId = turn.primaryToolCallId,
                    initialToolOutputs = turn.initialToolOutputs
                )
            }

            override fun attachAssistantImageRequestToTimeline(
                chatSessionId: String,
                request: ChatGeneratedImageRequest
            ) {
                this@MainViewModel.attachAssistantImageRequestToTimeline(chatSessionId, request)
            }

            /** One `_uiState.value` read, like the original `val currentState = _uiState.value`. */
            override fun currentTurnSelectionSnapshot(): AssistantImageToolTurnSelection {
                val state = _uiState.value
                return AssistantImageToolTurnSelection(
                    activeChatSessionId = state.activeChatSessionId,
                    selectedCloudChatModelId = state.selectedCloudChatModelId,
                    chatModelIdentity = chatImageToolChatModelIdentity(
                        state.selectedCloudChatModelId,
                        state.selectedChatCloudConfig()?.normalized()
                    ),
                    imageAuthorizationIdentity = state.chatImageModelBinding()?.authorizationIdentity()
                )
            }

            override fun chatImageToolAuthorizationMode(
                chatSessionId: String?,
                chatModelIdentity: String?,
                imageModelIdentity: String?
            ): ChatImageToolAuthorizationMode = chatImageToolAuthorizationStore.getMode(
                chatSessionId,
                chatModelIdentity,
                imageModelIdentity
            )

            override fun approveChatImageRequest(requestId: String) {
                this@MainViewModel.approveChatImageRequest(requestId)
            }
        }
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
    ): ChatImagePromptBridge.Result {
        val source = prompt.trim()
        if (source.isBlank()) {
            return ChatImagePromptBridge.Result.Failed(
                ChatImagePromptBridge.Code.INVALID_INPUT,
                "负面图片描述为空。"
            )
        }
        val neutralized = prepareAssistantImagePrompt(
            prompt = "neutral image\nNegative prompt: $source",
            chatConfig = chatConfig,
            params = params,
            conversationContext = conversationContext
        )
        return when (neutralized) {
            is ChatImagePromptBridge.Result.Prepared -> {
                val translatedNegative = neutralized.effectiveNegativePrompt
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
                when {
                    translatedNegative != null -> neutralized.copy(
                        originalPrompt = source,
                        effectiveNegativePrompt = translatedNegative
                    )
                    preserveAuthoredNegative && !source.containsHanScript() &&
                        source.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
                        source.isSafeAsciiDiffusionPrompt() -> neutralized.copy(
                        originalPrompt = source,
                        effectiveNegativePrompt = source
                    )
                    preserveAuthoredNegative -> neutralized.copy(originalPrompt = source)
                    else -> neutralized.copy(
                        originalPrompt = source,
                        effectiveNegativePrompt = ""
                    )
                }
            }
            is ChatImagePromptBridge.Result.Failed -> {
                // ASCII exclusions are already safe to send if the model is unavailable. A
                // Chinese authored branch must never leak through unchanged; model defaults can
                // be cleared instead of blocking an otherwise valid image request.
                if (!source.containsHanScript() &&
                    source.length <= LocalImagePromptExecution.MAX_EFFECTIVE_PROMPT_CHARS &&
                    source.isSafeAsciiDiffusionPrompt()
                ) {
                    ChatImagePromptBridge.Result.Prepared(
                        originalPrompt = source,
                        effectivePrompt = "neutral image",
                        translated = false,
                        effectiveNegativePrompt = source
                    )
                } else if (!preserveAuthoredNegative && source.containsHanScript()) {
                    ChatImagePromptBridge.Result.Prepared(
                        originalPrompt = source,
                        effectivePrompt = "neutral image",
                        translated = false,
                        effectiveNegativePrompt = ""
                    )
                } else {
                    neutralized
                }
            }
        }
    }

    /** Translate an assistant tool prompt with the exact chat connector that produced the call. */
    private suspend fun prepareAssistantImagePrompt(
        prompt: String,
        chatConfig: CloudApiConfig?,
        params: GenerationParams,
        conversationContext: List<ChatMessage> = _uiState.value.messages
    ): ChatImagePromptBridge.Result {
        val config = chatConfig?.takeIf { it.configured }
            ?: return ChatImagePromptBridge.Result.Failed(
                ChatImagePromptBridge.Code.MODEL_ERROR,
                "当前聊天模型配置不可用。请恢复聊天模型后重试。"
            )
        val state = _uiState.value
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val assistantRecord = assistantSnapshot?.assistantId
            ?.let { assistantId -> state.assistants.firstOrNull { it.id == assistantId } }
            ?: state.selectedAssistant()
        val assistantName = assistantSnapshot?.name ?: assistantRecord?.name
        val characterContext = assistantSnapshot?.systemPrompt ?: assistantRecord?.systemPrompt
        val runtimeContext = runCatching {
            withContext(Dispatchers.IO) {
                chatContextComposer.compose(
                    // The tool payload itself is the current query; include it so selected
                    // knowledge chunks are retrieved for this image request rather than for an
                    // older chat turn.
                    messages = conversationContext + ChatMessage(Role.USER, prompt),
                    params = params,
                    assistantId = assistantSnapshot?.assistantId
                        ?: state.selectedAssistantId.orEmpty(),
                    chatSessionId = state.activeChatSessionId,
                    knowledgeBaseIds = state.selectedKnowledgeBaseIds,
                    fileContextEnabled = assistantSnapshot?.fileContextEnabled
                        ?: assistantRecord?.fileContextEnabled
                        ?: true
                ).runtimeSystemContext
            }
        }.getOrNull()
        val first = ChatImagePromptBridge.translateChatImagePrompt(
            prompt = prompt,
            stream = cloudChatProvider.streamChat(
                config = config,
                request = ChatImagePromptBridge.chatImagePromptBridgeRequest(
                    prompt = prompt,
                    params = params,
                    assistantName = assistantName,
                    characterContext = characterContext,
                    conversationContext = conversationContext,
                    runtimeContext = runtimeContext,
                    protectNumericLiterals = false
                )
            ),
            // A Responses tool call is also an image-skill invocation. The tool payload may be
            // English, JSON, Markdown, or a compact preset; always let the selected chat model
            // normalize it before the image encoder sees it.
            requireModelSummary = true
        )
        if (first is ChatImagePromptBridge.Result.Prepared) return first
        // Responses providers occasionally wrap the result in prose even when instructed not to.
        // A single sequential fallback keeps assistant-tool image requests from sending known
        // invalid Chinese text into an English-only image encoder.
        return ChatImagePromptBridge.translateChatImagePrompt(
            prompt = prompt,
            stream = cloudChatProvider.streamChat(
                config = config,
                request = ChatImagePromptBridge.chatImagePromptBridgeRequest(
                    prompt,
                    params,
                    systemPrompt = ChatImagePromptBridge.FALLBACK_SYSTEM_PROMPT,
                    assistantName = assistantName,
                    characterContext = characterContext,
                    conversationContext = conversationContext,
                    runtimeContext = runtimeContext,
                    protectNumericLiterals = false
                )
            ),
            requireModelSummary = true
        )
    }

    private fun setPendingAssistantToolFailure(message: String) {
        val state = _uiState.value
        val messages = state.messages.toMutableList()
        val pendingIndex = messages.indexOfLast {
            it.role == Role.ASSISTANT && it.content.isBlank() && it.generatedImageRequest == null
        }
        val failedText = "图片请求未执行：$message"
        if (pendingIndex >= 0) {
            messages[pendingIndex] = messages[pendingIndex].copy(content = failedText)
        } else {
            messages += ChatMessage(Role.ASSISTANT, failedText)
        }
        val sessionId = state.activeChatSessionId ?: return
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val sessions = state.chatSessions.upsertSession(
            sessionId = sessionId,
            messages = messages,
            assistantId = assistantSnapshot?.assistantId ?: state.selectedAssistantId,
            assistantSnapshot = assistantSnapshot,
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        uiStateStore.update {
            it.copy(messages = messages, chatSessions = sessions, statusMessage = message)
        }
        persistChatSessions(sessions)
    }

    private fun attachAssistantImageRequestToTimeline(
        chatSessionId: String,
        request: ChatGeneratedImageRequest
    ) {
        val state = _uiState.value
        val messages = state.messages.toMutableList()
        val pendingIndex = messages.indexOfLast {
            it.role == Role.ASSISTANT && it.content.isBlank() && it.generatedImageRequest == null
        }
        val card = ChatMessage(
            role = Role.ASSISTANT,
            content = request.assistantTranscriptText(),
            generatedImageRequest = request
        )
        if (pendingIndex >= 0) messages[pendingIndex] = card else messages += card
        val assistantSnapshot = state.activeAssistantSnapshot()
            ?: state.selectedAssistant()?.toConversationSnapshot()
        val sessions = state.chatSessions.upsertSession(
            sessionId = chatSessionId,
            messages = messages,
            assistantId = assistantSnapshot?.assistantId ?: state.selectedAssistantId,
            assistantSnapshot = assistantSnapshot,
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        uiStateStore.update {
            it.copy(messages = messages, chatSessions = sessions, statusMessage = null)
        }
        persistChatSessions(sessions)
    }

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

    private fun completeAssistantImageToolTurnForJob(jobId: String) {
        val job = _uiState.value.imageJobs.firstOrNull { it.id == jobId } ?: return
        val requestId = job.spec?.chatMessageId ?: return
        val owner = findChatGeneratedImageRequest(requestId) ?: return
        val (sessionId, request) = owner
        if (request.origin != ChatGeneratedImageOrigin.ASSISTANT_TOOL) return
        val output = when (request.status) {
            ChatGeneratedImageStatus.DONE -> toolResultJson("completed", "Image generated successfully.")
            ChatGeneratedImageStatus.CANCELLED -> toolResultJson("cancelled", "Image generation was cancelled.")
            ChatGeneratedImageStatus.FAILED -> toolResultJson("failed", "Image generation failed.")
            ChatGeneratedImageStatus.INTERRUPTED -> toolResultJson("interrupted", "Image generation was interrupted.")
            ChatGeneratedImageStatus.AWAITING_APPROVAL,
            ChatGeneratedImageStatus.QUEUED,
            ChatGeneratedImageStatus.GENERATING -> return
        }
        applyChatGeneratedImageRequest(chatImageRequestPort, sessionId, requestId, request.copy(toolOutputJson = output))
        continueAssistantImageToolTurn(requestId, output)
    }

    /**
     * The ViewModel side of [ChatImageRequestApprovalPort]: one route per member, no logic of its
     * own. The three decisions moved to [ChatImageRequestApprovalOperations], which is now the only
     * place an approval, a rejection or a retry is decided.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name with the ViewModel member they forward to, and an unqualified call would recurse.
     */
    private val chatImageRequestApprovalPort: ChatImageRequestApprovalPort =
        object : ChatImageRequestApprovalPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val imageGenerationJob: Job?
                get() = this@MainViewModel.imageGenerationJob

            override fun showStatus(message: String?) {
                this@MainViewModel.showStatus(message)
            }

            override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
                with(this@MainViewModel) { state.canWriteToActiveAssistantSession() }

            override fun findChatGeneratedImageRequest(
                requestId: String
            ): Pair<String, ChatGeneratedImageRequest>? =
                this@MainViewModel.findChatGeneratedImageRequest(requestId)

            override fun buildChatImageRetrySnapshot(
                request: ChatGeneratedImageRequest,
                chatSessionId: String
            ): ImageGenerationJobSpec? =
                this@MainViewModel.buildChatImageRetrySnapshot(request, chatSessionId)

            override fun enqueueImageGeneration(
                prompt: String,
                inputDraft: LocalImageInputDraft,
                options: LocalImageGenerationOptions,
                jobSnapshot: ImageGenerationJobSpec?,
                requestedJobId: String?,
                requestedChatSessionId: String?,
                requestedChatMessageId: String?
            ): Boolean = this@MainViewModel.enqueueImageGeneration(
                prompt = prompt,
                inputDraft = inputDraft,
                options = options,
                jobSnapshot = jobSnapshot,
                requestedJobId = requestedJobId,
                requestedChatSessionId = requestedChatSessionId,
                requestedChatMessageId = requestedChatMessageId
            )

            override fun updateChatGeneratedImageRequest(
                chatSessionId: String,
                requestId: String,
                request: ChatGeneratedImageRequest
            ) {
                applyChatGeneratedImageRequest(chatImageRequestPort, chatSessionId, requestId, request)
            }

            override fun updateChatImageRequestFromJob(
                chatSessionId: String,
                requestId: String,
                jobId: String
            ) {
                applyChatImageRequestFromJob(chatImageRequestPort, chatSessionId, requestId, jobId)
            }

            override fun continueAssistantImageToolTurn(requestId: String, outputJson: String) {
                this@MainViewModel.continueAssistantImageToolTurn(requestId, outputJson)
            }
        }

    fun retryChatImageGeneration(requestId: String) {
        retryChatImageGeneration(port = chatImageRequestApprovalPort, requestId = requestId)
    }

    fun approveChatImageRequest(requestId: String) {
        approveChatImageRequest(port = chatImageRequestApprovalPort, requestId = requestId)
    }

    fun rejectChatImageRequest(requestId: String) {
        rejectChatImageRequest(port = chatImageRequestApprovalPort, requestId = requestId)
    }

    /** Resumes a terminal assistant tool turn if the process stopped before the follow-up reply. */
    fun continueAssistantImageTurn(requestId: String) {
        if (!_uiState.value.canWriteToActiveAssistantSession()) return
        val owner = findChatGeneratedImageRequest(requestId) ?: return
        val (_, request) = owner
        if (!request.canContinueAssistantImageTurn()) return
        val status = when (request.status) {
            ChatGeneratedImageStatus.DONE -> "completed"
            ChatGeneratedImageStatus.FAILED -> "failed"
            ChatGeneratedImageStatus.CANCELLED -> "cancelled"
            ChatGeneratedImageStatus.INTERRUPTED -> "interrupted"
            ChatGeneratedImageStatus.AWAITING_APPROVAL,
            ChatGeneratedImageStatus.QUEUED,
            ChatGeneratedImageStatus.GENERATING -> return
        }
        val output = request.toolOutputJson ?: toolResultJson(
            status,
            when (status) {
                "completed" -> "Image generated successfully."
                "cancelled" -> "Image generation was cancelled."
                "interrupted" -> "Image generation was interrupted."
                else -> "Image generation failed."
            }
        )
        continueAssistantImageToolTurn(requestId, output)
    }

    fun cancelChatImageGeneration(requestId: String) {
        val owner = findChatGeneratedImageRequest(requestId) ?: return
        val (chatSessionId, request) = owner
        val state = _uiState.value
        if (state.activeChatSessionId != chatSessionId || !state.canWriteToActiveAssistantSession()) return
        if (request.status == ChatGeneratedImageStatus.AWAITING_APPROVAL) {
            rejectChatImageRequest(requestId)
            return
        }
        val jobId = request.currentJobId
        if (jobId.isNullOrBlank()) {
            showStatus("找不到这条图片任务的执行编号，请刷新聊天后重试。")
            return
        }
        cancelImageGenerationForJob(jobId)
    }

    private fun findChatGeneratedImageRequest(
        requestId: String
    ): Pair<String, ChatGeneratedImageRequest>? {
        val state = _uiState.value
        return state.chatSessions.firstNotNullOfOrNull { session ->
            session.messages.firstOrNull { it.generatedImageRequest?.id == requestId }
                ?.generatedImageRequest
                ?.let { session.id to it }
        } ?: state.messages.firstOrNull { it.generatedImageRequest?.id == requestId }
            ?.generatedImageRequest
            ?.let { request -> state.activeChatSessionId?.let { it to request } }
    }

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

    private fun hasActiveAssistantImageExecution(chatSessionId: String?): Boolean {
        if (chatSessionId.isNullOrBlank()) return false
        val state = _uiState.value
        val session = state.chatSessions.firstOrNull { it.id == chatSessionId }
        val messages = if (state.activeChatSessionId == chatSessionId) state.messages else session?.messages.orEmpty()
        return messages.any { message ->
            message.generatedImageRequest?.let { request ->
                request.origin == ChatGeneratedImageOrigin.ASSISTANT_TOOL &&
                    (request.status in setOf(
                        ChatGeneratedImageStatus.AWAITING_APPROVAL,
                        ChatGeneratedImageStatus.QUEUED,
                        ChatGeneratedImageStatus.GENERATING
                    ) || request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING)
            } == true
        }
    }

    private fun isChatImageRequestActive(request: ChatGeneratedImageRequest): Boolean =
        request.status in setOf(
            ChatGeneratedImageStatus.AWAITING_APPROVAL,
            ChatGeneratedImageStatus.QUEUED,
            ChatGeneratedImageStatus.GENERATING
        ) || request.toolContinuationStatus == ChatImageToolContinuationStatus.RUNNING


    /**
     * The ViewModel side of [ChatImageRequestPort].
     */
    private val chatImageRequestPort: ChatImageRequestPort =
        object : ChatImageRequestPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun persistChatSessions(
                sessions: List<ChatSessionRecord>?,
                knowledgeBinding: Pair<String, Set<String>>?
            ) {
                this@MainViewModel.persistChatSessions(
                    sessions = sessions,
                    knowledgeBinding = knowledgeBinding
                )
            }
        }







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
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        if (!state.canWriteToActiveAssistantSession()) return
        if (state.isGenerating) {
            showStatus("请先停止当前生成，再删除消息")
            return
        }
        if (index !in state.messages.indices) return
        val removedRequest = state.messages[index].generatedImageRequest
        if (removedRequest?.let(::isChatImageRequestActive) == true) {
            showStatus("这条消息仍关联运行中的图片任务，请等任务结束后再删除。")
            return
        }
        val rollback = state.conversationMutationRollbackState()
        val updatedMessages = state.messages.filterIndexed { messageIndex, _ -> messageIndex != index }
        val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
        var updatedSessions: List<ChatSessionRecord> = state.chatSessions
        uiStateStore.update {
            updatedSessions = if (updatedMessages.isEmpty()) {
                it.chatSessions.map { session ->
                    if (session.id == sessionId) session.copy(
                        messages = emptyList(), contextSummaries = emptyList(),
                        updatedAt = System.currentTimeMillis()
                    ) else session
                }
            } else {
                it.chatSessions.upsertSession(
                    sessionId = sessionId,
                    messages = updatedMessages,
                    assistantId = it.selectedAssistantId,
                    modelMode = it.selectedChatBackend.bindingValue(),
                    modelId = it.currentChatModelId()
                )
            }
            it.copy(
                messages = updatedMessages,
                activeChatSessionId = sessionId,
                chatSessions = updatedSessions,
                promptContextUsage = null,
                statusMessage = "已删除消息"
            )
        }
        removedRequest?.let { pendingAssistantImageToolTurns.remove(it.id) }
        persistConversationMutation(updatedSessions, rollback)
    }

    fun deleteLastConversationTurn() {
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        if (!state.canWriteToActiveAssistantSession()) return
        if (state.isGenerating) {
            showStatus("\u8bf7\u5148\u505c\u6b62\u5f53\u524d\u751f\u6210\uff0c\u518d\u5220\u9664\u672c\u8f6e\u5bf9\u8bdd")
            return
        }
        val prune = state.messages.pruneLastConversationTurn()
        if (prune == null) {
            showStatus("\u6ca1\u6709\u53ef\u5220\u9664\u7684\u672c\u8f6e\u5bf9\u8bdd")
            return
        }
        val lastUserIndex = state.messages.indexOfLast { it.role == Role.USER }
        val lastAssistantIndex = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        val removedEndIndex = if (lastAssistantIndex > lastUserIndex) lastAssistantIndex else lastUserIndex
        val removedRequests = if (lastUserIndex >= 0 && removedEndIndex >= lastUserIndex) {
            state.messages.subList(lastUserIndex, removedEndIndex + 1)
                .mapNotNull { it.generatedImageRequest }
        } else emptyList()
        if (removedRequests.any(::isChatImageRequestActive)) {
            showStatus("本轮仍关联运行中的图片任务，请等任务结束后再删除。")
            return
        }
        val rollback = state.conversationMutationRollbackState()
        val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
        var updatedSessions: List<ChatSessionRecord> = state.chatSessions
        uiStateStore.update {
            updatedSessions = if (prune.messages.isEmpty()) {
                it.chatSessions.map { session ->
                    if (session.id == sessionId) session.copy(
                        messages = emptyList(), contextSummaries = emptyList(),
                        updatedAt = System.currentTimeMillis()
                    ) else session
                }
            } else {
                it.chatSessions.upsertSession(
                    sessionId = sessionId,
                    messages = prune.messages,
                    assistantId = it.selectedAssistantId,
                    modelMode = it.selectedChatBackend.bindingValue(),
                    modelId = it.currentChatModelId()
                )
            }
            it.copy(
                messages = prune.messages,
                activeChatSessionId = sessionId,
                chatSessions = updatedSessions,
                promptContextUsage = null,
                statusMessage = "\u5df2\u5220\u9664\u6700\u540e\u4e00\u8f6e\u5bf9\u8bdd"
            )
        }
        removedRequests.forEach { pendingAssistantImageToolTurns.remove(it.id) }
        persistConversationMutation(
            sessions = updatedSessions,
            rollback = rollback,
            preserveReusableNativePrefix = true
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
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return false
        if (hasActiveAssistantImageExecution(state.activeChatSessionId)) {
            showStatus("角色图片请求尚未完成，请先确认、取消或等待任务结束。")
            return false
        }
        if (state.isGenerating) {
            showStatus("请先停止当前生成，再新建对话")
            return false
        }
        invalidatePendingChatImagePreparation()
        activeWebSearchDeferred?.cancel()
        contentImportSelectionRevision.incrementAndGet()
        val defaultModelMode = assistant
            ?.takeIf { bindDefaultModel && !it.defaultModelId.isNullOrBlank() }
            ?.defaultModelMode
            ?.normalizedAssistantModelMode()
        val targetAssistant = assistant ?: state.selectedAssistant() ?: return false
        val modelMode = defaultModelMode?.takeUnless { it == ASSISTANT_MODEL_MODE_FOLLOW_CURRENT }
            ?: state.selectedChatBackend.bindingValue()
        val modelId = if (defaultModelMode != null && defaultModelMode != ASSISTANT_MODEL_MODE_FOLLOW_CURRENT) {
            targetAssistant.defaultModelId
        } else {
            state.currentChatModelId()
        }
        val greetingSession = targetAssistant.newConversationSession(
            modelMode = modelMode,
            modelId = modelId
        )
        var sessionsToPersist: List<ChatSessionRecord>? = null
        uiStateStore.update {
            sessionsToPersist = (listOf(greetingSession) + it.chatSessions).sortedForHistory()
            it.copy(
                messages = greetingSession.messages,
                input = "",
                activeChatSessionId = greetingSession.id,
                selectedAssistantId = targetAssistant.id,
                chatSessions = sessionsToPersist ?: it.chatSessions,
                browserTask = it.browserTask?.let { task ->
                    com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation owner changed")
                },
                contextCompressionPending = false,
                selectedKnowledgeBaseIds = emptySet(),
                promptContextUsage = null,
                statusMessage = "已新建对话"
            )
        }
        sessionsToPersist?.let { persistChatSessions(it) }
        markLocalConversationContextInvalid()
        return true
    }

    /**
     * The ViewModel side of [SelectChatSessionPort]: one route per member, no logic of its own. The
     * decision moved to [selectChatSession], which is now the only place a conversation switch is
     * decided.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name and signature with the ViewModel member they forward to, and an unqualified call
     * would recurse into the override instead of reaching the original implementation.
     */
    fun selectChatSession(sessionId: String) {
        selectChatSession(port = selectChatSessionPort, sessionId = sessionId)
    }

    private val selectChatSessionPort: SelectChatSessionPort =
        object : SelectChatSessionPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun rejectWhileConversationMutationInProgress(): Boolean =
                this@MainViewModel.rejectWhileConversationMutationInProgress()

            override fun hasActiveAssistantImageExecution(sessionId: String?): Boolean =
                this@MainViewModel.hasActiveAssistantImageExecution(sessionId)

            override fun showStatus(message: String) {
                this@MainViewModel.showStatus(message)
            }

            override fun invalidatePendingChatImagePreparation() {
                this@MainViewModel.invalidatePendingChatImagePreparation()
            }

            override fun cancelActiveWebSearch() {
                this@MainViewModel.activeWebSearchDeferred?.cancel()
            }

            override fun bumpContentImportSelectionRevision() {
                this@MainViewModel.contentImportSelectionRevision.incrementAndGet()
            }

            override fun selectedKnowledgeBaseIdsFor(sessionId: String): Set<String> =
                this@MainViewModel.knowledgeBaseStore.selectedKnowledgeBaseIds(sessionId)

            override fun saveSelectedAssistantId(assistantId: String) {
                this@MainViewModel.assistantStore.saveSelectedAssistantId(assistantId)
            }

            override fun loadModelGenerationParams(
                backend: ChatBackend,
                modelId: String?,
                defaults: GenerationParams
            ): GenerationParams = this@MainViewModel.loadModelGenerationParams(
                backend = backend,
                modelId = modelId,
                defaults = defaults
            )

            override fun persistGenerationParams(params: GenerationParams) {
                GenerationPreferences.persistGenerationParams(getApplication(), params)
            }

            override fun saveSelectedBackend(backend: ChatBackend) {
                this@MainViewModel.cloudApiStore.saveSelectedBackend(backend)
            }

            override fun saveSelectedCloudChatModelId(modelId: String) {
                this@MainViewModel.cloudApiStore.saveSelectedCloudChatModelId(modelId)
            }

            override fun normalizedCloudChatConfig(config: CloudApiConfig): CloudApiConfig =
                config.normalized()

            override fun saveCloudChatConfig(config: CloudApiConfig) {
                // `normalized()` is a private extension of this class, so the normalisation stays
                // here rather than moving into the operations file.
                this@MainViewModel.cloudApiStore.save(config.normalized())
            }

            override fun toGenerationParams(
                assistant: AssistantRecord,
                defaults: GenerationParams
            ): GenerationParams = assistant.toGenerationParams(defaults)

            override fun isContextCompressionPending(sessionId: String): Boolean =
                this@MainViewModel.manualContextCompressionRequests.isPending(sessionId)

            override fun markLocalConversationContextInvalid() {
                this@MainViewModel.markLocalConversationContextInvalid()
            }

            override fun continueAssistantImageTurn(requestId: String) {
                this@MainViewModel.continueAssistantImageTurn(requestId)
            }
        }

    fun branchMixedChatSession(sessionId: String, assistantId: String) {
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress() || state.isGenerating) return
        val source = state.chatSessions.firstOrNull { it.id == sessionId && it.mixedAssistantHistory } ?: return
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        if (hasActiveAssistantImageExecution(state.activeChatSessionId)) return
        val now = System.currentTimeMillis()
        // The old transcript stays intact. An uncertain role history cannot be copied into
        // another card's prompt, summaries, or automatic long-term memory.
        val branch = ChatSessionRecord(
            id = UUID.randomUUID().toString(),
            title = "${source.title.take(48)} · 分支",
            messages = emptyList(),
            manualTitle = true,
            updatedAt = now,
            assistantId = assistant.id,
            assistantSnapshot = assistant.toConversationSnapshot(now),
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        val sessions = (listOf(branch) + state.chatSessions).sortedForHistory()
        uiStateStore.update { current -> current.copy(chatSessions = sessions) }
        persistChatSessions(sessions)
        selectChatSession(branch.id)
    }

    fun deleteChatSession(sessionId: String) {
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        if (state.isGenerating) {
            showStatus("请先停止当前生成，再删除记录")
            return
        }
        val session = state.chatSessions.firstOrNull { it.id == sessionId } ?: return
        if (hasActiveAssistantImageExecution(sessionId) || state.imageJobs.any { job ->
                job.spec?.chatSessionId == sessionId && !job.status.terminal
            }
        ) {
            showStatus("该对话仍有图片任务正在执行，请等任务结束后再删除。")
            return
        }
        val rollback = state.conversationMutationRollbackState()
        val isActive = state.activeChatSessionId == sessionId
        val replacement = if (isActive) state.selectedAssistant()?.newConversationSession(
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        ) else null
        val remaining = (state.chatSessions.filterNot { it.id == sessionId } + listOfNotNull(replacement))
            .sortedForHistory()
        manualContextCompressionRequests.clear(sessionId)
        uiStateStore.update {
            it.copy(
                chatSessions = remaining,
                messages = if (isActive) replacement?.messages.orEmpty() else it.messages,
                input = if (isActive) "" else it.input,
                activeChatSessionId = if (isActive) replacement?.id else it.activeChatSessionId,
                browserTask = it.browserTask?.let { task ->
                    if (task.sessionId == sessionId) {
                        com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation deleted")
                    } else task
                },
                selectedKnowledgeBaseIds = if (isActive) emptySet() else it.selectedKnowledgeBaseIds,
                promptContextUsage = if (isActive) null else it.promptContextUsage,
                contextCompressionPending = if (isActive) false else it.contextCompressionPending,
                statusMessage = "已删除对话记录"
            )
        }
        pendingAssistantImageToolTurns.entries.removeIf { entry ->
            entry.value.chatSessionId == sessionId
        }
        queueWorldBookCleanup(WorldBookScope.CHAT, setOf(sessionId))
        persistConversationMutation(remaining, rollback)
        viewModelScope.launch(Dispatchers.IO) {
            chatImageToolAuthorizationStore.remove(sessionId)
        }
        viewModelScope.launch { chatAppearanceMutex.withLock { cleanupUnusedChatBackgrounds() } }
    }

    fun renameChatSession(sessionId: String, title: String) {
        if (rejectWhileConversationMutationInProgress()) return
        val cleanTitle = title.trim().take(48)
        if (cleanTitle.isBlank()) {
            showStatus("标题不能为空")
            return
        }
        var updatedSessions: List<ChatSessionRecord> = emptyList()
        uiStateStore.update { state ->
            updatedSessions = state.chatSessions.map { session ->
                if (session.id == sessionId) {
                    session.copy(
                        title = cleanTitle,
                        manualTitle = true,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    session
                }
            }.sortedForHistory()
            state.copy(
                chatSessions = updatedSessions,
                statusMessage = "已重命名对话"
            )
        }
        persistChatSessions(updatedSessions)
    }

    fun toggleChatSessionPinned(sessionId: String) {
        if (rejectWhileConversationMutationInProgress()) return
        var updatedSessions: List<ChatSessionRecord> = emptyList()
        var pinned = false
        uiStateStore.update { state ->
            updatedSessions = state.chatSessions.map { session ->
                if (session.id == sessionId) {
                    pinned = !session.pinned
                    session.copy(
                        pinned = pinned,
                        updatedAt = System.currentTimeMillis()
                    )
                } else {
                    session
                }
            }.sortedForHistory()
            state.copy(
                chatSessions = updatedSessions,
                statusMessage = if (pinned) "已置顶对话" else "已取消置顶"
            )
        }
        persistChatSessions(updatedSessions)
    }

    fun clearChatHistory() {
        val state = _uiState.value
        if (rejectWhileConversationMutationInProgress()) return
        if (state.isGenerating) {
            showStatus("请先停止当前生成，再清空历史")
            return
        }
        if (state.chatSessions.any { session -> hasActiveAssistantImageExecution(session.id) } ||
            state.imageJobs.any { job -> job.spec?.chatSessionId != null && !job.status.terminal }
        ) {
            showStatus("仍有角色图片任务正在执行，请等任务结束后再清空历史。")
            return
        }
        val rollback = state.conversationMutationRollbackState()
        val freshSession = state.selectedAssistant()?.newConversationSession(
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        manualContextCompressionRequests.clearAll()
        uiStateStore.update {
            it.copy(
                chatSessions = listOfNotNull(freshSession),
                messages = freshSession?.messages.orEmpty(),
                input = "",
                activeChatSessionId = freshSession?.id,
                browserTask = it.browserTask?.let { task ->
                    com.muyuchat.feature.chat.BrowserTaskStateMachine.cancel(task, "Conversation history cleared")
                },
                selectedKnowledgeBaseIds = emptySet(),
                promptContextUsage = null,
                contextCompressionPending = false,
                statusMessage = "已清空聊天记录"
            )
        }
        queueWorldBookCleanup(
            scope = WorldBookScope.CHAT,
            ownerIds = state.chatSessions.mapTo(hashSetOf()) { it.id }
        )
        pendingAssistantImageToolTurns.clear()
        persistConversationMutation(listOfNotNull(freshSession), rollback)
        viewModelScope.launch(Dispatchers.IO) {
            chatImageToolAuthorizationStore.prune(emptyList())
        }
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
        viewModelScope.launch { verifiedOfflineTranslationBundle() }
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
        // R3/S16: 诊断族写入统一经 UiStateStore（同一底层流，CAS 语义不变）。
        uiStateStore.update {
            it.copy(
                logs = engine.recentLogs(),
                agentLogs = agentLogger.recent(),
                benchmarkHistory = benchmarkHistoryLogger.recent(),
                nativeStatsJson = engine.nativeStatsJson(),
                diagnosticReport = buildDiagnosticReport()
            )
        }
    }

    fun refreshDiagnostics() {
        // R3/S16: 诊断族写入统一经 UiStateStore。
        uiStateStore.update {
            it.copy(
                nativeStatsJson = engine.nativeStatsJson(),
                benchmarkHistory = benchmarkHistoryLogger.recent(),
                diagnosticReport = buildDiagnosticReport(),
                statusMessage = "诊断报告已刷新"
            )
        }
    }

    fun exportDiagnosticReport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val report = buildDiagnosticReport()
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

    fun chatSessionExportFileName(sessionId: String): String {
        val session = _uiState.value.chatSessions.firstOrNull { it.id == sessionId }
        val title = safeChatExportTitle(session?.title)
        return "mca-$title-${System.currentTimeMillis()}.md"
    }

    fun exportChatSession(sessionId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val session = _uiState.value.chatSessions.firstOrNull { it.id == sessionId }
                    ?: error("未找到要导出的对话")
                val markdown = session.toMarkdown()
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(markdown.toByteArray(Charsets.UTF_8))
                } ?: error("无法写入所选位置。")
                session
            }.onSuccess { session ->
                uiStateStore.update {
                    it.copy(statusMessage = "已导出对话：${session.title}")
                }
            }.onFailure { error ->
                fail("对话导出失败：${error.message}")
            }
        }
    }

    fun scanAgent() {
        viewModelScope.launch(Dispatchers.IO) {
            busy("Agent 正在扫描设备和模型...")
            runCatching {
                val device = currentDeviceProfile()
                val recommendation = buildRecommendation(_uiState.value.preference, device)
                activeRuntimeIdentity?.let { identity ->
                    val loaded = _uiState.value.models.firstOrNull { it.id == _uiState.value.loadedModelId }
                    if (loaded != null) {
                        activeAdaptiveRecommendation = buildAdaptiveTuningRecommendation(
                            loaded,
                            identity,
                            device,
                            modelTuningCapabilities(
                                loaded,
                                identity,
                                loaded.runtime == ChatModelRuntime.GENIEX_QAIRT
                            )
                        )
                    }
                }
                agentLogger.append(device, recommendation)
                uiStateStore.update {
                    it.copy(
                        deviceProfile = device,
                        agentRecommendation = recommendation,
                        recommendedRemoteModels = sortRecommendedModels(
                            modelScopeClient.userFacingRecommendedModels(),
                            device
                        ),
                        agentLogs = agentLogger.recent(),
                        busy = false,
                        statusMessage = recommendation.explanation
                    )
                }
            }.onFailure { error ->
                fail("Agent 体检失败：${error.message}")
            }
        }
    }

    fun applyAgentRecommendation() {
        val state = _uiState.value
        val recommendation = state.agentRecommendation ?: return
        val previousParams = state.params
        val generationRecommendation = activeAdaptiveRecommendation?.generationRecommendation
        val updatedParams = generationRecommendation?.applyTo(state.params) ?: state.params.copy(
            nPredict = recommendation.tuningPlan.nPredict,
            temperature = recommendation.tuningPlan.temperature,
            topK = recommendation.tuningPlan.topK,
            topP = recommendation.tuningPlan.topP,
            minP = recommendation.tuningPlan.minP,
            repeatPenalty = recommendation.tuningPlan.repeatPenalty,
            presencePenalty = recommendation.tuningPlan.presencePenalty,
            frequencyPenalty = recommendation.tuningPlan.frequencyPenalty,
            seed = recommendation.tuningPlan.seed ?: state.params.seed
        )
        val device = state.deviceProfile ?: currentDeviceProfile()
        agentLogger.append(
            device = device,
            recommendation = recommendation,
            benchmark = state.benchmark,
            appliedPlan = recommendation.tuningPlan,
            userConfirmed = true
        )
        GenerationPreferences.persistGenerationParams(getApplication(), updatedParams)
        persistModelGenerationParams(state, updatedParams)
        uiStateStore.update {
            it.copy(
                params = updatedParams,
                rollbackParams = previousParams,
                agentLogs = agentLogger.recent(),
                statusMessage = "已应用助手生成建议；模型加载、线程、模板和 native 参数未被静默修改。"
            )
        }
    }

    fun rollbackAgentParams() {
        val state = _uiState.value
        val previous = state.rollbackParams ?: return
        GenerationPreferences.persistGenerationParams(getApplication(), previous)
        persistModelGenerationParams(state, previous)
        uiStateStore.update {
            it.copy(
                params = previous,
                rollbackParams = null,
                statusMessage = "已回退到上一次助手生成参数；模型执行 profile 保持不变。"
            )
        }
    }

    fun loadAgentRecommendedModel() {
        val recommendation = _uiState.value.agentRecommendation?.recommended
        val modelId = recommendation?.model?.id
        val model = _uiState.value.models.firstOrNull { it.id == modelId }
        if (model != null) {
            loadModel(model)
        } else {
            uiStateStore.update {
                it.copy(
                    statusMessage = "推荐模型尚未在本机。请先到模型页下载或导入，再由 Agent 加载。"
                )
            }
        }
    }

    fun runAgentBenchmark() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentParams = engineApiGenerationParams(engine, _uiState.value)
            busy("正在按当前参数测速...")
            runCatching {
                val device = currentDeviceProfile()
                val result = benchmarkRunner.runCurrentParamsBenchmark(currentParams)
                appendBenchmarkHistory(device, result, currentParams)
                uiStateStore.update {
                    it.copy(
                        deviceProfile = device,
                        benchmark = result,
                        benchmarkHistory = benchmarkHistoryLogger.recent(),
                        busy = false,
                        statusMessage = result.error
                            ?: "当前参数测速完成：decode ${"%.2f".format(result.decodeTps)} token/s，threads=${currentParams.nThreads}"
                    )
                }
            }.onFailure { error ->
                fail("当前参数测速失败：${error.message}")
            }
        }
    }

    fun runAgentStabilitySmoke() {
        viewModelScope.launch(Dispatchers.IO) {
            val initialState = _uiState.value
            if (initialState.isGenerating) {
                showStatus("请先停止当前生成，再运行稳定性自检")
                return@launch
            }
            val requestedModel = initialState.models.firstOrNull { it.id == initialState.loadedModelId }
            if (requestedModel == null) {
                showStatus("请先加载一个本地推理模型，再运行稳定性自检")
                return@launch
            }
            busy("正在运行稳定性自检...")
            runCatching {
                cancelGenerationJob()
                engine.stopGeneration()
                val preflight = modelStore.validateForLoad(requestedModel.id)
                val validatedCatalog = publishManagedChatCatalogAfterValidation()
                if (!preflight.canLoad) error("加载前检查失败：${preflight.message}")
                val model = validatedCatalog.models.firstOrNull { it.id == requestedModel.id }
                    ?: requestedModel
                LocalModelMemoryAdmissionPolicy.evaluate(model, currentDeviceProfile()).blocker
                    ?.let { error("加载前内存检查失败：$it") }

                val runtime = model.runtime.toLocalChatRuntime()
                val loadParams = model.buildLoadParamsFor(initialState.params)
                val firstLoadMs = timedLoadModel(model, runtime, loadParams)
                val secondLoadMs = timedLoadModel(model, runtime, loadParams)
                val generation = runLocalGenerationSmoke(initialState.params)
                val apiGuard = runLoopbackApiStreamSmoke()
                val apiEngine = runLoopbackEngineStreamSmoke(initialState.params)
                val nativeStats = engine.nativeStatsJson()
                val summary = buildString {
                    append("Reload ").append(firstLoadMs).append("ms / ").append(secondLoadMs).append("ms")
                    append(" · Stream ").append(generation.visibleChars).append(" 字")
                    append(" · API 防截断 ").append(if (apiGuard.stopCalls == 0 && apiGuard.visibleContentSeen && apiGuard.doneSeen) "通过" else "需排查")
                    append(" · HTTP 实流 ").append(if (apiEngine.visibleContentSeen && apiEngine.doneSeen) "通过" else "需排查")
                    append(" · n_ctx ").append(JSONObject(nativeStats).optInt("nCtx", initialState.params.nCtx))
                }
                modelStore.markLoaded(model.id)
                cloudApiStore.saveSelectedBackend(ChatBackend.LOCAL)
                managedRuntimeReadinessRefreshGate.invalidate()
                uiStateStore.update { state ->
                    state.copy(
                        loadedModelId = model.id,
                        loadedModelName = model.displayName,
                        selectedChatBackend = ChatBackend.LOCAL,
                        models = modelStore.listModels(),
                        busy = false,
                        nativeStatsJson = nativeStats,
                        logs = engine.recentLogs(),
                        localStabilitySmokeSummary = summary,
                        diagnosticReport = buildDiagnosticReport(),
                        statusMessage = "稳定性自检通过：$summary"
                    )
                }
            }.onFailure { error ->
                val message = "稳定性自检失败：${error.message ?: "未知错误"}"
                uiStateStore.update {
                    it.copy(
                        busy = false,
                        nativeStatsJson = engine.nativeStatsJson(),
                        localStabilitySmokeSummary = message,
                        diagnosticReport = buildDiagnosticReport(),
                        statusMessage = message
                    )
                }
            }
        }
    }

    fun startAgentTuning() {
        startAgentTuning(AgentDebugMode.Quick)
    }

    fun startAgentTuning(mode: AgentTuningMode, autoApply: Boolean) {
        val debugMode = when (mode) {
            AgentTuningMode.QUICK -> AgentDebugMode.Quick
            AgentTuningMode.STANDARD -> AgentDebugMode.Standard
            AgentTuningMode.DEEP -> AgentDebugMode.Deep
            AgentTuningMode.POWER_SAVE -> AgentDebugMode.PowerSave
        }
        if (mode == AgentTuningMode.POWER_SAVE) {
            uiStateStore.update { it.copy(preference = it.preference.copy(mode = PerformanceMode.PowerSave)) }
        }
        startAgentTuning(debugMode = debugMode, autoApply = autoApply)
    }

    fun pauseAgentTuning() {
        adaptiveTuningPauseRequested.set(true)
        val jobId = activeTuningJobId
        if (jobId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { runtimeProfileStore.pauseTuningJob(jobId) }
                    .onSuccess {
                        uiStateStore.update { state ->
                            state.copy(
                                tuningJobState = AgentTuningJobState.PAUSED,
                                tuningPhase = "等待用户续跑",
                                statusMessage = "调优已暂停；不会提交新的候选。"
                            )
                        }
                    }
            }
        } else {
            uiStateStore.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待任务") }
        }
    }

    fun resumeAgentTuning() {
        adaptiveTuningPauseRequested.set(false)
        val jobId = activeTuningJobId
        if (jobId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
                    .onSuccess {
                        uiStateStore.update { state ->
                            state.copy(
                                tuningJobState = AgentTuningJobState.RUNNING,
                                tuningPhase = "继续执行",
                                statusMessage = "调优已继续。"
                            )
                        }
                    }
            }
        } else {
            uiStateStore.update { it.copy(tuningJobState = AgentTuningJobState.IDLE) }
        }
    }

    fun cancelAgentTuning() {
        adaptiveTuningCancelRequested.set(true)
        val jobId = activeTuningJobId
        if (jobId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { cancelPersistedTuningJob(jobId) }
                    .onSuccess { canceled ->
                        uiStateStore.update {
                            it.copy(
                                tuningJobState = agentJobState(canceled),
                                tuningPhase = canceled.phase,
                                statusMessage = if (canceled.state == PersistedTuningJobState.FAILED.name) {
                                    "调优已取消，稳定 profile 已保留。"
                                } else {
                                    "正在取消调优并恢复当前稳定配置…"
                                }
                            )
                        }
                    }
            }
        } else {
            uiStateStore.update { it.copy(tuningJobState = AgentTuningJobState.IDLE, statusMessage = "当前没有运行中的调优任务。") }
        }
    }

    fun queryAgentTuningJob() {
        val identity = activeRuntimeIdentity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtimeProfileStore.currentRuntimeState(identity.identityHash) }
                .onSuccess { snapshot ->
                    val job = snapshot?.activeJob
                    uiStateStore.update { state ->
                        state.copy(
                            tuningJobState = job?.let(::agentJobState) ?: state.tuningJobState,
                            tuningPhase = job?.phase ?: state.tuningPhase,
                            statusMessage = job?.let { "调优任务 ${it.jobId.take(8)}：${it.phase}" }
                                ?: state.statusMessage
                        )
                    }
                }
        }
    }

    fun applyPendingAgentProfile() {
        val identity = activeRuntimeIdentity ?: return
        if (adaptiveTuningJob?.isActive == true) {
            showStatus("运行时正在执行其他调优生命周期操作。")
            return
        }
        adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = runCatching { runtimeProfileStore.pendingTransaction(identity.identityHash) }
                    .getOrNull()
                if (pending == null) {
                    showStatus("当前没有可应用的 pending profile。")
                    return@launch
                }
                applyPendingRuntimeTransaction(pending)
            } finally {
                activeTuningJobId = null
                adaptiveTuningJob = null
            }
        }
    }

    fun discardPendingAgentProfile() {
        val identity = activeRuntimeIdentity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val pending = runtimeProfileStore.pendingTransaction(identity.identityHash)
                    ?: error("待应用配置已不存在")
                val transactionId = pending.journal.transactionId
                val recovery = runtimeProfileStore.rejectCandidate(
                    transactionId = transactionId,
                    failureStage = "USER_DISCARDED",
                    failureCode = "DISCARDED",
                    failureSummary = "用户撤销了尚未应用的运行参数修改。"
                )
                runtimeProfileStore.completeRecovery(
                    transactionId = transactionId,
                    restoredProfileId = recovery.rollbackProfileId
                )
                if (pendingProfileTransactionId == transactionId) {
                    pendingProfileTransactionId = null
                }
                val restoredProfile = engine.activeExecutionProfile()
                    ?: runtimeProfileStore.currentRuntimeState(identity.identityHash)?.activeExecutionProfile
                    ?: error("稳定运行配置恢复记录缺失")
                val restored = mergedExecutionProfile(_uiState.value.params, restoredProfile)
                GenerationPreferences.persistGenerationParams(getApplication(), restored)
                uiStateStore.update {
                    it.copy(
                        params = restored,
                        pendingProfile = null,
                        reloadRequired = false,
                        tuningJobState = AgentTuningJobState.IDLE,
                        tuningPhase = null,
                        statusMessage = "已撤销未应用的运行参数修改。"
                    )
                }
            }.onFailure { error ->
                uiStateStore.update {
                    it.copy(statusMessage = "撤销修改失败：${error.message ?: "未知错误"}")
                }
            }
        }
    }

    fun rollbackAgentProfile() {
        val identity = activeRuntimeIdentity ?: return
        val model = activeModelForRuntimeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            rollbackCommittedRuntimeProfile(identity, model)
        }
    }

    fun runAgentQuickDebug() {
        startAgentTuning(AgentDebugMode.Quick)
    }

    fun runAgentStandardDebug() {
        startAgentTuning(AgentDebugMode.Standard)
    }

    fun runAgentDeepDebug() {
        startAgentTuning(AgentDebugMode.Deep)
    }

    fun runAgentPowerDebug() {
        uiStateStore.update { it.copy(preference = it.preference.copy(mode = PerformanceMode.PowerSave)) }
        startAgentTuning(AgentDebugMode.PowerSave)
    }

    private fun startAgentTuning(
        debugMode: AgentDebugMode,
        autoApply: Boolean = true,
        precreatedJob: TuningJobEntity? = null,
        preferenceOverride: UserPreference? = null
    ): Boolean {
        if (adaptiveTuningJob?.isActive == true) {
            showStatus("已有调优任务在运行。")
            return false
        }
        val identity = activeRuntimeIdentity
        val model = activeModelForRuntimeProfile
        val committed = engine.activeExecutionProfile()
        if (identity == null || model == null || committed == null) {
            showStatus("请先通过正式模型页加载本地模型，再开始智能调参。")
            return false
        }
        require(precreatedJob == null || precreatedJob.identityKey == identity.identityHash) {
            "预创建调优任务不属于当前 runtime identity"
        }
        require(precreatedJob == null || precreatedJob.autoApplyLoadChanges == autoApply) {
            "预创建调优任务的 autoApply 授权不匹配"
        }
        adaptiveTuningPauseRequested.set(false)
        adaptiveTuningCancelRequested.set(false)
        activeTuningJobId = precreatedJob?.jobId
        adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
            var jobId: String? = precreatedJob?.jobId
            var transactionId: String? = null
            var staged = false
            var retainStagedJob = false
            var lease: com.muyuchat.core.engine.EngineLifecycleLease? = null
            try {
                val device = currentDeviceProfile()
                val capabilities = modelTuningCapabilities(
                    model,
                    identity,
                    model.runtime == ChatModelRuntime.GENIEX_QAIRT
                )
                val recommendation = buildAdaptiveTuningRecommendation(
                    model = model,
                    identity = identity,
                    device = device,
                    capabilities = capabilities,
                    preference = preferenceOverride ?: _uiState.value.preference
                )
                activeAdaptiveRecommendation = recommendation
                val tuningDepth = debugMode.searchDepth()
                // RuntimeParameterAdapter.userOverrides also contains values
                // supplied by an automatically generated baseline. Only this
                // durable set is proven to originate from an explicit user edit.
                val explicitUserOverrides = runtimeUserOverrideFields.toSet()
                // Search starts from the exact active profile so an automatic
                // candidate can never reset a user-loaded context or another
                // already effective execution value back to a recommendation.
                val searchBase = committed.asTuningExecutionProfile(
                    kind = recommendation.executionProfile.kind,
                    verificationLevel = ProfileVerificationLevel.COMPATIBLE,
                    reason = "以当前 active profile 为逐阶段调参基线。"
                )
                val searchStages = agentTuningStages(
                    depth = tuningDepth,
                    capabilities = capabilities,
                    userOverrides = explicitUserOverrides
                )
                if (searchStages.isEmpty()) {
                    precreatedJob?.let { queued ->
                        runtimeProfileStore.transitionTuningJob(
                            jobId = queued.jobId,
                            state = PersistedTuningJobState.FAILED,
                            phase = "NO_TUNABLE_FIELDS",
                            failureCode = "NO_TUNABLE_FIELDS",
                            failureSummary = "当前运行时没有可安全自动搜索且未被用户锁定的执行字段。"
                        )
                    }
                    uiStateStore.update {
                        it.copy(
                            tuningJobState = if (precreatedJob == null) {
                                AgentTuningJobState.SUCCEEDED
                            } else {
                                AgentTuningJobState.FAILED
                            },
                            tuningPhase = "没有可自动搜索的执行字段",
                            tuningCandidateProgress = AgentCandidateProgress(),
                            busy = false,
                            autoTuningInProgress = false,
                            statusMessage = "当前运行参数均为用户自定义值，或当前 runtime 没有可安全自动搜索的字段；未修改任何参数。"
                        )
                    }
                    return@launch
                }
                val plannedCandidateTotal = searchStages.sumOf { stage ->
                    buildAgentTuningStageCandidates(
                        stage = stage,
                        base = searchBase,
                        capabilities = capabilities,
                        cpuCores = device.cpuCores,
                        estimatedBigCores = device.estimatedBigCores,
                        depth = tuningDepth,
                        userOverrides = explicitUserOverrides,
                        profileIdPrefix = "plan",
                        revision = committed.revision + 1
                    ).size
                }
                val safety = SafetyEnvelope.forDevice(device).assess(measurementPoint(device))
                if (!safety.passed) error(safety.violations.joinToString("；") { it.message })
                val job = precreatedJob ?: runtimeProfileStore.createTuningJob(
                    identityKey = identity.identityHash,
                    autoApplyLoadChanges = autoApply,
                    phase = "QUEUED"
                )
                jobId = job.jobId
                activeTuningJobId = job.jobId
                runtimeProfileStore.transitionTuningJob(job.jobId, PersistedTuningJobState.RUNNING, "DISCOVERY")
                awaitTuningBoundary(job.jobId)
                uiStateStore.update {
                    it.copy(
                        busy = true,
                        autoTuningInProgress = true,
                        tuningJobState = AgentTuningJobState.RUNNING,
                        tuningPhase = "候选搜索",
                        tuningEtaSeconds = estimateTuningEtaSeconds(model, debugMode),
                        tuningCandidateProgress = AgentCandidateProgress(
                            total = plannedCandidateTotal,
                            currentCandidate = tuningExecutionParameterSummary(searchBase)
                        ),
                        pendingProfile = null,
                        statusMessage = "${debugMode.label}：正在执行 ${searchStages.joinToString(" → ") { stage -> stage.label }} 逐阶段有界搜索…"
                    )
                }
                // Probe a bounded set of real runtime candidates. Each probe
                // is authorized, measured, correctness-gated, and rolled back
                // before the next candidate, so only the selected candidate is
                // persisted as active/LKG. This keeps tuning deterministic and
                // prevents a fast but unsafe candidate from winning by speed
                // alone.
                val probeCanaryParams = recommendation.canaryParams.copy(
                    maxOutputTokens = recommendation.canaryParams.maxOutputTokens.coerceIn(32, 48)
                )
                var eligibleCandidates = 0
                var completedCandidates = 0
                var rejectedCandidates = 0
                var currentBest = searchBase
                var selectedProbeResult: CandidateCanaryResult? = null
                val selectedStageSummaries = mutableListOf<String>()
                val probeRevisionBase = maxOf(
                    committed.revision + 1L,
                    (runtimeProfileStore.profiles(identity.identityHash).maxOfOrNull { it.revision } ?: 0L) + 1L
                )
                for ((stageIndex, stage) in searchStages.withIndex()) {
                    val stageCandidates = buildAgentTuningStageCandidates(
                        stage = stage,
                        base = currentBest,
                        capabilities = capabilities,
                        cpuCores = device.cpuCores,
                        estimatedBigCores = device.estimatedBigCores,
                        depth = tuningDepth,
                        userOverrides = explicitUserOverrides,
                        profileIdPrefix = "probe-${job.jobId.replace("-", "").take(12)}-$stageIndex",
                        revision = committed.revision + 1
                    )
                    if (stageCandidates.isEmpty()) continue
                    val measuredStage = mutableListOf<Pair<AgentTuningStageCandidate, CandidateCanaryResult>>()
                    uiStateStore.update {
                        it.copy(
                            tuningPhase = "${stage.label}候选搜索",
                            statusMessage = "${debugMode.label}：开始 ${stage.label} 阶段；仅从上一阶段最佳配置继续。"
                        )
                    }
                    for ((candidateIndex, candidate) in stageCandidates.withIndex()) {
                        awaitTuningBoundary(job.jobId)
                        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
                        val summary = candidate.executionSummary
                        uiStateStore.update {
                            it.copy(
                                tuningPhase = "${stage.label} ${candidateIndex + 1}/${stageCandidates.size}",
                                tuningCandidateProgress = AgentCandidateProgress(
                                    completed = completedCandidates,
                                    total = plannedCandidateTotal,
                                    currentCandidate = "${stage.label} · $summary",
                                    passed = eligibleCandidates,
                                    rejected = rejectedCandidates
                                ),
                                statusMessage = "${debugMode.label}：正在验证 ${stage.label} · $summary"
                            )
                        }
                        val canaryPlan = TuningCandidateCanaryPlanner.plan(searchBase, candidate.profile)
                        val result = when (canaryPlan.processBoundary) {
                            CandidateProcessBoundary.REJECT_IDENTITY_MISMATCH -> {
                                error("候选与当前 committed profile 的 runtime identity 不一致")
                            }
                            CandidateProcessBoundary.ISOLATED_PROCESS_REQUIRED -> {
                                // Room keeps terminal probe records for crash audit and enforces a
                                // unique revision per identity. Give every disposable probe its own
                                // identity while retaining the exact authoritative signatures.
                                val probeToken = UUID.randomUUID().toString().replace("-", "").take(12)
                                val isolatedProfile = candidate.profile.copy(
                                    engineProfile = candidate.profile.engineProfile.copy(
                                        profileId = "probe-${job.jobId.replace("-", "").take(12)}-$probeToken",
                                        revision = probeRevisionBase + completedCandidates
                                    ),
                                    reason = "${candidate.profile.reason} Isolated persisted load-bound probe."
                                )
                                runIsolatedTuningCandidateCanary(
                                    candidate = isolatedProfile,
                                    committed = committed,
                                    model = model,
                                    jobId = job.jobId,
                                    stage = stage.name,
                                    onProgress = { progress ->
                                        uiStateStore.update { state ->
                                            if (state.autoTuningInProgress) {
                                                state.copy(
                                                    tuningPhase = "${stage.label} · ${progress.stage}",
                                                    statusMessage = "${stage.label} 隔离探测：${progress.message}"
                                                )
                                            } else {
                                                state
                                            }
                                        }
                                    }
                                )
                            }
                            CandidateProcessBoundary.CALLER_PROCESS_ALLOWED -> {
                                var probeLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
                                try {
                                    probeLease = engine.acquireExclusiveLifecycleLease()
                                    val authorization = engine.stagePendingExecutionProfile(
                                        transactionId = "probe-hot-${job.jobId}-$stageIndex-$candidateIndex-${UUID.randomUUID()}",
                                        profile = candidate.profile.engineProfile,
                                        rollbackTargetProfileId = committed.profileId
                                    )
                                    runCandidateCanary(
                                        candidate = candidate.profile,
                                        canaryParams = probeCanaryParams,
                                        authorization = authorization,
                                        disposition = com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                                        lifecycleLease = probeLease
                                    )
                                } finally {
                                    runCatching { probeLease?.release() }
                                    // Hot-only probes still return to the committed execution values
                                    // before the next stage candidate starts.
                                    if (engine.activeExecutionProfile()?.profileId != committed.profileId) {
                                        restoreExactRuntimeProfile(model, committed)
                                    }
                                }
                            }
                        }
                        measuredStage += candidate to result
                        if (canaryScore(port = tuningCanaryPort, result).eligible) {
                            eligibleCandidates++
                        } else {
                            rejectedCandidates++
                        }
                        completedCandidates++
                        uiStateStore.update {
                            it.copy(
                                tuningCandidateProgress = AgentCandidateProgress(
                                    completed = completedCandidates,
                                    total = plannedCandidateTotal,
                                    currentCandidate = "${stage.label} · $summary",
                                    passed = eligibleCandidates,
                                    rejected = rejectedCandidates
                                ),
                                statusMessage = "${debugMode.label}：已验证 $completedCandidates/$plannedCandidateTotal；${stage.label} · $summary"
                            )
                        }
                    }
                    val selectedStageCandidate = StagedCandidateSelectionPolicy.selectBestEligible(
                        stage = stage,
                        candidates = measuredStage,
                        scoreOf = { (_, result) -> canaryScore(port = tuningCanaryPort, result) },
                        contextTokensOf = { (candidate, _) -> candidate.profile.loadBound.nCtx ?: 0 }
                    ) ?: error("${stage.label}没有候选同时通过正确性、安全、签名和稳定性门槛")
                    currentBest = selectedStageCandidate.first.profile
                    selectedProbeResult = selectedStageCandidate.second
                    val selectedSummary = tuningExecutionParameterSummary(currentBest)
                    selectedStageSummaries += "${stage.label}：$selectedSummary"
                    uiStateStore.update {
                        it.copy(
                            tuningPhase = "${stage.label}已选定",
                            tuningCandidateProgress = AgentCandidateProgress(
                                completed = completedCandidates,
                                total = plannedCandidateTotal,
                                currentCandidate = "${stage.label} · $selectedSummary",
                                passed = eligibleCandidates,
                                rejected = rejectedCandidates
                            ),
                            statusMessage = "${stage.label}阶段最佳已选定；下一阶段将从该配置继续：$selectedSummary"
                        )
                    }
                }
                val finalProbeResult = selectedProbeResult
                    ?: error("没有生成可执行的逐阶段候选")
                // Disposable load-bound probes are retained as terminal audit records, so the
                // selected production transaction receives a fresh profile id/revision. Native
                // equivalence is enforced by the two authoritative execution signatures below.
                val candidateProfile = currentBest.copy(
                    engineProfile = currentBest.engineProfile.copy(
                        profileId = "tuning-${job.jobId.replace("-", "").take(16)}-${UUID.randomUUID().toString().take(8)}",
                        revision = probeRevisionBase + maxOf(plannedCandidateTotal, completedCandidates) + 1L
                    )
                )
                finalProbeResult.testedResolvedLoadSignature?.let { tested ->
                    require(tested == candidateProfile.engineProfile.resolvedLoadSignature.digest) {
                        "选定 profile 的 resolved-load signature 与隔离探测证据不一致"
                    }
                }
                finalProbeResult.testedCommittedExecutionSignature?.let { tested ->
                    require(tested == candidateProfile.engineProfile.committedExecutionSignature.digest) {
                        "选定 profile 的 committed-execution signature 与隔离探测证据不一致"
                    }
                }
                val selectedThreads = candidateProfile.hotExecution.nThreads
                val finalExecutionSummary = tuningExecutionParameterSummary(candidateProfile)
                val finalCandidateTotal = maxOf(plannedCandidateTotal, completedCandidates)
                transactionId = "tuning-${job.jobId}"
                val snapshot = candidateProfile.engineProfile.toPersistedExecutionProfileSnapshot(
                    parentCommittedProfileId = committed.profileId,
                    verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                    sourceSummaryJson = JSONObject()
                        .put("mode", debugMode.name)
                        .put("source", "adaptive_candidate")
                        .put("candidateCount", completedCandidates)
                        .put("passedCount", eligibleCandidates)
                        .put("rejectedCount", rejectedCandidates)
                        .put("stages", JSONArray(searchStages.map { stage -> stage.name }))
                        .put("selectedStages", JSONArray(selectedStageSummaries))
                        .put("userOverrideFields", JSONArray(explicitUserOverrides.sorted()))
                        .put("selectedThreads", selectedThreads)
                        .put("testedProbeProfileId", finalProbeResult.testedProfileId)
                        .put("executionSummary", finalExecutionSummary)
                        .toString()
                )
                runtimeProfileStore.stageCandidate(
                    snapshot = snapshot,
                    transactionId = transactionId,
                    rollbackTargetProfileId = committed.profileId,
                    job = job.copy(state = PersistedTuningJobState.RUNNING.name, phase = "DISCOVERY")
                )
                staged = true
                runtimeProfileStore.recordMeasurement(
                    candidateMeasurement(
                        profileId = candidateProfile.profileId,
                        jobId = job.jobId,
                        phase = "BOUNDED_PROBE",
                        result = finalProbeResult
                    )
                )
                if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
                if (!job.autoApplyLoadChanges) {
                    val pausedJob = runtimeProfileStore.transitionTuningJob(
                        jobId = job.jobId,
                        state = PersistedTuningJobState.PAUSED,
                        phase = "STAGED_AWAITING_APPLY"
                    )
                    activeAdaptiveRecommendation = recommendation.copy(executionProfile = candidateProfile)
                    retainStagedJob = true
                    uiStateStore.update { state ->
                        state.copy(
                            tuningJobState = agentJobState(pausedJob),
                            tuningPhase = "候选已暂存，等待应用",
                            tuningEtaSeconds = null,
                            tuningCandidateProgress = AgentCandidateProgress(
                                completed = completedCandidates,
                                total = finalCandidateTotal,
                                currentCandidate = finalExecutionSummary,
                                passed = eligibleCandidates,
                                rejected = rejectedCandidates
                            ),
                            pendingProfile = AgentPendingProfile(
                                profileId = candidateProfile.profileId,
                                revision = candidateProfile.revision,
                                summary = "已通过逐阶段候选探测 · $finalExecutionSummary",
                                readyToApply = true
                            ),
                            busy = false,
                            autoTuningInProgress = false,
                            statusMessage = "逐阶段候选已通过硬门槛并暂存；autoApply=false，等待显式 apply：$finalExecutionSummary"
                        )
                    }
                    return@launch
                }
                runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.APPLYING, "CANDIDATE_APPLYING")
                lease = engine.acquireExclusiveLifecycleLease()
                val authorization = engine.stagePendingExecutionProfile(
                    transactionId = transactionId,
                    profile = candidateProfile.engineProfile,
                    rollbackTargetProfileId = committed.profileId
                )
                runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.VALIDATING, "CORRECTNESS_CANARY")
                runtimeProfileStore.transitionTuningJob(job.jobId, PersistedTuningJobState.VALIDATING, "CORRECTNESS_CANARY")
                val result = runCandidateCanary(
                    candidate = candidateProfile,
                    canaryParams = recommendation.canaryParams,
                    authorization = authorization,
                    disposition = com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                    lifecycleLease = lease
                )
                runtimeProfileStore.recordMeasurement(
                    candidateMeasurement(
                        profileId = candidateProfile.profileId,
                        jobId = job.jobId,
                        phase = "COMMIT_CANARY",
                        result = result
                    )
                )
                if (adaptiveTuningCancelRequested.get()) {
                    error("用户取消调优")
                }
                val commitScore = canaryScore(port = tuningCanaryPort, result)
                if (!commitScore.eligible) {
                    error(commitScore.reason ?: result.detail)
                }
                engine.commitDeferredPendingExecutionProfile(authorization, lease ?: error("调优 lease 已释放"))
                activeAdaptiveRecommendation = recommendation.copy(executionProfile = candidateProfile)
                val signatures = engine.parameterSignatureSnapshot()
                    ?: error("候选提交缺少签名快照")
                runtimeProfileStore.commitCandidate(
                    transactionId = transactionId,
                    verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                    activeLoadedSignature = signatures.active?.digest ?: signatures.resolved.digest,
                    effectiveExecutionSignature = signatures.effective?.digest ?: signatures.committed.digest
                )
                val benchmark = BenchmarkResult(
                    ttftMs = result.stats.ttftMs,
                    promptTokens = result.stats.promptTokens,
                    prefillMs = result.stats.prefillMs,
                    prefillTps = result.stats.prefillTps,
                    genTokens = result.stats.completionTokens,
                    decodeMs = result.stats.decodeMs,
                    decodeTps = result.stats.decodeTps,
                    e2eTps = result.stats.e2eTps,
                    cacheReuseHit = result.stats.cacheReuseHit,
                    cacheReusedTokens = result.stats.cacheReusedTokens,
                    cacheReuseReason = result.stats.cacheReuseReason,
                    nativePssKb = result.stats.nativePssKb,
                    processRssKb = result.stats.processRssKb,
                    availMemKb = result.stats.availMemKb,
                    totalMemKb = result.stats.totalMemKb,
                    modelMemoryBudgetKb = result.stats.modelMemoryBudgetKb,
                    bestThreadCount = selectedThreads,
                    stable = true
                )
                appendBenchmarkHistory(device, benchmark, mergedExecutionProfile(_uiState.value.params, candidateProfile.engineProfile))
                modelStore.markLoaded(model.id)
                val refreshedModels = modelStore.listModels()
                managedRuntimeReadinessRefreshGate.invalidate()
                val profileState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
                uiStateStore.update { state ->
                    state.copy(
                        models = refreshedModels,
                        params = mergedExecutionProfile(state.params, candidateProfile.engineProfile),
                        benchmark = benchmark,
                        benchmarkHistory = benchmarkHistoryLogger.recent(),
                        profileId = candidateProfile.profileId,
                        revision = candidateProfile.revision,
                        profileRecordState = AgentProfileRecordState.COMMITTED,
                        verification = AgentProfileVerification.COMPATIBLE,
                        reloadRequired = false,
                        pendingProfile = null,
                        rollbackProfile = profileState?.activeProfile?.parentCommittedProfileId?.let {
                            AgentRollbackProfile(it, null, "可回退到上一稳定 profile", true)
                        },
                        tuningJobState = AgentTuningJobState.SUCCEEDED,
                        tuningPhase = "已提交 active/LKG",
                        tuningEtaSeconds = 0L,
                        tuningCandidateProgress = AgentCandidateProgress(
                            completed = completedCandidates,
                            total = finalCandidateTotal,
                            currentCandidate = finalExecutionSummary,
                            passed = eligibleCandidates,
                            rejected = rejectedCandidates
                        ),
                        busy = false,
                        autoTuningInProgress = false,
                        lastAutoTuningSummary = "${debugMode.label}按 ${searchStages.joinToString(" → ") { stage -> stage.label }} 完成逐阶段搜索并提交本机 profile：$finalExecutionSummary",
                        nativeStatsJson = engine.nativeStatsJson(),
                        logs = engine.recentLogs(),
                        statusMessage = "智能调参完成：逐阶段候选通过正确性、安全和签名核对，已原子提交：$finalExecutionSummary"
                    )
                }
            } catch (error: Throwable) {
                val message = error.message ?: "调优失败"
                val canceled = adaptiveTuningCancelRequested.get() || error is CancellationException
                withContext(NonCancellable) {
                    if (staged && transactionId != null) {
                        runCatching {
                            val recovery = runtimeProfileStore.rejectCandidate(
                                transactionId = transactionId,
                                failureStage = "TUNING",
                                failureCode = if (canceled) "USER_CANCELED" else "CANDIDATE_REJECTED",
                                failureSummary = message
                            )
                            lease?.release()
                            lease = null
                            val restoredProfileId = recovery.rollbackProfileId?.let { rollbackId ->
                                val rollback = runtimeProfileStore.reconstructedProfile(rollbackId)
                                    ?: committed.takeIf { it.profileId == rollbackId }
                                if (rollback != null && restoreExactRuntimeProfile(model, rollback)) rollbackId else null
                            }
                            runtimeProfileStore.completeRecovery(transactionId, restoredProfileId)
                        }
                    }
                    jobId?.let { persistedJobId ->
                        runCatching {
                            val persisted = runtimeProfileStore.tuningJob(persistedJobId)
                            if (persisted != null && persisted.state !in setOf(
                                    PersistedTuningJobState.SUCCEEDED.name,
                                    PersistedTuningJobState.FAILED.name
                                )
                            ) {
                                runtimeProfileStore.transitionTuningJob(
                                    jobId = persistedJobId,
                                    state = PersistedTuningJobState.FAILED,
                                    phase = if (canceled) "USER_CANCELED" else "FAILED",
                                    failureCode = if (canceled) "USER_CANCELED" else "TUNING_FAILED",
                                    failureSummary = message
                                )
                            }
                        }
                    }
                }
                uiStateStore.update {
                    it.copy(
                        busy = false,
                        autoTuningInProgress = false,
                        tuningJobState = AgentTuningJobState.FAILED,
                        tuningPhase = "已恢复稳定 profile",
                        tuningEtaSeconds = null,
                        tuningCandidateProgress = it.tuningCandidateProgress.copy(rejected = it.tuningCandidateProgress.rejected + 1),
                        statusMessage = if (canceled) "调优已取消，稳定 profile 已恢复。" else "调优未通过：$message"
                    )
                }
            } finally {
                runCatching { lease?.release() }
                if (!retainStagedJob) activeTuningJobId = null
                adaptiveTuningJob = null
                adaptiveTuningPauseRequested.set(false)
                adaptiveTuningCancelRequested.set(false)
            }
        }
        return true
    }

    private suspend fun awaitTuningBoundary(jobId: String) {
        while (adaptiveTuningPauseRequested.get() && !adaptiveTuningCancelRequested.get()) {
            runCatching { runtimeProfileStore.pauseTuningJob(jobId) }
            uiStateStore.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待续跑") }
            delay(250L)
        }
        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
        runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
    }


    private fun estimateTuningEtaSeconds(model: ModelManifest, mode: AgentDebugMode): Long {
        val history = _uiState.value.benchmarkHistory
            .filter { it.modelId == model.id }
        return tuningEtaSecondsFromPreviousLoad(history.firstOrNull()?.result?.loadMs, mode)
    }

    /**
     * The ViewModel side of [RuntimeProfileTransactionPort]: one route per member, no logic of its
     * own. Both decisions moved to [RuntimeProfileTransactionOperations], which is now the only place
     * a runtime profile is applied or rolled back.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name with the ViewModel member they forward to, and an unqualified call would recurse.
     */
    private val runtimeProfileTransactionPort: RuntimeProfileTransactionPort =
        object : RuntimeProfileTransactionPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override val runtimeProfileStore: ModelRuntimeProfileStore
                get() = this@MainViewModel.runtimeProfileStore

            override val engine: McaInferenceService
                get() = this@MainViewModel.engine

            override val activeModelForRuntimeProfile: ModelManifest?
                get() = this@MainViewModel.activeModelForRuntimeProfile

            override var pendingProfileTransactionId: String?
                get() = this@MainViewModel.pendingProfileTransactionId
                set(value) {
                    this@MainViewModel.pendingProfileTransactionId = value
                }

            override fun candidateMeasurement(
                profileId: String,
                jobId: String?,
                phase: String,
                result: CandidateCanaryResult
            ): CandidateMeasurementEntity =
                this@MainViewModel.candidateMeasurement(profileId, jobId, phase, result)

            override fun tuningCandidateScore(result: CandidateCanaryResult): CandidateScore =
                canaryScore(port = tuningCanaryPort, result)

            override fun mergeExecutionProfile(
                generation: GenerationParams,
                profile: ModelExecutionProfile
            ): GenerationParams = mergedExecutionProfile(generation, profile)

            override suspend fun restoreExactRuntimeProfile(
                model: ModelManifest,
                profile: ModelExecutionProfile
            ): Boolean = this@MainViewModel.restoreExactRuntimeProfile(model, profile)

            override fun showStatus(message: String) {
                this@MainViewModel.showStatus(message)
            }

            override fun asTuningExecutionProfile(
                profile: ModelExecutionProfile,
                kind: ExecutionProfileKind,
                verificationLevel: ProfileVerificationLevel,
                reason: String
            ): TuningExecutionProfile =
                profile.asTuningExecutionProfile(kind = kind, verificationLevel = verificationLevel, reason = reason)

            override suspend fun runCandidateCanary(
                candidate: TuningExecutionProfile,
                canaryParams: CanaryEvaluationParams,
                authorization: LoadAuthorization,
                disposition: PendingProfileDisposition,
                lifecycleLease: EngineLifecycleLease?
            ): CandidateCanaryResult = this@MainViewModel.runCandidateCanary(
                candidate = candidate,
                canaryParams = canaryParams,
                authorization = authorization,
                disposition = disposition,
                lifecycleLease = lifecycleLease
            )
        }

    private suspend fun applyPendingRuntimeTransaction(pending: PendingRuntimeProfileTransaction) {
        applyPendingRuntimeTransaction(port = runtimeProfileTransactionPort, pending = pending)
    }

    private suspend fun rollbackCommittedRuntimeProfile(
        identity: ModelRuntimeIdentity,
        model: ModelManifest
    ) {
        rollbackCommittedRuntimeProfile(port = runtimeProfileTransactionPort, identity = identity, model = model)
    }

    private suspend fun restoreExactRuntimeProfile(
        model: ModelManifest,
        profile: ModelExecutionProfile
    ): Boolean {
        val active = engine.activeExecutionProfile()
        if (engine.stats.value.loaded &&
            active?.profileId == profile.profileId &&
            active.resolvedLoadSignature.digest == profile.resolvedLoadSignature.digest &&
            active.committedExecutionSignature.digest == profile.committedExecutionSignature.digest
        ) return true
        val qairtSha = currentQairtBundleSha256(model, modelStore.listModels())
        return engine.loadModel(
            modelPath = model.path,
            runtime = model.runtime.toLocalChatRuntime(),
            params = model.loadParamsForExecutionProfile(profile),
            qairtBundleSha256 = qairtSha,
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile
        ).isSuccess
    }

    fun showAgentDebugExplanation() {
        uiStateStore.update {
            it.copy(
                statusMessage = "智能调试由规则包执行参数搜索，当前已加载模型只用于解释调试结果。"
            )
        }
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
        chatImagePromptBridgeJob?.cancel()
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

    /** The ViewModel side of [ChatGenerationExecutionPort]. */
    private val chatSessionPersistencePort: ChatSessionPersistencePort =
        object : ChatSessionPersistencePort {
            override val uiState get() = this@MainViewModel.uiState
            // R3/S24: 写入经 store 具名方法（端口不再暴露可变流）。
            override fun updateUiState(transform: (MainUiState) -> MainUiState) =
                this@MainViewModel.uiStateStore.update(transform)
            override val scope get() = this@MainViewModel.viewModelScope
            override val chatRuns get() = this@MainViewModel.chatRuns
            override val chatSessionStore get() = this@MainViewModel.chatSessionStore
            override val chatSessionPersistenceMutex get() = this@MainViewModel.chatSessionPersistenceMutex
            override val chatSessionPersistenceSequence get() = this@MainViewModel.chatSessionPersistenceSequence
            override val chatSessionPersistenceStateLock get() = this@MainViewModel.chatSessionPersistenceStateLock
            override val pendingKnowledgeBindings get() = this@MainViewModel.pendingKnowledgeBindings
            override var durableChatSessions: List<ChatSessionRecord>
                get() = this@MainViewModel.durableChatSessions
                set(value) { this@MainViewModel.durableChatSessions = value }

            override fun flushPendingAssistantOutput(generationRunId: Long?): Boolean =
                this@MainViewModel.flushPendingAssistantOutput(generationRunId)

            override fun reconciledChatSessionsAfterSave(snapshot: List<ChatSessionRecord>): List<ChatSessionRecord> =
                this@MainViewModel.reconciledChatSessionsAfterSave(snapshot)

            override fun publishReconciledChatSessions(requested: List<ChatSessionRecord>, persisted: List<ChatSessionRecord>) {
                this@MainViewModel.publishReconciledChatSessions(requested, persisted)
            }

            override fun cleanupPendingWorldBooksAfterOwnerCommit(scope: WorldBookScope, retainedOwnerIds: Set<String>) {
                this@MainViewModel.cleanupPendingWorldBooksAfterOwnerCommit(scope, retainedOwnerIds)
            }

            override val engine get() = this@MainViewModel.engine
            override var localConversationContextNeedsInvalidation: Boolean
                get() = this@MainViewModel.localConversationContextNeedsInvalidation
                set(value) { this@MainViewModel.localConversationContextNeedsInvalidation = value }
            override val localConversationContextInvalidationSequence get() = this@MainViewModel.localConversationContextInvalidationSequence
            override var conversationMutationBarrier: Job?
                get() = this@MainViewModel.conversationMutationBarrier
                set(value) { this@MainViewModel.conversationMutationBarrier = value }
            override var localConversationContextInvalidationJob: Job?
                get() = this@MainViewModel.localConversationContextInvalidationJob
                set(value) { this@MainViewModel.localConversationContextInvalidationJob = value }

            override fun cancelPendingWorldBookCleanup(scope: WorldBookScope, ownerIds: Set<String>) {
                this@MainViewModel.cancelPendingWorldBookCleanup(scope, ownerIds)
            }
        }

    private val imageLibraryCommitPort: ImageLibraryCommitPort =
        object : ImageLibraryCommitPort {
            override val uiState get() = this@MainViewModel.uiState
            // R3/S25: 写入经 store 具名方法（端口不再暴露可变流）。
            override fun updateUiState(transform: (MainUiState) -> MainUiState) =
                this@MainViewModel.uiStateStore.update(transform)
            override val imageLibraryMutationMutex get() = this@MainViewModel.imageLibraryMutationMutex
            override val chatSessionPersistenceMutex get() = this@MainViewModel.chatSessionPersistenceMutex
            override val chatSessionPersistenceSequence get() = this@MainViewModel.chatSessionPersistenceSequence
            override val chatSessionStore get() = this@MainViewModel.chatSessionStore
            override var durableChatSessions: List<ChatSessionRecord>
                get() = this@MainViewModel.durableChatSessions
                set(value) { this@MainViewModel.durableChatSessions = value }
            override val activeImageGenerationJobId get() = this@MainViewModel.activeImageGenerationJobId
            override val chatImageCommitJournal get() = this@MainViewModel.chatImageCommitJournal

            override fun buildChatImageCommitSnapshot(job: ImageGenerationJobRecord, images: List<ImageAssetRecord>): ChatImageCommitSnapshot? =
                chatImageCommitSnapshot(chatImageRequestPort, job, images)

            override fun chatImageCommitJournalJobId(jobId: String, imageId: String): String =
                chatImageCommitJournalJobId(jobId, imageId)

            override fun updateImageJob(
                jobs: List<ImageGenerationJobRecord>,
                jobId: String,
                status: ImageGenerationStatusRecord,
                message: String,
                imageAssetId: String?,
                preview: PublishedLocalImagePreview?,
                imageAssetIds: List<String>
            ): List<ImageGenerationJobRecord> = with(this@MainViewModel) {
                jobs.updateImageJob(
                    jobId = jobId,
                    status = status,
                    message = message,
                    imageAssetId = imageAssetId,
                    preview = preview,
                    imageAssetIds = imageAssetIds
                )
            }
        }

    private val imageAssetDeletionPort: ImageAssetDeletionPort =
        object : ImageAssetDeletionPort {
            override val uiState get() = this@MainViewModel.uiState
            // R3/S25: 写入经 store 具名方法（端口不再暴露可变流）。
            override fun updateUiState(transform: (MainUiState) -> MainUiState) =
                this@MainViewModel.uiStateStore.update(transform)
            override val scope get() = this@MainViewModel.viewModelScope
            override val imageLibraryMutationMutex get() = this@MainViewModel.imageLibraryMutationMutex
            override val imageUpscaleOwner get() = this@MainViewModel.imageUpscaleOwner
            override val chatSessionStore get() = this@MainViewModel.chatSessionStore
            override val latestImageFavoriteMutations get() = this@MainViewModel.latestImageFavoriteMutations
            override val imageFavoriteMutationSequence get() = this@MainViewModel.imageFavoriteMutationSequence
            override val imageAssetDirectory get() = this@MainViewModel.imageAssetDirectory

            override suspend fun awaitImageLibraryStartupReconciliation() {
                this@MainViewModel.awaitImageLibraryStartupReconciliation()
            }

            override fun showStatus(message: String?) {
                this@MainViewModel.showStatus(message)
            }
        }

    private val localApiAuxProviderPort: LocalApiAuxProviderPort =
        object : LocalApiAuxProviderPort {
            override fun analysisParamsJson(): String = engineApiGenerationParams(engine, _uiState.value).toJson()

            override fun analysisGenerationParams(): GenerationParams = engineApiGenerationParams(engine, _uiState.value)

            override fun modelsJsonText(): String = localApiModelsJson(localApiPayloadPort)

            override fun textualInversionsJsonText(): String = JSONArray().apply {
                _uiState.value.localImageTextualInversions.forEach { artifact ->
                    put(artifact.toJson(includePath = false))
                }
            }.toString()

            override fun modelRuntimeStatesJsonText(): String = modelRuntimeStatesJson()

            override fun deviceProfileJsonText(): String =
                currentDeviceProfile().toJson().toString()

            override fun recommendationJsonText(requestJson: String): String {
                val preference = UserPreference.fromJson(requestJson)
                return buildRecommendation(preference).toJson().toString()
            }

            override suspend fun benchmarkJsonText(): String =
                benchmarkRunner.runCurrentParamsBenchmark(_uiState.value.params)
                    .toJson()
                    .toString()

            override suspend fun generateImage(requestId: String, body: String): String =
                generateLocalApiImage(requestId, body)
        }

    private val localApiTuningControlPort: LocalApiTuningControlPort =
        object : LocalApiTuningControlPort {
            override fun activeRuntimeIdentity(): ModelRuntimeIdentity? =
                this@MainViewModel.activeRuntimeIdentity

            override fun activeModelForRuntimeProfile(): ModelManifest? =
                this@MainViewModel.activeModelForRuntimeProfile

            override fun localApiModelMatches(
                requestedModel: String,
                state: MainUiState,
                model: ModelManifest
            ): Boolean = this@MainViewModel.localApiModelMatches(requestedModel, state, model)

            override fun localApiDebugMode(value: String): AgentDebugMode? =
                agentDebugModeFromLocalApiValue(value)

            override fun localApiPerformancePreference(value: String?): UserPreference? =
                this@MainViewModel.localApiPerformancePreference(value)

            override fun localApiTuningRejected(
                httpStatus: Int,
                code: String,
                message: String,
                details: JSONObject
            ): com.muyuchat.api.local.LocalApiControlResult.Rejected =
                this@MainViewModel.localApiTuningRejected(httpStatus, code, message, details)

            override fun localApiTuningConflict(
                action: String,
                activeJobId: String?,
                message: String
            ): com.muyuchat.api.local.LocalApiControlResult.Rejected =
                this@MainViewModel.localApiTuningConflict(action, activeJobId, message)

            override fun localApiExclusiveLifecycleConflict(
                action: String,
                allowedStagedJobId: String?
            ): com.muyuchat.api.local.LocalApiControlResult.Rejected? =
                this@MainViewModel.localApiExclusiveLifecycleConflict(action, allowedStagedJobId)

            override fun assignActiveTuningJobId(jobId: String?) {
                activeTuningJobId = jobId
            }

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                uiStateStore.update(transform)
            }

            override fun tuningJobPublicJson(job: TuningJobEntity): JSONObject =
                this@MainViewModel.tuningJobPublicJson(job)

            override fun startAgentTuning(
                debugMode: AgentDebugMode,
                autoApply: Boolean,
                precreatedJob: TuningJobEntity?,
                preferenceOverride: UserPreference?
            ): Boolean = this@MainViewModel.startAgentTuning(
                debugMode = debugMode,
                autoApply = autoApply,
                precreatedJob = precreatedJob,
                preferenceOverride = preferenceOverride
            )

            override suspend fun activeTuningJob(identityKey: String): TuningJobEntity? =
                runtimeProfileStore.activeTuningJob(identityKey)

            override suspend fun createTuningJob(
                identityKey: String,
                autoApplyLoadChanges: Boolean,
                phase: String
            ): TuningJobEntity = runtimeProfileStore.createTuningJob(
                identityKey = identityKey,
                autoApplyLoadChanges = autoApplyLoadChanges,
                phase = phase
            )

            override suspend fun transitionTuningJob(
                jobId: String,
                state: PersistedTuningJobState,
                phase: String,
                failureCode: String?,
                failureSummary: String?
            ): TuningJobEntity? = runtimeProfileStore.transitionTuningJob(
                jobId = jobId,
                state = state,
                phase = phase,
                failureCode = failureCode,
                failureSummary = failureSummary
            )

            override suspend fun tuningJob(jobId: String): TuningJobEntity? =
                runtimeProfileStore.tuningJob(jobId)

            override suspend fun pendingTransaction(identityKey: String): PendingRuntimeProfileTransaction? =
                runtimeProfileStore.pendingTransaction(identityKey)

            override fun setTuningCancelRequested(value: Boolean) {
                adaptiveTuningCancelRequested.set(value)
            }

            override fun setTuningPauseRequested(value: Boolean) {
                adaptiveTuningPauseRequested.set(value)
            }

            override fun launchTuningApply(pending: PendingRuntimeProfileTransaction) {
                adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        applyPendingRuntimeTransaction(pending)
                    } finally {
                        activeTuningJobId = null
                        adaptiveTuningJob = null
                        adaptiveTuningCancelRequested.set(false)
                        adaptiveTuningPauseRequested.set(false)
                    }
                }
            }

            override suspend fun currentRuntimeState(identityKey: String): CurrentRuntimeProfileState? =
                runtimeProfileStore.currentRuntimeState(identityKey)

            override fun launchTuningRollback(identity: ModelRuntimeIdentity, model: ModelManifest) {
                adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rollbackCommittedRuntimeProfile(identity, model)
                    } finally {
                        adaptiveTuningJob = null
                    }
                }
            }
        }

    private val chatLocalApiProviderPort: ChatLocalApiProviderPort =
        object : ChatLocalApiProviderPort {
            override val engine get() = this@MainViewModel.engine
            override val chatRuns get() = this@MainViewModel.chatRuns
            override val uiState get() = this@MainViewModel._uiState

            override fun mergeExecutionProfile(
                generation: GenerationParams,
                profile: ModelExecutionProfile
            ): GenerationParams = mergedExecutionProfile(generation, profile)

            override fun cancelActiveLocalApiImageGeneration(reason: String): Boolean =
                this@MainViewModel.cancelActiveLocalApiImageGeneration(reason)

            override fun loadedModelJson(): String = localApiLoadedModelJson(localApiPayloadPort)
        }

    private val chatExecutionPort: ChatGenerationExecutionPort =
        object : ChatGenerationExecutionPort {
            override val uiState: MutableStateFlow<MainUiState> = this@MainViewModel._uiState
            override val engine: McaInferenceService = this@MainViewModel.engine
            override val chatRuns: ChatRunOwnership = this@MainViewModel.chatRuns
            override val chatSessionStore: ChatSessionStore = this@MainViewModel.chatSessionStore
            override val chatContextComposer: ChatContextComposer = this@MainViewModel.chatContextComposer
            override val cloudChatProvider: OpenAiCompatibleChatProvider = this@MainViewModel.cloudChatProvider
            override val webSearchProvider: WebSearchProvider = this@MainViewModel.webSearchProvider
            override val manualContextCompressionRequests: ContextCompressionRequestStore = this@MainViewModel.manualContextCompressionRequests
            override val localConversationContextInvalidationSequence: AtomicLong = this@MainViewModel.localConversationContextInvalidationSequence
            override var activeWebSearchDeferred:  Deferred<WebSearchResult>?
                get() = this@MainViewModel.activeWebSearchDeferred
                set(value) { this@MainViewModel.activeWebSearchDeferred = value }
            override var activeWebSearchTaskId:  String?
                get() = this@MainViewModel.activeWebSearchTaskId
                set(value) { this@MainViewModel.activeWebSearchTaskId = value }
            override var localConversationContextNeedsInvalidation:  Boolean
                get() = this@MainViewModel.localConversationContextNeedsInvalidation
                set(value) { this@MainViewModel.localConversationContextNeedsInvalidation = value }
            override val foregroundRecoveryJob: Job? = this@MainViewModel.foregroundRecoveryJob

            override fun persistChatSessions(sessions: List<ChatSessionRecord>?, knowledgeBinding: Pair<String, Set<String>>?, generationRunId: Long?) = this@MainViewModel.persistChatSessions(sessions, knowledgeBinding, generationRunId)

            override fun appendAssistant(delta: String, reasoningDelta: String, reasoningDurationMs: Long, forcePublish: Boolean, generationRunId: Long?) = this@MainViewModel.appendAssistant(delta, reasoningDelta, reasoningDurationMs, forcePublish, generationRunId)

            override fun flushPendingAssistantOutput(generationRunId: Long?) = this@MainViewModel.flushPendingAssistantOutput(generationRunId)

            override fun removePendingAssistantPlaceholder(generationRunId: Long?) = this@MainViewModel.removePendingAssistantPlaceholder(generationRunId)

            override fun ensureVisibleAssistantReplyAfterCompletion(generationRunId: Long?) = this@MainViewModel.ensureVisibleAssistantReplyAfterCompletion(generationRunId)

            override fun applyWebSearchAnswerGuardsToLastAssistant(generationRunId: Long?) = this@MainViewModel.applyWebSearchAnswerGuardsToLastAssistant(generationRunId)

            override fun recordWebSearchCitationAudit(recordId: String?, audit: WebSearchCitationAudit, generationRunId: Long?) = this@MainViewModel.recordWebSearchCitationAudit(recordId, audit, generationRunId)

            override fun attachWebSearchEvidenceToPendingAssistant(sources: List<ChatSourceReference>, trace: ChatWebSearchTrace?, generationRunId: Long?) = this@MainViewModel.attachWebSearchEvidenceToPendingAssistant(sources, trace, generationRunId)

            override fun attachContextAssemblyTrace(traceJson: String?, generationRunId: Long, sessionId: String?, assistantMessageId: String?) = this@MainViewModel.attachContextAssemblyTrace(traceJson, generationRunId, sessionId, assistantMessageId)

            override fun promptContextUsageFor(plan: ChatRuntimeContextPlan, admission: ContextWindowAdmission, params: GenerationParams) = this@MainViewModel.promptContextUsageFor(plan, admission, params)

            override fun appendWebSearchDiagnostic(record: WebSearchDiagnosticRecord) = this@MainViewModel.appendWebSearchDiagnostic(record)

            override fun recordCompletedCharacterMemoryTurn(assistantId: String, sessionId: String, assistantSnapshot: AssistantConversationSnapshot, user: ChatMessage, assistant: ChatMessage, conversationPersistJob: Job?, backend: ChatBackend, params: GenerationParams, cloudConfig: CloudApiConfig?, localIdentity: String?) = this@MainViewModel.recordCompletedCharacterMemoryTurn(assistantId, sessionId, assistantSnapshot, user, assistant, conversationPersistJob, backend, params, cloudConfig, localIdentity)

            override suspend fun summarizeContextForGeneration(request: ChatRequest, settings: ContextCompressionSettings, trigger: ContextCompressionTrigger, initialState: MainUiState, effectiveBackend: ChatBackend, cloudModelIdOverride: String?, generationRunId: Long, useModel: Boolean) = this@MainViewModel.summarizeContextForGeneration(request, settings, trigger, initialState, effectiveBackend, cloudModelIdOverride, generationRunId, useModel)

            override suspend fun ensureLocalConversationContextInvalidated() = this@MainViewModel.ensureLocalConversationContextInvalidated()

            override fun loadContextCompressionThreshold() = this@MainViewModel.loadContextCompressionThreshold()

            override suspend fun handleAssistantImageToolCalls(calls: List<ChatToolCall>, originalRequestMessages: List<ChatMessage>, paramsBeforeAssistant: GenerationParams, chatSessionId: String?, cloudModelId: String?, chatModelIdentity: String?, imageModelBinding: AssistantImageModelBinding?, chatConfig: CloudApiConfig?) = this@MainViewModel.handleAssistantImageToolCalls(calls, originalRequestMessages, paramsBeforeAssistant, chatSessionId, cloudModelId, chatModelIdentity, imageModelBinding, chatConfig)

            override fun finishAssistantImageContinuation(requestId: String, completed: Boolean, failureMessage: String) = this@MainViewModel.finishAssistantImageContinuation(requestId, completed, failureMessage)

            override fun chatImageToolChatModelIdentity(modelId: String?, config: CloudApiConfig?) = this@MainViewModel.chatImageToolChatModelIdentity(modelId, config)

            override suspend fun localVisionRunnerAvailable() = isLocalVisionRunnerAvailable(visionAttachmentPort)

            override fun mergeExecutionProfile(generation: GenerationParams, profile: ModelExecutionProfile) = mergedExecutionProfile(generation, profile)

            override fun persistGenerationParams(params: GenerationParams) = GenerationPreferences.persistGenerationParams(getApplication(), params)

            override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean = state.canWriteToActiveAssistantSession()

            override fun selectedAssistant(state: MainUiState): AssistantRecord? = state.selectedAssistant()

            override fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig? = state.selectedChatCloudConfig()

            override fun currentChatModelId(state: MainUiState): String? = state.currentChatModelId()

            override fun chatImageModelBinding(state: MainUiState): AssistantImageModelBinding? = state.chatImageModelBinding()

            override val activeRuntimeIdentity: ModelRuntimeIdentity? = this@MainViewModel.activeRuntimeIdentity

            override fun localVisionUnavailableMessage(state: MainUiState): String = localVisionUnavailableReason(state, runCatching { engine.nativeStatsJson() }.getOrNull())

            override fun chatContextSnapshot(state: MainUiState, assistantId: String, fileContextEnabled: Boolean): ChatContextSnapshot = state.chatContextSnapshot(assistantId, fileContextEnabled)

            override fun isConfigurationActionError(event: GenerateEvent.Error): Boolean = event.isConfigurationActionError()

            override suspend fun withInlineImageDataForCloud(messages: List<ChatMessage>): List<ChatMessage> = messages.preparedForCloudVision(visionAttachmentPort)

            override suspend fun withLocalImageFilesForVision(messages: List<ChatMessage>, createdFiles: MutableList<File>): List<ChatMessage> = messages.preparedForLocalVision(visionAttachmentPort, createdFiles)
        }

    /** The ViewModel side of [ChatImagePromptBridgePort]. */
    private val chatImagePromptBridgePort: ChatImagePromptBridgePort =
        object : ChatImagePromptBridgePort {
            override fun currentState(): MainUiState = _uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                uiStateStore.update(transform)
            }

            override fun currentBridgeGeneration(): Long = chatImagePromptBridgeGeneration.get()

            override fun incrementBridgeGeneration(): Long =
                chatImagePromptBridgeGeneration.incrementAndGet()

            override fun cancelBridgeJob() {
                chatImagePromptBridgeJob?.cancel()
            }

            override fun setBridgeJob(job: Job) {
                chatImagePromptBridgeJob = job
            }

            override fun bridgeScope(): CoroutineScope = viewModelScope

            override fun setPendingChatImageAction(
                id: String,
                kind: com.muyuchat.feature.chat.ChatImageActionKind,
                visibleUserText: String,
                requestedOutputCount: Int?,
                stillOwned: () -> Boolean
            ) {
                pendingChatImageAction = PendingChatImageAction(
                    id = id,
                    kind = kind,
                    visibleUserText = visibleUserText,
                    requestedOutputCount = requestedOutputCount,
                    stillOwned = stillOwned
                )
            }

            override fun setPendingImageTranslation(translation: PendingImageTranslation) {
                pendingImageTranslation = translation
            }

            override fun showStatus(message: String?) {
                this@MainViewModel.showStatus(message = message)
            }

            override fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions =
                this@MainViewModel.chatImageOptionsForCurrentSelection(state)

            override fun composeChatContext(
                messages: List<ChatMessage>,
                params: GenerationParams,
                assistantId: String,
                chatSessionId: String?,
                knowledgeBaseIds: Set<String>,
                fileContextEnabled: Boolean
            ): ChatRuntimeContextPlan = chatContextComposer.compose(
                messages = messages,
                params = params,
                assistantId = assistantId,
                chatSessionId = chatSessionId,
                knowledgeBaseIds = knowledgeBaseIds,
                fileContextEnabled = fileContextEnabled
            )

            override fun streamChatCloud(config: CloudApiConfig, request: ChatRequest): Flow<GenerateEvent> =
                cloudChatProvider.streamChat(config, request)

            override fun streamChatLocal(
                request: ChatRequest,
                executionContext: LocalChatExecutionContext
            ): Flow<GenerateEvent> =
                LocalApiRuntime.streamChat(request, executionContext)
                    ?: engine.streamChat(request, executionContext)

            override suspend fun translateOffline(request: OfflinePromptTranslationRequest): OfflinePromptTranslationResolution =
                offlinePromptTranslationService.translate(verifiedOfflineTranslationBundle(), request)

            override fun sendChatImageRequest(
                visibleUserText: String,
                prompt: String,
                optionsOverride: LocalImageGenerationOptions?,
                promptEnvelope: PromptEnvelope?,
                contextTraceJson: String?
            ) = this@MainViewModel.sendChatImageRequest(
                visibleUserText = visibleUserText,
                prompt = prompt,
                optionsOverride = optionsOverride,
                promptEnvelope = promptEnvelope,
                contextTraceJson = contextTraceJson
            )

            override fun negativePromptSupported(state: MainUiState): Boolean =
                this@MainViewModel.chatImageNegativePromptSupported(state)

            override fun canWriteToActiveAssistantSession(state: MainUiState): Boolean =
                state.canWriteToActiveAssistantSession()

            override fun selectedChatCloudConfig(state: MainUiState): CloudApiConfig? =
                state.selectedChatCloudConfig()

            override fun selectedImageCloudConfig(state: MainUiState): CloudApiConfig? =
                state.selectedImageCloudConfig()

            override fun selectedLocalImageModel(state: MainUiState): LocalImageModelRecord? =
                state.selectedLocalImageModel()

            override fun activeAssistantSnapshot(state: MainUiState): AssistantConversationSnapshot? =
                state.activeAssistantSnapshot()

            override fun selectedAssistant(state: MainUiState): AssistantRecord? =
                state.selectedAssistant()
        }

    /** The ViewModel side of [ChatStopGenerationPort]. */
    private val chatStopGenerationPort: ChatStopGenerationPort =
        object : ChatStopGenerationPort {
            override fun currentState(): MainUiState = _uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                uiStateStore.update(transform)
            }

            override fun stopScope() = viewModelScope

            override fun rejectWhileConversationMutationInProgress(): Boolean =
                this@MainViewModel.rejectWhileConversationMutationInProgress()

            override fun tryEnterStop(): Boolean = stopGenerationInProgress.compareAndSet(false, true)

            override fun exitStop() {
                stopGenerationInProgress.set(false)
            }

            override fun currentReplyMetrics(): ReplyGenerationMetrics? = replyGenerationMetrics

            override fun isRunCurrent(runId: Long?): Boolean = chatRuns.isCurrent(runId)

            override fun currentRunId(): Long = chatRuns.currentRunId()

            override fun cancelCurrentGeneration(): UiGenerationCancellation = cancelGenerationJob()

            override fun activeUiStopRequestId(): String? =
                engine.activeGenerationStopToken()?.requestId

            override suspend fun requestRuntimeStop() {
                engine.stopGeneration()
            }

            override fun runtimeLifecycle(): AgentEngineLifecycle =
                engine.stats.value.lifecycleAfterGeneration()

            override fun rollbackState(): ConversationMutationRollbackState =
                _uiState.value.conversationMutationRollbackState()

            override fun flushPendingAssistantOutput() {
                this@MainViewModel.flushPendingAssistantOutput()
            }

            override fun persistConversationMutation(
                sessions: List<ChatSessionRecord>,
                rollback: ConversationMutationRollbackState
            ) {
                this@MainViewModel.persistConversationMutation(sessions, rollback)
            }
        }

    /** The ViewModel side of [ChatTerminalSettlementPort]. */
    private val chatTerminalSettlementPort: ChatTerminalSettlementPort =
        object : ChatTerminalSettlementPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun currentChatModelId(state: MainUiState): String? =
                this@MainViewModel.run { state.currentChatModelId() }

            override fun upsertSession(
                sessions: List<ChatSessionRecord>,
                sessionId: String,
                messages: List<ChatMessage>,
                assistantId: String?,
                modelMode: String?,
                modelId: String?
            ): List<ChatSessionRecord> =
                this@MainViewModel.run {
                    with(sessions) {
                        upsertSession(
                            sessionId = sessionId,
                            messages = messages,
                            assistantId = assistantId,
                            modelMode = modelMode,
                            modelId = modelId
                        )
                    }
                }


            override val webSearchDiagnosticStore: WebSearchDiagnosticStore
                get() = this@MainViewModel.webSearchDiagnosticStore

            override fun isRunCurrent(runId: Long?): Boolean = this@MainViewModel.chatRuns.isCurrent(runId)

            override fun discardPendingOutput(runId: Long?) {
                this@MainViewModel.discardPendingAssistantOutput(runId)
            }
        }

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

    /**
     * Edits invalidate native history immediately when possible, and always
     * leave a pending barrier for the next local request. The barrier is
     * joined by [startGeneration], so an edit cannot race a new begin call.
     */
    private fun markLocalConversationContextInvalid() {
        localConversationContextNeedsInvalidation = true
        val sequence = localConversationContextInvalidationSequence.incrementAndGet()
        // A durable deletion owns its own Room-then-KV barrier and must not be
        // cancelled by a later ordinary invalidation request.
        localConversationContextInvalidationJob
            ?.takeUnless { it === conversationMutationBarrier }
            ?.cancel()
        localConversationContextInvalidationJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { engine.invalidateConversationContext() }
                .onSuccess {
                    if (localConversationContextInvalidationSequence.get() == sequence) {
                        localConversationContextNeedsInvalidation = false
                    }
                }
                .onFailure { error ->
                    if (localConversationContextInvalidationSequence.get() == sequence) {
                        uiStateStore.update {
                            it.copy(statusMessage = "上下文重置失败，下次本地生成前将重试：${error.message ?: "native runtime error"}")
                        }
                    }
                }
        }
    }

    private fun rejectWhileConversationMutationInProgress(): Boolean {
        if (conversationMutationBarrier?.isActive != true) return false
        uiStateStore.update {
            it.copy(statusMessage = "正在保存上一项对话修改，请稍候。")
        }
        return true
    }

    private fun MainUiState.conversationMutationRollbackState() =
        ConversationMutationRollbackState(
            messages = messages,
            activeChatSessionId = activeChatSessionId,
            chatSessions = chatSessions,
            selectedKnowledgeBaseIds = selectedKnowledgeBaseIds,
            generationPhase = generationPhase,
            generationTokenProgress = generationTokenProgress,
            promptContextUsage = promptContextUsage
        )

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

    private fun reconciledChatSessionsAfterSave(
        snapshot: List<ChatSessionRecord>
    ): List<ChatSessionRecord> {
        val mixedIds = runCatching { chatSessionStore.mixedSessionIds() }.getOrNull() ?: return snapshot
        val currentById = snapshot.associateBy { it.id }
        val durableById = durableChatSessions.associateBy { it.id }
        if (mixedIds.none { id ->
                currentById[id]?.mixedAssistantHistory != true || currentById[id] != durableById[id]
            }) return snapshot
        val persisted = chatSessionStore.load()
        return persisted.takeIf { sessions ->
            mixedIds.all { id -> sessions.any { it.id == id && it.mixedAssistantHistory } }
        } ?: snapshot
    }

    private fun publishReconciledChatSessions(
        requested: List<ChatSessionRecord>,
        persisted: List<ChatSessionRecord>
    ) {
        if (requested == persisted) return
        val requestedById = requested.associateBy { it.id }
        val correctedById = persisted.filter { it.mixedAssistantHistory }.associateBy { it.id }
        var activeCorrection = false
        uiStateStore.update { current ->
            val corrected = current.chatSessions.map { session ->
                correctedById[session.id]?.takeIf { requestedById[session.id] == session } ?: session
            }
            if (corrected == current.chatSessions) current else {
                val activeBefore = current.chatSessions.firstOrNull { it.id == current.activeChatSessionId }
                val activeAfter = corrected.firstOrNull { it.id == current.activeChatSessionId }
                val activeWasCorrected = activeAfter != activeBefore
                if (activeWasCorrected) activeCorrection = true
                current.copy(
                    chatSessions = corrected,
                    messages = if (activeWasCorrected) activeAfter?.messages.orEmpty() else current.messages,
                    isGenerating = if (activeWasCorrected) false else current.isGenerating,
                    generationPhase = if (activeWasCorrected) null else current.generationPhase,
                    generationTokenProgress = if (activeWasCorrected) null else current.generationTokenProgress,
                    generationPersistProgress = if (activeWasCorrected) null else current.generationPersistProgress,
                    statusMessage = if (activeWasCorrected) {
                        "会话角色归属不明确，原始消息已保留；可从历史菜单创建角色分支。"
                    } else current.statusMessage
                )
            }
        }
        if (activeCorrection) cancelGenerationJob()
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
            sessions = _uiState.value.chatSessions,
            knowledgeBinding = sessionId to knowledgeBaseIds
        )
    }

    private fun persistFiles(files: List<FileAssetRecord> = _uiState.value.files) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatSessionStore.saveFiles(files) }
                .onFailure { error ->
                    uiStateStore.update {
                        it.copy(statusMessage = "文件库保存失败：${error.message}")
                    }
                }
        }
    }

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
    ): List<ChatSessionRecord> {
        if (messages.isEmpty()) return this
        val existing = firstOrNull { it.id == sessionId }
        val existingOwner = existing?.assistantSnapshot?.assistantId ?: existing?.assistantId
        if (existing != null && (existing.mixedAssistantHistory ||
                (assistantId != null && existingOwner != assistantId) ||
                (assistantSnapshot != null && assistantSnapshot.assistantId != existingOwner))) return this
        val capturedSnapshot = existing?.assistantSnapshot ?: assistantSnapshot
            ?: assistantId?.let { owner ->
                _uiState.value.assistants.firstOrNull { it.id == owner }?.toConversationSnapshot()
            }
        val record = ChatSessionRecord(
            id = sessionId,
            title = if (existing?.manualTitle == true) existing.title else messages.chatTitle(),
            messages = messages,
            pinned = existing?.pinned ?: false,
            manualTitle = existing?.manualTitle ?: false,
            updatedAt = System.currentTimeMillis(),
            projectId = existing?.projectId,
            assistantId = existing?.assistantId ?: assistantId,
            assistantSnapshot = if (replaceAssistantSnapshot) {
                assistantSnapshot ?: capturedSnapshot
            } else {
                capturedSnapshot
            },
            mixedAssistantHistory = existing?.mixedAssistantHistory ?: false,
            modelMode = modelMode ?: existing?.modelMode,
            modelId = if (modelMode != null) modelId else existing?.modelId,
            appearanceOverride = existing?.appearanceOverride,
            contextSummaries = existing?.contextSummaries.orEmpty()
        )
        return (listOf(record) + filterNot { it.id == sessionId }).sortedForHistory()
    }


    private fun MainUiState.currentChatModelId(): String? =
        when (selectedChatBackend) {
            ChatBackend.LOCAL -> loadedModelId
            ChatBackend.CLOUD -> selectedCloudChatModelId
        }

    private fun Throwable.imageLibraryBackupFailureMessage(): String = when (this) {
        is ImageLibraryBackupFormatException ->
            "备份文件格式无效：${message ?: "清单或文件校验失败"}"
        else -> "图片库备份失败：${message ?: "未知错误"}"
    }



    private fun List<ImageGenerationJobRecord>.updateImageJob(
        jobId: String,
        status: ImageGenerationStatusRecord,
        message: String,
        imageAssetId: String? = null,
        preview: PublishedLocalImagePreview? = null,
        imageAssetIds: List<String> = emptyList()
    ): List<ImageGenerationJobRecord> =
        map { job ->
            if (job.id == jobId) {
                job.withCommittedImageJobUpdate(
                    status = status,
                    message = message,
                    imageAssetId = imageAssetId ?: job.imageAssetId,
                    preview = preview,
                    imageAssetIds = imageAssetIds
                )
            } else {
                job
            }
        }

    private fun MainUiState.selectedChatCloudConfig(): CloudApiConfig? =
        cloudModels
            .firstOrNull { it.id == selectedCloudChatModelId && it.kind == CloudModelKind.CHAT }
            ?.toChatConfig()

    /** Secret-free identity for the exact Responses chat endpoint and model configuration. */
    private fun chatImageToolChatModelIdentity(
        modelId: String?,
        config: CloudApiConfig?
    ): String? {
        val normalizedId = modelId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = config?.normalized() ?: return null
        if (!normalized.configured || normalized.apiFormat != CloudApiFormat.OPENAI_RESPONSES ||
            !normalized.supportsTools
        ) return null
        return encodeChatImageAuthorizationIdentity(
            listOf(
                normalizedId,
                normalized.apiFormat.name,
                normalized.baseUrl,
                normalized.chatModel,
                normalized.supportsTools.toString(),
                normalized.responsesReasoningEnabled.toString()
            )
        )
    }

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

    private fun AssistantImageModelBinding.authorizationIdentity(): String =
        encodeChatImageAuthorizationIdentity(
            listOf(backend.name, modelId, fingerprint)
        )

    private fun encodeChatImageAuthorizationIdentity(parts: List<String>): String =
        parts.joinToString(separator = "\u0000") { value -> "${value.length}:$value" }

    private fun MainUiState.selectedImageCloudConfig(): CloudApiConfig? =
        cloudModels
            .firstOrNull { it.id == selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }
            ?.toImageConfig()

    private fun MainUiState.chatImageModelBinding(): AssistantImageModelBinding? {
        val state = this
        return when (selectedImageBackend) {
        ImageBackend.LOCAL -> selectedLocalImageModel()?.let { model ->
            AssistantImageModelBinding(
                backend = ImageBackend.LOCAL,
                modelId = model.id,
                modelName = model.displayName,
                options = chatImageOptionsForCurrentSelection(state),
                fingerprint = model.chatImageRetryFingerprint()
            )
        }
        ImageBackend.CLOUD -> selectedImageCloudConfig()?.let { config ->
            val modelId = selectedCloudImageModelId ?: return@let null
            AssistantImageModelBinding(
                backend = ImageBackend.CLOUD,
                modelId = modelId,
                modelName = config.displayName.ifBlank { config.imageModel },
                options = LocalImageGenerationOptions(batchCount = 1),
                fingerprint = config.chatImageRetryFingerprint()
            )
        }
        }
    }

    private fun MainUiState.selectedLocalImageModel(): LocalImageModelRecord? =
        localImageModels.firstOrNull { it.id == selectedLocalImageModelId && it.configured }

    /** Captures the selected image model's default conditioning for chat-triggered images. */
    internal fun chatImageOptionsForCurrentSelection(state: MainUiState): LocalImageGenerationOptions {
        val base = LocalImageGenerationOptions(batchCount = 1)
        if (state.selectedImageBackend != ImageBackend.LOCAL) return base
        // A null selection is a state the writers produce on purpose:
        // `reportMissingLocalImageLoraSelection()` and `updateChatImageLoraSelection()` both store
        // null to mean "the stored LoRA selection is no longer usable, ask the user to re-select".
        // Treating it as impossible crashed the app during composition (McaApp -> chatImageToolPermissionUiState).
        // Fall back to `base` (no LoRA conditioning) instead; the user-facing prompt lives in
        // `localImageLoraMessage` and is unaffected by this path.
        val selections = state.usableChatImageLoraSelections() ?: return base
        val resolvedLoras = resolveCurrentLocalImageLoras(selections, state.localImageLoras)
        val model = state.selectedLocalImageModel() ?: return base
        val bundleRoot = model.bundleRoot
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
            ?.takeIf(File::isDirectory)
            ?: File(model.path).parentFile?.takeIf(File::isDirectory)
        val defaultNegative = runCatching {
            val profile = resolveLocalImageExecutionProfile(
                model = model,
                options = base.normalizedForPromptExecutionProfile(model.runtime),
                bundleRoot = bundleRoot
            ).profile
            chatImageDefaultNegativePromptForProfile(profile)
        }.getOrElse {
            chatImageFallbackNegativePromptForModel(model.family, model.runtime)
        }
        return base.copy(negativePrompt = defaultNegative, loras = resolvedLoras)
    }


    private fun displayNameForUri(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val queriedName = if (uri.scheme.equals("content", ignoreCase = true)) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                }
            }.getOrNull()
        } else {
            null
        }
        return queriedName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "上传文件"
    }


    private fun isImageAttachment(uri: Uri, name: String): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val lowerName = name.lowercase()
        return mime.startsWith("image/") ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".heic") ||
            lowerName.endsWith(".heif")
    }

    private fun readAttachmentText(uri: Uri, maxBytes: Int = 64 * 1024): Pair<String, Boolean> {
        val resolver = getApplication<Application>().contentResolver
        val output = ByteArrayOutputStream()
        var truncated = false
        resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val remaining = maxBytes - total
                if (read > remaining) {
                    if (remaining > 0) output.write(buffer, 0, remaining)
                    truncated = true
                    break
                }
                output.write(buffer, 0, read)
                total += read
                if (total >= maxBytes) {
                    truncated = input.read() >= 0
                    break
                }
            }
        } ?: error("无法读取文件")
        return decodeImportedText(output.toByteArray()) to truncated
    }

    private fun createFileAsset(
        uri: Uri,
        displayName: String,
        text: String,
        truncated: Boolean,
        source: String,
        chatSessionId: String? = null
    ): FileAssetRecord =
        FileAssetRecord(
            id = UUID.randomUUID().toString(),
            name = displayName.ifBlank { "文件" },
            mimeType = mimeTypeForTextAttachment(getApplication<Application>().contentResolver, uri, displayName),
            text = text.trim(),
            preview = text.toFilePreview(),
            truncated = truncated,
            source = source,
            sizeBytes = sizeForUri(getApplication<Application>().contentResolver, uri).takeIf { it > 0L } ?: text.toByteArray(Charsets.UTF_8).size.toLong(),
            chatSessionId = chatSessionId
        )

    private suspend fun importImageAsset(
        uri: Uri,
        displayName: String,
        source: String,
        prompt: String = "",
        chatSessionId: String? = null
    ): ImageAssetRecord {
        val app = getApplication<Application>()
        val extension = imageExtension(displayName)
        val imported = imageAssetWriteMutex.withLock {
            val input = if (uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let { File(it).inputStream() }
            } else {
                app.contentResolver.openInputStream(uri)
            } ?: error("无法读取图片")
            input.use { sourceStream ->
                copyImageAssetStreamAtomically(
                    directory = imageAssetDirectory,
                    suggestedExtension = extension,
                    input = sourceStream
                )
            }
        }
        return ImageAssetRecord(
            id = UUID.randomUUID().toString(),
            name = displayName.ifBlank { "图片" },
            uriString = Uri.fromFile(imported.file).toString(),
            source = source,
            prompt = prompt,
            sizeBytes = imported.sizeBytes,
            width = imported.width,
            height = imported.height,
            chatSessionId = chatSessionId
        )
    }


    private suspend fun createCloudGeneratedImageAssets(
        request: CloudImageRequest,
        config: CloudApiConfig,
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata
    ): List<ImageAssetRecord> {
        val result = cloudImageProvider.generateBatch(config, request)
        currentCoroutineContext().ensureActive()
        return imageAssetWriteMutex.withLock {
            val writtenFiles = mutableListOf<File>()
            try {
                result.outputs.mapIndexed { index, output ->
                    currentCoroutineContext().ensureActive()
                    val extension = imageExtensionForMime(output.mimeType)
                    val outputFile = writeImageAssetBytesAtomically(
                        directory = imageAssetDirectory,
                        fileName = "cloud-image-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}-$index.$extension",
                        bytes = output.bytes
                    )
                    writtenFiles += outputFile
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                        "Cloud image $index could not be decoded after publication."
                    }
                    ImageAssetRecord(
                        id = UUID.randomUUID().toString(),
                        name = "Cloud Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}-$index.$extension",
                        uriString = Uri.fromFile(outputFile).toString(),
                        source = "generated:${config.imageApiFormat.label}",
                        prompt = output.revisedPrompt.ifBlank { request.positivePrompt },
                        sizeBytes = outputFile.length(),
                        width = bounds.outWidth,
                        height = bounds.outHeight,
                        generationMetadataJson = generationMetadata.toJsonString(),
                        chatSessionId = chatSessionId
                    )
                }
            } catch (error: Throwable) {
                writtenFiles.forEach { file -> runCatching { file.delete() } }
                throw error
            }
        }
    }

    /**
     * The ViewModel side of [CreateLocalGeneratedImageAssetPort]: one delegate per member, no logic
     * of its own. The publication decision moved to
     * [CreateLocalGeneratedImageAssetOperations.createLocalGeneratedImageAsset], which is now the
     * only place a worker result becomes a published image asset.
     *
     * `this@MainViewModel.` is spelled out on every forwarding member on purpose: the port members
     * share their names with the ViewModel members they forward to, so an unqualified call would
     * recurse into the override instead of reaching the original implementation.
     */
    private val createLocalGeneratedImageAssetPort: CreateLocalGeneratedImageAssetPort =
        object : CreateLocalGeneratedImageAssetPort {
            /** The same field `cancelActiveImageGeneration` observes, so it stays the port's own property. */
            override var activeLocalImageWorkerRequestId: String?
                get() = this@MainViewModel.activeLocalImageWorkerRequestId
                set(value) {
                    this@MainViewModel.activeLocalImageWorkerRequestId = value
                }

            override val localImageWorkerClient: LocalGeneratedImageWorkerGateway =
                object : LocalGeneratedImageWorkerGateway {
                    override suspend fun generate(
                        model: LocalImageModelRecord,
                        prompt: String,
                        options: LocalImageGenerationOptions,
                        inputDraft: LocalImageInputDraft,
                        requestId: String,
                        batchLineage: ImageGenerationBatchLineage?,
                        onProgress: (LocalImageProgress) -> Unit
                    ): LocalImageResult = this@MainViewModel.localImageWorkerClient.generate(
                        model = model,
                        prompt = prompt,
                        options = options,
                        inputDraft = inputDraft,
                        onProgress = onProgress,
                        requestId = requestId,
                        batchLineage = batchLineage
                    )
                }

            override fun recordLocalImageExecutionOutcome(
                model: LocalImageModelRecord,
                failureMessage: String?
            ) {
                this@MainViewModel.recordLocalImageExecutionOutcome(model, failureMessage)
            }

            override fun executionWithQnnVerificationEvidence(
                model: LocalImageModelRecord,
                result: LocalImageResult,
                options: LocalImageGenerationOptions,
                requestId: String,
                attemptId: String,
                entryPoint: QnnImageEvidenceEntryPoint
            ): String = this@MainViewModel.executionWithQnnVerificationEvidence(
                model = model,
                result = result,
                options = options,
                requestId = requestId,
                attemptId = attemptId,
                entryPoint = entryPoint
            )

            override val imageAssetWriteMutex: Mutex
                get() = this@MainViewModel.imageAssetWriteMutex

            override val imageAssetDirectory: File
                get() = this@MainViewModel.imageAssetDirectory
        }

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

    /**
     * The ViewModel side of [LocalApiImageGenerationPort]: one delegate per member, no logic of its
     * own. The protocol adaptation moved to [generateLocalApiImageOperations], which is now the only
     * place a Local API image request is decided.
     *
     * Every override keeps the `this@MainViewModel.` prefix on purpose: several port members share
     * their name and signature with the ViewModel member they forward to, and an unqualified call
     * would recurse into the override instead of reaching the original implementation.
     */
    private val localApiImageGenerationPort: LocalApiImageGenerationPort =
        object : LocalApiImageGenerationPort {
            override val uiState: StateFlow<MainUiState> get() = this@MainViewModel.uiState

            // Delegates to the real MutableStateFlow.update, so the CAS-based atomicity is kept.
            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun selectedLocalImageModel(): LocalImageModelRecord? =
                this@MainViewModel._uiState.value.selectedLocalImageModel()

            override val localImageLoraLifecycleLock: Any
                get() = this@MainViewModel.localImageLoraLifecycleLock

            override val localImageTextualInversionLifecycleLock: Any
                get() = this@MainViewModel.localImageTextualInversionLifecycleLock

            override var activeLocalApiImageModelId: String?
                get() = this@MainViewModel.activeLocalApiImageModelId
                set(value) {
                    this@MainViewModel.activeLocalApiImageModelId = value
                }

            override val localImageWorkerClient: LocalApiImageWorkerGateway =
                object : LocalApiImageWorkerGateway {
                    override suspend fun generate(
                        model: LocalImageModelRecord,
                        prompt: String,
                        options: LocalImageGenerationOptions,
                        inputDraft: LocalImageInputDraft,
                        requestId: String,
                        batchLineage: ImageGenerationBatchLineage?
                    ): LocalImageResult = this@MainViewModel.localImageWorkerClient.generate(
                        model = model,
                        prompt = prompt,
                        options = options,
                        inputDraft = inputDraft,
                        requestId = requestId,
                        batchLineage = batchLineage
                    )

                    override suspend fun awaitOwnedWorkerRelease() {
                        this@MainViewModel.localImageWorkerClient.awaitOwnedWorkerRelease()
                    }
                }

            override val localImageLoraStore: LocalApiImageLoraGateway =
                object : LocalApiImageLoraGateway {
                    override fun load(): List<LocalImageLoraRecord> =
                        this@MainViewModel.localImageLoraStore.load()
                }

            override fun tryAcquireObservedImageGenerationLease(
                requestId: String
            ): LocalImageGenerationCoordinator.Lease? =
                this@MainViewModel.tryAcquireObservedImageGenerationLease(requestId)

            override fun releaseObservedImageGenerationLease(
                lease: LocalImageGenerationCoordinator.Lease
            ): Boolean = this@MainViewModel.releaseObservedImageGenerationLease(lease)

            override fun registerLocalApiImageGenerationOwnership(
                ownership: LocalApiImageGenerationOwnership
            ) {
                this@MainViewModel.registerLocalApiImageGenerationOwnership(ownership)
            }

            override fun unregisterLocalApiImageGenerationOwnership(
                ownership: LocalApiImageGenerationOwnership
            ): Boolean = this@MainViewModel.unregisterLocalApiImageGenerationOwnership(ownership)

            override fun cancelOwnedLocalApiImageWorker(
                ownership: LocalApiImageGenerationOwnership
            ): Boolean = this@MainViewModel.cancelOwnedLocalApiImageWorker(ownership)

            override fun resolveCurrentLocalImageLoras(
                selections: List<Pair<String, Double>>
            ): List<LocalImagePreparedLora> =
                this@MainViewModel.resolveCurrentLocalImageLoras(selections)

            override fun resolveCurrentLocalImageLoras(
                selections: List<Pair<String, Double>>,
                records: List<LocalImageLoraRecord>
            ): List<LocalImagePreparedLora> =
                this@MainViewModel.resolveCurrentLocalImageLoras(selections, records)

            override fun resolveCurrentLocalImageTextualInversionIds(
                ids: List<String>
            ): List<String> =
                this@MainViewModel.resolveCurrentLocalImageTextualInversionIds(ids)

            override suspend fun prepareLocalImagePromptExecution(
                model: LocalImageModelRecord,
                prompt: String,
                options: LocalImageGenerationOptions,
                captured: LocalImagePromptExecution?
            ): LocalImagePromptExecution = this@MainViewModel.prepareLocalImagePromptExecution(
                model = model,
                prompt = prompt,
                options = options,
                captured = captured
            )

            override suspend fun prepareLocalImageRuntimeMemory():
                com.muyuchat.core.engine.EngineLifecycleLease? =
                this@MainViewModel.prepareLocalImageRuntimeMemory()

            override fun executionWithQnnVerificationEvidence(
                model: LocalImageModelRecord,
                result: LocalImageResult,
                options: LocalImageGenerationOptions,
                requestId: String,
                attemptId: String,
                entryPoint: QnnImageEvidenceEntryPoint
            ): String = this@MainViewModel.executionWithQnnVerificationEvidence(
                model = model,
                result = result,
                options = options,
                requestId = requestId,
                attemptId = attemptId,
                entryPoint = entryPoint
            )

            /**
             * Platform encoding seam. The production implementation is the two lines that were here
             * before the move, so the bytes this device produces for a response are unchanged; a plain
             * JVM unit test cannot reach them because the android.jar stubs throw, which is why they
             * are behind the port rather than inline.
             */
            override fun decodeImageBounds(bytes: ByteArray): LocalApiImageBounds {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                return LocalApiImageBounds(bounds.outWidth, bounds.outHeight)
            }

            /** Equivalent to `Base64.encodeToString(bytes, Base64.NO_WRAP)`. */
            override fun encodeBase64NoWrap(bytes: ByteArray): String =
                Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

    private suspend fun generateLocalApiImage(requestId: String, body: String): String =
        generateLocalApiImageOperations(
            requestId = requestId,
            body = body,
            port = localApiImageGenerationPort
        )

    /** Records actual worker execution only; this evidence is never an admission certificate. */
    private fun recordLocalImageExecutionOutcome(
        model: LocalImageModelRecord,
        failureMessage: String?
    ) {
        if (
            model.runtime != LocalImageRuntime.MNN_DIFFUSION &&
            model.runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP
        ) {
            return
        }
        val succeeded = failureMessage == null
        val current = localImageModelStore.loadModels().firstOrNull { it.id == model.id } ?: model
        val updated = current.copy(
            verificationStatus = if (succeeded) {
                LocalImageVerificationStatus.PASSED
            } else {
                LocalImageVerificationStatus.FAILED
            },
            verificationMessage = if (succeeded) {
                "真实图片生成执行成功。"
            } else {
                failureMessage.orEmpty().ifBlank { "本地 native 生图执行失败。" }
            },
            verifiedAt = System.currentTimeMillis()
        )
        val models = localImageModelStore.updateModel(updated)
        managedRuntimeReadinessRefreshGate.invalidate()
        uiStateStore.update { state -> state.copy(localImageModels = models) }
    }

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
    ): GenerationParams = GenerationPreferences.loadModelGenerationParams(
        profileStore = modelGenerationProfileStore,
        backend = backend,
        modelId = modelId,
        defaults = defaults
    )

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


    private fun loadRuntimeUserOverrideFields(application: Application): Set<String> =
        GenerationPreferences.loadRuntimeUserOverrideFields(application)


    private fun updatedAssistantsWithParams(params: GenerationParams): List<AssistantRecord> {
        val state = _uiState.value
        val selectedId = state.selectedAssistantId
        return state.assistants.map { assistant ->
            if (assistant.id == selectedId) {
                assistant.copy(
                    paramsJson = params.toAssistantGenerationJson()
                        .withAssistantPromptProvenance(assistant.systemPromptProvenance),
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                assistant
            }
        }
    }

    private fun applyAssistantDefaultModel(assistant: AssistantRecord) {
        val modelId = assistant.defaultModelId?.trim().orEmpty()
        when (assistant.defaultModelMode.normalizedAssistantModelMode()) {
            ASSISTANT_MODEL_MODE_CLOUD -> {
                if (modelId.isBlank()) {
                    showStatus("已切换助手：${assistant.name}，但该助手未绑定云端模型。")
                    return
                }
                val model = _uiState.value.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.CHAT }
                if (model == null) {
                    showStatus("已切换助手：${assistant.name}，但绑定的云端模型已不可用。")
                    return
                }
                selectCloudChatModel(model.id)
            }
            ASSISTANT_MODEL_MODE_LOCAL -> {
                if (modelId.isBlank()) {
                    showStatus("已切换助手：${assistant.name}，但该助手未绑定本地模型。")
                    return
                }
                val model = _uiState.value.models.firstOrNull { it.id == modelId }
                if (model == null) {
                    showStatus("已切换助手：${assistant.name}，但绑定的本地模型已不可用。")
                    return
                }
                if (_uiState.value.selectedChatBackend != ChatBackend.LOCAL || _uiState.value.loadedModelId != model.id) {
                    loadModel(model)
                }
            }
        }
    }

    private fun String.normalizedAssistantModelMode(): String =
        when (trim().lowercase()) {
            ASSISTANT_MODEL_MODE_LOCAL -> ASSISTANT_MODEL_MODE_LOCAL
            ASSISTANT_MODEL_MODE_CLOUD -> ASSISTANT_MODEL_MODE_CLOUD
            else -> ASSISTANT_MODEL_MODE_FOLLOW_CURRENT
        }

    private fun AssistantRecord.toGenerationParams(defaults: GenerationParams): GenerationParams =
        assistantGenerationParamsFromJson(paramsJson, defaults, systemPrompt)

    private fun MainUiState.activeAssistantSnapshot(): AssistantConversationSnapshot? =
        activeChatSessionId
            ?.let { sessionId -> chatSessions.firstOrNull { it.id == sessionId } }
            ?.assistantSnapshot

    private fun MainUiState.canWriteToActiveAssistantSession(): Boolean {
        val sessionId = activeChatSessionId ?: return false
        val session = chatSessions.firstOrNull { it.id == sessionId } ?: return false
        val owner = session.assistantId ?: return false
        return !session.mixedAssistantHistory && owner == selectedAssistantId &&
            session.assistantSnapshot?.assistantId == owner &&
            assistants.any { it.id == owner }
    }

    private fun MainUiState.selectedAssistant(): AssistantRecord? =
        assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()

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

    /**
     * The ViewModel side of [VisionAttachmentPort]. This interface is the Android adapter seam for
     * vision preparation: the `ContentResolver`, the cache directory and the engine's native stats are
     * the four things the moved functions genuinely need from their owner.
     */
    private val visionAttachmentPort: VisionAttachmentPort =
        object : VisionAttachmentPort {
            override val context: Context
                get() = this@MainViewModel.getApplication()

            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun nativeStatsJson(): String = this@MainViewModel.engine.nativeStatsJson()

            override val activeRuntimeIdentity: ModelRuntimeIdentity?
                get() = this@MainViewModel.activeRuntimeIdentity

            override val activeVisionProjectorStamp: LoadedVisionProjectorStamp?
                get() = this@MainViewModel.activeVisionProjectorStamp
        }

    /** Delegates to [visionMimeTypeForUri]; the Context read stays on the owner side. */
    private fun mimeTypeForUri(uriString: String): String =
        visionMimeTypeForUri(visionAttachmentPort, uriString)








    private fun currentOpenApiAddress(): String =
        apiUrl(detectLanIpAddress() ?: "本机局域网IP")


    private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
        while (hasMoreElements()) yield(nextElement())
    }


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
    ): Long {
        // Stability smoke reloads must use the same identity/profile as the
        // formal model-page load. Falling back to McaInferenceService's legacy
        // identity builder here would key the reload by mtime/length and make
        // a tuning candidate appear to belong to a different model.
        val activeProfile = engine.activeExecutionProfile()
            ?.takeIf {
                it.runtimeIdentity.modelId == model.id &&
                    it.runtimeIdentity.runtime == runtime
            }
        val activeIdentity = activeRuntimeIdentity
            ?.takeIf {
                it.modelId == model.id && it.runtime == runtime
            }
        val identity = activeIdentity
            ?: activeProfile?.runtimeIdentity
            ?: RuntimeIdentityFactory.create(
                context = getApplication(),
                model = model,
                runtime = runtime,
                device = currentDeviceProfile(),
                installationScopeId = installationScopeId,
                qairtAdmissionPassed = model.runtime == ChatModelRuntime.GENIEX_QAIRT &&
                    model.id in currentQairtVerifiedLocalModelIds(getApplication(), modelStore.listModels())
            )
        val profile = activeProfile
            ?.takeIf { it.runtimeIdentity.identityHash == identity.identityHash }
        val started = android.os.SystemClock.elapsedRealtime()
        engine.loadModel(
            modelPath = model.path,
            runtime = runtime,
            params = loadParams,
            qairtBundleSha256 = currentQairtBundleSha256(
                requested = model,
                persistedModels = modelStore.listModels()
            ),
            runtimeIdentity = identity,
            executionProfile = profile
        ).getOrThrow()
        return (android.os.SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
    }

    private suspend fun runLocalGenerationSmoke(params: GenerationParams): LocalGenerationSmokeResult {
        val smokeParams = params.copy(
            nPredict = 32,
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true,
            systemPrompt = "你是 MCA 稳定性自检助手。请只输出简短中文结果。"
        )
        val text = StringBuilder()
        var finalStats = RuntimeStats()
        var errorMessage: String? = null
        withTimeout(60_000L) {
            engine.streamChat(
                ChatRequest(
                    messages = listOf(ChatMessage(Role.USER, "用一句中文回答：稳定性自检完成。")),
                    params = smokeParams
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Phase -> finalStats = event.stats
                    is GenerateEvent.Persist -> Unit
                    is GenerateEvent.Chunk -> {
                        text.append(event.text)
                        finalStats = event.stats
                    }
                    is GenerateEvent.Done -> finalStats = event.stats
                    is GenerateEvent.Error -> errorMessage = event.message
                }
            }
        }
        errorMessage?.let { error(it) }
        if (text.isBlank()) error("短流式生成没有产生可见正文")
        return LocalGenerationSmokeResult(
            visibleChars = text.length,
            completionTokens = finalStats.completionTokens,
            decodeTps = finalStats.decodeTps
        )
    }

    private suspend fun runLoopbackApiStreamSmoke(): LocalApiStreamSmokeResult = withContext(Dispatchers.IO) {
        val previousStreamProvider = LocalApiRuntime.streamChatProvider
        val previousStopProvider = LocalApiRuntime.stopGenerationProvider
        val stopCalls = AtomicInteger(0)
        val port = freeLoopbackPort()
        val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = "")
        try {
            LocalApiRuntime.streamChatProvider = {
                flowOf(
                    GenerateEvent.Chunk(
                        text = "",
                        stats = RuntimeStats(completionTokens = 128),
                        hiddenReasoning = true
                    ),
                    GenerateEvent.Chunk(
                        text = "visible answer",
                        stats = RuntimeStats(completionTokens = 130)
                    ),
                )
            }
            LocalApiRuntime.stopGenerationProvider = { stopCalls.incrementAndGet() }
            server.start()
            val body = """{"messages":[{"role":"user","content":"hi"}],"stream":true,"hide_reasoning":true}"""
            val response = rawHttp(
                port = port,
                request = "POST /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                    body
            )
            val visibleSeen = response.contains("visible answer")
            val doneSeen = response.contains("data: [DONE]")
            if (!response.startsWith("HTTP/1.1 200 OK")) error("本机 API smoke 返回异常")
            if (!visibleSeen) error("本机 API smoke 未收到可见正文")
            if (!doneSeen) error("本机 API smoke 未收到 [DONE] 终止帧")
            if (response.contains("[MCA 提示]")) error("本机 API smoke 仍出现隐藏思考占位提示")
            if (stopCalls.get() != 0) error("本机 API smoke 触发了 stopGeneration")
            LocalApiStreamSmokeResult(
                visibleContentSeen = true,
                doneSeen = true,
                stopCalls = stopCalls.get()
            )
        } finally {
            server.shutdown()
            LocalApiRuntime.streamChatProvider = previousStreamProvider
            LocalApiRuntime.stopGenerationProvider = previousStopProvider
        }
    }

    private suspend fun runLoopbackEngineStreamSmoke(params: GenerationParams): LocalApiStreamSmokeResult = withContext(Dispatchers.IO) {
        val port = freeLoopbackPort()
        val server = McaLoopbackServer(port = port, bindHost = "127.0.0.1", apiKey = "")
        try {
            server.start()
            val body = JSONObject()
                .put("model", "mca-local")
                .put("stream", true)
                .put("hide_reasoning", true)
                .put("reasoning_mode", "off")
                .put("max_tokens", 32)
                .put("n_ctx", params.nCtx)
                .put("temperature", 0)
                .put(
                    "messages",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "用一句中文回答：本机 API 实流自检完成。")
                    )
                )
                .toString()
            val response = withTimeout(90_000L) {
                rawHttp(
                    port = port,
                    request = "POST /v1/chat/completions HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n\r\n" +
                        body,
                    readTimeoutMs = 90_000
                )
            }
            if (!response.startsWith("HTTP/1.1 200 OK")) {
                error("本机 API 实流 smoke 返回异常：${response.lineSequence().firstOrNull().orEmpty()}")
            }
            if (!response.contains("data: [DONE]")) error("本机 API 实流 smoke 未收到 [DONE] 终止帧")
            if (response.contains("[MCA 提示]")) error("本机 API 实流 smoke 仍出现隐藏思考占位提示")
            val visibleSeen = Regex(""""content"\s*:\s*"(?!")""").containsMatchIn(response)
            if (!visibleSeen) error("本机 API 实流 smoke 未收到可见正文")
            LocalApiStreamSmokeResult(
                visibleContentSeen = true,
                doneSeen = true,
                stopCalls = 0
            )
        } finally {
            server.shutdown()
        }
    }

    private fun freeLoopbackPort(): Int =
        ServerSocket(0).use { it.localPort }

    private fun rawHttp(port: Int, request: String, readTimeoutMs: Int = 15_000): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = readTimeoutMs
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun buildDiagnosticReport(): String {
        val state = _uiState.value
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        val device = currentDeviceProfile()
        val nativeStats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("time", System.currentTimeMillis())
            .put("startup", StartupDiagnostics.read(getApplication()))
            .put("loadedModelId", state.loadedModelId)
            .put("loadedModelName", state.loadedModelName)
            .put("modelPath", loadedModel?.path ?: state.stats.modelPath)
            .put("modelSizeBytes", loadedModel?.sizeBytes ?: 0L)
            .put("modelSha256", loadedModel?.sha256)
            .put("modelQuant", loadedModel?.quant)
            .put("modelArchitecture", loadedModel?.architecture)
            .put("backendReady", nativeStats.optBoolean("backendReady"))
            .put("backendDeviceCount", nativeStats.optInt("backendDeviceCount"))
            .put("nativeLibDir", nativeStats.optString("nativeLibDir"))
            .put("nativeLastError", nativeStats.optString("lastError"))
            .put("runtimeStats", nativeStats)
            .put(
                "androidMemory",
                JSONObject()
                    .put("processPssKb", state.stats.nativePssKb)
                    .put("processRssKb", state.stats.processRssKb)
                    .put("nativeHeapKb", state.stats.nativeHeapKb)
                    .put("nativeHeapSizeKb", state.stats.nativeHeapSizeKb)
                    .put("javaHeapKb", state.stats.javaHeapKb)
                    .put("availMemKb", state.stats.availMemKb)
            )
            .put("deviceProfile", device.toJson())
            .put("params", JSONObject(state.params.toJson()))
            .put("lastBenchmark", state.benchmark?.toJson())
            .put("benchmarkHistory", JSONArray(state.benchmarkHistory.take(10).map { it.toJson() }))
            .put("lastAutoTuningSummary", state.lastAutoTuningSummary)
            .put("localStabilitySmokeSummary", state.localStabilitySmokeSummary)
            .toString(2)
    }

    private fun appendBenchmarkHistory(
        device: DeviceProfile,
        result: BenchmarkResult,
        params: GenerationParams
    ) {
        val state = _uiState.value
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        benchmarkHistoryLogger.append(
            BenchmarkHistoryRecord(
                modelId = loadedModel?.id ?: state.loadedModelId,
                modelName = loadedModel?.displayName ?: state.loadedModelName,
                modelPath = loadedModel?.path ?: state.stats.modelPath,
                deviceSummary = "${device.socFamily.name} · ${device.socLabel}",
                paramsJson = params.toJson(),
                result = result
            )
        )
    }

    private suspend fun recoverInterruptedRuntimeProfiles() {
        runCatching {
            val plans = runtimeProfileStore.recoverInterruptedTransactions()
            synchronized(pendingRuntimeRecoveries) {
                pendingRuntimeRecoveries.clear()
                plans.forEach { plan -> pendingRuntimeRecoveries[plan.identityKey] = plan }
            }
            runtimeProfileStore.pruneRejectedProfiles()
            runtimeProfileStore.pruneTerminalHistory()
            if (plans.isNotEmpty()) {
                uiStateStore.update { state ->
                    state.copy(
                        tuningJobState = AgentTuningJobState.RECOVERING,
                        engineLifecycle = if (state.loadedModelId == null) {
                            AgentEngineLifecycle.UNLOADED
                        } else {
                            state.engineLifecycle
                        },
                        tuningPhase = "等待对应模型加载后恢复 LKG",
                        statusMessage = "检测到 ${plans.size} 个中断的调参事务；加载对应模型时将最多回滚一次。"
                    )
                }
            }
        }.onFailure { error ->
            uiStateStore.update { state ->
                state.copy(
                    tuningJobState = AgentTuningJobState.FAILED,
                    tuningPhase = "启动恢复检查失败",
                    statusMessage = "运行配置恢复检查失败：${error.message ?: "未知错误"}"
                )
            }
        }
    }

    private fun modelTuningCapabilities(
        model: ModelManifest,
        identity: ModelRuntimeIdentity,
        qairtAdmissionPassed: Boolean
    ): ModelTuningCapabilities = discoverModelTuningCapabilities(
        model = model,
        identity = identity,
        qairtAdmissionPassed = qairtAdmissionPassed
    )

    private fun buildAdaptiveTuningRecommendation(
        model: ModelManifest,
        identity: ModelRuntimeIdentity,
        device: DeviceProfile,
        capabilities: ModelTuningCapabilities,
        preference: UserPreference = _uiState.value.preference
    ): AdaptiveTuningRecommendation {
        return advisor.recommendAdaptiveForModel(
            device = device,
            model = model,
            runtimeIdentity = identity,
            capabilities = capabilities,
            preference = preference,
            lastDecodeTps = null
        )
    }

    private suspend fun persistBootstrapProfile(
        profile: ModelExecutionProfile,
        sourceSummary: String
    ) {
        val existing = runtimeProfileStore.profile(profile.profileId)
        if (existing?.recordState == PersistedProfileRecordState.COMMITTED.name) return
        val transactionId = "bootstrap-formal-${UUID.randomUUID()}"
        val current = runtimeProfileStore.currentRuntimeState(profile.runtimeIdentity.identityHash)
        val rollbackTargetProfileId = current?.activeProfile
            ?.takeIf {
                it.recordState == PersistedProfileRecordState.COMMITTED.name &&
                    it.profileId != profile.profileId
            }
            ?.profileId
        val snapshot = profile.toPersistedExecutionProfileSnapshot(
            parentCommittedProfileId = rollbackTargetProfileId,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            sourceSummaryJson = sourceSummary
        )
        runtimeProfileStore.stageCandidate(
            snapshot = snapshot,
            transactionId = transactionId,
            rollbackTargetProfileId = rollbackTargetProfileId
        )
        runtimeProfileStore.updateJournalStage(
            transactionId,
            TuningJournalState.VALIDATING,
            "FORMAL_LOAD_VALIDATED"
        )
        val signatures = engine.parameterSignatureSnapshot()
            ?: error("安全基线缺少参数签名快照")
        require(profile.matchesExactParameterSignatures(signatures)) {
            "正式加载参数签名与待提交 profile 不一致"
        }
        runtimeProfileStore.commitCandidate(
            transactionId = transactionId,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            activeLoadedSignature = requireNotNull(signatures.active).digest,
            effectiveExecutionSignature = requireNotNull(signatures.effective).digest
        )
    }

    private suspend fun stageBootstrapProfile(
        transactionId: String,
        profile: ModelExecutionProfile,
        rollbackTargetProfileId: String?
    ) {
        runtimeProfileStore.stageCandidate(
            snapshot = profile.toPersistedExecutionProfileSnapshot(
                parentCommittedProfileId = rollbackTargetProfileId,
                verificationLevel = PersistedProfileVerificationLevel.SAFE,
                sourceSummaryJson = JSONObject()
                    .put("kind", "bootstrap_load")
                    .put("probeKind", TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD.name)
                    .put("profileId", profile.profileId)
                    .put("resolvedLoadSignature", profile.resolvedLoadSignature.digest)
                    .put("committedExecutionSignature", profile.committedExecutionSignature.digest)
                    .toString()
            ),
            transactionId = transactionId,
            rollbackTargetProfileId = rollbackTargetProfileId,
            job = null
        )
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.APPLYING,
            stage = "BOOTSTRAP_WORKER_PENDING"
        )
    }

    private suspend fun recordBootstrapProbeMeasurement(
        result: TuningProbeWorkerProtocol.Result
    ) {
        require(result.probeKind == TuningProbeWorkerProtocol.ProbeKind.BOOTSTRAP_LOAD)
        runtimeProfileStore.recordMeasurement(
            CandidateMeasurementEntity(
                measurementId = "bootstrap-${result.requestId}",
                profileId = result.profileId,
                jobId = null,
                correctnessPassed = result.passed,
                safetyPassed = !result.lowMemoryTriggered,
                effectiveSignatureMatched = result.signatureMatched,
                accepted = result.passed && result.signatureMatched,
                metricsJson = JSONObject(result.evidenceJson)
                    .put("runtimeStats", JSONObject(result.runtimeStatsJson))
                    .put("elapsedMs", result.elapsedMs)
                    .toString(),
                failureCode = if (result.passed) null else "BOOTSTRAP_WORKER_REJECTED",
                createdAt = System.currentTimeMillis()
            )
        )
    }


    private suspend fun runIsolatedTuningCandidateCanary(
        candidate: TuningExecutionProfile,
        committed: ModelExecutionProfile,
        model: ModelManifest,
        jobId: String,
        stage: String,
        onProgress: (TuningProbeWorkerProtocol.Progress) -> Unit
    ): CandidateCanaryResult {
        require(candidate.identityHash == committed.runtimeIdentity.identityHash) {
            "隔离探测候选与 committed profile 身份不一致"
        }
        val probeStart = measurementPoint()
        val transactionId = "probe-$jobId-${UUID.randomUUID()}"
        val snapshot = candidate.engineProfile.toPersistedExecutionProfileSnapshot(
            parentCommittedProfileId = committed.profileId,
            verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
            sourceSummaryJson = JSONObject()
                .put("kind", "isolated_load_bound_probe")
                .put("probeKind", TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE.name)
                .put("jobId", jobId)
                .put("stage", stage)
                .put("profileId", candidate.profileId)
                .put("resolvedLoadSignature", candidate.engineProfile.resolvedLoadSignature.digest)
                .put("committedExecutionSignature", candidate.engineProfile.committedExecutionSignature.digest)
                .toString()
        )
        runtimeProfileStore.stageCandidate(
            snapshot = snapshot,
            transactionId = transactionId,
            rollbackTargetProfileId = committed.profileId,
            job = null
        )
        runtimeProfileStore.updateJournalStage(
            transactionId = transactionId,
            state = TuningJournalState.APPLYING,
            stage = "MAIN_UNLOAD_BEFORE_ISOLATED_PROBE"
        )

        var workerResult: TuningProbeWorkerProtocol.Result? = null
        var failure: Throwable? = null
        var restored = false
        try {
            // Do not keep a second 35B native mapping in the app process while :tuning loads the
            // candidate. unloadModel() also stops and joins any in-flight UI/API generation.
            engine.unloadModel()
            workerResult = TuningProbeWorkerClient(getApplication<Application>()).probe(
                probeKind = TuningProbeWorkerProtocol.ProbeKind.TUNING_CANDIDATE,
                transactionId = transactionId,
                identityKey = candidate.identityHash,
                modelId = model.id,
                profileId = candidate.profileId,
                resolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
                committedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
                onProgress = onProgress
            )
        } catch (error: CancellationException) {
            // withTimeout uses CancellationException for a candidate-local timeout even while the
            // parent tuning job remains active. Only a real caller/job cancellation may abort the
            // whole search; a timed-out disposable worker is simply an ineligible candidate.
            currentCoroutineContext().ensureActive()
            failure = error
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                restored = runCatching { restoreExactRuntimeProfile(model, committed) }.getOrDefault(false)
                if (restored) {
                    val result = workerResult
                    val summary = when {
                        failure != null -> failure?.message ?: "isolated probe failed"
                        result == null -> "isolated probe returned no evidence"
                        result.passed -> "isolated probe passed; committed profile restored"
                        else -> result.detail
                    }
                    runtimeProfileStore.completeIsolatedProbe(
                        transactionId = transactionId,
                        passed = result?.passed == true,
                        summary = summary
                    )
                }
            }
        }
        check(restored) {
            "隔离探测结束后无法恢复 committed profile；pending journal 已保留供进程恢复"
        }
        failure?.let { error ->
            return failedIsolatedTuningCandidateResult(candidate, probeStart, error)
        }
        return requireNotNull(workerResult).canaryResultFrom(port = tuningCanaryPort, candidate = candidate)
    }

    /**
     * The ViewModel side of [TuningCanaryPort]. The canary result port object stays here: it is the
     * adapter that binds that interface to this ViewModel.
     */
    private val tuningCanaryPort: TuningCanaryPort =
        object : TuningCanaryPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override fun updateUiState(transform: (MainUiState) -> MainUiState) {
                this@MainViewModel.uiStateStore.update(transform)
            }

            override fun currentDeviceProfile(): DeviceProfile =
                this@MainViewModel.currentDeviceProfile()

            override val candidateCanaryResultPort: CandidateCanaryResultPort
                get() = this@MainViewModel.candidateCanaryResultPort

            override val engine: McaInferenceService
                get() = this@MainViewModel.engine
        }


/** Delegates to [failedIsolatedCanaryResult]. Signature kept verbatim. */
    private fun failedIsolatedTuningCandidateResult(
        candidate: TuningExecutionProfile,
        start: MeasurementPoint,
        error: Throwable
    ): CandidateCanaryResult {
        return failedIsolatedCanaryResult(port = tuningCanaryPort, candidate = candidate, start = start, error = error)
    }


/** Delegates to [jsonOptionalFiniteDouble]. Signature kept verbatim. */
    private fun JSONObject.optionalFiniteDouble(field: String): Double {
        return jsonOptionalFiniteDouble(port = tuningCanaryPort, field = field)
    }

/** Delegates to [runCanary]. Signature kept verbatim. */
    private suspend fun runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: com.muyuchat.core.tuning.CanaryEvaluationParams,
        authorization: com.muyuchat.core.engine.LoadAuthorization,
        disposition: com.muyuchat.core.engine.PendingProfileDisposition =
            com.muyuchat.core.engine.PendingProfileDisposition.ROLLBACK_AFTER_REQUEST,
        lifecycleLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
    ): CandidateCanaryResult {
        return runCanary(port = tuningCanaryPort, candidate = candidate, canaryParams = canaryParams, authorization = authorization, disposition = disposition, lifecycleLease = lifecycleLease)
    }



/** Delegates to [canaryMeasurement]. Signature kept verbatim. */
    private fun candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity {
        return canaryMeasurement(port = tuningCanaryPort, profileId = profileId, jobId = jobId, phase = phase, result = result)
    }

/** Delegates to [asTuningProfile]. Signature kept verbatim. */
    private fun ModelExecutionProfile.asTuningExecutionProfile(
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile {
        return asTuningProfile(port = tuningCanaryPort, kind = kind, verificationLevel = verificationLevel, reason = reason)
    }

/** Delegates to [currentMeasurementPoint]. Signature kept verbatim. */
    private fun measurementPoint(device: DeviceProfile = currentDeviceProfile()): MeasurementPoint {
        return currentMeasurementPoint(port = tuningCanaryPort, device = device)
    }

    /**
     * The ViewModel side of [CandidateCanaryResultPort]: two members, no logic of its own. The
     * conversion moved to [toCandidateCanaryResult], which is now the only place a probe result
     * becomes a canary verdict.
     *
     * `this@MainViewModel.` is spelled out on purpose: the port member shares its name with the
     * ViewModel member it forwards to.
     */
    private val candidateCanaryResultPort: CandidateCanaryResultPort =
        object : CandidateCanaryResultPort {
            override fun currentDeviceProfile(): DeviceProfile =
                this@MainViewModel.currentDeviceProfile()

            override fun optionalFiniteDouble(root: JSONObject, field: String): Double =
                root.optionalFiniteDouble(field)
        }








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
    ): com.muyuchat.api.local.LocalApiControlResult = synchronized(localApiIdempotencyJournalLock) {
        if (idempotency.key.isBlank() || idempotency.requestFingerprint.isBlank()) {
            return@synchronized localApiTuningRejected(
                httpStatus = 400,
                code = "invalid_idempotency_context",
                message = "Idempotency-Key 或请求指纹无效。"
            )
        }
        val preferences = getApplication<Application>().getSharedPreferences(
            "mca_local_api_idempotency_journal",
            Context.MODE_PRIVATE
        )
        val storageKey = "entry_${localApiIdempotencyKeyHash(idempotency.key)}"
        preferences.getString(storageKey, null)?.let { persisted ->
            val entry = runCatching { JSONObject(persisted) }.getOrNull()
                ?: return@synchronized localApiTuningRejected(
                    409,
                    "idempotency_journal_corrupt",
                    "该 Idempotency-Key 的持久记录不可读；为避免重复执行，已拒绝重放。"
                )
            if (entry.optString("requestFingerprint") != idempotency.requestFingerprint) {
                return@synchronized localApiTuningRejected(
                    409,
                    "idempotency_key_conflict",
                    "同一 Idempotency-Key 不能用于不同请求。"
                )
            }
            if (entry.optString("state") == "completed") {
                return@synchronized decodeDurableLocalApiResult(entry)
            }
            return@synchronized localApiTuningRejected(
                409,
                "idempotency_recovery_required",
                "该写操作曾开始但未持久化最终响应；请先查询任务/profile 状态，系统不会自动重复执行。"
            )
        }
        val pending = JSONObject()
            .put("formatVersion", 1)
            .put("requestFingerprint", idempotency.requestFingerprint)
            .put("state", "pending")
            .put("updatedAt", System.currentTimeMillis())
        if (!preferences.edit().putString(storageKey, pending.toString()).commit()) {
            return@synchronized localApiTuningRejected(
                503,
                "idempotency_journal_unavailable",
                "无法持久化幂等事务，写操作未执行。"
            )
        }
        val result = runCatching(operation).getOrElse {
            com.muyuchat.api.local.LocalApiControlResult.Rejected(
                httpStatus = 500,
                code = "control_plane_failure",
                message = "本地运行时控制面执行失败。"
            )
        }
        val completed = encodeDurableLocalApiResult(idempotency.requestFingerprint, result)
        // The pending marker is already durable. If this replacement fails, returning the actual
        // response is safe; a later retry fails closed with recovery_required instead of mutating
        // the runtime twice.
        preferences.edit().putString(storageKey, completed.toString()).commit()
        result
    }

    private fun encodeDurableLocalApiResult(
        requestFingerprint: String,
        result: com.muyuchat.api.local.LocalApiControlResult
    ): JSONObject = JSONObject()
        .put("formatVersion", 1)
        .put("requestFingerprint", requestFingerprint)
        .put("state", "completed")
        .put("updatedAt", System.currentTimeMillis())
        .apply {
            when (result) {
                is com.muyuchat.api.local.LocalApiControlResult.Success -> {
                    put("resultType", "success")
                    put("httpStatus", result.httpStatus)
                    put("retryAfterMs", result.retryAfterMs)
                    put("json", result.json)
                }
                is com.muyuchat.api.local.LocalApiControlResult.Rejected -> {
                    put("resultType", "rejected")
                    put("httpStatus", result.httpStatus)
                    put("retryAfterMs", result.retryAfterMs)
                    put("code", result.code)
                    put("message", result.message)
                    put("detailsJson", result.detailsJson)
                }
            }
        }

    private fun decodeDurableLocalApiResult(
        entry: JSONObject
    ): com.muyuchat.api.local.LocalApiControlResult = when (entry.optString("resultType")) {
        "success" -> com.muyuchat.api.local.LocalApiControlResult.Success(
            json = entry.optString("json", "{}"),
            httpStatus = entry.optInt("httpStatus", 200),
            retryAfterMs = entry.optLong("retryAfterMs", 0L).coerceAtLeast(0L)
        )
        "rejected" -> com.muyuchat.api.local.LocalApiControlResult.Rejected(
            httpStatus = entry.optInt("httpStatus", 409),
            code = entry.optString("code", "control_plane_rejected"),
            message = entry.optString("message", "本地运行时控制面拒绝了该请求。"),
            detailsJson = entry.optString("detailsJson", "{}"),
            retryAfterMs = entry.optLong("retryAfterMs", 0L).coerceAtLeast(0L)
        )
        else -> localApiTuningRejected(
            409,
            "idempotency_journal_corrupt",
            "该 Idempotency-Key 的最终响应记录不可读；为避免重复执行，已拒绝重放。"
        )
    }

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
    private val localApiPayloadPort: LocalApiPayloadPort =
        object : LocalApiPayloadPort {
            override fun uiState(): MainUiState = this@MainViewModel._uiState.value

            override val activeTuningJobId: String?
                get() = this@MainViewModel.activeTuningJobId

            override fun tuningCancelRequested(): Boolean =
                this@MainViewModel.adaptiveTuningCancelRequested.get()

            override fun tuningPauseRequested(): Boolean =
                this@MainViewModel.adaptiveTuningPauseRequested.get()

            override fun runtimeProfileJson(): String = runtimeProfileJsonFor(this@MainViewModel._uiState.value, this@MainViewModel.engine, this@MainViewModel.activeRuntimeIdentity)

            override fun nativeStatsJson(): String = this@MainViewModel.engine.nativeStatsJson()

            override fun runtimeLoaded(): Boolean = this@MainViewModel.engine.stats.value.loaded

            override suspend fun modelRuntimeSummaries(
                modelIds: Collection<String>
            ): List<PersistedModelRuntimeSummary> =
                this@MainViewModel.runtimeProfileStore.modelRuntimeSummaries(modelIds)
        }




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












    private fun sortRecommendedModels(
        models: List<ModelScopeRecommendedModel>,
        device: DeviceProfile
    ): List<ModelScopeRecommendedModel> {
        return models.filter { it.visibleInRecommendations }.sortedWith(
            compareBy<ModelScopeRecommendedModel> { model ->
                model.group.ordinal
            }.thenBy { model ->
                model.priority
            }.thenBy { model ->
                model.id
            }
        )
    }

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
