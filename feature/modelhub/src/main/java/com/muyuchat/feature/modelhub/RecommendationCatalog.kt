package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ModelScopeRecommendedGroup
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RecommendedModelSection
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.download.downloadEligibilityFor
import java.util.Locale

internal data class RecommendationCatalog(
    val lightChat: List<ModelScopeRecommendedModel>,
    val mainChat: List<ModelScopeRecommendedModel>,
    val qualityChat: List<ModelScopeRecommendedModel>,
    val npuChat: List<ModelScopeRecommendedModel>,
    val cpuImage: List<ModelScopeRecommendedModel>,
    val npuImageSd15: List<ModelScopeRecommendedModel>,
    val npuImageSdxl: List<ModelScopeRecommendedModel>,
    val npuImageGen5: List<ModelScopeRecommendedModel>
) {
    val npuImage: List<ModelScopeRecommendedModel>
        get() = npuImageSd15 + npuImageSdxl + npuImageGen5
}

/** A collapsed tier exposes only its approved P0 entry; expansion reveals the rest in-place. */
internal fun collapsedRecommendationModels(
    models: List<ModelScopeRecommendedModel>
): List<ModelScopeRecommendedModel> = models.take(1)

internal fun buildRecommendationCatalog(
    models: List<ModelScopeRecommendedModel>,
    deviceChipsetCode: String,
    deviceTotalRamBytes: Long,
    deviceIsSnapdragon: Boolean = false
): RecommendationCatalog {
    // Keep the catalog defensive: callers normally pass the user-facing list,
    // but a raw recommendation list must never resurrect entries deliberately
    // hidden from the product UI.
    val visibleModels = models.filter { it.visibleInRecommendations }
    val comparator = recommendationComparator()
    fun modelsIn(section: RecommendedModelSection): List<ModelScopeRecommendedModel> =
        visibleModels.filter { it.section == section }.sortedWith(comparator)

    val cpuChat = modelsIn(RecommendedModelSection.CPU_CHAT)
    val npuImage = modelsIn(RecommendedModelSection.NPU_IMAGE)
    return RecommendationCatalog(
        lightChat = cpuChat.filter { it.group == ModelScopeRecommendedGroup.LIGHT_CHAT },
        mainChat = cpuChat.filter { it.group == ModelScopeRecommendedGroup.MAIN_CHAT },
        qualityChat = cpuChat.filter { it.group == ModelScopeRecommendedGroup.QUALITY_CHAT },
        npuChat = modelsIn(RecommendedModelSection.NPU_CHAT),
        cpuImage = modelsIn(RecommendedModelSection.CPU_IMAGE),
        npuImageSd15 = npuImage.filterNot { it.id in SDXL_QNN_MODEL_IDS || it.id in GEN5_QNN_MODEL_IDS },
        npuImageSdxl = npuImage.filter { it.id in SDXL_QNN_MODEL_IDS },
        npuImageGen5 = npuImage.filter { it.id in GEN5_QNN_MODEL_IDS }
    )
}

private val SDXL_QNN_MODEL_IDS = setOf(
    "sdxl_base_qnn228",
    "realismsdxl_dmd2_alt_qnn228",
    "animagine_xl_v4_qnn228",
    "cyberrealisticxl_qnn228"
)

private val GEN5_QNN_MODEL_IDS = setOf(
    "qualcomm_sd15_gen5_qnn",
    "qualcomm_sd21_gen5_qnn",
    "qualcomm_controlnet_canny_gen5_qnn"
)

private fun ModelScopeRecommendedModel.matchesChipset(deviceChipsetCode: String): Boolean {
    val normalizedDevice = deviceChipsetCode.trim().uppercase(Locale.ROOT)
    if (normalizedDevice.isEmpty() || supportedChipsetCodes.isEmpty()) return false
    return supportedChipsetCodes.any { it.trim().uppercase(Locale.ROOT) == normalizedDevice }
}

private fun recommendationComparator(): Comparator<ModelScopeRecommendedModel> {
    // `priority` is the approved P0/P1/P2 order. RAM and verification state are
    // advisory card metadata and must never reorder the product catalog.
    return compareBy<ModelScopeRecommendedModel> { it.priority }
        .thenBy { it.id }
}

internal data class RecommendationDownloadAccess(
    val canDownload: Boolean,
    val experimental: Boolean
)

/**
 * The fast-experiment catalog keeps visibility separate from download access.
 * CPU packages are device-agnostic. QAIRT chat remains an exact-chipset route,
 * while QNN image packages are exposed experimentally on any Snapdragon SoC.
 */
internal fun recommendationDownloadAccess(
    model: ModelScopeRecommendedModel,
    deviceChipsetCode: String,
    deviceIsSnapdragon: Boolean = false
): RecommendationDownloadAccess {
    val normalizedDevice = deviceChipsetCode.trim().uppercase(Locale.ROOT)
    val exactChipsetMatch = model.matchesChipset(normalizedDevice)
    val eligibility = model.downloadEligibilityFor(deviceChipsetCode, deviceIsSnapdragon)
    val experimental = model.status != RecommendedModelStatus.RECOMMENDED ||
        (model.section == RecommendedModelSection.NPU_IMAGE && !exactChipsetMatch)
    return RecommendationDownloadAccess(
        canDownload = eligibility.canDownload,
        experimental = experimental
    )
}

/**
 * Keep the recommendation card honest about where the app will try to get a
 * package without asserting that a particular mirror is currently serving it.
 */
internal const val RECOMMENDATION_DOWNLOAD_SOURCE_POLICY = "下载策略：ModelScope / 国内镜像优先"

/**
 * Hardware fit is advisory only.  It is intentionally kept separate from the
 * engineering / verification state so a low-RAM warning never reads like a
 * download denial.
 */
internal fun recommendationHardwareLine(
    model: ModelScopeRecommendedModel,
    fitLabel: String
): String = "硬件适配：建议 ${model.minRamGb}GB+ · $fitLabel"

/**
 * A compact, non-marketing explanation that remains visible even when a
 * model's longer catalog description is collapsed on a narrow phone.
 */
internal fun recommendationVerificationLine(
    model: ModelScopeRecommendedModel,
    qairtVerified: Boolean
): String = when {
    model.id in TEXT_VERIFIED_MNN_MODEL_IDS ->
        "验证状态：MNN 文本与图文链路已通过代表机型回归；兼容 ARM64 设备默认开放"
    model.id == "minicpm_v46_q4" ->
        "验证状态：三次冷启动、Local API、取消恢复与基础图文样例已通过"
    model.id == "qwen3_vl_4b_qairt_w4a16" && qairtVerified ->
        "验证状态：当前设备冷态、连续图文、Local API 与取消恢复已通过"
    model.id == "qwen3_vl_4b_qairt_w4a16" ->
        "验证状态：已有骁龙 8 Elite 完整图文回归证据；当前设备仍需隔离验收"
    model.id == "qwen3_4b_2507_qairt_w4a16" && qairtVerified ->
        "验证状态：当前设备十轮文本、Local API 与二次加载已通过"
    model.id == "qwen3_4b_2507_qairt_w4a16" ->
        "验证状态：已有骁龙 8 Elite 正式文本回归证据；当前设备仍需隔离验收"
    model.id == "cyberrealisticxl_qnn228" ->
        "工程状态：双进程阶段执行已通过；当前模型包 VAE shape 不匹配"
    model.id in VERIFIED_QNN_SD15_EXPERIMENT_IDS ->
        "验证状态：20-step 产品 worker 三次冷启动和三次复用已通过"
    model.id == "sd15_mnn_512_quality" ->
        "验证状态：direct + OpenCL 产品链路可出图；多提示词质量仍不稳定"
    model.id.startsWith("gemma4_") ->
        "验证状态：文本隔离方案待产品验收；完整图文包兼容性待验证"
    model.status == RecommendedModelStatus.RECOMMENDED ->
        "验证状态：已验证；不同设备仍建议本机复测"
    model.status == RecommendedModelStatus.PENDING_INTEGRATION ->
        "工程状态：待接入；组件包开放实验下载，当前版本未验证可运行"
    else ->
        "验证状态：实验下载，需本机验证，不会自动设为默认"
}

private val TEXT_VERIFIED_MNN_MODEL_IDS = setOf(
    "qwen35_08b_q4",
    "qwen35_2b_q4",
    "qwen35_4b_q4",
    "qwen35_9b_q4"
)

private val VERIFIED_QNN_SD15_EXPERIMENT_IDS = setOf(
    "realisticvisionhyper_sd15_qnn228",
    "dreamshaper_sd15_qnn228"
)

internal fun recommendationDownloadCtaLabel(
    model: ModelScopeRecommendedModel,
    canDownload: Boolean,
    experimental: Boolean = model.status != RecommendedModelStatus.RECOMMENDED
): String = when {
    !canDownload -> "芯片不支持"
    experimental -> "实验下载"
    else -> "下载"
}

internal fun recommendationStatusLabel(status: RecommendedModelStatus): String = when (status) {
    RecommendedModelStatus.RECOMMENDED -> "已验证"
    RecommendedModelStatus.EXPERIMENTAL,
    RecommendedModelStatus.NOT_RECOMMENDED -> "实验"
    RecommendedModelStatus.PENDING_INTEGRATION -> "待接入"
}

internal const val EXPERIMENTAL_DOWNLOAD_NOTICE = "实验下载不代表已验证可运行，请自行测试并反馈结果。"

/** One sentence is enough for the card; verification-critical copy lives above it. */
internal fun ModelScopeRecommendedModel.recommendationShortDescription(): String {
    val body = description.trim()
    val end = body.indexOfFirst { it == '。' || it == '；' || it == '\n' }
    return if (end > 0) body.substring(0, end).trim() else body
}
