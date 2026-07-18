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
    val loras: List<ImageGenerationLoraSelection> = options.loras.map { adapter ->
        ImageGenerationLoraSelection(adapter.id, adapter.name, adapter.multiplier)
    },
    val nativeExecutionJson: String = "",
    val sourceGenerationAvailable: Boolean = true,
    val upscaleHistory: List<ImageUpscaleHistoryMetadata> = emptyList()
) {
    init {
        require(modelId.isNotBlank()) { "Image history modelId must not be blank." }
        require(modelName.isNotBlank()) { "Image history modelName must not be blank." }
        require(requestPrompt.isNotBlank()) { "Image history requestPrompt must not be blank." }
        if (backend == ImageBackend.LOCAL) {
            require(options.taskMode == inputDraft.taskMode) {
                "Image history options and input draft must use the same task mode."
            }
        }
        require(loras.size <= LocalImagePreparedLora.MAX_COUNT &&
            loras.map(ImageGenerationLoraSelection::id).distinct().size == loras.size
        ) { "Image history LoRA selections are invalid." }
        require(upscaleHistory.size <= MAX_UPSCALE_HISTORY) {
            "Image history contains too many upscale lineage entries."
        }
    }

    fun toJsonString(): String = JSONObject()
        .put("version", VERSION)
        .put("backend", backend.name)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("requestPrompt", requestPrompt)
        .put("sourceGenerationAvailable", sourceGenerationAvailable)
        .put(
            "options",
            options.copy(
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

    /** Portable, secret-free parameters for clipboard sharing and selective reuse. */
    fun toShareJson(): String = JSONObject()
        .put("schema", "mca.image.generation.parameters")
        .put("version", 1)
        .put("modelId", modelId)
        .put("modelName", modelName)
        .put("prompt", requestPrompt)
        .put("taskMode", inputDraft.taskMode.wireName)
        .put("batchCount", options.batchCount)
        .put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        .apply {
            when (val negative = options.negativePrompt) {
                null -> put("negativePromptMode", "model_default")
                else -> {
                    put("negativePromptMode", "explicit")
                    put("negativePrompt", negative)
                }
            }
            options.width?.let { put("width", it) }
            options.height?.let { put("height", it) }
            options.steps?.let { put("steps", it) }
            options.cfgScale?.let { put("cfgScale", it) }
            options.seed?.let { put("seed", it) }
            options.sampleMethod?.takeIf(String::isNotBlank)?.let { put("sampleMethod", it) }
            options.clipSkip?.let { put("clipSkip", it) }
            options.vaeTiling?.let { tiling ->
                put(
                    "vaeTiling",
                    JSONObject()
                        .put("tileSize", tiling.tileSize)
                        .put("overlap", tiling.overlap)
                )
            }
            inputDraft.strength?.let { put("strength", it) }
            inputDraft.controlStrength?.let { put("controlStrength", it) }
        }
        .toString()

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
        add("$modelName · ${inputDraft.taskMode.displayName()}")
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
            if (elapsedMs > 0L) add("用时 ${"%.1f".format(elapsedMs / 1000.0)} 秒")
        }
        if (parameters.isNotEmpty()) add(parameters.joinToString(" · "))
        val controls = buildList {
            options.clipSkip?.let { add("CLIP skip $it") }
            options.vaeTiling?.let { add("VAE 分块 ${it.tileSize}/${it.overlap.toCompactText()}") }
            if (loras.isNotEmpty()) {
                add(
                    "LoRA " + loras.joinToString { selection ->
                        "${selection.name} ${selection.multiplier.toCompactText()}"
                    }
                )
            }
            if (!inputDraft.inputImageReference.isNullOrBlank()) add("原图")
            if (!inputDraft.maskImageReference.isNullOrBlank()) add("蒙版")
            if (!inputDraft.controlImageReference.isNullOrBlank()) add("控制图")
            inputDraft.strength?.let { add("重绘强度 ${it.toCompactText()}") }
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
        private const val VERSION = 1
        private const val MAX_UPSCALE_HISTORY = 16

        fun fromJsonOrNull(raw: String?): ImageGenerationHistoryMetadata? = runCatching {
            val json = JSONObject(raw?.trim().orEmpty())
            require(json.historyExactIntOrNull("version") == VERSION) {
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
            val persistedOptions = LocalImageGenerationOptions.fromJson(json.getJSONObject("options"))
            ImageGenerationHistoryMetadata(
                backend = ImageBackend.valueOf(json.getString("backend")),
                modelId = json.getString("modelId"),
                modelName = json.getString("modelName"),
                requestPrompt = json.getString("requestPrompt"),
                options = persistedOptions.copy(
                    taskMode = inputDraft.taskMode,
                    strength = inputDraft.strength,
                    controlStrength = inputDraft.controlStrength
                ),
                inputDraft = inputDraft,
                loras = json.optJSONArray("loras")?.let { array ->
                    require(array.length() <= LocalImagePreparedLora.MAX_COUNT)
                    buildList {
                        for (index in 0 until array.length()) {
                            add(ImageGenerationLoraSelection.fromJson(array.getJSONObject(index)))
                        }
                    }
                }.orEmpty(),
                nativeExecutionJson = sanitizeNativeExecutionJson(
                    json.optJSONObject("nativeExecution")?.toString().orEmpty()
                ),
                sourceGenerationAvailable = if (json.has("sourceGenerationAvailable")) {
                    requireNotNull(json.historyBooleanOrNull("sourceGenerationAvailable"))
                } else {
                    true
                },
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
                }.orEmpty()
            )
        }.getOrNull()
    }
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

internal fun sanitizeNativeExecutionJson(raw: String): String {
    val source = runCatching { JSONObject(raw.trim()) }.getOrNull() ?: return ""
    return sanitizeNativeObject(source).toString()
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
