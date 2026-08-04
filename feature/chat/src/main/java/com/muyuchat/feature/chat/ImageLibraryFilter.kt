package com.muyuchat.feature.chat

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal data class ImageLibraryDimensions(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0 && height > 0) { "Image library dimensions must be positive." }
    }

    val key: String = "${width}x${height}"
    val label: String = "${width}×${height}"

    companion object {
        fun from(image: ImageAssetUiItem): ImageLibraryDimensions? =
            if (image.width > 0 && image.height > 0) {
                ImageLibraryDimensions(image.width, image.height)
            } else {
                null
            }

        fun fromKeyOrNull(raw: String?): ImageLibraryDimensions? {
            val parts = raw?.split('x', limit = 2) ?: return null
            if (parts.size != 2) return null
            val width = parts[0].toIntOrNull() ?: return null
            val height = parts[1].toIntOrNull() ?: return null
            return runCatching { ImageLibraryDimensions(width, height) }.getOrNull()
        }
    }
}

internal data class ImageLibraryDateRange(
    val startInclusiveMillis: Long,
    val endExclusiveMillis: Long
) {
    init {
        require(startInclusiveMillis >= 0L) { "Image library date range start is invalid." }
        require(endExclusiveMillis > startInclusiveMillis) { "Image library date range must be non-empty." }
    }

    operator fun contains(timestampMillis: Long): Boolean =
        timestampMillis in startInclusiveMillis until endExclusiveMillis
}

/**
 * A history-facing operation is distinct from the execution task mode. In particular, UltraFix
 * executes through the img2img contract but must remain discoverable as its own library facet.
 */
internal object ImageLibraryOperationFacet {
    const val TEXT_TO_IMAGE = "text_to_image"
    const val IMG2IMG = "img2img"
    const val INPAINT = "inpaint"
    const val CONTROL = "control"
    const val EDIT = "edit"
    const val ULTRAFIX = "ultrafix"

    fun labelFor(wireName: String?): String? = when (wireName) {
        TEXT_TO_IMAGE -> "Text to image"
        IMG2IMG -> "Image to image"
        INPAINT -> "Inpaint"
        CONTROL -> "Control"
        EDIT -> "Edit"
        ULTRAFIX -> "UltraFix"
        else -> wireName?.takeIf(String::isNotBlank)
    }
}

/** Returns the stable operation facet without inferring anything from the current device/model. */
internal fun imageLibraryOperationWireName(image: ImageAssetUiItem): String? {
    image.generationOperation.trim().takeIf(String::isNotBlank)?.let { return it }
    if (image.generationPreset?.ultraFix != null) {
        return ImageLibraryOperationFacet.ULTRAFIX
    }
    return image.generationTaskMode.trim().takeIf(String::isNotBlank)
}

internal data class ImageLibraryFilter(
    val query: String = "",
    val favoritesOnly: Boolean = false,
    val modelId: String? = null,
    val taskMode: String? = null,
    /** Product operation facet; UltraFix is not represented as a different wire task mode. */
    val operation: String? = null,
    val dateRange: ImageLibraryDateRange? = null,
    val dimensions: ImageLibraryDimensions? = null,
    val scheduler: String? = null,
    val runtime: String? = null,
    val device: String? = null,
    val newestFirst: Boolean = true
)

internal fun filterImageLibrary(
    images: List<ImageAssetUiItem>,
    filter: ImageLibraryFilter
): List<ImageAssetUiItem> {
    val query = filter.query.trim().lowercase()
    val comparator = compareBy<ImageAssetUiItem> { it.createdAtMillis }
        .thenBy(ImageAssetUiItem::id)
        .let { if (filter.newestFirst) it.reversed() else it }
    return images.asSequence()
        .filter { !filter.favoritesOnly || it.favorite }
        .filter { filter.modelId == null || it.generationModelId == filter.modelId }
        .filter { filter.taskMode == null || it.generationTaskMode == filter.taskMode }
        .filter {
            filter.operation == null ||
                imageLibraryOperationWireName(it) == filter.operation
        }
        .filter { filter.dateRange == null || it.createdAtMillis in filter.dateRange }
        .filter { filter.dimensions == null || ImageLibraryDimensions.from(it) == filter.dimensions }
        .filter { filter.scheduler == null || it.generationSampler == filter.scheduler }
        .filter { filter.runtime == null || it.generationRuntime == filter.runtime }
        .filter { filter.device == null || it.generationDevice == filter.device }
        .filter { image ->
            query.isEmpty() || listOf(
                image.name,
                image.prompt,
                image.generationPrompt,
                image.generationModelName,
                image.generationSampler,
                image.generationRuntime,
                image.generationDevice,
                image.generationTaskMode,
                imageLibraryOperationWireName(image).orEmpty(),
                ImageLibraryOperationFacet.labelFor(imageLibraryOperationWireName(image)).orEmpty(),
                ImageLibraryDimensions.from(image)?.key.orEmpty()
            ).any { value -> query in value.lowercase() }
        }
        .sortedWith(comparator)
        .toList()
}

/**
 * Material date pickers expose UTC-midnight values that represent calendar dates. Convert those
 * dates to local-day boundaries before comparing them with real creation timestamps.
 */
internal fun imageLibraryDateRangeFromUtcPickerSelection(
    selectedStartDateMillis: Long?,
    selectedEndDateMillis: Long?,
    zoneId: ZoneId = ZoneId.systemDefault()
): ImageLibraryDateRange? {
    val startSelection = selectedStartDateMillis ?: return null
    val endSelection = selectedEndDateMillis ?: startSelection
    if (startSelection < 0L || endSelection < startSelection) return null
    return runCatching {
        val startDate = Instant.ofEpochMilli(startSelection)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        val endDate = Instant.ofEpochMilli(endSelection)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        ImageLibraryDateRange(
            startInclusiveMillis = startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endExclusiveMillis = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
    }.getOrNull()
}
