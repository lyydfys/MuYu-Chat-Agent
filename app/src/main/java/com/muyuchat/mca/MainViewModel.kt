package com.muyuchat.mca

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muyuchat.api.local.LocalApiRuntime
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
import com.muyuchat.core.deviceprofile.DeviceProfile
import com.muyuchat.core.deviceprofile.DeviceProfileReader
import com.muyuchat.core.download.ModelScopeClient
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ImageEngineBundleComponentRole
import com.muyuchat.core.download.LocalImageEngineTier
import com.muyuchat.core.download.ModelRepositoryProvider
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.DownloadTaskSnapshot
import com.muyuchat.core.download.ResumableDownloader
import com.muyuchat.core.download.isImageModelCandidate
import com.muyuchat.core.download.kindLabel
import com.muyuchat.core.engine.ChatImageAttachment
import com.muyuchat.core.engine.ChatMessage
import com.muyuchat.core.engine.ChatRequest
import com.muyuchat.core.engine.ChatSourceReference
import com.muyuchat.core.engine.ChatWebSearchTrace
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.GenerationParams
import com.muyuchat.core.engine.LoadParams
import com.muyuchat.core.engine.McaInferenceService
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.modelstore.ModelSource
import com.muyuchat.core.modelstore.ModelStoreRepository
import com.muyuchat.core.tuning.PerformanceMode
import com.muyuchat.core.tuning.UserPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

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

enum class ImageGenerationStatusRecord(val label: String, val failed: Boolean = false) {
    QUEUED("排队"),
    GENERATING("生成中"),
    DONE("完成"),
    FAILED("失败", failed = true)
}

data class ImageGenerationJobRecord(
    val id: String,
    val prompt: String,
    val status: ImageGenerationStatusRecord,
    val imageAssetId: String? = null,
    val message: String = "",
    val startedAtMillis: Long = System.currentTimeMillis()
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
    val remoteFiles: List<RemoteModelFile> = emptyList(),
    val recommendedRemoteModels: List<ModelScopeRecommendedModel> = emptyList(),
    val hubModels: List<ModelScopeHubModel> = emptyList(),
    val hubQuery: String = "Qwen3.5 GGUF Q4_K_M",
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
    val rollbackParams: GenerationParams? = null,
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
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val REST_PORT = 11435
        private const val CLOUD_REASONING_LOCKED_MESSAGE = "云端模型的思考模式由服务商和具体模型决定，MCA 会默认按开启处理；如需关闭或调整，请在云端模型配置中设置相关参数。"
        private const val LOCAL_IMAGE_GENERATION_WATCHDOG_MS = 8 * 60 * 1000L
        private const val ASSISTANT_MODEL_MODE_FOLLOW_CURRENT = "follow_current"
        private const val ASSISTANT_MODEL_MODE_LOCAL = "local"
        private const val ASSISTANT_MODEL_MODE_CLOUD = "cloud"
        const val CLOUD_MODEL_CHOICE_PREFIX = "cloudmodel:"
        const val CLOUD_IMAGE_MODEL_CHOICE_PREFIX = "cloudimagemodel:"
        const val LOCAL_IMAGE_MODEL_CHOICE_PREFIX = "localimagemodel:"
    }

    private val modelStore = ModelStoreRepository(application)
    private val modelScopeClient = ModelScopeClient()
    private val downloader = ResumableDownloader()
    private val engine = McaInferenceService(application)
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
    private val localImageProvider = LocalImageProvider(application)
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
        ?.takeIf { id -> initialLocalImageModels.any { it.id == id && it.isReadyForLocalImageGeneration() } }
        ?: initialLocalImageModels.firstOrNull { it.isReadyForLocalImageGeneration() }?.id
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
    private var imageGenerationJob: Job? = null
    private var activeImageGenerationJobId: String? = null

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
            recommendedRemoteModels = sortRecommendedModels(modelScopeClient.recommendedModels(), initialDeviceProfile),
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
        LocalApiRuntime.engine = engine
        LocalApiRuntime.loadedModelJsonProvider = { loadedModelJson() }
        LocalApiRuntime.paramsJsonProvider = { _uiState.value.params.toJson() }
        LocalApiRuntime.generationParamsProvider = { _uiState.value.params }
        LocalApiRuntime.modelsJsonProvider = { modelsJson() }
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
                _uiState.update {
                    it.copy(stats = stats, nativeStatsJson = engine.nativeStatsJson())
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(tab = tab) }
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
                var imagesToPersist: List<ImageAssetRecord>? = null
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
                    if (updatedImages != null) imagesToPersist = updatedImages
                    if (updatedFiles != null) filesToPersist = updatedFiles
                    state.copy(
                        input = state.input + attachment,
                        images = updatedImages ?: state.images,
                        files = updatedFiles ?: state.files,
                        statusMessage = if (imported.imageAsset != null) "已添加图片：${imported.name}" else "已添加文件：${imported.name}"
                    )
                }
                imagesToPersist?.let { persistImages(it) }
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

    fun generateImageAsset(prompt: String) {
        val cleanPrompt = prompt.trim().take(600)
        if (cleanPrompt.isBlank()) {
            _uiState.update { it.copy(statusMessage = "请输入图片描述") }
            return
        }
        if (imageGenerationJob?.isActive == true) {
            _uiState.update { it.copy(statusMessage = "已有图片生成任务正在运行，请先停止当前任务") }
            return
        }
        val jobId = "image-${UUID.randomUUID()}"
        val requestedBackend = _uiState.value.selectedImageBackend
        activeImageGenerationJobId = jobId
        _uiState.update { state ->
            state.copy(
                imageJobs = (
                    listOf(
                        ImageGenerationJobRecord(
                            id = jobId,
                            prompt = cleanPrompt,
                            status = ImageGenerationStatusRecord.QUEUED,
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
                val image = runCatching {
                    when (requestedBackend) {
                        ImageBackend.LOCAL -> {
                            val model = _uiState.value.selectedLocalImageModel()
                                ?: error("请先在模型管理的本地页导入并选择图像生成引擎。")
                            createLocalGeneratedImageAsset(
                                prompt = cleanPrompt,
                                model = model,
                                chatSessionId = _uiState.value.activeChatSessionId,
                                onProgress = { progress ->
                                    val message = progress.toImageGenerationMessage()
                                    _uiState.update { state ->
                                        val job = state.imageJobs.firstOrNull { it.id == jobId }
                                        if (job?.status == ImageGenerationStatusRecord.GENERATING) {
                                            state.copy(
                                                imageJobs = state.imageJobs.updateImageJob(
                                                    jobId,
                                                    ImageGenerationStatusRecord.GENERATING,
                                                    message
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
                            val imageConfig = _uiState.value.selectedImageCloudConfig()
                                ?: error("请先在模型管理的云端页接入并选择图像生成模型。")
                            createCloudGeneratedImageAsset(
                                prompt = cleanPrompt,
                                config = imageConfig.normalized(),
                                chatSessionId = _uiState.value.activeChatSessionId
                            )
                        }
                    }
                }.getOrElse { error ->
                    val message = error.message ?: "图片生成模型调用失败"
                    _uiState.update { state ->
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(jobId, ImageGenerationStatusRecord.FAILED, message),
                            statusMessage = "图片生成失败：$message"
                        )
                    }
                    return@launch
                }
                var imagesToPersist: List<ImageAssetRecord> = emptyList()
                _uiState.update { state ->
                    imagesToPersist = (listOf(image) + state.images).sortedImagesForLibrary()
                    state.copy(
                        images = imagesToPersist,
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            ImageGenerationStatusRecord.DONE,
                            "已保存到图片库",
                            image.id
                        ),
                        statusMessage = "已生成图片并保存到图片库：${image.name}"
                    )
                }
                persistImages(imagesToPersist)
            } finally {
                if (activeImageGenerationJobId == jobId) {
                    activeImageGenerationJobId = null
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
                    val message = "本地生图超过 8 分钟仍未返回，任务可能卡住。建议降低尺寸/步数，或稍后等待 native 后端完成。"
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(jobId, ImageGenerationStatusRecord.FAILED, message),
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
            localImageProvider.cancel()
            imageGenerationJob?.cancel()
            val jobId = activeImageGenerationJobId
            if (jobId != null) {
                _uiState.update { state ->
                    state.copy(
                        imageJobs = state.imageJobs.updateImageJob(
                            jobId,
                            ImageGenerationStatusRecord.FAILED,
                            "已停止图片生成"
                        ),
                        statusMessage = "已停止图片生成"
                    )
                }
            } else {
                _uiState.update { state ->
                    val working = state.imageJobs.firstOrNull {
                        it.status == ImageGenerationStatusRecord.QUEUED || it.status == ImageGenerationStatusRecord.GENERATING
                    }
                    if (working != null) {
                        state.copy(
                            imageJobs = state.imageJobs.updateImageJob(
                                working.id,
                                ImageGenerationStatusRecord.FAILED,
                                "已停止图片生成"
                            ),
                            statusMessage = "已停止图片生成"
                        )
                    } else {
                        state.copy(statusMessage = "当前没有正在生成的图片任务")
                    }
                }
            }
            activeImageGenerationJobId = null
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

    fun deleteImageAsset(imageId: String) {
        var removed: ImageAssetRecord? = null
        var imagesToPersist: List<ImageAssetRecord> = emptyList()
        _uiState.update { state ->
            removed = state.images.firstOrNull { it.id == imageId }
            imagesToPersist = state.images.filterNot { it.id == imageId }
            state.copy(images = imagesToPersist, statusMessage = "已从图片库移除：${removed?.name ?: "图片"}")
        }
        removed?.deleteLocalCopy()
        persistImages(imagesToPersist)
    }

    fun clearImageLibrary() {
        val imagesToRemove = _uiState.value.images
        if (imagesToRemove.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "图片库已为空") }
            return
        }
        imagesToRemove.forEach { it.deleteLocalCopy() }
        _uiState.update {
            it.copy(
                images = emptyList(),
                imageJobs = it.imageJobs.filter { job -> job.status == ImageGenerationStatusRecord.GENERATING },
                statusMessage = "已清空图片库：${imagesToRemove.size} 张图片"
            )
        }
        persistImages(emptyList())
    }

    fun importLocalImageModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在导入本地图像生成引擎...")
            runCatching { localImageModelStore.importFromUri(uri) }
                .onSuccess { model ->
                    val models = localImageModelStore.loadModels()
                    val readiness = model.localImageReadinessMessage()
                    if (readiness == null) {
                        localImageModelStore.saveSelectedModelId(model.id)
                        localImageModelStore.saveSelectedBackend(ImageBackend.LOCAL)
                    }
                    _uiState.update {
                        it.copy(
                            localImageModels = models,
                            selectedLocalImageModelId = if (readiness == null) model.id else it.selectedLocalImageModelId,
                            selectedImageBackend = if (readiness == null) ImageBackend.LOCAL else it.selectedImageBackend,
                            busy = false,
                            statusMessage = if (readiness == null) {
                                "已导入本地图像生成引擎：${model.displayName}"
                            } else {
                                "已导入 ${model.displayName}，但暂不能生成：$readiness"
                            }
                        )
                    }
                }
                .onFailure { error ->
                    fail(error.message ?: "本地图像生成引擎导入失败")
                }
        }
    }

    fun selectLocalImageModel(modelId: String) {
        val model = _uiState.value.localImageModels.firstOrNull { it.id == modelId }
        if (model == null) {
            _uiState.update { it.copy(statusMessage = "未找到本地图像生成引擎") }
            return
        }
        model.localImageReadinessMessage()?.let { message ->
            _uiState.update { it.copy(statusMessage = message) }
            return
        }
        localImageModelStore.saveSelectedModelId(model.id)
        localImageModelStore.saveSelectedBackend(ImageBackend.LOCAL)
        _uiState.update {
            it.copy(
                selectedLocalImageModelId = model.id,
                selectedImageBackend = ImageBackend.LOCAL,
                statusMessage = "图片页已切换到本地生图：${model.displayName}"
            )
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
            val readable = model.configured && primary.isFile && primary.canRead()
            val message = when {
                !readable -> "图像引擎校验失败：主模型文件不可读，请重新导入或重新下载。"
                readiness != null -> "图像引擎校验失败：$readiness"
                else -> {
                    val componentText = if (model.componentCount > 1) "，组件 ${model.componentCount} 个" else ""
                    "图像引擎校验通过：主模型可读$componentText，可用于图片页本地生图。"
                }
            }
            _uiState.update {
                it.copy(
                    localImageModels = localImageModelStore.loadModels(),
                    busy = false,
                    statusMessage = message
                )
            }
        }
    }

    fun deleteLocalImageModel(modelId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = _uiState.value.localImageModels.firstOrNull { it.id == modelId }
            val success = runCatching { localImageModelStore.deleteModel(modelId) }.getOrDefault(false)
            val models = localImageModelStore.loadModels()
            val selectedId = localImageModelStore.loadSelectedModelId()
                ?.takeIf { id -> models.any { it.id == id && it.isReadyForLocalImageGeneration() } }
                ?: models.firstOrNull { it.isReadyForLocalImageGeneration() }?.id
            _uiState.update {
                it.copy(
                    localImageModels = models,
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
                localImageModelStore.saveSelectedBackend(ImageBackend.CLOUD)
                _uiState.update { it.copy(selectedImageBackend = ImageBackend.CLOUD) }
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
        persistGenerationParams(params)
        val updatedAssistants = updatedAssistantsWithParams(params)
        assistantStore.saveAssistants(updatedAssistants)
        _uiState.update { it.copy(params = params, assistants = updatedAssistants) }
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
        nCtx: Int,
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
                nCtx = nCtx.coerceIn(512, 262_144),
                nPredict = nPredict.coerceIn(128, 65_536),
                reasoningMode = reasoningMode,
                hideReasoning = reasoningMode == ReasoningMode.OFF
            ).toJson(),
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
            updatedAt = System.currentTimeMillis()
        ) ?: CloudModelRecord(
            kind = CloudModelKind.CHAT,
            apiFormat = config.apiFormat,
            providerName = config.providerName,
            displayName = config.safeDisplayName(),
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            modelName = config.chatModel,
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
        val updatedParams = recommendation?.tuningPlan?.toGenerationParams(
            systemPrompt = state.params.systemPrompt,
            reasoningMode = state.params.reasoningMode,
            hideReasoning = state.params.hideReasoning
        )
        if (updatedParams != null) persistGenerationParams(updatedParams)
        _uiState.update {
            it.copy(
                preference = preference,
                agentRecommendation = recommendation,
                params = updatedParams ?: it.params,
                rollbackParams = if (updatedParams != null && updatedParams != state.params) {
                    state.rollbackParams ?: state.params
                } else {
                    state.rollbackParams
                },
                statusMessage = if (updatedParams != null) {
                    "已切换为${preference.mode.label}参数：n_ctx=${updatedParams.nCtx}，threads=${updatedParams.nThreads}，n_predict=${updatedParams.nPredict}"
                } else {
                    recommendation?.explanation ?: it.statusMessage
                }
            )
        }
    }

    fun refreshLocalModels() {
        _uiState.update { it.copy(models = modelStore.listModels()) }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在导入本地推理引擎...")
            runCatching {
                modelStore.importFromUri(uri)
            }.onSuccess { model ->
                _uiState.update {
                    it.copy(
                        models = modelStore.listModels(),
                        busy = false,
                            statusMessage = "已导入 GGUF：${model.displayName}"
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
            busy("正在查询 ModelScope GGUF 文件...")
            runCatching {
                modelScopeClient.listGgufFiles(input)
            }.onSuccess { files ->
                _uiState.update {
                    it.copy(remoteFiles = files, busy = false, statusMessage = "找到 ${files.size} 个 GGUF 文件")
                }
            }.onFailure { error ->
                fail("查询失败：${error.message}")
            }
        }
    }

    fun fetchRecommendedFiles(model: ModelScopeRecommendedModel) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在读取推荐模型：${model.title}...")
            runCatching {
                modelScopeClient.listRecommendedFiles(model)
            }.onSuccess { files ->
                _uiState.update {
                    it.copy(
                        repoInput = model.repoId,
                        remoteFiles = files,
                        busy = false,
                        statusMessage = "已列出 ${model.title} 的 ${files.size} 个 GGUF 文件"
                    )
                }
            }.onFailure { error ->
                fail("推荐模型读取失败：${error.message}")
            }
        }
    }

    fun downloadRecommended(model: ModelScopeRecommendedModel) {
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

    private fun downloadRecommendedImageBundle(model: ModelScopeRecommendedModel) {
        viewModelScope.launch(Dispatchers.IO) {
            val bundle = model.imageEngineBundle ?: return@launch
            busy("正在准备下载生图引擎包：${bundle.title}...")
            runCatching {
                val components = modelScopeClient.recommendedImageBundleFiles(model)
                val primary = components.firstOrNull { it.bundleRole == ImageEngineBundleComponentRole.DIFFUSION }
                    ?: error("生图引擎包缺少 diffusion 主模型。")
                val bundleDir = localImageModelStore.managedBundleDirFor(bundle.id)
                val targets = components.map { remote ->
                    remote to localImageModelStore.managedBundleFileFor(bundleDir, remote.name)
                }
                val bytesToDownload = targets.sumOf { (remote, finalFile) ->
                    val expected = remote.sizeBytes ?: 0L
                    if (
                        finalFile.exists() && (expected <= 0L || finalFile.length() == expected)
                    ) {
                        0L
                    } else {
                        expected
                    }
                }
                val knownTotalBytes = targets.sumOf { (remote, finalFile) ->
                    val expected = remote.sizeBytes ?: 0L
                    when {
                        finalFile.exists() && expected <= 0L -> finalFile.length()
                        else -> expected
                    }
                }
                val usableSpace = bundleDir.usableSpace
                if (bytesToDownload > 0L && usableSpace in 1 until bytesToDownload) {
                    error("存储空间不足：引擎包还需 ${formatBytes(bytesToDownload)}，请清理空间后重试。")
                }
                val tempDir = getApplication<Application>().externalCacheDir ?: getApplication<Application>().cacheDir
                var completedBytes = targets.sumOf { (remote, finalFile) ->
                    val expected = remote.sizeBytes ?: 0L
                    when {
                        finalFile.exists() && (expected <= 0L || finalFile.length() == expected) -> finalFile.length()
                        else -> 0L
                    }
                }
                targets.forEachIndexed { index, (remote, finalFile) ->
                    val expected = remote.sizeBytes ?: 0L
                    if (
                        finalFile.exists() && (expected <= 0L || finalFile.length() == expected)
                    ) {
                        _uiState.update {
                            it.copy(
                                downloadFileName = remote.name,
                                downloadedBytes = completedBytes,
                                downloadTotalBytes = knownTotalBytes,
                                downloadStatus = DownloadStatus.RUNNING,
                                statusMessage = "已存在组件 ${index + 1}/${targets.size}：${remote.kindLabel()}"
                            )
                        }
                        return@forEachIndexed
                    }
                    val tempFile = File(tempDir, "${bundle.id}-${remote.name}.part".replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    val completedBefore = completedBytes
                    downloader.download(remote, tempFile, finalFile) { snapshot ->
                        _uiState.update {
                            it.copy(
                                downloadFileName = snapshot.fileName,
                                downloadedBytes = completedBefore + snapshot.downloadedBytes,
                                downloadTotalBytes = knownTotalBytes.takeIf { total -> total > 0L } ?: snapshot.expectedLength,
                                downloadSpeedBytesPerSecond = snapshot.speedBytesPerSecond,
                                downloadRemainingSeconds = snapshot.remainingSeconds,
                                downloadStatus = snapshot.status,
                                statusMessage = "正在下载生图组件 ${index + 1}/${targets.size}：${remote.kindLabel()} · ${snapshot.fileName}"
                            )
                        }
                    }
                    completedBytes += finalFile.length()
                }
                val primaryFile = targets.firstOrNull { it.first == primary }?.second
                    ?: error("生图引擎包主模型下载目标不存在。")
                localImageModelStore.registerDownloadedBundle(
                    displayName = model.title,
                    bundleDir = bundleDir,
                    primaryFile = primaryFile,
                    primaryRemote = primary,
                    componentCount = components.size
                )
            }.onSuccess { record ->
                _uiState.update {
                    it.copy(
                        localImageModels = localImageModelStore.loadModels(),
                        selectedLocalImageModelId = record.id,
                        selectedImageBackend = ImageBackend.LOCAL,
                        busy = false,
                        downloadStatus = DownloadStatus.DONE,
                        downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                        downloadSpeedBytesPerSecond = 0L,
                        downloadRemainingSeconds = null,
                        statusMessage = "已下载完整本地生图引擎包：${record.displayName}"
                    )
                }
            }.onFailure { error ->
                fail("生图引擎包下载失败：${downloadFailureAdvice(error.message)}")
            }
        }
    }

    fun searchHubModels(reset: Boolean = true) {
        val state = _uiState.value
        val query = state.hubQuery.ifBlank { "gguf" }
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
            busy("正在读取 ${model.displayName} 的 GGUF 文件...")
            runCatching {
                modelScopeClient.listGgufFiles(model.id)
            }.onSuccess { files ->
                _uiState.update {
                    it.copy(
                        repoInput = model.id,
                        remoteFiles = files,
                        busy = false,
                        statusMessage = "已列出 ${model.displayName} 的 ${files.size} 个 GGUF 文件"
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
                val imageModel = remote.isImageModelCandidate()
                val finalFile = if (imageModel) {
                    localImageModelStore.managedFileFor(remote.name)
                } else {
                    modelStore.managedFileFor(remote.name)
                }
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
                        is DownloadedModelRegistration.Image -> {
                            val readiness = registration.model.localImageReadinessMessage()
                            it.copy(
                                localImageModels = localImageModelStore.loadModels(),
                                selectedLocalImageModelId = if (readiness == null) {
                                    it.selectedLocalImageModelId ?: registration.model.id
                                } else {
                                    it.selectedLocalImageModelId
                                },
                                busy = false,
                                downloadStatus = DownloadStatus.DONE,
                                downloadedBytes = it.downloadTotalBytes.takeIf { total -> total > 0L } ?: it.downloadedBytes,
                                downloadSpeedBytesPerSecond = 0L,
                                downloadRemainingSeconds = null,
                                statusMessage = if (readiness == null) {
                                    "已下载图像生成模型：${registration.model.displayName}"
                                } else {
                                    "已下载图像主模型：${registration.model.displayName}。$readiness"
                                }
                            )
                        }
                    }
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
            generationJob?.cancel()
            engine.stopGeneration()
            busy("正在加载 ${model.displayName}...")
            val preflight = modelStore.validateForLoad(model.id)
            if (!preflight.canLoad) {
                fail("加载前检查失败：${preflight.message}")
                return@launch
            }
            loadMemoryBlocker(model)?.let { message ->
                fail("加载前内存检查失败：$message")
                return@launch
            }
            val params = _uiState.value.params
            val visionProjectorPath = model.visionProjectorPath
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { File(it).isFile }
            engine.loadModel(
                modelPath = model.path,
                params = LoadParams(
                    nCtx = params.nCtx,
                    nThreads = params.nThreads,
                    visionProjectorPath = visionProjectorPath
                )
            ).onSuccess {
                modelStore.markLoaded(model.id)
                val device = currentDeviceProfile()
                val recommendation = runCatching {
                    advisor.recommend(
                        device = device,
                        localModels = listOf(model),
                        remoteFiles = emptyList(),
                        preference = _uiState.value.preference
                    )
                }.getOrNull()
                var sessionsToPersist: List<ChatSessionRecord> = emptyList()
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
                        logs = engine.recentLogs(),
                        nativeStatsJson = engine.nativeStatsJson(),
                        statusMessage = buildString {
                            append("已加载：").append(model.displayName)
                            if (visionProjectorPath != null) append("，本地识图已启用")
                            append("。已使用当前参数，可直接聊天；需要优化时到 Agent 页手动运行智能调试。")
                        },
                        tab = AppTab.CHAT
                    )
                }
                persistChatSessions(sessionsToPersist)
            }.onFailure { error ->
                val nativeStats = engine.nativeStatsJson()
                fail("加载失败：${loadFailureAdvice(error.message, nativeStats)}；Native stats=$nativeStats")
            }
        }
    }

    fun verifyModel(model: ModelManifest) {
        viewModelScope.launch(Dispatchers.IO) {
            busy("正在校验 ${model.displayName}...")
            val result = modelStore.validateForLoad(model.id)
            _uiState.update {
                it.copy(
                    busy = false,
                    statusMessage = if (result.canLoad) "模型校验通过：${result.message}" else "模型校验失败：${result.message}"
                )
            }
        }
    }

    fun deleteModel(model: ModelManifest) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { modelStore.deleteModel(model.id) }
            _uiState.update {
                it.copy(models = modelStore.listModels(), statusMessage = "已删除：${model.displayName}")
            }
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
            val stream = if (state.selectedChatBackend == ChatBackend.CLOUD) {
                val cloudConfig = state.selectedChatCloudConfig()?.normalized()
                if (cloudConfig == null) {
                    appendAssistant("\n请先在模型管理 > 云端 加载一个对话推理模型。")
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "未加载云端推理模型") }
                    persistChatSessions()
                    return@launch
                }
                val cloudMessages = runCatching {
                    requestMessages.withInlineImageDataForCloud()
                }.getOrElse { error ->
                    appendAssistant("\n图片读取失败：${error.message ?: "无法读取图片"}")
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "图片读取失败") }
                    persistChatSessions()
                    return@launch
                }
                cloudChatProvider.streamChat(cloudConfig, ChatRequest(cloudMessages, requestParams))
            } else {
                if (hasImageAttachments && !localVisionRunnerAvailable()) {
                    appendAssistant(
                        "\n当前本地聊天模型未启用视觉识图。请加载本地多模态模型包（主 GGUF + 对应 mmproj/视觉投影器）后再试，或先切换到 MiMo v2.5 等云端多模态模型。"
                    )
                    _uiState.update { it.copy(isGenerating = false, statusMessage = "本地视觉 runner 未启用") }
                    persistChatSessions()
                    return@launch
                }
                val localMessages = if (hasImageAttachments) {
                    runCatching { requestMessages.withLocalImageFilesForVision() }
                        .getOrElse { error ->
                            appendAssistant("\n本地图片预处理失败：${error.message ?: "无法读取图片"}")
                            _uiState.update { it.copy(isGenerating = false, statusMessage = "本地图片预处理失败") }
                            persistChatSessions()
                            return@launch
                        }
                } else {
                    requestMessages
                }
                engine.streamChat(ChatRequest(localMessages, requestParams))
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
                        val citationAudit = applyWebSearchAnswerGuardsToLastAssistant()
                        if (citationAudit != null) {
                            recordWebSearchCitationAudit(webSearchTurn.diagnostic?.id, citationAudit)
                        }
                        _uiState.update {
                            it.copy(
                                isGenerating = false,
                                stats = event.stats,
                                logs = if (state.selectedChatBackend == ChatBackend.LOCAL) engine.recentLogs() else it.logs
                            )
                        }
                        persistChatSessions()
                    }
                    is GenerateEvent.Error -> {
                        appendAssistant("\n${event.message}")
                        _uiState.update { it.copy(isGenerating = false, stats = event.stats, statusMessage = event.message) }
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
            _uiState.update { it.copy(isGenerating = false, statusMessage = "已停止生成") }
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
            _uiState.update { it.copy(messages = emptyList(), input = "", activeChatSessionId = null, isGenerating = false, statusMessage = "已清空对话，上下文已重置") }
            persistChatSessions()
        }
    }

    fun onAppBackgrounded() {
        if (!_uiState.value.isGenerating) return
        viewModelScope.launch {
            engine.stopGeneration()
            generationJob?.cancel()
            _uiState.update {
                it.copy(
                    isGenerating = false,
                    statusMessage = "应用进入后台，已停止生成以降低发热和耗电。"
                )
            }
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
                agentLogger.append(device, recommendation)
                _uiState.update {
                    it.copy(
                        deviceProfile = device,
                        agentRecommendation = recommendation,
                        recommendedRemoteModels = sortRecommendedModels(modelScopeClient.recommendedModels(), device),
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
        val updatedParams = recommendation.tuningPlan.toGenerationParams(
            systemPrompt = state.params.systemPrompt,
            reasoningMode = state.params.reasoningMode,
            hideReasoning = state.params.hideReasoning
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
                statusMessage = "已应用 Agent 参数：n_ctx=${updatedParams.nCtx}, threads=${updatedParams.nThreads}"
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
                statusMessage = "已回退到上一次手动/加载前参数：n_ctx=${previous.nCtx}, threads=${previous.nThreads}"
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
            val currentParams = _uiState.value.params
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

    fun runAgentQuickDebug() {
        runAgentDebug(AgentDebugMode.Quick, _uiState.value.preference)
    }

    fun runAgentDeepDebug() {
        runAgentDebug(AgentDebugMode.Deep, _uiState.value.preference)
    }

    fun runAgentPowerDebug() {
        runAgentDebug(AgentDebugMode.PowerSave, _uiState.value.preference.copy(mode = PerformanceMode.PowerSave))
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
                val updatedParams = recommendation.tuningPlan.toGenerationParams(
                    systemPrompt = _uiState.value.params.systemPrompt,
                    reasoningMode = _uiState.value.params.reasoningMode,
                    hideReasoning = _uiState.value.params.hideReasoning
                )
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
            val updatedParams = recommendation.tuningPlan.toGenerationParams(
                systemPrompt = _uiState.value.params.systemPrompt,
                reasoningMode = _uiState.value.params.reasoningMode,
                hideReasoning = _uiState.value.params.hideReasoning
            )
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

    private fun persistImages(images: List<ImageAssetRecord> = _uiState.value.images) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatSessionStore.saveImages(images) }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(statusMessage = "图片库保存失败：${error.message}")
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
        imageAssetId: String? = null
    ): List<ImageGenerationJobRecord> =
        map { job ->
            if (job.id == jobId) {
                job.copy(
                    status = status,
                    message = message,
                    imageAssetId = imageAssetId ?: job.imageAssetId
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
        chatSessionId: String? = null
    ): ImageAssetRecord {
        val app = getApplication<Application>()
        val imageDir = File(app.filesDir, "image_assets").apply { mkdirs() }
        val result = cloudImageProvider.generate(config, prompt)
        val extension = imageExtensionForMime(result.mimeType)
        val outputFile = File(imageDir, "cloud-image-${System.currentTimeMillis()}.$extension")
        outputFile.writeBytes(result.bytes)
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
            chatSessionId = chatSessionId
        )
    }

    private suspend fun createLocalGeneratedImageAsset(
        prompt: String,
        model: LocalImageModelRecord,
        chatSessionId: String? = null,
        onProgress: (LocalImageProgress) -> Unit = {}
    ): ImageAssetRecord {
        val app = getApplication<Application>()
        val imageDir = File(app.filesDir, "image_assets").apply { mkdirs() }
        val result = localImageProvider.generate(model, prompt, onProgress)
        val extension = imageExtensionForMime(result.mimeType)
        val outputFile = File(imageDir, "local-image-${System.currentTimeMillis()}.$extension")
        outputFile.writeBytes(result.bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(outputFile.absolutePath, bounds)
        return ImageAssetRecord(
            id = UUID.randomUUID().toString(),
            name = "Local Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.$extension",
            uriString = Uri.fromFile(outputFile).toString(),
            source = "generated:${model.runtime.label}",
            prompt = prompt,
            sizeBytes = outputFile.length(),
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            chatSessionId = chatSessionId
        )
    }

    private fun createGeneratedImageAsset(prompt: String, chatSessionId: String? = null): ImageAssetRecord {
        val app = getApplication<Application>()
        val imageDir = File(app.filesDir, "image_assets").apply { mkdirs() }
        val outputFile = File(imageDir, "mca-image-${System.currentTimeMillis()}.png")
        val bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            1024f,
            1024f,
            intArrayOf(Color.rgb(34, 94, 168), Color.rgb(58, 154, 125), Color.rgb(244, 180, 78)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 1024f, 1024f, paint)
        paint.shader = null
        paint.color = Color.argb(235, 255, 255, 255)
        canvas.drawRoundRect(82f, 710f, 942f, 918f, 36f, 36f, paint)
        paint.color = Color.rgb(32, 33, 36)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textSize = 44f
        canvas.drawText("MCA Image", 122f, 770f, paint)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        paint.textSize = 30f
        drawWrappedText(canvas, prompt, 122f, 826f, 780f, 38f, paint, maxLines = 3)
        outputFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 96, output)
        }
        bitmap.recycle()
        return ImageAssetRecord(
            id = UUID.randomUUID().toString(),
            name = "MCA Image ${java.text.SimpleDateFormat("HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.png",
            uriString = Uri.fromFile(outputFile).toString(),
            source = "generated",
            prompt = prompt,
            sizeBytes = outputFile.length(),
            width = 1024,
            height = 1024,
            chatSessionId = chatSessionId
        )
    }

    private fun drawWrappedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float,
        lineHeight: Float,
        paint: Paint,
        maxLines: Int
    ) {
        var line = ""
        var lineY = y
        var lines = 0
        text.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { word ->
            val candidate = if (line.isBlank()) word else "$line $word"
            if (paint.measureText(candidate) <= maxWidth) {
                line = candidate
            } else {
                if (line.isNotBlank() && lines < maxLines) {
                    canvas.drawText(line, x, lineY, paint)
                    lineY += lineHeight
                    lines += 1
                }
                line = word
            }
        }
        if (line.isNotBlank() && lines < maxLines) {
            canvas.drawText(line, x, lineY, paint)
        }
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
        val json = prefs.getString("params_json", null) ?: return GenerationParams()
        return GenerationParams.fromJson(json)
    }

    private fun persistGenerationParams(params: GenerationParams) {
        getApplication<Application>()
            .getSharedPreferences("mca_generation_params", Context.MODE_PRIVATE)
            .edit()
            .putString("params_json", params.toJson())
            .apply()
    }

    private fun updatedAssistantsWithParams(params: GenerationParams): List<AssistantRecord> {
        val state = _uiState.value
        val selectedId = state.selectedAssistantId
        return state.assistants.map { assistant ->
            if (assistant.id == selectedId) {
                assistant.copy(
                    systemPrompt = params.systemPrompt,
                    paramsJson = params.toJson(),
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
        GenerationParams.fromJson(paramsJson, defaults).copy(systemPrompt = systemPrompt)

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
        val uri = Uri.parse(uriString)
        val resolver = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight, MAX_VISION_IMAGE_EDGE)
        val bitmap = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: error("无法读取图片：${name.ifBlank { uriString }}")
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

    private fun localVisionRunnerAvailable(): Boolean =
        runCatching {
            JSONObject(engine.nativeStatsJson()).optBoolean("visionReady", false)
        }.getOrDefault(false)

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

    private fun loadMemoryBlocker(model: ModelManifest): String? {
        val device = deviceProfileReader.read()
        val totalRam = device.displayTotalRamBytes
        val availableRam = device.availableRamBytes
        val modelBudget = device.modelMemoryBudgetBytes.takeIf { it > 0L }
            ?: maxOf(availableRam, (device.totalRamBytes * 0.70).toLong())
        val estimatedNeed = (model.sizeBytes * 1.18).toLong() + 768L * 1024L * 1024L
        return when {
            totalRam > 0L && estimatedNeed > (totalRam * 0.88).toLong() ->
                "模型约需 ${formatBytes(estimatedNeed)} 内存，已接近或超过本机总内存 ${formatBytes(totalRam)}。建议换更小的 Q4 模型。"
            device.isLowMemory ->
                "系统已进入低内存状态（可用约 ${formatBytes(availableRam)}），建议关闭后台应用后再加载。"
            estimatedNeed > modelBudget && availableRam < 2L * 1024L * 1024L * 1024L ->
                "当前系统可用内存约 ${formatBytes(availableRam)}，运行预算偏紧。建议关闭后台应用，或先用短基准确认。"
            else -> null
        }
    }

    private fun loadFailureAdvice(message: String?, nativeStats: String): String {
        val raw = listOfNotNull(message, nativeStats).joinToString(" ")
        return when {
            "no backends are loaded" in raw ->
                "llama.cpp 后端未加载。请安装最新 APK，并确认 native stats 里 backendReady=true。"
            "llama_init_from_model returned null" in raw || "out of memory" in raw.lowercase() ->
                "上下文或模型占用过高。建议降低 n_ctx，关闭后台应用，或换 1B/2B/4B Q4 模型。"
            "not readable" in raw ->
                "模型文件不可读。请重新校验/重新下载，确认文件仍在 APP 模型目录。"
            "invalid magic" in raw.lowercase() || "gguf" in raw.lowercase() && "failed" in raw.lowercase() ->
                "模型文件可能不完整或不是主模型 GGUF。请点“校验”，必要时删除后断点重下。"
            else -> message ?: "未知加载错误。请刷新诊断报告查看 native stats。"
        }
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
            .toString(2)
    }

    private enum class AgentDebugMode(val label: String) {
        Quick("快速调试"),
        Deep("深度调试"),
        PowerSave("省电调试")
    }

    private fun AgentDebugMode.sweepConfig(): BenchmarkSweepConfig = when (this) {
        AgentDebugMode.Quick -> BenchmarkSweepConfig.quick()
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
        val speedCandidates = listOf(
            1,
            2,
            3,
            4,
            5,
            6,
            7,
            8,
            bigCores - 1,
            bigCores,
            bigCores + 1,
            recommendedThreads ?: bigCores
        )
        val base = when (debugMode) {
            AgentDebugMode.Quick -> speedCandidates
            AgentDebugMode.Deep -> (1..cores).toList() + listOf(recommendedThreads ?: bigCores, bigCores - 1, bigCores, bigCores + 1)
            AgentDebugMode.PowerSave -> listOf(1, 2, 3, 4, bigCores.coerceAtMost(4), recommendedThreads?.coerceAtMost(4) ?: bigCores.coerceAtMost(4))
        }
        return base
            .map { it.coerceIn(1, cores) }
            .distinct()
            .filter { debugMode != AgentDebugMode.PowerSave || it <= 4 }
            .take(
                when (debugMode) {
                    AgentDebugMode.Quick -> 8
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

    private fun loadedModelJson(): String {
        val state = _uiState.value
        val nativeStats = runCatching { JSONObject(engine.nativeStatsJson()) }.getOrElse { JSONObject() }
        return JSONObject()
            .put("id", state.loadedModelId)
            .put("name", state.loadedModelName)
            .put("runtime", state.models.firstOrNull { it.id == state.loadedModelId }?.runtime?.storageValue)
            .put("stats", nativeStats)
            .toString()
    }

    private fun modelsJson(): String {
        val array = JSONArray()
        _uiState.value.models.forEach { model ->
            array.put(
                JSONObject()
                    .put("id", model.id)
                    .put("object", "model")
                    .put("owned_by", "local")
                    .put("display_name", model.displayName)
                    .put("runtime", model.runtime.storageValue)
                    .put("path", model.path)
            )
        }
        return JSONObject().put("object", "list").put("data", array).toString()
    }

    private fun currentDeviceProfile(): DeviceProfile =
        deviceProfileReader.read()

    private fun sortRecommendedModels(
        models: List<ModelScopeRecommendedModel>,
        device: DeviceProfile
    ): List<ModelScopeRecommendedModel> {
        val ramGb = device.displayTotalRamBytes / 1024.0 / 1024.0 / 1024.0
        return models.sortedWith(
            compareBy<ModelScopeRecommendedModel> { model ->
                model.group.ordinal
            }.thenByDescending { model ->
                if (model.kind == com.muyuchat.core.download.ModelScopeRecommendedKind.IMAGE) {
                    localImageDeviceFitScore(model, device)
                } else {
                    0
                }
            }.thenBy { model ->
                model.priority
            }.thenByDescending { model ->
                when {
                    ramGb <= 0.0 -> 0
                    model.minRamGb <= ramGb && model.minRamGb >= ramGb - 4.0 -> 4
                    model.minRamGb <= ramGb -> 3
                    model.minRamGb <= ramGb + 2.0 -> 2
                    else -> 1
                }
            }.thenBy { model ->
                kotlin.math.abs(model.minRamGb - ramGb)
            }
        )
    }

    private fun localImageDeviceFitScore(model: ModelScopeRecommendedModel, device: DeviceProfile): Int {
        val engineTier = model.localImageEngineTier ?: return 0
        val ramGb = device.displayTotalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val memoryFit = when {
            ramGb <= 0.0 -> 0
            model.minRamGb <= ramGb -> 4
            model.minRamGb <= ramGb + 2.0 -> 2
            else -> 0
        }
        val tierFit = when (engineTier) {
            LocalImageEngineTier.QUICK -> 5
            LocalImageEngineTier.STANDARD -> 4
            LocalImageEngineTier.COMPACT_QUALITY -> 3
            LocalImageEngineTier.LARGE_QUALITY -> 1
            LocalImageEngineTier.HEAVY_EXPERIMENTAL -> 0
        }
        return memoryFit + tierFit
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

private fun ModelRepositoryProvider.toModelSource(): ModelSource =
    when (this) {
        ModelRepositoryProvider.MODELSCOPE -> ModelSource.MODELSCOPE
        ModelRepositoryProvider.HUGGING_FACE -> ModelSource.HUGGING_FACE
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
