package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

/**
 * T2B22-T1（b 工位）拆片：ImageAssetUiItem / WorldBookUiItem /
 * WorldBookImportScope / KnowledgeBaseUiItem / imageAssetBadgeText 自
 * ChatUiItemModels.kt 逐字节平移；零改写。
 */

data class ImageAssetUiItem(
    val id: String,
    val name: String,
    val uriString: String,
    val source: String,
    val prompt: String,
    val createdAtText: String,
    val sizeText: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = 0L,
    val upscaleTargetScale: Int? = null,
    val generationDetails: String = "",
    val generationPrompt: String = "",
    val generationModelId: String = "",
    val generationModelName: String = "",
    val generationTaskMode: String = "",
    /** Product history operation; unlike task mode this can distinguish UltraFix from img2img. */
    val generationOperation: String = "",
    val generationSampler: String = "",
    val parameterShareJson: String = "",
    val generationPreset: ImageGenerationUiPreset? = null,
    val favorite: Boolean = false,
    val canRecreate: Boolean = false,
    val createdAtMillis: Long = 0L,
    val generationRuntime: String = "",
    val generationDevice: String = ""
)

data class WorldBookUiItem(
    val id: String,
    val name: String,
    val entryCount: Int,
    val scopeLabel: String,
    val enabled: Boolean = true,
    val constantEntryCount: Int = 0,
    val keywordEntryCount: Int = 0
)

/** Scope selected before the host launches a world-book document picker. */
enum class WorldBookImportScope(val label: String) {
    GLOBAL("全局"),
    ASSISTANT("当前角色"),
    CHAT("当前对话")
}

data class KnowledgeBaseUiItem(
    val id: String,
    val name: String,
    val description: String,
    val selected: Boolean,
    val indexStateLabel: String,
    val documentCount: Int = 0,
    val importing: Boolean = false
)

internal fun imageAssetBadgeText(image: ImageAssetUiItem): String = when {
    image.source.startsWith("upscaled:", ignoreCase = true) ->
        image.upscaleTargetScale
            ?.takeIf(IMAGE_UPSCALE_TARGET_SCALES::contains)
            ?.let { scale -> "ESRGAN ${scale}×" }
            ?: "高清放大"
    image.width > 0 && image.height > 0 -> "${image.width}×${image.height}"
    else -> ""
}
