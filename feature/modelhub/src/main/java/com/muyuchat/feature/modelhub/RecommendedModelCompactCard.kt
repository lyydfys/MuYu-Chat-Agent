@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.modelstore.ModelManifest

/**
 * 推荐卡的渲染入口。
 *
 * 把一次推荐列表渲染需要的页面状态与回调收在一处，让分组列表、平铺排序列表和紧凑/详细切换
 * 共用同一组事件。它只做 UI 投影，不读全局 store，也不改变下载/打开/加载的业务路径。
 */
internal data class RecommendationCardHost(
    val state: ModelHubUiState,
    val onShowFiles: (ModelScopeRecommendedModel) -> Unit,
    val onDownload: (ModelScopeRecommendedModel) -> Unit,
    val onOpenPage: (String) -> Unit,
    val onOpenLocalModel: (String) -> Unit,
    val onVerifyLocalModel: (String) -> Unit,
    val onLoadLocalChatModel: (ModelManifest) -> Unit,
    val onVerifyLocalChatModel: (ModelManifest) -> Unit,
    val onImportLocalImageModel: () -> Unit
)

/** 按当前视图模式渲染同一模型；模式只影响排版，不影响排序、分组或任何动作。 */
@Composable
internal fun RecommendedModelItem(
    mode: RecommendationViewMode,
    model: ModelScopeRecommendedModel,
    host: RecommendationCardHost,
    modifier: Modifier = Modifier
) {
    when (mode) {
        RecommendationViewMode.DETAIL -> RecommendedModelCard(
            modifier = modifier,
            model = model,
            deviceTotalRamBytes = host.state.deviceTotalRamBytes,
            deviceAvailableRamBytes = host.state.deviceAvailableRamBytes,
            deviceChipsetCode = host.state.deviceChipsetCode,
            deviceSupportedAbis = host.state.deviceSupportedAbis,
            deviceIsSnapdragon = host.state.deviceIsSnapdragon,
            localModels = host.state.localModels,
            qairtVerifiedLocalModelIds = host.state.qairtVerifiedLocalModelIds,
            localImageModels = host.state.localImageModels,
            onLoadLocalChatModel = host.onLoadLocalChatModel,
            onVerifyLocalChatModel = host.onVerifyLocalChatModel,
            onOpenLocalModel = host.onOpenLocalModel,
            onVerifyLocalModel = host.onVerifyLocalModel,
            onImportLocalImageModel = host.onImportLocalImageModel,
            enabled = !host.state.isBusy,
            onShowFiles = { host.onShowFiles(model) },
            onDownload = { host.onDownload(model) },
            onOpenPage = { host.onOpenPage(model.modelPageUrl) }
        )

        RecommendationViewMode.COMPACT -> RecommendedModelCompactCard(
            modifier = modifier,
            model = model,
            host = host
        )
    }
}

/**
 * 紧凑视图。
 *
 * 只压缩非决策信息：标题行、能力行、资源/体积比较行各一行，其余技术字段收进“详情”。
 * 重要兼容提示、安装/校验状态、主操作、文件入口、诊断入口全部保留，且不缩小字号（沿用
 * 既有的 bodySmall/labelSmall 度量），仍是单列卡片而不是网格。
 */
@Composable
internal fun RecommendedModelCompactCard(
    model: ModelScopeRecommendedModel,
    host: RecommendationCardHost,
    modifier: Modifier = Modifier
) {
    val state = rememberRecommendedCardState(
        model = model,
        localModels = host.state.localModels,
        localImageModels = host.state.localImageModels,
        qairtVerifiedLocalModelIds = host.state.qairtVerifiedLocalModelIds,
        deviceTotalRamBytes = host.state.deviceTotalRamBytes,
        deviceAvailableRamBytes = host.state.deviceAvailableRamBytes,
        deviceChipsetCode = host.state.deviceChipsetCode,
        deviceSupportedAbis = host.state.deviceSupportedAbis,
        deviceIsSnapdragon = host.state.deviceIsSnapdragon
    )
    var detailsExpanded by rememberSaveable(model.id) { mutableStateOf(false) }
    val fitColor = if (state.fitLabel == "低于建议内存") {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    CardBox(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                model.title,
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val statusLabel = model.status.label
            if (model.status != RecommendedModelStatus.RECOMMENDED) {
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1
                )
            }
        }
        Text(
            "功能：${state.capabilityLine} · ${state.specificationLine}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // 兼容提示先于比较信息出现，紧凑视图不得把警告挤到看不见的位置。
        state.runtimeCompatibilityLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        state.qnnCompatibilityLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            state.hardwareLine,
            style = MaterialTheme.typography.bodySmall,
            color = fitColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            recommendationDownloadSizeLine(model, state.downloadSizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RecommendedCardInstallStatus(state)
        RecommendedCardBlockLine(state)
        McaButton(style = McaButtonStyle.Text, onClick = { detailsExpanded = !detailsExpanded }) {
            Text(if (detailsExpanded) "收起详情" else "详情")
        }
        AnimatedVisibility(
            visible = detailsExpanded,
            enter = fadeIn(animationSpec = McaMotion.fade()) +
                expandVertically(animationSpec = McaMotion.sizeChangePx),
            exit = fadeOut(animationSpec = McaMotion.fade()) +
                shrinkVertically(animationSpec = McaMotion.sizeChangePx)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "后端：${state.routeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                state.imageSizeLine?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(state.shortDescription, style = MaterialTheme.typography.bodySmall)
                // 展开的介绍不设硬行数上限：紧凑卡片也不得把官方说明截断成梗概。
                Text(model.description, style = MaterialTheme.typography.bodySmall)
            }
        }
        RecommendedCardActionRow(
            state = state,
            enabled = !host.state.isBusy,
            onShowFiles = { host.onShowFiles(model) },
            onDownload = { host.onDownload(model) },
            onOpenPage = { host.onOpenPage(model.modelPageUrl) },
            onOpenLocalModel = host.onOpenLocalModel,
            onVerifyLocalModel = host.onVerifyLocalModel,
            onLoadLocalChatModel = host.onLoadLocalChatModel,
            onVerifyLocalChatModel = host.onVerifyLocalChatModel,
            onImportLocalImageModel = host.onImportLocalImageModel,
            manualImportRequired = model.manualImportRequired
        )
    }
}
