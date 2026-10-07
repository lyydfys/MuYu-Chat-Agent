package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

internal data class ImageWorkspaceLibraryFilters(
    val models: List<Pair<String, String>>,
    val taskModes: List<String>,
    val operations: List<String>,
    val dimensions: List<ImageLibraryDimensions>,
    val schedulers: List<String>,
    val runtimes: List<String>,
    val devices: List<String>,
    val dateRange: ImageLibraryDateRange?,
    val dimensionsFilter: ImageLibraryDimensions?,
    val filteredImages: List<ImageAssetUiItem>
)

@Composable
internal fun rememberImageWorkspaceLibraryFilters(
    images: List<ImageAssetUiItem>,
    libraryQuery: String,
    favoritesOnly: Boolean,
    libraryModelId: String?,
    libraryTaskMode: String?,
    libraryOperation: String?,
    libraryDateStartUtcMillis: Long?,
    libraryDateEndUtcMillis: Long?,
    libraryDimensionsKey: String?,
    libraryScheduler: String?,
    libraryRuntime: String?,
    libraryDevice: String?,
    newestFirst: Boolean
): ImageWorkspaceLibraryFilters {
    val libraryModels = remember(images) {
        images.mapNotNull { image ->
            image.generationModelId.takeIf(String::isNotBlank)?.let { id ->
                id to image.generationModelName.ifBlank { id }
            }
        }.distinctBy { it.first }
    }
    val libraryTaskModes = remember(images) {
        images.map(ImageAssetUiItem::generationTaskMode)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryOperations = remember(images) {
        images.mapNotNull { imageLibraryOperationWireName(it) }
            .distinct()
            .sorted()
    }
    val libraryDimensions = remember(images) {
        images.mapNotNull(ImageLibraryDimensions::from)
            .distinct()
            .sortedWith(compareBy<ImageLibraryDimensions> { it.width * it.height }.thenBy { it.key })
    }
    val librarySchedulers = remember(images) {
        images.map(ImageAssetUiItem::generationSampler)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryRuntimes = remember(images) {
        images.map(ImageAssetUiItem::generationRuntime)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryDevices = remember(images) {
        images.map(ImageAssetUiItem::generationDevice)
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }
    val libraryDateRange = remember(libraryDateStartUtcMillis, libraryDateEndUtcMillis) {
        imageLibraryDateRangeFromUtcPickerSelection(
            selectedStartDateMillis = libraryDateStartUtcMillis,
            selectedEndDateMillis = libraryDateEndUtcMillis
        )
    }
    val libraryDimensionsFilter = remember(libraryDimensionsKey) {
        ImageLibraryDimensions.fromKeyOrNull(libraryDimensionsKey)
    }
    val filteredImages = remember(
        images,
        libraryQuery,
        favoritesOnly,
        libraryModelId,
        libraryTaskMode,
        libraryOperation,
        libraryDateRange,
        libraryDimensionsFilter,
        libraryScheduler,
        libraryRuntime,
        libraryDevice,
        newestFirst
    ) {
        filterImageLibrary(
            images = images,
            filter = ImageLibraryFilter(
                query = libraryQuery,
                favoritesOnly = favoritesOnly,
                modelId = libraryModelId,
                taskMode = libraryTaskMode,
                operation = libraryOperation,
                dateRange = libraryDateRange,
                dimensions = libraryDimensionsFilter,
                scheduler = libraryScheduler,
                runtime = libraryRuntime,
                device = libraryDevice,
                newestFirst = newestFirst
            )
        )
    }
    return ImageWorkspaceLibraryFilters(
        models = libraryModels,
        taskModes = libraryTaskModes,
        operations = libraryOperations,
        dimensions = libraryDimensions,
        schedulers = librarySchedulers,
        runtimes = libraryRuntimes,
        devices = libraryDevices,
        dateRange = libraryDateRange,
        dimensionsFilter = libraryDimensionsFilter,
        filteredImages = filteredImages
    )
}
