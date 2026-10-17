package com.muyuchat.mca

import org.json.JSONObject
import com.muyuchat.api.local.looksLikePrivatePath

/** Path-free evidence for one ESRGAN upscale derived from an image-library asset. */
data class ImageUpscaleHistoryMetadata(
    val sourceImageId: String,
    val upscalerId: String,
    val upscalerName: String,
    val upscalerSha256: String,
    val inputImageSha256: String,
    val targetScale: Int,
    val nativeScale: Int,
    val tileSize: Int,
    val threads: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val postResizeApplied: Boolean,
    val physicalComputeCount: Long,
    val physicalComputeSuccessCount: Long,
    val physicalTileComputeCount: Long,
    val physicalTileComputeSuccessCount: Long,
    val tiledExecution: Boolean,
    val executionCompleted: Boolean,
    val nativeGenerationSequence: Long,
    val nativeStageMask: Long,
    val nativeDetailStageMask: Long,
    val contextReleased: Boolean
) {
    init {
        require(UUID_PATTERN.matches(sourceImageId)) { "Upscale source image id must be a UUID." }
        require(UUID_PATTERN.matches(upscalerId)) { "Upscale history upscaler id must be a UUID." }
        require(upscalerName.isNotBlank() &&
            upscalerName.length <= MAX_UPSCALER_NAME_CHARS &&
            !upscalerName.looksLikePrivatePath()
        ) {
            "Upscale history upscaler name is invalid."
        }
        require(SHA256_PATTERN.matches(upscalerSha256)) { "Upscale history upscaler hash is invalid." }
        require(SHA256_PATTERN.matches(inputImageSha256)) { "Upscale history input hash is invalid." }
        require(targetScale in TARGET_SCALES && nativeScale in 2..8 && nativeScale >= targetScale) {
            "Upscale history scale evidence is invalid."
        }
        require(tileSize in 32..1_024 && tileSize % 8 == 0 && threads in 1..64) {
            "Upscale history execution controls are invalid."
        }
        val sourcePixels = runCatching {
            Math.multiplyExact(sourceWidth.toLong(), sourceHeight.toLong())
        }.getOrElse { -1L }
        val outputPixels = runCatching {
            Math.multiplyExact(outputWidth.toLong(), outputHeight.toLong())
        }.getOrElse { -1L }
        val expectedOutputWidth = runCatching {
            Math.multiplyExact(sourceWidth, targetScale)
        }.getOrNull()
        val expectedOutputHeight = runCatching {
            Math.multiplyExact(sourceHeight, targetScale)
        }.getOrNull()
        require(sourceWidth in 1..MAX_SOURCE_SIDE && sourceHeight in 1..MAX_SOURCE_SIDE &&
            sourcePixels in 1..MAX_SOURCE_PIXELS &&
            outputWidth in 1..MAX_OUTPUT_SIDE && outputHeight in 1..MAX_OUTPUT_SIDE &&
            outputPixels in 1..MAX_OUTPUT_PIXELS &&
            outputWidth == expectedOutputWidth && outputHeight == expectedOutputHeight
        ) { "Upscale history dimensions are invalid." }
        require(postResizeApplied == (nativeScale != targetScale)) {
            "Upscale history resize evidence is inconsistent."
        }
        val expectedTiled = sourceWidth > tileSize || sourceHeight > tileSize
        require(executionCompleted && physicalComputeCount > 0L &&
            physicalComputeSuccessCount == physicalComputeCount &&
            tiledExecution == expectedTiled &&
            (if (expectedTiled) {
                physicalTileComputeCount == physicalComputeCount &&
                    physicalTileComputeSuccessCount == physicalComputeSuccessCount
            } else {
                physicalComputeCount == 1L &&
                    physicalTileComputeCount == 0L &&
                    physicalTileComputeSuccessCount == 0L
            })
        ) { "Upscale history physical compute evidence is inconsistent." }
        require(nativeGenerationSequence > 0L && contextReleased) {
            "Upscale history is missing terminal native execution evidence."
        }
        require(nativeStageMask >= 0L && nativeDetailStageMask >= 0L &&
            nativeStageMask and REQUIRED_STAGE_MASK == REQUIRED_STAGE_MASK &&
            nativeDetailStageMask and REQUIRED_STAGE_MASK == REQUIRED_STAGE_MASK
        ) { "Upscale history is missing required native stages." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("sourceImageId", sourceImageId)
        .put("upscalerId", upscalerId)
        .put("upscalerName", upscalerName)
        .put("upscalerSha256", upscalerSha256)
        .put("inputImageSha256", inputImageSha256)
        .put("targetScale", targetScale)
        .put("nativeScale", nativeScale)
        .put("tileSize", tileSize)
        .put("threads", threads)
        .put("sourceWidth", sourceWidth)
        .put("sourceHeight", sourceHeight)
        .put("outputWidth", outputWidth)
        .put("outputHeight", outputHeight)
        .put("postResizeApplied", postResizeApplied)
        .put("physicalComputeCount", physicalComputeCount)
        .put("physicalComputeSuccessCount", physicalComputeSuccessCount)
        .put("physicalTileComputeCount", physicalTileComputeCount)
        .put("physicalTileComputeSuccessCount", physicalTileComputeSuccessCount)
        .put("tiledExecution", tiledExecution)
        .put("executionCompleted", executionCompleted)
        .put("nativeGenerationSequence", nativeGenerationSequence)
        .put("nativeStageMask", nativeStageMask)
        .put("nativeDetailStageMask", nativeDetailStageMask)
        .put("contextReleased", contextReleased)

    companion object {
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val TARGET_SCALES = setOf(2, 3, 4)
        private const val MAX_UPSCALER_NAME_CHARS = 128
        private const val MAX_SOURCE_SIDE = 2_048
        private const val MAX_SOURCE_PIXELS = 4_000_000L
        private const val MAX_OUTPUT_SIDE = 4_096
        private const val MAX_OUTPUT_PIXELS = 16_000_000L
        internal const val MAX_NATIVE_OUTPUT_SIDE = 8_192
        internal const val MAX_NATIVE_OUTPUT_PIXELS = 64_000_000L
        private const val REQUIRED_STAGE_MASK = 255L
        internal val UPSCALE_HISTORY_FIELDS = setOf(
            "sourceImageId",
            "upscalerId",
            "upscalerName",
            "upscalerSha256",
            "inputImageSha256",
            "targetScale",
            "nativeScale",
            "tileSize",
            "threads",
            "sourceWidth",
            "sourceHeight",
            "outputWidth",
            "outputHeight",
            "postResizeApplied",
            "physicalComputeCount",
            "physicalComputeSuccessCount",
            "physicalTileComputeCount",
            "physicalTileComputeSuccessCount",
            "tiledExecution",
            "executionCompleted",
            "nativeGenerationSequence",
            "nativeStageMask",
            "nativeDetailStageMask",
            "contextReleased"
        )

    }
}

/** Reproducible, secret-free generation parameters persisted with a generated image. */
data class ImageGenerationHistoryMetadata(
    val backend: ImageBackend,
    val modelId: String,
    val modelName: String,
    val requestPrompt: String,
    val options: LocalImageGenerationOptions,
    val inputDraft: LocalImageInputDraft,
    val promptExecution: LocalImagePromptExecution? = null,
    val loras: List<ImageGenerationLoraSelection> = options.loras.map { adapter ->
        ImageGenerationLoraSelection(adapter.id, adapter.name, adapter.multiplier)
    },
    val nativeExecutionJson: String = "",
    val sourceGenerationAvailable: Boolean = true,
    val upscaleHistory: List<ImageUpscaleHistoryMetadata> = emptyList(),
    val batchLineage: ImageGenerationBatchLineage? = null
) {
    init {
        require(modelId.isNotBlank()) { "Image history modelId must not be blank." }
        require(modelName.isNotBlank()) { "Image history modelName must not be blank." }
        require(requestPrompt.isNotBlank()) { "Image history requestPrompt must not be blank." }
        promptExecution?.let { execution ->
            require(execution.originalPrompt == requestPrompt) {
                "Image history prompt execution must retain the original request prompt."
            }
            require(execution.originalNegativePrompt == options.negativePrompt) {
                "Image history prompt execution must retain the original negative prompt."
            }
        }
        if (backend == ImageBackend.LOCAL) {
            val canonicalOptions = options.withCanonicalPersistedUltraFixControls()
            require(canonicalOptions.taskMode == inputDraft.taskMode &&
                canonicalOptions.strength == inputDraft.strength &&
                canonicalOptions.controlStrength == inputDraft.controlStrength
            ) {
                "Image history options and input draft must use the same input controls."
            }
        }
        require(loras.size <= LocalImagePreparedLora.MAX_COUNT &&
            loras.map(ImageGenerationLoraSelection::id).distinct().size == loras.size
        ) { "Image history LoRA selections are invalid." }
        require(upscaleHistory.size <= MAX_UPSCALE_HISTORY) {
            "Image history contains too many upscale lineage entries."
        }
        batchLineage?.let { lineage ->
            require(options.batchCount == 1 && options.seed == lineage.seed) {
                "Image history batch lineage must match the image's actual seed and single output."
            }
        }
    }

    companion object {
        internal const val VERSION = 5
        internal const val LEGACY_VERSION = 1
        internal const val LAST_VERSION_WITHOUT_PROMPT_EXECUTION = 3
        internal const val LEGACY_QNN_DETAIL_MASK_LAST_HISTORY_VERSION = 2
        internal const val MAX_UPSCALE_HISTORY = 16

    }
}
