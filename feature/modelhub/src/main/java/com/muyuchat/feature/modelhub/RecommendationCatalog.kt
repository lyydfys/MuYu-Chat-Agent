package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ModelScopeRecommendedGroup
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RemoteModelFile
import com.muyuchat.core.download.RecommendedModelSection
import com.muyuchat.core.download.RecommendedModelDownloadPolicy
import com.muyuchat.core.download.RecommendedModelStatus
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedComputeBackend
import com.muyuchat.core.download.downloadEligibilityFor
import com.muyuchat.core.download.fileKind
import com.muyuchat.core.download.kindLabel
import com.muyuchat.core.deviceprofile.DeviceAccelerationAnalyzer
import com.muyuchat.core.modelstore.ModelManifest
import java.util.Locale

internal data class RecommendationCatalog(
    val lightChat: List<ModelScopeRecommendedModel>,
    val mainChat: List<ModelScopeRecommendedModel>,
    val qualityChat: List<ModelScopeRecommendedModel>,
    val npuChat: List<ModelScopeRecommendedModel>,
    /** LiteRT-LM NPU cards are kept separate from the legacy NPU chat section. */
    val litertNpuModels: List<ModelScopeRecommendedModel> = emptyList(),
    val cpuImage: List<ModelScopeRecommendedModel>,
    val gpuImage: List<ModelScopeRecommendedModel>,
    val npuImageSd15: List<ModelScopeRecommendedModel>,
    val npuImageSdxl: List<ModelScopeRecommendedModel>,
    val npuImageGen5: List<ModelScopeRecommendedModel>
) {
    val npuImage: List<ModelScopeRecommendedModel>
        get() = npuImageSd15 + npuImageSdxl + npuImageGen5

    val litertCpu: List<ModelScopeRecommendedModel>
        get() = allChat.filter { it.chatRuntime == RecommendedChatRuntime.LITERT_LM && it.computeBackend == RecommendedComputeBackend.CPU }
    val litertGpu: List<ModelScopeRecommendedModel>
        get() = allChat.filter { it.chatRuntime == RecommendedChatRuntime.LITERT_LM && it.computeBackend == RecommendedComputeBackend.GPU }
    val litertNpu: List<ModelScopeRecommendedModel>
        get() = litertNpuModels

    private val allChat: List<ModelScopeRecommendedModel>
        get() = lightChat + mainChat + qualityChat + npuChat
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
    val comparator = recommendationComparator(deviceChipsetCode, deviceIsSnapdragon)
    fun modelsIn(section: RecommendedModelSection): List<ModelScopeRecommendedModel> =
        visibleModels.filter { it.section == section }.sortedWith(comparator)

    val cpuChat = modelsIn(RecommendedModelSection.CPU_CHAT)
    // Device discovery ranks packages and marks an unmatched package as an
    // experiment; it never removes a user-visible model from the catalog.
    val npuImage = modelsIn(RecommendedModelSection.NPU_IMAGE)
    val npuChatModels = modelsIn(RecommendedModelSection.NPU_CHAT)
    return RecommendationCatalog(
        lightChat = cpuChat.filter { it.group == ModelScopeRecommendedGroup.LIGHT_CHAT },
        mainChat = cpuChat.filter { it.group == ModelScopeRecommendedGroup.MAIN_CHAT },
        qualityChat = cpuChat.filter { it.group == ModelScopeRecommendedGroup.QUALITY_CHAT },
        npuChat = npuChatModels.filter { it.chatRuntime != RecommendedChatRuntime.LITERT_LM },
        litertNpuModels = npuChatModels.filter {
            it.chatRuntime == RecommendedChatRuntime.LITERT_LM &&
                it.computeBackend == RecommendedComputeBackend.NPU
        },
        cpuImage = modelsIn(RecommendedModelSection.CPU_IMAGE),
        gpuImage = modelsIn(RecommendedModelSection.GPU_IMAGE),
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

private fun recommendationComparator(
    deviceChipsetCode: String,
    deviceIsSnapdragon: Boolean
): Comparator<ModelScopeRecommendedModel> {
    // `priority` is the approved P0/P1/P2 order. RAM and verification state are
    // advisory card metadata. NPU packages get a device-fit tie breaker first
    // so a collapsed list presents a likely compatible transport, without
    // hiding the remaining packages.
    return compareBy<ModelScopeRecommendedModel> {
        if (it.section in setOf(RecommendedModelSection.NPU_CHAT, RecommendedModelSection.NPU_IMAGE)) {
            recommendationDeviceFitRank(recommendationDownloadAccess(it, deviceChipsetCode, deviceIsSnapdragon).deviceFit)
        } else {
            0
        }
    }.thenBy { it.priority }
        .thenBy { it.id }
}

private fun recommendationDeviceFitRank(fit: RecommendationDeviceFit): Int = when (fit) {
    RecommendationDeviceFit.EXACT -> 0
    RecommendationDeviceFit.VENDOR_GENERIC -> 1
    RecommendationDeviceFit.UNIVERSAL -> 2
    RecommendationDeviceFit.UNKNOWN -> 3
    RecommendationDeviceFit.CROSS_VENDOR -> 4
}

internal data class RecommendationDownloadAccess(
    val canDownload: Boolean,
    val experimental: Boolean,
    val deviceFit: RecommendationDeviceFit
)

/**
 * A recommendation confidence label, deliberately independent from access.
 * Native model loading remains the authority on whether a package runs.
 */
internal enum class RecommendationDeviceFit(val label: String) {
    UNIVERSAL("通用设备"),
    EXACT("芯片匹配"),
    VENDOR_GENERIC("骁龙设备"),
    CROSS_VENDOR("其他芯片平台"),
    UNKNOWN("设备信息未识别")
}

/**
 * The catalog keeps visibility and device-fit advice separate from download
 * access. An exact chipset match can improve the recommendation label, but a
 * missing match never removes the user's ability to download and try a model.
 */
internal fun recommendationDownloadAccess(
    model: ModelScopeRecommendedModel,
    deviceChipsetCode: String,
    deviceIsSnapdragon: Boolean = false,
    deviceSupportedAbis: List<String> = emptyList()
): RecommendationDownloadAccess {
    val normalizedDevice = deviceChipsetCode.trim().uppercase(Locale.ROOT)
    val exactChipsetMatch = model.matchesChipset(normalizedDevice)
    val eligibility = model.downloadEligibilityFor(deviceChipsetCode, deviceIsSnapdragon)
    val deviceFit = when {
        model.downloadPolicy == RecommendedModelDownloadPolicy.ALL_DEVICES ->
            RecommendationDeviceFit.UNIVERSAL
        exactChipsetMatch -> RecommendationDeviceFit.EXACT
        normalizedDevice.isBlank() -> RecommendationDeviceFit.UNKNOWN
        deviceIsSnapdragon || normalizedDevice.isSnapdragonChipsetCodeForRecommendation() ->
            RecommendationDeviceFit.VENDOR_GENERIC
        else -> RecommendationDeviceFit.CROSS_VENDOR
    }
    val expectedHtpArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(normalizedDevice)
    val declaredHtpArch = model.imageEngineBundle?.requiredRuntimeProfile?.htpArch
    val imageRuntimeMismatch = model.section == RecommendedModelSection.NPU_IMAGE &&
        declaredHtpArch != null && expectedHtpArch != null && declaredHtpArch != expectedHtpArch
    val normalizedAbis = deviceSupportedAbis
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
    val normalizedRequiredAbis = model.requiredAbis
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
    val runtimeAbiMismatch = normalizedRequiredAbis.isNotEmpty() &&
        normalizedAbis.isNotEmpty() && normalizedRequiredAbis.intersect(normalizedAbis).isEmpty()
    val experimental = model.status != RecommendedModelStatus.RECOMMENDED ||
        imageRuntimeMismatch ||
        runtimeAbiMismatch ||
        (model.section in setOf(RecommendedModelSection.NPU_CHAT, RecommendedModelSection.NPU_IMAGE) &&
            deviceFit !in setOf(RecommendationDeviceFit.EXACT, RecommendationDeviceFit.UNIVERSAL))
    return RecommendationDownloadAccess(
        canDownload = eligibility.canDownload,
        experimental = experimental,
        deviceFit = deviceFit
    )
}

private fun String.isSnapdragonChipsetCodeForRecommendation(): Boolean {
    if (isBlank()) return false
    if (contains("SNAPDRAGON") || contains("骁龙")) return true
    return matches(Regex("^(?:SM|SDM|MSM|APQ|QCS|QCM)[A-Z0-9_-]+$"))
}

internal fun recommendationQnnCompatibilityLine(
    model: ModelScopeRecommendedModel,
    deviceChipsetCode: String
): String? {
    val profile = model.imageEngineBundle?.requiredRuntimeProfile ?: return null
    val deviceArch = DeviceAccelerationAnalyzer.expectedQnnHtpArchVersionForChipsetCode(deviceChipsetCode)
        ?: return null
    if (deviceArch == profile.htpArch) return null
    return "模型包使用 QNN ${profile.qnnSdk} / HTP V${profile.htpArch}，当前设备为 HTP V$deviceArch。" +
        "若加载失败，可选择其他运行包或 CPU/GPU 模型。"
}

/**
 * Hardware fit is advisory only.  It is intentionally kept separate from the
 * engineering / verification state so a low-RAM warning never reads like a
 * download denial.
 */
internal fun recommendationHardwareLine(
    model: ModelScopeRecommendedModel,
    fitLabel: String
): String = "建议内存：${model.minRamGb} GB 及以上 · $fitLabel"

/** Download access is independent of internal engineering acceptance. */
internal fun recommendationDownloadCtaLabel(
    canDownload: Boolean
): String = when {
    !canDownload -> "暂不可下载"
    else -> "下载"
}

internal enum class RecommendedLocalBundleStatus {
    NONE,
    INSTALLED_UNVERIFIED,
    INSTALLED_VERIFIED,
    VERIFICATION_FAILED
}

internal fun recommendedLocalBundleStatus(
    model: ModelScopeRecommendedModel,
    localModels: List<LocalImageModelUiItem>
): RecommendedLocalBundleStatus {
    val local = localModels.firstOrNull { item ->
        item.recommendationId == model.id || item.recommendationId == model.imageEngineBundle?.id
    } ?: return RecommendedLocalBundleStatus.NONE
    return when {
        local.verificationStatus == "FAILED" -> RecommendedLocalBundleStatus.VERIFICATION_FAILED
        local.verificationStatus in setOf(
            "PASSED", "MNN_SMOKE_PASSED", "QNN_IMAGE_SMOKE_PASSED", "QNN_SMOKE_PASSED", "QNN_PIPELINE_PROBE_PASSED"
        ) -> RecommendedLocalBundleStatus.INSTALLED_VERIFIED
        else -> RecommendedLocalBundleStatus.INSTALLED_UNVERIFIED
    }
}

/**
 * Match a downloaded chat bundle to its catalog card using persisted source
 * identity. Display names and directory names are user-controlled and are not
 * strong enough to identify a recommendation.
 */
internal fun recommendedLocalChatModel(
    model: ModelScopeRecommendedModel,
    localModels: List<ModelManifest>
): ModelManifest? {
    val mnnBundle = model.mnnModelBundle
    val visionMain = model.visionModelBundle?.components?.firstOrNull {
        it.role == com.muyuchat.core.download.VisionModelBundleComponentRole.MAIN_MODEL
    }
    val sourceRepo = mnnBundle?.repoId ?: visionMain?.repoId ?: model.repoId
    val sourceRevision = mnnBundle?.revision ?: visionMain?.revision ?: model.revision
    val expectedFileName = model.recommendedFileName.normalizedModelFileName()
    val expectedFileStem = expectedFileName.removeSuffix(".zip")
    return localModels.firstOrNull { local ->
        val repoMatches = sourceRepo.isNotBlank() && local.repoId.equals(sourceRepo, ignoreCase = true)
        val revisionMatches = sourceRevision.isNotBlank() && local.revision.equals(sourceRevision, ignoreCase = true)
        val localFileName = local.fileName.normalizedModelFileName()
        val localFileStem = localFileName.removeSuffix(".zip")
        val fileMatches = localFileName == expectedFileName || localFileStem == expectedFileStem
        val exactSource = repoMatches && revisionMatches && (mnnBundle != null || fileMatches)
        val directFileMatch = fileMatches &&
            local.runtimeMatchesRecommendation(model)
        exactSource && local.runtimeMatchesRecommendation(model) || directFileMatch &&
            local.source != com.muyuchat.core.modelstore.ModelSource.LOCAL
    }
}

private fun ModelManifest.runtimeMatchesRecommendation(model: ModelScopeRecommendedModel): Boolean = when {
    model.mnnModelBundle != null -> runtime == com.muyuchat.core.modelstore.ChatModelRuntime.MNN
    model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT ->
        runtime == com.muyuchat.core.modelstore.ChatModelRuntime.GENIEX_QAIRT
    model.chatRuntime == RecommendedChatRuntime.LITERT_LM ->
        runtime == com.muyuchat.core.modelstore.ChatModelRuntime.LITERT_LM
    model.visionModelBundle?.runtime == com.muyuchat.core.download.VisionModelBundleRuntime.MNN_MULTIMODAL ->
        runtime == com.muyuchat.core.modelstore.ChatModelRuntime.MNN
    else -> runtime == com.muyuchat.core.modelstore.ChatModelRuntime.LLAMA_CPP
}

private fun String.normalizedModelFileName(): String = trim()
    .replace('\\', '/')
    .substringAfterLast('/')
    .lowercase(Locale.ROOT)

/** Filters only the already loaded file list; remote repository queries stay unchanged. */
internal fun filterRemoteModelFiles(
    files: List<RemoteModelFile>,
    query: String
): List<RemoteModelFile> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isBlank()) return files
    return files.filter { file ->
        listOf(
            file.name,
            file.path,
            file.repoId,
            file.revision,
            file.provider.label,
            file.fileKind().name,
            file.kindLabel()
        ).any { value -> value.lowercase(Locale.ROOT).contains(needle) }
    }
}

/** Keep the compact card focused on the model's purpose. */
internal fun ModelScopeRecommendedModel.recommendationShortDescription(): String {
    val body = description.trim()
    val end = body.indexOfFirst { it == '。' || it == '；' || it == '\n' }
    return if (end > 0) body.substring(0, end).trim() else body
}
