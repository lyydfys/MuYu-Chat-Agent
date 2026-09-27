package com.muyuchat.feature.modelhub

import com.muyuchat.core.download.ImageEngineBundleRuntime
import com.muyuchat.core.download.MnnModelBundleInstallProfile
import com.muyuchat.core.download.ModelScopeRecommendedKind
import com.muyuchat.core.download.ModelScopeRecommendedModel
import com.muyuchat.core.download.RecommendedChatRuntime
import com.muyuchat.core.download.RecommendedModelStatus
import java.util.Locale

private const val QWEN_IMAGE_21_RECOMMENDATION_ID = "qwen_image_21_mnn_opencl"
private const val QWEN_IMAGE_21_SIZE_LINE =
    "尺寸：MNN 已验证 21 个组合。Standard（7 种比例）：512×512、576×448、448×576、640×416、416×640、672×384、384×672；Fast：384×384、448×320、320×448、480×320、320×480、512×288、288×512；Tiny：320×320、384×288、288×384、384×256、256×384、416×256、256×416。默认 512×512；官方原始模型的 2K 示例需要另一套高分辨率运行包。"

internal fun recommendationSpecificationLine(model: ModelScopeRecommendedModel): String {
    val format = when {
        model.imageEngineBundle?.runtime == ImageEngineBundleRuntime.QNN_HTP -> "QNN"
        model.imageEngineBundle?.runtime == ImageEngineBundleRuntime.MNN_DIFFUSION -> "MNN"
        model.mnnModelBundle != null || model.chatRuntime == RecommendedChatRuntime.MNN -> "MNN"
        model.chatRuntime == RecommendedChatRuntime.GENIEX_QAIRT -> "QNN"
        model.chatRuntime == RecommendedChatRuntime.LITERT_LM -> "LiteRT-LM"
        model.recommendedFileName.endsWith(".safetensors", ignoreCase = true) -> "Safetensors"
        else -> "GGUF"
    }
    return "格式：" + listOf(format, model.parameterScale, model.quant)
        .filter { it.isNotBlank() }.distinct().joinToString(" · ")
}

internal fun recommendationCapabilityLine(model: ModelScopeRecommendedModel): String {
    if (model.status == RecommendedModelStatus.PENDING_INTEGRATION) {
        return "MCA 暂不支持此模型"
    }
    model.imageEngineBundle?.let { bundle ->
        val profile = bundle.executionProfile
        return listOfNotNull(
            bundle.task.label,
            profile?.defaults?.let { "${it.width}×${it.height}" },
            "LoRA".takeIf { profile?.capabilities?.supportsLora == true }
        ).joinToString(" · ")
    }
    val textOnly = model.mnnModelBundle?.installProfile == MnnModelBundleInstallProfile.TEXT_ONLY
    val hasVision = !textOnly && (
        model.kind == ModelScopeRecommendedKind.VISION || model.visionModelBundle != null ||
            model.mnnModelBundle?.components?.any {
                it.required && it.fileName.substringAfterLast('/') == "visual.mnn"
            } == true ||
            "图文聊天" in model.tags
        )
    return when {
        hasVision && model.visionModelBundle?.downloadProjectorByDefault == false ->
            "文本聊天 · 图片理解需另下视觉组件"
        hasVision -> "文本聊天 · 图片理解"
        else -> "文本聊天"
    }
}

/**
 * Explain the distinction between upstream model examples and the exact
 * executable bundle published in MCA. This is intentionally a separate line
 * from the generic capability summary so other image models keep their compact
 * card presentation.
 */
internal fun recommendationImageSizeLine(model: ModelScopeRecommendedModel): String? =
    QWEN_IMAGE_21_SIZE_LINE.takeIf { model.id == QWEN_IMAGE_21_RECOMMENDATION_ID }

/**
 * Explain native-runtime ABI limits before a user starts a large download.
 * Download remains available by product policy, but a known mismatch must be
 * explicit so an x86_64 user does not mistake a successful download for an
 * executable model.
 */
internal fun recommendationRuntimeCompatibilityLine(
    model: ModelScopeRecommendedModel,
    deviceSupportedAbis: List<String>
): String? {
    val required = model.requiredAbis
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
    if (required.isEmpty()) return null
    val device = deviceSupportedAbis
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotBlank() }
        .toSet()
    if (device.isEmpty() || required.intersect(device).isNotEmpty()) return null
    val requiredLabel = required.joinToString(" / ")
    val deviceLabel = device.joinToString(" / ")
    return "运行库仅支持 $requiredLabel；此设备支持 $deviceLabel，无法在此设备运行。"
}

/** Plain-language copy for an unavailable backend variant. */
internal fun recommendationDownloadBlockLine(reason: String?): String {
    val value = reason?.trim().orEmpty()
    if (value.isBlank()) return "当前没有可下载的模型文件。"
    return when {
        value.contains("没有可确认的 E4B LiteRT-LM Qualcomm") ->
            "当前没有此型号的 Qualcomm NPU 版本，可选择 CPU 或 GPU 版本。"
        value.contains("没有可确认的 Qualcomm LiteRT-LM") ->
            "当前没有此型号的 Qualcomm NPU 版本，可选择 CPU 或 GPU 版本。"
        else -> value
    }
}

internal fun recommendationNpuImageSectionDescription(): String =
    "Qualcomm QNN / HTP 生图模型，设备兼容信息会显示在对应模型卡片中。"

/** Only files in the default download plan count; optional add-ons must not inflate the card. */
internal fun recommendationDownloadSizeBytes(model: ModelScopeRecommendedModel): Long? {
    val components = model.imageEngineBundle?.components?.takeIf { it.isNotEmpty() } ?: return null
    val uniqueComponents = components
        .filter { it.downloadByDefault }
        .distinctBy { listOf(it.provider, it.repoId, it.revision, it.fileName) }
        .takeIf { it.isNotEmpty() }
        ?: return null
    if (uniqueComponents.any { it.expectedSizeBytes?.let { size -> size > 0L } != true }) return null
    return uniqueComponents.fold(0L) { total, component ->
        val size = requireNotNull(component.expectedSizeBytes)
        if (Long.MAX_VALUE - total < size) return null
        total + size
    }
}
