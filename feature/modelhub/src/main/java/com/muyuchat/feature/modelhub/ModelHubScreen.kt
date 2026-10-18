@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.SmoothRightToLeftPage
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberEnterVisibility

private enum class ModelHubSection(val title: String) {
    LOCAL("本地"),
    CLOUD("云端"),
    RECOMMENDED("推荐"),
    MARKET("广场"),
    FILES("文件")
}

internal data class ModelHubHeaderPresentation(
    val downloadSummary: ModelHubUiState?,
    val downloadSummaryKey: String?,
    val canDismissDownloadSummary: Boolean,
    val statusMessage: String?,
    val statusIsError: Boolean
)

/** Status copy plus its severity, derived once per status value instead of per recomposition. */
internal data class ModelHubStatusPresentation(
    val message: String?,
    val isError: Boolean
)

private val MODEL_HUB_ERROR_STATUS_MARKERS = listOf(
    "失败",
    "错误",
    "异常",
    "无法",
    "不可用",
    "不兼容",
    "未通过",
    "缺失",
    "缺少",
    "损坏",
    "超时",
    "拒绝",
    "未找到",
    "重试",
    "failed",
    "error"
)

/**
 * Derives the model-hub header presentation without changing the authoritative download state.
 *
 * The download summary deliberately clears [ModelHubUiState.statusMessage] in its display copy:
 * a later status/error belongs in its own card and must stay visible even while the completed or
 * failed download summary remains on screen. Dismissing a completed summary only suppresses that
 * local presentation for the current task key; it never rewrites [DownloadStatus] or download
 * identity/progress.
 */
internal fun modelHubHeaderPresentation(
    state: ModelHubUiState,
    dismissedCompletedDownloadKey: String? = null,
    // W9: callers that recompose on every download tick pass a cached status presentation so the
    // trim/lowercase/marker scan below runs once per status value instead of per frame.
    statusPresentation: ModelHubStatusPresentation = modelHubStatusPresentation(state.statusMessage)
): ModelHubHeaderPresentation {
    val downloadKey = state.downloadTaskId ?: state.downloadFileName
    val downloadCompleted = state.downloadStatus == DownloadStatus.DONE
    val summaryDismissed = downloadCompleted &&
        downloadKey != null &&
        downloadKey == dismissedCompletedDownloadKey
    val showDownloadSummary = state.downloadFileName != null && !summaryDismissed

    return ModelHubHeaderPresentation(
        downloadSummary = if (showDownloadSummary) {
            state.copy(statusMessage = null)
        } else {
            null
        },
        downloadSummaryKey = downloadKey,
        canDismissDownloadSummary = showDownloadSummary && downloadCompleted && downloadKey != null,
        statusMessage = statusPresentation.message,
        statusIsError = statusPresentation.isError
    )
}

internal fun modelHubStatusPresentation(message: String?): ModelHubStatusPresentation =
    ModelHubStatusPresentation(
        message = message?.trim()?.takeIf { it.isNotEmpty() },
        isError = message?.let(::modelHubStatusMessageIsError) == true
    )

internal fun modelHubStatusMessageIsError(message: String): Boolean {
    val normalized = message.trim().lowercase()
    return normalized.isNotEmpty() && MODEL_HUB_ERROR_STATUS_MARKERS.any(normalized::contains)
}

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
private fun ModelHubHeader(
    state: ModelHubUiState,
    selected: ModelHubSection,
    onSection: (ModelHubSection) -> Unit,
    onBack: () -> Unit,
    onRefreshLocal: () -> Unit,
    onPauseDownloads: () -> Unit,
    onResumeDownloads: () -> Unit
) {
    Column(
        modifier = Modifier.animateContentSize(McaMotion.sizeChangePx),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.Close, contentDescription = "返回聊天")
                }
                Column {
                    Text("模型管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("本地 / 云端 / 推荐 / 广场 / 文件", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefreshLocal, enabled = !state.isBusy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新本地模型")
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(9.dp).size(20.dp)
                    )
                }
            }
        }

        var dismissedCompletedDownloadKey by rememberSaveable { mutableStateOf<String?>(null) }
        val statusPresentation = remember(state.statusMessage) {
            modelHubStatusPresentation(state.statusMessage)
        }
        val presentation = modelHubHeaderPresentation(
            state = state,
            dismissedCompletedDownloadKey = dismissedCompletedDownloadKey,
            statusPresentation = statusPresentation
        )

        presentation.downloadSummary?.let { summary ->
            var downloadDetailsOpen by rememberSaveable(
                summary.downloadTaskId ?: summary.downloadFileName ?: "download-task"
            ) {
                mutableStateOf(false)
            }
            AnimatedVisibility(
                visibleState = rememberEnterVisibility(true),
                enter = fadeIn(animationSpec = McaMotion.fade(180))
            ) {
                DownloadProgressPanel(
                    state = summary,
                    onShowDetails = { downloadDetailsOpen = true },
                    onPauseDownloads = onPauseDownloads,
                    onResumeDownloads = onResumeDownloads
                )
            }
            if (presentation.canDismissDownloadSummary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            presentation.downloadSummaryKey?.let { dismissedCompletedDownloadKey = it }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("收起已完成摘要", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (downloadDetailsOpen) {
                DownloadDetailsDialog(
                    state = summary,
                    onDismiss = { downloadDetailsOpen = false },
                    onOpenLocalModels = {
                        downloadDetailsOpen = false
                        onSection(ModelHubSection.LOCAL)
                    },
                    onResumeDownloads = onResumeDownloads
                )
            }
        }
        presentation.statusMessage?.let { message ->
            StatusMessageCard(message = message, isError = presentation.statusIsError)
        }
        if (presentation.downloadSummary == null && state.isBusy) {
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
                ModelHubTab(
                    item = item,
                    active = selected == item,
                    onSection = onSection
                )
            }
        }
    }
}

@Composable
private fun RowScope.ModelHubTab(
    item: ModelHubSection,
    active: Boolean,
    onSection: (ModelHubSection) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = { onSection(item) },
        modifier = Modifier
            .weight(1f)
            .height(46.dp)
            .mcaPressScale(interactionSource),
        interactionSource = interactionSource,
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
