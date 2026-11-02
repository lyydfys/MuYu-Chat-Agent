@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest
import com.muyuchat.core.designsystem.McaIconSize

/**
 * 已安装模型的**次级**诊断按钮。
 *
 * 只在模型尚未通过本机验证时出现，且**永不取代**主操作：加载/打开始终可用，诊断失败后
 * 仍可走通用兼容路径，由真实 native 加载与执行决定结果（通用设备准入要求）。
 */
@Composable
private fun RowScope.VerifyLocalModelButton(
    failed: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    McaButton(style = McaButtonStyle.Outlined,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Icon(
            McaIcons.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(McaIconSize.S)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (failed) "重新诊断" else "本机诊断",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 推荐卡的呈现状态。
 *
 * 详细与紧凑两种视图共用这一份计算结果：安装/校验状态、可下载性、兼容提示和主操作决策都只有
 * 一个来源，紧凑视图不得另算一套。
 */
internal data class RecommendedCardState(
    val shortDescription: String,
    val specificationLine: String,
    val routeLabel: String,
    val capabilityLine: String,
    val imageSizeLine: String?,
    val hardwareLine: String,
    val fitLabel: String,
    val downloadSizeBytes: Long?,
    val runtimeCompatibilityLine: String?,
    val qnnCompatibilityLine: String?,
    val localStatusLine: String?,
    val localStatusVerified: Boolean,
    val localImageModel: LocalImageModelUiItem?,
    val localChatModel: ModelManifest?,
    val primaryAction: RecommendedCardPrimaryAction,
    val showVerifyDiagnostic: Boolean,
    val canDownload: Boolean,
    val downloadBlockReason: String?,
    val hasModelPage: Boolean
)

@Composable
internal fun rememberRecommendedCardState(
    model: ModelScopeRecommendedModel,
    localModels: List<ModelManifest>,
    localImageModels: List<LocalImageModelUiItem>,
    qairtVerifiedLocalModelIds: Set<String>,
    deviceTotalRamBytes: Long,
    deviceAvailableRamBytes: Long,
    deviceChipsetCode: String,
    deviceSupportedAbis: List<String>,
    deviceIsSnapdragon: Boolean
): RecommendedCardState {
    val hasModelPage = recommendationHasModelPage(model)
    // W9: eligibility and installed-model lookups scan device/list data. Cache them per real input
    // so a download-progress recomposition does not redo the scan for every visible card.
    val downloadAccess = remember(model, deviceChipsetCode, deviceIsSnapdragon, deviceSupportedAbis) {
        recommendationDownloadAccess(
            model,
            deviceChipsetCode,
            deviceIsSnapdragon,
            deviceSupportedAbis
        )
    }
    val fitLabel = deviceFitLabel(model, deviceTotalRamBytes, deviceAvailableRamBytes)
    val localImageModel = remember(model, localImageModels) {
        if (model.status == com.muyuchat.core.download.RecommendedModelStatus.IN_DEVELOPMENT) {
            null
        } else {
            localImageModels.firstOrNull { local ->
                local.recommendationId == model.id || local.id == model.imageEngineBundle?.id
            }
        }
    }
    val localChatModel = remember(model, localModels) {
        recommendedLocalChatModel(model, localModels)
    }
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
    // 主操作只由"是否已安装"决定（决策函数刻意不含验证参数）；验证状态只决定次级诊断按钮。
    return RecommendedCardState(
        shortDescription = model.recommendationShortDescription(),
        specificationLine = recommendationSpecificationLine(model),
        routeLabel = recommendedRouteLabel(model),
        capabilityLine = recommendationCapabilityLine(model),
        imageSizeLine = recommendationImageSizeLine(model),
        hardwareLine = recommendationHardwareLine(model, fitLabel),
        fitLabel = fitLabel,
        downloadSizeBytes = recommendationDownloadSizeBytes(model),
        runtimeCompatibilityLine = recommendationRuntimeCompatibilityLine(model, deviceSupportedAbis),
        qnnCompatibilityLine = recommendationQnnCompatibilityLine(model, deviceChipsetCode),
        localStatusLine = localStatusLine,
        localStatusVerified = localImageVerificationPassed || localChatVerificationPassed,
        localImageModel = localImageModel,
        localChatModel = localChatModel,
        primaryAction = recommendedCardPrimaryAction(
            hasLocalImageModel = localImageModel != null,
            hasLocalChatModel = localChatModel != null
        ),
        showVerifyDiagnostic = recommendedCardShowsVerifyDiagnostic(
            hasLocalImageModel = localImageModel != null,
            hasLocalChatModel = localChatModel != null,
            imageVerificationPassed = localImageVerificationPassed,
            chatVerificationPassed = localChatVerificationPassed
        ),
        canDownload = downloadAccess.canDownload,
        downloadBlockReason = model.downloadBlockReason,
        hasModelPage = hasModelPage
    )
}

@Composable
internal fun RecommendedModelCard(
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
    onImportLocalImageModel: () -> Unit = {},
    onLoadLocalChatModel: (ModelManifest) -> Unit = {},
    onVerifyLocalChatModel: (ModelManifest) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state = rememberRecommendedCardState(
        model = model,
        localModels = localModels,
        localImageModels = localImageModels,
        qairtVerifiedLocalModelIds = qairtVerifiedLocalModelIds,
        deviceTotalRamBytes = deviceTotalRamBytes,
        deviceAvailableRamBytes = deviceAvailableRamBytes,
        deviceChipsetCode = deviceChipsetCode,
        deviceSupportedAbis = deviceSupportedAbis,
        deviceIsSnapdragon = deviceIsSnapdragon
    )
    var detailsExpanded by rememberSaveable(model.id) { mutableStateOf(false) }
    val fitColor = if (state.fitLabel == "低于建议内存") {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    CardBox(modifier = modifier) {
        Text(model.title, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        if (model.status == com.muyuchat.core.download.RecommendedModelStatus.IN_DEVELOPMENT) {
            Text(
                "待开发",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Text(
            state.shortDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            state.specificationLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "后端：${state.routeLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
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
            "功能：${state.capabilityLine}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        state.imageSizeLine?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 展开态必须能读全 21 组尺寸长文，折叠态仍保持 3 行
                maxLines = if (detailsExpanded) Int.MAX_VALUE else 3,
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
            Text(if (detailsExpanded) "收起介绍" else "完整介绍")
        }
        AnimatedVisibility(
            visible = detailsExpanded,
            enter = fadeIn(animationSpec = McaMotion.fade()) +
                expandVertically(animationSpec = McaMotion.sizeChangePx),
            exit = fadeOut(animationSpec = McaMotion.fade()) +
                shrinkVertically(animationSpec = McaMotion.sizeChangePx)
        ) {
            Text(model.description, style = MaterialTheme.typography.bodySmall)
        }
        RecommendedCardActionRow(
            state = state,
            enabled = enabled,
            onShowFiles = onShowFiles,
            onDownload = onDownload,
            onOpenPage = onOpenPage,
            onOpenLocalModel = onOpenLocalModel,
            onVerifyLocalModel = onVerifyLocalModel,
            onLoadLocalChatModel = onLoadLocalChatModel,
            onVerifyLocalChatModel = onVerifyLocalChatModel,
            onImportLocalImageModel = onImportLocalImageModel,
            manualImportRequired = model.manualImportRequired
        )
    }
}

/** 已安装/校验状态行。详细与紧凑共用，状态语义只有一处。 */
@Composable
internal fun RecommendedCardInstallStatus(state: RecommendedCardState) {
    state.localStatusLine?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.localStatusVerified) {
                MaterialTheme.colorScheme.primary
            } else MaterialTheme.colorScheme.secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 明确写出“当前没有可下载文件”，不隐藏该行，也不改变任何入口。 */
@Composable
internal fun RecommendedCardBlockLine(state: RecommendedCardState) {
    if (!state.canDownload) {
        Text(
            recommendationDownloadBlockLine(state.downloadBlockReason),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 主操作 + 文件 + 打开页面 + 次级诊断。
 *
 * 详细与紧凑视图共用同一组事件和同一主操作决策：紧凑视图不得隐藏重要入口或把主操作降级。
 */
@Composable
internal fun RecommendedCardActionRow(
    state: RecommendedCardState,
    enabled: Boolean,
    onShowFiles: () -> Unit,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
    onOpenLocalModel: (String) -> Unit,
    onVerifyLocalModel: (String) -> Unit,
    onLoadLocalChatModel: (ModelManifest) -> Unit,
    onVerifyLocalChatModel: (ModelManifest) -> Unit,
    onImportLocalImageModel: () -> Unit = {},
    manualImportRequired: Boolean = false
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.localImageModel != null &&
            state.primaryAction == RecommendedCardPrimaryAction.OPEN_LOCAL_IMAGE
        ) {
            // 主操作**始终**是打开/加载：验证记录只作提示与诊断，不得把主按钮替换成"校验"，
            // 否则未知设备/未验证状态就阻断了加载路径（违反通用设备准入）。
            McaButton(style = McaButtonStyle.Outlined,
                onClick = { onOpenLocalModel(state.localImageModel.id) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(
                    McaIcons.Image,
                    contentDescription = null,
                    modifier = Modifier.size(McaIconSize.S)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "打开本地模型",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.showVerifyDiagnostic) {
                VerifyLocalModelButton(
                    failed = state.localImageModel.verificationStatus == "FAILED",
                    enabled = enabled,
                    onClick = { onVerifyLocalModel(state.localImageModel.id) }
                )
            }
        } else if (state.localChatModel != null &&
            state.primaryAction == RecommendedCardPrimaryAction.LOAD_LOCAL_CHAT
        ) {
            // 同上：加载不被验证状态阻断；诊断失败后仍可走通用兼容路径，由真实 native 加载决定结果。
            McaButton(style = McaButtonStyle.Outlined,
                onClick = { onLoadLocalChatModel(state.localChatModel) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(
                    McaIcons.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(McaIconSize.S)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "打开本地模型",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.showVerifyDiagnostic) {
                VerifyLocalModelButton(
                    failed = false,
                    enabled = enabled,
                    onClick = { onVerifyLocalChatModel(state.localChatModel) }
                )
            }
        } else if (manualImportRequired) {
            McaButton(style = McaButtonStyle.Primary,
                onClick = onImportLocalImageModel,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(McaIcons.UploadFile, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                Spacer(Modifier.width(6.dp))
                Text("导入本地包", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            McaButton(style = McaButtonStyle.Primary,
                onClick = onDownload,
                enabled = enabled && state.canDownload,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Icon(McaIcons.Download, contentDescription = null, modifier = Modifier.size(McaIconSize.S))
                Spacer(Modifier.width(6.dp))
                Text(
                    recommendationDownloadCtaLabel(canDownload = state.canDownload)
                )
            }
        }
        McaButton(style = McaButtonStyle.Outlined, onClick = onShowFiles, enabled = enabled && state.canDownload, modifier = Modifier.weight(1f), shape = RoundedCornerShape(999.dp)) {
            Text("文件")
        }
        McaIconButton(
            contentDescription = "打开页面",
            onClick = onOpenPage,
            enabled = state.hasModelPage,
            glyphSize = McaIconSize.L,
        ) {
            Icon(McaIcons.OpenInBrowser, contentDescription = null)
        }
    }
}

internal fun recommendedRouteLabel(model: ModelScopeRecommendedModel): String {
    val imageBundle = model.imageEngineBundle
    val visionBundle = model.visionModelBundle
    return when {
        model.plannedImageAccelerator == com.muyuchat.core.download.ImageEngineAccelerator.MEDIATEK_NPU -> "天玑 NPU"
        imageBundle != null -> imageBundle.runtimeSummary
        model.mnnModelBundle != null -> "MNN · CPU / OpenCL GPU"
        visionBundle != null -> visionBundle.runtimeSummary
        model.chatRuntime == com.muyuchat.core.download.RecommendedChatRuntime.LITERT_LM ->
            "LiteRT-LM · ${model.computeBackend.label}"
        model.chatRuntime == com.muyuchat.core.download.RecommendedChatRuntime.GENIEX_LLAMA_CPP ->
            "GenieX QNN · HTP0 / HYBRID"
        model.chatRuntime == com.muyuchat.core.download.RecommendedChatRuntime.GGUF -> "llama.cpp · CPU"
        else -> model.chatRuntime.label
    }
}

internal fun recommendationHasModelPage(model: ModelScopeRecommendedModel): Boolean =
    !model.repoId.startsWith("pending/", ignoreCase = true)

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
