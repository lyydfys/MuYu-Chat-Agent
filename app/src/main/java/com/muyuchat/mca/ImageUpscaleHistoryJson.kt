package com.muyuchat.mca

import org.json.JSONObject

/** T2B22-T4: upscale history JSON codec; moved verbatim from ImageGenerationHistoryMetadata.kt. */

internal fun ImageUpscaleHistoryMetadata.Companion.fromJsonOrNull(json: JSONObject): ImageUpscaleHistoryMetadata? = runCatching {
            require(json.keys().asSequence().toSet() == UPSCALE_HISTORY_FIELDS) {
                "Upscale history contains unknown or missing fields."
            }
            ImageUpscaleHistoryMetadata(
                sourceImageId = json.getString("sourceImageId").trim(),
                upscalerId = json.getString("upscalerId").trim().lowercase(),
                upscalerName = json.getString("upscalerName").trim(),
                upscalerSha256 = json.getString("upscalerSha256").trim().lowercase(),
                inputImageSha256 = json.getString("inputImageSha256").trim().lowercase(),
                targetScale = requireNotNull(json.historyExactIntOrNull("targetScale")),
                nativeScale = requireNotNull(json.historyExactIntOrNull("nativeScale")),
                tileSize = requireNotNull(json.historyExactIntOrNull("tileSize")),
                threads = requireNotNull(json.historyExactIntOrNull("threads")),
                sourceWidth = requireNotNull(json.historyExactIntOrNull("sourceWidth")),
                sourceHeight = requireNotNull(json.historyExactIntOrNull("sourceHeight")),
                outputWidth = requireNotNull(json.historyExactIntOrNull("outputWidth")),
                outputHeight = requireNotNull(json.historyExactIntOrNull("outputHeight")),
                postResizeApplied = requireNotNull(
                    json.historyBooleanOrNull("postResizeApplied")
                ),
                physicalComputeCount = requireNotNull(
                    json.historyExactLongOrNull("physicalComputeCount")
                ),
                physicalComputeSuccessCount = requireNotNull(
                    json.historyExactLongOrNull("physicalComputeSuccessCount")
                ),
                physicalTileComputeCount = requireNotNull(
                    json.historyExactLongOrNull("physicalTileComputeCount")
                ),
                physicalTileComputeSuccessCount = requireNotNull(
                    json.historyExactLongOrNull("physicalTileComputeSuccessCount")
                ),
                tiledExecution = requireNotNull(json.historyBooleanOrNull("tiledExecution")),
                executionCompleted = requireNotNull(
                    json.historyBooleanOrNull("executionCompleted")
                ),
                nativeGenerationSequence = requireNotNull(
                    json.historyExactLongOrNull("nativeGenerationSequence")
                ),
                nativeStageMask = requireNotNull(json.historyExactLongOrNull("nativeStageMask")),
                nativeDetailStageMask = requireNotNull(
                    json.historyExactLongOrNull("nativeDetailStageMask")
                ),
                contextReleased = requireNotNull(json.historyBooleanOrNull("contextReleased"))
            )
        }.getOrNull()

internal fun ImageUpscaleHistoryMetadata.Companion.fromNativeExecution(
            sourceImageId: String,
            sourceWidthHint: Int,
            sourceHeightHint: Int,
            upscaler: LocalImagePreparedUpscaler,
            targetScale: Int,
            tileSize: Int,
            threads: Int,
            outputWidth: Int,
            outputHeight: Int,
            nativeExecutionJson: String
        ): ImageUpscaleHistoryMetadata {
            val root = JSONObject(nativeExecutionJson)
            val effective = root.getJSONObject("nativeEffective")
            val productOutput = root.getJSONObject("productOutput")
            require(root.historyBooleanOrNull("nativeExecution") == true &&
                root.historyBooleanOrNull("contextReleased") == true)
            require(effective.getString("operation") == "ESRGAN_UPSCALE")
            require(effective.getString("runtime") == LocalImageRuntime.STABLE_DIFFUSION_CPP.name)
            require(effective.getString("backendMode") == "cpu")
            require(effective.historyBooleanOrNull("fallback") == false)
            require(effective.getString("upscalerId") == upscaler.id)
            require(effective.getString("upscalerSha256").lowercase() == upscaler.sha256)
            require(effective.historyExactLongOrNull("upscalerSizeBytes") == upscaler.sizeBytes)
            require(effective.historyBooleanOrNull("modelHashVerified") == true)
            require(effective.historyBooleanOrNull("modelFileIdentityStable") == true)
            require(effective.historyExactIntOrNull("requestedTargetScale") == targetScale)
            require(effective.historyExactIntOrNull("tileSize") == tileSize)
            require(effective.historyExactIntOrNull("threads") == threads)
            require(productOutput.historyExactIntOrNull("targetScale") == targetScale)
            require(productOutput.historyExactIntOrNull("nativeFixedScale") ==
                effective.historyExactIntOrNull("nativeScale"))
            require(productOutput.historyExactIntOrNull("width") == outputWidth)
            require(productOutput.historyExactIntOrNull("height") == outputHeight)
            require(productOutput.getString("mimeType") == "image/png")
            val sourceWidth = requireNotNull(effective.historyExactIntOrNull("sourceWidth"))
            val sourceHeight = requireNotNull(effective.historyExactIntOrNull("sourceHeight"))
            val nativeScale = requireNotNull(effective.historyExactIntOrNull("nativeScale"))
            require(productOutput.getString("postResizeMethod") ==
                if (nativeScale == targetScale) "none" else "android_bitmap_filtered")
            val nativeSequence = requireNotNull(root.historyExactLongOrNull("nativeGenerationSequence"))
            require(effective.historyExactLongOrNull("nativeGenerationSequence") == nativeSequence)
            val nativeWidth = requireNotNull(effective.historyExactIntOrNull("width"))
            val nativeHeight = requireNotNull(effective.historyExactIntOrNull("height"))
            val nativePixels = runCatching {
                Math.multiplyExact(nativeWidth.toLong(), nativeHeight.toLong())
            }.getOrElse { -1L }
            val expectedNativeWidth = runCatching {
                Math.multiplyExact(sourceWidth, nativeScale)
            }.getOrNull()
            val expectedNativeHeight = runCatching {
                Math.multiplyExact(sourceHeight, nativeScale)
            }.getOrNull()
            require(nativeWidth in 1..MAX_NATIVE_OUTPUT_SIDE &&
                nativeHeight in 1..MAX_NATIVE_OUTPUT_SIDE &&
                nativePixels in 1..MAX_NATIVE_OUTPUT_PIXELS)
            require(nativeWidth == expectedNativeWidth && nativeHeight == expectedNativeHeight)
            require(productOutput.historyExactIntOrNull("sourceWidth") == sourceWidth)
            require(productOutput.historyExactIntOrNull("sourceHeight") == sourceHeight)
            require(productOutput.historyExactIntOrNull("nativeWidth") == nativeWidth)
            require(productOutput.historyExactIntOrNull("nativeHeight") == nativeHeight)
            if (sourceWidthHint > 0) require(sourceWidth == sourceWidthHint)
            if (sourceHeightHint > 0) require(sourceHeight == sourceHeightHint)
            return ImageUpscaleHistoryMetadata(
                sourceImageId = sourceImageId,
                upscalerId = upscaler.id,
                upscalerName = upscaler.name,
                upscalerSha256 = upscaler.sha256,
                inputImageSha256 = effective.getString("inputImageSha256").trim().lowercase(),
                targetScale = targetScale,
                nativeScale = nativeScale,
                tileSize = tileSize,
                threads = threads,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                postResizeApplied = requireNotNull(
                    productOutput.historyBooleanOrNull("postResizeApplied")
                ),
                physicalComputeCount = requireNotNull(
                    effective.historyExactLongOrNull("physicalComputeCount")
                ),
                physicalComputeSuccessCount = requireNotNull(
                    effective.historyExactLongOrNull("physicalComputeSuccessCount")
                ),
                physicalTileComputeCount = requireNotNull(
                    effective.historyExactLongOrNull("physicalTileComputeCount")
                ),
                physicalTileComputeSuccessCount = requireNotNull(
                    effective.historyExactLongOrNull("physicalTileComputeSuccessCount")
                ),
                tiledExecution = requireNotNull(
                    effective.historyBooleanOrNull("tiledExecution")
                ),
                executionCompleted = requireNotNull(
                    effective.historyBooleanOrNull("executionCompleted")
                ),
                nativeGenerationSequence = nativeSequence,
                nativeStageMask = requireNotNull(root.historyExactLongOrNull("nativeStageMask")),
                nativeDetailStageMask = requireNotNull(
                    root.historyExactLongOrNull("nativeDetailStageMask")
                ),
                contextReleased = requireNotNull(root.historyBooleanOrNull("contextReleased"))
            )
        }
