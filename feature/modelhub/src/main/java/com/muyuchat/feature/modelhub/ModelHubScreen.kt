package com.muyuchat.feature.modelhub

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RemoteModelFileKind
import com.muyuchat.core.download.fileKind
import com.muyuchat.core.download.isChatModelCandidate
import com.muyuchat.core.download.isImageModelCandidate
import com.muyuchat.core.download.isLiteRtLmModelCandidate
import com.muyuchat.core.download.isVisionModelCandidate
import com.muyuchat.core.download.kindLabel
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ModelHubUiState(
    val localModels: List<ModelManifest> = emptyList(),
    val mnnRuntimeAvailable: Boolean = false,
    val localImageModels: List<LocalImageModelUiItem> = emptyList(),
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
    val deviceTotalRamBytes: Long = 0L,
    val deviceAvailableRamBytes: Long = 0L,
    val deviceAccelerationSummary: String = "",
    val deviceImagePolicy: String = "",
    val deviceImageTier: String = "",
    val deviceChipsetCode: String = "",
    val deviceIsSnapdragon: Boolean = false,
    val qairtVerifiedLocalModelIds: Set<String> = emptySet(),
    val qairtVerifiedRecommendationIds: Set<String> = emptySet(),
    val cloudApi: CloudApiUiState = CloudApiUiState(),
    val isBusy: Boolean = false,
    val loadedModelId: String? = null,
    val statusMessage: String? = null
)

data class LocalImageModelUiItem(
    val id: String,
    val displayName: String,
    val runtimeLabel: String,
    val familyLabel: String,
    val fileName: String,
    val sizeBytes: Long,
    val imageSize: String,
    val componentCount: Int = 1,
    val readyForGeneration: Boolean = true,
    val readinessMessage: String? = null,
    val readinessLabel: String = "",
    val selected: Boolean = false
)

data class CloudApiUiState(
    val enabled: Boolean = false,
    val apiFormat: String = "OPENAI_COMPATIBLE",
    val availableFormats: List<Pair<String, String>> = listOf(
        "OPENAI_COMPATIBLE" to "OpenAI-compatible",
        "ANTHROPIC" to "Anthropic Messages"
    ),
    val providerName: String = "OpenAI-compatible",
    val displayName: String = "自定义推理引擎",
    val baseUrl: String = "",
    val apiKey: String = "",
    val chatModel: String = "",
    val supportsVision: Boolean = false,
    val imageApiFormat: String = "OPENAI_IMAGES",
    val availableImageFormats: List<Pair<String, String>> = listOf(
        "OPENAI_IMAGES" to "OpenAI Images",
        "DASHSCOPE_IMAGE" to "DashScope Image",
        "CUSTOM_PATH" to "Custom Image Path"
    ),
    val imageModel: String = "",
    val imageSize: String = "1024x1024",
    val imageEndpointPath: String = "images/generations",
    val imageModelPresets: List<String> = emptyList(),
    val imageSizePresets: List<String> = listOf("1024x1024", "1024x1536", "1536x1024"),
    val providerPresets: List<CloudProviderPresetUi> = emptyList(),
    val connectedModels: List<CloudModelUiItem> = emptyList(),
    val selected: Boolean = false,
    val configured: Boolean = false,
    val imageConfigured: Boolean = false,
    val imageSupported: Boolean = true
)

data class CloudProviderPresetUi(
    val key: String,
    val title: String,
    val subtitle: String
)

data class CloudModelUiItem(
    val id: String,
    val kind: String,
    val displayName: String,
    val providerName: String,
    val protocolLabel: String,
    val modelName: String,
    val baseUrl: String,
    val supportsVision: Boolean = false,
    val imageSize: String = "",
    val selected: Boolean = false
)

private enum class ModelHubSection(val title: String) {
    LOCAL("本地"),
    CLOUD("云端"),
    RECOMMENDED("推荐"),
    MARKET("广场"),
    FILES("文件")
}

private enum class LocalModelPendingAction {
    UNLOAD,
    DELETE
}

private data class PendingLocalModelAction(
    val modelId: String,
    val action: LocalModelPendingAction,
    val statusAtStart: String?
)

@Composable
fun ModelHubScreen(
    state: ModelHubUiState,
    onImportClick: () -> Unit,
    onRepoInputChange: (String) -> Unit,
    onFetchRemoteFiles: () -> Unit,
    onHubQueryChange: (String) -> Unit,
    onSearchHubModels: (Boolean) -> Unit,
    onFetchHubModelFiles: (ModelScopeHubModel) -> Unit,
    onShowRecommendedFiles: (ModelScopeRecommendedModel) -> Unit,
    onDownloadRecommended: (ModelScopeRecommendedModel) -> Unit,
    onOpenModelPage: (String) -> Unit,
    onDownload: (RemoteModelFile) -> Unit,
    onLoad: (ModelManifest) -> Unit,
    onUnload: (ModelManifest) -> Unit,
    onVerify: (ModelManifest) -> Unit,
    onDelete: (ModelManifest) -> Unit,
    onAttachVisionProjector: (ModelManifest) -> Unit,
    onImportLocalImageModel: () -> Unit,
    onSelectLocalImageModel: (String) -> Unit,
    onVerifyLocalImageModel: (String) -> Unit,
    onDeleteLocalImageModel: (String) -> Unit,
    onCloudEnabledChange: (Boolean) -> Unit,
    onBeginAddCloudModel: (String) -> Unit,
    onEditCloudModel: (String) -> Unit,
    onCloudProviderPreset: (String) -> Unit,
    onCloudFormatChange: (String) -> Unit,
    onCloudBaseUrlChange: (String) -> Unit,
    onCloudApiKeyChange: (String) -> Unit,
    onCloudChatModelChange: (String) -> Unit,
    onCloudSupportsVisionChange: (Boolean) -> Unit,
    onCloudImageFormatChange: (String) -> Unit,
    onCloudImageModelChange: (String) -> Unit,
    onCloudImageSizeChange: (String) -> Unit,
    onCloudImageEndpointPathChange: (String) -> Unit,
    onCloudDisplayNameChange: (String) -> Unit,
    onSaveCloudChatModel: () -> Unit,
    onSaveCloudImageModel: () -> Unit,
    onTestCloudApi: () -> Unit,
    onSelectCloudChat: (String) -> Unit,
    onSelectCloudImage: (String) -> Unit,
    onDeleteCloudModel: (String) -> Unit,
    onRefreshLocal: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable { mutableStateOf(ModelHubSection.LOCAL) }
    var cloudEditorKind by rememberSaveable { mutableStateOf<String?>(null) }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ModelHubHeader(
                state = state,
                selected = section,
                onSection = { section = it },
                onBack = onBack,
                onRefreshLocal = onRefreshLocal
            )

            when (section) {
                ModelHubSection.LOCAL -> LocalModelsSection(
                    state = state,
                    onImportClick = onImportClick,
                    onLoad = onLoad,
                    onUnload = onUnload,
                    onVerify = onVerify,
                    onDelete = onDelete,
                    onAttachVisionProjector = onAttachVisionProjector,
                    onImportLocalImageModel = onImportLocalImageModel,
                    onSelectLocalImageModel = onSelectLocalImageModel,
                    onVerifyLocalImageModel = onVerifyLocalImageModel,
                    onDeleteLocalImageModel = onDeleteLocalImageModel,
                    modifier = Modifier.weight(1f)
                )
                ModelHubSection.CLOUD -> CloudModelsSection(
                    state = state,
                    onBeginAddCloudModel = { kind ->
                        onBeginAddCloudModel(kind)
                        cloudEditorKind = kind
                    },
                    onEditCloudModel = { modelId ->
                        onEditCloudModel(modelId)
                        cloudEditorKind = state.cloudApi.connectedModels.firstOrNull { it.id == modelId }?.kind ?: "CHAT"
                    },
                    onSelectCloudChat = onSelectCloudChat,
                    onSelectCloudImage = onSelectCloudImage,
                    onDeleteCloudModel = onDeleteCloudModel,
                    modifier = Modifier.weight(1f)
                )
                ModelHubSection.RECOMMENDED -> RecommendedModelsSection(
                    state = state,
                    onShowFiles = { model ->
                        section = ModelHubSection.FILES
                        onShowRecommendedFiles(model)
                    },
                    onDownload = onDownloadRecommended,
                    onOpenPage = onOpenModelPage,
                    modifier = Modifier.weight(1f)
                )
                ModelHubSection.MARKET -> MarketSection(
                    state = state,
                    onHubQueryChange = onHubQueryChange,
                    onSearchHubModels = onSearchHubModels,
                    onShowFiles = { model ->
                        section = ModelHubSection.FILES
                        onFetchHubModelFiles(model)
                    },
                    onOpenPage = onOpenModelPage,
                    modifier = Modifier.weight(1f)
                )
                ModelHubSection.FILES -> RemoteFilesSection(
                    state = state,
                    onImportClick = onImportClick,
                    onRepoInputChange = onRepoInputChange,
                    onFetchRemoteFiles = onFetchRemoteFiles,
                    onDownload = onDownload,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        SmoothRightToLeftPage(
            visible = cloudEditorKind != null,
            onDismiss = { cloudEditorKind = null }
        ) { pageModifier, closePage ->
            CloudModelEditorPage(
                kind = cloudEditorKind ?: "CHAT",
                cloud = state.cloudApi,
                enabled = !state.isBusy,
                statusMessage = state.statusMessage,
                onBack = closePage,
                onEnabledChange = onCloudEnabledChange,
                onFormatChange = onCloudFormatChange,
                onImageFormatChange = onCloudImageFormatChange,
                onBaseUrlChange = onCloudBaseUrlChange,
                onApiKeyChange = onCloudApiKeyChange,
                onChatModelChange = onCloudChatModelChange,
                onSupportsVisionChange = onCloudSupportsVisionChange,
                onImageModelChange = onCloudImageModelChange,
                onImageSizeChange = onCloudImageSizeChange,
                onImageEndpointPathChange = onCloudImageEndpointPathChange,
                onDisplayNameChange = onCloudDisplayNameChange,
                onSaveChat = {
                    onSaveCloudChatModel()
                    closePage()
                },
                onSaveImage = {
                    onSaveCloudImageModel()
                    closePage()
                },
                onTest = onTestCloudApi,
                modifier = pageModifier
            )
        }
    }
}

@Composable
private fun SmoothRightToLeftPage(
    visible: Boolean,
    onDismiss: () -> Unit,
    content: @Composable (Modifier, () -> Unit) -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = 240),
            initialOffsetX = { it }
        ) + fadeIn(animationSpec = tween(durationMillis = 140)),
        exit = ExitTransition.None
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val scope = rememberCoroutineScope()
            val offsetX = remember { Animatable(0f) }

            LaunchedEffect(visible) {
                if (visible) offsetX.snapTo(0f)
            }

            fun closeWithMotion() {
                scope.launch {
                    offsetX.animateTo(
                        targetValue = widthPx,
                        animationSpec = tween(durationMillis = 180)
                    )
                    onDismiss()
                }
            }

            BackHandler(enabled = visible) {
                closeWithMotion()
            }

            val pageModifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }

            content(pageModifier, ::closeWithMotion)
        }
    }
}

@Composable
private fun ModelHubHeader(
    state: ModelHubUiState,
    selected: ModelHubSection,
    onSection: (ModelHubSection) -> Unit,
    onBack: () -> Unit,
    onRefreshLocal: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.Close, contentDescription = "返回聊天")
                }
                Column {
                    Text("模型管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("本地 / 云端 / 推荐 / 广场 / 文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefreshLocal, enabled = !state.isBusy) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新本地模型")
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(9.dp).size(20.dp)
                    )
                }
            }
        }

        state.statusMessage?.let {
            StatusMessageCard(message = it)
        }
        if (state.downloadFileName != null) {
            DownloadProgressPanel(state)
        } else if (state.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        ModelHubSegmentedTabs(selected = selected, onSection = onSection)
    }
}

@Composable
private fun ModelHubSegmentedTabs(
    selected: ModelHubSection,
    onSection: (ModelHubSection) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(2.dp)) {
            ModelHubSection.entries.forEach { item ->
                val active = selected == item
                Surface(
                    onClick = { onSection(item) },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            item.title,
                            textAlign = TextAlign.Center,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalModelsSection(
    state: ModelHubUiState,
    onImportClick: () -> Unit,
    onLoad: (ModelManifest) -> Unit,
    onUnload: (ModelManifest) -> Unit,
    onVerify: (ModelManifest) -> Unit,
    onDelete: (ModelManifest) -> Unit,
    onAttachVisionProjector: (ModelManifest) -> Unit,
    onImportLocalImageModel: () -> Unit,
    onSelectLocalImageModel: (String) -> Unit,
    onVerifyLocalImageModel: (String) -> Unit,
    onDeleteLocalImageModel: (String) -> Unit,
    modifier: Modifier
) {
    var pendingAction by remember { mutableStateOf<PendingLocalModelAction?>(null) }
    var pendingObservedBusy by remember { mutableStateOf(false) }
    var localActionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(
        state.isBusy,
        state.loadedModelId,
        state.localModels,
        state.statusMessage,
        pendingAction
    ) {
        val pending = pendingAction ?: return@LaunchedEffect
        if (state.isBusy) pendingObservedBusy = true
        val actionCompleted = when (pending.action) {
            LocalModelPendingAction.UNLOAD -> state.loadedModelId != pending.modelId
            LocalModelPendingAction.DELETE -> state.localModels.none { it.id == pending.modelId }
        }
        val statusCompleted = state.statusMessage != pending.statusAtStart && !state.isBusy
        val busyCompleted = pendingObservedBusy && !state.isBusy
        if (actionCompleted || statusCompleted || busyCompleted) {
            pendingAction = null
            pendingObservedBusy = false
        }
    }

    fun submitLocalModelAction(
        model: ModelManifest,
        action: LocalModelPendingAction,
        callback: () -> Unit
    ) {
        if (state.isBusy || pendingAction != null) return
        localActionError = null
        pendingObservedBusy = false
        pendingAction = PendingLocalModelAction(
            modelId = model.id,
            action = action,
            statusAtStart = state.statusMessage
        )
        runCatching(callback).onFailure { error ->
            pendingAction = null
            localActionError = "操作失败：${error.message ?: error::class.java.simpleName}"
        }
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CardBox {
                Text("本地推理引擎", fontWeight = FontWeight.Bold)
                Text("高速引擎优先使用 MNN；兼容引擎继续支持 GGUF / llama.cpp 生态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                localActionError?.let { StatusMessageCard(message = it) }
                Button(
                    onClick = onImportClick,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ 导入 GGUF / LiteRT-LM / MNN 本地模型", fontWeight = FontWeight.Bold)
                }
                if (state.localModels.isEmpty()) {
                    Text("还没有本地推理引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("可以导入 GGUF 或 LiteRT-LM 模型，也可以导入完整 MNN 组件包；不同格式会交给对应运行时加载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.localModels.forEach { model ->
                        LocalModelCard(
                            model = model,
                            isLoaded = model.id == state.loadedModelId,
                            mnnRuntimeAvailable = state.mnnRuntimeAvailable,
                            qairtVerified = model.id in state.qairtVerifiedLocalModelIds,
                            enabled = !state.isBusy && pendingAction == null,
                            pendingAction = pendingAction
                                ?.takeIf { it.modelId == model.id }
                                ?.action,
                            onLoad = { onLoad(model) },
                            onUnload = {
                                submitLocalModelAction(model, LocalModelPendingAction.UNLOAD) {
                                    onUnload(model)
                                }
                            },
                            onVerify = { onVerify(model) },
                            onDelete = {
                                submitLocalModelAction(model, LocalModelPendingAction.DELETE) {
                                    onDelete(model)
                                }
                            },
                            onAttachVisionProjector = { onAttachVisionProjector(model) }
                        )
                    }
                }
            }
        }
        item {
            CardBox {
                Text("图像生成引擎", fontWeight = FontWeight.Bold)
                Text("本地文生图模型独立管理，图片页会使用选中的引擎", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.localImageModels.isEmpty()) {
                    Text("还没有本地图像生成引擎", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("FLUX、Qwen-Image、Z-Image 等需要 zip 引擎包：diffusion 主模型 + VAE/AE + 文本编码器/LLM。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    state.localImageModels.forEach { model ->
                        LocalImageModelCard(
                            model = model,
                            enabled = !state.isBusy,
                            onSelect = { onSelectLocalImageModel(model.id) },
                            onVerify = { onVerifyLocalImageModel(model.id) },
                            onDelete = { onDeleteLocalImageModel(model.id) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = onImportLocalImageModel,
                    enabled = !state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("+ 导入本地生图引擎包", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun CloudModelsSection(
    state: ModelHubUiState,
    onBeginAddCloudModel: (String) -> Unit,
    onEditCloudModel: (String) -> Unit,
    onSelectCloudChat: (String) -> Unit,
    onSelectCloudImage: (String) -> Unit,
    onDeleteCloudModel: (String) -> Unit,
    modifier: Modifier
) {
    val chatModels = state.cloudApi.connectedModels.filter { it.kind == "CHAT" }
    val imageModels = state.cloudApi.connectedModels.filter { it.kind == "IMAGE" }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            CloudModelGroupCard(
                title = "云端推理引擎",
                subtitle = "已接入 ${chatModels.size} 个云端推理引擎",
                emptyTitle = "还没有云端推理引擎",
                emptyBody = "点击下方按钮选择 OpenAI 或 Anthropic 协议，再填写自定义 Base URL、模型名和 API Key。",
                models = chatModels,
                primaryAction = "加载",
                addAction = "+ 接入更多推理引擎",
                onAdd = { onBeginAddCloudModel("CHAT") },
                onPrimaryAction = onSelectCloudChat,
                onEdit = onEditCloudModel,
                onDelete = onDeleteCloudModel
            )
        }
        item {
            CloudModelGroupCard(
                title = "图像生成引擎",
                subtitle = "图片页会使用选中的图像生成引擎",
                emptyTitle = "还没有图像生成引擎",
                emptyBody = "图像生成引擎和云端推理引擎分开保存，支持 OpenAI Images、DashScope Image 和后续自定义路径。",
                models = imageModels,
                primaryAction = "设为当前",
                addAction = "+ 接入更多图像生成引擎",
                onAdd = { onBeginAddCloudModel("IMAGE") },
                onPrimaryAction = onSelectCloudImage,
                onEdit = onEditCloudModel,
                onDelete = onDeleteCloudModel
            )
        }
    }
}

@Composable
private fun CloudModelEditorPage(
    kind: String,
    cloud: CloudApiUiState,
    enabled: Boolean,
    statusMessage: String?,
    onBack: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onFormatChange: (String) -> Unit,
    onImageFormatChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onChatModelChange: (String) -> Unit,
    onSupportsVisionChange: (Boolean) -> Unit,
    onImageModelChange: (String) -> Unit,
    onImageSizeChange: (String) -> Unit,
    onImageEndpointPathChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onSaveChat: () -> Unit,
    onSaveImage: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isImage = kind == "IMAGE"
    val title = if (isImage) "接入图像生成引擎" else "接入云端推理引擎"
    val subtitle = if (isImage) {
        "图像生成引擎独立保存，用于图片页。"
    } else {
        "云端推理引擎用于普通聊天页。"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回云端模型")
                }
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (!enabled) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                CardBox {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("基本信息", fontWeight = FontWeight.Bold)
                            Text(
                                if (isImage) "填写图像模型名和可选显示名。" else "填写聊天模型名和可选显示名。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = cloud.enabled, onCheckedChange = onEnabledChange, enabled = enabled)
                    }
                    OutlinedTextField(
                        value = cloud.displayName,
                        onValueChange = onDisplayNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        label = { Text("显示名称") },
                        placeholder = { Text("可选，不填则使用模型名") }
                    )
                    OutlinedTextField(
                        value = if (isImage) cloud.imageModel else cloud.chatModel,
                        onValueChange = if (isImage) onImageModelChange else onChatModelChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled && (!isImage || cloud.imageSupported),
                        label = { Text(if (isImage) "图像模型名" else "推理模型名") },
                        placeholder = {
                            Text(
                                if (isImage) {
                                    imageModelPlaceholder(cloud.imageApiFormat)
                                } else {
                                    chatModelPlaceholder(cloud.apiFormat)
                                }
                            )
                        }
                    )
                    if (!isImage) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("支持图片输入", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "仅在当前云端模型确实支持多模态识图时开启。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = cloud.supportsVision,
                                    onCheckedChange = onSupportsVisionChange,
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardBox {
                    Text("协议", fontWeight = FontWeight.Bold)
                    Text(
                        if (isImage) "选择图像生成接口协议。" else "选择聊天推理接口协议。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isImage) {
                            items(cloud.availableImageFormats, key = { it.first }) { format ->
                                FilterChip(
                                    selected = cloud.imageApiFormat == format.first,
                                    onClick = { onImageFormatChange(format.first) },
                                    label = { Text(format.second) },
                                    enabled = enabled
                                )
                            }
                        } else {
                            items(cloud.availableFormats, key = { it.first }) { format ->
                                FilterChip(
                                    selected = cloud.apiFormat == format.first,
                                    onClick = { onFormatChange(format.first) },
                                    label = { Text(format.second) },
                                    enabled = enabled
                                )
                            }
                        }
                    }
                }
            }

            item {
                CardBox {
                    Text("接口信息", fontWeight = FontWeight.Bold)
                    Text(
                        "Base URL、API Key 和路径信息会保存在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = cloud.baseUrl,
                        onValueChange = onBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        label = { Text("Base URL") },
                        placeholder = {
                            Text(
                                if (isImage) imageBaseUrlPlaceholder(cloud.imageApiFormat) else chatBaseUrlPlaceholder(cloud.apiFormat)
                            )
                        }
                    )
                    if (isImage) {
                        OutlinedTextField(
                            value = cloud.imageEndpointPath,
                            onValueChange = onImageEndpointPathChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled && cloud.imageSupported,
                            label = { Text("图像路径") },
                            placeholder = { Text(imageEndpointPlaceholder(cloud.imageApiFormat)) },
                            supportingText = { Text("可以留空，MCA 会按当前协议补齐默认路径。") }
                        )
                        OutlinedTextField(
                            value = cloud.imageSize,
                            onValueChange = onImageSizeChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = enabled && cloud.imageSupported,
                            label = { Text("图片尺寸") },
                            placeholder = { Text(imageSizePlaceholder(cloud.imageApiFormat)) },
                            supportingText = { Text("可以留空，默认使用 1024x1024。") }
                        )
                    }
                    OutlinedTextField(
                        value = cloud.apiKey,
                        onValueChange = onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = enabled,
                        visualTransformation = PasswordVisualTransformation(),
                        label = { Text("API Key") },
                        placeholder = { Text("不需要密钥的本地转发服务可以留空") }
                    )
                }
            }

            item {
                val status = cloudDialogStatusMessage(statusMessage)
                if (status != null) {
                    StatusMessageCard(message = status)
                } else {
                    CardBox {
                        Text("状态反馈", fontWeight = FontWeight.Bold)
                        Text(
                            if (isImage) {
                                "保存后可在图片页用短提示词验证真实生图。图像生成会产生实际请求和费用，当前不做静默测试。"
                            } else {
                                "保存前建议先测试连接。测试结果会显示在这里，不再被弹窗遮挡。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                Spacer(Modifier.height(78.dp))
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isImage) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = onSaveImage,
                        enabled = enabled && cloud.imageConfigured,
                        modifier = Modifier.weight(1.45f).height(48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("保存并设为当前", maxLines = 1)
                    }
                } else {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = enabled && cloud.configured,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("测试")
                    }
                    Button(
                        onClick = onSaveChat,
                        enabled = enabled && cloud.configured,
                        modifier = Modifier.weight(1.45f).height(48.dp),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Text("保存并加载", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CloudModelGroupCard(
    title: String,
    subtitle: String,
    emptyTitle: String,
    emptyBody: String,
    models: List<CloudModelUiItem>,
    primaryAction: String,
    addAction: String,
    onAdd: () -> Unit,
    onPrimaryAction: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    CardBox {
        Text(title, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (models.isEmpty()) {
            Text(emptyTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(emptyBody, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            models.forEach { model ->
                CloudModelRow(
                    model = model,
                    primaryAction = primaryAction,
                    onPrimaryAction = onPrimaryAction,
                    onEdit = onEdit,
                    onDelete = onDelete
                )
            }
        }
        OutlinedButton(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(addAction, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CloudModelRow(
    model: CloudModelUiItem,
    primaryAction: String,
    onPrimaryAction: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var confirmDelete by rememberSaveable(model.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "模型：${model.modelName}",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (model.selected) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                            Text("当前", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                val meta = buildString {
                    append("协议：").append(model.protocolLabel)
                    if (model.imageSize.isNotBlank()) append(" · 尺寸：").append(model.imageSize)
                    if (model.kind == "CHAT" && model.supportsVision) append(" · 图片输入")
                }
                Text(meta, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(model.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onPrimaryAction(model.id) },
                        enabled = !model.selected,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(
                            imageVector = if (model.kind == "IMAGE") Icons.Default.Image else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (model.selected) "当前" else primaryAction, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { onEdit(model.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(999.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("编辑", maxLines = 1)
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除云端模型")
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除云端模型", fontWeight = FontWeight.Bold) },
            text = {
                Text("确定删除「${model.modelName}」吗？这只会移除 MCA 中保存的接入配置，不会影响云端服务商账号。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(model.id)
                    }
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun StatusMessageCard(message: String) {
    val isError = message.contains("失败") ||
        message.contains("错误") ||
        message.contains("无法") ||
        message.contains("未找到") ||
        message.contains("不能") ||
        message.contains("error", ignoreCase = true)
    val isSuccess = message.contains("成功") || message.contains("success", ignoreCase = true)
    val background = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isSuccess -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isSuccess -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            lineHeight = 18.sp
        )
    }
}

private fun cloudDialogStatusMessage(message: String?): String? {
    val clean = message?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return clean.takeIf {
        it.contains("云端 API") ||
            it.contains("快速测试") ||
            it.contains("请先") ||
            it.contains("失败") ||
            it.contains("错误") ||
            it.contains("成功") ||
            it.contains("Base URL", ignoreCase = true) ||
            it.contains("API Key", ignoreCase = true)
    }
}

private fun chatBaseUrlPlaceholder(format: String): String =
    when (format) {
        "ANTHROPIC" -> "例如 https://api.anthropic.com/v1"
        else -> "例如 https://api.example.com/v1"
    }

private fun chatModelPlaceholder(format: String): String =
    when (format) {
        "ANTHROPIC" -> "输入模型名，例如 your-anthropic-model"
        else -> "输入模型名，例如 your-chat-model"
    }

private fun imageBaseUrlPlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "例如 https://dashscope.aliyuncs.com"
        "CUSTOM_PATH" -> "例如 https://api.example.com/v1"
        else -> "例如 https://api.example.com/v1"
    }

private fun imageEndpointPlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "api/v1/services/aigc/multimodal-generation/generation"
        "CUSTOM_PATH" -> "例如 images/generations 或自建 image 路径"
        else -> "images/generations"
    }

private fun imageModelPlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "输入生图模型名，例如 your-image-model"
        else -> "输入生图模型名，例如 your-image-model"
    }

private fun imageSizePlaceholder(format: String): String =
    when (format) {
        "DASHSCOPE_IMAGE" -> "例如 1024*1024 或 1024x1024"
        else -> "例如 1024x1024"
    }

@Composable
private fun RecommendedModelsSection(
    state: ModelHubUiState,
    onShowFiles: (ModelScopeRecommendedModel) -> Unit,
    onDownload: (ModelScopeRecommendedModel) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier
) {
    val catalog = remember(
        state.recommendedRemoteModels,
        state.deviceChipsetCode,
        state.deviceIsSnapdragon,
        state.deviceTotalRamBytes
    ) {
        buildRecommendationCatalog(
            models = state.recommendedRemoteModels,
            deviceChipsetCode = state.deviceChipsetCode,
            deviceTotalRamBytes = state.deviceTotalRamBytes,
            deviceIsSnapdragon = state.deviceIsSnapdragon
        )
    }
    var expandedGroups by rememberSaveable(state.deviceChipsetCode) {
        mutableStateOf(emptyList<String>())
    }
    val cpuChatGroups = listOf(
        Triple("cpu-chat-light", "轻量档 · 4–8GB", catalog.lightChat),
        Triple("cpu-chat-main", "主力档 · 8–16GB", catalog.mainChat),
        Triple("cpu-chat-quality", "高质量档 · 12–24GB", catalog.qualityChat)
    )
    val npuImageGroups = listOf(
        Triple("npu-image-sd15", "SD1.5 QNN", catalog.npuImageSd15),
        Triple("npu-image-sdxl", "SDXL QNN", catalog.npuImageSdxl),
        Triple("npu-image-gen5", "骁龙 8 Elite Gen 5 官方模型", catalog.npuImageGen5)
    )
    val litertGroups = listOf(
        Triple("litert-cpu", "CPU", catalog.litertCpu),
        Triple("litert-gpu", "GPU", catalog.litertGpu),
        Triple("litert-npu", "Qualcomm NPU", catalog.litertNpu)
    )
    val hasRecommendations = cpuChatGroups.any { it.third.isNotEmpty() } ||
        catalog.npuChat.isNotEmpty() ||
        litertGroups.any { it.third.isNotEmpty() } ||
        catalog.cpuImage.isNotEmpty() ||
        catalog.npuImage.isNotEmpty()

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val ramGb = totalRamGb(state.deviceTotalRamBytes)
            val deviceLabel = recommendationDeviceLabel(state.deviceChipsetCode)
            Text(
                buildString {
                    append("当前设备：").append(deviceLabel)
                    if (ramGb > 0.0) append(" · ").append(ramGb.roundToInt()).append("GB")
                    append("。内存仅作建议，不限制下载；实验模型不会自动设为默认。")
                    append(EXPERIMENTAL_DOWNLOAD_NOTICE)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!hasRecommendations) {
            item { EmptyCard("暂无推荐模型", "稍后刷新推荐列表，或到广场搜索公开模型。") }
        } else {
            item(key = "cpu-chat-header") {
                RecommendationSectionHeader(
                    title = "CPU 图文聊天",
                    body = "本机通用路线，按内存分档；每档默认展示首选模型。"
                )
            }
            cpuChatGroups.forEach { (key, title, models) ->
                if (models.isNotEmpty()) {
                    item(key = "$key-header") {
                        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    val expanded = key in expandedGroups
                    val visibleModels = if (expanded) models else collapsedRecommendationModels(models)
                    items(visibleModels, key = { "$key-${it.id}" }) { model ->
                        RecommendedModelCard(
                            model = model,
                            deviceTotalRamBytes = state.deviceTotalRamBytes,
                            deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                            deviceChipsetCode = state.deviceChipsetCode,
                            deviceIsSnapdragon = state.deviceIsSnapdragon,
                            qairtVerified = model.id in state.qairtVerifiedRecommendationIds,
                            enabled = !state.isBusy,
                            onShowFiles = { onShowFiles(model) },
                            onDownload = { onDownload(model) },
                            onOpenPage = { onOpenPage(model.modelPageUrl) }
                        )
                    }
                    if (models.size > 1) {
                        item(key = "$key-more") {
                            RecommendationExpandButton(
                                expanded = expanded,
                                hiddenCount = models.size - 1,
                                onClick = {
                                    expandedGroups = expandedGroups.toggle(key, expanded)
                                }
                            )
                        }
                    }
                }
            }

            if (catalog.npuChat.isNotEmpty()) {
                item(key = "npu-chat-header") {
                    RecommendationSectionHeader(
                        title = "NPU 图文聊天",
                        body = "全部机型开放下载；芯片识别只用于优先选择 QAIRT 包，未知机型使用确定性兼容回退。"
                    )
                }
                val key = "npu-chat"
                val expanded = key in expandedGroups
                val visibleModels = if (expanded) catalog.npuChat else collapsedRecommendationModels(catalog.npuChat)
                items(visibleModels, key = { "$key-${it.id}" }) { model ->
                    RecommendedModelCard(
                        model = model,
                        deviceTotalRamBytes = state.deviceTotalRamBytes,
                        deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                        deviceChipsetCode = state.deviceChipsetCode,
                        deviceIsSnapdragon = state.deviceIsSnapdragon,
                        qairtVerified = model.id in state.qairtVerifiedRecommendationIds,
                        enabled = !state.isBusy,
                        onShowFiles = { onShowFiles(model) },
                        onDownload = { onDownload(model) },
                        onOpenPage = { onOpenPage(model.modelPageUrl) }
                    )
                }
                if (catalog.npuChat.size > 1) {
                    item(key = "$key-more") {
                        RecommendationExpandButton(
                            expanded = expanded,
                            hiddenCount = catalog.npuChat.size - 1,
                            onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                        )
                    }
                }
            }

            if (litertGroups.any { it.third.isNotEmpty() }) {
                item(key = "litert-lm-header") {
                    RecommendationSectionHeader(
                        title = "LiteRT-LM",
                        body = "LiteRT-LM 大类；下面按 CPU、GPU、Qualcomm NPU 分组，每组包含 E2B、E4B、12B。"
                    )
                }
                litertGroups.forEach { (key, title, models) ->
                    if (models.isNotEmpty()) {
                        item(key = "$key-header") {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        items(models, key = { "$key-${it.id}" }) { model ->
                            RecommendedModelCard(
                                model = model,
                                deviceTotalRamBytes = state.deviceTotalRamBytes,
                                deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                                deviceChipsetCode = state.deviceChipsetCode,
                                deviceIsSnapdragon = state.deviceIsSnapdragon,
                                qairtVerified = model.id in state.qairtVerifiedRecommendationIds,
                                enabled = !state.isBusy,
                                onShowFiles = { onShowFiles(model) },
                                onDownload = { onDownload(model) },
                                onOpenPage = { onOpenPage(model.modelPageUrl) }
                            )
                        }
                    }
                }
            }

            item(key = "cpu-image-header") {
                RecommendationSectionHeader(
                    title = "CPU 生图",
                    body = "所有机型均可下载；默认展示首选，其余实验模型折叠。"
                )
            }
            if (catalog.cpuImage.isEmpty()) {
                item(key = "cpu-image-empty") {
                    Text("暂无可展示的 CPU 生图实验模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val key = "cpu-image"
                val expanded = key in expandedGroups
                val visibleModels = if (expanded) catalog.cpuImage else collapsedRecommendationModels(catalog.cpuImage)
                items(visibleModels, key = { "$key-${it.id}" }) { model ->
                    RecommendedModelCard(
                        model = model,
                        deviceTotalRamBytes = state.deviceTotalRamBytes,
                        deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                        deviceChipsetCode = state.deviceChipsetCode,
                        deviceIsSnapdragon = state.deviceIsSnapdragon,
                        qairtVerified = model.id in state.qairtVerifiedRecommendationIds,
                        enabled = !state.isBusy,
                        onShowFiles = { onShowFiles(model) },
                        onDownload = { onDownload(model) },
                        onOpenPage = { onOpenPage(model.modelPageUrl) }
                    )
                }
                if (catalog.cpuImage.size > 1) {
                    item(key = "$key-more") {
                        RecommendationExpandButton(
                            expanded = expanded,
                            hiddenCount = catalog.cpuImage.size - 1,
                            collapsedLabel = "查看实验模型（${catalog.cpuImage.size - 1}）",
                            expandedLabel = "收起实验模型",
                            onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                        )
                    }
                }
            }

            if (catalog.npuImage.isNotEmpty()) {
                item(key = "npu-image-header") {
                    RecommendationSectionHeader(
                        title = "NPU 生图",
                        body = "全部机型开放下载和尝试运行；芯片识别只用于推荐合适包，不作为使用门槛。"
                    )
                }
                npuImageGroups.forEach { (key, title, models) ->
                    if (models.isNotEmpty()) {
                        item(key = "$key-header") {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        val expanded = key in expandedGroups
                        val visibleModels = if (expanded) models else collapsedRecommendationModels(models)
                        items(visibleModels, key = { "$key-${it.id}" }) { model ->
                            RecommendedModelCard(
                                model = model,
                                deviceTotalRamBytes = state.deviceTotalRamBytes,
                                deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                                deviceChipsetCode = state.deviceChipsetCode,
                                deviceIsSnapdragon = state.deviceIsSnapdragon,
                                qairtVerified = model.id in state.qairtVerifiedRecommendationIds,
                                enabled = !state.isBusy,
                                onShowFiles = { onShowFiles(model) },
                                onDownload = { onDownload(model) },
                                onOpenPage = { onOpenPage(model.modelPageUrl) }
                            )
                        }
                        if (models.size > 1) {
                            item(key = "$key-more") {
                                RecommendationExpandButton(
                                    expanded = expanded,
                                    hiddenCount = models.size - 1,
                                    onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendationSectionHeader(title: String, body: String) {
    Column(modifier = Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecommendationExpandButton(
    expanded: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit,
    collapsedLabel: String = "展开其他 $hiddenCount 个",
    expandedLabel: String = "收起"
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(if (expanded) expandedLabel else collapsedLabel)
    }
}

private fun List<String>.toggle(key: String, remove: Boolean): List<String> =
    if (remove) this - key else this + key

internal fun recommendationDeviceLabel(chipsetCode: String): String =
    if (chipsetCode.isBlank()) "通用 CPU"
    else DeviceAccelerationAnalyzer.publicChipsetDisplayName(chipsetCode)

@Composable
private fun MarketSection(
    state: ModelHubUiState,
    onHubQueryChange: (String) -> Unit,
    onSearchHubModels: (Boolean) -> Unit,
    onShowFiles: (ModelScopeHubModel) -> Unit,
    onOpenPage: (String) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("搜索魔塔公开模型。下载后仍保存到 MCA 的受管模型目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.hubQuery,
                    onValueChange = onHubQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索模型") }
                )
                IconButton(onClick = { onSearchHubModels(true) }, enabled = !state.isBusy) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }
        }
        if (state.hubModels.isNotEmpty()) {
            item { Text("已显示 ${state.hubModels.size}/${state.hubTotalCount} · 第 ${state.hubPage} 页", style = MaterialTheme.typography.bodySmall) }
            items(state.hubModels, key = { it.id }) { model ->
                HubModelCard(
                    model = model,
                    enabled = !state.isBusy,
                    onShowFiles = { onShowFiles(model) },
                    onOpenPage = { onOpenPage(model.modelPageUrl) }
                )
            }
            item {
                Button(
                    onClick = { onSearchHubModels(false) },
                    enabled = !state.isBusy && state.hubModels.size < state.hubTotalCount,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Text("下一页")
                }
            }
        }
    }
}

@Composable
private fun RemoteFilesSection(
    state: ModelHubUiState,
    onImportClick: () -> Unit,
    onRepoInputChange: (String) -> Unit,
    onFetchRemoteFiles: () -> Unit,
    onDownload: (RemoteModelFile) -> Unit,
    modifier: Modifier
) {
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Button(onClick = onImportClick, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(999.dp)) {
                Icon(Icons.Default.UploadFile, contentDescription = "导入本地推理引擎", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入本地推理引擎")
            }
        }
        item {
            Text("支持单个 GGUF / LiteRT-LM 模型，也支持多选完整 MNN 组件或导入 MNN zip 包。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.repoInput,
                    onValueChange = onRepoInputChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("模型 ID 或链接") }
                )
                IconButton(onClick = onImportClick, enabled = !state.isBusy) {
                    Icon(Icons.Default.Search, contentDescription = "查找本地推理文件")
                }
            }
        }
        if (state.remoteFiles.isEmpty()) {
            item { EmptyCard("暂无文件", "可从推荐或广场读取文件列表。") }
        } else {
            items(state.remoteFiles, key = { it.path }) { file ->
                RemoteFileCard(file = file, enabled = !state.isBusy, onDownload = { onDownload(file) })
            }
        }
    }
}

@Composable
private fun DownloadProgressPanel(state: ModelHubUiState) {
    val total = state.downloadTotalBytes
    val downloaded = state.downloadedBytes
    val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 350),
        label = "downloadProgress"
    )
    val percentText = if (total > 0L) "%.1f%%".format(progress * 100f) else "准备中"
    val totalText = if (total > 0L) formatBytes(total) else "未知大小"

    CardBox {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(state.downloadFileName.orEmpty(), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(percentText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        if (total > 0L) {
            LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text("${formatBytes(downloaded)} / $totalText · ${state.downloadStatus.downloadStatusLabel()}", style = MaterialTheme.typography.bodySmall)
        if (state.downloadSpeedBytesPerSecond > 0L || state.downloadRemainingSeconds != null) {
            Text(
                buildString {
                    if (state.downloadSpeedBytesPerSecond > 0L) append("速度 ").append(formatBytes(state.downloadSpeedBytesPerSecond)).append("/s")
                    state.downloadRemainingSeconds?.let { seconds ->
                        if (isNotEmpty()) append(" · ")
                        append("剩余约 ").append(formatDuration(seconds))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecommendedModelCard(
    model: ModelScopeRecommendedModel,
    deviceTotalRamBytes: Long,
    deviceAvailableRamBytes: Long,
    deviceChipsetCode: String,
    deviceIsSnapdragon: Boolean = false,
    qairtVerified: Boolean,
    enabled: Boolean,
    onShowFiles: () -> Unit,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit
) {
    val hasModelPage = !model.repoId.startsWith("pending/", ignoreCase = true)
    val downloadAccess = recommendationDownloadAccess(model, deviceChipsetCode, deviceIsSnapdragon)
    val fitLabel = deviceFitLabel(model, deviceTotalRamBytes, deviceAvailableRamBytes)
    val hardwareLine = recommendationHardwareLine(model, fitLabel)
    val devicePathLine = recommendationDeviceFitLine(downloadAccess)
    val verificationLine = recommendationVerificationLine(model, qairtVerified)
    val shortDescription = model.recommendationShortDescription()
    val experimentalDownload = model.status == RecommendedModelStatus.EXPERIMENTAL &&
        downloadAccess.canDownload
    val fitColor = if (fitLabel == "不建议本机运行") {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val verificationColor = when (model.status) {
        RecommendedModelStatus.EXPERIMENTAL -> MaterialTheme.colorScheme.secondary
        RecommendedModelStatus.PENDING_INTEGRATION,
        RecommendedModelStatus.NOT_RECOMMENDED -> MaterialTheme.colorScheme.error
        RecommendedModelStatus.RECOMMENDED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    CardBox {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                model.title,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            RecommendationStatusBadge(model.status)
        }
        Text(
            "${recommendedRouteLabel(model)} · ${model.parameterScale} · ${model.quant}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            hardwareLine,
            style = MaterialTheme.typography.bodySmall,
            color = fitColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            devicePathLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            verificationLine,
            style = MaterialTheme.typography.bodySmall,
            color = verificationColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            shortDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (downloadAccess.canDownload) {
            Text(
                RECOMMENDATION_DOWNLOAD_SOURCE_POLICY,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (experimentalDownload) {
                OutlinedButton(
                    onClick = onDownload,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(recommendationDownloadCtaLabel(model, canDownload = true, experimental = true))
                }
            } else {
                Button(
                    onClick = onDownload,
                    enabled = enabled && downloadAccess.canDownload,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        recommendationDownloadCtaLabel(
                            model,
                            canDownload = downloadAccess.canDownload,
                            experimental = downloadAccess.experimental
                        )
                    )
                }
            }
            OutlinedButton(onClick = onShowFiles, enabled = enabled && downloadAccess.canDownload, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                Text("文件")
            }
            IconButton(onClick = onOpenPage, enabled = hasModelPage) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = "打开页面")
            }
        }
    }
}

@Composable
private fun HubModelCard(
    model: ModelScopeHubModel,
    enabled: Boolean,
    onShowFiles: () -> Unit,
    onOpenPage: () -> Unit
) {
    CardBox {
        Text(shortName(model.displayName), fontWeight = FontWeight.Bold)
        Text("${formatBytes(model.fileSizeBytes)} · 下载 ${model.downloads} · 收藏 ${model.likes} · ${model.license ?: "未知许可"}", style = MaterialTheme.typography.bodySmall)
        val tags = model.tags.filter {
            it.contains("gguf", ignoreCase = true) ||
                it.contains("mnn", ignoreCase = true) ||
                it.startsWith("task:")
        }.take(3)
        if (tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag -> AssistChip(onClick = {}, label = { Text(tag.substringAfter(':')) }) }
            }
        }
        if (model.private || model.gated) {
            Text("该模型可能需要登录或访问授权。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShowFiles, enabled = enabled, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                Text("读取文件")
            }
            OutlinedButton(onClick = onOpenPage, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
                Text("打开页面")
            }
        }
    }
}

@Composable
private fun LegacyLocalModelCard(
    model: ModelManifest,
    isLoaded: Boolean,
    enabled: Boolean,
    onLoad: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
    onAttachVisionProjector: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(shortName(model.displayName), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (isLoaded) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                    Text("已加载", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        Text("${model.architecture ?: "未知架构"} · ${model.quant ?: "未知量化"} · ${formatBytes(model.sizeBytes)} · ${sourceLabel(model.source.name)}", style = MaterialTheme.typography.bodySmall)
        Text("已保存到 MCA 的本机模型目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = onLoad,
                enabled = enabled && !isLoaded,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isLoaded) "已加载" else "加载", maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = onVerify,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("校验", maxLines = 1, softWrap = false)
            }
            IconButton(onClick = onDelete, enabled = enabled, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "删除")
            }
        }
        }
    }
}

@Composable
private fun LocalModelCard(
    model: ModelManifest,
    isLoaded: Boolean,
    mnnRuntimeAvailable: Boolean,
    qairtVerified: Boolean,
    enabled: Boolean,
    pendingAction: LocalModelPendingAction?,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
    onAttachVisionProjector: () -> Unit
) {
    var confirmDelete by rememberSaveable(model.id) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val isMnnRuntime = model.runtime == ChatModelRuntime.MNN
            val isQairtRuntime = model.runtime == ChatModelRuntime.GENIEX_QAIRT
            val canLoadRuntime = !isMnnRuntime || mnnRuntimeAvailable
            val canNormalLoad = canLoadRuntime
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shortName(model.displayName),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLoaded) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                        Text(
                            "已加载",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Text(
                "${model.runtime.label} · ${model.architecture ?: "未知架构"} · ${model.quant ?: "未知量化"} · ${formatBytes(model.sizeBytes)} · ${sourceLabel(model.source.name)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "已保存到 MCA 的本地模型目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                when {
                    isMnnRuntime -> "MNN CPU 高速路径"
                    isQairtRuntime && qairtVerified -> "已有当前设备 QAIRT 隔离运行诊断证据"
                    isQairtRuntime -> "QAIRT 使用隔离 native worker；实际加载结果决定兼容性"
                    model.runtime == ChatModelRuntime.LITERT_LM -> "LiteRT-LM 独立运行时；CPU/GPU/NPU 由实际 native load 决定"
                    else -> "GGUF / llama.cpp 兼容路径"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                localVisionStatusText(model, isLoaded),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (model.hasVisionProjector) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (isMnnRuntime && !mnnRuntimeAvailable) {
                Text(
                    "当前 APK 未启用 MNN-LLM executor。请先使用 GGUF 兼容模型，或打包官方 MNN runtime 后再加载 MNN 模型。",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (isMnnRuntime) {
                Text(
                    "推荐模型优先走 MNN 高速引擎；GGUF / llama.cpp 继续作为兼容生态补充。",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (pendingAction != null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = when (pendingAction) {
                        LocalModelPendingAction.UNLOAD -> "正在卸载 ${shortName(model.displayName)}…"
                        LocalModelPendingAction.DELETE -> if (isLoaded) {
                            "正在卸载并删除 ${shortName(model.displayName)}…"
                        } else {
                            "正在删除 ${shortName(model.displayName)}…"
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { if (isLoaded) onUnload() else onLoad() },
                    enabled = enabled && (isLoaded || canNormalLoad),
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(
                        imageVector = if (isLoaded) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    if (isLoaded) {
                        Text("卸载", maxLines = 1, softWrap = false)
                    } else if (!canLoadRuntime) {
                        Text("引擎未启用", maxLines = 1, softWrap = false)
                    } else {
                        Text("加载", maxLines = 1, softWrap = false)
                    }
                }
                OutlinedButton(
                    onClick = onVerify,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (isQairtRuntime) "重新诊断" else "校验", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onAttachVisionProjector,
                    enabled = enabled,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (model.hasVisionProjector) "更换" else "绑定", maxLines = 1, softWrap = false)
                }
                IconButton(onClick = { confirmDelete = true }, enabled = enabled, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "删除本地模型")
                }
            }
        }
    }
    if (confirmDelete) {
        LocalModelDeleteDialog(
            model = model,
            isLoaded = isLoaded,
            enabled = enabled,
            onDismiss = { confirmDelete = false },
            onUnload = {
                confirmDelete = false
                onUnload()
            },
            onDelete = {
                confirmDelete = false
                onDelete()
            }
        )
    }
}

@Composable
private fun LocalModelDeleteDialog(
    model: ModelManifest,
    isLoaded: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        title = {
            Text(
                text = if (isLoaded) "卸载或删除模型" else "删除本地模型",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                if (isLoaded) {
                    "「${shortName(model.displayName)}」正在运行。可以只释放当前运行时，也可以在安全卸载后一并删除本地模型文件。"
                } else {
                    "确定删除「${shortName(model.displayName)}」吗？这会移除 MCA 中的模型记录和本地模型文件，且无法撤销。"
                }
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isLoaded) {
                    TextButton(onClick = onUnload, enabled = enabled) {
                        Text("仅卸载")
                    }
                }
                TextButton(
                    onClick = onDelete,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isLoaded) "卸载并删除" else "删除")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = enabled) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun RecommendationStatusBadge(status: RecommendedModelStatus) {
    val (containerColor, contentColor) = when (status) {
        RecommendedModelStatus.RECOMMENDED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        RecommendedModelStatus.EXPERIMENTAL ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        RecommendedModelStatus.PENDING_INTEGRATION ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        RecommendedModelStatus.NOT_RECOMMENDED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(color = containerColor, shape = RoundedCornerShape(999.dp)) {
        Text(
            recommendationStatusLabel(status),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor
        )
    }
}
@Composable
private fun LocalImageModelCard(
    model: LocalImageModelUiItem,
    enabled: Boolean,
    onSelect: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.displayName,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (model.selected) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(999.dp)) {
                        Text("当前", modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text(
                "${model.familyLabel} · ${model.runtimeLabel} · ${model.imageSize} · ${formatBytes(model.sizeBytes)}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                buildString {
                    append(model.fileName)
                    if (model.componentCount > 1) append(" · ").append(model.componentCount).append(" 个组件")
                    if (!model.readyForGeneration) append(" · ").append(model.readinessLabel.ifBlank { "不可用" })
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!model.readyForGeneration) {
                Text(
                    model.readinessMessage ?: "缺少本地生图组件包。",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                localImageExecutionLabel(model.runtimeLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSelect,
                    enabled = enabled && !model.selected && model.readyForGeneration,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            model.selected -> "当前"
                            !model.readyForGeneration -> model.readinessLabel.ifBlank { "不可用" }
                            else -> "设为生图"
                        },
                        maxLines = 1
                    )
                }
                OutlinedButton(
                    onClick = onVerify,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("校验", maxLines = 1)
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(Icons.Default.Delete, contentDescription = "删除本地图像生成引擎")
                }
            }
        }
    }
}

private fun localImageExecutionLabel(runtimeLabel: String): String {
    val lower = runtimeLabel.lowercase()
    return when {
        "qnn" in lower || "npu" in lower || "htp" in lower -> "骁龙 NPU 生图"
        "mnn" in lower -> "MNN 生图"
        "onnx" in lower -> "ONNX 生图"
        else -> "CPU 生图"
    }
}

@Composable
private fun RemoteFileCard(file: RemoteModelFile, enabled: Boolean, onDownload: () -> Unit) {
    val kind = file.fileKind()
    val isProjector = kind == RemoteModelFileKind.PROJECTOR
    val isLiteRtLm = file.isLiteRtLmModelCandidate()
    val isDownloadableModel =
        file.isChatModelCandidate() || file.isVisionModelCandidate() || file.isImageModelCandidate() || isProjector
    CardBox {
        Text(shortName(file.name), fontWeight = FontWeight.Bold)
        Text("${file.kindLabel()} · ${file.sizeBytes?.let(::formatBytes) ?: "未知大小"}", style = MaterialTheme.typography.bodySmall)
        Text("来源：${file.provider.label}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!isDownloadableModel) {
            Text("这是辅助文件，不适合作为推理引擎加载。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (isProjector) {
            Text("下载后会绑定到当前已加载的本地多模态主模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (isLiteRtLm) {
            Text("这是独立 LiteRT-LM 容器，不是 GGUF；下载后会按 LiteRT-LM 运行时注册。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onDownload, enabled = enabled && isDownloadableModel, shape = RoundedCornerShape(999.dp)) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    isProjector -> "下载并绑定"
                    isDownloadableModel -> "下载到本机"
                    else -> "辅助文件"
                }
            )
        }
    }
}

@Composable
private fun EmptyCard(title: String, body: String) {
    CardBox {
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    val mb = bytes / 1024.0 / 1024.0
    return if (gb >= 1.0) "%.2f GB".format(gb) else "%.1f MB".format(mb)
}

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

private fun shortName(value: String): String =
    value.substringAfterLast('/')
        .substringAfterLast('\\')
        .removeSuffix(".gguf")
        .removeSuffix(".mnn")
        .let { if (it.length > 36) it.take(33) + "..." else it }

private fun localVisionStatusText(model: ModelManifest, isLoaded: Boolean): String =
    when {
        model.runtime == ChatModelRuntime.MNN ->
            if (isLoaded) {
                "\u672c\u5730\u8bc6\u56fe\uff1aMNN \u591a\u6a21\u6001\u5305\u5df2\u52a0\u8f7d\uff1b\u5982\u5305\u5185\u542b visual.mnn\uff0c\u53ef\u76f4\u63a5\u53d1\u9001\u56fe\u7247\u3002"
            } else {
                "\u672c\u5730\u8bc6\u56fe\uff1aMNN \u591a\u6a21\u6001\u5305\u52a0\u8f7d\u540e\u53ef\u542f\u7528 visual.mnn\uff1b\u7eaf\u6587\u672c MNN \u4ecd\u53ea\u652f\u6301\u804a\u5929\u3002"
            }
        model.hasVisionProjector && isLoaded ->
            "\u672c\u5730\u8bc6\u56fe\uff1a\u5df2\u7ed1\u5b9a ${model.visionProjectorFileName ?: "mmproj"}\uff0c\u5982\u804a\u5929\u9875\u4ecd\u63d0\u793a\u672a\u5c31\u7eea\uff0c\u8bf7\u91cd\u65b0\u52a0\u8f7d\u6a21\u578b\u3002"
        model.hasVisionProjector ->
            "\u672c\u5730\u8bc6\u56fe\uff1a\u5df2\u7ed1\u5b9a ${model.visionProjectorFileName ?: "mmproj"}\uff0c\u52a0\u8f7d\u8be5\u6a21\u578b\u540e\u53ef\u53d1\u9001\u56fe\u7247\u3002"
        else ->
            "\u672c\u5730\u8bc6\u56fe\uff1a\u7eaf\u6587\u672c\u6a21\u578b\u4e0d\u80fd\u76f4\u63a5\u8bc6\u56fe\uff1b\u8bf7\u4f7f\u7528 MNN \u591a\u6a21\u6001\u5305\u6216\u7ed1\u5b9a\u5339\u914d mmproj\u3002"
    }

private fun sourceLabel(value: String): String = when (value.lowercase()) {
    "modelscope" -> "魔塔"
    "hugging_face", "huggingface", "hugging-face" -> "Hugging Face"
    "local" -> "本地"
    else -> "本机"
}

private fun DownloadStatus?.downloadStatusLabel(): String = when (this) {
    DownloadStatus.QUEUED -> "排队中"
    DownloadStatus.RUNNING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.FAILED -> "连接中断，等待续传"
    DownloadStatus.DONE -> "完成"
    null -> "准备中"
}

private fun totalRamGb(bytes: Long): Double = bytes / 1024.0 / 1024.0 / 1024.0

private fun recommendedRouteLabel(model: ModelScopeRecommendedModel): String {
    val imageBundle = model.imageEngineBundle
    val visionBundle = model.visionModelBundle
    return when {
        imageBundle != null -> imageBundle.runtimeSummary
        model.mnnModelBundle != null -> RecommendedRoute.MNN
        visionBundle != null -> visionBundle.runtimeSummary
        else -> model.chatRuntime.label
    }
}

private object RecommendedRoute {
    const val MNN = "MNN 高速引擎"
}

private fun deviceFitLabel(
    model: ModelScopeRecommendedModel,
    totalRamBytes: Long,
    availableRamBytes: Long
): String {
    val ramGb = totalRamGb(totalRamBytes)
    val availableGb = totalRamGb(availableRamBytes)
    return when {
        ramGb <= 0.0 -> "等待设备体检"
        model.minRamGb <= ramGb && availableGb >= 1.5 -> "适合本机"
        model.minRamGb <= ramGb -> "建议关闭后台"
        model.minRamGb <= ramGb + 2.0 -> "勉强可试"
        else -> "不建议本机运行"
    }
}
