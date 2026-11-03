@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.download.DownloadStatus
import com.muyuchat.core.download.ModelScopeHubModel
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.SmoothRightToLeftPage
import com.muyuchat.core.designsystem.mcaPressScale
import com.muyuchat.core.designsystem.rememberEnterVisibility
import com.muyuchat.core.designsystem.rememberMcaHaptics
import com.muyuchat.core.designsystem.McaIconSize

private enum class ModelHubSection(val title: String) {
    LOCAL("本地"),
    CLOUD("云端"),
    RECOMMENDED("推荐"),
    MARKET("广场"),
    FILES("文件"),
    PRESETS("预设")
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
    onDownloadRecommendedVariant: (ModelScopeRecommendedModel, String) -> Unit = { model, _ ->
        onDownloadRecommended(model)
    },
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
    onCloudStreamingChange: (Boolean) -> Unit = {},
    onCloudImageFormatChange: (String) -> Unit,
    onCloudImageModelChange: (String) -> Unit,
    onCloudImageSizeChange: (String) -> Unit,
    onCloudImageEndpointPathChange: (String) -> Unit,
    onCloudDisplayNameChange: (String) -> Unit,
    onSaveCloudChatModel: () -> Unit,
    onSaveCloudImageModel: () -> Unit,
    onTestCloudApi: () -> Unit,
    onFetchCloudModels: suspend () -> Result<List<String>> = { Result.failure(UnsupportedOperationException("获取模型列表未接入")) },
    onFetchCloudImageModels: suspend () -> Result<List<String>> = { Result.failure(UnsupportedOperationException("获取图像模型列表未接入")) },
    onSelectCloudChat: (String) -> Unit,
    onSelectCloudImage: (String) -> Unit,
    onDeleteCloudModel: (String) -> Unit,
    onRefreshLocal: () -> Unit,
    onBack: () -> Unit,
    onPauseDownloads: () -> Unit = {},
    onResumeDownloads: () -> Unit = {},
    onPauseImport: (String) -> Unit = {},
    onResumeImport: (String) -> Unit = {},
    onImportGenerationPreset: (String) -> Unit = {},
    onImportGenerationPresets: (List<String>) -> Unit = { uris -> uris.forEach(onImportGenerationPreset) },
    onDeleteGenerationPreset: (String) -> Unit = {},
    onSaveGenerationPresetBindings: (String, String, Set<String>, Set<String>) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val haptics = rememberMcaHaptics()
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
                onSection = {
                    if (section != it) haptics.tick()
                    section = it
                },
                onBack = onBack,
                onRefreshLocal = onRefreshLocal,
                onPauseDownloads = onPauseDownloads,
                onResumeDownloads = onResumeDownloads
            )

            if (startInRecommended && section == ModelHubSection.RECOMMENDED) {
                Text("下载完成后自动加入本地模型；是否可加载以完整性、兼容性和首次真实执行状态为准。", style = MaterialTheme.typography.bodySmall)
            }
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    fadeIn(McaMotion.fade(150)) togetherWith fadeOut(McaMotion.fade(100))
                },
                label = "modelHubSectionContent",
                modifier = Modifier.weight(1f)
            ) { targetSection ->
                when (targetSection) {
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
                        modifier = Modifier.fillMaxSize()
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
                        modifier = Modifier.fillMaxSize()
                    )
                    ModelHubSection.RECOMMENDED -> RecommendedModelsSection(
                        state = state,
                        onShowFiles = { model ->
                            section = ModelHubSection.FILES
                            onShowRecommendedFiles(model)
                        },
                        onDownload = onDownloadRecommended,
                        onDownloadVariant = onDownloadRecommendedVariant,
                        onOpenPage = onOpenModelPage,
                        onOpenLocalModel = { modelId ->
                            section = ModelHubSection.LOCAL
                            onOpenLocalModel(modelId)
                        },
                        onVerifyLocalModel = onVerifyLocalModel,
                        onImportLocalImageModel = onImportLocalImageModel,
                        onLoadLocalChatModel = { model ->
                            section = ModelHubSection.LOCAL
                            onLoad(model)
                        },
                        onVerifyLocalChatModel = onVerify,
                        modifier = Modifier.fillMaxSize()
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
                        modifier = Modifier.fillMaxSize()
                    )
                    ModelHubSection.FILES -> RemoteFilesSection(
                        state = state,
                        onImportClick = onImportClick,
                        onPauseImport = onPauseImport,
                        onResumeImport = onResumeImport,
                        onRepoInputChange = onRepoInputChange,
                        onFetchRemoteFiles = onFetchRemoteFiles,
                        onDownload = onDownload,
                        modifier = Modifier.fillMaxSize()
                    )
                    ModelHubSection.PRESETS -> GenerationPresetManagerSection(
                        state = state,
                        onImportPreset = onImportGenerationPreset,
                        onDeletePreset = onDeleteGenerationPreset,
                        onSaveBindings = onSaveGenerationPresetBindings,
                        modifier = Modifier.fillMaxSize(),
                        onImportPresets = onImportGenerationPresets
                    )
                }
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
                onStreamingChange = onCloudStreamingChange,
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
                onFetchModels = onFetchCloudModels,
                onFetchImageModels = onFetchCloudImageModels,
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
            // 2.0x 大字号下给标题列限宽，副标题省略而不是把右侧刷新/图标挤出屏幕
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                McaIconButton(
                    onClick = onBack,
                    contentDescription = "返回聊天",
                    glyphSize = McaIconSize.L,
                    tint = LocalContentColor.current
                ) {
                    Icon(McaIcons.Close, contentDescription = null, modifier = Modifier.size(McaIconSize.L))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("模型管理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "本地 / 云端 / 推荐 / 广场 / 文件 / 预设",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                McaIconButton(
                    onClick = onRefreshLocal,
                    enabled = !state.isBusy,
                    contentDescription = "刷新本地模型",
                    glyphSize = McaIconSize.L,
                    tint = LocalContentColor.current
                ) {
                    Icon(McaIcons.Refresh, contentDescription = null, modifier = Modifier.size(McaIconSize.L))
                }
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                    Icon(
                        McaIcons.Folder,
                        contentDescription = null,
                        // 容器配对前景色：primary 在暗色 primaryContainer 上仅约 2.1:1，非文本图标也不足 3:1
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(9.dp).size(McaIconSize.M)
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
                    McaButton(style = McaButtonStyle.Text,
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
    val compactForLargeText = LocalDensity.current.fontScale >= 1.5f
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (compactForLargeText) {
            // Five equal segments cannot hold 2.0x labels; wrap them instead of clipping the last
            // one, the same adaptation the settings tabs use.
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ModelHubSection.entries.forEach { item ->
                    ModelHubTab(
                        item = item,
                        active = selected == item,
                        onSection = onSection
                    )
                }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().padding(2.dp)) {
                ModelHubSection.entries.forEach { item ->
                    ModelHubTab(
                        item = item,
                        active = selected == item,
                        onSection = onSection,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelHubTab(
    item: ModelHubSection,
    active: Boolean,
    onSection: (ModelHubSection) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        onClick = { onSection(item) },
        modifier = modifier
            .heightIn(min = 46.dp)
            .mcaPressScale(interactionSource),
        interactionSource = interactionSource,
        color = if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                item.title,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                // onPrimaryContainer reads 5.95:1 light / 6.30:1 dark on this container;
                // primary only reached 4.12 / 3.43.
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
