package com.muyuchat.mca

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.os.Build
import android.util.Base64
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
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RemoteModelFileKind
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.download.downloadEligibilityFor
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
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.AuthorizedPendingSignatureVerification
import com.muyuchat.core.engine.CanonicalParameterSet
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.LocalChatExecutionContext
import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.ModelRuntimeIdentity
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ParameterSignatureSnapshot
import com.muyuchat.core.engine.RuntimeOverrideSignature
import com.muyuchat.core.engine.CompletionPreflight
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.QairtExecutionVerificationStore
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
import com.muyuchat.core.tuning.TuningSearchDepth
import com.muyuchat.feature.agent.AgentCandidateProgress
import com.muyuchat.feature.agent.AgentEngineLifecycle
import com.muyuchat.feature.agent.AgentPendingProfile
import com.muyuchat.feature.agent.AgentProfileRecordState
import com.muyuchat.feature.agent.AgentProfileVerification
import com.muyuchat.feature.agent.AgentRollbackProfile
import com.muyuchat.feature.agent.AgentTuningMode
import com.muyuchat.feature.agent.AgentTuningJobState
import com.muyuchat.core.telemetry.SocFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import java.util.zip.ZipInputStream

enum class AppTab(val title: String) {
    CHAT("聊天"),
    AGENT("智能调参"),
    MODELS("模型管理"),
    API("本地 API"),
    SETTINGS("系统设置")
}

data class ChatSessionRecord(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val pinned: Boolean = false,
    val manualTitle: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val projectId: String? = null,
    val assistantId: String? = null,
    val modelMode: String? = null,
    val modelId: String? = null
)

data class MemoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val assistantId: String = AssistantRecord.DEFAULT_ID,
    val scope: String = "assistant",
    val content: String,
    val source: String = "manual",
    val createdAt: Long = System.currentTimeMillis()
)

data class ImageAssetRecord(
    val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val generationMetadataJson: String = "",
    val chatSessionId: String? = null,
    val projectId: String? = null
) {
    fun toInputAttachment(): String = buildString {
        append("【上传图片：").append(name).append("】\n")
        if (prompt.isNotBlank()) {
            append("描述：").append(prompt.trim()).append("\n")
        }
        append(uriString)
    }

    fun toChatAttachment(mimeType: String = "image/jpeg"): ChatImageAttachment =
        ChatImageAttachment(
            name = name,
            uriString = uriString,
            mimeType = mimeType,
            width = width,
            height = height,
            sizeBytes = sizeBytes
        )

    fun deleteLocalCopy() {
        runCatching {
            val uri = Uri.parse(uriString)
            if (uri.scheme.equals("file", ignoreCase = true)) {
                uri.path?.let { File(it).delete() }
            }
        }
    }
}

data class FileAssetRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mimeType: String = "text/plain",
    val text: String,
    val preview: String = text.toFilePreview(),
    val truncated: Boolean = false,
    val source: String = "uploaded",
    val createdAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = text.toByteArray(Charsets.UTF_8).size.toLong(),
    val chatSessionId: String? = null,
    val projectId: String? = null
) {
    fun toInputAttachment(): String = buildString {
        append("【上传文件：").append(name).append("】\n")
        append(text.trim())
        if (truncated) append("\n\n（文件较大，已截取前 64KB）")
    }
}

enum class ImageGenerationStatusRecord(
    val label: String,
    val failed: Boolean = false,
    val terminal: Boolean = false
) {
    QUEUED("排队"),
    GENERATING("生成中"),
    DONE("完成", terminal = true),
    CANCELLED("已取消", terminal = true),
    FAILED("失败", failed = true, terminal = true)
}

data class ImageGenerationJobSpec(
    val prompt: String,
    val backend: ImageBackend,
    val localModelSnapshot: LocalImageModelRecord? = null,
    val cloudConfigSnapshot: CloudApiConfig? = null,
    val modelId: String,
    val modelName: String,
    val inputDraft: LocalImageInputDraft,
    val options: LocalImageGenerationOptions,
    val chatSessionId: String? = null
) {
    init {
        require(prompt.isNotBlank()) { "Image generation job prompt must not be blank." }
        require(modelId.isNotBlank()) { "Image generation job modelId must not be blank." }
        require(modelName.isNotBlank()) { "Image generation job modelName must not be blank." }
        require(
            (backend == ImageBackend.LOCAL && localModelSnapshot != null && cloudConfigSnapshot == null) ||
                (backend == ImageBackend.CLOUD && cloudConfigSnapshot != null && localModelSnapshot == null)
        ) { "Image generation job backend must have exactly one matching model snapshot." }
    }

    fun toHistoryMetadata(nativeExecutionJson: String = ""): ImageGenerationHistoryMetadata =
        ImageGenerationHistoryMetadata(
            backend = backend,
            modelId = modelId,
            modelName = modelName,
            requestPrompt = prompt,
            options = if (backend == ImageBackend.LOCAL) {
                options.copy(
                    taskMode = inputDraft.taskMode,
                    strength = inputDraft.strength,
                    controlStrength = inputDraft.controlStrength
                )
            } else {
                LocalImageGenerationOptions()
            },
            inputDraft = if (backend == ImageBackend.LOCAL) inputDraft else LocalImageInputDraft(),
            nativeExecutionJson = nativeExecutionJson
        )
}

data class ImageGenerationJobRecord(
    val id: String,
    val prompt: String,
    val status: ImageGenerationStatusRecord,
    val backend: ImageBackend,
    val modelId: String? = null,
    val modelName: String = "",
    val spec: ImageGenerationJobSpec? = null,
    val imageAssetId: String? = null,
    val previewUriString: String? = null,
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val message: String = "",
    val startedAtMillis: Long = System.currentTimeMillis()
)

private data class PublishedLocalImagePreview(
    val uriString: String,
    val mode: String,
    val step: Int,
    val revision: Long,
    val width: Int,
    val height: Int
)

private fun LocalImageModelRecord.supportsLiveProjectionPreview(): Boolean =
    runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP && family in setOf(
        LocalImageModelFamily.SD15,
        LocalImageModelFamily.SD21,
        LocalImageModelFamily.SDXL,
        LocalImageModelFamily.SD_TURBO,
        LocalImageModelFamily.Z_IMAGE,
        LocalImageModelFamily.FLUX,
        LocalImageModelFamily.QWEN_IMAGE,
        LocalImageModelFamily.LONGCAT_IMAGE
    )

private data class AttachmentImportResult(
    val name: String,
    val text: String,
    val truncated: Boolean,
    val imageAsset: ImageAssetRecord? = null,
    val fileAsset: FileAssetRecord? = null
)

private data class PreparedChatInput(
    val text: String,
    val imageAttachments: List<ChatImageAttachment>
)

internal const val EMPTY_ASSISTANT_COMPLETION_MESSAGE =
    "本次生成没有返回可见正文。请重试；如果问题持续，请重新加载当前模型。"

internal fun List<ChatMessage>.withVisibleAssistantCompletionFallback(): List<ChatMessage> {
    val index = indexOfLast { it.role == Role.ASSISTANT }
    if (index < 0 || this[index].content.isNotBlank()) return this
    return toMutableList().also { messages ->
        messages[index] = messages[index].copy(content = EMPTY_ASSISTANT_COMPLETION_MESSAGE)
    }
}

/**
 * validateForLoad may refresh a QAIRT manifest fingerprint after inspecting a
 * changed bundle directory.  Always pass that refreshed value into the exact
 * bundle/chipset/runtime admission check; the caller's stale manifest must not
 * inherit a previous bundle's certification.
 */
internal fun currentQairtBundleSha256(
    requested: ModelManifest,
    persistedModels: List<ModelManifest>
): String? {
    if (requested.runtime != ChatModelRuntime.GENIEX_QAIRT) return null
    val current = persistedModels.firstOrNull {
        it.id == requested.id && it.runtime == ChatModelRuntime.GENIEX_QAIRT
    } ?: requested
    return current.sha256.trim().lowercase().takeIf { it.isNotBlank() }
}

/** Verification metadata is diagnostic only; structural readiness is the use gate. */
internal fun LocalImageModelRecord.localImageReadinessForUi(
    qnnVerificationCurrent: Boolean?,
    imageCapabilities: LocalImageUiCapabilitiesSnapshot? = null
): String? = localImageReadinessMessage()
    ?: (imageCapabilities ?: imageCapabilitiesForUi()).readinessError

internal fun LocalImageModelRecord.localImageReadinessLabelForUi(
    qnnVerificationCurrent: Boolean?,
    imageCapabilities: LocalImageUiCapabilitiesSnapshot? = null
): String =
    if ((imageCapabilities ?: imageCapabilitiesForUi()).readinessError != null) {
        "配置错误"
    } else if (runtime == LocalImageRuntime.QNN_HTP && qnnVerificationCurrent != true) {
        "NPU 可直接尝试"
    } else {
        localImageReadinessLabel()
    }

internal fun isExactQairtExecutionVerified(
    identity: QairtBundleRuntimeIdentity?,
    verifiedIdentities: Set<QairtBundleRuntimeIdentity>
): Boolean = identity
    ?.takeIf(QairtBundleRuntimeIdentity::isComplete)
    ?.let { it in verifiedIdentities }
    ?: false

internal fun shouldRunAutomaticQairtCanary(
    runtime: ChatModelRuntime,
    modelId: String,
    verifiedModelIds: Set<String>
): Boolean = runtime == ChatModelRuntime.GENIEX_QAIRT && modelId !in verifiedModelIds

private data class LocalGenerationSmokeResult(
    val visibleChars: Int,
    val completionTokens: Int,
    val decodeTps: Double
)

private data class BootstrapCanaryResult(
    val passed: Boolean,
    val detail: String,
    val stats: RuntimeStats = RuntimeStats()
)

private data class CandidateCanaryResult(
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

/**
 * A completed request releases the GENERATING lifecycle even when the final
 * RuntimeStats emission happened just before the ViewModel cleared isGenerating.
 */
internal fun RuntimeStats.lifecycleAfterGeneration(): AgentEngineLifecycle = when {
    loaded -> AgentEngineLifecycle.READY
    lastError?.isNotBlank() == true -> AgentEngineLifecycle.ERROR
    else -> AgentEngineLifecycle.UNLOADED
}

internal fun MainUiState.afterClearChatGenerationStopped(stats: RuntimeStats): MainUiState = copy(
    messages = emptyList(),
    input = "",
    activeChatSessionId = null,
    isGenerating = false,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = "已清空对话，上下文已重置"
)

internal fun MainUiState.afterBackgroundGenerationStopped(stats: RuntimeStats): MainUiState = copy(
    isGenerating = false,
    engineLifecycle = stats.lifecycleAfterGeneration(),
    statusMessage = "应用进入后台，已停止生成以降低发热和耗电。"
)

internal fun MainUiState.afterGenerationStarted(): MainUiState = copy(
    isGenerating = true,
    engineLifecycle = AgentEngineLifecycle.GENERATING
)

internal fun MainUiState.afterGenerationCompleted(stats: RuntimeStats): MainUiState = copy(
    isGenerating = false,
    stats = stats,
    engineLifecycle = stats.lifecycleAfterGeneration()
)

/**
 * Native unload is the authority for the active runtime projection. Persisted
 * committed/LKG profiles remain in [ModelRuntimeProfileStore], but no UI or
 * Local API field may continue to present them as the currently loaded model.
 */
internal fun MainUiState.afterNativeRuntimeReleased(
    lifecycle: AgentEngineLifecycle,
    statusMessage: String,
    stats: RuntimeStats = RuntimeStats(),
    busy: Boolean = false
): MainUiState = copy(
    loadedModelId = null,
    loadedModelName = null,
    busy = busy,
    isGenerating = false,
    stats = stats,
    autoTuningInProgress = false,
    rollbackParams = null,
    profileId = null,
    revision = null,
    profileRecordState = AgentProfileRecordState.NONE,
    verification = AgentProfileVerification.UNKNOWN,
    engineLifecycle = lifecycle,
    tuningJobState = AgentTuningJobState.IDLE,
    reloadRequired = false,
    pendingProfile = null,
    rollbackProfile = null,
    tuningEtaSeconds = null,
    tuningPhase = null,
    tuningCandidateProgress = AgentCandidateProgress(),
    statusMessage = statusMessage
)

/**
 * A runner can detach its native handle before a JNI destroy call reports an
 * error. In that case the engine's Kotlin stats may still be stale, so consult
 * the native diagnostic as a secondary release witness. Missing/malformed
 * diagnostics never override an engine that still reports loaded.
 */
internal fun nativeRuntimeReleaseObserved(
    engineLoaded: Boolean,
    nativeStatsJson: String
): Boolean {
    if (!engineLoaded) return true
    return runCatching {
        val native = JSONObject(nativeStatsJson)
        native.has("loaded") && !native.optBoolean("loaded", true)
    }.getOrDefault(false)
}

private data class LocalApiStreamSmokeResult(
    val visibleContentSeen: Boolean,
    val doneSeen: Boolean,
    val stopCalls: Int
)

private data class LocalApiPreferences(
    val apiEnabled: Boolean,
    val restEnabled: Boolean
)

private val imageAttachmentRegex = Regex("""\u3010上传图片：([^\u3011]+)\u3011(?:\s*\n描述：[^\n]+)?\s*\n(\S+)""")
private val oldImagePlaceholderRegex = Regex("""\s*（当前文本模型会收到图片占位信息；完整识图能力后续接入多模态模型。）""")
private const val MAX_CHAT_IMAGES_PER_MESSAGE = 4
private const val MAX_VISION_IMAGE_EDGE = 1280

data class MainUiState(
    val tab: AppTab = AppTab.CHAT,
    val messages: List<ChatMessage> = emptyList(),
    val chatSessions: List<ChatSessionRecord> = emptyList(),
    val activeChatSessionId: String? = null,
    val images: List<ImageAssetRecord> = emptyList(),
    val files: List<FileAssetRecord> = emptyList(),
    val imageJobs: List<ImageGenerationJobRecord> = emptyList(),
    val localImageModels: List<LocalImageModelRecord> = emptyList(),
    /**
     * Calculated on Dispatchers.IO from each exact QNN stamp.  An absent entry
     * is deliberately treated as not current by UI callers.
     */
    val qnnImageVerificationCurrentByModelId: Map<String, Boolean> = emptyMap(),
    /** Optional exact bundle/chipset/runtime diagnostic evidence; never an admission list. */
    val qairtVerifiedLocalModelIds: Set<String> = emptySet(),
    val qairtVerifiedRecommendationIds: Set<String> = emptySet(),
    val selectedLocalImageModelId: String? = null,
    val selectedImageBackend: ImageBackend = ImageBackend.CLOUD,
    val cloudApiConfig: CloudApiConfig = CloudApiConfig(),
    val cloudModels: List<CloudModelRecord> = emptyList(),
    val selectedCloudChatModelId: String? = null,
    val selectedCloudImageModelId: String? = null,
    val editingCloudModelId: String? = null,
    val selectedChatBackend: ChatBackend = ChatBackend.LOCAL,
    val assistants: List<AssistantRecord> = emptyList(),
    val selectedAssistantId: String = AssistantRecord.DEFAULT_ID,
    val input: String = "",
    val isGenerating: Boolean = false,
    val models: List<ModelManifest> = emptyList(),
    val mnnRuntimeAvailable: Boolean = false,
    val remoteFiles: List<RemoteModelFile> = emptyList(),
    val recommendedRemoteModels: List<ModelScopeRecommendedModel> = emptyList(),
    val hubModels: List<ModelScopeHubModel> = emptyList(),
    val hubQuery: String = "Qwen3.5 MNN",
    val hubPage: Int = 1,
    val hubTotalCount: Int = 0,
    val repoInput: String = "",
    val downloadFileName: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus? = null,
    val busy: Boolean = false,
    val loadedModelId: String? = null,
    val loadedModelName: String? = null,
    val statusMessage: String? = null,
    val params: GenerationParams = GenerationParams(),
    val stats: RuntimeStats = RuntimeStats(),
    val logs: List<com.muyuchat.core.telemetry.RuntimeMetrics> = emptyList(),
    val agentLogs: List<AgentDecisionLog> = emptyList(),
    val deviceProfile: DeviceProfile? = null,
    val agentRecommendation: AgentRecommendation? = null,
    val benchmark: BenchmarkResult? = null,
    val benchmarkHistory: List<BenchmarkHistoryRecord> = emptyList(),
    val autoTuningInProgress: Boolean = false,
    val lastAutoTuningSummary: String? = null,
    val localStabilitySmokeSummary: String? = null,
    val rollbackParams: GenerationParams? = null,
    val profileId: String? = null,
    val revision: Long? = null,
    val profileRecordState: AgentProfileRecordState = AgentProfileRecordState.NONE,
    val verification: AgentProfileVerification = AgentProfileVerification.UNKNOWN,
    val engineLifecycle: AgentEngineLifecycle = AgentEngineLifecycle.UNLOADED,
    val tuningJobState: AgentTuningJobState = AgentTuningJobState.IDLE,
    val reloadRequired: Boolean = false,
    val pendingProfile: AgentPendingProfile? = null,
    val rollbackProfile: AgentRollbackProfile? = null,
    val tuningEtaSeconds: Long? = null,
    val tuningPhase: String? = null,
    val tuningCandidateProgress: AgentCandidateProgress = AgentCandidateProgress(),
    val diagnosticReport: String = "",
    val preference: UserPreference = UserPreference(),
    val apiEnabled: Boolean = false,
    val restEnabled: Boolean = false,
    val apiKey: String = "",
    val localApiAddress: String = "",
    val openApiAddress: String = "",
    val nativeStatsJson: String = "{}",
    val webSearchConfig: WebSearchConfig = WebSearchConfig(),
    val webSearchTurnMode: WebSearchTurnMode = WebSearchTurnMode.FOLLOW,
    val webSearchResearchModeOverride: WebSearchResearchMode? = null,
    val webSearchOneShotEnabled: Boolean = false,
    val webSearchStatusMessage: String? = null,
    val webSearchDiagnostics: List<WebSearchDiagnosticRecord> = emptyList()
)

private sealed interface DownloadedModelRegistration {
    data class Chat(val model: ModelManifest) : DownloadedModelRegistration
    data class Image(val model: LocalImageModelRecord) : DownloadedModelRegistration
    data class VisionProjector(val model: ModelManifest, val shouldReload: Boolean) : DownloadedModelRegistration
}

private sealed interface VisionBundleDownloadResult {
    data class ChatModel(val model: ModelManifest) : VisionBundleDownloadResult
    data class EngineBundle(
        val displayName: String,
        val bundleDir: File,
        val report: LocalVisionNpuReport
    ) : VisionBundleDownloadResult
}

internal fun writeImageAssetBytesAtomically(
    directory: File,
    fileName: String,
    bytes: ByteArray
): File {
    require(bytes.isNotEmpty()) { "Generated image bytes must not be empty." }
    require(fileName.isNotBlank() && fileName == File(fileName).name) {
        "Generated image file name must be a safe leaf name."
    }
    val root = directory.canonicalFile
    require(root.isDirectory || root.mkdirs()) {
        "Unable to create the image asset directory."
    }
    val output = File(root, fileName).canonicalFile
    require(output.parentFile == root) { "Generated image path escaped the image asset directory." }
    check(!output.exists()) { "Generated image destination already exists." }
    val temp = File(root, ".$fileName.${UUID.randomUUID()}.part")
    try {
        temp.outputStream().use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        check(temp.isFile && temp.length() == bytes.size.toLong()) {
            "Generated image staging write was incomplete."
        }
        check(temp.renameTo(output)) { "Unable to atomically publish the generated image." }
        return output
    } finally {
        runCatching { temp.delete() }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val REST_PORT = 11435
        private const val CLOUD_REASONING_LOCKED_MESSAGE = "云端模型的思考模式由服务商和具体模型决定，MCA 会默认按开启处理；如需关闭或调整，请在云端模型配置中设置相关参数。"
        private const val LOCAL_IMAGE_GENERATION_WATCHDOG_MS = 8 * 60 * 1000L
        private const val LOCAL_IMAGE_UI_PREVIEW_DIRECTORY = "local_image_ui_previews"
        private const val MAX_LOCAL_IMAGE_PREVIEW_BYTES = 16L * 1024L * 1024L
        private const val ASSISTANT_MODEL_MODE_FOLLOW_CURRENT = "follow_current"
        private const val ASSISTANT_MODEL_MODE_LOCAL = "local"
        private const val ASSISTANT_MODEL_MODE_CLOUD = "cloud"
        const val CLOUD_MODEL_CHOICE_PREFIX = "cloudmodel:"
        const val CLOUD_IMAGE_MODEL_CHOICE_PREFIX = "cloudimagemodel:"
        const val LOCAL_IMAGE_MODEL_CHOICE_PREFIX = "localimagemodel:"
    }

    private val modelStore = ModelStoreRepository(application)
    private val runtimeProfileStore = ModelRuntimeProfileStore(application)
    private val installationScopeId = runBlocking(Dispatchers.IO) {
        runtimeProfileStore.installationScopeId()
    }
    private val modelScopeClient = ModelScopeClient()
    private val downloader = ResumableDownloader()
    private val engine = McaInferenceService(
        context = application,
        installationScopeId = installationScopeId
    )
    private val apiKey = loadOrCreateApiKey(application)
    private val initialApiPreferences = loadApiPreferences(application)
    private var apiServer: McaLoopbackServer? = null
    private var activeApiBindHost: String? = null
    private val chatSessionStore = ChatSessionStore(application)
    private val assistantStore = AssistantStore(application)
    private val cloudApiStore = CloudApiStore(application)
    private val cloudChatProvider = OpenAiCompatibleChatProvider()
    private val cloudImageProvider = CloudImageProvider()
    private val webSearchStore = WebSearchStore(application)
    private val webSearchDiagnosticStore = WebSearchDiagnosticStore(application)
    private val webSearchProvider = WebSearchProvider()
    private val localImageModelStore = LocalImageModelStore(application)
    private val localImageWorkerClient = LocalImageWorkerClient(application)
    private val deviceProfileReader = DeviceProfileReader(application)
    private val advisor = AgentAdvisor()
    private val benchmarkRunner = BenchmarkRunner(engine, deviceProfileReader)
    private val agentLogger = AgentDecisionLogger(application)
    private val benchmarkHistoryLogger = BenchmarkHistoryLogger(application)
    private val initialDeviceProfile = deviceProfileReader.read()
    private val initialChatSessions = chatSessionStore.load()
    private val initialImages = chatSessionStore.loadImages()
    private val initialFiles = chatSessionStore.loadFiles()
    private val initialParams = loadGenerationParams(application)
    private val runtimeUserOverrideFields = loadRuntimeUserOverrideFields(application).toMutableSet()
    private val initialAssistants = assistantStore.loadAssistants(initialParams)
    private val initialStoredSelectedAssistantId = assistantStore.loadSelectedAssistantId(initialAssistants)
    private val initialSelectedAssistantId = initialChatSessions.firstOrNull()
        ?.assistantId
        ?.takeIf { assistantId -> initialAssistants.any { it.id == assistantId } }
        ?: initialStoredSelectedAssistantId
    private val initialSelectedAssistant = initialAssistants.firstOrNull { it.id == initialSelectedAssistantId }
    private val initialEffectiveParams = initialSelectedAssistant?.toGenerationParams(initialParams) ?: initialParams
    private val initialLocalImageModels = localImageModelStore.loadModels()
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
    private val initialSessionCloudChatModelId = initialChatSessions.firstOrNull()
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
    private val initialSelectedBackend = initialChatSessions.firstOrNull()
        ?.modelMode
        ?.toChatBackendOrNull()
        ?: cloudApiStore.loadSelectedBackend()
    private val initialSelectedImageBackend = localImageModelStore.loadSelectedBackend()
    private var generationJob: Job? = null
    private var directParameterStageJob: Job? = null
    private val directParameterStageMutex = Mutex()
    private val directParameterStageGeneration = AtomicLong(0L)
    private var imageGenerationJob: Job? = null
    private var adaptiveTuningJob: Job? = null
    private val adaptiveTuningPauseRequested = AtomicBoolean(false)
    private val adaptiveTuningCancelRequested = AtomicBoolean(false)
    private var activeRuntimeIdentity: ModelRuntimeIdentity? = null
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
    @Volatile private var activeLocalApiImageModelId: String? = null
    private val localImageGenerationCoordinator = LocalImageGenerationCoordinator()

    private val _uiState = MutableStateFlow(
        MainUiState(
            messages = initialChatSessions.firstOrNull()?.messages.orEmpty(),
            chatSessions = initialChatSessions,
            activeChatSessionId = initialChatSessions.firstOrNull()?.id,
            images = initialImages,
            files = initialFiles,
            localImageModels = initialLocalImageModels,
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
            selectedAssistantId = initialSelectedAssistantId,
            selectedChatBackend = if (initialSelectedBackend == ChatBackend.CLOUD && initialCloudConfig.configured) {
                ChatBackend.CLOUD
            } else {
                ChatBackend.LOCAL
            },
            models = modelStore.listModels(),
            mnnRuntimeAvailable = engine.isRuntimeAvailable(LocalChatRuntime.MNN_CPU),
            recommendedRemoteModels = sortRecommendedModels(
                modelScopeClient.userFacingRecommendedModels(),
                initialDeviceProfile
            ),
            params = initialEffectiveParams,
            agentLogs = agentLogger.recent(),
            benchmarkHistory = benchmarkHistoryLogger.recent(),
            deviceProfile = initialDeviceProfile,
            apiEnabled = initialApiPreferences.apiEnabled,
            restEnabled = initialApiPreferences.restEnabled,
            apiKey = apiKey,
            localApiAddress = apiUrl("127.0.0.1"),
            openApiAddress = currentOpenApiAddress(),
            webSearchConfig = initialWebSearchConfig,
            webSearchDiagnostics = initialWebSearchDiagnostics
        )
    )
    val uiState = _uiState.asStateFlow()

    init {
        runCatching {
            File(getApplication<Application>().cacheDir, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY)
                .deleteRecursively()
        }
        LocalApiRuntime.engine = engine
        LocalApiRuntime.streamChatWithContextProvider = { request, executionContext ->
            val effectiveRequest = if (
                executionContext.loadAuthorization == null &&
                executionContext.requestId.startsWith("ui-")
            ) {
                engine.activeExecutionProfile()
                    ?.let { profile -> request.copy(params = mergeExecutionProfile(request.params, profile)) }
                    ?: request
            } else {
                request
            }
            engine.streamChat(effectiveRequest, executionContext)
                .onStart {
                    _uiState.update(MainUiState::afterGenerationStarted)
                }
                .onCompletion {
                    _uiState.update { state ->
                        state.afterGenerationCompleted(engine.stats.value)
                    }
                }
        }
        LocalApiRuntime.stopGenerationProvider = { engine.stopGeneration() }
        LocalApiRuntime.loadedModelJsonProvider = { loadedModelJson() }
        LocalApiRuntime.paramsJsonProvider = { apiGenerationParams().toJson() }
        LocalApiRuntime.generationParamsProvider = { apiGenerationParams() }
        LocalApiRuntime.modelsJsonProvider = { modelsJson() }
        LocalApiRuntime.modelRuntimeStatesJsonProvider = { modelRuntimeStatesJson() }
        LocalApiRuntime.deviceProfileJsonProvider = { currentDeviceProfile().toJson().toString() }
        LocalApiRuntime.agentRecommendationJsonProvider = { requestJson ->
            val preference = UserPreference.fromJson(requestJson)
            buildRecommendation(preference).toJson().toString()
        }
        LocalApiRuntime.benchmarkJsonProvider = {
            benchmarkRunner.runCurrentParamsBenchmark(_uiState.value.params)
                .toJson()
                .toString()
        }
        LocalApiRuntime.imageGenerationProvider = { requestId, body ->
            generateLocalApiImage(requestId, body)
        }
        LocalApiRuntime.controlPlane = object : LocalApiControlPlane {
            override fun busyState(): LocalApiBusyState {
                val state = _uiState.value
                val busyCode = when {
                    state.isGenerating || state.engineLifecycle == AgentEngineLifecycle.GENERATING ->
                        "generation_in_progress"
                    state.engineLifecycle == AgentEngineLifecycle.LOADING -> "model_loading"
                    state.engineLifecycle == AgentEngineLifecycle.RELOADING -> "model_reloading"
                    state.engineLifecycle == AgentEngineLifecycle.ROLLING_BACK -> "profile_rollback"
                    state.tuningJobState in setOf(
                        AgentTuningJobState.QUEUED,
                        AgentTuningJobState.RUNNING,
                        AgentTuningJobState.VALIDATING,
                        AgentTuningJobState.CANCELING,
                        AgentTuningJobState.RECOVERING
                    ) -> "tuning_in_progress"
                    state.busy && !state.isGenerating -> "runtime_busy"
                    else -> return LocalApiBusyState.IDLE
                }
                return LocalApiBusyState(
                    busy = true,
                    code = busyCode,
                    message = state.statusMessage ?: "本地模型运行时正在执行独占操作，请稍后重试。",
                    retryAfterMs = 1_000L,
                    detailsJson = tuningStateJson()
                )
            }

            override fun preflight(request: LocalApiPreflightRequest): LocalApiPreflightResult {
                val state = _uiState.value
                val requestedModel = request.requestedModel?.trim().orEmpty()
                if (requestedModel.isNotBlank()) {
                    val matches = requestedModel == state.loadedModelId ||
                        requestedModel == state.loadedModelName ||
                        state.models.firstOrNull { it.id == state.loadedModelId }?.let { model ->
                            requestedModel == model.fileName || requestedModel == model.displayName
                        } == true
                    if (!matches) {
                        return LocalApiPreflightResult.Rejected(
                            httpStatus = 409,
                            code = "model_mismatch",
                            message = "请求的模型不是当前正式 MainActivity 已加载模型。",
                            detailsJson = JSONObject()
                                .put("requestedModel", requestedModel)
                                .put("loadedModelId", state.loadedModelId)
                                .put("loadedModelName", state.loadedModelName)
                                .toString()
                        )
                    }
                }
                return when (val preflight = runBlocking(Dispatchers.IO) {
                    engine.preflightChat(request.chatRequest)
                }) {
                    is CompletionPreflight.Ready -> LocalApiPreflightResult.Ready
                    is CompletionPreflight.Rejected -> LocalApiPreflightResult.Rejected(
                        httpStatus = 409,
                        code = preflight.code,
                        message = preflight.message,
                        detailsJson = JSONObject()
                            .put("changedFields", JSONArray(preflight.changedFields.toList().sorted()))
                            .put("quarantinedOverrides", JSONArray().also { array ->
                                preflight.quarantinedOverrides.forEach { value -> array.put(value.toString()) }
                            })
                            .put("profile", JSONObject(runtimeProfileJson()))
                            .toString()
                    )
                }
            }

            override fun profileJson(): String = runtimeProfileJson()

            override fun tuningJson(): String = tuningStateJson()

            override fun tuningJob(jobId: String?): com.muyuchat.api.local.LocalApiControlResult {
                val requested = jobId?.takeIf { it.isNotBlank() && !it.equals("current", ignoreCase = true) }
                val job = if (requested == null) {
                    val identityKey = activeRuntimeIdentity?.identityHash
                    runBlocking(Dispatchers.IO) {
                        activeTuningJobId?.let { runtimeProfileStore.tuningJob(it) }
                            ?: identityKey?.let { runtimeProfileStore.activeTuningJob(it) }
                    }
                } else {
                    runBlocking(Dispatchers.IO) { runtimeProfileStore.tuningJob(requested) }
                }
                if (requested == null) {
                    val payload = JSONObject(tuningStateJson())
                    if (job != null) {
                        payload
                            .put("jobId", job.jobId)
                            .put("state", job.state.lowercase(Locale.ROOT))
                            .put("phase", job.phase)
                            .put("job", tuningJobPublicJson(job))
                    }
                    return com.muyuchat.api.local.LocalApiControlResult.Success(payload.toString())
                }
                job
                    ?: return com.muyuchat.api.local.LocalApiControlResult.Rejected(
                        httpStatus = 404,
                        code = "tuning_job_not_found",
                        message = "未找到调优任务：$requested"
                    )
                return com.muyuchat.api.local.LocalApiControlResult.Success(
                    JSONObject(tuningStateJson())
                        .put("job", tuningJobPublicJson(job))
                        .toString()
                )
            }

            override fun createTuningJob(
                request: com.muyuchat.api.local.LocalApiTuningJobCreateRequest,
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult {
                val state = _uiState.value
                val identity = activeRuntimeIdentity
                    ?: return localApiTuningRejected(
                        httpStatus = 409,
                        code = "model_not_loaded",
                        message = "请先通过正式 MainActivity 加载本地模型。"
                    )
                val model = activeModelForRuntimeProfile
                    ?: return localApiTuningRejected(
                        httpStatus = 409,
                        code = "model_not_loaded",
                        message = "当前模型没有可调优的本地运行身份。"
                    )
                if (engine.activeExecutionProfile() == null) {
                    return localApiTuningRejected(
                        httpStatus = 409,
                        code = "profile_not_loaded",
                        message = "当前模型尚未建立 active execution profile。"
                    )
                }
                if (!localApiModelMatches(request.modelId, state, model)) {
                    return localApiTuningRejected(
                        httpStatus = 409,
                        code = "model_mismatch",
                        message = "调优请求的模型不是当前正式 MainActivity 已加载模型。",
                        details = JSONObject()
                            .put("requestedModel", request.modelId)
                            .put("loadedModelId", state.loadedModelId)
                            .put("loadedModelName", state.loadedModelName)
                    )
                }
                val debugMode = localApiDebugMode(request.mode)
                    ?: return localApiTuningRejected(
                        httpStatus = 400,
                        code = "invalid_tuning_mode",
                        message = "mode 仅支持 quick、standard、deep 或 power_save。"
                    )
                val requestedPreference = localApiPerformancePreference(request.performancePreference)
                if (!request.performancePreference.isNullOrBlank() && requestedPreference == null) {
                    return localApiTuningRejected(
                        httpStatus = 400,
                        code = "invalid_performance_preference",
                        message = "performancePreference 仅支持 balanced、speed、quality、long_context 或 power_save。"
                    )
                }
                val preference = requestedPreference ?: if (debugMode == AgentDebugMode.PowerSave) {
                    state.preference.copy(mode = PerformanceMode.PowerSave)
                } else {
                    state.preference
                }
                val existing = runCatching {
                    runBlocking(Dispatchers.IO) { runtimeProfileStore.activeTuningJob(identity.identityHash) }
                }.getOrElse { error ->
                    return localApiTuningRejected(
                        httpStatus = 409,
                        code = "tuning_state_unavailable",
                        message = error.message ?: "无法读取当前调优任务状态。"
                    )
                }
                if (existing != null) {
                    return localApiTuningConflict(
                        action = "create",
                        activeJobId = existing.jobId,
                        message = "当前模型已有未结束的调优任务。"
                    )
                }
                localApiExclusiveLifecycleConflict("create")?.let { return it }
                val job = runCatching {
                    runBlocking(Dispatchers.IO) {
                        runtimeProfileStore.createTuningJob(
                            identityKey = identity.identityHash,
                            autoApplyLoadChanges = request.autoApply,
                            phase = "QUEUED"
                        )
                    }
                }.getOrElse { error ->
                    return localApiTuningRejected(
                        httpStatus = 409,
                        code = "tuning_create_conflict",
                        message = error.message ?: "调优任务创建失败。"
                    )
                }
                activeTuningJobId = job.jobId
                _uiState.update {
                    it.copy(
                        tuningJobState = AgentTuningJobState.QUEUED,
                        tuningPhase = "等待取得调优生命周期",
                        statusMessage = "Local API 已创建调优任务，正在进入安全候选搜索。"
                    )
                }
                val started = startAgentTuning(
                    debugMode = debugMode,
                    autoApply = request.autoApply,
                    precreatedJob = job,
                    preferenceOverride = preference
                )
                if (!started) {
                    val failed = runCatching {
                        runBlocking(Dispatchers.IO) {
                            runtimeProfileStore.transitionTuningJob(
                                jobId = job.jobId,
                                state = PersistedTuningJobState.FAILED,
                                phase = "START_REJECTED",
                                failureCode = "LIFECYCLE_CONFLICT",
                                failureSummary = "调优任务未能取得运行时生命周期所有权。"
                            )
                        }
                    }.getOrNull()
                    activeTuningJobId = null
                    _uiState.update {
                        it.copy(
                            tuningJobState = AgentTuningJobState.FAILED,
                            tuningPhase = "启动冲突",
                            statusMessage = "调优任务未能启动，运行时状态已变化。"
                        )
                    }
                    return localApiTuningConflict(
                        action = "create",
                        activeJobId = failed?.jobId,
                        message = "调优任务未能启动，运行时状态已变化。"
                    )
                }
                return com.muyuchat.api.local.LocalApiControlResult.Success(
                    json = JSONObject()
                        .put("accepted", true)
                        .put("job", tuningJobPublicJson(job))
                        .put("idempotencyKeyAccepted", idempotencyKey.isNotBlank())
                        .toString(),
                    httpStatus = 202
                )
            }

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
            ): com.muyuchat.api.local.LocalApiControlResult {
                val identity = activeRuntimeIdentity
                    ?: return localApiTuningRejected(409, "model_not_loaded", "当前没有已加载的本地模型。")
                if (activeModelForRuntimeProfile == null) {
                    return localApiTuningRejected(409, "model_not_loaded", "当前模型没有可应用的运行配置。")
                }
                val job = runCatching {
                    runBlocking(Dispatchers.IO) { runtimeProfileStore.tuningJob(jobId) }
                }.getOrNull() ?: return localApiTuningRejected(
                    httpStatus = 404,
                    code = "tuning_job_not_found",
                    message = "未找到调优任务：$jobId"
                )
                if (job.identityKey != identity.identityHash) {
                    return localApiTuningRejected(
                        409,
                        "tuning_job_model_mismatch",
                        "该调优任务不属于当前已加载模型。"
                    )
                }
                localApiExclusiveLifecycleConflict("apply", allowedStagedJobId = jobId)?.let { return it }
                val pending = runCatching {
                    runBlocking(Dispatchers.IO) { runtimeProfileStore.pendingTransaction(identity.identityHash) }
                }.getOrElse { error ->
                    return localApiTuningRejected(
                        409,
                        "pending_profile_unavailable",
                        error.message ?: "无法读取 staged candidate。"
                    )
                }
                if (pending == null || pending.journal.jobId != jobId) {
                    return localApiTuningRejected(
                        409,
                        "staged_candidate_not_found",
                        "该任务没有可应用的 staged candidate。"
                    )
                }
                if (pending.journal.state != TuningJournalState.STAGED.name) {
                    return localApiTuningRejected(
                        409,
                        "staged_candidate_not_ready",
                        "候选事务当前处于 ${pending.journal.state.lowercase()}，不能重复应用。"
                    )
                }
                if (job.state !in setOf(PersistedTuningJobState.PAUSED.name, PersistedTuningJobState.RUNNING.name)) {
                    return localApiTuningRejected(
                        409,
                        "tuning_apply_state_conflict",
                        "调优任务当前处于 ${job.state.lowercase()}，不能应用 staged candidate。"
                    )
                }
                activeTuningJobId = jobId
                adaptiveTuningCancelRequested.set(false)
                adaptiveTuningPauseRequested.set(false)
                _uiState.update {
                    it.copy(
                        busy = true,
                        engineLifecycle = AgentEngineLifecycle.RELOADING,
                        tuningJobState = AgentTuningJobState.VALIDATING,
                        tuningPhase = "等待应用 staged candidate",
                        statusMessage = "Local API 已接受 apply，正在取得运行时生命周期。"
                    )
                }
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
                return com.muyuchat.api.local.LocalApiControlResult.Success(
                    json = JSONObject()
                        .put("accepted", true)
                        .put("action", "apply")
                        .put("job", tuningJobPublicJson(job))
                        .put("idempotencyKeyAccepted", idempotencyKey.isNotBlank())
                        .toString(),
                    httpStatus = 202
                )
            }

            override fun applyTuningJob(
                jobId: String,
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                applyTuningJob(jobId, idempotency.key)
            }

            override fun rollbackTuning(
                idempotencyKey: String
            ): com.muyuchat.api.local.LocalApiControlResult {
                val identity = activeRuntimeIdentity
                    ?: return localApiTuningRejected(409, "model_not_loaded", "当前没有已加载的本地模型。")
                val model = activeModelForRuntimeProfile
                    ?: return localApiTuningRejected(409, "model_not_loaded", "当前模型没有可回滚的运行配置。")
                val runtimeState = runCatching {
                    runBlocking(Dispatchers.IO) { runtimeProfileStore.currentRuntimeState(identity.identityHash) }
                }.getOrElse { error ->
                    return localApiTuningRejected(409, "rollback_state_unavailable", error.message ?: "无法读取回滚状态。")
                } ?: return localApiTuningRejected(409, "rollback_not_available", "当前运行配置尚未持久化。")
                runtimeState.activeJob?.let { activeJob ->
                    return localApiTuningConflict(
                        action = "rollback",
                        activeJobId = activeJob.jobId,
                        message = "存在未结束的调优任务，不能并发回滚。"
                    )
                }
                localApiExclusiveLifecycleConflict("rollback")?.let { return it }
                val active = runtimeState.activeProfile
                    ?: return localApiTuningRejected(409, "rollback_not_available", "当前没有 committed profile。")
                val rollbackTarget = active.parentCommittedProfileId
                    ?: runtimeState.pointers?.lastKnownGoodProfileId?.takeIf { it != active.profileId }
                if (rollbackTarget == null) {
                    return localApiTuningRejected(409, "rollback_not_available", "当前 profile 没有更早的稳定版本可回滚。")
                }
                _uiState.update {
                    it.copy(
                        busy = true,
                        engineLifecycle = AgentEngineLifecycle.ROLLING_BACK,
                        tuningJobState = AgentTuningJobState.RECOVERING,
                        tuningPhase = "等待回滚生命周期",
                        statusMessage = "Local API 已接受 rollback，正在取得运行时生命周期。"
                    )
                }
                adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        rollbackCommittedRuntimeProfile(identity, model)
                    } finally {
                        adaptiveTuningJob = null
                    }
                }
                return com.muyuchat.api.local.LocalApiControlResult.Success(
                    json = JSONObject()
                        .put("accepted", true)
                        .put("action", "rollback")
                        .put("targetProfileId", rollbackTarget)
                        .put("idempotencyKeyAccepted", idempotencyKey.isNotBlank())
                        .toString(),
                    httpStatus = 202
                )
            }

            override fun rollbackTuning(
                idempotency: com.muyuchat.api.local.LocalApiIdempotencyContext
            ): com.muyuchat.api.local.LocalApiControlResult = runDurableLocalApiControl(idempotency) {
                rollbackTuning(idempotency.key)
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            recoverInterruptedRuntimeProfiles()
        }

        // GenieX keeps successfully installed QAIRT packages in internal app
        // storage. Recover a lost MCA product manifest in the background so a
        // multi-gigabyte bundle is reused instead of downloaded again. Directory
        // hashing must never run in the ViewModel constructor/main thread.
        viewModelScope.launch(Dispatchers.IO) {
            val recovered = modelStore.recoverInstalledQairtBundles()
            if (recovered.isNotEmpty()) {
                refreshManagedRuntimeReadiness()
                _uiState.update { state ->
                    state.copy(
                        statusMessage = "已恢复 ${recovered.size} 个现有 QAIRT NPU 模型；首次加载会自动隔离安全启动。"
                    )
                }
            }
        }

        if (initialApiPreferences.apiEnabled) {
            runCatching {
                startApiServer(if (initialApiPreferences.restEnabled) "0.0.0.0" else "127.0.0.1")
                updateLocalApiForegroundService(true, initialApiPreferences.restEnabled)
            }.onFailure { error ->
                persistApiPreferences(apiEnabled = false, restEnabled = false)
                _uiState.update {
                    it.copy(
                        apiEnabled = false,
                        restEnabled = false,
                        statusMessage = "本机 API 自动恢复失败：${error.message}"
                    )
                }
            }
        }

        viewModelScope.launch {
            engine.stats.collect { stats ->
                _uiState.update { state ->
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
                        nativeStatsJson = engine.nativeStatsJson(),
                        engineLifecycle = lifecycle
                    )
                }
            }
        }

        // Stamp hashing walks the whole managed QNN bundle, so it must not run
        // on the main thread.  Until this refresh finishes the UI treats every
        // QNN image package as not ready.
        viewModelScope.launch(Dispatchers.IO) {
            refreshManagedRuntimeReadiness()
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(tab = tab) }
        if (tab == AppTab.MODELS) {
            viewModelScope.launch(Dispatchers.IO) {
                refreshManagedRuntimeReadiness()
            }
        }
    }

    private fun LocalImageModelRecord.qnnBundleRootForVerification(): File? =
        bundleRoot
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isDirectory }
            ?: File(path).parentFile?.takeIf { it.isDirectory }

    /** Must be called from an IO coroutine. */
    private fun currentQnnImageVerificationByModelId(
        models: List<LocalImageModelRecord>
    ): Map<String, Boolean> {
        val context = getApplication<Application>()
        return models
            .asSequence()
            .filter { it.runtime == LocalImageRuntime.QNN_HTP }
            .associate { model ->
                model.id to (model.qnnBundleRootForVerification()
                    ?.let { root -> model.hasCurrentQnnVerificationStamp(context, root) }
                    ?: false)
            }
    }

    /** Must be called from an IO coroutine. */
    private fun currentQairtVerifiedLocalModelIds(
        models: List<ModelManifest>
    ): Set<String> {
        val context = getApplication<Application>()
        val verifiedIdentities = QairtExecutionVerificationStore
            .forContext(context)
            .verifiedIdentities()
        return models.asSequence()
            .filter { it.runtime == ChatModelRuntime.GENIEX_QAIRT }
            .filter { model ->
                isExactQairtExecutionVerified(
                    identity = qairtRuntimeIdentityFor(context, model.sha256),
                    verifiedIdentities = verifiedIdentities
                )
            }
            .map(ModelManifest::id)
            .toSet()
    }

    private fun verifiedQairtRecommendationIds(
        models: List<ModelManifest>,
        verifiedLocalModelIds: Set<String>,
        recommendations: List<ModelScopeRecommendedModel>
    ): Set<String> {
        val verifiedRepoIds = models.asSequence()
            .filter { it.id in verifiedLocalModelIds }
            .mapNotNull(ModelManifest::repoId)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return recommendations.asSequence()
            .filter { it.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT }
            .filter { it.repoId.trim() in verifiedRepoIds }
            .map(ModelScopeRecommendedModel::id)
            .toSet()
    }

    private fun LocalImageModelRecord.isReadyForManagedSelection(
        qnnVerificationCurrentByModelId: Map<String, Boolean>
    ): Boolean = localImageReadinessForUi(qnnVerificationCurrentByModelId[id]) == null

    private fun selectedReadyLocalImageModelId(
        models: List<LocalImageModelRecord>,
        qnnVerificationCurrentByModelId: Map<String, Boolean>,
        preferredId: String?
    ): String? {
        // Keep the verification map in the call shape for UI refresh bookkeeping, but never use
        // verification/certification as automatic-selection admission.
        @Suppress("UNUSED_VARIABLE")
        val advisoryVerification = qnnVerificationCurrentByModelId
        return selectStructurallyReadyLocalImageModelId(models, preferredId)
    }

    /** Refreshes product-facing readiness; persisted verification remains advisory. */
    private fun refreshManagedRuntimeReadiness() {
        val localImageModels = localImageModelStore.loadModels()
        val localModels = modelStore.listModels()
        val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(localImageModels)
        val qairtVerifiedLocalModelIds = currentQairtVerifiedLocalModelIds(localModels)
        val current = _uiState.value
        val selectedImageModelId = selectedReadyLocalImageModelId(
            models = localImageModels,
            qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
            preferredId = localImageModelStore.loadSelectedModelId()
        )
        val selectedImageBackend = when {
            selectedImageModelId != null && localImageModelStore.loadSelectedBackend() == ImageBackend.LOCAL ->
                ImageBackend.LOCAL
            selectedImageModelId == null && current.selectedImageBackend == ImageBackend.LOCAL ->
                ImageBackend.CLOUD
            else -> current.selectedImageBackend
        }
        localImageModelStore.saveSelectedModelId(selectedImageModelId)
        localImageModelStore.saveSelectedBackend(selectedImageBackend)
        _uiState.update {
            it.copy(
                models = localModels,
                localImageModels = localImageModels,
                qnnImageVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                qairtVerifiedLocalModelIds = qairtVerifiedLocalModelIds,
                qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                    models = localModels,
                    verifiedLocalModelIds = qairtVerifiedLocalModelIds,
                    recommendations = it.recommendedRemoteModels
                ),
                selectedLocalImageModelId = selectedImageModelId,
                selectedImageBackend = selectedImageBackend
            )
        }
    }

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun attachFile(uriString: String) {
        if (_uiState.value.isGenerating) {
            _uiState.update { it.copy(statusMessage = "请先停止当前生成，再上传文件") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val uri = Uri.parse(uriString)
                val name = displayNameForUri(uri)
                if (isImageAttachment(uri, name)) {
                    val image = importImageAsset(
                        uri = uri,
                        displayName = name,
                        source = "uploaded",
                        chatSessionId = _uiState.value.activeChatSessionId
                    )
                    return@runCatching AttachmentImportResult(name, image.toInputAttachment(), false, image)
                }
                if (!isSupportedTextAttachment(uri, name)) {
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
            result.onSuccess { imported ->
                imported.imageAsset?.let { image ->
                    val persistenceError = runCatching {
                        chatSessionStore.upsertImages(listOf(image))
                    }.exceptionOrNull()
                    if (persistenceError != null) {
                        image.deleteLocalCopy()
                        fail("图片库保存失败：${persistenceError.message ?: "数据库提交失败"}")
                        return@onSuccess
                    }
                }
                var filesToPersist: List<FileAssetRecord>? = null
                _uiState.update { state ->
                    val attachment = buildString {
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
                    val updatedImages = imported.imageAsset?.let { image ->
                        (listOf(image) + state.images.filterNot { it.id == image.id }).sortedImagesForLibrary()
                    }
                    val updatedFiles = imported.fileAsset?.let { file ->
                        (listOf(file) + state.files.filterNot { it.id == file.id }).sortedFilesForLibrary()
                    }
                    if (updatedFiles != null) filesToPersist = updatedFiles
                    state.copy(
                        input = state.input + attachment,
                        images = updatedImages ?: state.images,
                        files = updatedFiles ?: state.files,
                        statusMessage = if (imported.imageAsset != null) "已添加图片：${imported.name}" else "已添加文件：${imported.name}"
                    )
                }
                filesToPersist?.let { persistFiles(it) }
            }.onFailure { error ->
                _uiState.update { it.copy(statusMessage = error.message ?: "文件上传失败") }
            }
        }
    }

    fun useImageAsset(imageId: String) {
        val image = _uiState.value.images.firstOrNull { it.id == imageId } ?: return
        _uiState.update { state ->
            val separator = if (state.input.isBlank()) "" else "\n\n"
            state.copy(
                input = state.input + separator + image.toInputAttachment(),
                statusMessage = "已插入图片：${image.name}"
            )
        }
    }

    fun useFileAsset(fileId: String) {
        val file = _uiState.value.files.firstOrNull { it.id == fileId } ?: return
        _uiState.update { state ->
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
        _uiState.update { state ->
            removed = state.files.firstOrNull { it.id == fileId }
            filesToPersist = state.files.filterNot { it.id == fileId }
            state.copy(files = filesToPersist, statusMessage = "已从文件库移除：${removed?.name ?: "文件"}")
        }
        persistFiles(filesToPersist)
    }

    fun clearFileLibrary() {
        val filesToRemove = _uiState.value.files
        if (filesToRemove.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "文件库已经为空") }
            return
        }
        _uiState.update {
            it.copy(
                files = emptyList(),
                statusMessage = "已清空文件库：${filesToRemove.size} 个文件"
            )
        }
        persistFiles(emptyList())
    }

    fun generateImageAsset(
        prompt: String,
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        options: LocalImageGenerationOptions = LocalImageGenerationOptions()
    ) {
        enqueueImageGeneration(
            prompt = prompt,
            inputDraft = inputDraft,
            options = options,
            jobSnapshot = null
        )
    }

    fun retryImageGeneration(jobId: String) {
        val sourceJob = _uiState.value.imageJobs.firstOrNull { it.id == jobId }
        val snapshot = sourceJob?.spec
        if (sourceJob == null || snapshot == null) {
            _uiState.update { it.copy(statusMessage = "原图片任务参数已不可用，无法按原任务重试。") }
            return
        }
        if (!sourceJob.status.terminal) {
            _uiState.update { it.copy(statusMessage = "当前图片任务仍在运行，无需重复提交。") }
            return
        }
        enqueueImageGeneration(
            prompt = snapshot.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot
        )
    }

    fun recreateImageAsset(imageId: String) {
        val state = _uiState.value
        val asset = state.images.firstOrNull { it.id == imageId }
        val history = ImageGenerationHistoryMetadata.fromJsonOrNull(asset?.generationMetadataJson)
        if (asset == null || history == null) {
            _uiState.update { it.copy(statusMessage = "这张图片没有可复现的完整生成参数。") }
            return
        }
        val snapshot = when (history.backend) {
            ImageBackend.LOCAL -> {
                val model = state.localImageModels.firstOrNull { it.id == history.modelId }
                if (model == null) {
                    _uiState.update {
                        it.copy(statusMessage = "原本地模型已删除；重新导入同一模型后才能按原参数生成。")
                    }
                    return
                }
                ImageGenerationJobSpec(
                    prompt = history.requestPrompt,
                    backend = ImageBackend.LOCAL,
                    localModelSnapshot = model,
                    modelId = model.id,
                    modelName = history.modelName,
                    inputDraft = history.inputDraft,
                    options = history.options,
                    chatSessionId = asset.chatSessionId
                )
            }
            ImageBackend.CLOUD -> {
                val config = state.cloudModels
                    .firstOrNull { it.id == history.modelId && it.kind == CloudModelKind.IMAGE }
                    ?.toImageConfig()
                    ?.normalized()
                if (config == null) {
                    _uiState.update {
                        it.copy(statusMessage = "原云端图片模型已移除；恢复同一连接后才能重新生成。")
                    }
                    return
                }
                ImageGenerationJobSpec(
                    prompt = history.requestPrompt,
                    backend = ImageBackend.CLOUD,
                    cloudConfigSnapshot = config,
                    modelId = history.modelId,
                    modelName = history.modelName,
                    inputDraft = LocalImageInputDraft(),
                    options = LocalImageGenerationOptions(),
                    chatSessionId = asset.chatSessionId
                )
            }
        }
        enqueueImageGeneration(
            prompt = snapshot.prompt,
            inputDraft = snapshot.inputDraft,
            options = snapshot.options,
            jobSnapshot = snapshot
        )
    }

    private fun enqueueImageGeneration(
        prompt: String,
        inputDraft: LocalImageInputDraft,
        options: LocalImageGenerationOptions,
        jobSnapshot: ImageGenerationJobSpec?
    ) {
        val cleanPrompt = (jobSnapshot?.prompt ?: prompt).trim().take(600)
        if (cleanPrompt.isBlank()) {
            _uiState.update { it.copy(statusMessage = "请输入图片描述") }
            return
        }
        if (imageGenerationJob?.isActive == true) {
            _uiState.update { it.copy(statusMessage = "已有图片生成任务正在运行，请先停止当前任务") }
            return
        }
        val jobId = "ui-img-${UUID.randomUUID()}"
        val enqueueState = _uiState.value
        val requestedBackend = jobSnapshot?.backend ?: enqueueState.selectedImageBackend
        val requestedLocalModel = if (requestedBackend == ImageBackend.LOCAL) {
            jobSnapshot?.localModelSnapshot ?: enqueueState.selectedLocalImageModel()
        } else {
            null
        }
        val requestedCloudConfig = if (requestedBackend == ImageBackend.CLOUD) {
            jobSnapshot?.cloudConfigSnapshot ?: enqueueState.selectedImageCloudConfig()?.normalized()
        } else {
            null
        }
        if (requestedBackend == ImageBackend.LOCAL && requestedLocalModel == null) {
            _uiState.update { it.copy(statusMessage = "请先在模型管理的本地页导入并选择图像生成引擎。") }
            return
        }
        if (requestedBackend == ImageBackend.CLOUD && requestedCloudConfig == null) {
            _uiState.update { it.copy(statusMessage = "请先在模型管理的云端页接入并选择图像生成模型。") }
            return
        }
        val requestedModelId = jobSnapshot?.modelId
            ?: requestedLocalModel?.id
            ?: enqueueState.selectedCloudImageModelId
        val requestedModelName = jobSnapshot?.modelName
            ?: requestedLocalModel?.displayName
            ?: requestedCloudConfig?.displayName?.takeIf(String::isNotBlank)
            ?: requestedCloudConfig?.imageModel.orEmpty()
        val requestedChatSessionId = jobSnapshot?.chatSessionId ?: enqueueState.activeChatSessionId
        val baseOptions = jobSnapshot?.options ?: options
        val queuedOptions = if (
            requestedBackend == ImageBackend.LOCAL &&
            requestedLocalModel?.supportsLiveProjectionPreview() == true &&
            baseOptions.preview == null
        ) {
            baseOptions.copy(
                preview = LocalImagePreviewOptions(
                    interval = 1,
                    mode = LocalImagePreviewMode.PROJECTION
                )
            )
        } else {
            baseOptions
        }
        val queuedInputDraft = jobSnapshot?.inputDraft ?: inputDraft
        val queuedJobSpec = jobSnapshot ?: ImageGenerationJobSpec(
            prompt = cleanPrompt,
            backend = requestedBackend,
            localModelSnapshot = requestedLocalModel,
            cloudConfigSnapshot = requestedCloudConfig,
            modelId = requireNotNull(requestedModelId) { "The queued image model id is missing." },
            modelName = requestedModelName,
            inputDraft = queuedInputDraft,
            options = queuedOptions,
            chatSessionId = requestedChatSessionId
        )
        val generationLease = localImageGenerationCoordinator.tryAcquire(jobId)
        if (generationLease == null) {
            _uiState.update {
                it.copy(statusMessage = "已有 UI 或 Local API 图片任务正在运行，请等待完成或先停止当前任务")
            }
            return
        }
        activeImageGenerationJobId = jobId
        activeImageGenerationBackend = requestedBackend
        activeImageGenerationModelId = requestedModelId
        val prepareError = runCatching {
            requestedLocalModel?.let { model -> localImageWorkerClient.begin(model.runtime) }
        }.exceptionOrNull()
        if (prepareError != null) {
            localImageGenerationCoordinator.release(generationLease)
            activeImageGenerationJobId = null
            activeImageGenerationBackend = null
            activeImageGenerationModelId = null
            _uiState.update {
                it.copy(statusMessage = "本地生图 worker 准备失败：${prepareError.message ?: "未知错误"}")
            }
            return
        }
        _uiState.update { state ->
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
                            spec = queuedJobSpec,
                            message = if (requestedBackend == ImageBackend.LOCAL) "等待本地生图" else "等待云端生图"
                        )
                    ) + state.imageJobs
                ).take(8),
                statusMessage = "图片任务已排队"
            )
        }
        imageGenerationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { state ->
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            ImageGenerationStatusRecord.GENERATING,
                            if (requestedBackend == ImageBackend.LOCAL) "正在调用本地图像生成引擎，本地生成可能需要数分钟" else "正在调用云端图片模型"
                        )
                    )
                }
                if (requestedBackend == ImageBackend.LOCAL) {
                    startLocalImageGenerationWatchdog(jobId)
                }
                val generatedImages = runCatching {
                    when (requestedBackend) {
                        ImageBackend.LOCAL -> {
                            val model = requireNotNull(requestedLocalModel) {
                                "The queued local image model snapshot is missing."
                            }
                            createLocalGeneratedImageAsset(
                                requestId = jobId,
                                prompt = cleanPrompt,
                                model = model,
                                options = queuedOptions,
                                inputDraft = queuedInputDraft,
                                chatSessionId = requestedChatSessionId,
                                generationMetadata = queuedJobSpec.toHistoryMetadata(),
                                onProgress = { progress ->
                                    val message = progress.toImageGenerationMessage()
                                    val publishedPreview = publishLocalImagePreview(jobId, progress)
                                    _uiState.update { state ->
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
                        }
                        ImageBackend.CLOUD -> {
                            require(queuedInputDraft.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE) {
                                "The selected cloud image connector does not implement ${queuedInputDraft.taskMode.wireName}; no request was sent."
                            }
                            require(queuedOptions.clipSkip == null &&
                                queuedOptions.vaeTiling == null &&
                                queuedOptions.preview == null
                            ) {
                                "The selected cloud image connector does not implement local advanced image controls."
                            }
                            val imageConfig = requireNotNull(requestedCloudConfig) {
                                "The queued cloud image model snapshot is missing."
                            }
                            listOf(
                                createCloudGeneratedImageAsset(
                                    prompt = cleanPrompt,
                                    config = imageConfig,
                                    chatSessionId = requestedChatSessionId,
                                    generationMetadata = queuedJobSpec.toHistoryMetadata()
                                )
                            )
                        }
                    }
                }.getOrElse { error ->
                    val cancelled = error is CancellationException ||
                        error is LocalImageWorkerCancelledException
                    val message = if (cancelled) {
                        "已取消图片生成"
                    } else {
                        error.message ?: "图片生成模型调用失败"
                    }
                    _uiState.update { state ->
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
                val persistenceError = runCatching {
                    chatSessionStore.upsertImages(generatedImages)
                }.exceptionOrNull()
                if (persistenceError != null) {
                    generatedImages.forEach(ImageAssetRecord::deleteLocalCopy)
                    val message = persistenceError.message ?: "图片库数据库提交失败"
                    _uiState.update { state ->
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                jobId,
                                ImageGenerationStatusRecord.FAILED,
                                message
                            ),
                            statusMessage = "图片生成完成，但保存失败：$message"
                        )
                    }
                    return@launch
                }
                _uiState.update { state ->
                    val committedImages = (
                        generatedImages + state.images.filterNot { existing ->
                            generatedImages.any { generated -> generated.id == existing.id }
                        }
                    ).sortedImagesForLibrary()
                    state.copy(
                        images = committedImages,
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            ImageGenerationStatusRecord.DONE,
                            if (generatedImages.size == 1) "已保存到图片库" else "已保存 ${generatedImages.size} 张图片",
                            generatedImages.first().id
                        ),
                        statusMessage = if (generatedImages.size == 1) {
                            "已生成图片并保存到图片库：${generatedImages.first().name}"
                        } else {
                            "已生成 ${generatedImages.size} 张图片并保存到图片库"
                        }
                    )
                }
            } finally {
                cleanupLocalImagePreviews(jobId)
                check(localImageGenerationCoordinator.release(generationLease)) {
                    "UI image generation lease was replaced before request completion."
                }
                if (activeImageGenerationJobId == jobId) {
                    activeImageGenerationJobId = null
                    activeImageGenerationBackend = null
                    activeImageGenerationModelId = null
                }
                imageGenerationJob = null
            }
        }
    }

    private fun startLocalImageGenerationWatchdog(jobId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            delay(LOCAL_IMAGE_GENERATION_WATCHDOG_MS)
            _uiState.update { state ->
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

    fun cancelImageGeneration() {
        viewModelScope.launch(Dispatchers.IO) {
            val jobId = activeImageGenerationJobId
            val localGeneration = activeImageGenerationBackend == ImageBackend.LOCAL
            val nativeCancelRequested = if (localGeneration) localImageWorkerClient.cancel() else false
            if (jobId != null) {
                _uiState.update { state ->
                    val message = if (localGeneration && nativeCancelRequested) {
                        "正在停止本地生图，当前图执行结束后退出"
                    } else {
                        "已停止图片生成"
                    }
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            if (localGeneration && nativeCancelRequested) {
                                ImageGenerationStatusRecord.GENERATING
                            } else {
                                ImageGenerationStatusRecord.CANCELLED
                            },
                            message
                        ),
                        statusMessage = message
                    )
                }
                if (!(localGeneration && nativeCancelRequested)) {
                    imageGenerationJob?.cancel()
                }
            } else {
                imageGenerationJob?.cancel()
                _uiState.update { state ->
                    val working = state.imageJobs.firstOrNull {
                        it.status == ImageGenerationStatusRecord.QUEUED || it.status == ImageGenerationStatusRecord.GENERATING
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
        return when {
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
            phase == "failed" -> message.ifBlank { "本地生图失败" }
            else -> "正在准备本地生图 · ${elapsedSeconds}s$threadText"
        }
    }

    private fun publishLocalImagePreview(
        jobId: String,
        progress: LocalImageProgress
    ): PublishedLocalImagePreview? = runCatching {
        if (progress.previewRevision <= 0L || progress.previewPath.isBlank()) return@runCatching null
        val currentRevision = _uiState.value.imageJobs
            .firstOrNull { it.id == jobId }
            ?.previewRevision
            ?: 0L
        if (progress.previewRevision <= currentRevision) return@runCatching null
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
        val source = File(progress.previewPath).canonicalFile
        require(
            source.isFile && source.path.startsWith(cacheRoot.path + File.separator) &&
                ".preview-" in source.name && source.length() in 1..MAX_LOCAL_IMAGE_PREVIEW_BYTES
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

        val root = File(cacheRoot, LOCAL_IMAGE_UI_PREVIEW_DIRECTORY).canonicalFile
        require(root.isDirectory || root.mkdirs()) { "Unable to create the image preview directory." }
        val requestToken = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        val requestDir = File(root, requestToken).canonicalFile
        require(requestDir.parentFile == root && (requestDir.isDirectory || requestDir.mkdirs())) {
            "Unable to create the request preview directory."
        }
        val fileName = "preview-${progress.previewRevision}.png"
        val published = File(requestDir, fileName).takeIf(File::isFile)
            ?: writeImageAssetBytesAtomically(requestDir, fileName, bytes)
        PublishedLocalImagePreview(
            uriString = Uri.fromFile(published).toString(),
            mode = progress.previewMode,
            step = progress.previewStep,
            revision = progress.previewRevision,
            width = progress.previewWidth,
            height = progress.previewHeight
        )
    }.getOrNull()

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
        viewModelScope.launch(Dispatchers.IO) {
            val removed = _uiState.value.images.firstOrNull { it.id == imageId }
            if (removed == null) {
                _uiState.update { it.copy(statusMessage = "未找到要删除的图片") }
                return@launch
            }
            val error = runCatching { chatSessionStore.deleteImages(listOf(imageId)) }.exceptionOrNull()
            if (error != null) {
                _uiState.update { it.copy(statusMessage = "图片删除失败：${error.message ?: "数据库提交失败"}") }
                return@launch
            }
            removed.deleteLocalCopy()
            _uiState.update { state ->
                state.copy(
                    images = state.images.filterNot { it.id == imageId },
                    statusMessage = "已从图片库移除：${removed.name}"
                )
            }
        }
    }

    fun clearImageLibrary() {
        val imagesToRemove = _uiState.value.images
        if (imagesToRemove.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "图片库已为空") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val error = runCatching { chatSessionStore.clearImages() }.exceptionOrNull()
            if (error != null) {
                _uiState.update { it.copy(statusMessage = "图片库清空失败：${error.message ?: "数据库提交失败"}") }
                return@launch
            }
            imagesToRemove.forEach { it.deleteLocalCopy() }
            _uiState.update {
                it.copy(
                    images = emptyList(),
                    imageJobs = it.imageJobs.filter { job -> job.status == ImageGenerationStatusRecord.GENERATING },
                    statusMessage = "已清空图片库：${imagesToRemove.size} 张图片"
                )
            }
        }
    }

    fun importLocalImageModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
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
                    _uiState.update {
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
        _uiState.update { state ->
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

    fun selectLocalImageModel(modelId: String) {
        if (rejectImageModelSwitchWhileGenerationIsActive()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (rejectImageModelSwitchWhileGenerationIsActive()) return@launch
            val model = _uiState.value.localImageModels.firstOrNull { it.id == modelId }
            if (model == null) {
                _uiState.update { it.copy(statusMessage = "未找到本地图像生成引擎") }
                return@launch
            }
            val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(listOf(model))
            model.localImageReadinessForUi(qnnVerificationCurrentByModelId[model.id])?.let { message ->
                _uiState.update {
                    it.copy(
                        qnnImageVerificationCurrentByModelId = it.qnnImageVerificationCurrentByModelId +
                            qnnVerificationCurrentByModelId,
                        statusMessage = message
                    )
                }
                return@launch
            }
            val diagnostic = model.localImageVerificationDiagnosticMessage()
            val runningModelName = activeImageGenerationModelId
                ?.let { activeId -> _uiState.value.localImageModels.firstOrNull { it.id == activeId } }
                ?.displayName
            localImageModelStore.saveSelectedModelId(model.id)
            localImageModelStore.saveSelectedBackend(ImageBackend.LOCAL)
            _uiState.update {
                it.copy(
                    qnnImageVerificationCurrentByModelId = it.qnnImageVerificationCurrentByModelId +
                        qnnVerificationCurrentByModelId,
                    selectedLocalImageModelId = model.id,
                    selectedImageBackend = ImageBackend.LOCAL,
                    statusMessage = buildString {
                        append("图片页已切换到本地生图：${model.displayName}")
                        if (activeImageGenerationJobId != null) {
                            append("；当前任务仍固定使用")
                            append(runningModelName?.let { " $it" }.orEmpty())
                            append("，本次切换从下一任务生效")
                        }
                        diagnostic?.let { append("；").append(it) }
                    }
                )
            }
        }
    }

    fun verifyLocalImageModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val model = _uiState.value.localImageModels.firstOrNull { it.id == modelId }
            if (model == null) {
                _uiState.update { it.copy(statusMessage = "未找到本地图像生成引擎") }
                return@launch
            }
            busy("正在校验 ${model.displayName}...")
            val primary = File(model.path)
            val readiness = model.localImageReadinessMessage()
            val structuralReadiness = model.localImageStructuralReadinessMessage()
            val readable = model.configured && primary.isFile && primary.canRead()
            var verification = when {
                !readable -> false to "主模型文件不可读，请重新导入或重新下载。"
                model.runtime == LocalImageRuntime.MNN_DIFFUSION && structuralReadiness != null ->
                    false to structuralReadiness
                model.runtime == LocalImageRuntime.MNN_DIFFUSION ->
                    verifyMnnDiffusionImageRuntime(model)
                model.runtime == LocalImageRuntime.QNN_HTP && structuralReadiness != null ->
                    false to structuralReadiness
                model.runtime == LocalImageRuntime.QNN_HTP ->
                    verifyQnnImageRuntime(model)
                readiness != null -> false to readiness
                else -> {
                    val componentText = if (model.componentCount > 1) "，组件 ${model.componentCount} 个" else ""
                    true to "主模型可读$componentText，可用于图片页本地生图。"
                }
            }
            val qnnPersistence = qnnVerificationPersistence(model, verification)
            verification = qnnPersistence.verification
            val verifiedModel = model.copy(
                verificationStatus = verification.toLocalImageVerificationStatus(model.runtime, model.family),
                verificationMessage = verification.second,
                verifiedAt = System.currentTimeMillis(),
                qnnVerificationStamp = qnnPersistence.stamp
            )
            val models = localImageModelStore.updateModel(verifiedModel)
            val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(models)
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
            _uiState.update {
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

    private fun qnnVerificationPersistence(
        model: LocalImageModelRecord,
        verification: Pair<Boolean, String>
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
                stamp = qnnImageVerificationStampFor(getApplication<Application>(), bundleRoot)
            )
        }.getOrElse { error ->
            QnnVerificationPersistence(
                verification = false to "QNN 生图校验完成，但无法保存当前设备/运行时校验戳：${error.message ?: "未知错误"}",
                stamp = ""
            )
        }
    }

    private suspend fun verifyQnnImageRuntime(model: LocalImageModelRecord): Pair<Boolean, String> =
        runCatching {
            val bundleRoot = model.bundleRoot
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("QNN 图像引擎缺少完整组件目录。")
            val seedOptions = LocalImageGenerationOptions(seed = 1234)
            val baseResolution = resolveLocalImageExecutionProfile(model, seedOptions, bundleRoot)
            val options = seedOptions.copy(
                steps = baseResolution.profile.scheduler.minSteps,
                width = baseResolution.layers.resolved.width,
                height = baseResolution.layers.resolved.height,
                cfgScale = baseResolution.layers.resolved.cfgScale,
                useCfg = baseResolution.layers.resolved.useCfg,
                sampleMethod = imageSchedulerProductName(baseResolution.layers.resolved.scheduler)
            )
            val verificationResolution = resolveLocalImageExecutionProfile(model, options, bundleRoot)
            val requestId = "verify-qnn-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            localImageWorkerClient.begin(model.runtime)
            val result = localImageWorkerClient.generate(
                model = model,
                prompt = "a small white ceramic cup on a wooden desk, morning light, clean background, photo realistic",
                options = options,
                requestId = requestId
            )
            val execution = JSONObject(result.executionMetadataJson)
            ImageExecutionProfileNativeContract.parseAndValidate(verificationResolution, execution)
            require(execution.optBoolean("npuActive", false)) {
                "QNN verification did not prove that the NPU was active."
            }
            require(execution.optBoolean("qnnGraphExecution", false)) {
                "QNN verification did not prove graph execution."
            }
            require(execution.optBoolean("nativeExecution", false)) {
                "QNN verification did not prove native execution."
            }
            require(!execution.optBoolean("fallback", true)) {
                "QNN verification unexpectedly used a fallback path."
            }
            require(execution.optLong("nativeGenerationSequence", 0L) > 0L) {
                "QNN verification is missing its native generation sequence."
            }
            require(result.bytes.isNotEmpty()) { "QNN verification produced an empty image." }
            val resolved = verificationResolution.layers.resolved
            true to buildString {
                append("QNN 产品生图校验通过：${resolved.width}x${resolved.height}，${resolved.steps} step")
                execution.optLong("elapsedMs").takeIf { it > 0L }?.let { append("，耗时 ${it}ms") }
                execution.optLong("unetExecuteMsAvg").takeIf { it > 0L }?.let { append("，UNet ${it}ms/step") }
                append("，NPU/native/fallback 证据完整。")
            }
        }.getOrElse { error ->
            false to (error.message ?: "QNN 图像引擎校验异常。")
        }

    private suspend fun verifyMnnDiffusionImageRuntime(model: LocalImageModelRecord): Pair<Boolean, String> =
        runCatching {
            val bundleRoot = model.bundleRoot
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?.takeIf(File::isDirectory)
                ?: File(model.path).parentFile?.takeIf(File::isDirectory)
                ?: error("MNN-Diffusion 缺少完整组件目录。")
            val seedOptions = LocalImageGenerationOptions(seed = 42)
            val baseResolution = resolveLocalImageExecutionProfile(model, seedOptions, bundleRoot)
            require(baseResolution.profile.task == ImageTask.TEXT_TO_IMAGE) {
                "该 MNN 图像编辑模型需要源图片，不能用纯文本生成校验代替。"
            }
            val resolved = baseResolution.layers.resolved
            val options = seedOptions.copy(
                width = resolved.width,
                height = resolved.height,
                steps = resolved.steps,
                threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                cfgScale = resolved.cfgScale,
                sampleMethod = imageSchedulerProductName(resolved.scheduler),
                backendMode = "opencl",
                memoryMode = 0,
                runner = "direct",
                useCfg = resolved.useCfg
            )
            val verificationResolution = resolveLocalImageExecutionProfile(model, options, bundleRoot)
            val requestId = "verify-mnn-${System.currentTimeMillis()}-${UUID.randomUUID()}"
            localImageWorkerClient.begin(model.runtime)
            val result = localImageWorkerClient.generate(
                model = model,
                prompt = "A single red cube on a clean white background, studio lighting, no text.",
                options = options,
                requestId = requestId
            )
            val execution = JSONObject(result.executionMetadataJson)
            ImageExecutionProfileNativeContract.parseAndValidate(verificationResolution, execution)
            require(execution.optString("runner").equals("direct", ignoreCase = true)) {
                "MNN verification did not use the direct runner."
            }
            require(mnnDiffusionBackendMatches("opencl", execution.optString("backendMode"))) {
                "MNN verification did not execute on OpenCL."
            }
            require(!execution.optBoolean("fallback", true)) {
                "MNN verification unexpectedly used a fallback path."
            }
            require(result.bytes.size >= 8 && result.bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            )) { "MNN-Diffusion product output is not a complete PNG." }
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)) {
                "MNN-Diffusion product PNG cannot be decoded."
            }
            val quality = try {
                require(bitmap.width == resolved.width && bitmap.height == resolved.height) {
                    "MNN-Diffusion decoded ${bitmap.width}x${bitmap.height}, " +
                        "expected ${resolved.width}x${resolved.height}."
                }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                evaluateLocalImageSmokePixels(bitmap.width, bitmap.height, pixels)
            } finally {
                bitmap.recycle()
            }
            require(quality.passed) { "MNN-Diffusion PNG quality smoke failed: ${quality.message}." }
            true to "MNN-Diffusion 产品 worker ${resolved.width}x${resolved.height}、" +
                "${resolved.steps}-step direct/OpenCL 校验通过，执行合同与像素质量一致。"
        }.getOrElse { error ->
            false to (error.message ?: "MNN-Diffusion 校验异常。")
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
        val preferredModel = models.firstOrNull { it.id == preferred.id } ?: preferred
        val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(models)
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
        viewModelScope.launch(Dispatchers.IO) {
            if (activeImageGenerationModelId == modelId || activeLocalApiImageModelId == modelId) {
                _uiState.update {
                    it.copy(statusMessage = "当前图片任务正在使用该模型，请停止或等待任务完成后再删除")
                }
                return@launch
            }
            val removed = _uiState.value.localImageModels.firstOrNull { it.id == modelId }
            val success = runCatching { localImageModelStore.deleteModel(modelId) }.getOrDefault(false)
            val models = localImageModelStore.loadModels()
            val qnnVerificationCurrentByModelId = currentQnnImageVerificationByModelId(models)
            val selectedId = selectedReadyLocalImageModelId(
                models = models,
                qnnVerificationCurrentByModelId = qnnVerificationCurrentByModelId,
                preferredId = localImageModelStore.loadSelectedModelId()
            )
            _uiState.update {
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
        _uiState.update { it.copy(repoInput = value) }
    }

    fun onHubQueryChange(value: String) {
        _uiState.update { it.copy(hubQuery = value) }
    }

    fun updateParams(params: GenerationParams) {
        val previous = _uiState.value.params
        val changedRuntimeFields = runtimeParameterChanges(previous, params)
        if (changedRuntimeFields.isNotEmpty()) {
            runtimeUserOverrideFields += changedRuntimeFields
            persistRuntimeUserOverrideFields(runtimeUserOverrideFields)
        }
        persistGenerationParams(params)
        val updatedAssistants = updatedAssistantsWithParams(params)
        assistantStore.saveAssistants(updatedAssistants)
        val hasLoadedModel = _uiState.value.loadedModelId != null
        val reloadRequired = hasLoadedModel && executionProfileDiffers(params, engine.activeExecutionProfile())
        _uiState.update { state ->
            state.copy(
                params = params,
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
        if (reloadRequired && changedRuntimeFields.isNotEmpty()) {
            stageDirectParameterProfile(params)
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
                        _uiState.update {
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
                    _uiState.update {
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
                _uiState.update {
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
                        _uiState.update {
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
        reasoningMode: ReasoningMode,
        memoryEnabled: Boolean,
        webSearchEnabled: Boolean,
        fileContextEnabled: Boolean
    ) {
        val cleanName = name.trim().take(36).ifBlank { "未命名助手" }
        val cleanAvatar = avatar.trim().take(4)
        val cleanTag = tag.trim().take(24)
        val cleanPrompt = systemPrompt.trim().ifBlank { GenerationParams().systemPrompt }
        val cleanDefaultModelMode = defaultModelMode.normalizedAssistantModelMode()
        val cleanDefaultModelId = defaultModelId
            ?.trim()
            ?.takeIf { it.isNotBlank() && cleanDefaultModelMode != ASSISTANT_MODEL_MODE_FOLLOW_CURRENT }
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val existing = id?.let { assistantId -> state.assistants.firstOrNull { it.id == assistantId } }
        val assistant = (existing ?: AssistantRecord(name = cleanName, createdAt = now)).copy(
            name = cleanName,
            avatar = cleanAvatar,
            tag = cleanTag,
            systemPrompt = cleanPrompt,
            defaultModelMode = cleanDefaultModelMode,
            defaultModelId = cleanDefaultModelId,
            paramsJson = state.params.copy(
                systemPrompt = cleanPrompt,
                temperature = temperature.coerceIn(0f, 2f),
                topP = topP.coerceIn(0f, 1f),
                nPredict = nPredict.coerceIn(128, 65_536),
                reasoningMode = reasoningMode,
                hideReasoning = reasoningMode == ReasoningMode.OFF
            ).toAssistantGenerationJson(),
            memoryEnabled = memoryEnabled,
            webSearchEnabled = webSearchEnabled,
            fileContextEnabled = fileContextEnabled,
            updatedAt = now
        )
        val updatedAssistants = if (existing == null) {
            (state.assistants + assistant).distinctBy { it.id }
        } else {
            state.assistants.map { if (it.id == assistant.id) assistant else it }
        }
        assistantStore.saveAssistants(updatedAssistants)
        assistantStore.saveSelectedAssistantId(assistant.id)
        val updatedParams = assistant.toGenerationParams(state.params)
        persistGenerationParams(updatedParams)
        val updatedSessions = state.chatSessions.bindSession(
            sessionId = state.activeChatSessionId,
            assistantId = assistant.id,
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        _uiState.update {
            it.copy(
                assistants = updatedAssistants,
                selectedAssistantId = assistant.id,
                params = updatedParams,
                chatSessions = updatedSessions,
                statusMessage = if (existing == null) "已创建助手：${assistant.name}" else "已更新助手：${assistant.name}"
            )
        }
        persistChatSessions(updatedSessions)
        applyAssistantDefaultModel(assistant)
    }

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
        val config = buildWebSearchConfig(
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
        webSearchStore.save(config)
        _uiState.update {
            val nextTurnMode = if (config.enabled) it.webSearchTurnMode else WebSearchTurnMode.FOLLOW
            it.copy(
                webSearchConfig = config,
                webSearchTurnMode = nextTurnMode,
                webSearchResearchModeOverride = it.webSearchResearchModeOverride?.takeIf { config.enabled },
                webSearchOneShotEnabled = nextTurnMode == WebSearchTurnMode.ON && config.enabled,
                webSearchStatusMessage = null,
                statusMessage = if (config.realSearchConfigured) {
                    "联网检索已保存：${config.realSearchProviderLabel.ifBlank { config.providerLabel }}"
                } else if (config.isPublicCheckSource) {
                    "已保存协议自检源：可在设置页测试，聊天页关键词搜索请配置真实搜索源"
                } else if (config.enabled) {
                    "联网检索已启用：可直读网页链接；关键词搜索还需要补齐地址或 Key"
                } else {
                    "联网检索已关闭"
                }
            )
        }
    }

    fun testWebSearchConfig(query: String) {
        testWebSearchConfig(query, _uiState.value.webSearchConfig)
    }

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
        testWebSearchConfig(
            query = query,
            config = buildWebSearchConfig(
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
        )
    }

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
        val config = buildWebSearchConfig(
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
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    webSearchStatusMessage = "正在进行联网网络预检...",
                    statusMessage = "正在进行联网网络预检..."
                )
            }
            val started = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    buildWebSearchPreflightReport(
                        config = config,
                        environmentChecks = buildAndroidWebSearchNetworkChecks()
                    )
                }
            }
            _uiState.update { state ->
                val elapsedMs = System.currentTimeMillis() - started
                result.fold(
                    onSuccess = { report ->
                        val warningChecks = report.checks.filter { it.startsWith("需检查") }
                        val diagnostics = appendWebSearchDiagnostic(
                            WebSearchDiagnosticRecord(
                                providerLabel = config.providerLabel,
                                triggerModeLabel = config.triggerMode.label,
                                query = "网络预检",
                                searchedQueries = listOf(config.endpointForRequest()).filter { it.isNotBlank() },
                                elapsedMs = elapsedMs,
                                success = report.ok,
                                message = report.message,
                                healthScore = if (report.ok) 86 else 35,
                                healthLabel = if (report.ok) "健康" else "需检查",
                                healthReasons = report.checks,
                                qualityScore = if (report.ok) 80 else 20,
                                qualityLabel = if (report.ok) "配置可用" else "配置需检查",
                                qualityReasons = report.checks,
                                warnings = warningChecks,
                                closedLoopChecks = report.checks
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = report.message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = if (report.ok) "联网网络预检通过" else "联网网络预检需检查"
                        )
                    },
                    onFailure = { error ->
                        val message = "网络预检失败：${error.message ?: "未知错误"}"
                        val diagnostics = appendWebSearchDiagnostic(
                            WebSearchDiagnosticRecord(
                                providerLabel = config.providerLabel,
                                triggerModeLabel = config.triggerMode.label,
                                query = "网络预检",
                                searchedQueries = listOf(config.endpointForRequest()).filter { it.isNotBlank() },
                                elapsedMs = elapsedMs,
                                success = false,
                                message = message,
                                healthScore = 0,
                                healthLabel = "失败",
                                healthReasons = listOf(message),
                                qualityScore = 0,
                                qualityLabel = "预检失败",
                                qualityReasons = listOf(message),
                                warnings = listOf(message),
                                closedLoopChecks = listOf(message)
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = "联网网络预检失败"
                        )
                    }
                )
            }
        }
    }

    private fun buildAndroidWebSearchNetworkChecks(): List<String> {
        val app = getApplication<Application>()
        val checks = mutableListOf<String>()
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (connectivity == null) {
            return listOf("需检查：系统网络状态服务不可用，无法确认当前手机联网环境。")
        }
        when (connectivity.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> {
                checks += "需检查：系统流量节省正在限制后台联网；请允许 MCA 使用网络后再测试。"
            }
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> {
                checks += "通过：系统流量节省未限制 MCA 联网。"
            }
        }
        val activeNetwork = connectivity.activeNetwork
        if (activeNetwork == null) {
            checks += "需检查：手机当前没有活动网络，请先连接 Wi-Fi 或移动数据。"
            checks += "需检查：如果系统浏览器可以联网，请检查系统设置或安全中心是否禁止 MCA 使用 WLAN/移动数据，并排查 VPN、私人 DNS、代理或省电策略。"
            return checks
        }
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
        if (capabilities == null) {
            checks += "需检查：无法读取当前网络能力，请检查系统网络权限或重新连接网络。"
            return checks
        }
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("移动数据")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }.ifEmpty { listOf("未知网络") }
        checks += "通过：当前活动网络：${transports.joinToString(" / ")}。"
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            checks += "通过：系统报告当前网络具备 Internet 能力。"
        } else {
            checks += "需检查：系统报告当前网络不具备 Internet 能力，搜索请求可能无法发出。"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            checks += "通过：系统已验证当前网络可访问公网。"
        } else {
            checks += "需检查：系统尚未验证当前网络可访问公网，可能是未登录 Wi-Fi、DNS 异常、网络受限或代理/VPN 问题。"
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            checks += "提示：当前网络可能经过 VPN；如果 DNS 失败，请优先检查 VPN 或代理规则。"
        }
        val proxyHost = listOfNotNull(
            System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() },
            System.getProperty("https.proxyHost")?.takeIf { it.isNotBlank() }
        ).distinct()
        if (proxyHost.isNotEmpty()) {
            checks += "提示：检测到系统代理 ${proxyHost.joinToString(" / ")}；搜索接口会受代理可达性影响。"
        }
        runCatching {
            val resolver = app.contentResolver
            val privateDnsMode = Settings.Global.getString(resolver, "private_dns_mode").orEmpty()
            val privateDnsHost = Settings.Global.getString(resolver, "private_dns_specifier").orEmpty()
            if (privateDnsMode.isNotBlank() && privateDnsMode != "off") {
                checks += buildString {
                    append("提示：私人 DNS 模式为 ")
                    append(privateDnsMode)
                    if (privateDnsHost.isNotBlank()) append("（$privateDnsHost）")
                    append("；如域名解析失败，请检查该 DNS 服务。")
                }
            }
        }
        return checks
    }

    private fun testWebSearchConfig(query: String, config: WebSearchConfig) {
        val cleanQuery = query.trim().ifBlank { "MCA 本地 AI" }
        val plan = buildWebSearchPlan(cleanQuery, config.researchMode)
        if (!config.configured && !(config.canReadDirectUrls && plan.directUrls.isNotEmpty())) {
            _uiState.update {
                it.copy(
                    webSearchStatusMessage = if (plan.directUrls.isNotEmpty()) {
                        "请先启用联网检索，再测试网页链接读取"
                    } else {
                        "请先保存可用的联网检索配置"
                    },
                    statusMessage = if (plan.directUrls.isNotEmpty()) {
                        "请先启用联网检索"
                    } else {
                        "请先保存可用的联网检索配置"
                    }
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    webSearchStatusMessage = "正在测试联网检索：$cleanQuery",
                    statusMessage = "正在测试联网检索..."
                )
            }
            val started = System.currentTimeMillis()
            val result = runCatching { webSearchProvider.search(plan, config) }
            _uiState.update { state ->
                result.fold(
                    onSuccess = { searchResult ->
                        val success = searchResult.documents.isNotEmpty()
                        val message = if (success) {
                            "测试成功：${searchResult.documents.size} 个来源 · ${searchResult.elapsedMs}ms"
                        } else {
                            "测试完成：没有找到可靠来源"
                        }
                        val diagnostics = appendWebSearchDiagnostic(
                            searchResult.toDiagnosticRecord(
                                config = config,
                                success = success,
                                message = message
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = if (success) "联网检索测试成功" else "联网检索无结果"
                        )
                    },
                    onFailure = { error ->
                        val message = "测试失败：${error.message ?: "未知错误"}"
                        val diagnostics = appendWebSearchDiagnostic(
                            plan.toFailedDiagnosticRecord(
                                config = config,
                                elapsedMs = System.currentTimeMillis() - started,
                                message = message
                            )
                        )
                        state.copy(
                            busy = false,
                            webSearchStatusMessage = message,
                            webSearchDiagnostics = diagnostics,
                            statusMessage = "联网检索测试失败"
                        )
                    }
                )
            }
        }
    }

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
        testWebSearchTurn(
            query = query,
            config = buildWebSearchConfig(
                enabled = enabled,
                provider = provider,
                endpoint = endpoint,
                apiKey = apiKey,
                maxResults = maxResults,
                fetchPageContent = fetchPageContent,
                triggerMode = triggerMode,
                researchMode = researchMode,
                backupProviders = backupProviders
            ),
            allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest
        )
    }

    private fun testWebSearchTurn(
        query: String,
        config: WebSearchConfig,
        allowPublicCheckSourceForProtocolTest: Boolean = false
    ) {
        val cleanQuery = query.trim().ifBlank { "MCA 本地 AI 最新说明" }
        val plan = buildWebSearchPlan(cleanQuery, config.researchMode)
        if (!config.configured && !(config.canReadDirectUrls && plan.directUrls.isNotEmpty())) {
            _uiState.update {
                it.copy(
                    webSearchStatusMessage = if (plan.directUrls.isNotEmpty()) {
                        "请先启用联网检索，再进行闭环自检"
                    } else {
                        "请先填写可用搜索服务，再进行闭环自检"
                    },
                    statusMessage = "联网闭环自检未开始"
                )
            }
            return
        }
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    busy = true,
                    webSearchStatusMessage = "正在闭环自检：$cleanQuery",
                    statusMessage = "正在验证联网检索闭环..."
                )
            }
            val outcome = executeWebSearchForChatTurn(
                messages = listOf(ChatMessage(role = Role.USER, content = cleanQuery)),
                config = config,
                oneShotEnabled = true,
                assistantWebSearchEnabled = false,
                allowPublicCheckSourceForProtocolTest = allowPublicCheckSourceForProtocolTest,
                search = { turnPlan, turnConfig -> webSearchProvider.search(turnPlan, turnConfig) }
            )
            val diagnostics = outcome.diagnostic?.let(::appendWebSearchDiagnostic)
            val passed = outcome.success &&
                outcome.promptContext.isNotBlank() &&
                outcome.sourceReferences.isNotEmpty()
            val message = when {
                passed -> "闭环自检通过：${outcome.sourceReferences.size} 个来源 · 已生成上下文和来源卡片数据"
                !outcome.webSearchStatusMessage.isNullOrBlank() -> "闭环自检未通过：${outcome.webSearchStatusMessage}"
                else -> "闭环自检未通过：没有生成可用联网来源"
            }
            _uiState.update {
                it.copy(
                    busy = false,
                    webSearchStatusMessage = message,
                    webSearchDiagnostics = diagnostics ?: it.webSearchDiagnostics,
                    statusMessage = if (passed) "联网闭环自检通过" else "联网闭环自检未通过"
                )
            }
        }
    }

    private fun buildWebSearchConfig(
        enabled: Boolean,
        provider: String,
        endpoint: String,
        apiKey: String,
        maxResults: Int,
        fetchPageContent: Boolean,
        triggerMode: String,
        researchMode: String,
        backupProviders: List<WebSearchBackupProviderConfig> = emptyList()
    ): WebSearchConfig =
        WebSearchConfig(
            enabled = enabled,
            provider = WebSearchProviderType.from(provider),
            endpoint = endpoint.trim(),
            apiKey = apiKey.trim(),
            maxResults = maxResults.coerceIn(1, 8),
            fetchPageContent = fetchPageContent,
            triggerMode = WebSearchTriggerMode.from(triggerMode),
            researchMode = WebSearchResearchMode.from(researchMode),
            backupProviders = backupProviders.take(3)
        )

    fun toggleWebSearchForNextTurn() {
        val state = _uiState.value
        val assistantDefault = state.selectedAssistant()?.webSearchEnabled == true
        if (!state.webSearchConfig.enabled) {
            _uiState.update {
                it.copy(
                    webSearchTurnMode = WebSearchTurnMode.FOLLOW,
                    webSearchOneShotEnabled = false,
                    webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                    statusMessage = "请先启用联网检索"
                )
            }
            return
        }
        _uiState.update {
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
            _uiState.update {
                it.copy(
                    webSearchResearchModeOverride = null,
                    webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                    statusMessage = "请先启用联网检索"
                )
            }
            return
        }
        _uiState.update {
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
            _uiState.update {
                it.copy(
                    webSearchResearchModeOverride = null,
                    webSearchStatusMessage = "请先在系统设置 > 联网检索 启用联网",
                    statusMessage = "请先启用联网检索"
                )
            }
            return
        }
        val selected = WebSearchResearchMode.from(mode)
        _uiState.update {
            it.copy(
                webSearchResearchModeOverride = selected.takeUnless { next -> next == it.webSearchConfig.researchMode },
                webSearchStatusMessage = "本轮研究模式：${selected.label}",
                statusMessage = "本轮研究模式：${selected.label}"
            )
        }
    }

    fun clearWebSearchDiagnostics() {
        val records = webSearchDiagnosticStore.clear()
        _uiState.update {
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
        val assistant = state.assistants.firstOrNull { it.id == assistantId } ?: return
        assistantStore.saveSelectedAssistantId(assistant.id)
        val updatedParams = assistant.toGenerationParams(state.params)
        persistGenerationParams(updatedParams)
        val updatedSessions = state.chatSessions.bindSession(
            sessionId = state.activeChatSessionId,
            assistantId = assistant.id,
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        _uiState.update {
            it.copy(
                selectedAssistantId = assistant.id,
                params = updatedParams,
                chatSessions = updatedSessions,
                statusMessage = "已切换助手：${assistant.name}"
            )
        }
        persistChatSessions(updatedSessions)
        applyAssistantDefaultModel(assistant)
    }

    fun deleteAssistant(assistantId: String) {
        val state = _uiState.value
        if (assistantId == AssistantRecord.DEFAULT_ID || state.assistants.size <= 1) {
            _uiState.update { it.copy(statusMessage = "默认助手不能删除") }
            return
        }
        val removed = state.assistants.firstOrNull { it.id == assistantId } ?: return
        val remaining = state.assistants.filterNot { it.id == assistantId }
        val next = if (state.selectedAssistantId == assistantId) {
            remaining.firstOrNull { it.id == AssistantRecord.DEFAULT_ID } ?: remaining.first()
        } else {
            remaining.firstOrNull { it.id == state.selectedAssistantId } ?: remaining.first()
        }
        assistantStore.saveAssistants(remaining)
        assistantStore.saveSelectedAssistantId(next.id)
        val updatedParams = next.toGenerationParams(state.params)
        persistGenerationParams(updatedParams)
        val updatedSessions = state.chatSessions
            .map { session ->
                if (session.assistantId == assistantId) {
                    session.copy(assistantId = next.id)
                } else {
                    session
                }
            }
            .bindSession(
                sessionId = state.activeChatSessionId,
                assistantId = next.id,
                modelMode = state.selectedChatBackend.bindingValue(),
                modelId = state.currentChatModelId()
            )
        _uiState.update {
            it.copy(
                assistants = remaining,
                selectedAssistantId = next.id,
                params = updatedParams,
                chatSessions = updatedSessions,
                statusMessage = "已删除助手：${removed.name}"
            )
        }
        persistChatSessions(updatedSessions)
        applyAssistantDefaultModel(next)
    }

    fun importAssistantCard(rawJson: String) {
        val imported = runCatching {
            AssistantRecord.fromJson(JSONObject(rawJson), AssistantRecord.default(_uiState.value.params.systemPrompt, _uiState.value.params))
        }.getOrElse { error ->
            _uiState.update { it.copy(statusMessage = "角色卡导入失败：${error.message ?: "JSON 格式不正确"}") }
            return
        }
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val assistant = imported.copy(
            id = UUID.randomUUID().toString(),
            name = imported.name.ifBlank { "导入助手" }.take(36),
            systemPrompt = imported.systemPrompt.ifBlank { GenerationParams().systemPrompt },
            createdAt = now,
            updatedAt = now
        )
        val updatedAssistants = state.assistants + assistant
        assistantStore.saveAssistants(updatedAssistants)
        assistantStore.saveSelectedAssistantId(assistant.id)
        val updatedParams = assistant.toGenerationParams(state.params)
        persistGenerationParams(updatedParams)
        val updatedSessions = state.chatSessions.bindSession(
            sessionId = state.activeChatSessionId,
            assistantId = assistant.id,
            modelMode = state.selectedChatBackend.bindingValue(),
            modelId = state.currentChatModelId()
        )
        _uiState.update {
            it.copy(
                assistants = updatedAssistants,
                selectedAssistantId = assistant.id,
                params = updatedParams,
                chatSessions = updatedSessions,
                statusMessage = "已导入角色卡：${assistant.name}"
            )
        }
        persistChatSessions(updatedSessions)
        applyAssistantDefaultModel(assistant)
    }

    fun updateReasoningMode(mode: ReasoningMode) {
        val state = _uiState.value
        if (state.selectedChatBackend == ChatBackend.CLOUD) {
            _uiState.update { it.copy(statusMessage = CLOUD_REASONING_LOCKED_MESSAGE) }
            return
        }
        val updatedParams = state.params.copy(
            reasoningMode = mode,
            hideReasoning = mode == ReasoningMode.OFF
        ).let { params ->
            params.copy(nPredict = params.effectiveNPredict())
        }
        persistGenerationParams(updatedParams)
        _uiState.update {
            it.copy(
                params = updatedParams,
                statusMessage = "思考模式已切换为：${mode.label}"
            )
        }
    }

    fun showCloudReasoningModeLocked() {
        _uiState.update { it.copy(statusMessage = CLOUD_REASONING_LOCKED_MESSAGE) }
    }

    fun updateCloudApiEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(enabled = enabled))
        }
    }

    fun updateCloudApiFormat(value: String) {
        val format = listOf(CloudApiFormat.OPENAI_COMPATIBLE, CloudApiFormat.ANTHROPIC)
            .firstOrNull { it.name == value || it.label == value }
            ?: CloudApiFormat.OPENAI_COMPATIBLE
        val defaultBaseUrls = CloudApiFormat.entries.map { it.defaultBaseUrl }.filter { it.isNotBlank() }
        val defaultChatModels = CloudApiFormat.entries.map { it.defaultModel }.filter { it.isNotBlank() }
        _uiState.update { state ->
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
        _uiState.update { state ->
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
        val preset = cloudProviderPreset(value) ?: return
        _uiState.update { state ->
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
        _uiState.update { state ->
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
        _uiState.update { state ->
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
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(baseUrl = value))
        }
    }

    fun updateCloudApiKey(value: String) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(apiKey = value))
        }
    }

    fun updateCloudChatModel(value: String) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(chatModel = value))
        }
    }

    fun updateCloudSupportsVision(value: Boolean) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(supportsVision = value))
        }
    }

    fun updateCloudImageModel(value: String) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(imageModel = value))
        }
    }

    fun updateCloudImageSize(value: String) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(imageSize = value))
        }
    }

    fun updateCloudImageEndpointPath(value: String) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(imageEndpointPath = value))
        }
    }

    fun updateCloudDisplayName(value: String) {
        _uiState.update { state ->
            state.copy(cloudApiConfig = state.cloudApiConfig.copy(displayName = value))
        }
    }

    fun saveCloudApiConfig() {
        saveCloudChatModel()
    }

    fun saveCloudChatModel() {
        val config = _uiState.value.cloudApiConfig.normalized()
        if (!config.configured) {
            _uiState.update { it.copy(statusMessage = "请先填写对话推理模型的 Base URL、模型名和必要的 API Key。") }
            return
        }
        val editing = _uiState.value.cloudModels.firstOrNull {
            it.id == _uiState.value.editingCloudModelId && it.kind == CloudModelKind.CHAT
        }
        val record = editing?.copy(
            apiFormat = config.apiFormat,
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.chatModel,
            supportsVision = config.supportsVision,
            imageSize = config.imageSize,
            updatedAt = System.currentTimeMillis()
        ) ?: _uiState.value.cloudModels.matchingCloudModel(
            kind = CloudModelKind.CHAT,
            format = config.apiFormat,
            baseUrl = config.baseUrl,
            modelName = config.chatModel
        )?.copy(
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            apiKey = config.apiKey,
            supportsVision = config.supportsVision,
            updatedAt = System.currentTimeMillis()
        ) ?: CloudModelRecord(
            kind = CloudModelKind.CHAT,
            apiFormat = config.apiFormat,
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.chatModel,
            supportsVision = config.supportsVision,
            imageSize = config.imageSize
        )
        val models = _uiState.value.cloudModels.upsertCloudModel(record)
        val selectedChatId = record.id
        cloudApiStore.save(config)
        cloudApiStore.saveModels(models)
        cloudApiStore.saveSelectedCloudChatModelId(selectedChatId)
        cloudApiStore.saveSelectedBackend(ChatBackend.CLOUD)
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        _uiState.update { state ->
            sessionsToPersist = state.chatSessions.bindSession(
                sessionId = state.activeChatSessionId,
                assistantId = state.selectedAssistantId,
                modelMode = ChatBackend.CLOUD.bindingValue(),
                modelId = selectedChatId
            )
            state.copy(
                cloudApiConfig = config,
                cloudModels = models,
                selectedCloudChatModelId = selectedChatId,
                selectedChatBackend = ChatBackend.CLOUD,
                editingCloudModelId = null,
                chatSessions = sessionsToPersist,
                statusMessage = "已保存并加载云端推理模型：${record.displayName}"
            )
        }
        persistChatSessions(sessionsToPersist)
    }

    fun saveCloudImageModel() {
        val config = _uiState.value.cloudApiConfig.normalized()
        if (!config.imageConfigured) {
            _uiState.update { it.copy(statusMessage = "请先选择支持生图的协议，并填写生图模型、Base URL 和必要的 API Key。") }
            return
        }
        val displayName = config.displayName.trim().ifBlank { "${config.providerName} · ${config.imageModel}" }
        val editing = _uiState.value.cloudModels.firstOrNull {
            it.id == _uiState.value.editingCloudModelId && it.kind == CloudModelKind.IMAGE
        }
        val record = editing?.copy(
            apiFormat = config.apiFormat,
            imageApiFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest(),
            providerName = config.providerName,
            displayName = displayName,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.imageModel,
            imageSize = config.imageSize,
            updatedAt = System.currentTimeMillis()
        ) ?: _uiState.value.cloudModels.matchingCloudModel(
            kind = CloudModelKind.IMAGE,
            format = config.apiFormat,
            baseUrl = config.baseUrl,
            modelName = config.imageModel,
            imageFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest()
        )?.copy(
            imageApiFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest(),
            providerName = config.providerName,
            displayName = displayName,
            apiKey = config.apiKey,
            imageSize = config.imageSize,
            updatedAt = System.currentTimeMillis()
        ) ?: CloudModelRecord(
            kind = CloudModelKind.IMAGE,
            apiFormat = config.apiFormat,
            imageApiFormat = config.imageApiFormat,
            imageEndpointPath = config.imageEndpointPathForRequest(),
            providerName = config.providerName,
            displayName = displayName,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.imageModel,
            imageSize = config.imageSize
        )
        val models = _uiState.value.cloudModels.upsertCloudModel(record)
        cloudApiStore.save(config)
        cloudApiStore.saveModels(models)
        val selectedImageId = record.id
        cloudApiStore.saveSelectedCloudImageModelId(selectedImageId)
        localImageModelStore.saveSelectedBackend(ImageBackend.CLOUD)
        _uiState.update { state ->
            state.copy(
                cloudApiConfig = config,
                cloudModels = models,
                selectedCloudImageModelId = selectedImageId,
                selectedImageBackend = ImageBackend.CLOUD,
                editingCloudModelId = null,
                statusMessage = "已保存并设为当前图像生成模型：${record.modelName}"
            )
        }
    }

    fun testCloudApiConfig() {
        val config = _uiState.value.cloudApiConfig.normalized()
        if (!config.configured) {
            _uiState.update { it.copy(statusMessage = "请先启用云端 API，并填写 Base URL、API Key 和对话推理模型。") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在快速测试云端 API...")
            cloudChatProvider.quickTest(config)
                .onSuccess {
                    cloudApiStore.save(config)
                    _uiState.update {
                        it.copy(
                            busy = false,
                            cloudApiConfig = config,
                            statusMessage = "云端 API 快速测试成功：${config.safeDisplayName()}"
                        )
                    }
                }
                .onFailure { error ->
                    fail("云端 API 快速测试失败：${error.message ?: "请求失败"}")
                }
        }
    }

    fun selectCloudImageModel(modelId: String) {
        if (rejectImageModelSwitchWhileGenerationIsActive()) return
        val model = _uiState.value.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.IMAGE }
        if (model == null) {
            _uiState.update { it.copy(statusMessage = "未找到图像生成模型") }
            return
        }
        cloudApiStore.saveSelectedCloudImageModelId(model.id)
        localImageModelStore.saveSelectedBackend(ImageBackend.CLOUD)
        _uiState.update {
            it.copy(
                selectedCloudImageModelId = model.id,
                selectedImageBackend = ImageBackend.CLOUD,
                cloudApiConfig = model.toImageConfig(),
                statusMessage = "图片页已切换到：${model.modelName}"
            )
        }
    }

    fun deleteCloudModel(modelId: String) {
        val current = _uiState.value
        val removed = current.cloudModels.firstOrNull { it.id == modelId }
        if (removed == null) {
            _uiState.update { it.copy(statusMessage = "未找到要删除的云端模型") }
            return
        }
        val remaining = current.cloudModels.filterNot { it.id == modelId }
        val deletedSelectedChat = removed.kind == CloudModelKind.CHAT && current.selectedCloudChatModelId == modelId
        val deletedSelectedImage = removed.kind == CloudModelKind.IMAGE && current.selectedCloudImageModelId == modelId
        val nextChat = if (deletedSelectedChat) {
            remaining.firstOrNull { it.kind == CloudModelKind.CHAT }
        } else {
            remaining.firstOrNull { it.id == current.selectedCloudChatModelId && it.kind == CloudModelKind.CHAT }
        }
        val nextImage = if (deletedSelectedImage) {
            remaining.firstOrNull { it.kind == CloudModelKind.IMAGE }
        } else {
            remaining.firstOrNull { it.id == current.selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }
        }
        val nextChatBackend = if (deletedSelectedChat && nextChat == null) ChatBackend.LOCAL else current.selectedChatBackend
        val nextImageBackend = if (deletedSelectedImage && nextImage == null) ImageBackend.LOCAL else current.selectedImageBackend
        val nextConfig = when {
            deletedSelectedChat && nextChat != null -> nextChat.toChatConfig().normalized()
            deletedSelectedImage && nextImage != null -> nextImage.toImageConfig().normalized()
            current.editingCloudModelId == modelId -> CloudApiConfig()
            else -> current.cloudApiConfig
        }

        cloudApiStore.saveModels(remaining)
        cloudApiStore.saveSelectedCloudChatModelId(nextChat?.id)
        cloudApiStore.saveSelectedCloudImageModelId(nextImage?.id)
        if (deletedSelectedChat) cloudApiStore.saveSelectedBackend(nextChatBackend)
        if (deletedSelectedImage) localImageModelStore.saveSelectedBackend(nextImageBackend)
        if (deletedSelectedChat || deletedSelectedImage || current.editingCloudModelId == modelId) {
            cloudApiStore.save(nextConfig)
        }

        _uiState.update { state ->
            state.copy(
                cloudModels = remaining,
                selectedCloudChatModelId = nextChat?.id,
                selectedCloudImageModelId = nextImage?.id,
                selectedChatBackend = nextChatBackend,
                selectedImageBackend = nextImageBackend,
                cloudApiConfig = nextConfig,
                editingCloudModelId = state.editingCloudModelId.takeUnless { it == modelId },
                statusMessage = "已删除云端模型：${removed.displayName}"
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
        _uiState.update { current ->
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
            refreshManagedRuntimeReadiness()
        }
    }

    fun importModel(uri: Uri) {
        importModel(listOf(uri))
    }

    fun importModel(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在导入本地推理引擎...")
            runCatching {
                modelStore.importFromUris(uris)
            }.onSuccess { model ->
                _uiState.update {
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                        statusMessage = "已导入${model.runtime.label}：${model.displayName}"
                    )
                }
            }.onFailure { error ->
                fail("导入失败：${error.message}")
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
                _uiState.update {
                    it.copy(remoteFiles = files, busy = false, statusMessage = "找到 ${files.size} 个本地推理文件")
                }
            }.onFailure { error ->
                fail("查询失败：${error.message}")
            }
        }
    }

    fun fetchRecommendedFiles(model: ModelScopeRecommendedModel) {
        recommendedDownloadBlockReason(model)?.let { reason ->
            _uiState.update { it.copy(statusMessage = reason) }
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
                _uiState.update {
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
        recommendedDownloadBlockReason(model)?.let { reason ->
            _uiState.update { it.copy(statusMessage = reason) }
            return
        }
        if (model.mnnModelBundle != null) {
            downloadRecommendedMnnBundle(model)
            return
        }
        if (model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT) {
            downloadRecommendedQairtChatBundle(model)
            return
        }
        if (model.visionModelBundle?.downloadProjectorByDefault == true) {
            downloadRecommendedVisionBundle(model)
            return
        }
        if (model.imageEngineBundle != null) {
            downloadRecommendedImageBundle(model)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在准备下载推荐模型：${model.title}...")
            runCatching {
                modelScopeClient.recommendedFile(model)
            }.onSuccess { remote ->
                download(remote)
            }.onFailure { error ->
                fail("推荐模型下载准备失败：${error.message}")
            }
        }
    }

    private fun recommendedDownloadBlockReason(model: ModelScopeRecommendedModel): String? {
        val deviceChipset = _uiState.value.deviceProfile
            ?.accelerationProfile
            ?.chipsetCode
            .orEmpty()
        val deviceIsSnapdragon = _uiState.value.deviceProfile?.socFamily == SocFamily.Snapdragon
        return model.downloadEligibilityFor(deviceChipset, deviceIsSnapdragon).blockedReason
    }

    fun attachVisionProjector(modelId: String, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在绑定本地视觉投影器...")
            val shouldReload = _uiState.value.loadedModelId == modelId &&
                _uiState.value.selectedChatBackend == ChatBackend.LOCAL
            runCatching {
                modelStore.attachVisionProjector(modelId, uri)
            }.onSuccess { model ->
                _uiState.update {
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
                fail("视觉文件绑定失败：${error.message}")
            }
        }
    }

    private fun downloadRecommendedQairtChatBundle(model: ModelScopeRecommendedModel) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在准备下载 QNN 聊天引擎：${model.title}...")
            runCatching {
                val remote = modelScopeClient.recommendedQairtChatFile(
                    model = model,
                    preferredChipsets = preferredQairtChipsets(currentDeviceProfile())
                )
                val bundleId = remote.name.removeSuffix(".zip").ifBlank { model.id }
                val bundleDir = modelStore.managedBundleDirFor(bundleId)
                val finalZip = modelStore.managedBundleFileFor(bundleDir, remote.name)
                val expected = remote.sizeBytes ?: 0L
                if (expected > 0L && bundleDir.usableSpace in 1 until expected) {
                    error("存储空间不足：QAIRT 引擎包还需 ${formatBytes(expected)}，请清理空间后重试。")
                }
                val tempDir = getApplication<Application>().externalCacheDir ?: getApplication<Application>().cacheDir
                val tempFile = File(tempDir, "${bundleId}-${remote.name}.part".replace(Regex("[^A-Za-z0-9._-]"), "_"))
                downloader.download(remote, tempFile, finalZip) { snapshot ->
                    _uiState.update {
                        it.copy(
                            downloadFileName = snapshot.fileName,
                            downloadedBytes = snapshot.downloadedBytes,
                            downloadTotalBytes = snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = snapshot.status,
                            statusMessage = "正在下载 QNN 聊天引擎：${snapshot.fileName}"
                        )
                    }
                }
                clearBundleDirectoryExcept(bundleDir, finalZip)
                unzipIntoDirectory(finalZip, bundleDir)
                finalZip.delete()
                val qairtBundleRoot = modelStore.resolveQairtBundleRoot(bundleDir)
                modelStore.registerDownloadedQairtBundle(
                    displayName = model.title,
                    bundleDir = qairtBundleRoot,
                    repoId = model.repoId,
                    revision = model.revision,
                    source = remote.provider.toModelSource(),
                    quant = model.quant,
                    architecture = recommendedQairtArchitecture(model)
                )
            }.onSuccess { registered ->
                val localModels = modelStore.listModels()
                val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(localModels)
                _uiState.update {
                    it.copy(
                        models = localModels,
                        qairtVerifiedLocalModelIds = qairtVerifiedIds,
                        qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                            models = localModels,
                            verifiedLocalModelIds = qairtVerifiedIds,
                            recommendations = it.recommendedRemoteModels
                        ),
                        busy = false,
                        downloadStatus = DownloadStatus.DONE,
                        downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                        downloadSpeedBytesPerSecond = 0L,
                        downloadRemainingSeconds = null,
                        statusMessage = "已下载 QNN 聊天引擎：${registered.displayName}。请在本地模型中加载后执行真机 smoke test；通过前不宣传为已跑通。"
                    )
                }
            }.onFailure { error ->
                fail("QNN 聊天引擎下载失败：${downloadFailureAdvice(error.message)}")
            }
        }
    }

    private fun downloadRecommendedMnnBundle(model: ModelScopeRecommendedModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val bundle = model.mnnModelBundle ?: return@launch
            busy("正在准备下载 MNN 高速引擎：${bundle.title}...")
            runCatching {
                val components = modelScopeClient.recommendedMnnBundleFiles(model)
                val config = components.firstOrNull {
                    it.mnnBundleRole == MnnModelBundleComponentRole.CONFIG
                } ?: error("MNN 模型包缺少 config.json。")
                val bundleDir = modelStore.managedBundleDirFor(bundle.id)
                val installer = ModelBundleInstaller(
                    BundleComponentDownloader { remote, tempFile, stagedFile, onProgress ->
                        downloader.download(remote, tempFile, stagedFile, onProgress)
                    }
                )
                val plan = installer.plan(bundleDir, components)
                val knownTotalBytes = components.sumOf { remote -> remote.sizeBytes ?: 0L }
                val usableSpace = bundleDir.usableSpace
                if (knownTotalBytes > 0L && usableSpace in 1 until knownTotalBytes) {
                    error("存储空间不足：MNN 引擎包约需 ${formatBytes(knownTotalBytes)}，请清理空间后重试。")
                }
                val downloadedBytesByPath = mutableMapOf<String, Long>()
                val installed = installer.install(
                    bundleRoot = bundleDir,
                    components = components,
                    stagedTransformer = bundle.installProfile.stagedTransformer()
                ) { snapshot ->
                    val targetIndex = plan.targets.indexOfFirst { target ->
                        target.finalFile.canonicalFile == snapshot.finalFile.canonicalFile
                    }
                    val target = plan.targets.getOrNull(targetIndex)
                    val progressPath = target?.relativePath ?: snapshot.fileName
                    downloadedBytesByPath[progressPath] = snapshot.downloadedBytes
                    _uiState.update {
                        it.copy(
                            downloadFileName = progressPath,
                            downloadedBytes = downloadedBytesByPath.values.sum(),
                            downloadTotalBytes = knownTotalBytes.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = snapshot.status,
                            statusMessage = "正在下载 MNN 组件 ${(targetIndex + 1).coerceAtLeast(1)}/${plan.targets.size}：${target?.remote?.kindLabel() ?: "组件"} · $progressPath"
                        )
                    }
                }
                require(installer.verifyInstalledBundle(installed.bundleRoot).isVerified) {
                    "MNN 模型包安装后的组件校验失败，请重新下载。"
                }
                modelStore.registerDownloadedMnnBundle(
                    displayName = model.title,
                    bundleDir = installed.bundleRoot,
                    repoId = bundle.repoId,
                    revision = bundle.revision,
                    license = config.license,
                    source = bundle.provider.toModelSource(),
                    quant = model.quant,
                    architecture = model.title.substringBefore(' ').lowercase().takeIf { it.isNotBlank() },
                    requiredFiles = bundle.requiredComponents.map { it.relativePath }
                )
            }.onSuccess { registered ->
                _uiState.update {
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                        downloadStatus = DownloadStatus.DONE,
                        downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                        downloadSpeedBytesPerSecond = 0L,
                        downloadRemainingSeconds = null,
                        statusMessage = "已下载 MNN 高速引擎包：${registered.displayName}。可在本地模型中加载；GGUF / llama.cpp 继续作为兼容引擎。"
                    )
                }
            }.onFailure { error ->
                fail("MNN 高速引擎下载失败：${downloadFailureAdvice(error.message)}")
            }
        }
    }

    private fun downloadRecommendedVisionBundle(model: ModelScopeRecommendedModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val bundle = model.visionModelBundle ?: return@launch
            busy("正在准备下载多模态模型包：${bundle.title}...")
            runCatching {
                val components = modelScopeClient.recommendedVisionBundleFiles(model)
                val primary = components.firstOrNull {
                    it.visionBundleRole == VisionModelBundleComponentRole.MAIN_MODEL
                } ?: error("多模态模型包缺少主模型。")
                val projector = components.firstOrNull {
                    it.visionBundleRole == VisionModelBundleComponentRole.PROJECTOR
                }
                val bundleDir = modelStore.managedBundleDirFor(bundle.id)
                val targets = components.map { remote ->
                    remote to modelStore.managedBundleFileFor(bundleDir, remote.path)
                }
                val bytesToDownload = targets.sumOf { (remote, _) -> remote.sizeBytes ?: 0L }
                val usableSpace = targets.firstOrNull()?.second?.parentFile?.usableSpace ?: 0L
                if (bytesToDownload > 0L && usableSpace in 1 until bytesToDownload) {
                    error("存储空间不足：多模态模型包约需 ${formatBytes(bytesToDownload)}，请清理空间后重试。")
                }
                val tempDir = getApplication<Application>().externalCacheDir ?: getApplication<Application>().cacheDir
                var completedBytes = 0L
                targets.forEachIndexed { index, (remote, finalFile) ->
                    val tempFile = File(tempDir, "${bundle.id}-${remote.name}.part".replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    val completedBefore = completedBytes
                    downloader.download(remote, tempFile, finalFile) { snapshot ->
                        _uiState.update {
                            it.copy(
                                downloadFileName = snapshot.fileName,
                                downloadedBytes = completedBefore + snapshot.downloadedBytes,
                                downloadTotalBytes = bytesToDownload.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                                downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                                downloadRemainingSeconds = snapshot.remainingSeconds,
                                downloadStatus = snapshot.status,
                                statusMessage = "正在下载多模态组件 ${index + 1}/${targets.size}：${remote.kindLabel()} · ${snapshot.fileName}"
                            )
                        }
                    }
                    completedBytes += finalFile.length()
                }
                val primaryFile = targets.firstOrNull { it.first == primary }?.second
                    ?: error("多模态模型包主模型下载目标不存在。")
                writeDownloadedVisionBundleManifest(
                    displayName = model.title,
                    bundleDir = bundleDir,
                    bundle = bundle,
                    targets = targets
                )
                if (bundle.runtime == VisionModelBundleRuntime.GGUF_MMPROJ) {
                    val projectorRemote = projector ?: error("多模态模型包缺少 mmproj / projector。")
                    val projectorFile = targets.firstOrNull { it.first == projectorRemote }?.second
                        ?: error("多模态模型包 projector 下载目标不存在。")
                    val registered = modelStore.registerDownloadedModel(
                        file = primaryFile,
                        repoId = primary.repoId,
                        revision = primary.revision,
                        license = primary.license,
                        source = primary.provider.toModelSource()
                    )
                    VisionBundleDownloadResult.ChatModel(
                        modelStore.attachVisionProjectorFile(registered.id, projectorFile, projectorRemote.name)
                    )
                } else {
                    val report = LiteRtQnnVisionRunner().health(
                        device = currentDeviceProfile(),
                        bundleRoot = bundleDir
                    )
                    VisionBundleDownloadResult.EngineBundle(
                        displayName = model.title,
                        bundleDir = bundleDir,
                        report = report
                    )
                }
            }.onSuccess { result ->
                _uiState.update {
                    val status = when (result) {
                        is VisionBundleDownloadResult.ChatModel ->
                            "已下载多模态聊天模型：${result.model.displayName}，并绑定视觉投影器。加载后可直接聊天和识图。"
                        is VisionBundleDownloadResult.EngineBundle ->
                            "已下载本地图片理解候选包：${result.displayName}。${result.report.message}"
                    }
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                        downloadStatus = DownloadStatus.DONE,
                        downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                        downloadSpeedBytesPerSecond = 0L,
                        downloadRemainingSeconds = null,
                        statusMessage = status
                    )
                }
            }.onFailure { error ->
                fail("多模态模型下载失败：${downloadFailureAdvice(error.message)}")
            }
        }
    }

    private fun downloadRecommendedImageBundle(model: ModelScopeRecommendedModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val bundle = model.imageEngineBundle ?: return@launch
            busy("正在准备下载生图引擎包：${bundle.title}...")
            runCatching {
                val components = modelScopeClient.recommendedImageBundleFiles(
                    model = model,
                    preferredQairtChipsets = preferredQairtChipsets(currentDeviceProfile())
                )
                val primary = components.firstOrNull { it.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
                    ?: error("生图引擎包缺少 diffusion 主模型。")
                val bundleDir = localImageModelStore.managedBundleDirFor(bundle.id)
                val candidateDir = File(bundleDir.parentFile, ".${bundleDir.name}.candidate")
                val installer = ModelBundleInstaller(
                    BundleComponentDownloader { remote, tempFile, finalFile, onProgress ->
                        downloader.download(remote, tempFile, finalFile, onProgress)
                    }
                )
                val plan = installer.plan(candidateDir, components)
                val knownTotalBytes = components.sumOf { it.sizeBytes ?: 0L }
                val usableSpace = bundleDir.usableSpace
                if (knownTotalBytes > 0L && usableSpace in 1 until knownTotalBytes) {
                    error("存储空间不足：引擎包约需 ${formatBytes(knownTotalBytes)}，请清理空间后重试。")
                }
                val downloadedBytesByPath = mutableMapOf<String, Long>()
                installer.install(candidateDir, components) { snapshot ->
                    val targetIndex = plan.targets.indexOfFirst { target ->
                        target.finalFile.canonicalFile == snapshot.finalFile.canonicalFile
                    }
                    val target = plan.targets.getOrNull(targetIndex)
                    val progressPath = target?.relativePath ?: snapshot.fileName
                    downloadedBytesByPath[progressPath] = snapshot.downloadedBytes
                    _uiState.update {
                        it.copy(
                            downloadFileName = progressPath,
                            downloadedBytes = downloadedBytesByPath.values.sum(),
                            downloadTotalBytes = knownTotalBytes.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = snapshot.status,
                            statusMessage = "正在下载生图组件 ${(targetIndex + 1).coerceAtLeast(1)}/${plan.targets.size}：${target?.remote?.kindLabel() ?: "组件"} · $progressPath"
                        )
                    }
                }
                var promoted = false
                var previousBundleBackup: File? = null
                try {
                    val primaryFile = plan.targets.firstOrNull { it.remote == primary }?.finalFile
                        ?: error("生图引擎包主模型下载目标不存在。")
                    if (primaryFile.extension.equals("zip", ignoreCase = true)) {
                        extractImageBundleZipIntoDirectory(primaryFile, candidateDir)
                        check(primaryFile.delete()) { "无法清理已展开的引擎 ZIP：${primaryFile.name}" }
                    }
                    val installedBundle = resolveInstalledQnnRuntimeProfile(
                        bundleDir = candidateDir,
                        bundle = bundle,
                        preferredHtpArch = currentDeviceProfile().let { device ->
                            val chipsetCode = device.accelerationProfile.chipsetCode.ifBlank { device.socModel }
                            DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(chipsetCode)
                                ?: device.accelerationProfile.qnnRuntime.htpArchVersion.takeIf { it > 0 }
                        }
                    )
                    preparePinnedQnnRuntimeMetadataIfRequired(candidateDir, installedBundle)
                    prepareMnnDiffusionTokenizerIfPossible(candidateDir)
                    val resolvedPrimary = localImageBundleManifestFromRoot(candidateDir)?.primaryFile
                        ?: findPrimaryImageModel(candidateDir)
                        ?: error("生图引擎包内没有可注册的 diffusion 主模型。")
                    val primarySha256 = resolvedPrimary.sha256ForProfile()
                    val manifestTargets = expandedImageBundleManifestTargets(
                        bundleDir = candidateDir,
                        resolvedPrimary = resolvedPrimary,
                        targets = plan.targets.map { it.remote to it.finalFile }
                    )
                    writeDownloadedImageBundleManifest(
                        displayName = model.title,
                        bundleDir = candidateDir,
                        bundle = installedBundle,
                        targets = manifestTargets,
                        primarySha256 = primarySha256
                    )
                    previousBundleBackup = promoteImageBundleCandidate(candidateDir, bundleDir)
                    promoted = true
                    val finalPrimary = File(bundleDir, resolvedPrimary.relativeTo(candidateDir).path)
                    require(finalPrimary.isFile) { "生图引擎主模型在提交后不存在：${resolvedPrimary.name}" }
                    val registered = localImageModelStore.registerDownloadedBundle(
                        displayName = model.title,
                        bundleDir = bundleDir,
                        primaryFile = finalPrimary,
                        primaryRemote = primary,
                        componentCount = components.size,
                        runtimeOverride = bundle.runtime.toLocalImageRuntime(),
                        imageSizeOverride = "${bundle.smokeSpec.width}x${bundle.smokeSpec.height}",
                        primarySha256 = primarySha256
                    )
                    previousBundleBackup?.deleteRecursively()
                    registered
                } catch (error: Throwable) {
                    if (promoted) {
                        restoreImageBundleBackup(bundleDir, previousBundleBackup)
                    }
                    candidateDir.deleteRecursively()
                    throw error
                }
            }.onSuccess { record ->
                val selection = settleLocalImageSelection(record)
                val readiness = record.localImageReadinessForUi(
                    selection.qnnVerificationCurrentByModelId[record.id]
                )
                val diagnostic = record.localImageVerificationDiagnosticMessage()
                _uiState.update {
                    it.copy(
                        localImageModels = selection.models,
                        qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId,
                        selectedLocalImageModelId = selection.selectedId,
                        selectedImageBackend = selection.selectedBackend,
                        busy = false,
                        downloadStatus = DownloadStatus.DONE,
                        downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                        downloadSpeedBytesPerSecond = 0L,
                        downloadRemainingSeconds = null,
                        statusMessage = if (readiness != null) {
                            "已下载完整本地生图引擎包：${record.displayName}，但暂不能生成：$readiness"
                        } else if (diagnostic != null) {
                            "已下载完整本地生图引擎包：${record.displayName}；$diagnostic"
                        } else {
                            "已下载完整本地生图引擎包：${record.displayName}"
                        }
                    )
                }
            }.onFailure { error ->
                fail("生图引擎包下载失败：${downloadFailureAdvice(error.message)}")
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
                _uiState.update {
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
        _uiState.update { it.copy(repoInput = model.id) }
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在读取 ${model.displayName} 的本地推理文件...")
            runCatching {
                modelScopeClient.listEngineFiles(model.id)
            }.onSuccess { files ->
                _uiState.update {
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
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(
                    busy = true,
                    downloadFileName = remote.name,
                    downloadedBytes = 0L,
                    downloadTotalBytes = remote.sizeBytes ?: 0L,
                    downloadSpeedBytesPerSecond = 0L,
                    downloadRemainingSeconds = null,
                    downloadStatus = DownloadStatus.RUNNING,
                    statusMessage = "正在下载 ${remote.name}..."
                )
            }
            runCatching {
                val remoteKind = remote.fileKind()
                if (remoteKind == RemoteModelFileKind.MNN_COMPONENT) {
                    error("MNN 组件需要作为完整高速引擎包下载或多选导入。请回到推荐卡片点击“下载 MNN”，或一次选择完整组件。")
                }
                val imageModel = remote.isImageModelCandidate()
                val targetVisionModel = if (remoteKind == RemoteModelFileKind.PROJECTOR) {
                    val currentState = _uiState.value
                    currentState.models.firstOrNull { it.id == currentState.loadedModelId }
                        ?: error("请先加载要启用识图的本地多模态主模型，再下载 mmproj / projector。")
                } else {
                    null
                }
                val finalFile = if (imageModel) localImageModelStore.managedFileFor(remote.name) else modelStore.managedFileFor(remote.name)
                val tempDir = getApplication<Application>().externalCacheDir ?: getApplication<Application>().cacheDir
                val tempFile = File(tempDir, "${remote.name}.part")
                val expectedBytes = remote.sizeBytes ?: 0L
                val usableSpace = finalFile.parentFile?.usableSpace ?: 0L
                if (expectedBytes > 0L && usableSpace in 1 until expectedBytes) {
                    error("存储空间不足：模型约需 ${formatBytes(expectedBytes)}，请清理空间后重试。")
                }
                downloader.download(remote, tempFile, finalFile) { snapshot ->
                    _uiState.update {
                        it.copy(
                            downloadFileName = snapshot.fileName,
                            downloadedBytes = snapshot.downloadedBytes,
                            downloadTotalBytes = snapshot.expectedLength,
                            downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                            downloadRemainingSeconds = snapshot.remainingSeconds,
                            downloadStatus = snapshot.status,
                            statusMessage = snapshot.progressText()
                        )
                    }
                }
                if (imageModel) {
                    DownloadedModelRegistration.Image(localImageModelStore.registerDownloadedModel(finalFile, remote))
                } else if (remoteKind == RemoteModelFileKind.PROJECTOR && targetVisionModel != null) {
                    DownloadedModelRegistration.VisionProjector(
                        model = modelStore.attachVisionProjectorFile(targetVisionModel.id, finalFile, remote.name),
                        shouldReload = _uiState.value.loadedModelId == targetVisionModel.id &&
                            _uiState.value.selectedChatBackend == ChatBackend.LOCAL
                    )
                } else {
                    DownloadedModelRegistration.Chat(
                        modelStore.registerDownloadedModel(
                            file = finalFile,
                            repoId = remote.repoId,
                            revision = remote.revision,
                            license = remote.license,
                            source = remote.provider.toModelSource()
                        )
                    )
                }
            }.onSuccess { registration ->
                val registeredImageSelection = (registration as? DownloadedModelRegistration.Image)
                    ?.let { image -> settleLocalImageSelection(image.model) }
                _uiState.update {
                    when (registration) {
                        is DownloadedModelRegistration.Chat -> it.copy(
                            models = modelStore.listModels(),
                            busy = false,
                            downloadStatus = DownloadStatus.DONE,
                            downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                            downloadSpeedBytesPerSecond = 0L,
                            downloadRemainingSeconds = null,
                            statusMessage = "已下载推理模型：${registration.model.displayName}"
                        )
                        is DownloadedModelRegistration.VisionProjector -> it.copy(
                            models = modelStore.listModels(),
                            busy = false,
                            downloadStatus = DownloadStatus.DONE,
                            downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                            downloadSpeedBytesPerSecond = 0L,
                            downloadRemainingSeconds = null,
                            statusMessage = if (registration.shouldReload) {
                                "已下载并绑定视觉投影器：${registration.model.visionProjectorFileName ?: "mmproj"}，正在重新加载模型以启用本地识图。"
                            } else {
                                "已下载并绑定视觉投影器：${registration.model.visionProjectorFileName ?: "mmproj"}，加载该模型后可本地识图。"
                            }
                        )
                        is DownloadedModelRegistration.Image -> {
                            val selection = requireNotNull(registeredImageSelection)
                            val readiness = registration.model.localImageReadinessForUi(
                                selection.qnnVerificationCurrentByModelId[registration.model.id]
                            )
                            val diagnostic = registration.model.localImageVerificationDiagnosticMessage()
                            it.copy(
                                localImageModels = selection.models,
                                qnnImageVerificationCurrentByModelId = selection.qnnVerificationCurrentByModelId,
                                selectedLocalImageModelId = selection.selectedId,
                                selectedImageBackend = selection.selectedBackend,
                                busy = false,
                                downloadStatus = DownloadStatus.DONE,
                                downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                                downloadSpeedBytesPerSecond = 0L,
                                downloadRemainingSeconds = null,
                                statusMessage = if (readiness != null) {
                                    "已下载图像主模型：${registration.model.displayName}。$readiness"
                                } else if (diagnostic != null) {
                                    "已下载图像生成模型：${registration.model.displayName}；$diagnostic"
                                } else {
                                    "已下载图像生成模型：${registration.model.displayName}"
                                }
                            )
                        }
                    }
                }
                if (registration is DownloadedModelRegistration.VisionProjector && registration.shouldReload) {
                    loadModel(registration.model)
                }
            }.onFailure { error ->
                fail("下载失败：${downloadFailureAdvice(error.message)}")
            }
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
            _uiState.update {
                it.copy(statusMessage = "云端推理模型未配置。请到模型管理 > 云端 接入并保存对话推理模型。")
            }
            return
        }
        cloudApiStore.save(config)
        cloudApiStore.saveSelectedBackend(ChatBackend.CLOUD)
        cloudApiStore.saveSelectedCloudChatModelId(model.id)
        viewModelScope.launch(Dispatchers.IO) {
            engine.stopGeneration()
        }
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        _uiState.update {
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
                chatSessions = sessionsToPersist,
                busy = false,
                statusMessage = "已切换到云端模型：${config.safeDisplayName()}",
                tab = AppTab.CHAT
            )
        }
        persistChatSessions(sessionsToPersist)
    }

    fun loadModel(model: ModelManifest) {
        viewModelScope.launch(Dispatchers.IO) {
            val runtimeBeforeLoad = captureLoadedRuntimeSnapshot()
            fun failBeforeNativeReplacement(message: String) {
                val nativeStats = currentNativeStatsJson()
                val recentLogs = currentEngineLogs()
                _uiState.update {
                    it.copy(
                        busy = false,
                        engineLifecycle = if (engine.stats.value.loaded) {
                            AgentEngineLifecycle.READY
                        } else {
                            AgentEngineLifecycle.ERROR
                        },
                        nativeStatsJson = nativeStats,
                        logs = recentLogs,
                        statusMessage = message
                    )
                }
            }
            generationJob?.cancel()
            engine.stopGeneration()
            _uiState.update {
                it.copy(
                    busy = true,
                    engineLifecycle = AgentEngineLifecycle.LOADING,
                    statusMessage = "正在加载 ${model.displayName}..."
                )
            }
            val preflight = modelStore.validateForLoad(model.id)
            if (!preflight.canLoad) {
                failBeforeNativeReplacement("加载前检查失败：${preflight.message}")
                return@launch
            }
            val params = _uiState.value.params
            var persistedModels = modelStore.listModels()
            var qairtVerifiedIds = if (model.runtime == ChatModelRuntime.GENIEX_QAIRT) {
                currentQairtVerifiedLocalModelIds(persistedModels)
            } else {
                emptySet()
            }
            val device = currentDeviceProfile()
            val memoryAdmission = LocalModelMemoryAdmissionPolicy.evaluate(model, device)
            memoryAdmission.blocker?.let { message ->
                failBeforeNativeReplacement("加载前内存检查失败：$message")
                return@launch
            }
            var nativeReplacementOccurred = false

            suspend fun recoverAfterNativeReplacement(
                message: String,
                emptyLifecycle: AgentEngineLifecycle = AgentEngineLifecycle.ERROR
            ) = withContext(NonCancellable) {
                if (!nativeReplacementOccurred) {
                    failBeforeNativeReplacement(message)
                    return@withContext
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
            }

            try {
            if (shouldRunAutomaticQairtCanary(model.runtime, model.id, qairtVerifiedIds)) {
                if (engine.stats.value.loaded) {
                    val releasedIdentity = activeRuntimeIdentity
                    try {
                        // Never map a second large model in :qairt_smoke while
                        // the production process still owns the current model.
                        engine.stopGeneration()
                        engine.unloadModel()
                    } catch (error: Throwable) {
                        if (nativeRuntimeReleaseObservedNow()) {
                            nativeReplacementOccurred = true
                            clearNativeRuntimeSessionState(
                                lifecycle = AgentEngineLifecycle.LOADING,
                                statusMessage = "当前模型已释放，但 QAIRT 自动安全启动准备失败。",
                                busy = true
                            )
                        }
                        throw IllegalStateException(
                            "QAIRT 自动安全启动前无法释放当前模型：${error.message ?: "未知错误"}",
                            error
                        )
                    }
                    nativeReplacementOccurred = true
                    clearNativeRuntimeSessionState(
                        lifecycle = AgentEngineLifecycle.LOADING,
                        statusMessage = "已释放当前模型内存，正在隔离进程自动安全启动 ${model.displayName}…",
                        busy = true
                    )
                    clearPendingRuntimeTransactionForLifecycle(
                        reason = "QAIRT_CANARY_MODEL_SWITCH",
                        identity = releasedIdentity
                    )
                }
                _uiState.update {
                    it.copy(statusMessage = "首次加载：正在隔离进程自动安全启动 ${model.displayName}…")
                }
                try {
                    QairtDryRunWorkerClient(getApplication<Application>()).certify(
                        modelId = model.id,
                        nCtx = params.nCtx,
                        nThreads = params.nThreads
                    ) { progress ->
                        _uiState.update { state ->
                            if (state.busy) {
                                state.copy(statusMessage = "自动安全启动：${progress.message}")
                            } else {
                                state
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    throw IllegalStateException(
                        "QAIRT 自动安全启动失败：" +
                            error.message.orEmpty().ifBlank { "隔离进程未完成真实执行。" },
                        error
                    )
                }
                persistedModels = modelStore.listModels()
                qairtVerifiedIds = currentQairtVerifiedLocalModelIds(persistedModels)
                if (model.id !in qairtVerifiedIds) {
                    error(
                        "QAIRT 自动安全启动未生成真实 create/generate/destroy 证据，已停止正式加载。"
                    )
                }
                _uiState.update {
                    it.copy(
                        models = persistedModels,
                        qairtVerifiedLocalModelIds = qairtVerifiedIds,
                        qairtVerifiedRecommendationIds = verifiedQairtRecommendationIds(
                            models = persistedModels,
                            verifiedLocalModelIds = qairtVerifiedIds,
                            recommendations = it.recommendedRemoteModels
                        ),
                        statusMessage = "自动安全启动通过，正在正式加载 ${model.displayName}…"
                    )
                }
            }
            if (memoryAdmission.mode == LocalModelMemoryAdmissionMode.SPARSE_MOE_MMAP) {
                _uiState.update {
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
            var bootstrapProfile = persistedProfile ?: adaptive.executionProfile.engineProfile
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
            if (persistedProfile == null && runtimeProfileStore.profile(bootstrapProfile.profileId)
                    ?.recordState == PersistedProfileRecordState.REJECTED.name
            ) {
                bootstrapProfile = bootstrapProfile.copy(
                    profileId = "${bootstrapProfile.profileId}-retry-${UUID.randomUUID().toString().take(8)}",
                    revision = nextExecutionProfileRevision(bootstrapProfile.revision, persistedRevisions)
                )
            }
            val loadParams = model.toLoadParams(params, bootstrapProfile)
            val nativeLoad = engine.loadModel(
                modelPath = model.path,
                runtime = runtime,
                params = loadParams,
                qairtBundleSha256 = qairtBundleSha256,
                runtimeIdentity = identity,
                executionProfile = bootstrapProfile
            )
            if (nativeLoad.isFailure && runtimeBeforeLoad != null &&
                nativeRuntimeReleaseObservedNow()
            ) {
                nativeReplacementOccurred = true
            }
            nativeLoad.getOrThrow()
            nativeReplacementOccurred = true
            // A successful native load has replaced any prior session. Clear
            // the old public projection before correctness and persistence;
            // the new model is not user-visible until the transaction commits.
            clearNativeRuntimeSessionState(
                lifecycle = AgentEngineLifecycle.LOADING,
                statusMessage = "正在完成安全基线正确性校准…",
                busy = true
            )
            activeRuntimeIdentity = identity
            activeModelForRuntimeProfile = model
            val effectiveParams = mergeExecutionProfile(params, engine.activeExecutionProfile() ?: bootstrapProfile)
            _uiState.update { state ->
                state.copy(
                    params = effectiveParams,
                    reloadRequired = false,
                    engineLifecycle = AgentEngineLifecycle.LOADING,
                    statusMessage = "正在完成安全基线正确性校准…"
                )
            }
            val needsBootstrapCommit = persistedState?.activeExecutionProfile == null ||
                persistedState.activeProfile?.recordState != PersistedProfileRecordState.COMMITTED.name ||
                persistedState.activeExecutionProfile.profileId != bootstrapProfile.profileId
            val canary = try {
                runBootstrapCorrectnessCanary(effectiveParams)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw IllegalStateException(
                    "安全基线正确性校准失败：${error.message ?: error::class.java.simpleName}",
                    error
                )
            }
            if (!canary.passed) {
                error("安全基线正确性校准未通过：${canary.detail}")
            }
                if (needsBootstrapCommit) {
                    persistBootstrapProfile(
                        profile = engine.activeExecutionProfile() ?: bootstrapProfile,
                        sourceSummary = JSONObject()
                            .put("kind", "bootstrap")
                            .put("canary", canary.detail)
                            .toString()
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
                val nativeStatsAfterLoad = currentNativeStatsJson()
                val logsAfterLoad = currentEngineLogs()
                _uiState.update { state ->
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
                            if (JSONObject(nativeStatsAfterLoad).optBoolean("visionReady", false)) {
                                append("，本地视觉组件已就绪")
                            }
                            append("。安全基线和正确性校准通过，可直接聊天；性能调优可在 Agent 页单独启动。")
                        },
                        tab = AppTab.CHAT
                    )
                }
            persistChatSessions(sessionsToPersist)
            } catch (error: CancellationException) {
                if (runtimeBeforeLoad != null &&
                    nativeRuntimeReleaseObservedNow()
                ) {
                    nativeReplacementOccurred = true
                }
                recoverAfterNativeReplacement(
                    message = "模型加载已取消。",
                    emptyLifecycle = AgentEngineLifecycle.UNLOADED
                )
                throw error
            } catch (error: Throwable) {
                if (runtimeBeforeLoad != null &&
                    nativeRuntimeReleaseObservedNow()
                ) {
                    nativeReplacementOccurred = true
                }
                val nativeStats = currentNativeStatsJson()
                val failure = LocalModelLoadFailureClassifier.classify(error.message, nativeStats)
                recoverAfterNativeReplacement("加载失败：${failure.userMessage}")
            }
        }
    }

    fun verifyModel(model: ModelManifest) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在校验 ${model.displayName}...")
            val result = modelStore.validateForLoad(model.id)
            val qairtDryRun = if (
                result.canLoad &&
                model.runtime == ChatModelRuntime.GENIEX_QAIRT &&
                model.id !in currentQairtVerifiedLocalModelIds(modelStore.listModels())
            ) {
                _uiState.update {
                    it.copy(statusMessage = "模型包校验通过，正在独立进程执行 QAIRT create/generate/destroy 诊断…")
                }
                runCatching {
                    val params = _uiState.value.params
                    QairtDryRunWorkerClient(getApplication<Application>()).certify(
                        modelId = model.id,
                        nCtx = params.nCtx,
                        nThreads = params.nThreads
                    ) { progress ->
                        _uiState.update { state ->
                            if (state.busy) state.copy(statusMessage = progress.message) else state
                        }
                    }
                }
            } else {
                null
            }
            val localModels = modelStore.listModels()
            val qairtVerifiedIds = currentQairtVerifiedLocalModelIds(localModels)
            val qairtVerified = model.id in qairtVerifiedIds
            _uiState.update {
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
                            "QAIRT 运行诊断失败：${qairtDryRun.exceptionOrNull()?.message.orEmpty()}。仍可点击加载重新自动安全启动。"
                        qairtDryRun?.isSuccess == true && !qairtVerified ->
                            "QAIRT 运行诊断完成但未写入证据记录；这不会阻止模型加载。"
                        model.runtime == ChatModelRuntime.GENIEX_QAIRT && !qairtVerified ->
                            "模型包完整性校验通过；首次加载会自动隔离安全启动。"
                        qairtDryRun?.isSuccess == true ->
                            "QAIRT 运行诊断通过：已确认骁龙 NPU、固定回答与干净卸载。"
                        model.runtime == ChatModelRuntime.GENIEX_QAIRT ->
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

    private fun currentNativeStatsJson(): String =
        runCatching { engine.nativeStatsJson() }.getOrDefault("{}")

    private fun currentEngineLogs(): List<com.muyuchat.core.telemetry.RuntimeMetrics> =
        runCatching { engine.recentLogs() }.getOrDefault(emptyList())

    private fun nativeRuntimeReleaseObservedNow(): Boolean = nativeRuntimeReleaseObserved(
        engineLoaded = engine.stats.value.loaded,
        nativeStatsJson = currentNativeStatsJson()
    )

    private fun clearNativeRuntimeSessionState(
        lifecycle: AgentEngineLifecycle,
        statusMessage: String,
        busy: Boolean = false
    ) {
        directParameterStageGeneration.incrementAndGet()
        directParameterStageJob?.cancel()
        activeRuntimeIdentity = null
        activeModelForRuntimeProfile = null
        activeAdaptiveRecommendation = null
        pendingAdaptiveRecommendation = null
        pendingProfileTransactionId = null
        activeProfileTransactionId = null
        val nativeStats = currentNativeStatsJson()
        val recentLogs = currentEngineLogs()
        _uiState.update { state ->
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
        activeModelForRuntimeProfile = snapshot.model
        activeAdaptiveRecommendation = snapshot.adaptiveRecommendation
        pendingAdaptiveRecommendation = null
        pendingProfileTransactionId = null
        activeProfileTransactionId = null
        val nativeStats = currentNativeStatsJson()
        val recentLogs = currentEngineLogs()
        _uiState.update { state ->
            state.copy(
                models = modelStore.listModels(),
                loadedModelId = snapshot.model.id,
                loadedModelName = snapshot.model.displayName,
                selectedChatBackend = snapshot.uiState.selectedChatBackend,
                busy = false,
                isGenerating = false,
                params = mergeExecutionProfile(snapshot.uiState.params, activeProfile),
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
            generationJob?.cancel()
            val releasedIdentity = activeRuntimeIdentity
            _uiState.update {
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
                !nativeRuntimeReleaseObservedNow()
            ) {
                val error = nativeRelease.exceptionOrNull()
                _uiState.update {
                    it.copy(
                        busy = false,
                        engineLifecycle = AgentEngineLifecycle.READY,
                        statusMessage = "卸载失败：${error?.message ?: "未知错误"}"
                    )
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
                _uiState.update {
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
            _uiState.update { it.copy(busy = true, statusMessage = "正在删除 ${model.displayName}…") }
            runCatching {
                if (wasLoaded) {
                    generationJob?.cancel()
                    engine.stopGeneration()
                    val release = runCatching { engine.unloadModel() }
                    if (release.isFailure &&
                        !nativeRuntimeReleaseObservedNow()
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
                _uiState.update {
                    it.copy(
                        models = modelStore.listModels(),
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
                _uiState.update {
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                        statusMessage = if (nativeReleased) {
                            buildString {
                                append("删除失败，但模型已安全卸载：")
                                append(error.message ?: "请检查文件权限或存储状态")
                                lifecycleWarning?.let { append("；").append(it) }
                            }
                        } else {
                            "删除失败：${error.message ?: "请检查文件权限或存储状态"}"
                        }
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
        val preparedInput = state.prepareChatInput()
        if ((preparedInput.text.isBlank() && preparedInput.imageAttachments.isEmpty()) || state.isGenerating) return
        val user = ChatMessage(
            role = Role.USER,
            content = preparedInput.text.ifBlank {
                if (preparedInput.imageAttachments.isNotEmpty()) "请描述这张图片。" else ""
            },
            imageAttachments = preparedInput.imageAttachments
        )
        val assistant = ChatMessage(Role.ASSISTANT, "")
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        _uiState.update {
            val sessionId = it.activeChatSessionId ?: UUID.randomUUID().toString()
            val messages = it.messages + user + assistant
            sessionsToPersist = it.chatSessions.upsertSession(
                sessionId = sessionId,
                messages = messages,
                assistantId = it.selectedAssistantId,
                modelMode = it.selectedChatBackend.bindingValue(),
                modelId = it.currentChatModelId()
            )
            it.copy(
                input = "",
                messages = messages,
                activeChatSessionId = sessionId,
                chatSessions = sessionsToPersist,
                isGenerating = true,
                statusMessage = null
            )
        }
        persistChatSessions(sessionsToPersist)

        startGeneration(_uiState.value.messages.dropLast(1))
    }

    fun regenerateLastResponse() {
        val state = _uiState.value
        if (state.isGenerating) return
        val lastAssistant = state.messages.indexOfLast { it.role == Role.ASSISTANT }
        if (lastAssistant < 0) return
        val priorUser = state.messages.take(lastAssistant).indexOfLast { it.role == Role.USER }
        if (priorUser < 0) return
        val kept = state.messages.take(lastAssistant) + ChatMessage(Role.ASSISTANT, "")
        var sessionsToPersist: List<ChatSessionRecord> = emptyList()
        _uiState.update {
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
                statusMessage = "正在重新生成上一条回答..."
            )
        }
        persistChatSessions(sessionsToPersist)
        startGeneration(kept.dropLast(1))
    }

    fun deleteMessageAt(index: Int) {
        val state = _uiState.value
        if (state.isGenerating) {
            _uiState.update { it.copy(statusMessage = "请先停止当前生成，再删除消息") }
            return
        }
        if (index !in state.messages.indices) return
        val updatedMessages = state.messages.filterIndexed { messageIndex, _ -> messageIndex != index }
        val sessionId = state.activeChatSessionId ?: UUID.randomUUID().toString()
        var updatedSessions: List<ChatSessionRecord> = state.chatSessions
        _uiState.update {
            updatedSessions = if (updatedMessages.isEmpty()) {
                it.chatSessions.filterNot { session -> session.id == sessionId }
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
                activeChatSessionId = if (updatedMessages.isEmpty()) null else sessionId,
                chatSessions = updatedSessions,
                statusMessage = "已删除消息"
            )
        }
        persistChatSessions(updatedSessions)
    }

    private fun startGeneration(requestMessages: List<ChatMessage>) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            val initialState = _uiState.value
            val baseParams = initialState.params.let { current ->
                val backendParams = if (initialState.selectedChatBackend == ChatBackend.CLOUD) {
                    current.copy(
                        reasoningMode = ReasoningMode.STANDARD,
                        hideReasoning = false
                    )
                } else {
                    current
                }
                backendParams.copy(nPredict = backendParams.effectiveNPredict())
            }
            if (initialState.selectedChatBackend == ChatBackend.LOCAL && baseParams != initialState.params) {
                persistGenerationParams(baseParams)
                _uiState.update { it.copy(params = baseParams) }
            }
            var requestParams = baseParams
            val webSearchTurnMode = if (initialState.webSearchOneShotEnabled) {
                WebSearchTurnMode.ON
            } else {
                initialState.webSearchTurnMode
            }
            val webSearchConfigForTurn = initialState.webSearchResearchModeOverride?.let { researchMode ->
                initialState.webSearchConfig.copy(researchMode = researchMode)
            } ?: initialState.webSearchConfig
            if (
                initialState.webSearchOneShotEnabled ||
                initialState.webSearchTurnMode != WebSearchTurnMode.FOLLOW ||
                initialState.webSearchResearchModeOverride != null
            ) {
                _uiState.update {
                    it.copy(
                        webSearchTurnMode = WebSearchTurnMode.FOLLOW,
                        webSearchResearchModeOverride = null,
                        webSearchOneShotEnabled = false
                    )
                }
            }
            val webSearchTurn = executeWebSearchForChatTurn(
                messages = requestMessages,
                config = webSearchConfigForTurn,
                oneShotEnabled = webSearchTurnMode == WebSearchTurnMode.ON,
                assistantWebSearchEnabled = initialState.selectedAssistant()?.webSearchEnabled == true,
                turnMode = webSearchTurnMode,
                search = { plan, config -> webSearchProvider.search(plan, config) },
                beforeSearch = { plan, triggerReasons ->
                    attachWebSearchEvidenceToPendingAssistant(
                        sources = emptyList(),
                        trace = plan.toPendingChatWebSearchTrace(
                            config = webSearchConfigForTurn,
                            triggerReasons = triggerReasons
                        )
                    )
                    _uiState.update {
                        it.copy(
                            webSearchStatusMessage = "正在联网检索：${plan.displayQuery.take(42)}",
                            statusMessage = "正在联网检索..."
                        )
                    }
                }
            )
            if (webSearchTurn.promptContext.isNotBlank()) {
                requestParams = requestParams.copy(
                    systemPrompt = listOf(requestParams.systemPrompt, webSearchTurn.promptContext)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n")
                )
            }
            attachWebSearchEvidenceToPendingAssistant(
                sources = webSearchTurn.sourceReferences,
                trace = webSearchTurn.diagnostic?.toChatWebSearchTrace()
            )
            if (webSearchTurn.requested) {
                val diagnostics = webSearchTurn.diagnostic?.let(::appendWebSearchDiagnostic)
                _uiState.update {
                    it.copy(
                        webSearchStatusMessage = webSearchTurn.webSearchStatusMessage ?: it.webSearchStatusMessage,
                        webSearchDiagnostics = diagnostics ?: it.webSearchDiagnostics,
                        statusMessage = webSearchTurn.statusMessage ?: it.statusMessage
                    )
                }
            }
            val state = _uiState.value
            val hasImageAttachments = requestMessages.any { it.imageAttachments.isNotEmpty() }
            var localUiRequestId: String? = null
            val stream = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                val cloudConfig = state.selectedChatCloudConfig()?.normalized()
                if (cloudConfig == null) {
                    appendAssistant("\n请先在模型管理 > 云端 加载一个对话推理模型。")
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "未加载云端推理模型") }
                    persistChatSessions()
                    return@launch
                }
                if (hasImageAttachments && !cloudConfig.supportsVision) {
                    appendAssistant("\n当前云端推理引擎未开启图片输入。请在模型管理中编辑该云端推理引擎，打开“支持图片输入”后再发送图片。")
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "云端识图未启用") }
                    persistChatSessions()
                    return@launch
                }
                val cloudMessages = runCatching {
                    requestMessages.withInlineImageDataForCloud()
                }.getOrElse { error ->
                    appendAssistant("\n图片读取失败：无法读取或压缩这张图片。请换一张本地图片，或检查文件权限。${error.message?.let { "\n原因：$it" } ?: ""}")
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "图片读取失败") }
                    persistChatSessions()
                    return@launch
                }
                cloudChatProvider.streamChat(cloudConfig, ChatRequest(cloudMessages, requestParams))
            } else {
                if (hasImageAttachments && !localVisionRunnerAvailable()) {
                    appendAssistant("\n${state.localVisionUnavailableMessage()}")
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "本地视觉 runner 未启用") }
                    persistChatSessions()
                    return@launch
                }
                val localMessages = if (hasImageAttachments) {
                    runCatching { requestMessages.withLocalImageFilesForVision() }
                        .getOrElse { error ->
                            appendAssistant("\n本地图片预处理失败：图片压缩或缓存失败，请换一张本地图片后重试。${error.message?.let { "\n原因：$it" } ?: ""}")
                            _uiState.update { it.copy(isGenerating = false, statusMessage = "本地图片预处理失败") }
                            persistChatSessions()
                            return@launch
                        }
                } else {
                    requestMessages
                }
                val uiRequestId = "ui-${UUID.randomUUID().toString().replace("-", "")}"
                localUiRequestId = uiRequestId
                val executionContext = LocalChatExecutionContext(requestId = uiRequestId)
                // The editor may contain a not-yet-applied load-bound value.
                // Ordinary chat must continue with the committed profile until
                // the explicit reload transaction succeeds.
                val activeRequestParams = engine.activeExecutionProfile()
                    ?.let { profile -> mergeExecutionProfile(requestParams, profile) }
                    ?: requestParams
                LocalApiRuntime.streamChat(
                    ChatRequest(localMessages, activeRequestParams),
                    executionContext
                ) ?: engine.streamChat(ChatRequest(localMessages, activeRequestParams), executionContext)
            }
            stream.collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> {
                        appendAssistant(
                            delta = event.text,
                            reasoningDelta = event.reasoning,
                            reasoningDurationMs = event.reasoningDurationMs
                        )
                        _uiState.update { it.copy(stats = event.stats) }
                    }
                    is GenerateEvent.Done -> {
                        val requestId = localUiRequestId
                        if (requestId != null) LocalApiRuntime.generationSequence()?.let { sequence ->
                            // Pair the UI-owned request id with the native
                            // sequence for the redacted acceptance trace.
                            // The context id is also the one registered by
                            // LocalApiRuntime.streamChat above.
                            LocalApiRuntime.recordGenerationSequence(requestId, sequence)
                        }
                        val citationAudit = applyWebSearchAnswerGuardsToLastAssistant()
                        if (citationAudit != null) {
                            recordWebSearchCitationAudit(webSearchTurn.diagnostic?.id, citationAudit)
                        }
                        ensureVisibleAssistantReplyAfterCompletion()
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                stats = event.stats,
                                engineLifecycle = event.stats.lifecycleAfterGeneration(),
                                logs = if (state.selectedChatBackend == ChatBackend.LOCAL) engine.recentLogs() else it.logs
                            )
                        }
                        persistChatSessions()
                    }
                    is GenerateEvent.Error -> {
                        val requestId = localUiRequestId
                        if (requestId != null) LocalApiRuntime.generationSequence()?.let { sequence ->
                            LocalApiRuntime.recordGenerationSequence(requestId, sequence)
                        }
                        if (event.isConfigurationActionError()) {
                            removePendingAssistantPlaceholder()
                        } else {
                            appendAssistant("\n${event.message}")
                        }
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                stats = event.stats,
                                engineLifecycle = event.stats.lifecycleAfterGeneration(),
                                statusMessage = event.message
                            )
                        }
                        persistChatSessions()
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        viewModelScope.launch {
            engine.stopGeneration()
            generationJob?.cancel()
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    engineLifecycle = engine.stats.value.lifecycleAfterGeneration(),
                    statusMessage = "已停止生成"
                )
            }
            persistChatSessions()
        }
    }

    fun newChat() {
        val state = _uiState.value
        if (state.isGenerating) {
            _uiState.update { it.copy(statusMessage = "请先停止当前生成，再新建对话") }
            return
        }
        _uiState.update {
            it.copy(
                messages = emptyList(),
                input = "",
                activeChatSessionId = null,
                statusMessage = "已新建对话"
            )
        }
    }

    fun selectChatSession(sessionId: String) {
        val state = _uiState.value
        if (state.isGenerating) {
            _uiState.update { it.copy(statusMessage = "请先停止当前生成，再切换对话") }
            return
        }
        val session = state.chatSessions.firstOrNull { it.id == sessionId } ?: return
        val assistant = session.assistantId
            ?.let { id -> state.assistants.firstOrNull { it.id == id } }
            ?: state.assistants.firstOrNull { it.id == state.selectedAssistantId }
        val updatedParams = assistant?.toGenerationParams(state.params) ?: state.params
        if (assistant != null && assistant.id != state.selectedAssistantId) {
            assistantStore.saveSelectedAssistantId(assistant.id)
            persistGenerationParams(updatedParams)
        }
        val sessionBackend = session.modelMode.toChatBackendOrNull()
        val sessionCloudModel = if (sessionBackend == ChatBackend.CLOUD) {
            state.cloudModels.firstOrNull { it.id == session.modelId && it.kind == CloudModelKind.CHAT && it.configured }
        } else {
            null
        }
        val restoreCloud = sessionCloudModel != null
        val restoreLoadedLocal = sessionBackend == ChatBackend.LOCAL &&
            (session.modelId.isNullOrBlank() || session.modelId == state.loadedModelId)
        if (sessionCloudModel != null) {
            cloudApiStore.saveSelectedBackend(ChatBackend.CLOUD)
            cloudApiStore.saveSelectedCloudChatModelId(sessionCloudModel.id)
            cloudApiStore.save(sessionCloudModel.toChatConfig().normalized())
        } else if (restoreLoadedLocal) {
            cloudApiStore.saveSelectedBackend(ChatBackend.LOCAL)
        }
        val status = when {
            sessionBackend == ChatBackend.LOCAL &&
                !session.modelId.isNullOrBlank() &&
                session.modelId != state.loadedModelId ->
                "已打开对话；该会话原本使用的本地模型尚未加载，可在顶部模型胶囊中重新加载。"
            sessionBackend == ChatBackend.CLOUD && session.modelId != null && !restoreCloud ->
                "已打开对话；该会话原本使用的云端模型已不可用，请重新选择云端推理引擎。"
            else -> null
        }
        _uiState.update {
            it.copy(
                messages = session.messages,
                input = "",
                activeChatSessionId = session.id,
                selectedAssistantId = assistant?.id ?: it.selectedAssistantId,
                params = updatedParams,
                selectedChatBackend = when {
                    restoreCloud -> ChatBackend.CLOUD
                    restoreLoadedLocal -> ChatBackend.LOCAL
                    else -> it.selectedChatBackend
                },
                selectedCloudChatModelId = sessionCloudModel?.id ?: it.selectedCloudChatModelId,
                cloudApiConfig = sessionCloudModel?.toChatConfig()?.normalized() ?: it.cloudApiConfig,
                statusMessage = status
            )
        }
    }

    fun deleteChatSession(sessionId: String) {
        val state = _uiState.value
        if (state.isGenerating) {
            _uiState.update { it.copy(statusMessage = "请先停止当前生成，再删除记录") }
            return
        }
        val remaining = state.chatSessions.filterNot { it.id == sessionId }
        val isActive = state.activeChatSessionId == sessionId
        _uiState.update {
            it.copy(
                chatSessions = remaining,
                messages = if (isActive) emptyList() else it.messages,
                input = if (isActive) "" else it.input,
                activeChatSessionId = if (isActive) null else it.activeChatSessionId,
                statusMessage = "已删除对话记录"
            )
        }
        persistChatSessions(remaining)
    }

    fun renameChatSession(sessionId: String, title: String) {
        val cleanTitle = title.trim().take(48)
        if (cleanTitle.isBlank()) {
            _uiState.update { it.copy(statusMessage = "标题不能为空") }
            return
        }
        var updatedSessions: List<ChatSessionRecord> = emptyList()
        _uiState.update { state ->
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
        var updatedSessions: List<ChatSessionRecord> = emptyList()
        var pinned = false
        _uiState.update { state ->
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
        if (state.isGenerating) {
            _uiState.update { it.copy(statusMessage = "请先停止当前生成，再清空历史") }
            return
        }
        _uiState.update {
            it.copy(
                chatSessions = emptyList(),
                messages = emptyList(),
                input = "",
                activeChatSessionId = null,
                statusMessage = "已清空聊天记录"
            )
        }
        persistChatSessions(emptyList())
    }

    fun clearChat() {
        viewModelScope.launch {
            engine.stopGeneration()
            generationJob?.cancel()
            _uiState.update { it.afterClearChatGenerationStopped(engine.stats.value) }
            persistChatSessions()
        }
    }

    fun onAppBackgrounded() {
        if (adaptiveTuningJob?.isActive == true) {
            pauseAgentTuning()
        }
        if (!_uiState.value.isGenerating) return
        viewModelScope.launch {
            engine.stopGeneration()
            generationJob?.cancel()
            _uiState.update { it.afterBackgroundGenerationStopped(engine.stats.value) }
            persistChatSessions()
        }
    }

    fun refreshLogs() {
        _uiState.update {
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
        _uiState.update {
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
                _uiState.update {
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
        val title = session?.title?.sanitizeFileName()?.ifBlank { "chat" } ?: "chat"
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
                _uiState.update {
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
                _uiState.update {
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
        persistGenerationParams(updatedParams)
        _uiState.update {
            it.copy(
                params = updatedParams,
                rollbackParams = previousParams,
                agentLogs = agentLogger.recent(),
                statusMessage = "已应用助手生成建议；模型加载、线程、模板和 native 参数未被静默修改。"
            )
        }
    }

    fun rollbackAgentParams() {
        val previous = _uiState.value.rollbackParams ?: return
        persistGenerationParams(previous)
        _uiState.update {
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
            _uiState.update {
                it.copy(
                    statusMessage = "推荐模型尚未在本机。请先到模型页下载或导入，再由 Agent 加载。"
                )
            }
        }
    }

    fun runAgentBenchmark() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentParams = apiGenerationParams()
            busy("正在按当前参数测速...")
            runCatching {
                val device = currentDeviceProfile()
                val result = benchmarkRunner.runCurrentParamsBenchmark(currentParams)
                appendBenchmarkHistory(device, result, currentParams)
                _uiState.update {
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
                _uiState.update { it.copy(statusMessage = "请先停止当前生成，再运行稳定性自检") }
                return@launch
            }
            val model = initialState.models.firstOrNull { it.id == initialState.loadedModelId }
            if (model == null) {
                _uiState.update { it.copy(statusMessage = "请先加载一个本地推理模型，再运行稳定性自检") }
                return@launch
            }
            busy("正在运行稳定性自检...")
            runCatching {
                generationJob?.cancel()
                engine.stopGeneration()
                val preflight = modelStore.validateForLoad(model.id)
                if (!preflight.canLoad) error("加载前检查失败：${preflight.message}")
                LocalModelMemoryAdmissionPolicy.evaluate(model, currentDeviceProfile()).blocker
                    ?.let { error("加载前内存检查失败：$it") }

                val runtime = model.runtime.toLocalChatRuntime()
                val loadParams = model.toLoadParams(initialState.params)
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
                _uiState.update { state ->
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
                _uiState.update {
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
            _uiState.update { it.copy(preference = it.preference.copy(mode = PerformanceMode.PowerSave)) }
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
                        _uiState.update { state ->
                            state.copy(
                                tuningJobState = AgentTuningJobState.PAUSED,
                                tuningPhase = "等待用户续跑",
                                statusMessage = "调优已暂停；不会提交新的候选。"
                            )
                        }
                    }
            }
        } else {
            _uiState.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待任务") }
        }
    }

    fun resumeAgentTuning() {
        adaptiveTuningPauseRequested.set(false)
        val jobId = activeTuningJobId
        if (jobId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
                    .onSuccess {
                        _uiState.update { state ->
                            state.copy(
                                tuningJobState = AgentTuningJobState.RUNNING,
                                tuningPhase = "继续执行",
                                statusMessage = "调优已继续。"
                            )
                        }
                    }
            }
        } else {
            _uiState.update { it.copy(tuningJobState = AgentTuningJobState.IDLE) }
        }
    }

    fun cancelAgentTuning() {
        adaptiveTuningCancelRequested.set(true)
        val jobId = activeTuningJobId
        if (jobId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { cancelPersistedTuningJob(jobId) }
                    .onSuccess { canceled ->
                        _uiState.update {
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
            _uiState.update { it.copy(tuningJobState = AgentTuningJobState.IDLE, statusMessage = "当前没有运行中的调优任务。") }
        }
    }

    fun queryAgentTuningJob() {
        val identity = activeRuntimeIdentity ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { runtimeProfileStore.currentRuntimeState(identity.identityHash) }
                .onSuccess { snapshot ->
                    val job = snapshot?.activeJob
                    _uiState.update { state ->
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
            _uiState.update { it.copy(statusMessage = "运行时正在执行其他调优生命周期操作。") }
            return
        }
        adaptiveTuningJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val pending = runCatching { runtimeProfileStore.pendingTransaction(identity.identityHash) }
                    .getOrNull()
                if (pending == null) {
                    _uiState.update { it.copy(statusMessage = "当前没有可应用的 pending profile。") }
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
                val restored = mergeExecutionProfile(_uiState.value.params, restoredProfile)
                persistGenerationParams(restored)
                _uiState.update {
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
                _uiState.update {
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
        _uiState.update { it.copy(preference = it.preference.copy(mode = PerformanceMode.PowerSave)) }
        startAgentTuning(AgentDebugMode.PowerSave)
    }

    private fun startAgentTuning(
        debugMode: AgentDebugMode,
        autoApply: Boolean = true,
        precreatedJob: TuningJobEntity? = null,
        preferenceOverride: UserPreference? = null
    ): Boolean {
        if (adaptiveTuningJob?.isActive == true) {
            _uiState.update { it.copy(statusMessage = "已有调优任务在运行。") }
            return false
        }
        val identity = activeRuntimeIdentity
        val model = activeModelForRuntimeProfile
        val committed = engine.activeExecutionProfile()
        if (identity == null || model == null || committed == null) {
            _uiState.update { it.copy(statusMessage = "请先通过正式模型页加载本地模型，再开始智能调参。") }
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
                    _uiState.update {
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
                _uiState.update {
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
                    _uiState.update {
                        it.copy(
                            tuningPhase = "${stage.label}候选搜索",
                            statusMessage = "${debugMode.label}：开始 ${stage.label} 阶段；仅从上一阶段最佳配置继续。"
                        )
                    }
                    for ((candidateIndex, candidate) in stageCandidates.withIndex()) {
                        awaitTuningBoundary(job.jobId)
                        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
                        val summary = candidate.executionSummary
                        _uiState.update {
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
                                        _uiState.update { state ->
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
                        if (tuningCandidateScore(result).eligible) {
                            eligibleCandidates++
                        } else {
                            rejectedCandidates++
                        }
                        completedCandidates++
                        _uiState.update {
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
                        scoreOf = { (_, result) -> tuningCandidateScore(result) },
                        contextTokensOf = { (candidate, _) -> candidate.profile.loadBound.nCtx ?: 0 }
                    ) ?: error("${stage.label}没有候选同时通过正确性、安全、签名和稳定性门槛")
                    currentBest = selectedStageCandidate.first.profile
                    selectedProbeResult = selectedStageCandidate.second
                    val selectedSummary = tuningExecutionParameterSummary(currentBest)
                    selectedStageSummaries += "${stage.label}：$selectedSummary"
                    _uiState.update {
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
                    _uiState.update { state ->
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
                val commitScore = tuningCandidateScore(result)
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
                    genTokens = result.stats.completionTokens,
                    decodeMs = result.stats.decodeMs,
                    decodeTps = result.stats.decodeTps,
                    e2eTps = result.stats.e2eTps,
                    nativePssKb = result.stats.nativePssKb,
                    processRssKb = result.stats.processRssKb,
                    availMemKb = result.stats.availMemKb,
                    totalMemKb = result.stats.totalMemKb,
                    modelMemoryBudgetKb = result.stats.modelMemoryBudgetKb,
                    bestThreadCount = selectedThreads,
                    stable = true
                )
                appendBenchmarkHistory(device, benchmark, mergeExecutionProfile(_uiState.value.params, candidateProfile.engineProfile))
                modelStore.markLoaded(model.id)
                val profileState = runtimeProfileStore.currentRuntimeState(identity.identityHash)
                _uiState.update { state ->
                    state.copy(
                        params = mergeExecutionProfile(state.params, candidateProfile.engineProfile),
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
                _uiState.update {
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
            _uiState.update { it.copy(tuningJobState = AgentTuningJobState.PAUSED, tuningPhase = "等待续跑") }
            delay(250L)
        }
        if (adaptiveTuningCancelRequested.get()) error("用户取消调优")
        runCatching { runtimeProfileStore.resumeTuningJob(jobId) }
    }

    private fun agentJobState(job: TuningJobEntity): AgentTuningJobState = when {
        job.cancellationRequested -> AgentTuningJobState.CANCELING
        else -> when (job.state.uppercase(Locale.ROOT)) {
            PersistedTuningJobState.QUEUED.name -> AgentTuningJobState.QUEUED
            PersistedTuningJobState.RUNNING.name -> AgentTuningJobState.RUNNING
            PersistedTuningJobState.PAUSED.name -> AgentTuningJobState.PAUSED
            PersistedTuningJobState.CANCELING.name -> AgentTuningJobState.CANCELING
            PersistedTuningJobState.VALIDATING.name -> AgentTuningJobState.VALIDATING
            PersistedTuningJobState.SUCCEEDED.name -> AgentTuningJobState.SUCCEEDED
            PersistedTuningJobState.RECOVERING.name -> AgentTuningJobState.RECOVERING
            else -> AgentTuningJobState.FAILED
        }
    }

    private fun estimateTuningEtaSeconds(model: ModelManifest, mode: AgentDebugMode): Long {
        val history = _uiState.value.benchmarkHistory
            .filter { it.modelId == model.id }
        val previous = history.firstOrNull()?.result?.loadMs?.takeIf { it > 0 }
        val multiplier = when (mode) {
            AgentDebugMode.Quick -> 1.5
            AgentDebugMode.Standard -> 2.0
            AgentDebugMode.Deep -> 2.5
            AgentDebugMode.PowerSave -> 1.8
        }
        return ((previous ?: 30_000L) * multiplier / 1000.0).toLong().coerceAtLeast(15L)
    }

    private suspend fun applyPendingRuntimeTransaction(pending: PendingRuntimeProfileTransaction) {
        val model = activeModelForRuntimeProfile
            ?: error("当前没有可用于 pending profile 的已加载模型")
        var lease: com.muyuchat.core.engine.EngineLifecycleLease? = null
        val transactionId = pending.journal.transactionId
        try {
            _uiState.update {
                it.copy(
                    busy = true,
                    engineLifecycle = AgentEngineLifecycle.RELOADING,
                    tuningJobState = AgentTuningJobState.VALIDATING,
                    tuningPhase = "应用 pending profile",
                    statusMessage = "正在重新加载并验证 pending profile…"
                )
            }
            runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.VALIDATING, "USER_APPLY")
            pending.journal.jobId?.let { jobId ->
                val persistedJob = runtimeProfileStore.tuningJob(jobId)
                    ?: error("pending profile 对应的调优任务不存在")
                if (persistedJob.state == PersistedTuningJobState.PAUSED.name) {
                    runtimeProfileStore.resumeTuningJob(jobId, "USER_APPLY_RESUMED")
                }
                runtimeProfileStore.transitionTuningJob(
                    jobId,
                    PersistedTuningJobState.VALIDATING,
                    "USER_APPLY"
                )
            }
            val candidate = pending.pendingExecutionProfile.asTuningExecutionProfile(
                kind = ExecutionProfileKind.BALANCED,
                verificationLevel = ProfileVerificationLevel.UNVERIFIED,
                reason = "用户应用已验证的 pending profile"
            )
            lease = engine.acquireExclusiveLifecycleLease()
            val authorization = engine.stagePendingExecutionProfile(
                transactionId = transactionId,
                profile = pending.pendingExecutionProfile,
                rollbackTargetProfileId = pending.journal.rollbackTargetProfileId
            )
            val result = runCandidateCanary(
                candidate = candidate,
                canaryParams = com.muyuchat.core.tuning.CanaryEvaluationParams(),
                authorization = authorization,
                disposition = com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                lifecycleLease = lease
            )
            runtimeProfileStore.recordMeasurement(
                candidateMeasurement(
                    profileId = pending.pendingExecutionProfile.profileId,
                    jobId = pending.journal.jobId,
                    phase = "USER_APPLY_CANARY",
                    result = result
                )
            )
            val applyScore = tuningCandidateScore(result)
            if (!applyScore.eligible) error(applyScore.reason ?: result.detail)
            engine.commitDeferredPendingExecutionProfile(authorization, lease ?: error("pending apply lease 已释放"))
            val signatures = engine.parameterSignatureSnapshot() ?: error("pending profile 缺少签名快照")
            runtimeProfileStore.commitCandidate(
                transactionId = transactionId,
                verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                activeLoadedSignature = signatures.active?.digest ?: signatures.resolved.digest,
                effectiveExecutionSignature = signatures.effective?.digest ?: signatures.committed.digest
            )
            pendingProfileTransactionId = null
            _uiState.update { state ->
                state.copy(
                    params = mergeExecutionProfile(state.params, pending.pendingExecutionProfile),
                    profileId = pending.pendingExecutionProfile.profileId,
                    revision = pending.pendingExecutionProfile.revision,
                    profileRecordState = AgentProfileRecordState.COMMITTED,
                    verification = AgentProfileVerification.COMPATIBLE,
                    pendingProfile = null,
                    reloadRequired = false,
                    engineLifecycle = AgentEngineLifecycle.READY,
                    tuningJobState = AgentTuningJobState.SUCCEEDED,
                    tuningPhase = "pending 已提交",
                    busy = false,
                    statusMessage = "pending profile 已通过正确性与签名核对并提交。"
                )
            }
        } catch (error: Throwable) {
            lease?.release()
            lease = null
            val recovery = runCatching {
                runtimeProfileStore.rejectCandidate(
                    transactionId = transactionId,
                    failureStage = "USER_APPLY",
                    failureCode = "PENDING_APPLY_FAILED",
                    failureSummary = error.message ?: "pending apply failed"
                )
            }.getOrNull()
            val restoredId = recovery?.rollbackProfileId?.let { rollbackId ->
                val rollback = runtimeProfileStore.reconstructedProfile(rollbackId)
                if (rollback != null && restoreExactRuntimeProfile(model, rollback)) rollbackId else null
            }
            if (recovery != null) runCatching { runtimeProfileStore.completeRecovery(transactionId, restoredId) }
            if (pendingProfileTransactionId == transactionId) pendingProfileTransactionId = null
            _uiState.update {
                it.copy(
                    busy = false,
                    engineLifecycle = if (restoredId != null) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR,
                    tuningJobState = AgentTuningJobState.FAILED,
                    tuningPhase = "pending 应用失败",
                    pendingProfile = null,
                    statusMessage = "pending profile 应用失败：${error.message ?: "未知错误"}"
                )
            }
        } finally {
            runCatching { lease?.release() }
        }
    }

    private suspend fun rollbackCommittedRuntimeProfile(
        identity: ModelRuntimeIdentity,
        model: ModelManifest
    ) {
        val current = runtimeProfileStore.currentRuntimeState(identity.identityHash)
            ?: error("当前运行配置未持久化")
        val activeEntity = current.activeProfile ?: error("当前没有 active profile")
        val parentId = activeEntity.parentCommittedProfileId
            ?: current.pointers?.lastKnownGoodProfileId?.takeIf { it != activeEntity.profileId }
        if (parentId == null) {
            _uiState.update { it.copy(statusMessage = "当前 profile 没有更早的稳定版本可回退。") }
            return
        }
        val parentEntity = runtimeProfileStore.profile(parentId) ?: error("回退目标不存在")
        val target = runtimeProfileStore.reconstructedProfile(parentId) ?: error("回退目标无法重建")
        val rollbackCandidate = target.copy(
            profileId = "rollback-${UUID.randomUUID().toString().replace("-", "").take(20)}",
            revision = nextExecutionProfileRevision(
                committedRevision = activeEntity.revision,
                persistedRevisions = runtimeProfileStore.profiles(identity.identityHash).map { it.revision }
            ),
            resolvedAt = System.currentTimeMillis()
        )
        val transactionId = "rollback-${UUID.randomUUID()}"
        var lease: com.muyuchat.core.engine.EngineLifecycleLease? = null
        try {
            _uiState.update {
                it.copy(
                    busy = true,
                    engineLifecycle = AgentEngineLifecycle.ROLLING_BACK,
                    tuningJobState = AgentTuningJobState.RECOVERING,
                    tuningPhase = "回退到上一稳定 profile",
                    statusMessage = "正在回退并重新验证…"
                )
            }
            runtimeProfileStore.stageCandidate(
                snapshot = rollbackCandidate.toPersistedExecutionProfileSnapshot(
                    parentCommittedProfileId = parentEntity.parentCommittedProfileId,
                    verificationLevel = PersistedProfileVerificationLevel.COMPATIBLE,
                    sourceSummaryJson = JSONObject().put("source", "user_rollback").put("target", parentId).toString()
                ),
                transactionId = transactionId,
                rollbackTargetProfileId = activeEntity.profileId
            )
            runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.APPLYING, "ROLLBACK_APPLYING")
            lease = engine.acquireExclusiveLifecycleLease()
            val authorization = engine.stagePendingExecutionProfile(
                transactionId = transactionId,
                profile = rollbackCandidate,
                rollbackTargetProfileId = activeEntity.profileId
            )
            runtimeProfileStore.updateJournalStage(transactionId, TuningJournalState.VALIDATING, "ROLLBACK_VALIDATING")
            val wrapper = rollbackCandidate.asTuningExecutionProfile(
                ExecutionProfileKind.BALANCED,
                ProfileVerificationLevel.UNVERIFIED,
                "用户回退"
            )
            val result = runCandidateCanary(
                wrapper,
                com.muyuchat.core.tuning.CanaryEvaluationParams(),
                authorization,
                com.muyuchat.core.engine.PendingProfileDisposition.DEFER_TO_LEASE_HOLDER,
                lease
            )
            runtimeProfileStore.recordMeasurement(
                candidateMeasurement(
                    profileId = rollbackCandidate.profileId,
                    jobId = null,
                    phase = "ROLLBACK_CANARY",
                    result = result
                )
            )
            val rollbackScore = tuningCandidateScore(result)
            if (!rollbackScore.eligible) error(rollbackScore.reason ?: result.detail)
            engine.commitDeferredPendingExecutionProfile(authorization, lease ?: error("rollback lease 已释放"))
            val signatures = engine.parameterSignatureSnapshot() ?: error("回退缺少签名快照")
            runtimeProfileStore.commitCandidate(
                transactionId,
                PersistedProfileVerificationLevel.COMPATIBLE,
                signatures.active?.digest ?: signatures.resolved.digest,
                signatures.effective?.digest ?: signatures.committed.digest
            )
            _uiState.update { state ->
                state.copy(
                    params = mergeExecutionProfile(state.params, rollbackCandidate),
                    profileId = rollbackCandidate.profileId,
                    revision = rollbackCandidate.revision,
                    profileRecordState = AgentProfileRecordState.COMMITTED,
                    verification = AgentProfileVerification.COMPATIBLE,
                    engineLifecycle = AgentEngineLifecycle.READY,
                    tuningJobState = AgentTuningJobState.SUCCEEDED,
                    tuningPhase = "回退完成",
                    busy = false,
                    rollbackProfile = parentEntity.parentCommittedProfileId?.let {
                        AgentRollbackProfile(it, null, "可继续回退", true)
                    },
                    statusMessage = "已回退到上一稳定配置并完成正确性验证。"
                )
            }
        } catch (error: Throwable) {
            lease?.release()
            lease = null
            val recovery = runCatching {
                runtimeProfileStore.rejectCandidate(
                    transactionId,
                    "ROLLBACK",
                    "ROLLBACK_FAILED",
                    error.message ?: "rollback failed"
                )
            }.getOrNull()
            val restoredId = recovery?.rollbackProfileId?.let { rollbackId ->
                val previous = runtimeProfileStore.reconstructedProfile(rollbackId)
                if (previous != null && restoreExactRuntimeProfile(model, previous)) rollbackId else null
            }
            if (recovery != null) runCatching { runtimeProfileStore.completeRecovery(transactionId, restoredId) }
            _uiState.update {
                it.copy(
                    busy = false,
                    engineLifecycle = if (restoredId != null) AgentEngineLifecycle.READY else AgentEngineLifecycle.ERROR,
                    tuningJobState = AgentTuningJobState.FAILED,
                    statusMessage = "回退失败：${error.message ?: "未知错误"}"
                )
            }
        } finally {
            runCatching { lease?.release() }
        }
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
            params = model.toLoadParams(_uiState.value.params, profile),
            qairtBundleSha256 = qairtSha,
            runtimeIdentity = profile.runtimeIdentity,
            executionProfile = profile
        ).isSuccess
    }

    fun showAgentDebugExplanation() {
        _uiState.update {
            it.copy(
                statusMessage = "智能调试由规则包执行参数搜索，当前已加载模型只用于解释调试结果。"
            )
        }
    }

    private fun runAgentDebug(debugMode: AgentDebugMode, preference: UserPreference) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(preference = preference) }
            busy("Agent 正在运行${debugMode.label}...")
            runCatching {
                val device = currentDeviceProfile()
                val basePlan = _uiState.value.agentRecommendation?.tuningPlan
                    ?: buildRecommendation(preference, device, null).tuningPlan
                val result = benchmarkRunner.runThreadSweep(
                    plan = basePlan,
                    candidates = threadSweepCandidates(device, basePlan.nThreads, debugMode),
                    config = debugMode.sweepConfig()
                )
                val recommendation = buildRecommendation(preference, device, result.decodeTps)
                    .withBenchmarkThread(result)
                val previousParams = _uiState.value.params
                val updatedParams = recommendation.tuningPlan.applyTo(_uiState.value.params)
                appendBenchmarkHistory(device, result, updatedParams)
                agentLogger.append(
                    device = device,
                    recommendation = recommendation,
                    benchmark = result,
                    appliedPlan = recommendation.tuningPlan,
                    userConfirmed = true
                )
                persistGenerationParams(updatedParams)
                _uiState.update {
                    it.copy(
                        deviceProfile = device,
                        agentRecommendation = recommendation,
                        benchmark = result,
                        benchmarkHistory = benchmarkHistoryLogger.recent(),
                        preference = preference,
                        params = updatedParams,
                        rollbackParams = previousParams,
                        agentLogs = agentLogger.recent(),
                        busy = false,
                        lastAutoTuningSummary = tuningSummary(result, recommendation),
                        statusMessage = result.error ?: "${debugMode.label}完成并已应用 Agent 参数：decode ${"%.2f".format(result.decodeTps)} token/s"
                    )
                }
            }.onFailure { error ->
                fail("${debugMode.label}失败：${error.message}")
            }
        }
    }

    private suspend fun runPostLoadBenchmarkAndTune(model: ModelManifest) {
        runCatching {
            val device = currentDeviceProfile()
            val initialRecommendation = advisor.recommend(
                device = device,
                localModels = listOf(model),
                remoteFiles = emptyList(),
                preference = _uiState.value.preference
            )
            val result = benchmarkRunner.runThreadSweep(
                initialRecommendation.tuningPlan,
                threadSweepCandidates(device, initialRecommendation.tuningPlan.nThreads, AgentDebugMode.Quick),
                AgentDebugMode.Quick.sweepConfig()
            )
            val recommendation = advisor.recommend(
                device = currentDeviceProfile(),
                localModels = listOf(model),
                remoteFiles = emptyList(),
                preference = _uiState.value.preference,
                lastDecodeTps = result.decodeTps.takeIf { it > 0.0 }
            ).withBenchmarkThread(result)
            val previousParams = _uiState.value.params
            val updatedParams = recommendation.tuningPlan.applyTo(_uiState.value.params)
            appendBenchmarkHistory(device, result, updatedParams)
            agentLogger.append(
                device = device,
                recommendation = recommendation,
                benchmark = result,
                appliedPlan = recommendation.tuningPlan,
                userConfirmed = false
            )
            persistGenerationParams(updatedParams)
            _uiState.update {
                it.copy(
                    params = updatedParams,
                    deviceProfile = device,
                    agentRecommendation = recommendation,
                    benchmark = result,
                    benchmarkHistory = benchmarkHistoryLogger.recent(),
                    agentLogs = agentLogger.recent(),
                    logs = engine.recentLogs(),
                    busy = false,
                    autoTuningInProgress = false,
                    rollbackParams = previousParams,
                    lastAutoTuningSummary = tuningSummary(result, recommendation),
                    nativeStatsJson = engine.nativeStatsJson(),
                    statusMessage = result.error
                        ?: "Agent 短基准完成并已应用安全参数：decode ${"%.2f".format(result.decodeTps)} token/s，n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}",
                    tab = AppTab.CHAT
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    busy = false,
                    autoTuningInProgress = false,
                    nativeStatsJson = engine.nativeStatsJson(),
                    statusMessage = "模型已加载，但 Agent 短基准失败：${error.message}。可先聊天，或稍后在 Agent 页手动重跑短基准。",
                    tab = AppTab.CHAT
                )
            }
        }
    }

    fun toggleApi(enabled: Boolean) {
        val restEnabled = if (enabled) _uiState.value.restEnabled else false
        runCatching {
            if (enabled) {
                startApiServer(if (restEnabled) "0.0.0.0" else "127.0.0.1")
            } else {
                stopApiServer()
            }
        }.onSuccess {
            persistApiPreferences(apiEnabled = enabled, restEnabled = restEnabled)
            _uiState.update {
                it.copy(
                    apiEnabled = enabled,
                    restEnabled = restEnabled,
                    localApiAddress = apiUrl("127.0.0.1"),
                    openApiAddress = currentOpenApiAddress()
                )
            }
            updateLocalApiForegroundService(enabled, restEnabled)
        }.onFailure { error ->
            fail("本机 API 启动失败：${error.message}")
        }
    }

    fun toggleRest(enabled: Boolean) {
        val apiEnabled = enabled || _uiState.value.apiEnabled
        runCatching {
            if (enabled) {
                startApiServer("0.0.0.0")
            } else if (_uiState.value.apiEnabled) {
                startApiServer("127.0.0.1")
            } else {
                stopApiServer()
            }
        }.onSuccess {
            persistApiPreferences(apiEnabled = apiEnabled, restEnabled = enabled)
            _uiState.update {
                it.copy(
                    apiEnabled = apiEnabled,
                    restEnabled = enabled,
                    localApiAddress = apiUrl("127.0.0.1"),
                    openApiAddress = currentOpenApiAddress()
                )
            }
            updateLocalApiForegroundService(apiEnabled, enabled)
        }.onFailure { error ->
            fail("REST 启动失败：${error.message}")
        }
    }

    override fun onCleared() {
        stopApiServer()
        LocalApiForegroundService.stop(getApplication())
        LocalApiRuntime.engine = null
        LocalApiRuntime.streamChatWithContextProvider = null
        LocalApiRuntime.stopGenerationProvider = null
        LocalApiRuntime.imageGenerationProvider = null
        LocalApiRuntime.controlPlane = null
        localImageWorkerClient.close()
        super.onCleared()
    }

    private fun updateLocalApiForegroundService(enabled: Boolean, restEnabled: Boolean) {
        runCatching {
            if (enabled) {
                LocalApiForegroundService.start(getApplication(), restEnabled)
            } else {
                LocalApiForegroundService.stop(getApplication())
            }
        }.onFailure { error ->
            _uiState.update { it.copy(statusMessage = "本地 API 保活服务启动失败：${error.message}") }
        }
    }

    private fun startApiServer(bindHost: String) {
        val current = apiServer
        if (current?.isRunning == true && activeApiBindHost == bindHost) return
        current?.shutdown()
        val next = McaLoopbackServer(port = REST_PORT, bindHost = bindHost, apiKey = apiKey)
        next.start()
        apiServer = next
        activeApiBindHost = bindHost
    }

    private fun stopApiServer() {
        runCatching { apiServer?.shutdown() }
        apiServer = null
        activeApiBindHost = null
    }

    private fun applyWebSearchAnswerGuardsToLastAssistant(): WebSearchCitationAudit? {
        var citationAudit: WebSearchCitationAudit? = null
        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            val index = updated.indexOfLast { it.role == Role.ASSISTANT }
            if (index < 0) return@update state
            val guardResult = updated[index].withWebSearchAnswerGuards()
            citationAudit = guardResult.citationAudit
            if (guardResult.message == updated[index]) return@update state
            updated[index] = guardResult.message
            val sessionId = state.activeChatSessionId
            state.copy(
                messages = updated,
                chatSessions = if (sessionId == null) {
                    state.chatSessions
                } else {
                    state.chatSessions.upsertSession(
                        sessionId = sessionId,
                        messages = updated,
                        assistantId = state.selectedAssistantId,
                        modelMode = state.selectedChatBackend.bindingValue(),
                        modelId = state.currentChatModelId()
                    )
                }
            )
        }
        return citationAudit
    }

    private fun ensureVisibleAssistantReplyAfterCompletion() {
        _uiState.update { state ->
            val updated = state.messages.withVisibleAssistantCompletionFallback()
            if (updated === state.messages) return@update state
            val sessionId = state.activeChatSessionId
            state.copy(
                messages = updated,
                chatSessions = if (sessionId == null) {
                    state.chatSessions
                } else {
                    state.chatSessions.upsertSession(
                        sessionId = sessionId,
                        messages = updated,
                        assistantId = state.selectedAssistantId,
                        modelMode = state.selectedChatBackend.bindingValue(),
                        modelId = state.currentChatModelId()
                    )
                }
            )
        }
    }

    private fun recordWebSearchCitationAudit(recordId: String?, audit: WebSearchCitationAudit) {
        if (recordId.isNullOrBlank()) return
        val current = _uiState.value.webSearchDiagnostics.firstOrNull { it.id == recordId } ?: return
        val updatedRecord = current.copy(
            message = current.message + if (audit.repaired) " · 引用已修正" else " · 引用已审计",
            warnings = (current.warnings + audit.warnings).distinct(),
            closedLoopChecks = (current.closedLoopChecks + audit.closedLoopChecks).distinct()
        )
        val records = webSearchDiagnosticStore.replace(updatedRecord)
        _uiState.update {
            it.copy(
                webSearchDiagnostics = records,
                webSearchStatusMessage = audit.statusMessage,
                statusMessage = audit.statusMessage
            )
        }
    }

    private fun appendAssistant(
        delta: String,
        reasoningDelta: String = "",
        reasoningDurationMs: Long = 0L
    ) {
        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            val index = updated.indexOfLast { it.role == Role.ASSISTANT }
            if (index >= 0) {
                val current = updated[index]
                updated[index] = current.copy(
                    content = current.content + delta,
                    reasoningContent = current.reasoningContent + reasoningDelta,
                    reasoningDurationMs = maxOf(current.reasoningDurationMs, reasoningDurationMs)
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

    private fun removePendingAssistantPlaceholder() {
        _uiState.update { state ->
            val index = state.messages.indexOfLast { it.role == Role.ASSISTANT }
            if (index < 0) return@update state
            val pending = state.messages[index]
            if (pending.content.isNotBlank() || pending.reasoningContent.isNotBlank()) return@update state
            val updated = state.messages.filterIndexed { messageIndex, _ -> messageIndex != index }
            val sessionId = state.activeChatSessionId
            state.copy(
                messages = updated,
                chatSessions = if (sessionId == null) {
                    state.chatSessions
                } else {
                    state.chatSessions.upsertSession(
                        sessionId = sessionId,
                        messages = updated,
                        assistantId = state.selectedAssistantId,
                        modelMode = state.selectedChatBackend.bindingValue(),
                        modelId = state.currentChatModelId()
                    )
                }
            )
        }
    }

    private fun GenerateEvent.Error.isConfigurationActionError(): Boolean = code in setOf(
        "model_reload_required",
        "model_reload_required_authorized",
        "execution_override_forbidden",
        "model_behavior_override_forbidden",
        "active_profile_drift",
        "model_mismatch"
    )

    private fun attachWebSearchEvidenceToPendingAssistant(
        sources: List<ChatSourceReference>,
        trace: ChatWebSearchTrace?
    ) {
        if (sources.isEmpty() && trace?.hasContent != true) return
        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            val index = updated.indexOfLast { it.role == Role.ASSISTANT }
            if (index >= 0) {
                val current = updated[index]
                updated[index] = current.copy(
                    sourceReferences = sources.ifEmpty { current.sourceReferences },
                    webSearchTrace = trace ?: current.webSearchTrace
                )
            } else {
                updated += ChatMessage(
                    role = Role.ASSISTANT,
                    content = "",
                    sourceReferences = sources,
                    webSearchTrace = trace
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

    private fun persistChatSessions(sessions: List<ChatSessionRecord> = _uiState.value.chatSessions) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatSessionStore.save(sessions) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(statusMessage = "聊天历史保存失败：${error.message}")
                    }
                }
        }
    }

    private fun persistFiles(files: List<FileAssetRecord> = _uiState.value.files) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatSessionStore.saveFiles(files) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(statusMessage = "文件库保存失败：${error.message}")
                    }
                }
        }
    }

    private fun busy(message: String) {
        _uiState.update { it.copy(busy = true, statusMessage = message) }
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(busy = false, statusMessage = message) }
    }

    private fun DownloadTaskSnapshot.progressText(): String {
        val total = expectedLength.takeIf { it > 0L }
        val percent = total?.let {
            downloadedBytes.toDouble().div(it).times(100.0).coerceIn(0.0, 100.0)
        }
        val progress = if (total != null) {
            "${formatPercent(percent ?: 0.0)} · ${formatBytes(downloadedBytes)} / ${formatBytes(total)}"
        } else {
            "${formatBytes(downloadedBytes)} / 未知大小"
        }
        val speed = speedBytesPerSecond
            .takeIf { it > 0L }
            ?.let { " · ${formatBytes(it)}/s" }
            .orEmpty()
        val remaining = remainingSeconds
            ?.let { " · 剩余约 ${formatDuration(it)}" }
            .orEmpty()
        val error = errorMessage
            ?.takeIf { it.isNotBlank() }
            ?.let { " · $it" }
            .orEmpty()
        val label = when (status) {
            DownloadStatus.RUNNING -> "正在下载"
            DownloadStatus.FAILED -> "连接中断，准备续传"
            DownloadStatus.DONE -> "下载完成"
            DownloadStatus.PAUSED -> "已暂停"
            DownloadStatus.QUEUED -> "排队中"
        }
        return "$label ${fileName}: $progress$speed$remaining$error"
    }

    private fun formatPercent(value: Double): String = "%.1f%%".format(value)

    private fun formatDuration(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val minutes = safe / 60
        val remainSeconds = safe % 60
        val hours = minutes / 60
        val remainMinutes = minutes % 60
        return when {
            hours > 0 -> "${hours}小时${remainMinutes}分"
            minutes > 0 -> "${minutes}分${remainSeconds}秒"
            else -> "${remainSeconds}秒"
        }
    }

    private fun downloadFailureAdvice(message: String?): String {
        val raw = message.orEmpty()
        return when {
            raw.contains("SHA-256", ignoreCase = true) -> raw
            raw.contains("大小不匹配") -> raw
            raw.contains("空间不足") -> raw
            raw.contains("Software caused connection abort", ignoreCase = true) ||
                raw.contains("unexpected end of stream", ignoreCase = true) ||
                raw.contains("timeout", ignoreCase = true) ||
                raw.contains("中断") ->
                "$raw。临时文件已保留，再次点击下载会尝试续传。"
            else -> raw.ifBlank { "未知错误。可重新点击下载，MCA 会优先尝试续传。" }
        }
    }

    private fun List<ChatSessionRecord>.upsertSession(
        sessionId: String,
        messages: List<ChatMessage>,
        assistantId: String? = null,
        modelMode: String? = null,
        modelId: String? = null
    ): List<ChatSessionRecord> {
        if (messages.isEmpty()) return this
        val existing = firstOrNull { it.id == sessionId }
        val record = ChatSessionRecord(
            id = sessionId,
            title = if (existing?.manualTitle == true) existing.title else messages.chatTitle(),
            messages = messages,
            pinned = existing?.pinned ?: false,
            manualTitle = existing?.manualTitle ?: false,
            updatedAt = System.currentTimeMillis(),
            projectId = existing?.projectId,
            assistantId = assistantId ?: existing?.assistantId,
            modelMode = modelMode ?: existing?.modelMode,
            modelId = if (modelMode != null) modelId else existing?.modelId
        )
        return (listOf(record) + filterNot { it.id == sessionId }).sortedForHistory()
    }

    private fun List<ChatSessionRecord>.bindSession(
        sessionId: String?,
        assistantId: String? = null,
        modelMode: String? = null,
        modelId: String? = null
    ): List<ChatSessionRecord> {
        if (sessionId == null) return this
        var changed = false
        val updated = map { session ->
            if (session.id != sessionId) {
                session
            } else {
                val next = session.copy(
                    assistantId = assistantId ?: session.assistantId,
                    modelMode = modelMode ?: session.modelMode,
                    modelId = if (modelMode != null) modelId else session.modelId
                )
                if (next != session) changed = true
                next
            }
        }
        return if (changed) updated else this
    }

    private fun MainUiState.currentChatModelId(): String? =
        when (selectedChatBackend) {
            ChatBackend.LOCAL -> loadedModelId
            ChatBackend.CLOUD -> selectedCloudChatModelId
        }

    private fun ChatBackend.bindingValue(): String =
        name.lowercase()

    private fun String?.toChatBackendOrNull(): ChatBackend? =
        when (this?.trim()?.lowercase()) {
            "local" -> ChatBackend.LOCAL
            "cloud" -> ChatBackend.CLOUD
            else -> null
        }

    private fun List<ChatMessage>.chatTitle(): String {
        val userText = firstOrNull { it.role == Role.USER }?.content.orEmpty()
        val fileName = Regex("""【上传文件：([^】]+)】""").find(userText)?.groupValues?.getOrNull(1)
        val imageName = Regex("""【上传图片：([^】]+)】""").find(userText)?.groupValues?.getOrNull(1)
        val raw = userText
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("【上传文件：") &&
                    !line.startsWith("【上传图片：") &&
                    !line.startsWith("（文件较大")
            }
            ?: fileName?.let { "文件问答：$it" }
            ?: imageName?.let { "图片：$it" }
            ?: "新对话"
        val compact = raw
            .replace(Regex("""[#>*`_\[\]()]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return compact.ifBlank { "新对话" }.let { if (it.length > 28) it.take(28) + "..." else it }
    }

    private fun List<ChatSessionRecord>.sortedForHistory(): List<ChatSessionRecord> =
        sortedWith(
            compareByDescending<ChatSessionRecord> { it.pinned }
                .thenByDescending { it.updatedAt }
        )

    private fun List<ImageAssetRecord>.sortedImagesForLibrary(): List<ImageAssetRecord> =
        sortedByDescending { it.createdAt }

    private fun List<FileAssetRecord>.sortedFilesForLibrary(): List<FileAssetRecord> =
        sortedByDescending { it.createdAt }

    private fun List<CloudModelRecord>.matchingCloudModel(
        kind: CloudModelKind,
        format: CloudApiFormat,
        baseUrl: String,
        modelName: String,
        imageFormat: CloudImageApiFormat? = null,
        imageEndpointPath: String = ""
    ): CloudModelRecord? =
        firstOrNull {
            it.kind == kind &&
                it.apiFormat == format &&
                (
                    kind != CloudModelKind.IMAGE ||
                        (
                            it.imageApiFormat == (imageFormat ?: it.imageApiFormat) &&
                                it.imageEndpointPath.trim().trim('/') == imageEndpointPath.trim().trim('/')
                            )
                    ) &&
                it.baseUrl.trim().trimEnd('/') == baseUrl.trim().trimEnd('/') &&
                it.modelName == modelName
        }

    private fun List<CloudModelRecord>.upsertCloudModel(model: CloudModelRecord): List<CloudModelRecord> =
        (listOf(model) + filterNot { it.id == model.id })
            .sortedWith(
                compareBy<CloudModelRecord> { it.kind.name }
                    .thenByDescending { it.updatedAt }
            )

    private fun List<ImageGenerationJobRecord>.updateImageJob(
        jobId: String,
        status: ImageGenerationStatusRecord,
        message: String,
        imageAssetId: String? = null,
        preview: PublishedLocalImagePreview? = null
    ): List<ImageGenerationJobRecord> =
        map { job ->
            if (job.id == jobId) {
                job.copy(
                    status = status,
                    message = message,
                    imageAssetId = imageAssetId ?: job.imageAssetId,
                    previewUriString = if (status.terminal) null else {
                        preview?.uriString ?: job.previewUriString
                    },
                    previewMode = if (status.terminal) "" else preview?.mode ?: job.previewMode,
                    previewStep = if (status.terminal) 0 else preview?.step ?: job.previewStep,
                    previewRevision = if (status.terminal) 0L else {
                        preview?.revision ?: job.previewRevision
                    },
                    previewWidth = if (status.terminal) 0 else preview?.width ?: job.previewWidth,
                    previewHeight = if (status.terminal) 0 else preview?.height ?: job.previewHeight
                )
            } else {
                job
            }
        }

    private fun MainUiState.selectedChatCloudConfig(): CloudApiConfig? =
        cloudModels
            .firstOrNull { it.id == selectedCloudChatModelId && it.kind == CloudModelKind.CHAT }
            ?.toChatConfig()

    private fun MainUiState.selectedImageCloudConfig(): CloudApiConfig? =
        cloudModels
            .firstOrNull { it.id == selectedCloudImageModelId && it.kind == CloudModelKind.IMAGE }
            ?.toImageConfig()

    private fun MainUiState.selectedLocalImageModel(): LocalImageModelRecord? =
        localImageModels.firstOrNull { it.id == selectedLocalImageModelId && it.configured }

    private fun CloudApiConfig.normalized(): CloudApiConfig =
        copy(
            providerName = providerName.trim().ifBlank { apiFormat.label },
            displayName = displayName.trim().ifBlank { chatModel.trim().ifBlank { "自定义推理引擎" } },
            baseUrl = baseUrl.trim().trimEnd('/'),
            chatModel = chatModel.trim(),
            imageModel = imageModel.trim(),
            imageSize = imageSize.trim().ifBlank { "1024x1024" },
            imageEndpointPath = imageEndpointPath.trim().trim('/')
        ).normalizedForImageRequest()

    private fun ChatSessionRecord.toMarkdown(): String = buildString {
        append("# ").append(title).append("\n\n")
        append("- 导出时间：").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())).append("\n")
        append("- 消息数量：").append(messages.size).append("\n")
        append("- 会话 ID：").append(id).append("\n\n")
        messages.forEach { message ->
            append("## ").append(message.role.displayLabel()).append("\n\n")
            if (message.role == Role.ASSISTANT && message.reasoningContent.isNotBlank()) {
                append("### 思考过程")
                if (message.reasoningDurationMs > 0L) {
                    append("（用时 ").append(message.reasoningDurationMs / 1000).append(" 秒）")
                }
                append("\n\n")
                append(message.reasoningContent.trim()).append("\n\n")
                append("### 最终回答\n\n")
            }
            append(message.content.ifBlank { "（空消息）" }).append("\n\n")
        }
    }

    private fun Role.displayLabel(): String = when (this) {
        Role.SYSTEM -> "系统"
        Role.USER -> "用户"
        Role.ASSISTANT -> "MCA"
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trim()
            .take(36)

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

    private fun isSupportedTextAttachment(uri: Uri, name: String): Boolean {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(uri).orEmpty().lowercase()
        val lowerName = name.lowercase()
        return mime.startsWith("text/") ||
            mime in setOf("application/json", "application/xml", "application/x-ndjson") ||
            lowerName.endsWith(".txt") ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".markdown") ||
            lowerName.endsWith(".json") ||
            lowerName.endsWith(".jsonl") ||
            lowerName.endsWith(".xml") ||
            lowerName.endsWith(".csv") ||
            lowerName.endsWith(".log") ||
            lowerName.endsWith(".kt") ||
            lowerName.endsWith(".java") ||
            lowerName.endsWith(".py") ||
            lowerName.endsWith(".js") ||
            lowerName.endsWith(".ts")
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
        return output.toByteArray().toString(Charsets.UTF_8) to truncated
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
            mimeType = mimeTypeForTextAttachment(uri, displayName),
            text = text.trim(),
            preview = text.toFilePreview(),
            truncated = truncated,
            source = source,
            sizeBytes = sizeForUri(uri).takeIf { it > 0L } ?: text.toByteArray(Charsets.UTF_8).size.toLong(),
            chatSessionId = chatSessionId
        )

    private fun mimeTypeForTextAttachment(uri: Uri, name: String): String {
        val resolver = getApplication<Application>().contentResolver
        resolver.getType(uri)?.takeIf { it.isNotBlank() }?.let { return it }
        return when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown" -> "text/markdown"
            "json", "jsonl" -> "application/json"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "kt", "java", "py", "js", "ts" -> "text/x-code"
            else -> "text/plain"
        }
    }

    private fun sizeForUri(uri: Uri): Long {
        val app = getApplication<Application>()
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let { File(it).length() } ?: 0L
        }
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun importImageAsset(
        uri: Uri,
        displayName: String,
        source: String,
        prompt: String = "",
        chatSessionId: String? = null
    ): ImageAssetRecord {
        val app = getApplication<Application>()
        val imageDir = File(app.filesDir, "image_assets").apply { mkdirs() }
        val extension = imageExtension(displayName)
        val fileName = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$extension"
        val outputFile = File(imageDir, fileName)
        val input = if (uri.scheme.equals("file", ignoreCase = true)) {
            uri.path?.let { File(it).inputStream() }
        } else {
            app.contentResolver.openInputStream(uri)
        } ?: error("无法读取图片")
        input.use { sourceStream ->
            outputFile.outputStream().use { targetStream ->
                sourceStream.copyTo(targetStream)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
        return ImageAssetRecord(
            id = UUID.randomUUID().toString(),
            name = displayName.ifBlank { "图片" },
            uriString = Uri.fromFile(outputFile).toString(),
            source = source,
            prompt = prompt,
            sizeBytes = outputFile.length(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            chatSessionId = chatSessionId
        )
    }

    private suspend fun createCloudGeneratedImageAsset(
        prompt: String,
        config: CloudApiConfig,
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata
    ): ImageAssetRecord {
        val app = getApplication<Application>()
        val imageDir = File(app.filesDir, "image_assets").apply { mkdirs() }
        val result = cloudImageProvider.generate(config, prompt)
        val extension = imageExtensionForMime(result.mimeType)
        val outputFile = writeImageAssetBytesAtomically(
            directory = imageDir,
            fileName = "cloud-image-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.$extension",
            bytes = result.bytes
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
        val displayPrompt = result.revisedPrompt.ifBlank { prompt }
        return ImageAssetRecord(
            id = UUID.randomUUID().toString(),
            name = "Cloud Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.$extension",
            uriString = Uri.fromFile(outputFile).toString(),
            source = "generated:${config.imageApiFormat.label}",
            prompt = displayPrompt,
            sizeBytes = outputFile.length(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            generationMetadataJson = generationMetadata.toJsonString(),
            chatSessionId = chatSessionId
        )
    }

    private suspend fun createLocalGeneratedImageAsset(
        requestId: String,
        prompt: String,
        model: LocalImageModelRecord,
        options: LocalImageGenerationOptions = LocalImageGenerationOptions(),
        inputDraft: LocalImageInputDraft = LocalImageInputDraft(),
        chatSessionId: String? = null,
        generationMetadata: ImageGenerationHistoryMetadata,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): List<ImageAssetRecord> {
        val app = getApplication<Application>()
        val imageDir = File(app.filesDir, "image_assets").apply { mkdirs() }
        val result = try {
            localImageWorkerClient.generate(
                model = model,
                prompt = prompt,
                options = options,
                inputDraft = inputDraft,
                onProgress = onProgress,
                requestId = requestId
            )
        } catch (error: Throwable) {
            if (error !is CancellationException && error !is LocalImageWorkerCancelledException) {
                recordLocalImageExecutionOutcome(model, error.message ?: "本地 native 生图执行失败。")
            }
            throw error
        }
        recordLocalImageExecutionOutcome(model, failureMessage = null)
        val generationMetadataJson = generationMetadata
            .withNativeExecution(result.executionMetadataJson)
            .toJsonString()
        val timestamp = System.currentTimeMillis()
        val requestToken = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)
        val writtenFiles = mutableListOf<File>()
        return try {
            result.outputs.map { output ->
                val extension = imageExtensionForMime(output.mimeType)
                val suffix = if (result.outputs.size == 1) "" else "-${output.index.toString().padStart(3, '0')}"
                val outputFile = writeImageAssetBytesAtomically(
                    directory = imageDir,
                    fileName = "local-image-$timestamp-$requestToken$suffix.$extension",
                    bytes = output.bytes
                )
                writtenFiles += outputFile
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "Generated image ${output.index} could not be decoded after publication."
                }
                ImageAssetRecord(
                    id = UUID.randomUUID().toString(),
                    name = "Local Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}$suffix.$extension",
                    uriString = Uri.fromFile(outputFile).toString(),
                    source = "generated:${model.runtime.label}",
                    prompt = prompt,
                    sizeBytes = outputFile.length(),
                    width = bounds.outWidth,
                    height = bounds.outHeight,
                    generationMetadataJson = generationMetadataJson,
                    chatSessionId = chatSessionId
                )
            }
        } catch (error: Throwable) {
            writtenFiles.forEach { file -> runCatching { file.delete() } }
            throw error
        }
    }

    private suspend fun generateLocalApiImage(requestId: String, body: String): String {
        val request = ImageGenerationApiContract.parseRequest(body)
        require(requestId.isNotBlank()) { "requestId must not be blank." }
        if (request.preview != null) {
            throw ImageGenerationProviderException(
                code = "unsupported_preview_transport",
                httpStatus = 422,
                message = "This synchronous Images API response cannot publish live preview frames."
            )
        }
        val requestedModel = request.model.orEmpty()
        val model = if (requestedModel.isNotBlank()) {
            val candidate = _uiState.value.localImageModels.firstOrNull { it.id == requestedModel }
                ?: throw ImageGenerationProviderException(
                    code = "image_model_not_found",
                    httpStatus = 404,
                    message = "The requested local image model was not found."
                )
            if (!candidate.configured) {
                throw ImageGenerationProviderException(
                    code = "image_model_not_ready",
                    httpStatus = 409,
                    message = "The requested local image model is not configured."
                )
            }
            candidate
        } else {
            _uiState.value.selectedLocalImageModel()
                ?: throw ImageGenerationProviderException(
                    code = "image_runtime_unavailable",
                    httpStatus = 503,
                    message = "No configured local image model is selected."
                )
        }
        val dispatch = request.toLocalImageApiDispatch()
        val options = dispatch.options
        val inputDraft = dispatch.inputDraft
        val generationLease = localImageGenerationCoordinator.tryAcquire(requestId)
            ?: throw ImageGenerationProviderException(
                code = "image_generation_busy",
                httpStatus = 409,
                message = "Another UI or Local API image request is already running."
            )
        activeLocalApiImageModelId = model.id
        try {
            val result = try {
                localImageWorkerClient.generate(
                    model = model,
                    prompt = request.prompt,
                    options = options,
                    inputDraft = inputDraft,
                    requestId = requestId
                )
            } catch (error: Throwable) {
                error.toLocalImageApiProviderExceptionOrNull()?.let { throw it }
                if (error is IllegalArgumentException) {
                    throw ImageGenerationProviderException(
                        code = "invalid_image_input",
                        httpStatus = 422,
                        message = error.message ?: "The image input could not be prepared."
                    )
                }
                throw error
            }
            return try {
                require(result.outputs.size == request.imageCount) {
                    "The local image worker returned ${result.outputs.size} images; ${request.imageCount} were requested."
                }
                val execution = runCatching { JSONObject(result.executionMetadataJson) }.getOrElse { JSONObject() }
                val nativeEffective = execution.optJSONObject("nativeEffective")
                    ?: error("The local image result is missing nativeEffective execution evidence.")
                require(
                    execution.optBoolean("nativeExecution", false) &&
                        !execution.optBoolean("fallback", true)
                ) {
                    "The local image result did not prove direct native execution without fallback."
                }
                require(execution.optLong("nativeGenerationSequence", 0L) > 0L) {
                    "The local image result is missing a positive native generation sequence."
                }
                require(nativeEffective.getString("runtime") == model.runtime.name) {
                    "The local image result runtime does not match the selected model runtime."
                }
                if (model.runtime == LocalImageRuntime.QNN_HTP) {
                    require(
                        execution.optBoolean("npuActive", false) &&
                            execution.optBoolean("qnnGraphExecution", false)
                    ) {
                        "The local image result did not prove native QNN graph execution."
                    }
                }
                JSONObject()
                    .put("created", System.currentTimeMillis() / 1000L)
                    .put("request_id", requestId)
                    .put("model", model.id)
                    .put("execution", execution)
                    .put(
                        "data",
                        JSONArray().apply {
                            result.outputs.forEach { output ->
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(output.bytes, 0, output.bytes.size, bounds)
                                require(bounds.outWidth > 0 && bounds.outHeight > 0) {
                                    "The local image worker returned an invalid image at index ${output.index}."
                                }
                                put(
                                    JSONObject()
                                        .put("index", output.index)
                                        .put("b64_json", Base64.encodeToString(output.bytes, Base64.NO_WRAP))
                                        .put("mime_type", output.mimeType)
                                        .put("width", bounds.outWidth)
                                        .put("height", bounds.outHeight)
                                        .apply { output.seed?.let { put("seed", it) } }
                                )
                            }
                        }
                    )
                    .toString()
            } catch (error: ImageGenerationProviderException) {
                throw error
            } catch (error: Throwable) {
                throw ImageGenerationProviderException(
                    code = "invalid_image_worker_response",
                    httpStatus = 502,
                    message = error.message ?: "The local image worker returned invalid execution evidence."
                )
            }
        } finally {
            if (activeLocalApiImageModelId == model.id) activeLocalApiImageModelId = null
            check(localImageGenerationCoordinator.release(generationLease)) {
                "Local API image generation lease was replaced before request completion."
            }
        }
    }

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
        _uiState.update { state -> state.copy(localImageModels = models) }
    }

    private fun imageExtension(name: String): String {
        val extension = name.substringAfterLast('.', "png").lowercase()
        return when (extension) {
            "jpg", "jpeg", "png", "webp", "heic", "heif" -> extension
            else -> "png"
        }
    }

    private fun imageExtensionForMime(mimeType: String): String =
        when (mimeType.lowercase().substringBefore(";").trim()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/webp" -> "webp"
            "image/heic" -> "heic"
            "image/heif" -> "heif"
            else -> "png"
        }

    private data class CloudProviderPreset(
        val key: String,
        val providerName: String,
        val displayName: String,
        val format: CloudApiFormat,
        val baseUrl: String,
        val chatModel: String,
        val imageModel: String = "",
        val imageSize: String = "1024x1024"
    )

    private fun cloudProviderPreset(key: String): CloudProviderPreset? =
        cloudProviderPresets.firstOrNull { it.key == key }

    private val cloudProviderPresets = listOf(
        CloudProviderPreset("openai", "OpenAI-compatible", "自定义推理引擎", CloudApiFormat.OPENAI_COMPATIBLE, "", ""),
        CloudProviderPreset("anthropic", "Anthropic Messages", "自定义推理引擎", CloudApiFormat.ANTHROPIC, "", "")
    )

    private fun loadOrCreateApiKey(application: Application): String {
        val prefs = application.getSharedPreferences("mca_api", Context.MODE_PRIVATE)
        val existing = prefs.getString("api_key", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "mca-" + UUID.randomUUID().toString().replace("-", "").take(24)
        prefs.edit().putString("api_key", created).apply()
        return created
    }

    private fun loadApiPreferences(application: Application): LocalApiPreferences {
        val prefs = application.getSharedPreferences("mca_api", Context.MODE_PRIVATE)
        val apiEnabled = prefs.getBoolean("api_enabled", false)
        return LocalApiPreferences(
            apiEnabled = apiEnabled,
            restEnabled = apiEnabled && prefs.getBoolean("rest_enabled", false)
        )
    }

    private fun persistApiPreferences(apiEnabled: Boolean, restEnabled: Boolean) {
        getApplication<Application>()
            .getSharedPreferences("mca_api", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("api_enabled", apiEnabled)
            .putBoolean("rest_enabled", apiEnabled && restEnabled)
            .apply()
    }

    private fun loadGenerationParams(application: Application): GenerationParams {
        val prefs = application.getSharedPreferences("mca_generation_params", Context.MODE_PRIVATE)
        return restoreGenerationParams(
            semanticJson = prefs.getString("params_json", null),
            runtimeJson = prefs.getString("runtime_params_json", null)
        )
    }

    private fun persistGenerationParams(params: GenerationParams) {
        getApplication<Application>()
            .getSharedPreferences("mca_generation_params", Context.MODE_PRIVATE)
            .edit()
            .putString("params_json", params.toAssistantGenerationJson())
            .putString("runtime_params_json", runtimeParameterDocument(params).toString())
            .apply()
    }

    private fun loadRuntimeUserOverrideFields(application: Application): Set<String> {
        val raw = application.getSharedPreferences("mca_generation_params", Context.MODE_PRIVATE)
            .getString("runtime_user_fields", null)
            ?: return emptySet()
        return runCatching {
            val values = JSONArray(raw)
            buildSet {
                for (index in 0 until values.length()) {
                    values.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.getOrDefault(emptySet())
    }

    private fun persistRuntimeUserOverrideFields(fields: Set<String>) {
        getApplication<Application>()
            .getSharedPreferences("mca_generation_params", Context.MODE_PRIVATE)
            .edit()
            .putString("runtime_user_fields", JSONArray(fields.sorted()).toString())
            .apply()
    }

    private fun updatedAssistantsWithParams(params: GenerationParams): List<AssistantRecord> {
        val state = _uiState.value
        val selectedId = state.selectedAssistantId
        return state.assistants.map { assistant ->
            if (assistant.id == selectedId) {
                assistant.copy(
                    systemPrompt = params.systemPrompt,
                    paramsJson = params.toAssistantGenerationJson(),
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
                    _uiState.update { it.copy(statusMessage = "已切换助手：${assistant.name}，但该助手未绑定云端模型。") }
                    return
                }
                val model = _uiState.value.cloudModels.firstOrNull { it.id == modelId && it.kind == CloudModelKind.CHAT }
                if (model == null) {
                    _uiState.update { it.copy(statusMessage = "已切换助手：${assistant.name}，但绑定的云端模型已不可用。") }
                    return
                }
                selectCloudChatModel(model.id)
            }
            ASSISTANT_MODEL_MODE_LOCAL -> {
                if (modelId.isBlank()) {
                    _uiState.update { it.copy(statusMessage = "已切换助手：${assistant.name}，但该助手未绑定本地模型。") }
                    return
                }
                val model = _uiState.value.models.firstOrNull { it.id == modelId }
                if (model == null) {
                    _uiState.update { it.copy(statusMessage = "已切换助手：${assistant.name}，但绑定的本地模型已不可用。") }
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

    private fun MainUiState.selectedAssistant(): AssistantRecord? =
        assistants.firstOrNull { it.id == selectedAssistantId } ?: assistants.firstOrNull()

    private fun MainUiState.shouldUseWebSearchForTurn(plan: WebSearchPlan): Boolean =
        when {
            webSearchOneShotEnabled || webSearchTurnMode == WebSearchTurnMode.ON ->
                webSearchConfig.realSearchConfigured || (webSearchConfig.canReadDirectUrls && plan.directUrls.isNotEmpty())
            webSearchTurnMode == WebSearchTurnMode.OFF -> false
            selectedAssistant()?.webSearchEnabled == true ->
                webSearchConfig.realSearchConfigured || (webSearchConfig.canReadDirectUrls && plan.directUrls.isNotEmpty())
            else -> (webSearchConfig.realSearchConfigured || (webSearchConfig.canReadDirectUrls && plan.directUrls.isNotEmpty())) &&
                plan.shouldUseWebSearchAutomatically(webSearchConfig.triggerMode)
        }

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
            imageAttachments = attachments
        )
    }

    private fun mimeTypeForUri(uriString: String): String {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return "image/jpeg"
        return runCatching { getApplication<Application>().contentResolver.getType(uri) }
            .getOrNull()
            ?.takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: when (uriString.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                else -> "image/jpeg"
            }
    }

    private suspend fun List<ChatMessage>.withInlineImageDataForCloud(): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            map { message ->
                if (message.imageAttachments.isEmpty()) {
                    message
                } else {
                    message.copy(
                        imageAttachments = message.imageAttachments
                            .take(MAX_CHAT_IMAGES_PER_MESSAGE)
                            .map { attachment ->
                                if (attachment.hasInlineData) attachment else attachment.withCompressedInlineData()
                            }
                    )
                }
            }
        }

    private suspend fun List<ChatMessage>.withLocalImageFilesForVision(): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            map { message ->
                if (message.imageAttachments.isEmpty()) {
                    message
                } else {
                    message.copy(
                        imageAttachments = message.imageAttachments
                            .take(MAX_CHAT_IMAGES_PER_MESSAGE)
                            .map { it.withCompressedFileForLocalVision() }
                    )
                }
            }
        }

    private fun ChatImageAttachment.withCompressedFileForLocalVision(): ChatImageAttachment {
        val bitmap = decodeBitmapForLocalVision()
        val prepared = bitmap.scaledToMaxEdge(MAX_VISION_IMAGE_EDGE)
        val visionDir = File(getApplication<Application>().cacheDir, "vision_inputs").apply { mkdirs() }
        val outputFile = File(visionDir, "vision-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.jpg")
        outputFile.outputStream().use { output ->
            prepared.compress(Bitmap.CompressFormat.JPEG, 88, output)
        }
        if (prepared !== bitmap) bitmap.recycle()
        return copy(
            uriString = Uri.fromFile(outputFile).toString(),
            mimeType = "image/jpeg",
            dataBase64 = "",
            width = prepared.width,
            height = prepared.height,
            sizeBytes = outputFile.length()
        )
    }

    private fun ChatImageAttachment.decodeBitmapForLocalVision(): Bitmap {
        if (hasInlineData) {
            val bytes = runCatching {
                Base64.decode(plainBase64(), Base64.DEFAULT)
            }.getOrElse {
                error("无法解析内联图片：${name.ifBlank { "api-image" }}")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val sampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight, MAX_VISION_IMAGE_EDGE)
            return BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: error("无法读取内联图片：${name.ifBlank { "api-image" }}")
        }
        val uri = Uri.parse(uriString)
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight, MAX_VISION_IMAGE_EDGE)
        return resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: error("无法读取图片：${name.ifBlank { uriString }}")
    }

    private fun ChatImageAttachment.withCompressedInlineData(): ChatImageAttachment {
        val uri = Uri.parse(uriString)
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight, MAX_VISION_IMAGE_EDGE)
        val bitmap = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: error("无法读取图片：${name.ifBlank { uriString }}")
        val prepared = bitmap.scaledToMaxEdge(MAX_VISION_IMAGE_EDGE)
        val bytes = ByteArrayOutputStream().use { output ->
            prepared.compress(Bitmap.CompressFormat.JPEG, 86, output)
            output.toByteArray()
        }
        if (prepared !== bitmap) bitmap.recycle()
        return copy(
            mimeType = "image/jpeg",
            dataBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = prepared.width,
            height = prepared.height,
            sizeBytes = bytes.size.toLong()
        )
    }

    private fun Bitmap.scaledToMaxEdge(maxEdge: Int): Bitmap {
        val currentMax = maxOf(width, height)
        if (currentMax <= maxEdge) return this
        val scale = maxEdge.toFloat() / currentMax.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun calculateImageSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width / sample, height / sample) > maxEdge * 2) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun localVisionRunnerAvailable(): Boolean {
        val nativeVisionReady = runCatching {
            JSONObject(engine.nativeStatsJson()).optBoolean("visionReady", false)
        }.getOrDefault(false)
        val model = _uiState.value.models.firstOrNull { it.id == _uiState.value.loadedModelId }
        return model?.acceptsImageInput(nativeVisionReady) == true
    }

    private fun MainUiState.localVisionUnavailableMessage(): String {
        val model = models.firstOrNull { it.id == loadedModelId }
        return when {
            model == null ->
                "当前未加载本地模型。请在模型管理加载支持视觉的多模态 GGUF，或切换到支持图片输入的云端模型。"
            model.runtime == ChatModelRuntime.MNN ->
                "当前 MNN 模型没有就绪的视觉组件。请加载包含可读 visual.mnn 的完整多模态包后重试。"
            model.visionProjectorPath.isNullOrBlank() ->
                "当前本地模型未启用识图。纯文本 GGUF 不能直接看图，请在模型管理为多模态模型绑定匹配的 mmproj / projector 文件后重新加载。"
            else ->
                "已绑定视觉投影器，但本地视觉 runner 还未就绪。请重新加载当前模型；如果仍失败，请更换与主模型匹配的 mmproj / projector。"
        }
    }

    private fun currentOpenApiAddress(): String =
        apiUrl(detectLanIpAddress() ?: "本机局域网IP")

    private fun detectLanIpAddress(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    val host = address.hostAddress.orEmpty()
                    !address.isLoopbackAddress && !host.startsWith("127.")
                }
                ?.hostAddress
        }.getOrNull()
    }

    private fun <T> java.util.Enumeration<T>.asSequence(): Sequence<T> = sequence {
        while (hasMoreElements()) yield(nextElement())
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
    }

    private fun ModelManifest.toLoadParams(params: GenerationParams): LoadParams =
        LoadParams(
            nCtx = params.nCtx,
            nThreads = params.nThreads,
            advancedJson = params.advancedJson,
            visionProjectorPath = if (runtime == ChatModelRuntime.LLAMA_CPP) {
                visionProjectorPath
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { File(it).isFile }
            } else {
                null
            }
        )

    private fun ModelManifest.toLoadParams(
        params: GenerationParams,
        profile: ModelExecutionProfile
    ): LoadParams {
        val effective = profile.resolvedLoadBoundValues
            .plus(profile.hotExecutionValues)
            .plus(profile.modelBehaviorValues)
            .toJsonObject()
        return LoadParams.fromJson(effective.toString(), toLoadParams(params)).copy(
            visionProjectorPath = if (runtime == ChatModelRuntime.LLAMA_CPP) {
                visionProjectorPath
                    ?.takeIf { it.isNotBlank() }
                    ?.takeIf { File(it).isFile }
            } else {
                null
            }
        )
    }

    private fun mergeExecutionProfile(
        generation: GenerationParams,
        profile: ModelExecutionProfile
    ): GenerationParams = GenerationParams.fromJson(
        profile.resolvedLoadBoundValues
            .plus(profile.hotExecutionValues)
            .plus(profile.modelBehaviorValues)
            .toJsonObject(),
        generation
    )

    private fun mergeUserRequestedExecutionProfile(
        base: ModelExecutionProfile,
        requested: ModelExecutionProfile,
        authoritativeFields: Set<String>
    ): ModelExecutionProfile {
        require(base.runtimeIdentity.identityHash == requested.runtimeIdentity.identityHash) {
            "用户参数候选与当前运行身份不一致"
        }
        val selected = requested.userOverrides.intersect(authoritativeFields)
        if (selected.isEmpty()) return base
        val merged = requested.copy(
            desiredLoadBoundValues = base.desiredLoadBoundValues.plus(
                requested.desiredLoadBoundValues.only(selected)
            ),
            resolvedLoadBoundValues = base.resolvedLoadBoundValues.plus(
                requested.resolvedLoadBoundValues.only(selected)
            ),
            hotExecutionValues = base.hotExecutionValues.plus(
                requested.hotExecutionValues.only(selected)
            ),
            desiredHotExecutionValues = base.desiredHotExecutionValues.plus(
                requested.desiredHotExecutionValues.only(selected)
            ),
            modelBehaviorValues = base.modelBehaviorValues.plus(
                requested.modelBehaviorValues.only(selected)
            ),
            desiredModelBehaviorValues = base.desiredModelBehaviorValues.plus(
                requested.desiredModelBehaviorValues.only(selected)
            ),
            userOverrides = base.userOverrides + selected,
            quarantinedOverrides = (base.quarantinedOverrides + requested.quarantinedOverrides)
                .distinctBy { it.field }
        )
        return if (merged.resolvedLoadSignature.digest == base.resolvedLoadSignature.digest &&
            merged.committedExecutionSignature.digest == base.committedExecutionSignature.digest
        ) {
            base
        } else {
            merged
        }
    }

    private fun executionProfileDiffers(
        requested: GenerationParams,
        active: ModelExecutionProfile?
    ): Boolean {
        active ?: return false
        val requestedLoad = LoadParams(
            nCtx = requested.nCtx,
            nThreads = requested.nThreads,
            advancedJson = requested.advancedJson
        ).toJson().let(::JSONObject)
        val effective = active.resolvedLoadBoundValues
            .plus(active.hotExecutionValues)
            .plus(active.modelBehaviorValues)
            .toJsonObject()
        val governedFields = active.resolvedLoadBoundValues.fields +
            active.hotExecutionValues.fields + active.modelBehaviorValues.fields
        return governedFields.any { field ->
            requestedLoad.has(field) &&
                canonicalJsonValue(requestedLoad.opt(field)) != canonicalJsonValue(effective.opt(field))
        }
    }

    private fun canonicalJsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.toString()
        is Boolean -> value.toString()
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> value.toString().trim()
    }

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
                    model.id in currentQairtVerifiedLocalModelIds(modelStore.listModels())
            )
        val profile = activeProfile
            ?.takeIf { it.runtimeIdentity.identityHash == identity.identityHash }
        val started = System.currentTimeMillis()
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
        return System.currentTimeMillis() - started
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

    private fun tuningSummary(result: BenchmarkResult, recommendation: AgentRecommendation): String {
        val plan = recommendation.tuningPlan
        return buildString {
            append("decode=${"%.2f".format(result.decodeTps)} token/s")
            append("，TTFT=${result.ttftMs}ms")
            append("，n_ctx=${plan.nCtx}")
            append("，threads=${plan.nThreads}")
            append("，n_predict=${plan.nPredict}")
            if (result.bestThreadCount > 0) append("，最佳线程=${result.bestThreadCount}")
            if (result.error != null) append("，error=${result.error}")
        }
    }

    private fun buildDiagnosticReport(): String {
        val state = _uiState.value
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        val device = currentDeviceProfile()
        val nativeStats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("time", System.currentTimeMillis())
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

    private enum class AgentDebugMode(val label: String) {
        Quick("快速调试"),
        Standard("标准调试"),
        Deep("深度调试"),
        PowerSave("省电调试")
    }

    private fun AgentDebugMode.searchDepth(): TuningSearchDepth = when (this) {
        AgentDebugMode.Quick -> TuningSearchDepth.QUICK
        AgentDebugMode.Standard -> TuningSearchDepth.STANDARD
        AgentDebugMode.Deep -> TuningSearchDepth.DEEP
        AgentDebugMode.PowerSave -> TuningSearchDepth.POWER_SAVE
    }

    private fun AgentDebugMode.sweepConfig(): BenchmarkSweepConfig = when (this) {
        AgentDebugMode.Quick -> BenchmarkSweepConfig.quick()
        AgentDebugMode.Standard -> BenchmarkSweepConfig.quick()
        AgentDebugMode.Deep -> BenchmarkSweepConfig.deep()
        AgentDebugMode.PowerSave -> BenchmarkSweepConfig.powerSave()
    }

    private fun threadSweepCandidates(
        device: DeviceProfile,
        recommendedThreads: Int?,
        debugMode: AgentDebugMode
    ): List<Int> {
        val cores = device.cpuCores.coerceAtLeast(1)
        val bigCores = device.estimatedBigCores.coerceIn(1, cores)
        val quickCandidates = TuningCandidatePolicy.quickThreadCandidates(
            cpuCores = cores,
            estimatedBigCores = bigCores,
            recommendedThreads = recommendedThreads
        )
        val standardCandidates = quickCandidates + listOf(
            (recommendedThreads ?: bigCores) - 2,
            (recommendedThreads ?: bigCores) + 2,
            bigCores - 2,
            bigCores + 2
        ) + (1..cores)
        val base = when (debugMode) {
            AgentDebugMode.Quick -> quickCandidates
            AgentDebugMode.Standard -> standardCandidates
            AgentDebugMode.Deep -> listOf(
                recommendedThreads ?: bigCores,
                bigCores,
                bigCores - 1,
                bigCores + 1
            ) + (1..cores)
            AgentDebugMode.PowerSave -> listOf(
                recommendedThreads?.coerceAtMost(4) ?: bigCores.coerceAtMost(4),
                bigCores.coerceAtMost(4),
                2,
                1,
                3,
                4
            )
        }
        return base
            .map { it.coerceIn(1, cores) }
            .distinct()
            .filter { debugMode != AgentDebugMode.PowerSave || it <= 4 }
            .take(
                when (debugMode) {
                    AgentDebugMode.Quick -> TuningCandidatePolicy.QUICK_MAX_CANDIDATES
                    AgentDebugMode.Standard -> 6
                    AgentDebugMode.Deep -> 12
                    AgentDebugMode.PowerSave -> 4
                }
            )
    }

    private fun AgentRecommendation.withBenchmarkThread(result: BenchmarkResult): AgentRecommendation {
        val bestThreads = result.bestThreadCount.takeIf { it > 0 } ?: return this
        if (bestThreads == tuningPlan.nThreads) return this
        val updatedPlan = tuningPlan.copy(
            nThreads = bestThreads,
            reason = listOf(tuningPlan.reason, "短基准线程扫描选择 ${bestThreads} 线程。")
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        return copy(
            tuningPlan = updatedPlan,
            explanation = "$explanation 线程扫描显示 ${bestThreads} 线程最快，已按实测结果调整。"
        )
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
                _uiState.update { state ->
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
            _uiState.update { state ->
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
        val transactionId = "bootstrap-${profile.runtimeIdentity.identityHash.take(24)}-${profile.revision}"
        val snapshot = profile.toPersistedExecutionProfileSnapshot(
            parentCommittedProfileId = null,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            sourceSummaryJson = sourceSummary
        )
        val actualTransaction = transactionId
        runtimeProfileStore.stageCandidate(
            snapshot = snapshot,
            transactionId = actualTransaction,
            rollbackTargetProfileId = null
        )
        runtimeProfileStore.updateJournalStage(actualTransaction, TuningJournalState.VALIDATING, "BOOTSTRAP_VALIDATING")
        val signatures = engine.parameterSignatureSnapshot()
            ?: error("安全基线缺少参数签名快照")
        runtimeProfileStore.commitCandidate(
            transactionId = actualTransaction,
            verificationLevel = PersistedProfileVerificationLevel.SAFE,
            activeLoadedSignature = signatures.active?.digest ?: signatures.resolved.digest,
            effectiveExecutionSignature = signatures.effective?.digest ?: signatures.committed.digest
        )
    }

    private fun agentVerification(value: String): AgentProfileVerification = when {
        value.equals(PersistedProfileVerificationLevel.DEVICE_VERIFIED.name, ignoreCase = true) ->
            AgentProfileVerification.DEVICE_VERIFIED
        value.equals(PersistedProfileVerificationLevel.COMPATIBLE.name, ignoreCase = true) ->
            AgentProfileVerification.COMPATIBLE
        value.equals(PersistedProfileVerificationLevel.SAFE.name, ignoreCase = true) ->
            AgentProfileVerification.SAFE
        else -> AgentProfileVerification.UNKNOWN
    }

    private suspend fun runBootstrapCorrectnessCanary(params: GenerationParams): BootstrapCanaryResult {
        val canaryParams = params.copy(
            nPredict = minOf(params.nPredict.coerceAtLeast(32), 96),
            temperature = 0.0f,
            topK = 1,
            topP = 1.0f,
            minP = 0.0f,
            repeatPenalty = 1.0f,
            presencePenalty = 0.0f,
            frequencyPenalty = 0.0f,
            reasoningMode = ReasoningMode.OFF,
            hideReasoning = true,
            // Some valid compact MNN exporters expose a user/assistant prompt
            // template but no system-role slot. Bootstrap proves the real chat
            // path without injecting an unsupported role; candidate tuning
            // still uses the richer strict correctness suite below.
            systemPrompt = ""
        )
        val output = StringBuilder()
        var stats = RuntimeStats()
        var errorMessage: String? = null
        withTimeout(120_000L) {
            engine.streamChat(
                ChatRequest(
                    messages = listOf(
                        ChatMessage(
                            Role.USER,
                            BootstrapLoadCanaryPolicy.prompt
                        )
                    ),
                    params = canaryParams
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> {
                        output.append(event.text)
                        stats = event.stats
                    }
                    is GenerateEvent.Done -> stats = event.stats
                    is GenerateEvent.Error -> {
                        errorMessage = event.message
                        stats = event.stats
                    }
                }
            }
        }
        val text = output.toString()
        val passed = errorMessage == null && BootstrapLoadCanaryPolicy.matches(text)
        return BootstrapCanaryResult(
            passed = passed,
            detail = errorMessage ?: if (passed) {
                "bootstrap-load-v1 通过（${text.length} 字，decode ${"%.2f".format(stats.decodeTps)} token/s）"
            } else {
                "输出未满足 bootstrap-load-v1 最小生成契约：${text.take(160)}"
            },
            stats = stats
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
        return requireNotNull(workerResult).toCandidateCanaryResult(candidate)
    }

    private fun failedIsolatedTuningCandidateResult(
        candidate: TuningExecutionProfile,
        start: MeasurementPoint,
        error: Throwable
    ): CandidateCanaryResult {
        val message = error.message?.take(1_024) ?: error::class.java.simpleName
        val normalized = message.lowercase()
        val remoteCode = (error as? TuningProbeWorkerRemoteException)?.code.orEmpty().lowercase()
        val timedOut = error is TimeoutCancellationException ||
            "timeout" in remoteCode || "timed out" in normalized || "watchdog" in normalized ||
            "hang" in normalized
        val nativeFatal = "native_fatal" in remoteCode || "fatal signal" in normalized ||
            "sigsegv" in normalized || "sigabrt" in normalized
        val workerDied = error is TuningProbeWorkerException &&
            error !is TuningProbeWorkerRemoteException &&
            ("crash" in normalized || "exited" in normalized || "disconnected" in normalized ||
                "binding died" in normalized || "watchdog" in normalized)
        val measuredEnd = measurementPoint()
        val measurement = MeasurementEnvelope(
            start = start,
            end = measuredEnd,
            samples = listOf(
                PerformanceSample(
                    ttftMs = 0L,
                    decodeTps = 0.0,
                    pssBytes = measuredEnd.pssBytes,
                    rssBytes = measuredEnd.rssBytes,
                    availableMemoryBytes = measuredEnd.availableMemoryBytes
                )
            )
        )
        return CandidateCanaryResult(
            passed = false,
            output = "",
            stats = RuntimeStats(
                loaded = false,
                backend = candidate.runtimeIdentity.runtime.backendId,
                isLowMemory = start.lowMemoryTriggered || measuredEnd.lowMemoryTriggered,
                lastError = message
            ),
            detail = "isolated candidate rejected: $message",
            measurement = measurement,
            safetyPassed = SafetyEnvelope.forDevice(currentDeviceProfile()).assess(measurement).passed,
            signatureVerification = null,
            crashCount = if (workerDied) 1 else 0,
            anrCount = if (timedOut) 1 else 0,
            nativeFatalSignalCount = if (nativeFatal) 1 else 0,
            testedProfileId = candidate.profileId,
            testedResolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
            testedCommittedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
            evidenceJson = JSONObject()
                .put("environment", "isolated_process")
                .put("outcome", "worker_failure")
                .put("errorClass", error::class.java.name)
                .put("remoteCode", remoteCode.takeIf(String::isNotBlank))
                .put("message", message)
                .put("crashCount", if (workerDied) 1 else 0)
                .put("anrCount", if (timedOut) 1 else 0)
                .put("nativeFatalSignalCount", if (nativeFatal) 1 else 0)
                .toString()
        )
    }

    private fun TuningProbeWorkerProtocol.Result.toCandidateCanaryResult(
        candidate: TuningExecutionProfile
    ): CandidateCanaryResult {
        val statsRoot = JSONObject(runtimeStatsJson)
        val stats = RuntimeStats(
            loaded = statsRoot.optBoolean("loaded"),
            backend = statsRoot.optString("backend").ifBlank { candidate.runtimeIdentity.runtime.backendId },
            loadMs = statsRoot.optLong("loadMs").coerceAtLeast(0L),
            promptTokens = statsRoot.optInt("promptTokens").coerceAtLeast(0),
            completionTokens = statsRoot.optInt("completionTokens").coerceAtLeast(0),
            ttftMs = statsRoot.optLong("ttftMs").coerceAtLeast(0L),
            prefillMs = statsRoot.optLong("prefillMs").coerceAtLeast(0L),
            decodeMs = statsRoot.optLong("decodeMs").coerceAtLeast(0L),
            decodeTps = statsRoot.optionalFiniteDouble("decodeTps"),
            e2eTps = statsRoot.optionalFiniteDouble("e2eTps"),
            nativePssKb = statsRoot.optLong("nativePssKb").coerceAtLeast(0L),
            processRssKb = statsRoot.optLong("processRssKb").coerceAtLeast(0L),
            availMemKb = statsRoot.optLong("availMemKb").coerceAtLeast(0L),
            totalMemKb = statsRoot.optLong("totalMemKb").coerceAtLeast(0L),
            modelMemoryBudgetKb = statsRoot.optLong("modelMemoryBudgetKb").coerceAtLeast(0L),
            nThreads = statsRoot.optInt("nThreads").coerceAtLeast(0),
            nThreadsBatch = statsRoot.optInt("nThreadsBatch").coerceAtLeast(0),
            nBatch = statsRoot.optInt("nBatch").coerceAtLeast(0),
            nUbatch = statsRoot.optInt("nUbatch").coerceAtLeast(0),
            nCtx = statsRoot.optInt("nCtx").coerceAtLeast(0),
            maxAllTokens = statsRoot.optInt("maxAllTokens").coerceAtLeast(0),
            maxNewTokens = statsRoot.optInt("maxNewTokens").coerceAtLeast(0),
            isLowMemory = statsRoot.optBoolean("isLowMemory") || lowMemoryTriggered,
            lastError = statsRoot.optString("lastError").takeIf(String::isNotBlank)
        )
        val device = currentDeviceProfile()
        val start = MeasurementPoint(
            thermalStatus = device.thermalStatus,
            batteryPercent = device.batteryPercent,
            isCharging = device.isCharging,
            availableMemoryBytes = startAvailableMemoryBytes,
            pssBytes = startPssBytes,
            rssBytes = startRssBytes,
            lowMemoryTriggered = lowMemoryTriggered,
            appInForeground = true
        )
        val end = start.copy(
            timeMs = start.timeMs + elapsedMs.coerceAtLeast(0L),
            availableMemoryBytes = endAvailableMemoryBytes,
            pssBytes = endPssBytes,
            rssBytes = endRssBytes,
            lowMemoryTriggered = lowMemoryTriggered || stats.isLowMemory
        )
        val decodeTps = stats.decodeTps.takeIf { it.isFinite() && it >= 0.0 }
            ?: stats.e2eTps.coerceAtLeast(0.0)
        val measurement = MeasurementEnvelope(
            start = start,
            end = end,
            samples = listOf(
                PerformanceSample(
                    ttftMs = stats.ttftMs,
                    decodeTps = decodeTps,
                    pssBytes = end.pssBytes,
                    rssBytes = end.rssBytes,
                    availableMemoryBytes = end.availableMemoryBytes
                )
            )
        )
        val expected = candidate.expectedSignatures()
        val signatures = ParameterSignatureSnapshot(
            desired = candidate.engineProfile.desiredSignature,
            resolved = candidate.engineProfile.resolvedLoadSignature,
            active = expected.activeLoaded,
            committed = candidate.engineProfile.committedExecutionSignature,
            override = RuntimeOverrideSignature.none(candidate.runtimeIdentity),
            effective = expected.effectiveExecution
        )
        val verification = AuthorizedPendingSignatureVerification(
            transactionId = transactionId,
            profileId = profileId,
            revision = candidate.revision,
            signatures = signatures,
            strictlyMatches = signatureMatched
        )
        val safetyPassed = !lowMemoryTriggered && SafetyEnvelope.forDevice(device).assess(measurement).passed
        return CandidateCanaryResult(
            passed = passed,
            output = output,
            stats = stats,
            detail = detail,
            measurement = measurement,
            safetyPassed = safetyPassed,
            signatureVerification = verification,
            testedProfileId = profileId,
            testedResolvedLoadSignature = resolvedLoadSignature,
            testedCommittedExecutionSignature = committedExecutionSignature,
            evidenceJson = evidenceJson
        )
    }

    private fun JSONObject.optionalFiniteDouble(field: String): Double {
        if (!has(field) || isNull(field)) return 0.0
        return optDouble(field, 0.0).takeIf(Double::isFinite) ?: 0.0
    }

    private suspend fun runCandidateCanary(
        candidate: TuningExecutionProfile,
        canaryParams: com.muyuchat.core.tuning.CanaryEvaluationParams,
        authorization: com.muyuchat.core.engine.LoadAuthorization,
        disposition: com.muyuchat.core.engine.PendingProfileDisposition =
            com.muyuchat.core.engine.PendingProfileDisposition.ROLLBACK_AFTER_REQUEST,
        lifecycleLease: com.muyuchat.core.engine.EngineLifecycleLease? = null
    ): CandidateCanaryResult {
        val startPoint = measurementPoint()
        val output = StringBuilder()
        var stats = RuntimeStats()
        var errorMessage: String? = null
        val request = ChatRequest(
            messages = listOf(
                ChatMessage(
                    Role.USER,
                    MinimumTextCanaryPolicy.prompt
                )
            ),
            params = canaryParams.toGenerationParams(candidate)
        )
        withTimeout(120_000L) {
            engine.streamChat(
                request,
                com.muyuchat.core.engine.LocalChatExecutionContext(
                    requestId = "tuning-${UUID.randomUUID()}",
                    loadAuthorization = authorization,
                    pendingProfileDisposition = disposition,
                    lifecycleLease = lifecycleLease
                )
            ).collect { event ->
                when (event) {
                    is GenerateEvent.Chunk -> {
                        output.append(event.text)
                        stats = event.stats
                    }
                    is GenerateEvent.Done -> stats = event.stats
                    is GenerateEvent.Error -> {
                        errorMessage = event.message
                        stats = event.stats
                    }
                }
            }
        }
        val text = output.toString()
        val passed = errorMessage == null && MinimumTextCanaryPolicy.matches(text)
        val signatureVerification = engine.authorizedPendingSignatureVerification(authorization)
        val measuredEnd = measurementPoint()
        val endPoint = measuredEnd.copy(
            availableMemoryBytes = stats.availMemKb
                .takeIf { it > 0L }
                ?.times(1024L)
                ?: measuredEnd.availableMemoryBytes,
            pssBytes = stats.nativePssKb.coerceAtLeast(0L) * 1024L,
            rssBytes = stats.processRssKb.coerceAtLeast(0L) * 1024L,
            lowMemoryTriggered = measuredEnd.lowMemoryTriggered || stats.isLowMemory
        )
        val decodeTps = stats.decodeTps
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: stats.e2eTps.coerceAtLeast(0.0)
        val measurement = MeasurementEnvelope(
            start = startPoint,
            end = endPoint,
            samples = listOf(
                PerformanceSample(
                    ttftMs = stats.ttftMs.coerceAtLeast(0L),
                    decodeTps = decodeTps,
                    pssBytes = endPoint.pssBytes,
                    rssBytes = endPoint.rssBytes,
                    availableMemoryBytes = endPoint.availableMemoryBytes
                )
            )
        )
        val safetyPassed = SafetyEnvelope.forDevice(currentDeviceProfile()).assess(measurement).passed
        return CandidateCanaryResult(
            passed = passed,
            output = text,
            stats = stats,
            detail = errorMessage ?: if (passed) "candidate correctness passed" else "candidate output failed correctness",
            measurement = measurement,
            safetyPassed = safetyPassed,
            signatureVerification = signatureVerification,
            testedProfileId = candidate.profileId,
            testedResolvedLoadSignature = candidate.engineProfile.resolvedLoadSignature.digest,
            testedCommittedExecutionSignature = candidate.engineProfile.committedExecutionSignature.digest,
            evidenceJson = JSONObject()
                .put("environment", "caller_process")
                .put("profileId", candidate.profileId)
                .put("resolvedLoadSignature", candidate.engineProfile.resolvedLoadSignature.digest)
                .put("committedExecutionSignature", candidate.engineProfile.committedExecutionSignature.digest)
                .toString()
        )
    }

    private fun tuningCandidateHardGate(result: CandidateCanaryResult): CandidateHardGate {
        return CandidateHardGate(
            correctnessPassed = result.passed && result.stats.lastError == null,
            crashCount = result.crashCount,
            anrCount = result.anrCount,
            nativeFatalSignalCount = result.nativeFatalSignalCount,
            lowMemoryTriggered = result.stats.isLowMemory ||
                result.measurement.start.lowMemoryTriggered ||
                result.measurement.end.lowMemoryTriggered,
            outputVisible = result.output.isNotBlank(),
            templateValid = result.passed,
            safetyPassed = result.safetyPassed,
            signaturesMatch = result.signatureVerification?.strictlyMatches == true
        )
    }

    private fun tuningCandidateScore(result: CandidateCanaryResult): CandidateScore =
        CandidateScorer.score(tuningCandidateHardGate(result), result.measurement)

    private fun candidateMeasurement(
        profileId: String,
        jobId: String?,
        phase: String,
        result: CandidateCanaryResult
    ): CandidateMeasurementEntity {
        val gate = tuningCandidateHardGate(result)
        val score = CandidateScorer.score(gate, result.measurement)
        val failureCode = when {
            score.eligible -> null
            gate.nativeFatalSignalCount > 0 -> "NATIVE_FATAL_SIGNAL"
            gate.crashCount > 0 -> "WORKER_CRASH"
            gate.anrCount > 0 -> "WORKER_TIMEOUT"
            result.stats.lastError != null -> "RUNTIME_ERROR"
            !gate.correctnessPassed || !gate.templateValid || !gate.outputVisible -> "CORRECTNESS_GATE_FAILED"
            !gate.safetyPassed -> "SAFETY_GATE_FAILED"
            gate.lowMemoryTriggered -> "LOW_MEMORY"
            !gate.signaturesMatch -> "SIGNATURE_MISMATCH"
            else -> "CANDIDATE_REJECTED"
        }
        val metrics = JSONObject()
            .put("phase", phase)
            .put("eligible", score.eligible)
            .put("score", score.value)
            .put("correctnessPassed", gate.correctnessPassed)
            .put("templateValid", gate.templateValid)
            .put("outputVisible", gate.outputVisible)
            .put("safetyPassed", gate.safetyPassed)
            .put("signatureStrictlyMatched", gate.signaturesMatch)
            .put("lowMemoryTriggered", gate.lowMemoryTriggered)
            .put("crashCount", gate.crashCount)
            .put("anrCount", gate.anrCount)
            .put("nativeFatalSignalCount", gate.nativeFatalSignalCount)
            .put("ttftMs", result.stats.ttftMs.coerceAtLeast(0L))
            .put("decodeMs", result.stats.decodeMs.coerceAtLeast(0L))
            .put("decodeTps", result.stats.decodeTps.takeIf { it.isFinite() })
            .put("completionTokens", result.stats.completionTokens.coerceAtLeast(0))
            .put("nativePssKb", result.stats.nativePssKb.coerceAtLeast(0L))
            .put("processRssKb", result.stats.processRssKb.coerceAtLeast(0L))
            .put("minimumAvailableMemoryBytes", result.measurement.minimumAvailableMemoryBytes.coerceAtLeast(0L))
            .put("testedProfileId", result.testedProfileId)
            .put("testedResolvedLoadSignature", result.testedResolvedLoadSignature)
            .put("testedCommittedExecutionSignature", result.testedCommittedExecutionSignature)
            .put(
                "isolatedEvidence",
                runCatching { JSONObject(result.evidenceJson) }.getOrElse { JSONObject() }
            )
            .put("signatures", JSONObject().apply {
                result.signatureVerification?.signatures?.let { signatures ->
                    put("desired", signatures.desired.digest)
                    put("resolvedLoad", signatures.resolved.digest)
                    put("activeLoaded", signatures.active?.digest)
                    put("committedExecution", signatures.committed.digest)
                    put("runtimeOverride", signatures.override.digest)
                    put("effectiveExecution", signatures.effective?.digest)
                }
            })
        return CandidateMeasurementEntity(
            measurementId = "measurement-${UUID.randomUUID()}",
            profileId = profileId,
            jobId = jobId,
            correctnessPassed = gate.correctnessPassed,
            safetyPassed = gate.safetyPassed,
            effectiveSignatureMatched = gate.signaturesMatch,
            accepted = score.eligible,
            metricsJson = metrics.toString(),
            failureCode = failureCode,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun ModelExecutionProfile.asTuningExecutionProfile(
        kind: ExecutionProfileKind,
        verificationLevel: ProfileVerificationLevel,
        reason: String
    ): TuningExecutionProfile {
        fun intValue(field: String): Int? =
            (resolvedLoadBoundValues.value(field) as? Number)?.toInt()
                ?: (hotExecutionValues.value(field) as? Number)?.toInt()
        fun stringValue(field: String): String? =
            resolvedLoadBoundValues.value(field)?.toString()
        fun boolValue(field: String): Boolean? =
            resolvedLoadBoundValues.value(field) as? Boolean
        return TuningExecutionProfile(
            engineProfile = this,
            kind = kind,
            loadBound = LoadBoundExecutionParams(
                nCtx = intValue("n_ctx") ?: _uiState.value.params.nCtx,
                nBatch = intValue("n_batch"),
                nUbatch = intValue("n_ubatch"),
                cacheTypeK = stringValue("cache_type_k"),
                cacheTypeV = stringValue("cache_type_v"),
                flashAttention = stringValue("flash_attn"),
                gpuLayers = intValue("n_gpu_layers"),
                mainGpu = intValue("main_gpu"),
                cpuMoeLayers = intValue("n_cpu_moe"),
                speculativeType = stringValue("spec_type"),
                speculativeDraftMax = intValue("spec_draft_n_max"),
                nParallel = intValue("n_parallel") ?: 1,
                mmap = boolValue("mmap") ?: true,
                mlock = boolValue("mlock") ?: false,
                backend = stringValue("backend") ?: runtimeIdentity.runtime.backendId
            ),
            hotExecution = HotExecutionParams(
                nThreads = intValue("n_threads") ?: _uiState.value.params.nThreads,
                nThreadsBatch = intValue("n_threads_batch")
            ),
            verificationLevel = verificationLevel,
            reason = reason
        )
    }

    private fun measurementPoint(device: DeviceProfile = currentDeviceProfile()): MeasurementPoint {
        val stats = _uiState.value.stats
        return MeasurementPoint(
            thermalStatus = device.thermalStatus,
            batteryPercent = device.batteryPercent,
            isCharging = device.isCharging,
            availableMemoryBytes = device.availableRamBytes,
            pssBytes = stats.nativePssKb.coerceAtLeast(0L) * 1024L,
            rssBytes = stats.processRssKb.coerceAtLeast(0L) * 1024L,
            lowMemoryTriggered = device.isLowMemory || stats.isLowMemory,
            appInForeground = _uiState.value.engineLifecycle != AgentEngineLifecycle.STOPPING
        )
    }

    private fun runtimeProfileJson(): String {
        val state = _uiState.value
        val runtimeLoaded = state.loadedModelId != null && engine.stats.value.loaded
        val profile = engine.activeExecutionProfile().takeIf { runtimeLoaded }
        val signatures = engine.parameterSignatureSnapshot().takeIf { runtimeLoaded }
        return JSONObject()
            .put("modelId", state.loadedModelId)
            .put("modelName", state.loadedModelName)
            .put("identityHash", activeRuntimeIdentity?.identityHash.takeIf { runtimeLoaded })
            .put("profileId", if (runtimeLoaded) state.profileId ?: profile?.profileId else null)
            .put("revision", if (runtimeLoaded) state.revision ?: profile?.revision else null)
            .put("recordState", state.profileRecordState.name.lowercase())
            .put("verification", state.verification.name.lowercase())
            .put("engineLifecycle", state.engineLifecycle.name.lowercase())
            .put("reloadRequired", state.reloadRequired)
            .put("signatures", JSONObject().apply {
                signatures?.let { value ->
                    put("desired", value.desired.digest)
                    put("resolvedLoad", value.resolved.digest)
                    put("activeLoaded", value.active?.digest)
                    put("committedExecution", value.committed.digest)
                    put("runtimeOverride", value.override.digest)
                    put("effectiveExecution", value.effective?.digest)
                }
            })
            .put("pending", state.pendingProfile?.let { pending ->
                JSONObject()
                    .put("profileId", pending.profileId)
                    .put("revision", pending.revision)
                    .put("summary", pending.summary)
                    .put("readyToApply", pending.readyToApply)
            } ?: JSONObject.NULL)
            .put("rollback", state.rollbackProfile?.let { rollback ->
                JSONObject()
                    .put("profileId", rollback.targetProfileId)
                    .put("revision", rollback.targetRevision)
                    .put("summary", rollback.summary)
                    .put("available", rollback.available)
            } ?: JSONObject.NULL)
            .toString()
    }

    private fun apiGenerationParams(): GenerationParams {
        val state = _uiState.value
        val active = engine.activeExecutionProfile()
            .takeIf { state.loadedModelId != null && engine.stats.value.loaded }
        return if (active == null) state.params else mergeExecutionProfile(state.params, active)
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
                requested == model.displayName
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

    private fun localApiIdempotencyKeyHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(Locale.ROOT, byte.toInt() and 0xff) }

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

    private fun localApiDebugMode(value: String): AgentDebugMode? = when (
        value.trim().lowercase(Locale.ROOT).replace("-", "_")
    ) {
        "quick" -> AgentDebugMode.Quick
        "standard" -> AgentDebugMode.Standard
        "deep", "full" -> AgentDebugMode.Deep
        "power", "power_save", "powersave" -> AgentDebugMode.PowerSave
        else -> null
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
                _uiState.update { it.copy(pendingProfile = null) }
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

    private fun tuningStateJson(): String {
        val state = _uiState.value
        return JSONObject()
            .put("jobId", activeTuningJobId)
            .put("state", state.tuningJobState.name.lowercase())
            .put("phase", state.tuningPhase)
            .put("etaSeconds", state.tuningEtaSeconds)
            .put("autoTuningInProgress", state.autoTuningInProgress)
            .put("cancelRequested", adaptiveTuningCancelRequested.get())
            .put("pauseRequested", adaptiveTuningPauseRequested.get())
            .put("progress", JSONObject()
                .put("completed", state.tuningCandidateProgress.completed)
                .put("total", state.tuningCandidateProgress.total)
                .put("currentCandidate", state.tuningCandidateProgress.currentCandidate)
                .put("passed", state.tuningCandidateProgress.passed)
                .put("rejected", state.tuningCandidateProgress.rejected))
            .put("profile", JSONObject(runtimeProfileJson()))
            .toString()
    }

    private fun runTuningControl(
        jobId: String,
        action: String,
        operation: suspend () -> TuningJobEntity
    ): com.muyuchat.api.local.LocalApiControlResult = runCatching {
        val job = runBlocking(Dispatchers.IO) { operation() }
        _uiState.update { state ->
            state.copy(
                tuningJobState = agentJobState(job),
                tuningPhase = job.phase,
                statusMessage = "Local API 已请求调优任务 ${action}。"
            )
        }
        com.muyuchat.api.local.LocalApiControlResult.Success(tuningStateJson())
    }.getOrElse { error ->
        com.muyuchat.api.local.LocalApiControlResult.Rejected(
            httpStatus = 409,
            code = "tuning_${action}_rejected",
            message = error.message ?: "调优任务操作失败"
        )
    }

    private fun loadedModelJson(): String {
        val state = _uiState.value
        val loadedModel = state.models.firstOrNull { it.id == state.loadedModelId }
        val nativeStats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrElse { JSONObject() }
        val runtimeLoaded = state.loadedModelId != null && engine.stats.value.loaded
        val visionReady = runtimeLoaded && nativeStats.optBoolean("visionReady", false)
        // Backward-compatible API alias. Product admission is based on native
        // visual readiness across all compatible devices.
        val visionValidated = visionReady
        return JSONObject()
            .put("id", state.loadedModelId)
            .put("name", state.loadedModelName)
            .put("displayName", state.loadedModelName)
            .put("runtime", loadedModel?.runtime?.storageValue)
            .put("vision_ready", visionReady)
            .put("vision_validated", visionValidated)
            .put("visionValidated", visionValidated)
            .put("vision_projector", loadedModel?.visionProjectorFileName)
            .put("stats", nativeStats)
            .toString()
    }

    private fun modelsJson(): String {
        val array = JSONArray()
        val state = _uiState.value
        val nativeStats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrElse { JSONObject() }
        state.models.forEach { model ->
            val selected = engine.stats.value.loaded && model.id == state.loadedModelId
            val visionReady = selected && nativeStats.optBoolean("visionReady", false)
            val visionValidated = visionReady
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("object", "model")
                    .put("owned_by", "local")
                    .put("display_name", model.displayName)
                    .put("runtime", model.runtime.storageValue)
                    .put("vision_projector", model.visionProjectorFileName)
                    .put("vision_ready", visionReady)
                    .put("vision_validated", visionValidated)
            )
        }
        return JSONObject().put("object", "list").put("data", array).toString()
    }

    private fun modelRuntimeStatesJson(): String {
        val models = _uiState.value.models
        val states = runBlocking(Dispatchers.IO) {
            runtimeProfileStore.modelRuntimeSummaries(models.map { it.id })
        }
        val root = JSONObject()
        states.forEach { runtimeState ->
            val modelId = runtimeState.modelId
            val profile = runtimeState.activeProfile
            val activeJob = runtimeState.activeJob
            val isLoaded = modelId == _uiState.value.loadedModelId
            root.put(
                modelId,
                JSONObject()
                    .put("profileId", profile?.profileId)
                    .put("profileRecordState", profile?.recordState?.lowercase(Locale.ROOT) ?: "none")
                    .put(
                        "profileVerificationLevel",
                        profile?.verificationLevel?.lowercase(Locale.ROOT) ?: "unknown"
                    )
                    .put("reloadRequired", runtimeState.pendingProfile != null)
                    .put(
                        "engineLifecycle",
                        if (isLoaded) _uiState.value.engineLifecycle.name.lowercase(Locale.ROOT) else "unloaded"
                    )
                    .put("tuningJobState", activeJob?.state?.lowercase(Locale.ROOT) ?: "idle")
            )
        }
        return root.toString()
    }

    private fun currentDeviceProfile(): DeviceProfile =
        deviceProfileReader.read()

    private fun preferredQairtChipsets(device: DeviceProfile): List<String> =
        when (device.accelerationProfile.chipsetCode.trim().uppercase(Locale.US)) {
            "SM8850", "SM8850P" -> listOf(
                "qualcomm-snapdragon-8-elite-gen5",
                "qualcomm-snapdragon-8-elite"
            )
            "SM8750", "SM8750P" -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            "SM8650", "SM8650P" -> listOf(
                "qualcomm-snapdragon-8gen3",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            "SM8550", "SM8550P" -> listOf(
                "qualcomm-snapdragon-8gen2",
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
            // Unknown/future devices must never produce an empty admission
            // list. The repository client will prefer an exact key when one
            // exists and otherwise choose a deterministic published fallback.
            else -> listOf(
                "qualcomm-snapdragon-8-elite",
                "qualcomm-snapdragon-8-elite-gen5"
            )
        }

    private fun clearBundleDirectoryExcept(bundleDir: File, keepFile: File) {
        val root = runCatching { bundleDir.canonicalFile }.getOrNull() ?: return
        val keep = runCatching { keepFile.canonicalFile }.getOrNull()
        bundleDir.listFiles()?.forEach { child ->
            val candidate = runCatching { child.canonicalFile }.getOrNull() ?: return@forEach
            if (keep != null && candidate.absolutePath == keep.absolutePath) return@forEach
            if (candidate.absolutePath.startsWith(root.absolutePath + File.separator)) {
                child.deleteRecursively()
            }
        }
    }

    private fun promoteImageBundleCandidate(candidateDir: File, bundleDir: File): File? {
        val candidate = candidateDir.canonicalFile
        val destination = bundleDir.canonicalFile
        val parent = destination.parentFile ?: throw IOException("生图引擎包目录没有父目录：$destination")
        val backup = File(parent, ".${destination.name}.backup").canonicalFile
        require(candidate.isDirectory) { "生图引擎候选目录不存在：$candidate" }
        if (backup.exists() && !backup.deleteRecursively()) {
            throw IOException("无法清理旧的生图引擎备份：$backup")
        }

        val hadExistingBundle = destination.exists()
        if (hadExistingBundle && !destination.renameTo(backup)) {
            throw IOException("无法备份现有生图引擎包：$destination")
        }
        if (!candidate.renameTo(destination)) {
            if (hadExistingBundle && !destination.exists() && !backup.renameTo(destination)) {
                throw IOException("无法提交新的生图引擎包，也无法恢复旧包：$backup")
            }
            throw IOException("无法提交生图引擎候选包：$candidate")
        }
        return backup.takeIf { hadExistingBundle }
    }

    private fun restoreImageBundleBackup(bundleDir: File, backup: File?) {
        if (bundleDir.exists() && !bundleDir.deleteRecursively()) {
            throw IOException("无法移除失败的生图引擎包：$bundleDir")
        }
        if (backup != null && backup.exists() && !backup.renameTo(bundleDir)) {
            throw IOException("无法恢复先前的生图引擎包：$backup")
        }
    }

    private fun unzipIntoDirectory(zipFile: File, targetDir: File) {
        require(zipFile.isFile && zipFile.length() > 0L) { "QAIRT zip 包为空或不存在：${zipFile.name}" }
        targetDir.mkdirs()
        val root = targetDir.canonicalFile
        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name.replace('\\', '/').trimStart('/')
                if (entryName.isBlank()) {
                    zip.closeEntry()
                    continue
                }
                val target = File(root, entryName).canonicalFile
                require(target.absolutePath.startsWith(root.absolutePath + File.separator)) {
                    "QAIRT zip 包包含不安全路径：${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
            }
        }
    }

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

    private fun localImageDeviceFitScore(model: ModelScopeRecommendedModel, device: DeviceProfile): Int {
        val engineTier = model.localImageEngineTier ?: return 0
        val bundle = model.imageEngineBundle
        val ramGb = device.displayTotalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val memoryFit = when {
            ramGb <= 0.0 -> 0
            model.minRamGb <= ramGb -> 4
            model.minRamGb <= ramGb + 2.0 -> 2
            else -> 0
        }
        val qualityFit = when {
            bundle?.accelerator == ImageEngineAccelerator.QNN_HTP &&
                bundle.minDeviceTier == ImageEngineMinDeviceTier.SNAPDRAGON_8_GEN3 -> 8
            bundle?.accelerator == ImageEngineAccelerator.QNN_HTP &&
                bundle.minDeviceTier == ImageEngineMinDeviceTier.SNAPDRAGON_8_ELITE -> 8
            engineTier == LocalImageEngineTier.COMPACT_QUALITY -> 6
            engineTier == LocalImageEngineTier.HEAVY_EXPERIMENTAL -> 5
            engineTier == LocalImageEngineTier.LARGE_QUALITY -> 4
            engineTier == LocalImageEngineTier.STANDARD -> 3
            engineTier == LocalImageEngineTier.QUICK -> 2
            else -> 0
        }
        val acceleration = device.accelerationProfile
        val npuFit = if (bundle?.accelerator == ImageEngineAccelerator.QNN_HTP) {
            when {
                !acceleration.localImage.deviceCapable -> -4
                bundle.requiresQnnRuntime && acceleration.qnnRuntime.usableForSmoke -> 6
                bundle.requiresQnnRuntime -> 3
                else -> 1
            }
        } else {
            0
        }
        val runtimeReadyBonus = if (acceleration.qnnRuntime.usableForSmoke && npuFit > 0) 2 else 0
        return memoryFit + qualityFit + npuFit + runtimeReadyBonus
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

internal fun recommendedQairtArchitecture(model: ModelScopeRecommendedModel): String = when (model.id) {
    "qwen3_vl_4b_qairt_w4a16" -> "qwen3-vl"
    "qwen25_vl_7b_qairt_w4a16" -> "qwen2.5-vl"
    else -> "qwen3"
}

internal fun downloadedImageBundleManifestJson(
    displayName: String,
    bundle: ImageEngineBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>,
    primarySha256: String? = null
): JSONObject {
    val primary = targets.firstOrNull { it.first.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
    val familyHint = "${bundle.id}/$displayName/${primary?.second?.name.orEmpty()}"
    val family = bundle.modelFamily
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: LocalImageModelFamily.infer(familyHint).name
    val components = JSONArray()
    targets.forEach { (remote, file) ->
        val role = remote.bundleRole ?: ImageEngineBundleComponentRole.OPTIONAL
        components.put(
            JSONObject()
                .put("role", role.name)
                .put("path", remote.relativePath)
                .put("fileName", remote.relativePath)
                .put("sourceRepo", remote.repoId)
                .put("sourcePath", remote.path)
                .put("provider", remote.provider.name)
                .put("required", role != ImageEngineBundleComponentRole.OPTIONAL)
        )
    }
    val manifest = JSONObject()
        .put("schema", "mca.image_engine.bundle.v1")
        .put("id", bundle.id)
        .put("recommendationId", bundle.recommendationId)
        .put("title", displayName)
        .put("task", bundle.task.name)
        .put("runtime", bundle.runtime.name)
        .put("accelerator", bundle.accelerator.name)
        .put("minDeviceTier", bundle.minDeviceTier.name)
        .put("family", family)
        .put("requiresQnnRuntime", bundle.requiresQnnRuntime)
        .put("requiresSmokeTest", bundle.requiresSmokeTest)
        .put("imageSize", "${bundle.smokeSpec.width}x${bundle.smokeSpec.height}")
        .put(
            "smoke",
            JSONObject()
                .put("width", bundle.smokeSpec.width)
                .put("height", bundle.smokeSpec.height)
                .put("steps", bundle.smokeSpec.steps)
                .put("timeoutSeconds", bundle.smokeSpec.timeoutSeconds)
                .put("prompt", bundle.smokeSpec.prompt)
                .put("expectedOutputMime", bundle.smokeSpec.expectedOutputMime)
        )
        .put("components", components)
    bundle.executionProfile?.let {
        val primaryFile = primary?.second?.takeIf(File::isFile)
            ?: error("Image execution profile requires a concrete downloaded primary model file.")
        val fingerprint = primarySha256
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: primaryFile.sha256ForProfile()
        val profile = requireNotNull(
            materializeDownloadedImageExecutionProfile(bundle, fingerprint)
        ).resolveDownloadedExecutionGraphPaths(targets)
        manifest.put("executionProfile", ImageExecutionProfileJson.toJson(profile))
    }
    bundle.requiredRuntimeProfile?.let { profile ->
        manifest.put(
            "requiredRuntimeProfile",
            JSONObject()
                .put("qnnSdk", profile.qnnSdk)
                .put("htpArch", profile.htpArch)
                .put("completeBundleRuntime", profile.completeBundleRuntime)
        )
    }
    if (bundle.qnnSmokeSpecs.isNotEmpty()) {
        manifest.put(
            "smokes",
            JSONArray(
                bundle.qnnSmokeSpecs.map { smoke ->
                    JSONObject()
                        .put("width", smoke.width)
                        .put("height", smoke.height)
                        .put("steps", smoke.steps)
                        .put("timeoutSeconds", smoke.timeoutSeconds)
                        .put("prompt", smoke.prompt)
                        .put("graphName", smoke.graphName)
                        .put(
                            "contextBinary",
                            resolvedDownloadedQnnContextPath(smoke.contextBinary, targets)
                        )
                        .put(
                            "inputs",
                            JSONArray(
                                smoke.inputs.map { tensor ->
                                    JSONObject()
                                        .put("name", tensor.name)
                                        .put("role", tensor.role)
                                        .put("dataType", tensor.dataType)
                                        .put("shape", JSONArray(tensor.shape))
                                        .put("fill", tensor.fill)
                                }
                            )
                        )
                        .put(
                            "outputs",
                            JSONArray(
                                smoke.outputs.map { tensor ->
                                    JSONObject()
                                        .put("name", tensor.name)
                                        .put("role", tensor.role)
                                        .put("dataType", tensor.dataType)
                                        .put("shape", JSONArray(tensor.shape))
                                        .put("fill", tensor.fill)
                                }
                            )
                        )
                }
            )
        )
    }
    return manifest
}

/**
 * Public QNN ZIPs often wrap graph binaries in a publisher-specific directory.
 * Persist the extracted relative path instead of a root-only catalog basename;
 * native workers then resolve the exact immutable artifact recorded in the
 * component manifest. Ambiguous filenames deliberately remain unresolved so
 * the structural/native smoke reports a concrete package error.
 */
internal fun resolvedDownloadedQnnContextPath(
    requested: String,
    targets: List<Pair<RemoteModelFile, File>>
): String {
    val normalized = requested.replace('\\', '/').trim().trimStart('/')
    val paths = targets.map { it.first.relativePath.replace('\\', '/').trim().trimStart('/') }
    paths.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }
    val requestedName = normalized.substringAfterLast('/')
    val matchingNames = paths.filter {
        it.substringAfterLast('/').equals(requestedName, ignoreCase = true)
    }.distinct()
    return matchingNames.singleOrNull() ?: normalized
}

private fun ImageExecutionProfile.resolveDownloadedExecutionGraphPaths(
    targets: List<Pair<RemoteModelFile, File>>
): ImageExecutionProfile {
    fun ImageGraphArtifactContract?.resolved(): ImageGraphArtifactContract? = this?.copy(
        relativePath = resolvedDownloadedQnnContextPath(relativePath, targets)
    )
    fun String?.resolvedSidecar(): String? = this?.let { requested ->
        resolvedDownloadedQnnContextPath(requested, targets)
    }
    return copy(
        graph = graph.copy(
            textEncoder = graph.textEncoder.resolved(),
            unet = graph.unet.resolved(),
            vae = graph.vae.resolved(),
            vaeEncoder = graph.vaeEncoder.resolved(),
            controlNet = graph.controlNet.resolved(),
            schedulerSidecar = graph.schedulerSidecar.resolvedSidecar(),
            tokenizerSidecar = graph.tokenizerSidecar.resolvedSidecar(),
            configSidecars = graph.configSidecars.map { path ->
                resolvedDownloadedQnnContextPath(path, targets)
            }
        )
    )
}

private fun expandedImageBundleManifestTargets(
    bundleDir: File,
    resolvedPrimary: File,
    targets: List<Pair<RemoteModelFile, File>>
): List<Pair<RemoteModelFile, File>> {
    val root = bundleDir.canonicalFile
    val primaryFile = resolvedPrimary.canonicalFile
    val archiveTarget = targets.firstOrNull { (remote, file) ->
        remote.bundleRole == ImageEngineBundleComponentRole.DIFFUSION &&
            file.extension.equals("zip", ignoreCase = true)
    }
    if (archiveTarget == null) return targets
    val archiveRemote = archiveTarget.first
    val existingTargetsByPath = targets
        .filterNot { it === archiveTarget }
        .mapNotNull { (remote, file) ->
            runCatching { file.canonicalFile }.getOrNull()
                ?.takeIf(File::isFile)
                ?.let { it.path to remote }
        }
        .toMap()
    return root.walkTopDown()
        .filter(File::isFile)
        .filterNot { it.name == "manifest.json" || it.name.endsWith(".part") }
        .map { file ->
            val canonical = file.canonicalFile
            val relative = canonical.relativeTo(root).invariantSeparatorsPath
            val existingRemote = existingTargetsByPath[canonical.path]
            val remote = when {
                existingRemote != null -> existingRemote.copy(relativePath = relative)
                canonical == primaryFile -> archiveRemote.copy(
                    path = "${archiveRemote.path}!/$relative",
                    name = canonical.name,
                    bundleRole = ImageEngineBundleComponentRole.DIFFUSION,
                    relativePath = relative
                )
                else -> archiveRemote.copy(
                    path = "${archiveRemote.path}!/$relative",
                    name = canonical.name,
                    sizeBytes = canonical.length(),
                    sha256 = null,
                    bundleRole = ImageEngineBundleComponentRole.CONFIG,
                    relativePath = relative
                )
            }
            remote to canonical
        }
        .toList()
}

private fun writeDownloadedImageBundleManifest(
    displayName: String,
    bundleDir: File,
    bundle: ImageEngineBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>,
    primarySha256: String
) {
    bundleDir.mkdirs()
    File(bundleDir, "manifest.json").writeText(
        downloadedImageBundleManifestJson(
            displayName = displayName,
            bundle = bundle,
            targets = targets,
            primarySha256 = primarySha256
        ).toString(2),
        Charsets.UTF_8
    )
}

private fun preparePinnedQnnRuntimeMetadataIfRequired(
    bundleDir: File,
    bundle: ImageEngineBundleSpec
) {
    val profile = bundle.requiredRuntimeProfile
        ?.takeIf { it.completeBundleRuntime }
        ?: return
    val archive = bundle.components.singleOrNull { component ->
        component.role == ImageEngineBundleComponentRole.DIFFUSION &&
            component.fileName.endsWith(".zip", ignoreCase = true)
    } ?: error("QNN runtime contract requires one pinned archive component.")
    val expectedArchiveBytes = archive.expectedSizeBytes
    require(expectedArchiveBytes != null && expectedArchiveBytes > 0L) {
        "QNN runtime archive is missing a pinned byte size."
    }
    val archiveSha256 = archive.sha256?.trim()?.lowercase().orEmpty()
    require(Regex("^[0-9a-f]{64}$").matches(archiveSha256)) {
        "QNN runtime archive is missing a pinned SHA-256."
    }
    writePinnedQnnRuntimeMetadata(
        bundleRoot = bundleDir,
        qnnSdk = profile.qnnSdk,
        contextHtpArch = profile.htpArch,
        sourceArchiveSha256 = archiveSha256
    )
}

/**
 * A universal QNN archive can contain several physical HTP transports. Persist
 * the profile selected for this installation so a V79 phone does not stage the
 * V68 fallback merely because it is the catalog's broad compatibility floor.
 * Missing or unknown hardware metadata remains advisory: in that case the
 * pinned fallback (or any complete packaged profile) is used and the isolated
 * native graph smoke remains the authority.
 */
internal fun resolveInstalledQnnRuntimeProfile(
    bundleDir: File,
    bundle: ImageEngineBundleSpec,
    preferredHtpArch: Int?
): ImageEngineBundleSpec {
    val required = bundle.requiredRuntimeProfile
        ?.takeIf { it.completeBundleRuntime }
        ?: return bundle
    val selected = preferredHtpArch
        ?.let { arch -> qnnImageBundleRuntimeProfileForArchOrNull(bundleDir, arch) }
        ?: qnnImageBundleRuntimeProfileForArchOrNull(bundleDir, required.htpArch)
        ?: coherentQnnImageBundleRuntimeProfileOrNull(bundleDir)
        ?: return bundle
    return bundle.copy(
        requiredRuntimeProfile = required.copy(htpArch = selected.htpArchVersion)
    )
}

internal fun downloadedVisionBundleManifestJson(
    displayName: String,
    bundle: VisionModelBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>
): JSONObject {
    val components = JSONArray()
    targets.forEach { (remote, file) ->
        val role = remote.visionBundleRole ?: VisionModelBundleComponentRole.OPTIONAL
        components.put(
            JSONObject()
                .put("role", role.name)
                .put("path", file.relativePathFromCommonRoot(targets))
                .put("fileName", file.name)
                .put("sourceRepo", remote.repoId)
                .put("sourcePath", remote.path)
                .put("provider", remote.provider.name)
                .put("required", role != VisionModelBundleComponentRole.OPTIONAL)
        )
    }
    return JSONObject()
        .put("schema", "mca.vision_engine.bundle.v1")
        .put("id", bundle.id)
        .put("title", displayName)
        .put("runtime", bundle.runtime.name)
        .put("accelerator", bundle.accelerator.name)
        .put("minDeviceTier", bundle.minDeviceTier.name)
        .put("requiresQnnRuntime", bundle.requiresQnnRuntime)
        .put("requiresSmokeTest", bundle.requiresSmokeTest)
        .put(
            "smoke",
            JSONObject()
                .put("imageWidth", bundle.smokeSpec.imageWidth)
                .put("imageHeight", bundle.smokeSpec.imageHeight)
                .put("prompt", bundle.smokeSpec.prompt)
                .put("timeoutSeconds", bundle.smokeSpec.timeoutSeconds)
        )
        .put("npuActive", false)
        .put("components", components)
}

private fun writeDownloadedVisionBundleManifest(
    displayName: String,
    bundleDir: File,
    bundle: VisionModelBundleSpec,
    targets: List<Pair<RemoteModelFile, File>>
) {
    bundleDir.mkdirs()
    File(bundleDir, "manifest.json").writeText(
        downloadedVisionBundleManifestJson(
            displayName = displayName,
            bundle = bundle,
            targets = targets
        ).toString(2),
        Charsets.UTF_8
    )
}

private fun File.relativePathFromCommonRoot(targets: List<Pair<RemoteModelFile, File>>): String {
    val root = targets.firstOrNull()?.second?.parentFile ?: parentFile
    return if (root != null) {
        runCatching { relativeTo(root).invariantSeparatorsPath }.getOrDefault(name)
    } else {
        name
    }
}

private fun ModelRepositoryProvider.toModelSource(): ModelSource =
    when (this) {
        ModelRepositoryProvider.MODELSCOPE -> ModelSource.MODELSCOPE
        ModelRepositoryProvider.HUGGING_FACE -> ModelSource.HUGGING_FACE
    }

private fun ChatModelRuntime.toLocalChatRuntime(): LocalChatRuntime =
    when (this) {
        ChatModelRuntime.MNN -> LocalChatRuntime.MNN_CPU
        ChatModelRuntime.LLAMA_CPP -> LocalChatRuntime.LLAMA_CPP
        ChatModelRuntime.GENIEX_QAIRT -> LocalChatRuntime.GENIEX_QAIRT
    }

private fun ImageEngineBundleRuntime.toLocalImageRuntime(): LocalImageRuntime =
    when (this) {
        ImageEngineBundleRuntime.STABLE_DIFFUSION_CPP -> LocalImageRuntime.STABLE_DIFFUSION_CPP
        ImageEngineBundleRuntime.MNN_DIFFUSION -> LocalImageRuntime.MNN_DIFFUSION
        ImageEngineBundleRuntime.QNN_HTP -> LocalImageRuntime.QNN_HTP
    }

private fun String.toFilePreview(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(4)
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .take(180)
        .ifBlank { "无可预览文本" }
