package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ImageEngineAccelerator
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedComputeBackend
import com.muyuchat.core.download.VisionModelAccelerator

/**
 * 推荐页的用途维度。
 *
 * 只来自类型化目录元数据（[ModelScopeRecommendedModel.kind] / bundle 任务），不从标题、
 * 标签或说明文字反推。多模态聊天归 [CHAT]：带视觉能力不等于生图。
 */
internal enum class RecommendationTask(val label: String) {
    CHAT("聊天"),
    IMAGE("生图")
}

/**
 * 运行路线。
 *
 * 取自类型的后端/加速器字段（[RecommendedComputeBackend]、image/vision bundle 的 accelerator、
 * GENIEX_QAIRT），不解析芯片型号字符串。它只决定当前列表显示哪一项，不参与下载/加载准入。
 */
internal enum class RecommendationBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU")
}

/**
 * 排序方式。
 *
 * [CATALOG] 必须严格沿用 [RecommendationCatalog] 的既有分组与组内顺序，不生成跨 CPU/GPU/NPU
 * 的总榜。[DOWNLOAD_SIZE_ASC] / [MINIMUM_RAM_ASC] 只在用户显式选择时使用平铺比较列表。
 */
internal enum class RecommendationSort(val label: String) {
    CATALOG("推荐"),
    DOWNLOAD_SIZE_ASC("下载体积"),
    MINIMUM_RAM_ASC("最小内存")
}

/** 详细为默认，紧凑只压缩非决策信息；两者共用同一模型、同一事件。 */
internal enum class RecommendationViewMode { DETAIL, COMPACT }

/** 面板打开入口；只决定面板内先显示哪一段设置，不改变任何已应用状态。 */
internal enum class RecommendationPanelFocus { FILTER, SORT }

/** `minRamGb` 未声明时的资源档标记。它绝不伪造一个估算值。 */
internal const val RECOMMENDATION_RAM_TIER_UNKNOWN: Int = 0

/**
 * 推荐页已应用的筛选/排序条件。
 *
 * 同一维度内多个选项为 OR，不同维度之间为 AND；某个维度为空等于该维度不限。
 * 本类型是纯数据：不依赖 Compose、Android UI、全局 store 或下载 owner。
 */
internal data class RecommendationQuery(
    val tasks: Set<RecommendationTask> = emptySet(),
    val backends: Set<RecommendationBackend> = emptySet(),
    val ramTiers: Set<Int> = emptySet(),
    val sort: RecommendationSort = RecommendationSort.CATALOG
) {
    /** 已应用的筛选条件数（排序不计入）。用于工具行的“筛选 · N”。 */
    val filterCount: Int
        get() = tasks.size + backends.size + ramTiers.size

    val hasFilters: Boolean
        get() = filterCount > 0

    /** 只有“无筛选 + 推荐排序”才是改动前的原始目录呈现。 */
    val isDefault: Boolean
        get() = !hasFilters && sort == RecommendationSort.CATALOG

    /** 保留排序、清空筛选（面板“重置”与“清除筛选”动作）。 */
    fun withoutFilters(): RecommendationQuery = copy(tasks = emptySet(), backends = emptySet(), ramTiers = emptySet())

    fun matches(model: ModelScopeRecommendedModel): Boolean {
        if (tasks.isNotEmpty() && model.recommendationTask() !in tasks) return false
        if (backends.isNotEmpty() && model.recommendationBackend() !in backends) return false
        if (ramTiers.isNotEmpty() && model.recommendationRamTier() !in ramTiers) return false
        return true
    }
}

internal fun ModelScopeRecommendedModel.recommendationTask(): RecommendationTask =
    if (kind == ModelScopeRecommendedKind.IMAGE) RecommendationTask.IMAGE else RecommendationTask.CHAT

internal fun ModelScopeRecommendedModel.recommendationBackend(): RecommendationBackend {
    val imageAccelerator = imageEngineBundle?.accelerator ?: plannedImageAccelerator
    if (imageAccelerator != null) {
        return when (imageAccelerator) {
            ImageEngineAccelerator.QNN_HTP -> RecommendationBackend.NPU
            ImageEngineAccelerator.MEDIATEK_NPU -> RecommendationBackend.NPU
            ImageEngineAccelerator.OPENCL_GPU -> RecommendationBackend.GPU
            ImageEngineAccelerator.CPU -> RecommendationBackend.CPU
        }
    }
    val visionRunsOnNpu = visionModelBundle?.accelerator == VisionModelAccelerator.QNN_HTP
    return when {
        computeBackend == RecommendedComputeBackend.NPU ||
            visionRunsOnNpu ||
            chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT -> RecommendationBackend.NPU
        computeBackend == RecommendedComputeBackend.GPU -> RecommendationBackend.GPU
        else -> RecommendationBackend.CPU
    }
}

internal fun ModelScopeRecommendedModel.recommendationRamTier(): Int =
    if (minRamGb > 0) minRamGb else RECOMMENDATION_RAM_TIER_UNKNOWN

internal fun recommendationRamTierLabel(tier: Int): String =
    if (tier == RECOMMENDATION_RAM_TIER_UNKNOWN) "未标注" else "$tier GB"

/**
 * 当前目录里真实存在的资源档，升序；未标注单列在最后。
 *
 * 只展示数据实际支持的档位，不按设备内存推测一个新档。
 */
internal fun RecommendationCatalog.availableRamTiers(): List<Int> {
    val tiers = visibleModelsInCatalogOrder().map { it.recommendationRamTier() }.distinct().sorted()
    return tiers.filter { it != RECOMMENDATION_RAM_TIER_UNKNOWN } +
        tiers.filter { it == RECOMMENDATION_RAM_TIER_UNKNOWN }
}

/**
 * 目录里当前用户可见的模型，按页面既有分组顺序排列并按 id 去重。
 *
 * LiteRT-LM 的 CPU/GPU 条目同时出现在 CPU 聊天档与 LiteRT-LM 分组中；这里保留首次出现的
 * 位置，使“计数/平铺排序”与用户看到的目录保持一致，也不会产生重复的 LazyColumn key。
 */
internal fun RecommendationCatalog.visibleModelsInCatalogOrder(): List<ModelScopeRecommendedModel> {
    val ordered = listOf(lightChat, mainChat, qualityChat, npuChat, litertCpu, litertGpu, litertNpu) +
        listOf(cpuImage, gpuImage) +
        listOf(npuImageSd15, npuImageSdxl, npuImageGen5, npuImageMediaTek)
    return ordered.flatten().distinctBy { it.id }
}

/** 只把匹配当前条件的条目保留在同样形状的目录里；条件为空时原样返回（默认状态零变化）。 */
internal fun filterRecommendationCatalog(
    catalog: RecommendationCatalog,
    query: RecommendationQuery
): RecommendationCatalog {
    if (!query.hasFilters) return catalog
    fun List<ModelScopeRecommendedModel>.kept(): List<ModelScopeRecommendedModel> =
        filter { query.matches(it) }
    return catalog.copy(
        lightChat = catalog.lightChat.kept(),
        mainChat = catalog.mainChat.kept(),
        qualityChat = catalog.qualityChat.kept(),
        npuChat = catalog.npuChat.kept(),
        litertNpuModels = catalog.litertNpuModels.kept(),
        cpuImage = catalog.cpuImage.kept(),
        gpuImage = catalog.gpuImage.kept(),
        npuImageSd15 = catalog.npuImageSd15.kept(),
        npuImageSdxl = catalog.npuImageSdxl.kept(),
        npuImageGen5 = catalog.npuImageGen5.kept(),
        npuImageMediaTek = catalog.npuImageMediaTek.kept()
    )
}

/** 面板“显示 N 个模型”与列表一致的结果数。 */
internal fun RecommendationCatalog.matchingModelCount(query: RecommendationQuery): Int =
    filterRecommendationCatalog(this, query).visibleModelsInCatalogOrder().size

/** 平铺比较列表的一行：模型 + 它的任务/路线标记。 */
internal data class RecommendationFlatEntry(
    val model: ModelScopeRecommendedModel,
    val task: RecommendationTask,
    val backend: RecommendationBackend
) {
    val routeLabel: String
        get() = "${task.label} · ${backend.label}"
}

/**
 * 平铺比较列表。
 *
 * 推荐排序不使用平铺列表；只有显式选择下载体积/最低内存时才把同一批已筛选结果线性比较。
 * 缺失数据始终排末位，相同值用既有 priority 与稳定 id 决胜。
 */
internal fun RecommendationCatalog.flatEntries(query: RecommendationQuery): List<RecommendationFlatEntry> {
    val entries = filterRecommendationCatalog(this, query).visibleModelsInCatalogOrder().map { model ->
        RecommendationFlatEntry(
            model = model,
            task = model.recommendationTask(),
            backend = model.recommendationBackend()
        )
    }
    return when (query.sort) {
        RecommendationSort.CATALOG -> entries
        RecommendationSort.DOWNLOAD_SIZE_ASC -> entries.sortedWith(downloadSizeComparator)
        RecommendationSort.MINIMUM_RAM_ASC -> entries.sortedWith(minimumRamComparator)
    }
}

private val stableTieBreaker: Comparator<RecommendationFlatEntry> =
    compareBy<RecommendationFlatEntry>({ it.model.priority }, { it.model.id })

private val downloadSizeComparator: Comparator<RecommendationFlatEntry> =
    Comparator<RecommendationFlatEntry> { first, second ->
        val firstSize = recommendationDownloadSizeBytes(first.model)
        val secondSize = recommendationDownloadSizeBytes(second.model)
        when {
            firstSize == null && secondSize == null -> 0
            firstSize == null -> 1
            secondSize == null -> -1
            else -> firstSize.compareTo(secondSize)
        }
    }.then(stableTieBreaker)

private val minimumRamComparator: Comparator<RecommendationFlatEntry> =
    Comparator<RecommendationFlatEntry> { first, second ->
        val firstRam = first.model.recommendationRamTier()
        val secondRam = second.model.recommendationRamTier()
        when {
            firstRam == RECOMMENDATION_RAM_TIER_UNKNOWN && secondRam == RECOMMENDATION_RAM_TIER_UNKNOWN -> 0
            firstRam == RECOMMENDATION_RAM_TIER_UNKNOWN -> 1
            secondRam == RECOMMENDATION_RAM_TIER_UNKNOWN -> -1
            else -> firstRam.compareTo(secondRam)
        }
    }.then(stableTieBreaker)

internal fun recommendationFilterChipLabel(query: RecommendationQuery): String =
    if (query.filterCount > 0) "筛选 · ${query.filterCount}" else "筛选"

internal fun recommendationViewModeChipLabel(mode: RecommendationViewMode): String =
    when (mode) {
        RecommendationViewMode.DETAIL -> "详细"
        RecommendationViewMode.COMPACT -> "紧凑"
    }

internal fun recommendationResultCountLabel(count: Int): String = "显示 $count 个模型"

internal fun recommendationSortSummaryLine(sort: RecommendationSort, count: Int): String = when (sort) {
    RecommendationSort.CATALOG -> "按推荐顺序显示 $count 个模型"
    RecommendationSort.DOWNLOAD_SIZE_ASC ->
        "按下载体积从小到大平铺对比 · $count 个模型；体积未知的排在末尾"
    RecommendationSort.MINIMUM_RAM_ASC ->
        "按最低内存从小到大平铺对比 · $count 个模型；未标注内存的排在末尾"
}

/** 可保存的稳定 token；用于 rememberSaveable，键名不依赖枚举 ordinal。 */
internal fun RecommendationQuery.toStorageToken(): String = listOf(
    tasks.sortedBy { it.ordinal }.joinToString(",") { it.name },
    backends.sortedBy { it.ordinal }.joinToString(",") { it.name },
    ramTiers.sorted().joinToString(","),
    sort.name
).joinToString("|")

internal fun recommendationQueryFromToken(token: String?): RecommendationQuery {
    if (token.isNullOrBlank()) return RecommendationQuery()
    val parts = token.split("|")
    fun part(index: Int): String = parts.getOrNull(index)?.trim().orEmpty()
    val tasks = part(0).split(",")
        .mapNotNull { name -> RecommendationTask.entries.firstOrNull { it.name == name.trim() } }
        .toSet()
    val backends = part(1).split(",")
        .mapNotNull { name -> RecommendationBackend.entries.firstOrNull { it.name == name.trim() } }
        .toSet()
    val ramTiers = part(2).split(",")
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .toSet()
    val sort = RecommendationSort.entries.firstOrNull { it.name == part(3) } ?: RecommendationSort.CATALOG
    return RecommendationQuery(tasks = tasks, backends = backends, ramTiers = ramTiers, sort = sort)
}
