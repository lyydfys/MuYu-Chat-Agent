@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.platform.LocalContext
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

enum class ModelHubDownloadPhase(val label: String) {
    QUEUED("排队中"),
    DOWNLOADING("下载中"),
    INSTALLING("安装中"),
    INTEGRITY_CHECK("校验文件"),
    EXECUTION_CHECK("检查运行"),
    COMPLETED("已完成")
}

enum class ModelHubDownloadFailureSource(val label: String) {
    DOWNLOAD("下载"),
    INSTALL("导入"),
    INTEGRITY("文件校验"),
    EXECUTION("运行检查")
}

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
    val downloadTaskId: String? = null,
    val downloadFileName: String? = null,
    val downloadedBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val downloadSpeedBytesPerSecond: Long = 0L,
    val downloadRemainingSeconds: Long? = null,
    val downloadStatus: DownloadStatus? = null,
    val downloadPhase: ModelHubDownloadPhase? = null,
    val downloadFailureSource: ModelHubDownloadFailureSource? = null,
    val downloadIntegrityStatus: String = "UNKNOWN",
    val downloadIntegrityMessage: String? = null,
    val downloadExecutionStatus: String = "UNKNOWN",
    val downloadExecutionMessage: String? = null,
    val importTasks: List<ModelImportTaskUi> = emptyList(),
    val deviceTotalRamBytes: Long = 0L,
    val deviceAvailableRamBytes: Long = 0L,
    val deviceAccelerationSummary: String = "",
    val deviceImagePolicy: String = "",
    val deviceImageTier: String = "",
    val deviceChipsetCode: String = "",
    val deviceSupportedAbis: List<String> = emptyList(),
    val deviceIsSnapdragon: Boolean = false,
    val qairtVerifiedLocalModelIds: Set<String> = emptySet(),
    val qairtVerifiedRecommendationIds: Set<String> = emptySet(),
    val cloudApi: CloudApiUiState = CloudApiUiState(),
    val isBusy: Boolean = false,
    val loadedModelId: String? = null,
    val statusMessage: String? = null
)

data class ModelImportTaskUi(
    val id: String,
    val message: String,
    val file: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    val active: Boolean = false,
    val resumable: Boolean = false
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
    val selected: Boolean = false,
    val storagePath: String = "",
    val recommendationId: String? = null,
    val verificationStatus: String = "UNKNOWN",
    val verificationMessage: String = ""
)

data class CloudApiUiState(
    val enabled: Boolean = false,
    val apiFormat: String = "OPENAI_COMPATIBLE",
    val availableFormats: List<Pair<String, String>> = listOf(
        "OPENAI_COMPATIBLE" to "OpenAI-compatible",
        "OPENAI_RESPONSES" to "OpenAI Responses",
        "ANTHROPIC" to "Anthropic Messages"
    ),
    val providerName: String = "OpenAI-compatible",
    val displayName: String = "自定义推理引擎",
    val baseUrl: String = "",
    val apiKey: String = "",
    val chatModel: String = "",
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val responsesReasoningEnabled: Boolean = false,
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
    val supportsTools: Boolean = false,
    val imageSize: String = "",
    val selected: Boolean = false
)

@Composable
private fun ModelImportProgressCard(task: ModelImportTaskUi, onPause: (String) -> Unit, onResume: (String) -> Unit) {
    CardBox {
        Text(task.message, style = MaterialTheme.typography.bodyMedium)
        task.file?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        if (task.active) {
            if (task.totalBytes > 0) {
                LinearProgressIndicator(progress = { (task.bytes.toDouble() / task.totalBytes).coerceIn(0.0, 1.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (task.active || task.resumable) {
            TextButton(onClick = { if (task.active) onPause(task.id) else onResume(task.id) }) {
                Text(if (task.active) "暂停导入" else "继续 / 重试导入")
            }
            Text("已完成组件经校验后可复用；未完成或源信息不明的组件重新复制。若源文件权限失效，请重新选择。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

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
    startInRecommended: Boolean = false,
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
    onOpenLocalModel: (String) -> Unit = {},
    onVerifyLocalModel: (String) -> Unit = {},
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
    onRemoveLocalImageModelRecord: (String) -> Unit = {},
    onCloudEnabledChange: (Boolean) -> Unit,
    onBeginAddCloudModel: (String) -> Unit,
    onEditCloudModel: (String) -> Unit,
    onCloudProviderPreset: (String) -> Unit,
    onCloudFormatChange: (String) -> Unit,
    onCloudBaseUrlChange: (String) -> Unit,
    onCloudApiKeyChange: (String) -> Unit,
    onCloudChatModelChange: (String) -> Unit,
    onCloudSupportsVisionChange: (Boolean) -> Unit,
    onCloudSupportsToolsChange: (Boolean) -> Unit = {},
    onCloudResponsesReasoningChange: (Boolean) -> Unit = {},
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
    onPauseDownloads: () -> Unit = {},
    onResumeDownloads: () -> Unit = {},
    onPauseImport: (String) -> Unit = {},
    onResumeImport: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var section by rememberSaveable(startInRecommended) {
        mutableStateOf(if (startInRecommended) ModelHubSection.RECOMMENDED else ModelHubSection.LOCAL)
    }
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
                onRefreshLocal = onRefreshLocal,
                onPauseDownloads = onPauseDownloads,
                onResumeDownloads = onResumeDownloads
            )

            if (startInRecommended && section == ModelHubSection.RECOMMENDED) {
                Text("下载完成后自动加入本地模型；是否可加载以完整性、兼容性和首次真实执行状态为准。", style = MaterialTheme.typography.bodySmall)
            }
            when (section) {
                ModelHubSection.LOCAL -> LocalModelsSection(
                    state = state,
                    onImportClick = onImportClick,
                    onPauseImport = onPauseImport,
                    onResumeImport = onResumeImport,
                    onLoad = onLoad,
                    onUnload = onUnload,
                    onVerify = onVerify,
                    onDelete = onDelete,
                    onAttachVisionProjector = onAttachVisionProjector,
                    onImportLocalImageModel = onImportLocalImageModel,
                    onSelectLocalImageModel = onSelectLocalImageModel,
                    onVerifyLocalImageModel = onVerifyLocalImageModel,
                    onDeleteLocalImageModel = onDeleteLocalImageModel,
                    onRemoveLocalImageModelRecord = onRemoveLocalImageModelRecord,
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
                    onOpenLocalModel = { modelId ->
                        section = ModelHubSection.LOCAL
                        onOpenLocalModel(modelId)
                    },
                    onVerifyLocalModel = onVerifyLocalModel,
                    onLoadLocalChatModel = { model ->
                        section = ModelHubSection.LOCAL
                        onLoad(model)
                    },
                    onVerifyLocalChatModel = onVerify,
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
                    onPauseImport = onPauseImport,
                    onResumeImport = onResumeImport,
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
                onSupportsToolsChange = onCloudSupportsToolsChange,
                onResponsesReasoningChange = onCloudResponsesReasoningChange,
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

            BackHandler(enabled = visible && !WindowInsets.isImeVisible) {
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
    onRefreshLocal: () -> Unit,
    onPauseDownloads: () -> Unit,
    onResumeDownloads: () -> Unit
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

        state.statusMessage?.let { message ->
            if (state.downloadFileName == null) {
                StatusMessageCard(message = message, isError = state.downloadStatus == DownloadStatus.FAILED)
            }
        }
        if (state.downloadFileName != null) {
            var downloadDetailsOpen by rememberSaveable(state.downloadTaskId ?: "download-task") {
                mutableStateOf(false)
            }
            DownloadProgressPanel(
                state = state,
                onShowDetails = { downloadDetailsOpen = true },
                onPauseDownloads = onPauseDownloads,
                onResumeDownloads = onResumeDownloads
            )
            if (downloadDetailsOpen) {
                DownloadDetailsDialog(
                    state = state,
                    onDismiss = { downloadDetailsOpen = false },
                    onOpenLocalModels = {
                        downloadDetailsOpen = false
                        onSection(ModelHubSection.LOCAL)
                    },
                    onResumeDownloads = onResumeDownloads
                )
            }
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
    onPauseImport: (String) -> Unit,
    onResumeImport: (String) -> Unit,
    onLoad: (ModelManifest) -> Unit,
    onUnload: (ModelManifest) -> Unit,
    onVerify: (ModelManifest) -> Unit,
    onDelete: (ModelManifest) -> Unit,
    onAttachVisionProjector: (ModelManifest) -> Unit,
    onImportLocalImageModel: () -> Unit,
    onSelectLocalImageModel: (String) -> Unit,
    onVerifyLocalImageModel: (String) -> Unit,
    onDeleteLocalImageModel: (String) -> Unit,
    onRemoveLocalImageModelRecord: (String) -> Unit,
    modifier: Modifier
) {
    var imageOnly by rememberSaveable { mutableStateOf(false) }
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

    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !imageOnly, onClick = { imageOnly = false }, label = { Text("聊天 (${state.localModels.size})") })
            FilterChip(selected = imageOnly, onClick = { imageOnly = true }, label = { Text("生图 (${state.localImageModels.size})") })
        }
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.importTasks, key = { "import-${it.id}" }) { task ->
            ModelImportProgressCard(task, onPauseImport, onResumeImport)
        }
        if (!imageOnly) {
            item {
                CardBox {
                Text("本地推理引擎", fontWeight = FontWeight.Bold)
                Text("高速引擎优先使用 MNN；兼容引擎继续支持 GGUF / llama.cpp 生态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                localActionError?.let { StatusMessageCard(message = it, isError = true) }
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
                    if (state.localModels.isEmpty()) Text("还没有本地推理引擎，可从推荐页下载或导入完整模型。")
                }
            }
            items(state.localModels, key = { "chat-${it.id}-${it.path}" }) { model ->
                        LocalModelCard(
                            model = model,
                            isLoaded = model.id == state.loadedModelId,
                            mnnRuntimeAvailable = state.mnnRuntimeAvailable,
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
        } else {
            item {
                CardBox {
                    Text("图像生成引擎", fontWeight = FontWeight.Bold)
                    Text("本地文生图模型独立管理，图片页会使用选中的引擎")
                    if (state.localImageModels.isEmpty()) Text("还没有本地图像生成引擎，可下载推荐包或导入完整引擎包。")
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
            items(state.localImageModels, key = { "image-${it.id}-${it.storagePath}" }) { model ->
                        LocalImageModelCard(
                            model = model,
                            enabled = !state.isBusy,
                            onSelect = { onSelectLocalImageModel(model.id) },
                            onVerify = { onVerifyLocalImageModel(model.id) },
                            onDelete = { onDeleteLocalImageModel(model.id) },
                            onRemoveRecord = { onRemoveLocalImageModelRecord(model.id) }
                        )
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
    onSupportsToolsChange: (Boolean) -> Unit,
    onResponsesReasoningChange: (Boolean) -> Unit = {},
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
                        if (cloud.apiFormat == "OPENAI_RESPONSES") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("支持工具调用", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "仅在此模型和服务端实际支持 Responses 工具调用时开启。开启后角色可请求生图；每次调用前仍会向你确认。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = cloud.supportsTools,
                                    onCheckedChange = onSupportsToolsChange,
                                    enabled = enabled
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("推理模型", fontWeight = FontWeight.SemiBold)
                                    Text("仅对支持 reasoning 的模型开启。开启后请求推理摘要，并停用温度与 Top P 参数。",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = cloud.responsesReasoningEnabled,
                                    onCheckedChange = onResponsesReasoningChange, enabled = enabled)
                            }
                        }
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
                    if (model.kind == "CHAT" && model.protocolLabel == "OpenAI Responses" && model.supportsTools) {
                        append(" · 工具调用")
                    }
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
private fun StatusMessageCard(message: String, isError: Boolean = false) {
    val background = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
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
        "OPENAI_RESPONSES" -> "例如 https://api.example.com/v1 或完整 /responses 地址"
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
    onOpenLocalModel: (String) -> Unit,
    onVerifyLocalModel: (String) -> Unit,
    onLoadLocalChatModel: (ModelManifest) -> Unit,
    onVerifyLocalChatModel: (ModelManifest) -> Unit,
    modifier: Modifier
) {
    val catalog = remember(
        state.recommendedRemoteModels,
        state.deviceChipsetCode,
        state.deviceSupportedAbis,
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
        catalog.gpuImage.isNotEmpty() ||
        catalog.npuImage.isNotEmpty()

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            val ramGb = totalRamGb(state.deviceTotalRamBytes)
            val deviceLabel = recommendationDeviceLabel(state.deviceChipsetCode)
            Text(
                buildString {
                    append("当前设备：").append(deviceLabel)
                    if (ramGb > 0.0) append(" · ").append(ramGb.roundToInt()).append("GB")
                    append("。请选择适合用途与内存的模型，下载后可在本地页管理。")
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
                            deviceSupportedAbis = state.deviceSupportedAbis,
                            deviceIsSnapdragon = state.deviceIsSnapdragon,
                            localModels = state.localModels,
                            qairtVerifiedLocalModelIds = state.qairtVerifiedLocalModelIds,
                            localImageModels = state.localImageModels,
                            onOpenLocalModel = onOpenLocalModel,
                            onVerifyLocalModel = onVerifyLocalModel,
                            onLoadLocalChatModel = onLoadLocalChatModel,
                            onVerifyLocalChatModel = onVerifyLocalChatModel,
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
                    body = "使用 Qualcomm NPU 运行聊天模型，默认展示首选，可展开更多模型。"
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
                                deviceSupportedAbis = state.deviceSupportedAbis,
                                deviceIsSnapdragon = state.deviceIsSnapdragon,
                                localModels = state.localModels,
                                qairtVerifiedLocalModelIds = state.qairtVerifiedLocalModelIds,
                                localImageModels = state.localImageModels,
                                onLoadLocalChatModel = onLoadLocalChatModel,
                                onVerifyLocalChatModel = onVerifyLocalChatModel,
                                onOpenLocalModel = onOpenLocalModel,
                                onVerifyLocalModel = onVerifyLocalModel,
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
                        val expanded = "$key-group" in expandedGroups
                        val visibleModels = if (expanded) models else collapsedRecommendationModels(models)
                        items(visibleModels, key = { "$key-${it.id}" }) { model ->
                            RecommendedModelCard(
                                model = model,
                                deviceTotalRamBytes = state.deviceTotalRamBytes,
                                deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                                deviceChipsetCode = state.deviceChipsetCode,
                                deviceSupportedAbis = state.deviceSupportedAbis,
                                deviceIsSnapdragon = state.deviceIsSnapdragon,
                                localModels = state.localModels,
                                qairtVerifiedLocalModelIds = state.qairtVerifiedLocalModelIds,
                                localImageModels = state.localImageModels,
                        onLoadLocalChatModel = onLoadLocalChatModel,
                        onVerifyLocalChatModel = onVerifyLocalChatModel,
                                onOpenLocalModel = onOpenLocalModel,
                                onVerifyLocalModel = onVerifyLocalModel,
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
                                    collapsedLabel = "查看其余模型（${models.size - 1}）",
                                    expandedLabel = "收起其余模型",
                                    onClick = { expandedGroups = expandedGroups.toggle("$key-group", expanded) }
                                )
                            }
                        }
                    }
                }
            }

            item(key = "cpu-image-header") {
                RecommendationSectionHeader(
                    title = "CPU 生图",
                    body = "在手机上生成或编辑图片，默认展示首选，可展开更多模型。"
                )
            }
            if (catalog.cpuImage.isEmpty()) {
                item(key = "cpu-image-empty") {
                    Text("暂无可展示的 CPU 生图模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        deviceSupportedAbis = state.deviceSupportedAbis,
                        deviceIsSnapdragon = state.deviceIsSnapdragon,
                        localModels = state.localModels,
                        localImageModels = state.localImageModels,
                        onLoadLocalChatModel = onLoadLocalChatModel,
                        onVerifyLocalChatModel = onVerifyLocalChatModel,
                        onOpenLocalModel = onOpenLocalModel,
                        onVerifyLocalModel = onVerifyLocalModel,
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
                            collapsedLabel = "查看更多模型（${catalog.cpuImage.size - 1}）",
                            expandedLabel = "收起模型",
                            onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                        )
                    }
                }
            }

            if (catalog.gpuImage.isNotEmpty()) {
                item(key = "gpu-image-header") {
                    RecommendationSectionHeader(
                        title = "GPU 生图",
                        body = "DiT 使用 OpenCL GPU；文本编码器和 VAE 可由 CPU 执行。"
                    )
                }
                val key = "gpu-image"
                val expanded = key in expandedGroups
                val visibleModels = if (expanded) catalog.gpuImage else collapsedRecommendationModels(catalog.gpuImage)
                items(visibleModels, key = { "$key-${it.id}" }) { model ->
                    RecommendedModelCard(
                        model = model,
                        deviceTotalRamBytes = state.deviceTotalRamBytes,
                        deviceAvailableRamBytes = state.deviceAvailableRamBytes,
                        deviceChipsetCode = state.deviceChipsetCode,
                        deviceSupportedAbis = state.deviceSupportedAbis,
                        deviceIsSnapdragon = state.deviceIsSnapdragon,
                        localModels = state.localModels,
                        localImageModels = state.localImageModels,
                        onLoadLocalChatModel = onLoadLocalChatModel,
                        onVerifyLocalChatModel = onVerifyLocalChatModel,
                        onOpenLocalModel = onOpenLocalModel,
                        onVerifyLocalModel = onVerifyLocalModel,
                        enabled = !state.isBusy,
                        onShowFiles = { onShowFiles(model) },
                        onDownload = { onDownload(model) },
                        onOpenPage = { onOpenPage(model.modelPageUrl) }
                    )
                }
                if (catalog.gpuImage.size > 1) {
                    item(key = "$key-more") {
                        RecommendationExpandButton(
                            expanded = expanded,
                            hiddenCount = catalog.gpuImage.size - 1,
                            collapsedLabel = "查看更多模型（${catalog.gpuImage.size - 1}）",
                            expandedLabel = "收起模型",
                            onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                        )
                    }
                }
            }

            if (catalog.npuImage.isNotEmpty()) {
            item(key = "npu-image-header") {
                RecommendationSectionHeader(
                    title = "NPU 生图",
                    body = recommendationNpuImageSectionDescription()
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
                                deviceSupportedAbis = state.deviceSupportedAbis,
                                deviceIsSnapdragon = state.deviceIsSnapdragon,
                                localModels = state.localModels,
                                localImageModels = state.localImageModels,
                                onLoadLocalChatModel = onLoadLocalChatModel,
                                onVerifyLocalChatModel = onVerifyLocalChatModel,
                                onOpenLocalModel = onOpenLocalModel,
                                onVerifyLocalModel = onVerifyLocalModel,
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
    onPauseImport: (String) -> Unit,
    onResumeImport: (String) -> Unit,
    onRepoInputChange: (String) -> Unit,
    onFetchRemoteFiles: () -> Unit,
    onDownload: (RemoteModelFile) -> Unit,
    modifier: Modifier
) {
    var fileFilter by rememberSaveable { mutableStateOf("") }
    val visibleFiles = filterRemoteModelFiles(state.remoteFiles, fileFilter)
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(state.importTasks, key = { "import-${it.id}" }) { task ->
            ModelImportProgressCard(task, onPauseImport, onResumeImport)
        }
        item {
            Button(onClick = onImportClick, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(999.dp)) {
                Icon(Icons.Default.UploadFile, contentDescription = "导入本地推理引擎", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("导入本地推理引擎")
            }
        }
        item {
            Text("支持 GGUF / LiteRT-LM 文件、MNN 完整目录或 ZIP。多选 MNN 文件时请包含配置引用的全部组件。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                IconButton(onClick = onFetchRemoteFiles, enabled = !state.isBusy) {
                    Icon(Icons.Default.Search, contentDescription = "查找远程推理文件")
                }
            }
        }
        if (state.remoteFiles.isNotEmpty()) {
            item {
                OutlinedTextField(
                    value = fileFilter,
                    onValueChange = { fileFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("筛选当前文件") },
                    placeholder = { Text("文件名、路径、类型或来源") },
                    trailingIcon = {
                        if (fileFilter.isNotEmpty()) {
                            IconButton(onClick = { fileFilter = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除筛选")
                            }
                        }
                    }
                )
            }
        }
        if (state.remoteFiles.isEmpty()) {
            item { EmptyCard("暂无文件", "可从推荐或广场读取文件列表。") }
        } else if (visibleFiles.isEmpty()) {
            item { EmptyCard("没有匹配文件", "清除筛选或换一个文件名、路径、类型关键词。") }
        } else {
            items(visibleFiles, key = { it.path }) { file ->
                RemoteFileCard(file = file, enabled = !state.isBusy, onDownload = { onDownload(file) })
            }
        }
    }
}

@Composable
private fun DownloadProgressPanel(
    state: ModelHubUiState,
    onShowDetails: () -> Unit,
    onPauseDownloads: () -> Unit,
    onResumeDownloads: () -> Unit
) {
    val total = state.downloadTotalBytes
    val downloaded = state.downloadedBytes
    val progress = if (total > 0L) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 350),
        label = "downloadProgress"
    )
    val percentText = when (state.downloadStatus) {
        DownloadStatus.FAILED -> "失败"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.DONE -> "完成"
        else -> when (state.downloadPhase) {
            ModelHubDownloadPhase.INSTALLING,
            ModelHubDownloadPhase.INTEGRITY_CHECK,
            ModelHubDownloadPhase.EXECUTION_CHECK -> state.downloadPhase?.label ?: "处理中"
            else -> if (total > 0L) "%.1f%%".format(progress * 100f) else "下载中"
        }
    }
    val totalText = if (total > 0L) formatBytes(total) else "未知大小"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 3.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    state.downloadFileName.orEmpty(),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(percentText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                if (state.downloadStatus == DownloadStatus.RUNNING || state.downloadStatus == DownloadStatus.QUEUED) {
                    TextButton(
                        onClick = onPauseDownloads,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text("暂停", style = MaterialTheme.typography.labelMedium) }
                }
                if (state.downloadStatus == DownloadStatus.PAUSED || state.downloadStatus == DownloadStatus.FAILED) {
                    TextButton(
                        onClick = onResumeDownloads,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) { Text(if (state.downloadStatus == DownloadStatus.FAILED) "重试" else "继续",
                        style = MaterialTheme.typography.labelMedium) }
                }
                IconButton(
                    onClick = onShowDetails,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "查看下载详情",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            if (total > 0L || state.downloadStatus == DownloadStatus.FAILED ||
                state.downloadStatus == DownloadStatus.PAUSED || state.downloadStatus == DownloadStatus.DONE
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth().height(2.dp)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
            val transferSummary = buildString {
                if (state.downloadSpeedBytesPerSecond > 0L) {
                    append(formatBytes(state.downloadSpeedBytesPerSecond)).append("/s")
                }
                state.downloadRemainingSeconds?.let { seconds ->
                    if (isNotEmpty()) append(" · ")
                    append("剩余约 ").append(formatDuration(seconds))
                }
            }
            if (transferSummary.isNotEmpty() && state.downloadStatus == DownloadStatus.RUNNING) {
                Text(
                    transferSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.downloadStatus == DownloadStatus.FAILED) {
                Text(
                    listOfNotNull(state.downloadFailureSource?.label, state.statusMessage)
                        .joinToString("：").ifBlank { "下载失败，请查看详情后重试。" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DownloadDetailsDialog(
    state: ModelHubUiState,
    onDismiss: () -> Unit,
    onOpenLocalModels: () -> Unit,
    onResumeDownloads: () -> Unit
) {
    val context = LocalContext.current
    val total = state.downloadTotalBytes
    val downloaded = state.downloadedBytes
    val totalText = if (total > 0L) formatBytes(total) else "未知大小"
    val stageLabel = if (state.downloadStatus == DownloadStatus.RUNNING || state.downloadStatus == DownloadStatus.QUEUED) {
        state.downloadPhase?.label ?: state.downloadStatus.downloadStatusLabel()
    } else {
        state.downloadStatus.downloadStatusLabel()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                state.downloadFileName.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.heightIn(min = 164.dp, max = 280.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$stageLabel · 已下载 ${formatBytes(downloaded)} / $totalText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.downloadStatus == DownloadStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                if (state.downloadSpeedBytesPerSecond > 0L || state.downloadRemainingSeconds != null) {
                    Text(
                        buildString {
                            if (state.downloadSpeedBytesPerSecond > 0L) {
                                append(formatBytes(state.downloadSpeedBytesPerSecond)).append("/s")
                            }
                            state.downloadRemainingSeconds?.let { seconds ->
                                if (isNotEmpty()) append(" · ")
                                append("剩余约 ").append(formatDuration(seconds))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DownloadCheckLine("文件完整性", state.downloadIntegrityStatus, state.downloadIntegrityMessage)
                DownloadCheckLine("本机执行兼容性", state.downloadExecutionStatus, state.downloadExecutionMessage)
                if (state.downloadStatus == DownloadStatus.FAILED) {
                    state.statusMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (state.downloadStatus == DownloadStatus.FAILED || state.downloadStatus == DownloadStatus.PAUSED) {
                TextButton(onClick = onResumeDownloads) {
                    Text(if (state.downloadStatus == DownloadStatus.FAILED) "重试" else "继续")
                }
            } else if (state.downloadStatus == DownloadStatus.DONE && !state.isBusy) {
                TextButton(onClick = onOpenLocalModels) { Text("查看本地模型") }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("模型下载诊断", downloadDiagnostics(state)))
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("复制诊断")
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun downloadDiagnostics(state: ModelHubUiState): String = buildString {
    appendLine("任务: ${state.downloadTaskId ?: "未知"}")
    appendLine("文件: ${state.downloadFileName ?: "未知"}")
    appendLine("状态: ${state.downloadStatus ?: "UNKNOWN"}")
    appendLine("阶段: ${state.downloadPhase ?: "UNKNOWN"}")
    state.downloadFailureSource?.let { appendLine("失败来源: $it") }
    appendLine("进度: ${state.downloadedBytes}/${state.downloadTotalBytes} bytes")
    appendLine("文件完整性: ${state.downloadIntegrityStatus}: ${state.downloadIntegrityMessage.orEmpty()}")
    appendLine("本机执行: ${state.downloadExecutionStatus}: ${state.downloadExecutionMessage.orEmpty()}")
    state.statusMessage?.let { appendLine("说明: $it") }
}

@Composable
private fun DownloadCheckLine(title: String, status: String, message: String?) {
    val normalized = status.uppercase()
    // Missing acceptance/metadata alone is internal state, not a user warning.
    if (normalized == "EXPERIMENTAL" && message.isNullOrBlank()) return
    val label = when (normalized) {
        "PASSED" -> "通过"
        "PREFLIGHT_PASSED" -> "预检通过"
        "FAILED" -> "失败"
        "PENDING" -> "检查中"
        "RUNNING" -> "校验中"
        "EXPERIMENTAL" -> "运行提示"
        "NOT_APPLICABLE" -> "不适用"
        else -> "待检查"
    }
    val color = when (normalized) {
        "FAILED" -> MaterialTheme.colorScheme.error
        "PASSED", "PREFLIGHT_PASSED" -> MaterialTheme.colorScheme.primary
        "EXPERIMENTAL" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        buildString {
            append(title).append("：").append(label)
            message?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        },
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun RecommendedModelCard(
    model: ModelScopeRecommendedModel,
    localModels: List<ModelManifest> = emptyList(),
    localImageModels: List<LocalImageModelUiItem> = emptyList(),
    qairtVerifiedLocalModelIds: Set<String> = emptySet(),
    deviceTotalRamBytes: Long,
    deviceAvailableRamBytes: Long,
    deviceChipsetCode: String,
    deviceSupportedAbis: List<String> = emptyList(),
    deviceIsSnapdragon: Boolean = false,
    enabled: Boolean,
    onShowFiles: () -> Unit,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
    onOpenLocalModel: (String) -> Unit = {},
    onVerifyLocalModel: (String) -> Unit = {},
    onLoadLocalChatModel: (ModelManifest) -> Unit = {},
    onVerifyLocalChatModel: (ModelManifest) -> Unit = {}
) {
    val hasModelPage = !model.repoId.startsWith("pending/", ignoreCase = true)
    val downloadAccess = recommendationDownloadAccess(
        model,
        deviceChipsetCode,
        deviceIsSnapdragon,
        deviceSupportedAbis
    )
    val fitLabel = deviceFitLabel(model, deviceTotalRamBytes, deviceAvailableRamBytes)
    val hardwareLine = recommendationHardwareLine(model, fitLabel)
    val qnnCompatibilityLine = recommendationQnnCompatibilityLine(model, deviceChipsetCode)
    val runtimeCompatibilityLine = recommendationRuntimeCompatibilityLine(model, deviceSupportedAbis)
    val shortDescription = model.recommendationShortDescription()
    val imageSizeLine = recommendationImageSizeLine(model)
    var detailsExpanded by rememberSaveable(model.id) { mutableStateOf(false) }
    val downloadSize = recommendationDownloadSizeBytes(model)
    val localImageModel = localImageModels.firstOrNull { local ->
        local.recommendationId == model.id || local.id == model.imageEngineBundle?.id
    }
    val localChatModel = recommendedLocalChatModel(model, localModels)
    val localImageVerificationPassed = localImageModel != null && localImageModel.verificationStatus in setOf(
        "PASSED", "MNN_SMOKE_PASSED", "QNN_IMAGE_SMOKE_PASSED", "QNN_SMOKE_PASSED", "QNN_PIPELINE_PROBE_PASSED"
    )
    val localChatVerificationPassed = localChatModel != null && (
        localChatModel.runtime != ChatModelRuntime.GENIEX_QAIRT ||
            localChatModel.id in qairtVerifiedLocalModelIds
    )
    val localStatusLine = when {
        localImageModel != null -> localImageModel.let {
        when {
            localImageVerificationPassed -> "已安装 · ${formatBytes(it.sizeBytes)}"
            it.verificationStatus == "FAILED" -> "校验失败 · ${it.verificationMessage.ifBlank { "请重新校验" }}"
            else -> "已安装 · ${formatBytes(it.sizeBytes)}"
        }
        }
        localChatModel != null -> when {
            localChatModel.runtime == ChatModelRuntime.GENIEX_QAIRT &&
                localChatModel.id !in qairtVerifiedLocalModelIds ->
                "已安装 · 待本机 QNN 诊断 · ${formatBytes(localChatModel.sizeBytes)}"
            else -> "已安装 · ${formatBytes(localChatModel.sizeBytes)}"
        }
        else -> null
    }
    val fitColor = if (fitLabel == "低于建议内存") {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    CardBox {
        Text(model.title, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Text(
            shortDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            recommendationSpecificationLine(model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "后端：${recommendedRouteLabel(model)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        runtimeCompatibilityLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        qnnCompatibilityLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "功能：${recommendationCapabilityLine(model)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        imageSizeLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (detailsExpanded) 8 else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            hardwareLine,
            style = MaterialTheme.typography.bodySmall,
            color = fitColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            downloadSize?.let { "下载大小：约 ${formatBytes(it)}" } ?: "下载大小：见文件列表",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        localStatusLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (localImageVerificationPassed || localChatVerificationPassed) {
                    MaterialTheme.colorScheme.primary
                } else MaterialTheme.colorScheme.secondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!downloadAccess.canDownload) {
            Text(
                recommendationDownloadBlockLine(model.downloadBlockReason),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) "收起介绍" else "完整介绍")
        }
        if (detailsExpanded) {
            Text(model.description, style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (localImageModel != null) {
                OutlinedButton(
                    onClick = {
                        if (localImageVerificationPassed) onOpenLocalModel(localImageModel.id)
                        else onVerifyLocalModel(localImageModel.id)
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(
                        if (localImageVerificationPassed) Icons.Default.Image else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            localImageVerificationPassed -> "打开本地模型"
                            localImageModel.verificationStatus == "FAILED" -> "校验失败 · 重试"
                            else -> "已安装 · 重新校验"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else if (localChatModel != null) {
                OutlinedButton(
                    onClick = {
                        if (localChatVerificationPassed) onLoadLocalChatModel(localChatModel)
                        else onVerifyLocalChatModel(localChatModel)
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(
                        if (localChatVerificationPassed) Icons.Default.PlayArrow else Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (localChatVerificationPassed) "打开本地模型" else "已安装 · 重新校验",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                        recommendationDownloadCtaLabel(canDownload = downloadAccess.canDownload)
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
            ModelStoragePath(model.path)
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
                    isMnnRuntime -> "MNN · CPU / OpenCL GPU"
                    isQairtRuntime -> "GenieX QAIRT · Qualcomm NPU"
                    model.runtime == ChatModelRuntime.LITERT_LM -> "LiteRT-LM · CPU / GPU / Qualcomm NPU，需对应后端的模型文件"
                    else -> "GGUF · llama.cpp"
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
private fun LocalImageModelCard(
    model: LocalImageModelUiItem,
    enabled: Boolean,
    onSelect: () -> Unit,
    onVerify: () -> Unit,
    onDelete: () -> Unit,
    onRemoveRecord: () -> Unit
) {
    var confirmRemoval by remember { mutableStateOf(false) }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("移除本地图像模型") },
            text = { Text(model.displayName) },
            confirmButton = {
                TextButton(onClick = { confirmRemoval = false; onRemoveRecord() }) {
                    Text("仅移除记录")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmRemoval = false; onDelete() }) {
                        Text("删除文件")
                    }
                    TextButton(onClick = { confirmRemoval = false }) { Text("取消") }
                }
            }
        )
    }
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
            ModelStoragePath(model.storagePath)
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
                IconButton(onClick = { confirmRemoval = true }, enabled = enabled) {
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
    DownloadStatus.FAILED -> "失败，请查看原因并重试"
    DownloadStatus.DONE -> "已导入本地模型"
    null -> "准备中"
}

private fun totalRamGb(bytes: Long): Double = bytes / 1024.0 / 1024.0 / 1024.0

private fun recommendedRouteLabel(model: ModelScopeRecommendedModel): String {
    val imageBundle = model.imageEngineBundle
    val visionBundle = model.visionModelBundle
    return when {
        imageBundle != null -> imageBundle.runtimeSummary
        model.mnnModelBundle != null -> "MNN · CPU / OpenCL GPU"
        visionBundle != null -> visionBundle.runtimeSummary
        model.chatRuntime == com.muyuchat.core.download.RecommendedChatRuntime.LITERT_LM ->
            "LiteRT-LM · ${model.computeBackend.label}"
        model.chatRuntime == com.muyuchat.core.download.RecommendedChatRuntime.GGUF -> "llama.cpp · CPU"
        else -> model.chatRuntime.label
    }
}

private fun deviceFitLabel(
    model: ModelScopeRecommendedModel,
    totalRamBytes: Long,
    availableRamBytes: Long
): String {
    val ramGb = totalRamGb(totalRamBytes)
    val availableGb = totalRamGb(availableRamBytes)
    return when {
        ramGb <= 0.0 -> "按设备内存选择"
        model.minRamGb <= ramGb && availableGb >= 1.5 -> "达到建议内存"
        model.minRamGb <= ramGb -> "当前空闲内存较少"
        else -> "低于建议内存"
    }
}

@Composable
private fun ModelStoragePath(path: String) {
    if (path.isBlank()) return
    var expanded by rememberSaveable(path) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起保存位置" else "查看保存位置") }
    if (expanded) SelectionContainer {
        Text(path, style = MaterialTheme.typography.bodySmall)
    }
}
