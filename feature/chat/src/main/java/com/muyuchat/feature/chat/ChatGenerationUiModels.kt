package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * T2B22-T1（b 工位）拆片：ImageGenerationUiPreset / ImageGenerationPresetField /
 * FileAssetUiItem / ImageGenerationUiJob / ImageGenerationUiTaskMode /
 * ImageGenerationUiPreviewMode / ImageGenerationUiOptions /
 * ImageGenerationUiUltraFixOptions / imageGenerationUltraFixDenoisingTailStepCount /
 * imageGenerationUltraFixStrengthForDenoisingSteps / IMAGE_GENERATION_ULTRAFIX_MAX_* /
 * IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION / ImageGenerationUiParameterSnapshot /
 * normalizedImageSamplerForCapabilities 自 ChatUiItemModels.kt 逐字节平移；零改写。
 */

@Immutable
data class ImageGenerationUiPreset(
    val prompt: String,
    val taskMode: ImageGenerationUiTaskMode? = null,
    val negativePrompt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val seed: Int? = null,
    val sampleMethod: String? = null,
    val clipSkip: Int? = null,
    val batchCount: Int? = null,
    val vaeTileSize: Int? = null,
    val vaeTileOverlap: Double? = null,
    val loras: List<ImageGenerationUiLoraSelection> = emptyList(),
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: ImageGenerationUiUltraFixOptions? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
)

internal enum class ImageGenerationPresetField(val label: String) {
    PROMPT("提示词"),
    NEGATIVE_PROMPT("负向提示词"),
    SIZE("尺寸"),
    STEPS("步数"),
    CFG("CFG"),
    SEED("Seed"),
    SAMPLER("采样器"),
    CLIP_SKIP("CLIP skip"),
    LORA("LoRA"),
    TEXTUAL_INVERSION("Textual Inversion"),
    ULTRAFIX("UltraFix"),
    STRENGTH("重绘强度"),
    CONTROL_STRENGTH("控制强度"),
    BATCH("批次数量"),
    VAE_TILING("VAE 分块")
}

@Immutable
data class FileAssetUiItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val preview: String,
    val createdAtText: String,
    val sizeText: String,
    val truncated: Boolean
)

@Immutable
data class ImageGenerationUiJob(
    val id: String,
    val prompt: String,
    val statusLabel: String,
    val modelId: String? = null,
    val modelName: String = "",
    val modelIsCloud: Boolean = false,
    val chatSessionId: String? = null,
    val chatMessageId: String? = null,
    val imageAssetId: String? = null,
    val imageAssetIds: List<String> = emptyList(),
    val completedProgressCount: Int = 0,
    val expectedOutputCount: Int = 1,
    val previewUriString: String? = null,
    val previewMode: String = "",
    val previewStep: Int = 0,
    val previewRevision: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val failed: Boolean = false,
    val terminal: Boolean = false,
    val message: String = "",
    val translationPreview: String? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val startedAtElapsedMs: Long? = null
)

enum class ImageGenerationUiTaskMode(val wireName: String, val label: String) {
    TEXT_TO_IMAGE("text_to_image", "文生图"),
    IMG2IMG("img2img", "图生图"),
    INPAINT("inpaint", "局部重绘"),
    CONTROL("control", "Control"),
    EDIT("edit", "编辑")
}

enum class ImageGenerationUiPreviewMode(val wireName: String) {
    PROJECTION("projection"),
    VAE("vae");

    companion object {
        fun fromWireNameOrNull(value: String?): ImageGenerationUiPreviewMode? {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
        }
    }
}

data class ImageGenerationUiOptions(
    val taskMode: ImageGenerationUiTaskMode = ImageGenerationUiTaskMode.TEXT_TO_IMAGE,
    val negativePrompt: String? = null,
    val inputImageUri: String? = null,
    val maskImageUri: String? = null,
    val controlImageUri: String? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null,
    val clipSkip: Int? = null,
    val batchCount: Int = 1,
    val loras: List<ImageGenerationUiLoraSelection> = emptyList(),
    val vaeTileSize: Int? = null,
    val vaeTileOverlap: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val cfgScale: Double? = null,
    val seed: Int? = null,
    val sampleMethod: String? = null,
    val previewMode: ImageGenerationUiPreviewMode? = null,
    val previewInterval: Int? = null,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: ImageGenerationUiUltraFixOptions? = null,
)

data class ImageGenerationUiUltraFixOptions(
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Double,
    val inversionSteps: Int,
    val refinementSteps: Int,
    val tileSize: Int,
    val overlap: Double,
)

/** Uses the same Float strength that crosses JNI into stable-diffusion.cpp. */
internal fun imageGenerationUltraFixDenoisingTailStepCount(
    refinementSteps: Int,
    strength: Double,
): Int {
    require(refinementSteps > 0)
    val wireStrength = strength.toFloat()
    require(wireStrength.isFinite() && wireStrength in 0.0f..1.0f)
    val beginIndex = (refinementSteps.toFloat() * (1.0f - wireStrength))
        .toInt()
        .coerceIn(0, refinementSteps - 1)
    return refinementSteps - beginIndex
}

/**
 * Local Dream exposes the effective denoising-step count instead of a raw strength. Choosing the
 * midpoint of the scheduler's integer interval keeps the Float value crossing JNI away from either
 * boundary, so the native begin index deterministically yields [denoisingSteps].
 */
internal fun imageGenerationUltraFixStrengthForDenoisingSteps(
    refinementSteps: Int,
    denoisingSteps: Int,
): Double {
    require(refinementSteps > 0)
    require(denoisingSteps in 1..refinementSteps)
    val strength = (denoisingSteps.toDouble() - 0.5) / refinementSteps.toDouble()
    check(imageGenerationUltraFixDenoisingTailStepCount(refinementSteps, strength) == denoisingSteps)
    return strength
}

internal const val IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS = 20
internal const val IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS = 10
// P9/S1: private→internal（只读常量；被新文件 ImageGenerationParameterNormalization.kt 引用）
internal const val IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION = 10

internal data class ImageGenerationUiParameterSnapshot(
    val taskModeName: String,
    val strengthText: String,
    val controlStrengthText: String,
    val negativePrompt: String,
    val disableModelNegativePrompt: Boolean,
    val clipSkipText: String,
    val vaeTilingEnabled: Boolean,
    val batchCount: Int,
    val widthText: String,
    val heightText: String,
    val stepsText: String,
    val cfgScaleText: String,
    /** Only values inserted by the Viggle preset are reverted when the adapter is removed. */
    val viggleAutoSteps: Boolean = false,
    val viggleAutoCfg: Boolean = false,
    val stepsEditedByUser: Boolean = false,
    val cfgEditedByUser: Boolean = false,
    val seedText: String,
    val sampler: String,
    val loras: List<ImageGenerationUiLoraDraft> = emptyList(),
    val inputImageUri: String? = null,
    val maskImageUri: String? = null,
    val controlImageUri: String? = null,
    val livePreviewMode: ImageGenerationUiPreviewMode? = null,
    val livePreviewEnabled: Boolean = true,
    val livePreviewInterval: Int = 1,
    val livePreviewIntervalExplicit: Boolean = false,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFixEnabled: Boolean = false,
    val ultraFixStrengthText: String = "0.35",
    val ultraFixInversionStepsText: String = "4",
    val ultraFixRefinementStepsText: String = "10",
    val ultraFixTileSizeText: String = "512",
    val ultraFixTileSizeExplicit: Boolean = false,
    val ultraFixOverlapText: String = "0.25",
    /** UltraFix target dimensions are transient execution dimensions, separate from normal size. */
    val ultraFixTargetWidthText: String = "",
    val ultraFixTargetHeightText: String = "",
    internal val sourceVersion: Int = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION)
        .put("taskModeName", taskModeName)
        .put("strengthText", strengthText)
        .put("controlStrengthText", controlStrengthText)
        .put("negativePrompt", negativePrompt)
        .put("disableModelNegativePrompt", disableModelNegativePrompt)
        .put("clipSkipText", clipSkipText)
        .put("vaeTilingEnabled", vaeTilingEnabled)
        .put("batchCount", batchCount)
        .put("widthText", widthText)
        .put("heightText", heightText)
        .put("stepsText", stepsText)
        .put("cfgScaleText", cfgScaleText)
        .put("viggleAutoSteps", viggleAutoSteps)
        .put("viggleAutoCfg", viggleAutoCfg)
        .put("stepsEditedByUser", stepsEditedByUser)
        .put("cfgEditedByUser", cfgEditedByUser)
        .put("seedText", seedText)
        .put("sampler", sampler)
        .put("livePreviewEnabled", livePreviewEnabled)
        .put("livePreviewInterval", livePreviewInterval.coerceIn(1, 10))
        .put("livePreviewIntervalExplicit", livePreviewIntervalExplicit)
        .put("textualInversionIds", JSONArray(textualInversionIds))
        .put("ultraFixEnabled", ultraFixEnabled)
        .put("ultraFixStrengthText", ultraFixStrengthText)
        .put("ultraFixInversionStepsText", ultraFixInversionStepsText)
        .put("ultraFixRefinementStepsText", ultraFixRefinementStepsText)
        .put("ultraFixTileSizeText", ultraFixTileSizeText)
        .put("ultraFixTileSizeExplicit", ultraFixTileSizeExplicit)
        .put("ultraFixOverlapText", ultraFixOverlapText)
        .put("ultraFixTargetWidthText", ultraFixTargetWidthText)
        .put("ultraFixTargetHeightText", ultraFixTargetHeightText)
        .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        .apply {
            livePreviewMode?.let { put("livePreviewMode", it.wireName) }
            inputImageUri?.let { put("inputImageUri", it) }
            maskImageUri?.let { put("maskImageUri", it) }
            controlImageUri?.let { put("controlImageUri", it) }
        }

    companion object {
        fun fromJsonOrNull(raw: String?): ImageGenerationUiParameterSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val version = json.optInt("version", -1)
                require(version in 1..IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION)
                val previewMode = if (version >= 5) {
                    json.optString("livePreviewMode").takeIf(String::isNotBlank)?.let { wireName ->
                        requireNotNull(ImageGenerationUiPreviewMode.fromWireNameOrNull(wireName)) {
                            "Unknown image live-preview mode."
                        }
                    }
                } else {
                    null
                }
                ImageGenerationUiParameterSnapshot(
                    taskModeName = json.getString("taskModeName"),
                    strengthText = json.getString("strengthText"),
                    controlStrengthText = json.getString("controlStrengthText"),
                    negativePrompt = json.getString("negativePrompt"),
                    disableModelNegativePrompt = json.getBoolean("disableModelNegativePrompt"),
                    clipSkipText = json.getString("clipSkipText"),
                    vaeTilingEnabled = json.getBoolean("vaeTilingEnabled"),
                    batchCount = json.getInt("batchCount").coerceIn(1, 8),
                    widthText = json.getString("widthText"),
                    heightText = json.getString("heightText"),
                    stepsText = json.getString("stepsText"),
                    cfgScaleText = json.getString("cfgScaleText"),
                    viggleAutoSteps = version >= 10 && json.optBoolean("viggleAutoSteps", false),
                    viggleAutoCfg = version >= 10 && json.optBoolean("viggleAutoCfg", false),
                    stepsEditedByUser = version >= 10 && json.optBoolean("stepsEditedByUser", false),
                    cfgEditedByUser = version >= 10 && json.optBoolean("cfgEditedByUser", false),
                    seedText = json.getString("seedText"),
                    sampler = json.getString("sampler"),
                    loras = if (version >= 3) {
                        val array = json.getJSONArray("loras")
                        require(array.length() <= 8)
                        buildList {
                            for (index in 0 until array.length()) {
                                add(ImageGenerationUiLoraDraft.fromJson(array.getJSONObject(index)))
                            }
                        }.also { drafts ->
                            require(drafts.map(ImageGenerationUiLoraDraft::id).distinct().size == drafts.size)
                        }
                    } else {
                        emptyList()
                    },
                    inputImageUri = if (version >= 2) {
                        json.optString("inputImageUri").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    maskImageUri = if (version >= 2) {
                        json.optString("maskImageUri").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    controlImageUri = if (version >= 2) {
                        json.optString("controlImageUri").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    livePreviewMode = previewMode,
                    livePreviewEnabled = if (version >= 4) {
                        json.getBoolean("livePreviewEnabled")
                    } else {
                        true
                    },
                    livePreviewInterval = if (version >= 4) {
                        json.getInt("livePreviewInterval").coerceIn(1, 10)
                    } else {
                        1
                    },
                    livePreviewIntervalExplicit =
                        version >= 5 &&
                            json.optBoolean("livePreviewIntervalExplicit", false),
                    textualInversionIds = if (version >= 6) {
                        json.optJSONArray("textualInversionIds")?.let { values ->
                            require(values.length() <= 8)
                            buildList {
                                for (index in 0 until values.length()) {
                                    add(java.util.UUID.fromString(values.getString(index)).toString())
                                }
                            }.also { ids -> require(ids.distinct().size == ids.size) }
                        }.orEmpty()
                    } else {
                        emptyList()
                    },
                    ultraFixEnabled = version >= 6 && json.optBoolean("ultraFixEnabled", false),
                    ultraFixStrengthText = json.optString("ultraFixStrengthText", "0.35"),
                    ultraFixInversionStepsText = json.optString("ultraFixInversionStepsText", "4"),
                    ultraFixRefinementStepsText = json.optString("ultraFixRefinementStepsText", "10"),
                    ultraFixTileSizeText = json.optString("ultraFixTileSizeText", "512"),
                    ultraFixTileSizeExplicit = version >= 7 &&
                        json.optBoolean("ultraFixTileSizeExplicit", false),
                    ultraFixOverlapText = json.optString("ultraFixOverlapText", "0.25"),
                    ultraFixTargetWidthText = if (version >= 9) {
                        json.optString("ultraFixTargetWidthText", "")
                    } else {
                        // Versions 6-8 stored the active UltraFix target in widthText/heightText.
                        // Keep those values as the target for a one-time, loss-minimizing migration;
                        // normalizedForImageModel supplies clean ordinary dimensions.
                        ""
                    },
                    ultraFixTargetHeightText = if (version >= 9) {
                        json.optString("ultraFixTargetHeightText", "")
                    } else {
                        ""
                    },
                    sourceVersion = version
                )
            }.getOrNull()
        }
    }
}

internal fun normalizedImageSamplerForCapabilities(
    current: String,
    supported: List<String>,
    defaultSampler: String
): String {
    val available = supported.distinct()
    if (available.isEmpty() || current in available) return current
    return defaultSampler.takeIf(available::contains) ?: available.first()
}
