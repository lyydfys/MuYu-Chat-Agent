@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.modelstore.ModelManifest
import kotlin.math.roundToInt

@Composable
internal fun RecommendedModelsSection(
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

        // busy 且无推荐时用行内进度占位，空态只在非 busy 时出现
        if (state.isBusy && !hasRecommendations) {
            item(key = "recommendations-loading") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (!hasRecommendations) {
            if (!state.isBusy) {
                item { EmptyCard("暂无推荐模型", "稍后刷新推荐列表，或到广场搜索公开模型。") }
            }
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
                            modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade()),
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
                                modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade()),
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
                                modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade()),
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
                        modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade()),
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
                        modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade()),
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
                                modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade()),
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

/** 推荐卡的主操作。**刻意不含 Verify** —— 验证状态不得替换加载/打开。 */
internal enum class RecommendedCardPrimaryAction { DOWNLOAD, OPEN_LOCAL_IMAGE, LOAD_LOCAL_CHAT }

/**
 * 推荐卡主操作的决策。
 *
 * 通用设备准入要求：验证状态（含 `qairtVerifiedLocalModelIds` 这类设备侧名单）**不得**把主操作
 * 变成"校验"，否则未知设备或"暂无验证记录"就阻断了加载/打开路径。
 *
 * ⇒ 因此本函数**不接受任何验证参数**：主操作与验证状态**在结构上无法耦合**，而不只是靠约定。
 */

internal fun recommendedCardPrimaryAction(
    hasLocalImageModel: Boolean,
    hasLocalChatModel: Boolean
): RecommendedCardPrimaryAction = when {
    hasLocalImageModel -> RecommendedCardPrimaryAction.OPEN_LOCAL_IMAGE
    hasLocalChatModel -> RecommendedCardPrimaryAction.LOAD_LOCAL_CHAT
    else -> RecommendedCardPrimaryAction.DOWNLOAD
}

/** 是否显示**次级**诊断按钮。它只提示验证状态，永不取代主操作。 */

internal fun recommendedCardShowsVerifyDiagnostic(
    hasLocalImageModel: Boolean,
    hasLocalChatModel: Boolean,
    imageVerificationPassed: Boolean,
    chatVerificationPassed: Boolean
): Boolean = when {
    hasLocalImageModel -> !imageVerificationPassed
    hasLocalChatModel -> !chatVerificationPassed
    else -> false
}
