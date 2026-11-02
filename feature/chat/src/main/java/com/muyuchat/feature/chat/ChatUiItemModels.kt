package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Immutable
import org.json.JSONObject

// P9/S2：从 ChatScreen.kt 等值移出的聊天/图像 UI 条目模型与小助手簇。
// 原样逐字迁移，未做行为改动；同包解析，调用点无需变更。
internal val IMAGE_TEXTUAL_INVERSION_ALL_FORMATS: Set<String> = setOf(
    "safetensors",
    "pytorch",
    "checkpoint",
    "binary"
)

// P9/S2: private→internal（纯分类器；跨文件被 ChatScreen 复合体引用）
internal fun ImageTextualInversionUiItem.isSupportedBy(formats: Set<String>): Boolean =
    format.trim().lowercase() in formats

@Immutable
data class ImageUpscalerUiItem(
    val id: String,
    val name: String,
    val sizeText: String,
    val sha256: String,
    val selected: Boolean = false,
    val inUse: Boolean = false,
    val deleting: Boolean = false
)

@Immutable
data class ImageUpscaleUiJob(
    val id: String,
    val sourceImageId: String,
    val upscalerId: String,
    val upscalerName: String,
    val targetScale: Int,
    val statusLabel: String,
    val running: Boolean,
    val failed: Boolean,
    val terminal: Boolean,
    val resultImageAssetId: String? = null,
    val message: String = ""
)

internal val IMAGE_UPSCALE_TARGET_SCALES: List<Int> = listOf(2, 3, 4)

internal fun upscaleOutputDimensionsOrNull(width: Int, height: Int, scale: Int): Pair<Int, Int>? {
    if (width !in 1..2_048 || height !in 1..2_048 ||
        scale !in IMAGE_UPSCALE_TARGET_SCALES
    ) return null
    return runCatching {
        val sourcePixels = Math.multiplyExact(width.toLong(), height.toLong())
        val outputWidth = Math.multiplyExact(width, scale)
        val outputHeight = Math.multiplyExact(height, scale)
        val outputPixels = Math.multiplyExact(outputWidth.toLong(), outputHeight.toLong())
        (outputWidth to outputHeight).takeIf {
            sourcePixels <= 4_000_000L &&
                outputWidth <= 4_096 && outputHeight <= 4_096 &&
                outputPixels <= 16_000_000L
        }
    }.getOrNull()
}

@Immutable
data class ImageGenerationUiLoraSelection(
    val id: String,
    val multiplier: Double
)

@Immutable
internal data class ImageGenerationUiLoraDraft(
    val id: String,
    val multiplierText: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("multiplierText", multiplierText)

    companion object {
        fun fromJson(json: JSONObject): ImageGenerationUiLoraDraft =
            ImageGenerationUiLoraDraft(
                id = json.getString("id").trim().also { require(it.isNotEmpty()) },
                multiplierText = json.getString("multiplierText").trim().take(32)
            )
    }
}

