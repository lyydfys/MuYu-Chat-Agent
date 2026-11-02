@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.muyuchat.feature.modelhub

import com.muyuchat.core.designsystem.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaMotion
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.modelstore.ModelManifest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 页面级呈现状态：筛选/排序属于推荐页自己的投影，不写入 MainUiState、数据库或下载 owner。 */
private val RecommendationQuerySaver: Saver<RecommendationQuery, String> = Saver(
    save = { it.toStorageToken() },
    restore = { recommendationQueryFromToken(it) }
)

private val RecommendationViewModeSaver: Saver<RecommendationViewMode, String> = Saver(
    save = { it.name },
    restore = { name ->
        RecommendationViewMode.entries.firstOrNull { it.name == name } ?: RecommendationViewMode.DETAIL
    }
)

@Composable
internal fun RecommendedModelsSection(
    state: ModelHubUiState,
    onShowFiles: (ModelScopeRecommendedModel) -> Unit,
    onDownload: (ModelScopeRecommendedModel) -> Unit,
    onDownloadVariant: (ModelScopeRecommendedModel, String) -> Unit = { model, _ -> onDownload(model) },
    onOpenPage: (String) -> Unit,
    onOpenLocalModel: (String) -> Unit,
    onVerifyLocalModel: (String) -> Unit,
    onImportLocalImageModel: () -> Unit = {},
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
    // 已应用条件与面板草稿分开保存：草稿只在“显示 N 个模型”时提交，取消/遮罩/返回都丢弃草稿。
    var appliedQuery by rememberSaveable(stateSaver = RecommendationQuerySaver) {
        mutableStateOf(RecommendationQuery())
    }
    var viewMode by rememberSaveable(stateSaver = RecommendationViewModeSaver) {
        mutableStateOf(RecommendationViewMode.DETAIL)
    }
    var panelOpen by remember { mutableStateOf(false) }
    var panelFocus by remember { mutableStateOf(RecommendationPanelFocus.FILTER) }
    var draftQuery by remember { mutableStateOf(RecommendationQuery()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val visibleCatalog = remember(catalog, appliedQuery) {
        filterRecommendationCatalog(catalog, appliedQuery)
    }
    val matchedCount = remember(visibleCatalog) {
        visibleCatalog.visibleModelsInCatalogOrder().size
    }
    val flatEntries = remember(catalog, appliedQuery) {
        catalog.flatEntries(appliedQuery)
    }
    val draftMatchedCount = remember(catalog, draftQuery) {
        catalog.matchingModelCount(draftQuery)
    }
    val ramTiers = remember(catalog) { catalog.availableRamTiers() }

    var pendingLicenseModel by remember { mutableStateOf<ModelScopeRecommendedModel?>(null) }
    val requestDownload: (ModelScopeRecommendedModel) -> Unit = { model ->
        if (model.remoteImageBundle != null) {
            pendingLicenseModel = model
        } else {
            onDownload(model)
        }
    }

    val cardHost = RecommendationCardHost(
        state = state,
        onShowFiles = onShowFiles,
        onDownload = requestDownload,
        onOpenPage = onOpenPage,
        onOpenLocalModel = onOpenLocalModel,
        onVerifyLocalModel = onVerifyLocalModel,
        onLoadLocalChatModel = onLoadLocalChatModel,
        onVerifyLocalChatModel = onVerifyLocalChatModel,
        onImportLocalImageModel = onImportLocalImageModel
    )

    pendingLicenseModel?.let { model ->
        model.remoteImageBundle?.let { spec ->
            RemoteImageBundleLicenseDialog(
                title = model.title,
                spec = spec,
                onOpenPage = onOpenPage,
                onDismiss = { pendingLicenseModel = null },
                onConfirm = { variantId ->
                    pendingLicenseModel = null
                    onDownloadVariant(model, variantId)
                }
            )
        }
    }

    val cpuChatGroups = remember(visibleCatalog) {
        listOf(
            Triple("cpu-chat-light", "轻量档 · 4–8GB", visibleCatalog.lightChat),
            Triple("cpu-chat-main", "主力档 · 8–16GB", visibleCatalog.mainChat),
            Triple("cpu-chat-quality", "高质量档 · 12–24GB", visibleCatalog.qualityChat)
        )
    }
    val npuImageGroups = remember(visibleCatalog) {
        listOf(
            Triple("npu-image-sd15", "SD1.5 QNN", visibleCatalog.npuImageSd15),
            Triple("npu-image-sdxl", "SDXL QNN", visibleCatalog.npuImageSdxl),
            Triple("npu-image-gen5", "骁龙 8 Elite Gen 5 官方模型", visibleCatalog.npuImageGen5),
            Triple("npu-image-mediatek", "天玑 NPU", visibleCatalog.npuImageMediaTek)
        )
    }
    val litertGroups = remember(visibleCatalog) {
        listOf(
            Triple("litert-cpu", "CPU", visibleCatalog.litertCpu),
            Triple("litert-gpu", "GPU", visibleCatalog.litertGpu),
            Triple("litert-npu", "Qualcomm NPU", visibleCatalog.litertNpu)
        )
    }
    // 目录本身是否有推荐：只有真目录为空时才显示“暂无推荐模型”，筛选零结果不是空目录。
    val hasRecommendations = remember(catalog) {
        catalog.lightChat.isNotEmpty() ||
            catalog.mainChat.isNotEmpty() ||
            catalog.qualityChat.isNotEmpty() ||
            catalog.npuChat.isNotEmpty() ||
            catalog.litertNpu.isNotEmpty() ||
            catalog.cpuImage.isNotEmpty() ||
            catalog.gpuImage.isNotEmpty() ||
            catalog.npuImage.isNotEmpty()
    }

    fun openPanel(focus: RecommendationPanelFocus) {
        draftQuery = appliedQuery
        panelFocus = focus
        panelOpen = true
    }

    fun showAll() {
        appliedQuery = RecommendationQuery()
        viewMode = RecommendationViewMode.DETAIL
        draftQuery = RecommendationQuery()
        panelOpen = false
        scope.launch { listState.scrollToItem(0) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
        item(key = "recommendation-device") {
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

        item(key = "recommendation-toolbar") {
            RecommendationToolbar(
                query = appliedQuery,
                viewMode = viewMode,
                onShowAll = ::showAll,
                onOpenFilter = { openPanel(RecommendationPanelFocus.FILTER) },
                onOpenSort = { openPanel(RecommendationPanelFocus.SORT) },
                onToggleViewMode = {
                    viewMode = when (viewMode) {
                        RecommendationViewMode.DETAIL -> RecommendationViewMode.COMPACT
                        RecommendationViewMode.COMPACT -> RecommendationViewMode.DETAIL
                    }
                }
            )
        }

        // busy 且无推荐时用行内进度占位，空态只在非 busy 时出现
        if (state.isBusy && !hasRecommendations) {
            item(key = "recommendations-loading") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        when {
            !hasRecommendations -> {
                if (!state.isBusy) {
                    item(key = "recommendations-empty") {
                        EmptyCard("暂无推荐模型", "稍后刷新推荐列表，或到广场搜索公开模型。")
                    }
                }
            }

            matchedCount == 0 -> {
                item(key = "recommendations-no-match") {
                    RecommendationNoMatchCard(
                        filterCount = appliedQuery.filterCount,
                        onClearFilters = { appliedQuery = appliedQuery.withoutFilters() }
                    )
                }
            }

            appliedQuery.sort != RecommendationSort.CATALOG -> {
                recommendationFlatList(
                    entries = flatEntries,
                    sort = appliedQuery.sort,
                    mode = viewMode,
                    host = cardHost
                )
            }

            else -> {
                if (cpuChatGroups.any { it.third.isNotEmpty() }) {
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
                            val expanded = appliedQuery.hasFilters || key in expandedGroups
                            recommendationModels(
                                groupKey = key,
                                models = if (expanded) models else collapsedRecommendationModels(models),
                                mode = viewMode,
                                host = cardHost
                            )
                            if (models.size > 1 && !appliedQuery.hasFilters) {
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

                if (visibleCatalog.npuChat.isNotEmpty()) {
                    item(key = "npu-chat-header") {
                        RecommendationSectionHeader(
                            title = "NPU 图文聊天",
                            body = "使用 Qualcomm NPU 运行聊天模型，默认展示首选，可展开更多模型。"
                        )
                    }
                    val key = "npu-chat"
                    val expanded = appliedQuery.hasFilters || key in expandedGroups
                    recommendationModels(
                        groupKey = key,
                        models = if (expanded) visibleCatalog.npuChat else collapsedRecommendationModels(visibleCatalog.npuChat),
                        mode = viewMode,
                        host = cardHost
                    )
                    if (visibleCatalog.npuChat.size > 1 && !appliedQuery.hasFilters) {
                        item(key = "$key-more") {
                            RecommendationExpandButton(
                                expanded = expanded,
                                hiddenCount = visibleCatalog.npuChat.size - 1,
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
                            val collapseKey = "$key-group"
                            val expanded = appliedQuery.hasFilters || collapseKey in expandedGroups
                            recommendationModels(
                                groupKey = key,
                                models = if (expanded) models else collapsedRecommendationModels(models),
                                mode = viewMode,
                                host = cardHost
                            )
                            if (models.size > 1 && !appliedQuery.hasFilters) {
                                item(key = "$key-more") {
                                    RecommendationExpandButton(
                                        expanded = expanded,
                                        hiddenCount = models.size - 1,
                                        collapsedLabel = "查看其余模型（${models.size - 1}）",
                                        expandedLabel = "收起其余模型",
                                        onClick = {
                                            expandedGroups = expandedGroups.toggle(collapseKey, expanded)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (!appliedQuery.hasFilters || visibleCatalog.cpuImage.isNotEmpty()) {
                    item(key = "cpu-image-header") {
                        RecommendationSectionHeader(
                            title = "CPU 生图",
                            body = "在手机上生成或编辑图片，默认展示首选，可展开更多模型。"
                        )
                    }
                    if (visibleCatalog.cpuImage.isEmpty()) {
                        item(key = "cpu-image-empty") {
                            Text(
                                "暂无可展示的 CPU 生图模型。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val key = "cpu-image"
                        val expanded = appliedQuery.hasFilters || key in expandedGroups
                        recommendationModels(
                            groupKey = key,
                            models = if (expanded) visibleCatalog.cpuImage else collapsedRecommendationModels(visibleCatalog.cpuImage),
                            mode = viewMode,
                            host = cardHost
                        )
                        if (visibleCatalog.cpuImage.size > 1 && !appliedQuery.hasFilters) {
                            item(key = "$key-more") {
                                RecommendationExpandButton(
                                    expanded = expanded,
                                    hiddenCount = visibleCatalog.cpuImage.size - 1,
                                    collapsedLabel = "查看更多模型（${visibleCatalog.cpuImage.size - 1}）",
                                    expandedLabel = "收起模型",
                                    onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                                )
                            }
                        }
                    }
                }

                if (visibleCatalog.gpuImage.isNotEmpty()) {
                    item(key = "gpu-image-header") {
                        RecommendationSectionHeader(
                            title = "GPU 生图",
                            body = "DiT 使用 OpenCL GPU；文本编码器和 VAE 可由 CPU 执行。"
                        )
                    }
                    val key = "gpu-image"
                    val expanded = appliedQuery.hasFilters || key in expandedGroups
                    recommendationModels(
                        groupKey = key,
                        models = if (expanded) visibleCatalog.gpuImage else collapsedRecommendationModels(visibleCatalog.gpuImage),
                        mode = viewMode,
                        host = cardHost
                    )
                    if (visibleCatalog.gpuImage.size > 1 && !appliedQuery.hasFilters) {
                        item(key = "$key-more") {
                            RecommendationExpandButton(
                                expanded = expanded,
                                hiddenCount = visibleCatalog.gpuImage.size - 1,
                                collapsedLabel = "查看更多模型（${visibleCatalog.gpuImage.size - 1}）",
                                expandedLabel = "收起模型",
                                onClick = { expandedGroups = expandedGroups.toggle(key, expanded) }
                            )
                        }
                    }
                }

                if (visibleCatalog.npuImage.isNotEmpty()) {
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
                            val expanded = appliedQuery.hasFilters || key in expandedGroups
                            recommendationModels(
                                groupKey = key,
                                models = if (expanded) models else collapsedRecommendationModels(models),
                                mode = viewMode,
                                host = cardHost
                            )
                            if (models.size > 1 && !appliedQuery.hasFilters) {
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
        RecommendationFilterSheet(
            visible = panelOpen,
            draft = draftQuery,
            focus = panelFocus,
            ramTiers = ramTiers,
            matchedCount = draftMatchedCount,
            onFocusChange = { panelFocus = it },
            onDraftChange = { draftQuery = it },
            onReset = { draftQuery = RecommendationQuery() },
            onApply = {
                appliedQuery = draftQuery
                panelOpen = false
                scope.launch { listState.scrollToItem(0) }
            },
            onDismiss = { panelOpen = false }
        )
    }
}

/**
 * 模型分组内的条目。
 *
 * key 沿用既有 `"$groupKey-${model.id}"` 约定，切换详细/紧凑不会重建卡片状态，也不会把列表
 * 索引当作 key。
 */
private fun LazyListScope.recommendationModels(
    groupKey: String,
    models: List<ModelScopeRecommendedModel>,
    mode: RecommendationViewMode,
    host: RecommendationCardHost
) {
    items(models, key = { "$groupKey-${it.id}" }) { model ->
        RecommendedModelItem(
            mode = mode,
            model = model,
            host = host,
            modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
        )
    }
}

/** 平铺比较列表：显式选择下载体积/最低内存排序后才出现，每一行保留用途与路线标记。 */
private fun LazyListScope.recommendationFlatList(
    entries: List<RecommendationFlatEntry>,
    sort: RecommendationSort,
    mode: RecommendationViewMode,
    host: RecommendationCardHost
) {
    item(key = "recommendation-sort-summary") {
        Text(
            recommendationSortSummaryLine(sort, entries.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    items(entries, key = { "flat-${it.model.id}" }) { entry ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.routeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RecommendedModelItem(
                mode = mode,
                model = entry.model,
                host = host,
                modifier = Modifier.animateItem(McaMotion.fade(), McaMotion.pageSlide, McaMotion.fade())
            )
        }
    }
}

@Composable
private fun RecommendationNoMatchCard(filterCount: Int, onClearFilters: () -> Unit) {
    CardBox {
        Text("没有匹配模型", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
        Text(
            "当前筛选条件（$filterCount 个）没有命中推荐目录里的模型。可以调整条件，或清除筛选回到完整目录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        McaButton(
            style = McaButtonStyle.Outlined,
            onClick = onClearFilters,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp)
        ) {
            Text("清除筛选")
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
    McaButton(style = McaButtonStyle.Outlined,
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
