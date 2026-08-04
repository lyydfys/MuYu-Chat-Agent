package com.muyuchat.mca

import org.json.JSONObject
import org.json.JSONArray

data class ImageGenerationLoraSelection(
    val id: String,
    val name: String,
    val multiplier: Double
) {
    init {
        require(UUID_PATTERN.matches(id)) { "Image history LoRA id must be a UUID." }
        require(name.isNotBlank() &&
            name.length <= MAX_NAME_CHARS &&
            !name.looksLikePrivatePath()
        ) {
            "Image history LoRA name is invalid."
        }
        require(multiplier.isFinite() &&
            multiplier in LocalImagePreparedLora.MIN_MULTIPLIER..LocalImagePreparedLora.MAX_MULTIPLIER &&
            kotlin.math.abs(multiplier) >= LocalImagePreparedLora.MIN_ABSOLUTE_MULTIPLIER
        ) { "Image history LoRA multiplier is invalid." }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("multiplier", multiplier)

    companion object {
        private val UUID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private const val MAX_NAME_CHARS = 128
        private val LORA_HISTORY_FIELDS = setOf("id", "name", "multiplier")

        fun fromJson(json: JSONObject): ImageGenerationLoraSelection {
            require(json.keys().asSequence().toSet() == LORA_HISTORY_FIELDS) {
                "Image history LoRA contains unknown or missing fields."
            }
            return ImageGenerationLoraSelection(
                id = json.getString("id").trim().lowercase(),
                name = json.getString("name").trim(),
                multiplier = json.getDouble("multiplier")
            )
        }
    }
}

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
        private const val MAX_NATIVE_OUTPUT_SIDE = 8_192
        private const val MAX_NATIVE_OUTPUT_PIXELS = 64_000_000L
        private const val REQUIRED_STAGE_MASK = 255L
        private val UPSCALE_HISTORY_FIELDS = setOf(
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

        fun fromJsonOrNull(json: JSONObject): ImageUpscaleHistoryMetadata? = runCatching {
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

        fun fromNativeExecution(
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

    fun toJsonString(): String = JSONObject()
        .put(
            "version",
            if (backend == ImageBackend.LOCAL && promptExecution == null) {
                LAST_VERSION_WITHOUT_PROMPT_EXECUTION
            } else {
                VERSION
            }
        )
        .put("backend", backend.name)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("requestPrompt", requestPrompt)
        .put("sourceGenerationAvailable", sourceGenerationAvailable)
        .put(
            "options",
            options.withCanonicalPersistedUltraFixControls().copy(
                inputImage = null,
                maskImage = null,
                controlImage = null,
                taskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
                strength = null,
                controlStrength = null,
                loras = emptyList(),
                preview = null
            ).toJson()
        )
        .put("inputDraft", inputDraft.toHistoryJson())
        .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        .put("upscaleHistory", JSONArray().apply { upscaleHistory.forEach { put(it.toJson()) } })
        .apply {
            promptExecution?.let { put("promptExecution", it.toJson()) }
            batchLineage?.let { put("batchLineage", it.toJson()) }
            sanitizeNativeExecutionJson(nativeExecutionJson).takeIf(String::isNotEmpty)?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { put("nativeExecution", it) }
            }
        }
        .toString()

    fun withNativeExecution(nativeExecutionJson: String): ImageGenerationHistoryMetadata {
        val nativeEffective = runCatching {
            JSONObject(nativeExecutionJson).getJSONObject("nativeEffective")
        }.getOrNull() ?: return copy(
            nativeExecutionJson = sanitizeNativeExecutionJson(nativeExecutionJson)
        )
        return copy(
            options = options.copy(
                width = nativeEffective.historyExactIntOrNull("width") ?: options.width,
                height = nativeEffective.historyExactIntOrNull("height") ?: options.height,
                steps = nativeEffective.historyExactIntOrNull("steps") ?: options.steps,
                seed = nativeEffective.historyExactIntOrNull("seed") ?: options.seed,
                cfgScale = nativeEffective.historyFiniteDoubleOrNull("cfgScale") ?: options.cfgScale,
                sampleMethod = nativeEffective.historyNonBlankStringOrNull("sampleMethod")
                    ?: options.sampleMethod,
                clipSkip = nativeEffective.historyExactIntOrNull("clipSkip") ?: options.clipSkip,
                batchCount = nativeEffective.historyExactIntOrNull("batchCount")
                    ?.takeIf { it > 0 }
                    ?: options.batchCount
            ),
            nativeExecutionJson = sanitizeNativeExecutionJson(nativeExecutionJson)
        )
    }

    fun withUpscale(lineage: ImageUpscaleHistoryMetadata): ImageGenerationHistoryMetadata = copy(
        upscaleHistory = (upscaleHistory + lineage).takeLast(MAX_UPSCALE_HISTORY)
    )

    /** Specializes parent/native-batch evidence for one library image without falsifying it. */
    fun forBatchOutput(lineage: ImageGenerationBatchLineage): ImageGenerationHistoryMetadata = copy(
        options = options.copy(seed = lineage.seed, batchCount = 1),
        batchLineage = lineage
    )

    /** Portable, secret-free parameters for clipboard sharing and selective reuse. */
    fun toShareJson(): String {
        val portableOptions = options.withCanonicalPersistedUltraFixControls()
        return JSONObject()
            .put("schema", "mca.image.generation.parameters")
            .put("version", 1)
            .put("modelId", modelId)
            .put("modelName", modelName)
            .put("prompt", requestPrompt)
            .put("taskMode", inputDraft.taskMode.wireName)
            .put("batchCount", portableOptions.batchCount)
            .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
            .put("textualInversionIds", JSONArray(portableOptions.textualInversionIds))
            .apply {
                when (val negative = portableOptions.negativePrompt) {
                    null -> put("negativePromptMode", "model_default")
                    else -> {
                        put("negativePromptMode", "explicit")
                        put("negativePrompt", negative)
                    }
                }
                portableOptions.width?.let { put("width", it) }
                portableOptions.height?.let { put("height", it) }
                portableOptions.steps?.let { put("steps", it) }
                portableOptions.cfgScale?.let { put("cfgScale", it) }
                portableOptions.seed?.let { put("seed", it) }
                portableOptions.sampleMethod?.takeIf(String::isNotBlank)?.let {
                    put("sampleMethod", it)
                }
                portableOptions.clipSkip?.let { put("clipSkip", it) }
                portableOptions.vaeTiling?.let { tiling ->
                    put(
                        "vaeTiling",
                        JSONObject()
                            .put("tileSize", tiling.tileSize)
                            .put("overlap", tiling.overlap)
                    )
                }
                portableOptions.ultraFix?.let { put("ultraFix", it.toJson()) }
                inputDraft.strength?.let { put("strength", it) }
                inputDraft.controlStrength?.let { put("controlStrength", it) }
            }
            .toString()
    }

    /** Backup-safe history metadata without device-local paths or transient native evidence. */
    fun toPortableBackupJsonString(): String = copy(
        inputDraft = inputDraft.copy(
            inputImageReference = null,
            maskImageReference = null,
            controlImageReference = null
        ),
        nativeExecutionJson = ""
    ).toJsonString()

    fun canRecreate(): Boolean = sourceGenerationAvailable && inputDraft.hasRequiredInputReferences()

    /**
     * Product operation used by the image library. This is deliberately derived instead of
     * persisted: UltraFix is an img2img execution variant, so its wire taskMode must remain
     * `img2img` for share/recreate/API compatibility while history may expose it separately.
     */
    internal fun operationFacet(): ImageGenerationHistoryOperation =
        ImageGenerationHistoryOperation.from(
            taskMode = inputDraft.taskMode,
            ultraFix = options.ultraFix != null
        )

    /** Required, path-free inputs that must retain their generation-owned read grant for recreate. */
    fun requiredContentInputReferences(): Set<String> {
        if (!sourceGenerationAvailable) return emptySet()
        val required = when (inputDraft.taskMode) {
            LocalImageTaskMode.TEXT_TO_IMAGE -> emptyList()
            LocalImageTaskMode.IMG2IMG,
            LocalImageTaskMode.EDIT -> listOf(inputDraft.inputImageReference)
            LocalImageTaskMode.INPAINT -> listOf(
                inputDraft.inputImageReference,
                inputDraft.maskImageReference
            )
            LocalImageTaskMode.CONTROL -> listOf(inputDraft.controlImageReference)
        }
        return required.mapNotNullTo(mutableSetOf()) { reference ->
            reference.safeHistoryInputReferenceOrNull()
        }
    }

    fun displayDetails(): String = buildList {
        if (sourceGenerationAvailable) {
        add("$modelName · ${if (options.ultraFix != null) "UltraFix" else inputDraft.taskMode.displayName()}")
        val elapsedMs = runCatching {
            JSONObject(nativeExecutionJson).optLong("elapsedMs", 0L)
        }.getOrDefault(0L)
        val parameters = buildList {
            if (options.width != null && options.height != null) add("${options.width}×${options.height}")
            options.steps?.let { add("$it steps") }
            options.cfgScale?.let { add("CFG ${it.toCompactText()}") }
            add(options.seed?.let { "seed $it" } ?: "随机 seed")
            options.sampleMethod?.takeIf(String::isNotBlank)?.let { add(it) }
            if (options.batchCount > 1) add("${options.batchCount} 张")
            batchLineage?.let { add("批次 ${it.index + 1}/${it.count}") }
            if (elapsedMs > 0L) add("用时 ${"%.1f".format(elapsedMs / 1000.0)} 秒")
        }
        if (parameters.isNotEmpty()) add(parameters.joinToString(" · "))
        val controls = buildList {
            options.clipSkip?.let { add("CLIP skip $it") }
            options.ultraFix?.let { ultraFix ->
                add(
                    "UltraFix ${ultraFix.targetWidth}×${ultraFix.targetHeight} · " +
                        "反演 ${ultraFix.inversionSteps} / 精修 ${ultraFix.refinementSteps} · " +
                        "分块 ${ultraFix.tileSize}/${ultraFix.overlap.toCompactText()}"
                )
            } ?: options.vaeTiling?.let {
                add("VAE 分块 ${it.tileSize}/${it.overlap.toCompactText()}")
            }
            if (loras.isNotEmpty()) {
                add(
                    "LoRA " + loras.joinToString { selection ->
                        "${selection.name} ${selection.multiplier.toCompactText()}"
                    }
                )
            }
            if (options.textualInversionIds.isNotEmpty()) {
                add("Textual Inversion ${options.textualInversionIds.size} 个")
            }
            if (!inputDraft.inputImageReference.isNullOrBlank()) add("原图")
            if (!inputDraft.maskImageReference.isNullOrBlank()) add("蒙版")
            if (!inputDraft.controlImageReference.isNullOrBlank()) add("控制图")
            if (options.ultraFix == null) {
                inputDraft.strength?.let { add("重绘强度 ${it.toCompactText()}") }
            }
            inputDraft.controlStrength?.let { add("控制强度 ${it.toCompactText()}") }
            when (val negative = options.negativePrompt) {
                null -> add("模型默认负向提示词")
                "" -> add("无负向提示词")
                else -> add("负向：$negative")
            }
        }
        if (controls.isNotEmpty()) add(controls.joinToString(" · "))
        }
        upscaleHistory.lastOrNull()?.let { upscale ->
            add(
                "ESRGAN ${upscale.targetScale}x | ${upscale.upscalerName} | " +
                    "${upscale.sourceWidth}x${upscale.sourceHeight} -> " +
                    "${upscale.outputWidth}x${upscale.outputHeight}"
            )
        }
    }.joinToString("\n")

    companion object {
        private const val VERSION = 5
        private const val LEGACY_VERSION = 1
        private const val LAST_VERSION_WITHOUT_PROMPT_EXECUTION = 3
        private const val LEGACY_QNN_DETAIL_MASK_LAST_HISTORY_VERSION = 2
        private const val MAX_UPSCALE_HISTORY = 16

        fun fromJsonOrNull(raw: String?): ImageGenerationHistoryMetadata? = runCatching {
            val json = JSONObject(raw?.trim().orEmpty())
            val historyVersion = requireNotNull(json.historyExactIntOrNull("version"))
            require(historyVersion in LEGACY_VERSION..VERSION) {
                "Unsupported image history version."
            }
            val input = json.getJSONObject("inputDraft")
            val inputDraft = LocalImageInputDraft(
                taskMode = LocalImageTaskMode.fromWireName(input.getString("taskMode")),
                inputImageReference = input.optionalHistoryString("inputImageReference")
                    .safeHistoryInputReferenceOrNull(),
                maskImageReference = input.optionalHistoryString("maskImageReference")
                    .safeHistoryInputReferenceOrNull(),
                controlImageReference = input.optionalHistoryString("controlImageReference")
                    .safeHistoryInputReferenceOrNull(),
                strength = input.optionalHistoryDouble("strength"),
                controlStrength = input.optionalHistoryDouble("controlStrength")
            ).also(LocalImageInputDraft::validateForHistory)
            val persistedOptions = LocalImageGenerationOptions.fromHistoryJson(
                json.getJSONObject("options")
            )
            val backend = ImageBackend.valueOf(json.getString("backend"))
            val requestPrompt = json.getString("requestPrompt")
            var discardedLegacyTranslatedPromptExecution = false
            val promptExecution = if (json.has("promptExecution")) {
                val promptExecutionJson = requireNotNull(json.optJSONObject("promptExecution")) {
                    "Image history promptExecution is invalid."
                }
                if (promptExecutionJson.isDiscardableLegacyTranslatedPromptExecution(
                        requestPrompt = requestPrompt,
                        originalNegativePrompt = persistedOptions.negativePrompt
                    )
                ) {
                    discardedLegacyTranslatedPromptExecution = true
                    null
                } else {
                    requireNotNull(LocalImagePromptExecution.fromJsonOrNull(promptExecutionJson)) {
                        "Image history promptExecution is invalid."
                    }
                }
            } else {
                null
            }
            val sourceGenerationAvailable = if (json.has("sourceGenerationAvailable")) {
                requireNotNull(json.historyBooleanOrNull("sourceGenerationAvailable"))
            } else {
                true
            }
            require(historyVersion < VERSION || backend != ImageBackend.LOCAL ||
                !sourceGenerationAvailable || promptExecution != null ||
                discardedLegacyTranslatedPromptExecution
            ) {
                "Current local image history must include prompt execution evidence."
            }
            val historyOptions = persistedOptions.copy(
                taskMode = inputDraft.taskMode,
                strength = inputDraft.strength,
                controlStrength = inputDraft.controlStrength
            ).also(LocalImageGenerationOptions::validateHistoryProductInputContract)
            ImageGenerationHistoryMetadata(
                backend = backend,
                modelId = json.getString("modelId"),
                modelName = json.getString("modelName"),
                requestPrompt = requestPrompt,
                options = historyOptions,
                inputDraft = inputDraft,
                promptExecution = promptExecution,
                loras = json.optJSONArray("loras")?.let { array ->
                    require(array.length() <= LocalImagePreparedLora.MAX_COUNT)
                    buildList {
                        for (index in 0 until array.length()) {
                            add(ImageGenerationLoraSelection.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                nativeExecutionJson = sanitizeNativeExecutionJson(
                    json.optJSONObject("nativeExecution")?.toString().orEmpty(),
                    allowLegacyQnnDetailStageMaskMigration =
                        historyVersion <= LEGACY_QNN_DETAIL_MASK_LAST_HISTORY_VERSION
                ),
                sourceGenerationAvailable = sourceGenerationAvailable,
                upscaleHistory = json.optJSONArray("upscaleHistory")?.let { array ->
                    require(array.length() <= MAX_UPSCALE_HISTORY)
                    buildList {
                        for (index in 0 until array.length()) {
                            add(requireNotNull(
                                ImageUpscaleHistoryMetadata.fromJsonOrNull(
                                    array.getJSONObject(index)
                                )
                            ))
                        }
                    }
                }.orEmpty(),
                batchLineage = json.optJSONObject("batchLineage")
                    ?.let(ImageGenerationBatchLineage::fromJson)
            )
        }.getOrNull()
    }
}

/** Stable, display-oriented operation facet for generated-image history. */
internal enum class ImageGenerationHistoryOperation(
    val wireName: String,
    val displayName: String
) {
    TEXT_TO_IMAGE("text_to_image", "Text to image"),
    IMG2IMG("img2img", "Image to image"),
    INPAINT("inpaint", "Inpaint"),
    CONTROL("control", "Control"),
    EDIT("edit", "Edit"),
    ULTRAFIX("ultrafix", "UltraFix");

    companion object {
        fun from(taskMode: LocalImageTaskMode, ultraFix: Boolean): ImageGenerationHistoryOperation {
            if (ultraFix) return ULTRAFIX
            return entries.first { it.wireName == taskMode.wireName }
        }

        fun fromWireNameOrNull(value: String?): ImageGenerationHistoryOperation? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}

/** Canonicalizes UltraFix's duplicated scalar controls without requiring staged worker paths. */
private fun LocalImageGenerationOptions.withCanonicalPersistedUltraFixControls():
    LocalImageGenerationOptions {
    val request = ultraFix ?: return this
    require(taskMode == LocalImageTaskMode.IMG2IMG && batchCount == 1 && preview == null) {
        "Persisted UltraFix controls require one img2img output."
    }
    require(width == null || width == request.targetWidth)
    require(height == null || height == request.targetHeight)
    require(steps == null || steps == request.refinementSteps)
    require(strength == null || kotlin.math.abs(strength - request.strength) <= 1.0e-12)
    require(vaeTiling == null || (
        vaeTiling.tileSize == request.tileSize &&
            kotlin.math.abs(vaeTiling.overlap - request.overlap) <= 1.0e-12
        )) {
        "Persisted UltraFix scalar controls conflict with the structured request."
    }
    return copy(
        width = request.targetWidth,
        height = request.targetHeight,
        steps = request.refinementSteps,
        strength = request.strength,
        vaeTiling = LocalImageVaeTilingOptions(request.tileSize, request.overlap)
    )
}

/**
 * Persisted translation receipts are portable audit metadata, not an install-authenticated
 * attestation that a historical translator ran. Recreate therefore starts again under the current
 * prompt-language policy. Only the two current execution methods are safe to reuse because their effective
 * positive prompt is identical to the retained request text. The former UTF-8-pass-through
 * marker is retained solely so old history remains readable; it cannot authorize a new run.
 */
internal fun LocalImagePromptTransformationMethod.isReusableFromImageHistory(): Boolean =
    this == LocalImagePromptTransformationMethod.DIRECT ||
        this == LocalImagePromptTransformationMethod.NATIVE_MULTILINGUAL

/**
 * V1-v3 translated prompt evidence predates the current two-phase semantic contract. Preserve the
 * surrounding history, but deliberately discard that weak evidence so recreate performs a fresh
 * v4 translation. Current-schema corruption must still fail closed instead of silently downgrading.
 */
private fun JSONObject.isDiscardableLegacyTranslatedPromptExecution(
    requestPrompt: String,
    originalNegativePrompt: String?
): Boolean {
    if (opt("method") != LocalImagePromptTransformationMethod.LOCAL_LLM_ZH_TO_EN.name) return false
    if (opt("originalPrompt") != requestPrompt) return false
    val persistedOriginalNegativePrompt = when {
        !has("originalNegativePrompt") || isNull("originalNegativePrompt") -> null
        opt("originalNegativePrompt") is String -> getString("originalNegativePrompt")
        else -> return false
    }
    if (persistedOriginalNegativePrompt != originalNegativePrompt) return false
    val schemaVersion = historyExactIntOrNull("version") ?: return false
    if (schemaVersion in 1..LAST_LEGACY_TRANSLATED_PROMPT_EXECUTION_VERSION) return true
    val contractVersion = historyExactIntOrNull("translationContractVersion") ?: return false
    return schemaVersion == CURRENT_TRANSLATED_PROMPT_EXECUTION_SCHEMA_VERSION &&
        contractVersion in 1 until CURRENT_LOCAL_IMAGE_PROMPT_TRANSLATION_CONTRACT_VERSION
}

private const val LAST_LEGACY_TRANSLATED_PROMPT_EXECUTION_VERSION = 3
private const val CURRENT_TRANSLATED_PROMPT_EXECUTION_SCHEMA_VERSION = 4

internal data class ImageHistoryExecutionFacets(
    val runtimeLabel: String = "",
    val deviceLabel: String = ""
)

/**
 * Structured, display-safe execution facets for image-library filtering. Values are accepted only
 * from the persisted, path-sanitized native execution echo. Unknown and legacy evidence stays
 * empty instead of being inferred from the current model or device.
 */
internal fun ImageGenerationHistoryMetadata.executionFacets(): ImageHistoryExecutionFacets {
    if (backend == ImageBackend.CLOUD) {
        return ImageHistoryExecutionFacets(runtimeLabel = "云端 API")
    }
    val root = runCatching { JSONObject(nativeExecutionJson) }.getOrNull()
        ?: return ImageHistoryExecutionFacets()
    val nativeEffective = root.optJSONObject("nativeEffective")
    fun exactString(key: String): String? = sequenceOf(nativeEffective, root)
        .filterNotNull()
        .mapNotNull { json -> json.opt(key) as? String }
        .map(String::trim)
        .firstOrNull(String::isNotBlank)

    val runtime = exactString("runtime")?.let { wireName ->
        LocalImageRuntime.entries.firstOrNull { it.name == wireName }
    }
    val runtimeLabel = when (runtime) {
        LocalImageRuntime.QNN_HTP -> "QNN HTP"
        LocalImageRuntime.MNN_DIFFUSION -> "MNN Diffusion"
        LocalImageRuntime.STABLE_DIFFUSION_CPP -> "stable-diffusion.cpp"
        LocalImageRuntime.ONNX_RUNTIME -> "ONNX Runtime"
        LocalImageRuntime.CUSTOM -> "自定义本地引擎"
        null -> ""
    }

    val transportHtpArch = sequenceOf(root, nativeEffective)
        .filterNotNull()
        .mapNotNull { json -> json.historyExactIntOrNull("transportHtpArch") }
        .firstOrNull { it in 1..999 }
    val backendMode = exactString("backendMode")?.lowercase()
    val deviceLabel = when {
        transportHtpArch != null -> "HTP V$transportHtpArch"
        backendMode == "opencl" -> "OpenCL GPU"
        backendMode == "cpu" -> "CPU"
        runtime == LocalImageRuntime.QNN_HTP && root.optBoolean("npuActive", false) -> "NPU"
        else -> ""
    }
    return ImageHistoryExecutionFacets(
        runtimeLabel = runtimeLabel,
        deviceLabel = deviceLabel
    )
}

private fun LocalImageInputDraft.toHistoryJson(): JSONObject = JSONObject()
    .put("taskMode", taskMode.wireName)
    .apply {
        inputImageReference.safeHistoryInputReferenceOrNull()?.let { put("inputImageReference", it) }
        maskImageReference.safeHistoryInputReferenceOrNull()?.let { put("maskImageReference", it) }
        controlImageReference.safeHistoryInputReferenceOrNull()?.let { put("controlImageReference", it) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
    }

private fun JSONObject.optionalHistoryString(key: String): String? =
    if (has(key) && !isNull(key)) getString(key).takeIf(String::isNotBlank) else null

private fun JSONObject.optionalHistoryDouble(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key).also { require(it.isFinite()) } else null

private fun JSONObject.historyExactIntOrNull(key: String): Int? {
    val value = historyExactLongOrNull(key) ?: return null
    return value.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
}

private fun JSONObject.historyExactLongOrNull(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) as? Number ?: return null
    return when (value) {
        is Byte, is Short, is Int, is Long -> value.toLong()
        is java.math.BigInteger -> value.toString().toLongOrNull()
        is java.math.BigDecimal -> value.stripTrailingZeros()
            .takeIf { it.scale() <= 0 }
            ?.toPlainString()
            ?.toLongOrNull()
        is Float, is Double -> value.toDouble().takeIf { number ->
            number.isFinite() &&
                number % 1.0 == 0.0 &&
                kotlin.math.abs(number) <= MAX_EXACT_JSON_DOUBLE_INTEGER
        }?.toLong()
        else -> runCatching { java.math.BigDecimal(value.toString()).stripTrailingZeros() }
            .getOrNull()
            ?.takeIf { it.scale() <= 0 }
            ?.toPlainString()
            ?.toLongOrNull()
    }
}

private const val MAX_EXACT_JSON_DOUBLE_INTEGER = 9_007_199_254_740_991.0

private fun JSONObject.historyFiniteDoubleOrNull(key: String): Double? =
    (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun JSONObject.historyBooleanOrNull(key: String): Boolean? = opt(key) as? Boolean

private fun JSONObject.historyNonBlankStringOrNull(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf(String::isNotBlank)

private fun String?.safeHistoryInputReferenceOrNull(): String? = this
    ?.trim()
    ?.takeIf { it.startsWith("content://", ignoreCase = true) }

internal fun sanitizeNativeExecutionJson(
    raw: String,
    allowLegacyQnnDetailStageMaskMigration: Boolean = false
): String {
    val source = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return ""
    val runtime = sequenceOf(source.optJSONObject("nativeEffective"), source)
        .filterNotNull()
        .mapNotNull { json -> json.opt("runtime") as? String }
        .firstOrNull()
    if (runtime == LocalImageRuntime.QNN_HTP.name) {
        val accepted = runCatching {
            if (allowLegacyQnnDetailStageMaskMigration) {
                migrateLegacyQnnDetailStageMasks(source)
            }
            validatePersistedQnnDetailStageMasks(source)
        }.isSuccess
        if (!accepted) return ""
    }
    return sanitizeNativeObject(source).toString()
}

private fun migrateLegacyQnnDetailStageMasks(root: JSONObject) {
    fun migrate(container: JSONObject, hexField: String, legacyField: String) {
        if (!container.has(legacyField)) return
        require(!container.has(hexField)) { "QNN detail stage mask has two wire encodings." }
        val signed = requireNotNull(container.historyExactLongOrNull(legacyField))
        require(signed >= 0L) { "Legacy QNN detail stage mask is outside its accepted range." }
        container.put(hexField, signed.toULong().toFixedUInt64Hex())
        container.remove(legacyField)
    }

    val containers = buildList {
        add(root)
        root.optJSONObject("nativeEffective")?.let(::add)
        root.optJSONObject("sdxlPhaseProof")?.let(::add)
        root.optJSONObject("nativeEffective")
            ?.optJSONObject("sdxlPhaseProof")
            ?.let(::add)
    }
    containers.forEach { container ->
        migrate(
            container,
            QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
            "nativeDetailStageMask"
        )
        listOf("encoder", "unet", "vae").forEach { phase ->
            migrate(
                container,
                "${phase}NativeDetailStageMaskHex",
                "${phase}NativeDetailStageMask"
            )
        }
    }
}

private fun validatePersistedQnnDetailStageMasks(root: JSONObject) {
    val aggregate = root.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    root.optJSONObject("nativeEffective")?.let { nativeEffective ->
        if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
            nativeEffective.has("nativeDetailStageMask")
        ) {
            require(
                nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                    aggregate
            ) { "Persisted QNN detail stage mask conflicts with nativeEffective." }
        }
    }
    listOf("encoder", "unet", "vae").forEach { phase ->
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        if (root.has(hexField) || root.has(legacyField)) {
            val phaseMask = root.strictUInt64Hex(hexField, legacyField)
            root.optJSONObject("sdxlPhaseProof")?.let { proof ->
                if (proof.has(hexField) || proof.has(legacyField)) {
                    require(proof.strictUInt64Hex(hexField, legacyField) == phaseMask) {
                        "Persisted QNN $phase detail stage mask conflicts with its phase proof."
                    }
                }
            }
        }
    }
}

private fun sanitizeNativeObject(source: JSONObject): JSONObject = JSONObject().apply {
    source.keys().asSequence().forEach { key ->
        if (key.isPrivatePathEvidenceKey()) return@forEach
        sanitizeNativeValue(source.opt(key))?.let { value -> put(key, value) }
    }
}

private fun sanitizeNativeArray(source: JSONArray): JSONArray = JSONArray().apply {
    for (index in 0 until source.length()) {
        sanitizeNativeValue(source.opt(index))?.let(::put)
    }
}

private fun sanitizeNativeValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> sanitizeNativeObject(value)
    is JSONArray -> sanitizeNativeArray(value)
    is String -> value.takeUnless(String::looksLikePrivatePath)
    is Number, is Boolean -> value
    else -> null
}

private fun String.isPrivatePathEvidenceKey(): Boolean {
    val normalized = lowercase().filter(Char::isLetterOrDigit)
    return "path" in normalized || normalized in setOf(
        "bundleroot",
        "modelroot",
        "upscalerroot",
        "loraroot",
        "cachedir",
        "filesdir",
        "tempdir",
        "temporarydirectory",
        "workingdirectory",
        "outputdirectory"
    )
}

private fun String.looksLikePrivatePath(): Boolean {
    val value = trim()
    if (value.startsWith("/") ||
        value.startsWith("\\\\") ||
        value.contains("file:", ignoreCase = true) ||
        value.contains("content:", ignoreCase = true) ||
        value.contains("android.resource:", ignoreCase = true)
    ) return true
    if (Regex("[A-Za-z]:[\\\\/]").containsMatchIn(value)) return true
    val lowered = value.lowercase()
    return listOf("/data/", "/storage/", "/sdcard/", "/mnt/", "/cache/", "/tmp/")
        .any(lowered::contains)
}

private fun LocalImageTaskMode.displayName(): String = when (this) {
    LocalImageTaskMode.TEXT_TO_IMAGE -> "文生图"
    LocalImageTaskMode.IMG2IMG -> "图生图"
    LocalImageTaskMode.INPAINT -> "局部重绘"
    LocalImageTaskMode.CONTROL -> "Control"
    LocalImageTaskMode.EDIT -> "编辑"
}

private fun Double.toCompactText(): String = toString().trimEnd('0').trimEnd('.')
