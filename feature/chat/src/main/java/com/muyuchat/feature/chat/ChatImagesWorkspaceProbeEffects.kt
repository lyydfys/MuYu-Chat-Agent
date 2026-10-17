package com.muyuchat.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun ImagesWorkspaceProbeEffectsSection(bindings: ImagesWorkspaceOverlayBindings) {
    with(bindings) {
    LaunchedEffect(imageInputUri) {
        val sourceUri = imageInputUri
        imageInputDimensionsProbeFailed = false
        if (sourceUri == null) {
            imageInputDimensionsUri = null
            imageInputSourceWidth = 0
            imageInputSourceHeight = 0
            imageInputDimensionsProbing = false
            return@LaunchedEffect
        }
        if (imageInputDimensionsUri != sourceUri) {
            imageInputSourceWidth = 0
            imageInputSourceHeight = 0
        }
        imageInputDimensionsProbing = true
        try {
            val dimensions = withContext(Dispatchers.IO) {
                val probeJob = currentCoroutineContext()[Job]
                ImageGenerationBitmapEditing.probeDimensionsBounded(
                    context = context,
                    rawUri = sourceUri,
                    checkCancelled = { probeJob?.ensureActive() }
                )
            }
            if (imageInputUri == sourceUri) {
                imageInputDimensionsUri = sourceUri
                imageInputSourceWidth = dimensions.width
                imageInputSourceHeight = dimensions.height
                imageInputDimensionsProbeFailed = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (imageInputUri == sourceUri) {
                imageInputDimensionsUri = sourceUri
                imageInputSourceWidth = 0
                imageInputSourceHeight = 0
                imageInputDimensionsProbeFailed = true
            }
        } finally {
            if (imageInputUri == sourceUri) imageInputDimensionsProbing = false
        }
    }
    LaunchedEffect(
        imageUltraFixEnabled,
        imageInputUri,
        imageInputDimensionsUri,
        imageInputSourceWidth,
        imageInputSourceHeight,
        imageInputDimensionsProbing,
        selectedImageModelChoice?.id
    ) {
        if (!imageUltraFixEnabled || imageInputUri == null ||
            imageInputDimensionsUri != imageInputUri ||
            imageInputDimensionsProbing
        ) return@LaunchedEffect
        if (imageInputSourceWidth <= 0 || imageInputSourceHeight <= 0) {
            imageUltraFixEnabled = false
            return@LaunchedEffect
        }
        val model = selectedImageModelChoice?.takeIf(ChatModelChoice::supportsImageUltraFix)
            ?: return@LaunchedEffect
        val target = model.ultraFixTargetSizeForSourceOrNull(
            imageInputSourceWidth,
            imageInputSourceHeight
        ) ?: run {
            imageUltraFixEnabled = false
            return@LaunchedEffect
        }
        val tile = normalizedImageGenerationUltraFixTileSelection(
            model = model,
            rawValue = imageUltraFixTileSizeText,
            explicit = imageUltraFixTileSizeExplicit,
            sourceVersion = IMAGE_GENERATION_UI_PARAMETER_SNAPSHOT_VERSION
        )
        imageWidthText = normalizedImageGenerationDimensionText(
            rawValue = imageWidthText,
            defaultValue = model.imageDefaultWidth,
            minValue = maxOf(target.first, tile.tileSize, model.imageUltraFixMinWidth),
            maxValue = model.imageUltraFixMaxWidth,
            multiple = model.imageUltraFixWidthMultiple
        )
        imageHeightText = normalizedImageGenerationDimensionText(
            rawValue = imageHeightText,
            defaultValue = model.imageDefaultHeight,
            minValue = maxOf(target.second, tile.tileSize, model.imageUltraFixMinHeight),
            maxValue = model.imageUltraFixMaxHeight,
            multiple = model.imageUltraFixHeightMultiple
        )
    }
    }
}
