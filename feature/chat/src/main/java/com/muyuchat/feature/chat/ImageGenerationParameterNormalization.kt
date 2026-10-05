package com.muyuchat.feature.chat

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width

// P9/S1：从 ChatScreen.kt 等值移出的“图像生成 UI 参数规范化”纯函数簇。
// 原样逐字迁移，未做行为改动；同包解析，调用点无需变更。
internal fun ChatModelChoice.resolvedImagePreviewMode(): ImageGenerationUiPreviewMode? =
    imagePreviewMode

internal fun ChatModelChoice.resolvedImageUltraFixDefaultTileSize(): Int {
    imageUltraFixRequiredTileSize.takeIf { it > 0 }?.let { return it }
    if (!supportsImageUltraFix) return 512

    val widthMultiple = imageUltraFixWidthMultiple.coerceAtLeast(1)
    val heightMultiple = imageUltraFixHeightMultiple.coerceAtLeast(1)
    val commonMultiple = leastCommonMultiple(widthMultiple, heightMultiple)
    val upperBound = minOf(
        1024,
        imageDefaultWidth,
        imageDefaultHeight,
        imageUltraFixMaxWidth,
        imageUltraFixMaxHeight,
        2048,
    ).toLong()
    val aligned = (upperBound / commonMultiple) * commonMultiple
    check(aligned in 128L..2048L) {
        "UltraFix capabilities do not expose a valid default tile."
    }
    return aligned.toInt()
}

internal fun ChatModelChoice.ultraFixTargetSizeForSourceOrNull(
    sourceWidth: Int,
    sourceHeight: Int,
): Pair<Int, Int>? {
    if (!supportsImageUltraFix || sourceWidth <= 0 || sourceHeight <= 0) return null
    val tile = resolvedImageUltraFixDefaultTileSize()

    fun alignedAxis(source: Int, minimum: Int, maximum: Int, multiple: Int): Int? {
        if (maximum <= 0 || multiple <= 0) return null
        val requested = maxOf(source, minimum, tile).toLong()
        val step = multiple.toLong()
        val aligned = ((requested + step - 1L) / step) * step
        return aligned.takeIf { it <= maximum.toLong() }?.toInt()
    }

    val width = alignedAxis(
        sourceWidth,
        imageUltraFixMinWidth,
        imageUltraFixMaxWidth,
        imageUltraFixWidthMultiple,
    ) ?: return null
    val height = alignedAxis(
        sourceHeight,
        imageUltraFixMinHeight,
        imageUltraFixMaxHeight,
        imageUltraFixHeightMultiple,
    ) ?: return null
    if (width.toLong() * height.toLong() > 64L * 1024L * 1024L) return null
    return width to height
}

internal data class ImageGenerationUiUltraFixTileSelection(
    val tileSize: Int,
    val explicit: Boolean,
)

internal fun normalizedImageGenerationUltraFixTileSelection(
    model: ChatModelChoice,
    rawValue: String,
    explicit: Boolean,
    sourceVersion: Int,
): ImageGenerationUiUltraFixTileSelection {
    model.imageUltraFixRequiredTileSize.takeIf { it > 0 }?.let { required ->
        return ImageGenerationUiUltraFixTileSelection(required, explicit = false)
    }

    val defaultTile = model.resolvedImageUltraFixDefaultTileSize()
    val parsed = rawValue.trim().toIntOrNull()
    val valid = parsed != null && parsed in 128..2048 &&
        parsed <= model.imageUltraFixMaxWidth &&
        parsed <= model.imageUltraFixMaxHeight &&
        parsed % model.imageUltraFixWidthMultiple.coerceAtLeast(1) == 0 &&
        parsed % model.imageUltraFixHeightMultiple.coerceAtLeast(1) == 0
    val inferredExplicit = when {
        sourceVersion >= 7 -> explicit
        rawValue.trim() == "512" -> false
        else -> valid
    }
    return if (inferredExplicit && valid) {
        ImageGenerationUiUltraFixTileSelection(requireNotNull(parsed), explicit = true)
    } else {
        ImageGenerationUiUltraFixTileSelection(defaultTile, explicit = false)
    }
}

private fun leastCommonMultiple(first: Int, second: Int): Long {
    fun greatestCommonDivisor(left: Long, right: Long): Long {
        var a = left
        var b = right
        while (b != 0L) {
            val remainder = a % b
            a = b
            b = remainder
        }
        return a
    }

    val left = first.coerceAtLeast(1).toLong()
    val right = second.coerceAtLeast(1).toLong()
    return (left / greatestCommonDivisor(left, right)) * right
}

internal fun ChatModelChoice.imageSupportedSamplersForTask(
    taskMode: ImageGenerationUiTaskMode
): List<String> = when (taskMode) {
    ImageGenerationUiTaskMode.IMG2IMG,
    ImageGenerationUiTaskMode.INPAINT -> imageImg2ImgSupportedSamplers
    else -> imageSupportedSamplers
}.distinct()

internal data class ImageGenerationUiPreviewRequest(
    val mode: ImageGenerationUiPreviewMode,
    val interval: Int
)

internal fun imageGenerationUiPreviewRequestOrNull(
    model: ChatModelChoice?,
    enabled: Boolean,
    interval: Int
): ImageGenerationUiPreviewRequest? = model
    ?.resolvedImagePreviewMode()
    ?.takeIf { enabled }
    ?.let { mode ->
        ImageGenerationUiPreviewRequest(
            mode = mode,
            interval = interval.coerceIn(1, 10)
        )
    }

internal fun shouldShowImageGenerationVaePreviewCostWarning(
    mode: ImageGenerationUiPreviewMode?,
    enabled: Boolean,
    interval: Int
): Boolean = mode == ImageGenerationUiPreviewMode.VAE && enabled && interval == 1

internal fun normalizedImageGenerationDimensionText(
    rawValue: String,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int,
    multiple: Int
): String {
    val safeMin = minValue.coerceAtLeast(1)
    val safeMax = maxValue.coerceAtLeast(safeMin)
    if (safeMin == safeMax) return safeMin.toString()

    val step = multiple.coerceAtLeast(1).toLong()
    val min = safeMin.toLong()
    val max = safeMax.toLong()
    val firstAligned = ((min + step - 1L) / step) * step
    val lastAligned = (max / step) * step
    if (firstAligned > lastAligned) {
        return defaultValue.coerceIn(safeMin, safeMax).toString()
    }

    val requested = rawValue.trim().toLongOrNull() ?: defaultValue.toLong()
    val bounded = requested.coerceIn(firstAligned, lastAligned)
    val lower = ((bounded / step) * step).coerceAtLeast(firstAligned)
    val upper = (lower + step).coerceAtMost(lastAligned)
    val normalized = if (bounded - lower <= upper - bounded) lower else upper
    return normalized.toString()
}

internal fun normalizedImageGenerationStepsText(
    rawValue: String,
    defaultValue: Int,
    minValue: Int,
    maxValue: Int
): String {
    val safeMin = minValue.coerceAtLeast(1)
    val safeMax = maxValue.coerceAtLeast(safeMin)
    val safeDefault = defaultValue.coerceIn(safeMin, safeMax)
    return (rawValue.trim().toLongOrNull() ?: safeDefault.toLong())
        .coerceIn(safeMin.toLong(), safeMax.toLong())
        .toString()
}

internal fun ImageGenerationUiParameterSnapshot.normalizedForImageModel(
    model: ChatModelChoice
): ImageGenerationUiParameterSnapshot {
    if (model.cloud) return this
    val taskMode = ImageGenerationUiTaskMode.entries.firstOrNull { it.name == taskModeName }
        ?: ImageGenerationUiTaskMode.TEXT_TO_IMAGE
    val supportedSamplers = model.imageSupportedSamplersForTask(taskMode)
    val targetPreviewMode = model.resolvedImagePreviewMode()
    val targetPreviewInterval = model.imageDefaultPreviewInterval.coerceIn(1, 10)
    val currentPreviewInterval = livePreviewInterval.coerceIn(1, 10)
    val inferredLegacyExplicitInterval = sourceVersion <= 4 && when (targetPreviewMode) {
        ImageGenerationUiPreviewMode.VAE -> currentPreviewInterval != 1
        ImageGenerationUiPreviewMode.PROJECTION -> currentPreviewInterval != targetPreviewInterval
        null -> false
    }
    val normalizedPreviewIntervalExplicit =
        livePreviewIntervalExplicit || inferredLegacyExplicitInterval
    val normalizedPreviewInterval = when {
        targetPreviewMode == null -> currentPreviewInterval
        normalizedPreviewIntervalExplicit -> currentPreviewInterval
        sourceVersion <= 4 && targetPreviewMode == ImageGenerationUiPreviewMode.PROJECTION ->
            currentPreviewInterval
        else -> targetPreviewInterval
    }
    val normalizedUltraFixTotalSteps = ultraFixRefinementStepsText.toIntOrNull()
        ?.coerceIn(1, IMAGE_GENERATION_ULTRAFIX_MAX_REFINEMENT_STEPS)
        ?: 10
    val legacyDenoisingSteps = ultraFixStrengthText.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?.coerceIn(0.0, 1.0)
        ?.let { strength ->
            imageGenerationUltraFixDenoisingTailStepCount(
                normalizedUltraFixTotalSteps,
                strength,
            )
        }
        ?: 4
    val normalizedUltraFixInversionSteps = ultraFixInversionStepsText.toIntOrNull()
        ?.coerceIn(
            1,
            minOf(
                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                normalizedUltraFixTotalSteps,
            ),
        )
        ?: legacyDenoisingSteps.coerceIn(
            1,
            minOf(
                IMAGE_GENERATION_ULTRAFIX_MAX_DENOISING_STEPS,
                normalizedUltraFixTotalSteps,
            ),
        )
    val normalizedUltraFixStrength = imageGenerationUltraFixStrengthForDenoisingSteps(
        normalizedUltraFixTotalSteps,
        normalizedUltraFixInversionSteps,
    )
    val normalizedUltraFixEnabled = ultraFixEnabled && model.supportsImageUltraFix &&
        taskMode == ImageGenerationUiTaskMode.IMG2IMG
    val normalizedUltraFixTile = normalizedImageGenerationUltraFixTileSelection(
        model = model,
        rawValue = ultraFixTileSizeText,
        explicit = ultraFixTileSizeExplicit,
        sourceVersion = sourceVersion,
    )
    val normalizedUltraFixOverlap = ultraFixOverlapText.toDoubleOrNull()
        ?.takeIf(Double::isFinite)
        ?.coerceIn(0.0, 0.5)
        ?: 0.25
    val legacyUltraFixTargetWidth = if (ultraFixTargetWidthText.isBlank() && ultraFixEnabled) {
        widthText
    } else {
        ultraFixTargetWidthText
    }
    val legacyUltraFixTargetHeight = if (ultraFixTargetHeightText.isBlank() && ultraFixEnabled) {
        heightText
    } else {
        ultraFixTargetHeightText
    }
    val ordinaryWidthRaw = if (sourceVersion < 9 && ultraFixEnabled) {
        model.imageDefaultWidth.toString()
    } else {
        widthText
    }
    val ordinaryHeightRaw = if (sourceVersion < 9 && ultraFixEnabled) {
        model.imageDefaultHeight.toString()
    } else {
        heightText
    }
    val normalizedUltraFixTargetWidth = normalizedImageGenerationDimensionText(
        rawValue = legacyUltraFixTargetWidth,
        defaultValue = model.imageDefaultWidth,
        minValue = maxOf(model.imageUltraFixMinWidth, normalizedUltraFixTile.tileSize),
        maxValue = model.imageUltraFixMaxWidth,
        multiple = model.imageUltraFixWidthMultiple
    )
    val normalizedUltraFixTargetHeight = normalizedImageGenerationDimensionText(
        rawValue = legacyUltraFixTargetHeight,
        defaultValue = model.imageDefaultHeight,
        minValue = maxOf(model.imageUltraFixMinHeight, normalizedUltraFixTile.tileSize),
        maxValue = model.imageUltraFixMaxHeight,
        multiple = model.imageUltraFixHeightMultiple
    )
    return copy(
        livePreviewMode = targetPreviewMode,
        livePreviewInterval = normalizedPreviewInterval,
        livePreviewIntervalExplicit = normalizedPreviewIntervalExplicit,
        textualInversionIds = textualInversionIds
            .distinct()
            .take(8)
            .takeIf { model.supportsImageTextualInversion }
            .orEmpty(),
        ultraFixEnabled = normalizedUltraFixEnabled,
        ultraFixStrengthText = normalizedUltraFixStrength.toString(),
        ultraFixInversionStepsText = normalizedUltraFixInversionSteps.toString(),
        ultraFixRefinementStepsText = normalizedUltraFixTotalSteps.toString(),
        ultraFixTileSizeText = normalizedUltraFixTile.tileSize.toString(),
        ultraFixTileSizeExplicit = normalizedUltraFixTile.explicit,
        ultraFixOverlapText = normalizedUltraFixOverlap.toString(),
        ultraFixTargetWidthText = normalizedUltraFixTargetWidth,
        ultraFixTargetHeightText = normalizedUltraFixTargetHeight,
        sourceVersion = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION,
        widthText = normalizedImageGenerationDimensionText(
            rawValue = ordinaryWidthRaw,
            defaultValue = model.imageDefaultWidth,
            minValue = model.imageMinWidth,
            maxValue = model.imageMaxWidth,
            multiple = model.imageWidthMultiple
        ),
        heightText = normalizedImageGenerationDimensionText(
            rawValue = ordinaryHeightRaw,
            defaultValue = model.imageDefaultHeight,
            minValue = model.imageMinHeight,
            maxValue = model.imageMaxHeight,
            multiple = model.imageHeightMultiple
        ),
        stepsText = normalizedImageGenerationStepsText(
            rawValue = stepsText,
            defaultValue = model.imageDefaultSteps,
            minValue = model.imageMinSteps,
            maxValue = model.imageMaxSteps
        ),
        sampler = normalizedImageSamplerForCapabilities(
            current = sampler,
            supported = supportedSamplers,
            defaultSampler = model.imageDefaultSampler
        )
    )
}
