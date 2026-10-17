@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.modelstore.ModelManifest

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
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (failed) "重新诊断" else "本机诊断",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
        // 主操作只由"是否已安装"决定（决策函数刻意不含验证参数）；验证状态只决定次级诊断按钮。
        val primaryAction = recommendedCardPrimaryAction(
            hasLocalImageModel = localImageModel != null,
            hasLocalChatModel = localChatModel != null
        )
        val showVerifyDiagnostic = recommendedCardShowsVerifyDiagnostic(
            hasLocalImageModel = localImageModel != null,
            hasLocalChatModel = localChatModel != null,
            imageVerificationPassed = localImageVerificationPassed,
            chatVerificationPassed = localChatVerificationPassed
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (localImageModel != null &&
                primaryAction == RecommendedCardPrimaryAction.OPEN_LOCAL_IMAGE
            ) {
                // 主操作**始终**是打开/加载：验证记录只作提示与诊断，不得把主按钮替换成"校验"，
                // 否则未知设备/未验证状态就阻断了加载路径（违反通用设备准入）。
                OutlinedButton(
                    onClick = { onOpenLocalModel(localImageModel.id) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "打开本地模型",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showVerifyDiagnostic) {
                    VerifyLocalModelButton(
                        failed = localImageModel.verificationStatus == "FAILED",
                        enabled = enabled,
                        onClick = { onVerifyLocalModel(localImageModel.id) }
                    )
                }
            } else if (localChatModel != null &&
                primaryAction == RecommendedCardPrimaryAction.LOAD_LOCAL_CHAT
            ) {
                // 同上：加载不被验证状态阻断；诊断失败后仍可走通用兼容路径，由真实 native 加载决定结果。
                OutlinedButton(
                    onClick = { onLoadLocalChatModel(localChatModel) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "打开本地模型",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (showVerifyDiagnostic) {
                    VerifyLocalModelButton(
                        failed = false,
                        enabled = enabled,
                        onClick = { onVerifyLocalChatModel(localChatModel) }
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
