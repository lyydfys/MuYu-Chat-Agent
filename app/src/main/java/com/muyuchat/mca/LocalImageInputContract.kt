package com.muyuchat.mca

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

private const val NATIVE_FLOAT_RELATIVE_TOLERANCE = 1e-6
private const val SDCPP_STAGE_INPUT_IMAGE_DECODED = 128L
private const val SDCPP_STAGE_MASK_IMAGE_DECODED = 256L
private const val SDCPP_STAGE_CONTROL_IMAGE_DECODED = 512L
private const val SDCPP_STAGE_LORA_VALIDATED = 1_024L
private const val SDCPP_REQUIRED_SUCCESS_STAGES = 127L
private val QNN_ENCODER_GRAPH_EXECUTE_DETAIL_STAGE = 1uL shl 63
private val LOWERCASE_SHA256 = Regex("^[a-f0-9]{64}$")
internal val LOWERCASE_UINT64_HEX = Regex("^[a-f0-9]{16}$")
private val SHARED_QNN_ENCODER_INPUT_SHAPE = listOf(1, 3, 512, 512)
private val SHARED_QNN_ENCODER_OUTPUT_SHAPE = listOf(1, 4, 64, 64)
private const val SHARED_QNN_ENCODER_POSTERIOR_SAMPLE_COUNT = 1L * 4L * 64L * 64L
private const val SHARED_QNN_ENCODER_RUNTIME_MODE = "standalone_encoder_then_shared_unet_vae"

private fun nativeFloatMatches(first: Double, second: Double): Boolean {
    if (!first.isFinite() || !second.isFinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
    return kotlin.math.abs(first - second) <= NATIVE_FLOAT_RELATIVE_TOLERANCE * scale
}

class LocalImageProductContractException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)

/** Product-visible image operation. This is independent of runtime or device discovery. */
enum class LocalImageTaskMode(val wireName: String) {
    TEXT_TO_IMAGE("text_to_image"),
    IMG2IMG("img2img"),
    INPAINT("inpaint"),
    CONTROL("control"),
    EDIT("edit");

    companion object {
        fun fromWireName(value: String?): LocalImageTaskMode {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: error("Unsupported image taskMode: ${value.orEmpty()}")
        }
    }
}

enum class LocalImagePreviewMode(val wireName: String) {
    NONE("none"),
    PROJECTION("projection"),
    TAE("tae"),
    VAE("vae");

    companion object {
        fun fromWireName(value: String?): LocalImagePreviewMode {
            val normalized = value.orEmpty().trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: error("Unsupported image preview mode: ${value.orEmpty()}")
        }
    }
}

data class LocalImagePreparedLora(
    val id: String,
    val name: String,
    val path: String,
    val sha256: String,
    val sizeBytes: Long,
    val multiplier: Double
) {
    init {
        require(ID_PATTERN.matches(id)) { "LoRA id must be a UUID." }
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) { "LoRA name is invalid." }
        require(path.isNotBlank() && path.length <= MAX_PATH_CHARS) { "LoRA path is invalid." }
        require(SHA256_PATTERN.matches(sha256)) { "LoRA sha256 is invalid." }
        require(sizeBytes in 16..MAX_LORA_BYTES) { "LoRA size is invalid." }
        require(multiplier.isFinite() &&
            multiplier in MIN_MULTIPLIER..MAX_MULTIPLIER &&
            kotlin.math.abs(multiplier) >= MIN_ABSOLUTE_MULTIPLIER
        ) {
            "LoRA multiplier must be in [-4, -0.01] or [0.01, 4]."
        }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("multiplier", multiplier)
        .apply { if (includePath) put("path", path) }

    companion object {
        const val MAX_COUNT = 8
        const val MIN_MULTIPLIER = -4.0
        const val MAX_MULTIPLIER = 4.0
        const val MIN_ABSOLUTE_MULTIPLIER = 0.01
        private const val MAX_NAME_CHARS = 128
        private const val MAX_PATH_CHARS = 4_096
        private const val MAX_LORA_BYTES = 2L * 1024L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val ID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun fromJson(json: JSONObject): LocalImagePreparedLora = LocalImagePreparedLora(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            path = json.requiredString("path"),
            sha256 = json.requiredString("sha256").lowercase(),
            sizeBytes = json.requiredExactLong("sizeBytes"),
            multiplier = json.requiredFiniteDouble("multiplier")
        )
    }
}

data class LocalImageVaeTilingOptions(
    val tileSize: Int,
    val overlap: Double
) {
    init {
        require(tileSize in MIN_TILE_SIZE..MAX_TILE_SIZE && tileSize % 8 == 0) {
            "vaeTiling.tileSize must be an 8-pixel multiple between $MIN_TILE_SIZE and $MAX_TILE_SIZE."
        }
        require(overlap.isFinite() && overlap >= 0.0 && overlap <= 0.5) {
            "vaeTiling.overlap must be a finite ratio in [0, 0.5]."
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("tileSize", tileSize)
        .put("overlap", overlap)

    companion object {
        private const val MIN_TILE_SIZE = 64
        private const val MAX_TILE_SIZE = 4_096

        fun fromJson(json: JSONObject): LocalImageVaeTilingOptions = LocalImageVaeTilingOptions(
            tileSize = json.requiredExactInt("tileSize"),
            overlap = json.requiredFiniteDouble("overlap")
        )
    }
}

data class LocalImagePreviewOptions(
    val interval: Int,
    val mode: LocalImagePreviewMode
) {
    init {
        require(interval in 1..100) { "preview.interval must be between 1 and 100 steps." }
        require(mode != LocalImagePreviewMode.NONE) {
            "Omit preview instead of specifying preview.mode=none."
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("interval", interval)
        .put("mode", mode.wireName)

    companion object {
        fun fromJson(json: JSONObject): LocalImagePreviewOptions = LocalImagePreviewOptions(
            interval = json.requiredExactInt("interval"),
            mode = LocalImagePreviewMode.fromWireName(json.requiredString("mode"))
        )
    }
}

/**
 * A bounded app-private copy ready to cross Binder. No data URL, content URI, or arbitrary path is
 * permitted in this type. The worker copies it once more into its own request directory before
 * native execution.
 */
data class LocalImagePreparedInput(
    val path: String,
    val mimeType: String,
    val sha256: String,
    val sizeBytes: Long,
    /** Encoded pixel dimensions before EXIF orientation is applied. */
    val width: Int,
    val height: Int,
    /** Logical dimensions seen by native decoders after EXIF orientation is applied. */
    val orientedWidth: Int = width,
    val orientedHeight: Int = height,
    val exifOrientation: Int = 1
) {
    init {
        require(path.isNotBlank()) { "Prepared image input path must not be blank." }
        require(mimeType.startsWith("image/") && mimeType.length <= 128) {
            "Prepared image input MIME type must use image/*."
        }
        require(SHA256_PATTERN.matches(sha256)) {
            "Prepared image input sha256 must be a lowercase 64-character digest."
        }
        require(sizeBytes in 1..MAX_INPUT_BYTES) {
            "Prepared image input size must be between 1 byte and $MAX_INPUT_BYTES bytes."
        }
        require(width in 1..MAX_IMAGE_SIDE && height in 1..MAX_IMAGE_SIDE) {
            "Prepared image input dimensions must be between 1 and $MAX_IMAGE_SIDE pixels per side."
        }
        require(width.toLong() * height.toLong() <= MAX_IMAGE_PIXELS) {
            "Prepared image input exceeds the $MAX_IMAGE_PIXELS pixel limit."
        }
        require(orientedWidth in 1..MAX_IMAGE_SIDE && orientedHeight in 1..MAX_IMAGE_SIDE &&
            orientedWidth.toLong() * orientedHeight.toLong() <= MAX_IMAGE_PIXELS
        ) { "Prepared image input oriented dimensions are invalid." }
        require(exifOrientation in 1..8) {
            "Prepared image input EXIF orientation must be between 1 and 8."
        }
        require(
            (orientedWidth to orientedHeight) ==
                localImageOrientedDimensions(width, height, exifOrientation)
        ) { "Prepared image input dimensions do not match its EXIF orientation." }
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .apply { if (includePath) put("path", path) }
        .put("mimeType", mimeType)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("width", width)
        .put("height", height)
        .put("orientedWidth", orientedWidth)
        .put("orientedHeight", orientedHeight)
        .put("exifOrientation", exifOrientation)

    companion object {
        const val MAX_INPUT_BYTES: Long = 32L * 1024L * 1024L
        const val MAX_IMAGE_SIDE: Int = 8_192
        const val MAX_IMAGE_PIXELS: Long = 64L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

        fun fromJson(json: JSONObject): LocalImagePreparedInput {
            val width = json.requiredExactInt("width")
            val height = json.requiredExactInt("height")
            val exifOrientation = if (json.has("exifOrientation")) {
                json.requiredExactInt("exifOrientation")
            } else {
                1
            }
            val defaultOriented = localImageOrientedDimensions(width, height, exifOrientation)
            return LocalImagePreparedInput(
                path = json.requiredString("path"),
                mimeType = json.requiredString("mimeType"),
                sha256 = json.requiredString("sha256").lowercase(),
                sizeBytes = json.requiredExactLong("sizeBytes"),
                width = width,
                height = height,
                orientedWidth = if (json.has("orientedWidth")) {
                    json.requiredExactInt("orientedWidth")
                } else defaultOriented.first,
                orientedHeight = if (json.has("orientedHeight")) {
                    json.requiredExactInt("orientedHeight")
                } else defaultOriented.second,
                exifOrientation = exifOrientation
            )
        }
    }
}

internal fun localImageOrientedDimensions(
    encodedWidth: Int,
    encodedHeight: Int,
    exifOrientation: Int
): Pair<Int, Int> {
    require(encodedWidth > 0 && encodedHeight > 0)
    require(exifOrientation in 1..8)
    return if (exifOrientation in setOf(5, 6, 7, 8)) {
        encodedHeight to encodedWidth
    } else {
        encodedWidth to encodedHeight
    }
}

/** Mirrors the Float strength crossing JNI so every layer selects the same denoising tail. */
internal fun localImageDenoisingTailStepCount(totalSteps: Int, strength: Double): Int {
    require(totalSteps > 0) { "Denoising step count must be positive." }
    val wireStrength = strength.toFloat()
    require(wireStrength.isFinite() && wireStrength in 0.0f..1.0f) {
        "Denoising strength must be finite and in [0, 1]."
    }
    val beginIndex = (totalSteps.toFloat() * (1.0f - wireStrength))
        .toInt()
        .coerceIn(0, totalSteps - 1)
    return totalSteps - beginIndex
}

/** Canonical quality-noise seed binding shared by the native stable and QNN bridges. */
internal fun localImageUltraFixNoiseSeedFingerprint(
    seed: Long,
    denoiseStepCount: Int
): String {
    require(denoiseStepCount > 0) { "UltraFix denoising tail must be positive." }
    val descriptor = "mca-ultrafix-quality-noise-v1|seed=${seed.toULong()}|steps=$denoiseStepCount"
    return MessageDigest.getInstance("SHA-256")
        .digest(descriptor.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/**
 * Rebuilds the fixed graph-aligned QNN tile plan descriptor. QNN UltraFix uses an 8x latent
 * spatial scale and an 8-latent-origin alignment for both the shared and isolated topologies.
 */
internal data class LocalQnnUltraFixTilePlanEvidence(
    val tileCount: Long,
    val tilePlanSha256: String
)

internal fun localQnnUltraFixTilePlanEvidence(
    request: LocalImageUltraFixOptions
): LocalQnnUltraFixTilePlanEvidence {
    val scale = 8
    val alignment = 8
    require(request.targetWidth >= request.tileSize && request.targetHeight >= request.tileSize) {
        "QNN UltraFix target dimensions must cover the fixed tile canvas."
    }
    require(request.targetWidth % (scale * alignment) == 0 &&
        request.targetHeight % (scale * alignment) == 0 &&
        request.tileSize % (scale * alignment) == 0
    ) { "QNN UltraFix dimensions must align to the fixed latent origin grid." }

    fun axisPositions(extentPixels: Int): List<Int> {
        val extent = extentPixels / scale
        val tile = request.tileSize / scale
        require(extent > 0 && tile > 0 && tile <= extent)
        if (extent == tile) return listOf(0)
        val minimumOverlap = kotlin.math.floor(tile.toDouble() * request.overlap).toInt()
        require(minimumOverlap in 0 until tile)
        val maximumStride = tile - minimumOverlap
        val finalStart = extent - tile
        val intervalCount = (finalStart + maximumStride - 1) / maximumStride
        require(intervalCount > 0)
        val baseStride = finalStart / intervalCount
        val remainder = finalStart % intervalCount
        val unaligned = ArrayList<Int>(intervalCount + 1)
        unaligned += 0
        var cursor = 0
        repeat(intervalCount) { index ->
            val step = baseStride + if (index < remainder) 1 else 0
            require(step > 0) { "QNN UltraFix tile stride did not advance." }
            cursor += step
            unaligned += cursor
        }
        unaligned[unaligned.lastIndex] = finalStart
        val positions = unaligned.mapIndexed { index, position ->
            if (index == unaligned.lastIndex) finalStart else (position / alignment) * alignment
        }.distinct()
        require(positions.first() == 0 && positions.last() == finalStart) {
            "QNN UltraFix aligned tile grid did not cover both canvas edges."
        }
        return positions
    }

    val xPositions = axisPositions(request.targetWidth)
    val yPositions = axisPositions(request.targetHeight)
    val tile = request.tileSize / scale
    val descriptor = buildString {
        append("ultrafix-tile-plan-v2|target=")
        append(request.targetWidth).append('x').append(request.targetHeight)
        append("|tile=").append(request.tileSize)
        append("|scale=").append(scale)
        append("|alignment=").append(alignment)
        append("|overlapBits=").append(qnnUltraFixOverlapBits(request.overlap))
        append("|tiles=").append(xPositions.size.toLong() * yPositions.size.toLong())
        yPositions.forEachIndexed { yIndex, latentY ->
            xPositions.forEachIndexed { xIndex, latentX ->
                val left = if (xIndex == 0) 0 else
                    xPositions[xIndex - 1] + tile - latentX
                val right = if (xIndex + 1 == xPositions.size) 0 else
                    latentX + tile - xPositions[xIndex + 1]
                val top = if (yIndex == 0) 0 else
                    yPositions[yIndex - 1] + tile - latentY
                val bottom = if (yIndex + 1 == yPositions.size) 0 else
                    latentY + tile - yPositions[yIndex + 1]
                require(left in 0 until tile && right in 0 until tile &&
                    top in 0 until tile && bottom in 0 until tile
                ) { "QNN UltraFix tile overlap consumed an entire latent tile." }
                append('|')
                    .append(latentX * scale).append(',').append(latentY * scale).append(',')
                    .append(latentX).append(',').append(latentY).append(',')
                    .append(left * scale).append(',').append(right * scale).append(',')
                    .append(top * scale).append(',').append(bottom * scale).append(',')
                    .append(left).append(',').append(right).append(',')
                    .append(top).append(',').append(bottom)
            }
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(descriptor.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return LocalQnnUltraFixTilePlanEvidence(
        tileCount = xPositions.size.toLong() * yPositions.size.toLong(),
        tilePlanSha256 = digest
    )
}

private fun qnnUltraFixOverlapBits(value: Double): String =
    value.toRawBits().toULong().toString(radix = 16).padStart(16, '0')

/** Raw main-process references. These are never serialized into worker IPC. */
data class LocalImageInputDraft(
    val taskMode: LocalImageTaskMode = LocalImageTaskMode.TEXT_TO_IMAGE,
    val inputImageReference: String? = null,
    val maskImageReference: String? = null,
    val controlImageReference: String? = null,
    val strength: Double? = null,
    val controlStrength: Double? = null
) {
    fun validate() {
        validateImageInputShape(
            taskMode = taskMode,
            inputPresent = !inputImageReference.isNullOrBlank(),
            maskPresent = !maskImageReference.isNullOrBlank(),
            controlPresent = !controlImageReference.isNullOrBlank(),
            strength = strength,
            controlStrength = controlStrength
        )
        listOfNotNull(inputImageReference, maskImageReference, controlImageReference).forEach { reference ->
            require(reference.length <= MAX_REFERENCE_CHARS) {
                "Image input reference exceeds the $MAX_REFERENCE_CHARS character limit."
            }
        }
    }

    internal fun validateForHistory() {
        validateImageInputShape(
            taskMode = taskMode,
            inputPresent = !inputImageReference.isNullOrBlank(),
            maskPresent = !maskImageReference.isNullOrBlank(),
            controlPresent = !controlImageReference.isNullOrBlank(),
            strength = strength,
            controlStrength = controlStrength,
            allowMissingRequiredInputs = true
        )
        listOfNotNull(inputImageReference, maskImageReference, controlImageReference).forEach { reference ->
            require(reference.length <= MAX_REFERENCE_CHARS) {
                "Image input reference exceeds the $MAX_REFERENCE_CHARS character limit."
            }
        }
    }

    internal fun hasRequiredInputReferences(): Boolean = when (taskMode) {
        LocalImageTaskMode.TEXT_TO_IMAGE -> true
        LocalImageTaskMode.IMG2IMG,
        LocalImageTaskMode.EDIT -> !inputImageReference.isNullOrBlank()
        LocalImageTaskMode.INPAINT ->
            !inputImageReference.isNullOrBlank() && !maskImageReference.isNullOrBlank()
        LocalImageTaskMode.CONTROL -> !controlImageReference.isNullOrBlank()
    }

    companion object {
        const val MAX_REFERENCE_CHARS: Int = 48 * 1024 * 1024
    }
}

internal fun LocalImageGenerationOptions.validateProductInputContract(
    requirePreparedPaths: Boolean = true
) {
    validateImageInputShape(
        taskMode = taskMode,
        inputPresent = inputImage != null,
        maskPresent = maskImage != null,
        controlPresent = controlImage != null,
        strength = strength,
        controlStrength = controlStrength
    )
    require(batchCount in 1..8) { "batchCount must be between 1 and 8." }
    require(loras.size <= LocalImagePreparedLora.MAX_COUNT) {
        "At most ${LocalImagePreparedLora.MAX_COUNT} LoRA adapters may be used per request."
    }
    require(loras.map(LocalImagePreparedLora::id).distinct().size == loras.size) {
        "LoRA adapter ids must be unique per request."
    }
    require(loras.map { File(it.path).canonicalPath }.distinct().size == loras.size) {
        "LoRA adapter paths must be unique per request."
    }
    clipSkip?.let { value ->
        require(value in -1..32) { "clipSkip must be -1 (model default) or between 0 and 32." }
    }
    if (maskImage != null && inputImage != null) {
        require(maskImage.orientedWidth == inputImage.orientedWidth &&
            maskImage.orientedHeight == inputImage.orientedHeight
        ) {
            "maskImage oriented dimensions must exactly match inputImage dimensions."
        }
    }
    if (requirePreparedPaths) {
        listOfNotNull(inputImage, maskImage, controlImage).forEach { input ->
            require(input.path.isNotBlank()) { "Prepared image input path must not be blank." }
        }
    }
    ultraFix?.let { request ->
        require(taskMode == LocalImageTaskMode.IMG2IMG && inputImage != null) {
            "UltraFix requires one prepared img2img source."
        }
        require(maskImage == null && controlImage == null) {
            "UltraFix does not accept mask or control inputs."
        }
        require(batchCount == 1) { "UltraFix supports exactly one output per native request." }
        require(preview == null) { "UltraFix does not publish live previews." }
        require(width == null || width == request.targetWidth)
        require(height == null || height == request.targetHeight)
        require(steps == null || steps == request.refinementSteps)
        require(strength == null || kotlin.math.abs(strength - request.strength) <= 1.0e-12)
        require(vaeTiling == null || (
            vaeTiling.tileSize == request.tileSize &&
                kotlin.math.abs(vaeTiling.overlap - request.overlap) <= 1.0e-12
            )) {
            "UltraFix outer execution controls must match its structured request."
        }
    }
}

/** Validates path-free persisted controls before a history item is shown or restaged. */
internal fun LocalImageGenerationOptions.validateHistoryProductInputContract() {
    require(inputImage == null && maskImage == null && controlImage == null &&
        loras.isEmpty() && preview == null
    ) { "Image history must not retain transient execution artifacts." }
    validateImageInputShape(
        taskMode = taskMode,
        inputPresent = false,
        maskPresent = false,
        controlPresent = false,
        strength = strength,
        controlStrength = controlStrength,
        allowMissingRequiredInputs = true
    )
    require(batchCount in 1..8) { "Image history batchCount must be between 1 and 8." }
    clipSkip?.let { require(it in -1..32) { "Image history clipSkip is invalid." } }
    ultraFix?.let { request ->
        require(taskMode == LocalImageTaskMode.IMG2IMG && batchCount == 1) {
            "UltraFix history must retain one img2img output."
        }
        require(width == request.targetWidth && height == request.targetHeight &&
            steps == request.refinementSteps &&
            strength != null && kotlin.math.abs(strength - request.strength) <= 1.0e-12 &&
            vaeTiling?.tileSize == request.tileSize &&
            kotlin.math.abs((vaeTiling?.overlap ?: Double.NaN) - request.overlap) <= 1.0e-12
        ) { "UltraFix history controls conflict with its structured request." }
    }
}

/**
 * UltraFix owns its target canvas, denoising tail, and VAE tile plan. UI and Local API callers may
 * omit the duplicated outer controls, but a conflicting duplicate is always rejected.
 */
internal fun LocalImageGenerationOptions.withCanonicalUltraFixControls(): LocalImageGenerationOptions {
    val request = ultraFix ?: return this
    validateProductInputContract(requirePreparedPaths = false)
    return copy(
        width = request.targetWidth,
        height = request.targetHeight,
        steps = request.refinementSteps,
        strength = request.strength,
        vaeTiling = LocalImageVaeTilingOptions(request.tileSize, request.overlap)
    )
}

internal fun LocalImageGenerationOptions.inputAuditJson(): JSONObject = JSONObject()
    .put("taskMode", taskMode.wireName)
    .put("batchCount", batchCount)
    .apply {
        inputImage?.let { put("inputImage", it.toJson(includePath = false)) }
        maskImage?.let { put("maskImage", it.toJson(includePath = false)) }
        controlImage?.let { put("controlImage", it.toJson(includePath = false)) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
        clipSkip?.let { put("clipSkip", it) }
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        ultraFix?.let { put("ultraFix", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }

internal fun LocalImageGenerationOptions.putProductInputNativeParams(target: JSONObject): JSONObject {
    validateProductInputContract()
    target.put("taskMode", taskMode.wireName)
        .put("batchCount", batchCount)
    inputImage?.let { input ->
        target.put("inputImagePath", input.path)
            .put("inputImageSha256", input.sha256)
    }
    maskImage?.let { input ->
        target.put("maskImagePath", input.path)
            .put("maskImageSha256", input.sha256)
    }
    controlImage?.let { input ->
        target.put("controlImagePath", input.path)
            .put("controlImageSha256", input.sha256)
    }
    strength?.let { target.put("strength", it) }
    controlStrength?.let { target.put("controlStrength", it) }
    clipSkip?.let { target.put("clipSkip", it) }
    target.put("loraCount", loras.size)
    if (loras.isNotEmpty()) {
        val canonicalParents = loras.map { File(it.path).canonicalFile.parentFile?.canonicalPath }.distinct()
        val loraRoot = canonicalParents.singleOrNull()
        require(!loraRoot.isNullOrBlank()) {
            "All LoRA adapters must be direct children of one app-private root."
        }
        target.put("loraRootPath", loraRoot)
        target.put(
            "loras",
            JSONArray().apply { loras.forEach { adapter -> put(adapter.toJson()) } }
        )
    } else {
        target.put("loraRootPath", "")
        target.put("loras", JSONArray())
    }
    vaeTiling?.let { target.put("vaeTiling", it.toJson()) }
    ultraFix?.let { target.put("ultraFix", it.toJson()) }
    preview?.let { target.put("preview", it.toJson()) }
    return target
}

internal fun validateLocalImageRuntimeProductOptions(
    runtime: LocalImageRuntime,
    options: LocalImageGenerationOptions
) {
    options.validateProductInputContract()
    if (options.ultraFix != null && runtime !in setOf(
            LocalImageRuntime.STABLE_DIFFUSION_CPP,
            LocalImageRuntime.QNN_HTP
        )
    ) {
        rejectProductInput(
            "unsupported_ultrafix",
            "Runtime ${runtime.name} does not expose a complete native UltraFix pipeline."
        )
    }
    if (options.batchCount != 1 && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        rejectProductInput(
            "unsupported_batch_count",
            "Runtime ${runtime.name} currently supports one output per request; native execution was not started."
        )
    }
    if (options.preview != null &&
        runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        runtime != LocalImageRuntime.QNN_HTP
    ) {
        rejectProductInput(
            "unsupported_preview",
            "Runtime ${runtime.name} does not expose a native preview callback."
        )
    }
    if (options.clipSkip != null && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        rejectProductInput(
            "unsupported_clip_skip",
            "Runtime ${runtime.name} does not consume clipSkip; native execution was not started."
        )
    }
    if (options.vaeTiling != null &&
        runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        !(runtime == LocalImageRuntime.QNN_HTP && options.ultraFix != null)
    ) {
        rejectProductInput(
            "unsupported_vae_tiling",
            "Runtime ${runtime.name} does not expose native VAE tiling controls."
        )
    }
    if (options.loras.isNotEmpty() && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        rejectProductInput(
            "unsupported_lora",
            "Runtime ${runtime.name} does not expose a native LoRA execution path."
        )
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP &&
        options.distilledGuidance != null
    ) {
        rejectProductInput(
            "unsupported_distilled_guidance",
            "Runtime ${runtime.name} has no distilled-guidance graph input; native execution was not started."
        )
    }
    if (runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP && options.flowShift != null) {
        rejectProductInput(
            "unsupported_flow_shift",
            "Runtime ${runtime.name} does not expose a writable native flow-shift control."
        )
    }
    if (runtime == LocalImageRuntime.MNN_DIFFUSION &&
        options.taskMode == LocalImageTaskMode.EDIT
    ) {
        if (options.strength != null) {
            rejectProductInput(
                "unsupported_edit_strength",
                "This MNN edit execution path does not consume strength; native execution was not started."
            )
        }
    }
    // Runtime/profile capability checks belong to the resolved execution path. This generic
    // boundary only enforces product-wide lifecycle limits; it must not pre-emptively block an
    // edit or control request merely because a runtime is new or was not previously validated.
}

/**
 * Enforces the selected package's executable capability contract after profile resolution.
 * Runtime-wide checks above remain the early safety boundary; this check prevents a runtime
 * feature from being exposed to a model family whose native conditioner cannot consume it.
 */
internal fun validateLocalImageProfileProductOptions(
    profile: ImageExecutionProfile,
    options: LocalImageGenerationOptions
) {
    val capabilities = profile.capabilities
    val sharedQnnVaePreview = profile.hasSharedQnnVaePreviewTopology()
    val runtimeHasNativePreview =
        profile.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP ||
            sharedQnnVaePreview
    if (profile.runtime == LocalImageRuntime.QNN_HTP &&
        options.taskMode != LocalImageTaskMode.TEXT_TO_IMAGE
    ) {
        val hasExecutableTaskTopology = when (options.taskMode) {
            LocalImageTaskMode.IMG2IMG -> profile.hasExecutableQnnImg2ImgTopology()
            LocalImageTaskMode.INPAINT -> profile.hasExecutableQnnInpaintTopology()
            LocalImageTaskMode.CONTROL -> profile.graph.controlNet != null
            LocalImageTaskMode.TEXT_TO_IMAGE -> true
            LocalImageTaskMode.EDIT -> false
        }
        if (!hasExecutableTaskTopology) {
            rejectProductInput(
                "task_mode_execution_unsupported",
                "The resolved QNN graph topology cannot execute ${options.taskMode.wireName}; " +
                    "a concrete compatible input graph is required before native execution starts."
            )
        }
    }
    if (options.textualInversionIds.isNotEmpty() &&
        (!profile.capabilities.supportsTextualInversion ||
            !profile.tokenizer.supportsTextualInversion)
    ) {
        rejectProductInput(
            "textual_inversion_execution_unsupported",
            "The resolved tokenizer and conditioning graph do not support textual inversion."
        )
    }
    if (options.ultraFix != null && !profile.capabilities.supportsUltraFix) {
        rejectProductInput(
            "ultrafix_execution_unsupported",
            "The resolved graph does not expose the complete UltraFix pipeline."
        )
    }
    options.ultraFix?.let { request ->
        require(request.targetWidth in
            capabilities.ultraFixMinWidth..capabilities.ultraFixMaxWidth &&
            request.targetHeight in
            capabilities.ultraFixMinHeight..capabilities.ultraFixMaxHeight &&
            request.targetWidth % capabilities.ultraFixWidthMultiple == 0 &&
            request.targetHeight % capabilities.ultraFixHeightMultiple == 0 &&
            request.tileSize % capabilities.ultraFixWidthMultiple == 0 &&
            request.tileSize % capabilities.ultraFixHeightMultiple == 0 &&
            (capabilities.ultraFixRequiredTileSize == 0 ||
                request.tileSize == capabilities.ultraFixRequiredTileSize)
        ) {
            "UltraFix target and tile dimensions do not match the resolved graph topology."
        }
    }
    validateLocalImageTaskSamplerCapability(
        profile = profile,
        taskMode = options.taskMode,
        requestedScheduler = profile.scheduler.algorithm
    )
    if (options.batchCount > capabilities.maxBatchCount) {
        rejectProductInput(
            "unsupported_batch_count",
            "Profile ${profile.profileId} supports at most ${capabilities.maxBatchCount} output(s) per request."
        )
    }
    if (options.clipSkip != null && !capabilities.supportsClipSkip) {
        rejectProductInput(
            "unsupported_clip_skip",
            "Profile ${profile.profileId} has no CLIP layer-skip execution path."
        )
    }
    if (options.vaeTiling != null && options.ultraFix == null &&
        !capabilities.supportsVaeTiling
    ) {
        rejectProductInput(
            "unsupported_vae_tiling",
            "Profile ${profile.profileId} does not consume native VAE tiling controls."
        )
    }
    if (options.preview != null && !runtimeHasNativePreview) {
        rejectProductInput(
            "unsupported_preview",
            "Profile ${profile.profileId} does not expose a native live-preview callback."
        )
    }
    if (options.preview != null && profile.runtime == LocalImageRuntime.QNN_HTP) {
        if (options.preview.interval !in 1..10) {
            rejectProductInput(
                "invalid_preview_interval",
                "QNN live preview interval must be between 1 and 10 steps."
            )
        }
        val supportedPreview =
            sharedQnnVaePreview && options.preview.mode == LocalImagePreviewMode.VAE
        if (!supportedPreview) {
            rejectProductInput(
                "unsupported_preview",
                "QNN preview requires a resolved shared-QNN VAE topology."
            )
        }
    }
    if (options.loras.isNotEmpty() && !capabilities.supportsLora) {
        rejectProductInput(
            "unsupported_lora",
            "Profile ${profile.profileId} does not expose LoRA adapter execution."
        )
    }
}

/**
 * Scheduler availability is a product-task property, not a model-id or device admission rule.
 * QNN img2img and inpaint start from a strength-derived denoising index. PNDM/PLMS has a stateful
 * PRK/PLMS warm-up and cannot enter that schedule at an arbitrary index, while the same scheduler
 * remains valid for a full text-to-image timetable.
 */
internal fun ImageExecutionProfile.supportedSchedulersForProductTask(
    taskMode: LocalImageTaskMode
): Set<ImageSchedulerAlgorithm> {
    val declared = capabilities.supportedSchedulers
    return when {
        taskMode == LocalImageTaskMode.IMG2IMG && hasExecutableQnnImg2ImgTopology() ->
            declared - ImageSchedulerAlgorithm.PNDM_PLMS
        taskMode == LocalImageTaskMode.INPAINT && hasExecutableQnnInpaintTopology() ->
            supportedQnnInpaintSchedulers()
        else -> declared
    }
}

private val PRODUCT_TASK_SAMPLER_PREFERENCE = listOf(
    ImageSchedulerAlgorithm.DPMPP_2M,
    ImageSchedulerAlgorithm.EULER,
    ImageSchedulerAlgorithm.EULER_A,
    ImageSchedulerAlgorithm.DDIM,
    ImageSchedulerAlgorithm.LCM,
    ImageSchedulerAlgorithm.FLOW_MATCH,
    ImageSchedulerAlgorithm.PNDM_PLMS
)

/**
 * Chooses the deterministic sampler used when a request omits `sampler`/`sampleMethod`.
 *
 * The profile's own default remains authoritative whenever it is valid for the selected task.
 * Only a task-incompatible default (notably generic QNN profiles whose legacy default is PNDM)
 * is replaced. The fallback order is intentionally independent of recommendation id, chipset,
 * and device validation state.
 */
internal fun ImageExecutionProfile.defaultSchedulerForProductTask(
    taskMode: LocalImageTaskMode
): ImageSchedulerAlgorithm {
    val supported = supportedSchedulersForProductTask(taskMode)
    scheduler.algorithm.takeIf { it in supported }?.let { return it }
    PRODUCT_TASK_SAMPLER_PREFERENCE.firstOrNull { it in supported }?.let { return it }
    rejectProductInput(
        "unsupported_sampler",
        "The resolved image profile has no sampler supported for ${taskMode.wireName}."
    )
}

internal fun ImageExecutionProfile.orderedSchedulersForProductTask(
    taskMode: LocalImageTaskMode
): List<ImageSchedulerAlgorithm> {
    val supported = supportedSchedulersForProductTask(taskMode)
    if (supported.isEmpty()) return emptyList()
    val taskDefault = defaultSchedulerForProductTask(taskMode)
    return buildList {
        add(taskDefault)
        PRODUCT_TASK_SAMPLER_PREFERENCE.forEach { scheduler ->
            if (scheduler != taskDefault && scheduler in supported) add(scheduler)
        }
        supported.forEach { scheduler ->
            if (scheduler !in this) add(scheduler)
        }
    }
}

internal fun validateLocalImageTaskSamplerCapability(
    profile: ImageExecutionProfile,
    taskMode: LocalImageTaskMode,
    requestedScheduler: ImageSchedulerAlgorithm
) {
    if (requestedScheduler in profile.supportedSchedulersForProductTask(taskMode)) return
    if (
        taskMode == LocalImageTaskMode.IMG2IMG &&
        profile.hasExecutableQnnImg2ImgTopology() &&
        requestedScheduler == ImageSchedulerAlgorithm.PNDM_PLMS
    ) {
        rejectProductInput(
            "unsupported_img2img_sampler",
            "QNN img2img cannot use PNDM/PLMS because that scheduler cannot start at the " +
            "strength-derived denoising index. Select another sampler supported by this model."
        )
    }
    if (
        taskMode == LocalImageTaskMode.INPAINT &&
        profile.hasExecutableQnnInpaintTopology() &&
        requestedScheduler == ImageSchedulerAlgorithm.PNDM_PLMS
    ) {
        rejectProductInput(
            "unsupported_inpaint_sampler",
            "QNN inpaint cannot use PNDM/PLMS because that scheduler cannot start at the " +
                "strength-derived denoising index. Select another sampler supported by this model."
        )
    }
    rejectProductInput(
        "unsupported_sampler",
        "The resolved image profile does not support ${imageSchedulerProductName(requestedScheduler)} " +
            "for ${taskMode.wireName}."
    )
}

/**
 * Verifies native echo before converting worker-private paths into non-sensitive content hashes.
 * Kotlin-synthesized request metadata alone is never accepted as execution proof.
 */
internal fun verifyAndSanitizeStableDiffusionProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "stable-diffusion.cpp did not report nativeEffective image input evidence."
        )
    fun requireExactString(field: String, expected: String) {
        require(result.optString(field) == expected && nativeEffective.optString(field) == expected) {
            "stable-diffusion.cpp did not execute the requested $field."
        }
    }
    fun requireExactInt(field: String, expected: Int) {
        require(result.optInt(field, Int.MIN_VALUE) == expected &&
            nativeEffective.optInt(field, Int.MIN_VALUE) == expected
        ) { "stable-diffusion.cpp did not execute the requested $field." }
    }
    fun requireExactDouble(field: String, expected: Double) {
        val outer = result.optDouble(field, Double.NaN)
        val inner = nativeEffective.optDouble(field, Double.NaN)
        require(
            nativeFloatMatches(outer, expected) &&
                nativeFloatMatches(inner, expected)
        ) { "stable-diffusion.cpp did not execute the requested $field." }
    }
    fun requirePathAndCount(
        pathField: String,
        countField: String,
        expected: LocalImagePreparedInput?
    ) {
        val hashField = pathField.removeSuffix("Path") + "Sha256"
        val expectedCount = if (expected == null) 0 else 1
        requireExactInt(countField, expectedCount)
        require(result.has(pathField) && !result.isNull(pathField) &&
            nativeEffective.has(pathField) && !nativeEffective.isNull(pathField)
        ) { "stable-diffusion.cpp did not report $pathField execution evidence." }
        require(result.has(hashField) && !result.isNull(hashField) &&
            nativeEffective.has(hashField) && !nativeEffective.isNull(hashField)
        ) { "stable-diffusion.cpp did not report $hashField execution evidence." }
        val outerPath = result.optString(pathField)
        val innerPath = nativeEffective.optString(pathField)
        val outerSha256 = result.optString(hashField).lowercase()
        val innerSha256 = nativeEffective.optString(hashField).lowercase()
        if (expected == null) {
            require(outerPath.isBlank() && innerPath.isBlank() &&
                outerSha256.isBlank() && innerSha256.isBlank()
            ) {
                "stable-diffusion.cpp reported evidence for an unused $pathField."
            }
        } else {
            val expectedPath = File(expected.path).canonicalPath
            require(outerPath.isNotBlank() && innerPath.isNotBlank() &&
                File(outerPath).canonicalPath == expectedPath &&
                File(innerPath).canonicalPath == expectedPath
            ) { "stable-diffusion.cpp did not execute the prepared $pathField." }
            require(outerSha256 == expected.sha256 && innerSha256 == expected.sha256) {
                "stable-diffusion.cpp did not prove the decoded bytes for $pathField."
            }
        }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }

    requireExactString("taskMode", options.taskMode.wireName)
    requirePathAndCount("inputImagePath", "inputImageExecutionCount", options.inputImage)
    requirePathAndCount("maskImagePath", "maskImageExecutionCount", options.maskImage)
    requirePathAndCount("controlImagePath", "controlImageExecutionCount", options.controlImage)
    requireExactDouble("strength", options.strength ?: 1.0)
    requireExactDouble("controlStrength", options.controlStrength ?: 1.0)
    requireExactInt("clipSkip", options.clipSkip ?: -1)
    requireExactInt("batchCount", options.batchCount)

    val timetableCount = result.optInt("timetableCount", -1)
    require(timetableCount > 0 &&
        nativeEffective.optInt("timetableCount", -1) == timetableCount
    ) { "stable-diffusion.cpp timetableCount evidence is missing or inconsistent." }
    require(result.has("useCfg") && !result.isNull("useCfg") &&
        nativeEffective.has("useCfg") && !nativeEffective.isNull("useCfg")
    ) { "stable-diffusion.cpp did not report its actual CFG mode." }
    val useCfg = result.getBoolean("useCfg")
    require(nativeEffective.getBoolean("useCfg") == useCfg) {
        "stable-diffusion.cpp CFG evidence differs between the result and nativeEffective."
    }
    val expectedUnetExecutionCountLong =
        timetableCount.toLong() * (if (useCfg) 2L else 1L)
    require(expectedUnetExecutionCountLong <= Int.MAX_VALUE.toLong()) {
        "stable-diffusion.cpp CFG execution count exceeds the supported range."
    }
    val expectedUnetExecutionCount = expectedUnetExecutionCountLong.toInt()
    require(result.optInt("unetExecutionCount", -1) == expectedUnetExecutionCount &&
        nativeEffective.optInt("unetExecutionCount", -1) == expectedUnetExecutionCount
    ) {
        "stable-diffusion.cpp physical diffusion count does not match timetableCount and CFG."
    }

    val outerControlNetEvidence = result.optJSONObject("controlNetEvidence")
        ?: error("stable-diffusion.cpp did not report outer ControlNet execution evidence.")
    val nativeControlNetEvidence = nativeEffective.optJSONObject("controlNetEvidence")
        ?: error("stable-diffusion.cpp did not report nativeEffective ControlNet execution evidence.")
    val controlEvidenceFields = listOf(
        "computeAttemptCount",
        "computeSuccessCount",
        "positiveComputeAttemptCount",
        "positiveComputeSuccessCount",
        "negativeComputeAttemptCount",
        "negativeComputeSuccessCount",
        "residualConsumptionCount",
        "positiveResidualConsumptionCount",
        "negativeResidualConsumptionCount",
        "auxiliaryResidualConsumptionCount"
    )
    val controlCounts = controlEvidenceFields.associateWith { field ->
        val outer = outerControlNetEvidence.optLong(field, -1L)
        val native = nativeControlNetEvidence.optLong(field, -1L)
        require(outer >= 0L && native == outer) {
            "stable-diffusion.cpp ControlNet $field evidence is missing or inconsistent."
        }
        outer
    }
    val controlAttemptCount = controlCounts.getValue("computeAttemptCount")
    val controlSuccessCount = controlCounts.getValue("computeSuccessCount")
    val positiveControlAttemptCount = controlCounts.getValue("positiveComputeAttemptCount")
    val positiveControlSuccessCount = controlCounts.getValue("positiveComputeSuccessCount")
    val negativeControlAttemptCount = controlCounts.getValue("negativeComputeAttemptCount")
    val negativeControlSuccessCount = controlCounts.getValue("negativeComputeSuccessCount")
    val residualConsumptionCount = controlCounts.getValue("residualConsumptionCount")
    val positiveResidualConsumptionCount =
        controlCounts.getValue("positiveResidualConsumptionCount")
    val negativeResidualConsumptionCount =
        controlCounts.getValue("negativeResidualConsumptionCount")
    val auxiliaryResidualConsumptionCount =
        controlCounts.getValue("auxiliaryResidualConsumptionCount")
    require(controlAttemptCount == positiveControlAttemptCount + negativeControlAttemptCount &&
        controlSuccessCount == positiveControlSuccessCount + negativeControlSuccessCount &&
        residualConsumptionCount == positiveResidualConsumptionCount +
        negativeResidualConsumptionCount + auxiliaryResidualConsumptionCount
    ) { "stable-diffusion.cpp ControlNet evidence is not completely classified by branch." }
    val expectedControl = options.controlImage != null
    val outerControlStrengthApplied = result.optBoolean("controlStrengthApplied", false)
    val nativeControlStrengthApplied =
        nativeEffective.optBoolean("controlStrengthApplied", false)
    val expectedPositiveControlCount = if (expectedControl) timetableCount.toLong() else 0L
    val expectedNegativeControlCount =
        if (expectedControl && useCfg) timetableCount.toLong() else 0L
    val expectedTotalControlCount =
        expectedPositiveControlCount + expectedNegativeControlCount
    val outerImageInputConsumption = result.optJSONObject("imageInputConsumption")
        ?: error("stable-diffusion.cpp did not report outer imageInputConsumption evidence.")
    val nativeImageInputConsumption = nativeEffective.optJSONObject("imageInputConsumption")
        ?: error("stable-diffusion.cpp did not report nativeEffective imageInputConsumption evidence.")
    val expectedControlConsumption = if (expectedControl) "controlnet_residual" else "none"
    require(outerImageInputConsumption.optString("control") == expectedControlConsumption &&
        nativeImageInputConsumption.optString("control") == expectedControlConsumption
    ) { "stable-diffusion.cpp ControlNet residual-consumption evidence is inconsistent." }
    if (expectedControl) {
        require(controlAttemptCount == expectedTotalControlCount &&
            controlSuccessCount == expectedTotalControlCount &&
            positiveControlAttemptCount == expectedPositiveControlCount &&
            positiveControlSuccessCount == expectedPositiveControlCount &&
            negativeControlAttemptCount == expectedNegativeControlCount &&
            negativeControlSuccessCount == expectedNegativeControlCount &&
            residualConsumptionCount == expectedTotalControlCount &&
            positiveResidualConsumptionCount == expectedPositiveControlCount &&
            negativeResidualConsumptionCount == expectedNegativeControlCount &&
            auxiliaryResidualConsumptionCount == 0L &&
            outerControlStrengthApplied && nativeControlStrengthApplied
        ) {
            "stable-diffusion.cpp did not prove successful ControlNet compute and residual consumption."
        }
    } else {
        require(controlCounts.values.all { it == 0L } &&
            !outerControlStrengthApplied && !nativeControlStrengthApplied
        ) { "stable-diffusion.cpp reported unrequested ControlNet execution." }
    }

    val nativeTiling = nativeEffective.optJSONObject("vaeTiling")
        ?: error("stable-diffusion.cpp did not report vaeTiling evidence.")
    val outerTiling = result.optJSONObject("vaeTiling")
        ?: error("stable-diffusion.cpp did not report outer vaeTiling evidence.")
    val expectedTiling = options.vaeTiling
    val expectedEnabled = expectedTiling != null
    require(nativeTiling.optBoolean("enabled") == expectedEnabled &&
        outerTiling.optBoolean("enabled") == expectedEnabled
    ) { "stable-diffusion.cpp VAE tiling enablement did not match the request." }
    val expectedRequestedTileSize = expectedTiling?.tileSize ?: 0
    val expectedRequestedOverlap = expectedTiling?.overlap ?: 0.0
    listOf(nativeTiling, outerTiling).forEach { tiling ->
        require(tiling.optInt("requestedTileSize", -1) == expectedRequestedTileSize &&
            nativeFloatMatches(
                tiling.optDouble("requestedOverlap", Double.NaN),
                expectedRequestedOverlap
            )
        ) { "stable-diffusion.cpp VAE tiling request evidence is inconsistent." }
    }
    fun verifyVaeTilingPhase(
        phase: String,
        requireExecution: Boolean,
        exactInvocationCount: Long? = null
    ) {
        val outerPhase = outerTiling.optJSONObject(phase)
            ?: error("stable-diffusion.cpp did not report outer VAE $phase evidence.")
        val nativePhase = nativeTiling.optJSONObject(phase)
            ?: error("stable-diffusion.cpp did not report nativeEffective VAE $phase evidence.")
        val integerFields = listOf(
            "invocationCount",
            "successCount",
            "plannedTileCount",
            "tileComputeAttemptCount",
            "tileComputeSuccessCount",
            "tileSizeX",
            "tileSizeY"
        )
        val values = integerFields.associateWith { field ->
            val outer = outerPhase.optLong(field, -1L)
            val native = nativePhase.optLong(field, -1L)
            require(outer >= 0L && native == outer) {
                "stable-diffusion.cpp VAE $phase $field evidence is missing or inconsistent."
            }
            outer
        }
        val overlapX = outerPhase.optDouble("overlapX", Double.NaN)
        val overlapY = outerPhase.optDouble("overlapY", Double.NaN)
        require(nativeFloatMatches(nativePhase.optDouble("overlapX", Double.NaN), overlapX) &&
            nativeFloatMatches(nativePhase.optDouble("overlapY", Double.NaN), overlapY)
        ) { "stable-diffusion.cpp VAE $phase overlap evidence is inconsistent." }
        val invocationCount = values.getValue("invocationCount")
        if (invocationCount == 0L) {
            require(!requireExecution && values.values.all { it == 0L } &&
                overlapX == 0.0 && overlapY == 0.0
            ) { "stable-diffusion.cpp did not execute required VAE $phase tiling." }
            return
        }
        exactInvocationCount?.let { expectedCount ->
            require(invocationCount == expectedCount) {
                "stable-diffusion.cpp VAE $phase invocation count differs from the physical output plan."
            }
        }
        require(expectedEnabled &&
            values.getValue("successCount") == invocationCount &&
            values.getValue("plannedTileCount") > 0L &&
            values.getValue("tileComputeAttemptCount") ==
                values.getValue("plannedTileCount") &&
            values.getValue("tileComputeSuccessCount") ==
                values.getValue("tileComputeAttemptCount") &&
            values.getValue("tileSizeX") > 0L && values.getValue("tileSizeY") > 0L &&
            overlapX.isFinite() && overlapX in 0.0..0.5 &&
            overlapY.isFinite() && overlapY in 0.0..0.5
        ) { "stable-diffusion.cpp VAE $phase tiling did not complete its physical tile plan." }
    }
    verifyVaeTilingPhase(
        "encode",
        requireExecution = expectedEnabled && options.inputImage != null
    )
    verifyVaeTilingPhase(
        "decode",
        requireExecution = expectedEnabled,
        exactInvocationCount = if (expectedEnabled) options.batchCount.toLong() else null
    )

    val expectedPreview = options.preview
    val previewRequested = result.optBoolean("previewRequested", false)
    val previewMode = result.optString("previewMode")
    val previewInterval = result.optInt("previewInterval", -1)
    val previewPublicationCount = result.optInt("previewPublicationCount", -1)
    val previewLastStep = result.optInt("previewLastStep", -1)
    val previewLastRevision = result.optLong("previewLastRevision", -1L)
    if (expectedPreview == null) {
        require(
            !previewRequested && previewMode == "none" && previewInterval == 0 &&
                previewPublicationCount == 0 && previewLastStep == 0 && previewLastRevision == 0L
        ) { "stable-diffusion.cpp reported an unrequested preview callback." }
    } else {
        val totalSteps = result.optInt("steps", -1)
        require(
            previewRequested && previewMode == expectedPreview.mode.wireName &&
                previewInterval == expectedPreview.interval
        ) { "stable-diffusion.cpp preview mode or interval did not match the request." }
        require(
            previewPublicationCount > 0 && previewLastStep in 1..totalSteps &&
                previewLastRevision > 0L
        ) { "stable-diffusion.cpp did not prove publication of a real preview frame." }
    }

    val outerLoras = result.optJSONArray("loras")
        ?: error("stable-diffusion.cpp did not report outer LoRA execution evidence.")
    val nativeLoras = nativeEffective.optJSONArray("loras")
        ?: error("stable-diffusion.cpp did not report nativeEffective LoRA execution evidence.")
    require(outerLoras.length() == options.loras.size &&
        nativeLoras.length() == options.loras.size
    ) { "stable-diffusion.cpp LoRA evidence count differs from the request." }
    options.loras.forEachIndexed { index, expected ->
        listOf(outerLoras, nativeLoras).forEach { array ->
            val item = array.optJSONObject(index)
                ?: error("stable-diffusion.cpp LoRA evidence item must be an object.")
            require(!item.has("path")) {
                "stable-diffusion.cpp exposed a worker-private LoRA path."
            }
            require(item.optString("id") == expected.id &&
                item.optString("sha256").lowercase() == expected.sha256 &&
                nativeFloatMatches(
                    item.optDouble("multiplier", Double.NaN),
                    expected.multiplier
                )
            ) { "stable-diffusion.cpp LoRA identity or multiplier differs from the request." }
        }
    }
    val outerLoraEvidence = result.optJSONObject("loraEvidence")
        ?: error("stable-diffusion.cpp did not report outer LoRA count evidence.")
    val nativeLoraEvidence = nativeEffective.optJSONObject("loraEvidence")
        ?: error("stable-diffusion.cpp did not report nativeEffective LoRA count evidence.")
    listOf(outerLoraEvidence, nativeLoraEvidence).forEach { evidence ->
        val expectedCount = options.loras.size
        require(evidence.optInt("requestedCount", -1) == expectedCount &&
            evidence.optInt("loadedCount", -1) == expectedCount &&
            evidence.optInt("appliedCount", -1) == expectedCount
        ) { "stable-diffusion.cpp did not load and apply the complete LoRA set." }
        val appliedTensorCount = evidence.optLong("appliedTensorCount", -1L)
        require(
            if (expectedCount == 0) appliedTensorCount == 0L else appliedTensorCount > 0L
        ) { "stable-diffusion.cpp LoRA tensor execution evidence is invalid." }
    }

    var requiredStageMask = SDCPP_REQUIRED_SUCCESS_STAGES
    if (options.inputImage != null) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_INPUT_IMAGE_DECODED
    if (options.maskImage != null) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_MASK_IMAGE_DECODED
    if (options.controlImage != null) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_CONTROL_IMAGE_DECODED
    if (options.loras.isNotEmpty()) requiredStageMask =
        requiredStageMask or SDCPP_STAGE_LORA_VALIDATED
    listOf("nativeStageMask", "nativeDetailStageMask").forEach { field ->
        val stageMask = result.optLong(field, -1L)
        require(stageMask >= 0L && (stageMask and requiredStageMask) == requiredStageMask) {
            "stable-diffusion.cpp $field is missing required execution stages."
        }
    }

    val audit = options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
        .put("controlNetEvidence", JSONObject(nativeControlNetEvidence.toString()))
        .put("vaeTiling", JSONObject(nativeTiling.toString()))
        .put("loras", JSONArray(nativeLoras.toString()))
        .put("loraEvidence", JSONObject(nativeLoraEvidence.toString()))
    return audit
}

/** MNN must echo every product-input field and prove the same count with concrete outputs. */
internal fun verifyAndSanitizeMnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "MNN-Diffusion did not report nativeEffective image input evidence."
        )
    require(result.has("taskMode") && !result.isNull("taskMode") &&
        nativeEffective.has("taskMode") && !nativeEffective.isNull("taskMode")
    ) {
        "MNN-Diffusion is missing required taskMode execution evidence."
    }
    require(result.getString("taskMode") == options.taskMode.wireName) {
        "MNN-Diffusion did not execute the requested taskMode."
    }
    require(nativeEffective.getString("taskMode") == options.taskMode.wireName) {
        "MNN-Diffusion nativeEffective taskMode differs from the request."
    }

    val outputs = result.optJSONArray("outputs")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "MNN-Diffusion did not report its concrete outputs array."
        )
    require(outputs.length() == options.batchCount) {
        "MNN-Diffusion output count differs from batchCount."
    }
    val outputPath = result.optString("outputPath")
    val nativeOutputPath = nativeEffective.optString("outputPath")
    val outputBytes = result.optLong("outputBytes", -1L)
    val nativeOutputBytes = nativeEffective.optLong("outputBytes", -1L)
    val outputSha256 = result.optString("outputSha256")
    val nativeOutputSha256 = nativeEffective.optString("outputSha256")
    require(outputPath.isNotBlank() && outputPath == nativeOutputPath &&
        outputBytes > 0L && outputBytes == nativeOutputBytes &&
        outputSha256.matches(Regex("^[0-9a-f]{64}$")) &&
        outputSha256 == nativeOutputSha256
    ) { "MNN-Diffusion output publication proof is missing or inconsistent." }
    for (index in 0 until outputs.length()) {
        val output = outputs.optJSONObject(index)
            ?: error("MNN-Diffusion output item must be an object.")
        require(output.optInt("index", -1) == index) {
            "MNN-Diffusion output indices are not contiguous."
        }
        require(output.optString("path") == outputPath &&
            output.optLong("outputBytes", -1L) == outputBytes &&
            output.optString("outputSha256") == outputSha256
        ) {
            "MNN-Diffusion output item differs from the committed publication proof."
        }
        require(output.optString("mimeType") == "image/png") {
            "MNN-Diffusion output item must be a PNG."
        }
        require(output.has("seed") && output.getLong("seed") == nativeEffective.getLong("seed")) {
            "MNN-Diffusion output seed differs from nativeEffective."
        }
    }
    require(result.has("batchCount") && !result.isNull("batchCount") &&
        nativeEffective.has("batchCount") && !nativeEffective.isNull("batchCount") &&
        result.getInt("batchCount") == options.batchCount &&
        nativeEffective.getInt("batchCount") == options.batchCount
    ) {
        "MNN-Diffusion batchCount execution evidence differs from the request."
    }

    fun verifyRole(
        pathField: String,
        countField: String,
        hashField: String,
        expected: LocalImagePreparedInput?
    ) {
        val expectedCount = if (expected == null) 0 else 1
        require(result.has(countField) && !result.isNull(countField) &&
            nativeEffective.has(countField) && !nativeEffective.isNull(countField)
        ) {
            "MNN-Diffusion is missing required $countField execution evidence."
        }
        require(result.getInt(countField) == expectedCount) {
            "MNN-Diffusion $countField evidence differs from the request."
        }
        require(nativeEffective.getInt(countField) == expectedCount) {
            "MNN-Diffusion nativeEffective $countField differs from the request."
        }
        require(result.has(pathField) && !result.isNull(pathField) &&
            nativeEffective.has(pathField) && !nativeEffective.isNull(pathField)
        ) {
            "MNN-Diffusion is missing required $pathField execution evidence."
        }
        val outerPath = result.getString(pathField)
        val innerPath = nativeEffective.getString(pathField)
        if (expected == null) {
            require(outerPath.isBlank() && innerPath.isBlank()) {
                "MNN-Diffusion reported an unused $pathField."
            }
            require(!result.has(hashField) && !nativeEffective.has(hashField)) {
                "MNN-Diffusion reported unexpected $hashField evidence."
            }
        } else {
            val expectedPath = File(expected.path).canonicalPath
            require(outerPath.isNotBlank() && File(outerPath).canonicalPath == expectedPath) {
                "MNN-Diffusion did not execute the prepared $pathField."
            }
            require(innerPath.isNotBlank() && File(innerPath).canonicalPath == expectedPath) {
                "MNN-Diffusion nativeEffective did not bind the prepared $pathField."
            }
            require(result.has(hashField) && !result.isNull(hashField) &&
                nativeEffective.has(hashField) && !nativeEffective.isNull(hashField) &&
                result.getString(hashField) == expected.sha256 &&
                nativeEffective.getString(hashField) == expected.sha256
            ) {
                "MNN-Diffusion did not report the native-consumed $hashField."
            }
        }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }

    verifyRole("inputImagePath", "inputImageExecutionCount", "inputImageSha256", options.inputImage)
    verifyRole("maskImagePath", "maskImageExecutionCount", "maskImageSha256", options.maskImage)
    verifyRole(
        "controlImagePath",
        "controlImageExecutionCount",
        "controlImageSha256",
        options.controlImage
    )
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
}

/**
 * QNN currently exposes product image inputs only for the native ControlNet path.  Verify that the
 * prepared worker file was actually consumed on every scheduler step, then remove its private path
 * before the execution object can cross back into the main process or Local API response.
 */
private fun validateQnnNativeDetailStageMask(result: JSONObject): ULong {
    val detailStageMask = result.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD)
    result.optJSONObject("nativeEffective")?.let { nativeEffective ->
        if (nativeEffective.has(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ||
            nativeEffective.has("nativeDetailStageMask")
        ) {
            require(
                nativeEffective.strictUInt64Hex(QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD) ==
                    detailStageMask
            ) { "QNN detail stage mask conflicts with nativeEffective." }
        }
    }
    return detailStageMask
}

internal fun verifyAndSanitizeQnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
    validateQnnNativeDetailStageMask(result)
    require(options.taskMode == LocalImageTaskMode.CONTROL &&
        options.controlImage != null && options.inputImage == null && options.maskImage == null
    ) {
        "QNN image-input evidence is currently defined only for a control request."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "QNN ControlNet did not report nativeEffective image input evidence."
        )
    require(result.optString("taskMode") == LocalImageTaskMode.CONTROL.wireName &&
        nativeEffective.optString("taskMode") == LocalImageTaskMode.CONTROL.wireName
    ) { "QNN ControlNet did not execute the requested taskMode." }
    require(result.optInt("batchCount", -1) == 1 &&
        nativeEffective.optInt("batchCount", -1) == 1
    ) { "QNN ControlNet must report its single concrete output." }

    fun requireExactCount(field: String, expected: Int) {
        require(result.optInt(field, -1) == expected &&
            nativeEffective.optInt(field, -1) == expected
        ) { "QNN ControlNet $field evidence differs from the request." }
    }
    requireExactCount("inputImageExecutionCount", 0)
    requireExactCount("maskImageExecutionCount", 0)
    requireExactCount("controlImageExecutionCount", 1)

    listOf("inputImagePath", "maskImagePath").forEach { field ->
        require(result.optString(field).isBlank() && nativeEffective.optString(field).isBlank()) {
            "QNN ControlNet reported an unused $field."
        }
        result.remove(field)
        nativeEffective.remove(field)
    }
    val expectedInput = requireNotNull(options.controlImage)
    val expectedPath = File(expectedInput.path).canonicalPath
    val outerPath = result.optString("controlImagePath")
    val innerPath = nativeEffective.optString("controlImagePath")
    require(outerPath.isNotBlank() && innerPath.isNotBlank() &&
        File(outerPath).canonicalPath == expectedPath &&
        File(innerPath).canonicalPath == expectedPath
    ) { "QNN ControlNet did not consume the prepared control image." }
    result.remove("controlImagePath")
    nativeEffective.remove("controlImagePath")

    require(result.optString("controlImageSha256") == expectedInput.sha256 &&
        nativeEffective.optString("controlImageSha256") == expectedInput.sha256
    ) { "QNN ControlNet control-image digest differs from the prepared input." }
    val expectedStrength = options.controlStrength ?: 1.0
    require(nativeFloatMatches(result.optDouble("controlStrength", Double.NaN), expectedStrength) &&
        nativeFloatMatches(
            nativeEffective.optDouble("controlStrength", Double.NaN),
            expectedStrength
        )
    ) { "QNN ControlNet did not execute the requested controlStrength." }
    require(result.optBoolean("controlStrengthApplied", false) &&
        nativeEffective.optBoolean("controlStrengthApplied", false)
    ) { "QNN ControlNet did not apply controlStrength." }

    val timetableCount = result.optInt("timetableCount", -1)
    val useCfg = result.optBoolean("useCfg", false)
    require(nativeEffective.optBoolean("useCfg", !useCfg) == useCfg) {
        "QNN ControlNet CFG evidence differs between the result and nativeEffective."
    }
    val expectedExecutions = timetableCount
    require(timetableCount > 0 &&
        result.optInt("controlNetExecutionCount", -1) == expectedExecutions &&
        nativeEffective.optInt("controlNetExecutionCount", -1) == expectedExecutions
    ) { "QNN ControlNet did not execute once per scheduler step." }
    val residualTensorCount = result.optInt("controlNetResidualTensorCount", -1)
    require(residualTensorCount > 0 &&
        nativeEffective.optInt("controlNetResidualTensorCount", -1) == residualTensorCount
    ) { "QNN ControlNet residual tensor evidence is invalid." }
    val expectedResidualWrites = Math.multiplyExact(expectedExecutions, residualTensorCount)
    require(result.optInt("controlNetResidualWriteCount", -1) == expectedResidualWrites &&
        nativeEffective.optInt("controlNetResidualWriteCount", -1) == expectedResidualWrites
    ) { "QNN ControlNet did not inject every residual on every scheduler step." }
    val expectedResidualReuseCount = if (useCfg) timetableCount else 0
    require(result.optInt("controlNetResidualUnetReuseCount", -1) == expectedResidualReuseCount &&
        nativeEffective.optInt("controlNetResidualUnetReuseCount", -1) == expectedResidualReuseCount
    ) { "QNN ControlNet residual reuse evidence does not match the CFG execution." }
    val expectedConditioningBranch = "positive"
    require(result.optString("controlNetConditioningBranch") == expectedConditioningBranch &&
        nativeEffective.optString("controlNetConditioningBranch") == expectedConditioningBranch
    ) { "QNN ControlNet did not execute on the required positive conditioning branch." }
    require(result.optBoolean("controlNetInputConsumed", false) &&
        nativeEffective.optBoolean("controlNetInputConsumed", false)
    ) { "QNN ControlNet did not report consuming the prepared control tensor." }

    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 0)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 1)
        .put("controlNetExecutionCount", expectedExecutions)
        .put("controlNetResidualTensorCount", residualTensorCount)
        .put("controlNetResidualWriteCount", expectedResidualWrites)
        .put("controlNetResidualUnetReuseCount", expectedResidualReuseCount)
        .put("controlNetConditioningBranch", expectedConditioningBranch)
}

/** Verifies the three-process SDXL encoder -> UNet -> decoder img2img chain. */
internal fun verifyAndSanitizeQnnImg2ImgProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: SdxlPreparedInputTensor,
    expectedVaeEncoderContextSha256: String
): JSONObject {
    val detailStageMask = validateQnnNativeDetailStageMask(result)
    require(options.taskMode == LocalImageTaskMode.IMG2IMG &&
        options.inputImage != null && options.maskImage == null && options.controlImage == null
    ) { "QNN SDXL img2img verification requires exactly one prepared input image." }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "QNN SDXL img2img did not report nativeEffective image input evidence."
        )
    require(result.optString("taskMode") == LocalImageTaskMode.IMG2IMG.wireName &&
        nativeEffective.optString("taskMode") == LocalImageTaskMode.IMG2IMG.wireName &&
        result.optInt("batchCount", -1) == 1 && nativeEffective.optInt("batchCount", -1) == 1
    ) { "QNN SDXL img2img task or batch evidence differs from the request." }
    fun exactCount(field: String, expected: Int) {
        require(result.optInt(field, -1) == expected &&
            nativeEffective.optInt(field, -1) == expected
        ) { "QNN SDXL img2img $field evidence differs from the request." }
    }
    exactCount("inputImageExecutionCount", 1)
    exactCount("maskImageExecutionCount", 0)
    exactCount("controlImageExecutionCount", 0)
    val expectedInput = requireNotNull(options.inputImage)
    require(File(result.getString("inputImagePath")).canonicalFile == File(expectedInput.path).canonicalFile &&
        File(nativeEffective.getString("inputImagePath")).canonicalFile == File(expectedInput.path).canonicalFile &&
        result.getString("inputImageSha256").lowercase() == expectedInput.sha256 &&
        nativeEffective.getString("inputImageSha256").lowercase() == expectedInput.sha256
    ) { "QNN SDXL source-image preprocessing provenance differs from the worker input." }
    require(!result.optBoolean("inputImageSourceReadByNative", true) &&
        result.optString("inputImageSourceValidation") == "android_preprocess_provenance"
    ) { "QNN SDXL encoder must consume the committed tensor without reopening the source image." }
    require(File(result.getString("inputImageTensorPath")).canonicalFile == File(prepared.tensorPath).canonicalFile &&
        File(nativeEffective.getString("inputImageTensorPath")).canonicalFile == File(prepared.tensorPath).canonicalFile &&
        result.getString("inputImageTensorSha256").lowercase() == prepared.tensorSha256 &&
        nativeEffective.getString("inputImageTensorSha256").lowercase() == prepared.tensorSha256
    ) { "QNN SDXL encoder did not consume the exact Android preprocessing tensor." }
    listOf("inputImagePath", "inputImageTensorPath", "maskImagePath", "controlImagePath").forEach {
        result.remove(it)
        nativeEffective.remove(it)
    }
    require(result.optString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        nativeEffective.optString("inputImagePreprocess") == SDXL_INPUT_TENSOR_PREPROCESS &&
        result.optString("inputImageTensorDtype") == SDXL_INPUT_TENSOR_DTYPE &&
        result.optString("inputImageTensorLayout") == SDXL_INPUT_TENSOR_LAYOUT &&
        result.optString("inputImageTensorRange") == SDXL_INPUT_TENSOR_RANGE &&
        result.optLong("inputImageTensorBytes", -1L) == prepared.tensorBytes
    ) { "QNN SDXL img2img preprocessing evidence is incomplete." }
    val expectedStrength = options.strength ?: 1.0
    require(nativeFloatMatches(result.optDouble("strength", Double.NaN), expectedStrength) &&
        nativeFloatMatches(nativeEffective.optDouble("strength", Double.NaN), expectedStrength)
    ) { "QNN SDXL UNet did not execute the requested img2img strength." }
    val schedule = resolveSdxlImg2ImgSchedule(
        steps = result.getInt("steps"),
        fullTimetableCount = result.getInt("fullTimetableCount"),
        strength = expectedStrength
    )
    require(result.getInt("timetableCount") == schedule.effectiveSteps &&
        nativeEffective.getInt("timetableCount") == schedule.effectiveSteps &&
        result.getInt("effectiveDenoiseSteps") == schedule.effectiveSteps &&
        result.getInt("img2imgBeginIndex") == schedule.beginIndex &&
        nativeEffective.getInt("img2imgBeginIndex") == schedule.beginIndex
    ) { "QNN SDXL img2img did not execute the exact strength-derived tail timetable." }
    val encoderPid = result.optInt("encoderWorkerPid", -1)
    val unetPid = result.optInt("unetWorkerPid", -1)
    val vaePid = result.optInt("vaeWorkerPid", -1)
    val phaseSequences = listOf(
        result.optLong("encoderNativeGenerationSequence", -1L),
        result.optLong("unetNativeGenerationSequence", -1L),
        result.optLong("vaeNativeGenerationSequence", -1L)
    )
    val phaseProof = result.getJSONObject("sdxlPhaseProof")
    val nativePhaseProof = nativeEffective.getJSONObject("sdxlPhaseProof")
    val expectedEncoderExecutionCount = options.ultraFix?.let { request ->
        val expectedTileCount = Math.toIntExact(localQnnUltraFixTilePlanEvidence(request).tileCount)
        require(
            phaseProof.optInt("ultraFixTileCount", -1) == expectedTileCount &&
                nativePhaseProof.optInt("ultraFixTileCount", -1) == expectedTileCount &&
                phaseProof.optInt("ultraFixEncoderGraphExecutionCount", -1) == expectedTileCount &&
                nativePhaseProof.optInt("ultraFixEncoderGraphExecutionCount", -1) == expectedTileCount
        ) { "QNN SDXL UltraFix encoder evidence differs from its deterministic tile plan." }
        expectedTileCount
    } ?: 1
    require(encoderPid > 0 && unetPid > 0 && vaePid > 0 &&
        phaseSequences.all { it > 0L } && phaseSequences.distinct().size == 3 &&
        result.optBoolean("encoderProcessDeathConfirmed") &&
        result.optBoolean("unetProcessDeathConfirmed") &&
        result.optBoolean("vaeProcessDeathConfirmed") &&
        result.optInt("encoderContextLoadCount", -1) == 1 &&
        result.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount &&
        phaseProof.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount &&
        nativePhaseProof.optInt("encoderExecutionCount", -1) == expectedEncoderExecutionCount
    ) { "QNN SDXL img2img did not prove three ordered disposable graph processes." }
    val phaseDetailStageMasks = listOf("encoder", "unet", "vae").map { phase ->
        val hexField = "${phase}NativeDetailStageMaskHex"
        val legacyField = "${phase}NativeDetailStageMask"
        val outer = result.strictUInt64Hex(hexField, legacyField)
        require(phaseProof.strictUInt64Hex(hexField, legacyField) == outer) {
            "QNN SDXL $phase detail stage mask conflicts with the phase proof."
        }
        outer
    }
    require(phaseDetailStageMasks.fold(0uL, ULong::or) == detailStageMask) {
        "QNN SDXL aggregate detail stage mask differs from its three phase proofs."
    }
    val encoderLatentSha256 = result.optString("encoderLatentSha256").lowercase()
    val encoderContextSha256 = result.optString("encoderContextSha256").lowercase()
    require(encoderLatentSha256.matches(Regex("[a-f0-9]{64}")) &&
        nativeEffective.optString("encoderLatentSha256").lowercase() == encoderLatentSha256
    ) { "QNN SDXL UNet encoder-latent provenance is invalid." }
    require(expectedVaeEncoderContextSha256.matches(Regex("[a-f0-9]{64}")) &&
        encoderContextSha256 == expectedVaeEncoderContextSha256 &&
        nativeEffective.optString("encoderContextSha256").lowercase() == encoderContextSha256 &&
        result.getJSONObject("sdxlPhaseProof")
            .optString("encoderContextSha256").lowercase() == encoderContextSha256 &&
        nativeEffective.getJSONObject("sdxlPhaseProof")
            .optString("encoderContextSha256").lowercase() == encoderContextSha256
    ) { "QNN SDXL VAE encoder context provenance is invalid." }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 0)
        .put("inputImagePreprocess", SDXL_INPUT_TENSOR_PREPROCESS)
        .put("inputImageTensorSha256", prepared.tensorSha256)
        .put("encoderContextSha256", encoderContextSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
}

/**
 * Verifies one standalone encoder followed by one coherent shared-QNN UNet/VAE session. This is
 * intentionally separate from the split-SDXL verifier, whose three disposable-process proof must
 * remain intact.
 */
internal fun verifyAndSanitizeQnnUltraFixProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: Any,
    usesSplitWorkers: Boolean,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String
): JSONObject {
    require(options.ultraFix != null) {
        "QNN UltraFix input verification requires a structured UltraFix request."
    }
    return if (usesSplitWorkers) {
        verifyAndSanitizeQnnImg2ImgProductInput(
            result = result,
            options = options,
            prepared = prepared as? SdxlPreparedInputTensor
                ?: error("QNN UltraFix split input tensor has the wrong topology."),
            expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256
        )
    } else {
        verifyAndSanitizeSharedQnnImg2ImgProductInput(
            result = result,
            options = options,
            prepared = prepared as? QnnPreparedInputTensor
                ?: error("QNN UltraFix shared input tensor has the wrong topology."),
            profile = profile,
            expectedVaeEncoderContextSha256 = expectedVaeEncoderContextSha256,
            expectedVaeEncoderGraphName = expectedVaeEncoderGraphName
        )
    }
}

internal fun verifyAndSanitizeSharedQnnImg2ImgProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions,
    prepared: QnnPreparedInputTensor,
    profile: ImageExecutionProfile,
    expectedVaeEncoderContextSha256: String,
    expectedVaeEncoderGraphName: String
): JSONObject {
    require(profile.hasSharedQnnImg2ImgTopology()) {
        "Shared-QNN img2img verification requires a coherent QNN UNet/VAE topology with a VAE encoder."
    }
    val ultraFixRequest = options.ultraFix
    require(if (ultraFixRequest == null) {
        prepared.tensorWidth == 512 && prepared.tensorHeight == 512
    } else {
        prepared.tensorWidth == ultraFixRequest.targetWidth &&
            prepared.tensorHeight == ultraFixRequest.targetHeight
    }) {
        "Shared-QNN img2img verification requires the committed tensor dimensions."
    }
    require(expectedVaeEncoderContextSha256.matches(LOWERCASE_SHA256)) {
        "Resolved shared-QNN VAE encoder context SHA-256 is malformed."
    }
    require(expectedVaeEncoderGraphName.isNotBlank()) {
        "Resolved shared-QNN VAE encoder graph name is missing."
    }
    require(
        options.taskMode == LocalImageTaskMode.IMG2IMG &&
            options.inputImage != null && options.maskImage == null && options.controlImage == null
    ) { "Shared-QNN img2img verification requires exactly one prepared input image." }

    val detailStageMask = validateQnnNativeDetailStageMask(result)
    require(detailStageMask and QNN_ENCODER_GRAPH_EXECUTE_DETAIL_STAGE != 0uL) {
        "Shared-QNN img2img did not prove a real VAE encoder graphExecute call."
    }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "Shared-QNN img2img did not report nativeEffective image input evidence."
        )

    fun exactString(field: String, expected: String) {
        require(result.optString(field) == expected && nativeEffective.optString(field) == expected) {
            "Shared-QNN img2img $field evidence differs from the executed request."
        }
    }
    fun exactInt(field: String, expected: Int) {
        require(result.optInt(field, Int.MIN_VALUE) == expected &&
            nativeEffective.optInt(field, Int.MIN_VALUE) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactLong(field: String, expected: Long) {
        require(result.optLong(field, Long.MIN_VALUE) == expected &&
            nativeEffective.optLong(field, Long.MIN_VALUE) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactBoolean(field: String, expected: Boolean) {
        require(result.has(field) && nativeEffective.has(field) &&
            result.optBoolean(field, !expected) == expected &&
            nativeEffective.optBoolean(field, !expected) == expected
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactDouble(field: String, expected: Double) {
        require(nativeFloatMatches(result.optDouble(field, Double.NaN), expected) &&
            nativeFloatMatches(nativeEffective.optDouble(field, Double.NaN), expected)
        ) { "Shared-QNN img2img $field evidence differs from the executed request." }
    }
    fun exactSha256(field: String, expected: String? = null): String {
        val outer = result.optString(field)
        val inner = nativeEffective.optString(field)
        require(outer.matches(LOWERCASE_SHA256) && inner == outer &&
            (expected == null || outer == expected)
        ) { "Shared-QNN img2img $field evidence is missing, malformed, or inconsistent." }
        return outer
    }
    fun exactShape(field: String, expected: List<Int>) {
        fun JSONObject.shapeOrNull(): List<Int>? {
            val shape = optJSONArray(field) ?: return null
            if (shape.length() != expected.size) return null
            return (0 until shape.length()).map { index -> shape.optInt(index, Int.MIN_VALUE) }
        }
        require(result.shapeOrNull() == expected && nativeEffective.shapeOrNull() == expected) {
            "Shared-QNN img2img $field differs from the physical encoder tensor contract."
        }
    }

    exactString("taskMode", LocalImageTaskMode.IMG2IMG.wireName)
    exactInt("batchCount", 1)
    exactInt("inputImageExecutionCount", 1)
    exactInt("maskImageExecutionCount", 0)
    exactInt("controlImageExecutionCount", 0)
    listOf("maskImagePath", "controlImagePath").forEach { field ->
        require(result.has(field) && nativeEffective.has(field) &&
            result.optString(field).isBlank() && nativeEffective.optString(field).isBlank()
        ) { "Shared-QNN img2img reported an unexpected private $field slot." }
    }

    val expectedInput = requireNotNull(options.inputImage)
    val ultraFixPlan = ultraFixRequest?.let(::localQnnUltraFixTilePlanEvidence)
    val expectedInputTensorShape = ultraFixRequest?.let { request ->
        listOf(1, 3, request.targetHeight, request.targetWidth)
    } ?: SHARED_QNN_ENCODER_INPUT_SHAPE
    val expectedEncoderGraphInputShape = SHARED_QNN_ENCODER_INPUT_SHAPE
    val expectedEncoderGraphOutputShape = SHARED_QNN_ENCODER_OUTPUT_SHAPE
    val expectedEncoderExecutionCount = ultraFixPlan?.tileCount ?: 1L
    val expectedPosteriorSampleCount = ultraFixRequest?.let { request ->
        Math.multiplyExact(
            Math.multiplyExact(request.targetWidth.toLong() / 8L, request.targetHeight.toLong() / 8L),
            4L
        )
    } ?: SHARED_QNN_ENCODER_POSTERIOR_SAMPLE_COUNT
    val expectedEncoderRuntimeMode = if (ultraFixRequest != null) {
        "standalone_tiled_encoder_then_shared_tiled_unet_vae"
    } else {
        SHARED_QNN_ENCODER_RUNTIME_MODE
    }
    val expectedSource = File(expectedInput.path).canonicalFile
    require(
        File(result.optString("inputImagePath")).canonicalFile == expectedSource &&
            File(nativeEffective.optString("inputImagePath")).canonicalFile == expectedSource
    ) { "Shared-QNN source-image preprocessing provenance differs from the worker input." }
    exactSha256("inputImageSha256", expectedInput.sha256)
    exactBoolean("inputImageSourceReadByNative", false)
    exactString("inputImageSourceValidation", "android_preprocess_provenance")

    val expectedTensor = File(prepared.tensorPath).canonicalFile
    require(
        File(result.optString("inputImageTensorPath")).canonicalFile == expectedTensor &&
            File(nativeEffective.optString("inputImageTensorPath")).canonicalFile == expectedTensor
    ) { "Shared-QNN encoder did not consume the exact committed Android preprocessing tensor." }
    exactSha256("inputImageTensorSha256", prepared.tensorSha256)
    exactString("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
    exactInt("inputImageSourceWidth", prepared.sourceWidth)
    exactInt("inputImageSourceHeight", prepared.sourceHeight)
    exactInt("inputImageOrientedWidth", prepared.orientedWidth)
    exactInt("inputImageOrientedHeight", prepared.orientedHeight)
    exactInt("inputImageExifOrientation", prepared.exifOrientation)
    exactInt("inputImageTensorWidth", prepared.tensorWidth)
    exactInt("inputImageTensorHeight", prepared.tensorHeight)
    exactInt("inputImageTensorChannels", 3)
    exactLong("inputImageTensorBytes", prepared.tensorBytes)
    exactShape("inputImageTensorShape", expectedInputTensorShape)
    exactString("inputImageTensorDtype", QNN_INPUT_TENSOR_DTYPE)
    exactString("inputImageTensorLayout", QNN_INPUT_TENSOR_LAYOUT)
    exactString("inputImageTensorRange", QNN_INPUT_TENSOR_RANGE)

    exactSha256("encoderContextSha256", expectedVaeEncoderContextSha256)
    exactInt("encoderContextLoadCount", 1)
    exactInt("encoderExecutionCount", expectedEncoderExecutionCount.toInt())
    exactString("encoderGraphName", expectedVaeEncoderGraphName)
    exactString("encoderInputName", "input")
    exactString("encoderMeanOutputName", "mean")
    exactString("encoderStdOutputName", "std")
    exactString("encoderInputDtype", "uint16")
    exactString("encoderMeanDtype", "uint16")
    exactString("encoderStdDtype", "uint16")
    exactShape("encoderInputShape", expectedEncoderGraphInputShape)
    exactShape("encoderMeanShape", expectedEncoderGraphOutputShape)
    exactShape("encoderStdShape", expectedEncoderGraphOutputShape)
    val encoderInputBufferSha256 = exactSha256("encoderInputBufferSha256")
    val encoderMeanBufferSha256 = exactSha256("encoderMeanBufferSha256")
    val encoderStdBufferSha256 = exactSha256("encoderStdBufferSha256")
    val encoderLatentSha256 = exactSha256("encoderLatentSha256")
    exactString("posteriorSampling", "mean_plus_std_times_normal_mt19937_domain_v1")
    exactLong("posteriorSampleCount", expectedPosteriorSampleCount)
    exactDouble("encoderLatentScalingFactor", profile.vae.scalingFactor)
    exactBoolean("encoderContextReleasedBeforeSharedSession", true)
    exactString("encoderRuntimeMode", expectedEncoderRuntimeMode)
    exactString(
        "runtimeSessionMode",
        if (profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE) {
            "shared_text_unet_vae"
        } else {
            "shared_unet_vae"
        }
    )

    val steps = result.optInt("steps", -1)
    require(steps > 0 && nativeEffective.optInt("steps", -1) == steps) {
        "Shared-QNN img2img did not report the full scheduler step count."
    }
    val expectedStrength = options.strength ?: 1.0
    exactDouble("strength", expectedStrength)
    val fullTimetableCount = result.optInt("fullTimetableCount", -1)
    require(fullTimetableCount == steps &&
        nativeEffective.optInt("fullTimetableCount", -1) == fullTimetableCount
    ) { "Shared-QNN img2img full timetable evidence is invalid." }
    val schedule = resolveQnnImg2ImgSchedule(
        steps = steps,
        fullTimetableCount = fullTimetableCount,
        strength = expectedStrength
    )
    exactInt("timetableCount", schedule.effectiveSteps)
    exactInt("effectiveDenoiseSteps", schedule.effectiveSteps)
    exactInt("img2imgBeginIndex", schedule.beginIndex)
    val useCfg = result.optBoolean("useCfg", false)
    require(nativeEffective.has("useCfg") && nativeEffective.optBoolean("useCfg", !useCfg) == useCfg) {
        "Shared-QNN img2img CFG evidence is inconsistent."
    }
    exactInt(
        "unetExecutionCount",
        Math.multiplyExact(schedule.effectiveSteps, if (useCfg) 2 else 1)
    )

    val tailTimesteps = result.optJSONArray("timesteps")
    require(tailTimesteps != null && tailTimesteps.length() == schedule.effectiveSteps) {
        "Shared-QNN img2img did not report the exact executed scheduler tail."
    }
    val addNoiseTimestep = tailTimesteps.optDouble(0, Double.NaN)
    require(addNoiseTimestep.isFinite()) {
        "Shared-QNN img2img first executed timestep is missing or non-finite."
    }
    val ultraFix = options.ultraFix != null
    if (ultraFix) {
        exactBoolean("img2imgAddNoiseApplied", false)
        exactInt("img2imgAddNoiseBeginIndex", schedule.beginIndex)
        exactDouble("img2imgAddNoiseTimestep", 0.0)
    } else {
        exactBoolean("img2imgAddNoiseApplied", true)
        exactInt("img2imgAddNoiseBeginIndex", schedule.beginIndex)
        exactDouble("img2imgAddNoiseTimestep", addNoiseTimestep)
    }
    val noiseChecksum = result.optString("img2imgNoiseChecksum")
    if (ultraFix) {
        require(noiseChecksum == "0000000000000000" &&
            nativeEffective.optString("img2imgNoiseChecksum") == noiseChecksum
        ) { "Shared-QNN UltraFix did not prove addNoise=false." }
    } else {
        require(noiseChecksum.matches(LOWERCASE_UINT64_HEX) &&
            noiseChecksum != "0000000000000000" &&
            nativeEffective.optString("img2imgNoiseChecksum") == noiseChecksum
        ) {
            "Shared-QNN img2img did not prove non-empty scheduler add-noise output."
        }
    }

    listOf("inputImagePath", "inputImageTensorPath", "maskImagePath", "controlImagePath").forEach {
        result.remove(it)
        nativeEffective.remove(it)
    }
    return options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", 1)
        .put("maskImageExecutionCount", 0)
        .put("controlImageExecutionCount", 0)
        .put("inputImagePreprocess", QNN_INPUT_TENSOR_PREPROCESS)
        .put("inputImageTensorSha256", prepared.tensorSha256)
        .put("encoderContextSha256", expectedVaeEncoderContextSha256)
        .put("encoderGraphName", expectedVaeEncoderGraphName)
        .put("encoderInputBufferSha256", encoderInputBufferSha256)
        .put("encoderMeanBufferSha256", encoderMeanBufferSha256)
        .put("encoderStdBufferSha256", encoderStdBufferSha256)
        .put("encoderLatentSha256", encoderLatentSha256)
        .put("encoderRuntimeMode", expectedEncoderRuntimeMode)
        .put("fullTimetableCount", schedule.fullTimetableCount)
        .put("effectiveDenoiseSteps", schedule.effectiveSteps)
        .put("img2imgBeginIndex", schedule.beginIndex)
        .put("img2imgAddNoiseTimestep", if (ultraFix) 0.0 else addNoiseTimestep)
        .put("img2imgNoiseChecksum", noiseChecksum)
}

/**
 * QNN text-to-image reports three empty worker path slots as native evidence. Verify the slots
 * before removing them so a Local API response exposes neither private paths nor private path
 * field names. Missing or non-empty slots remain a native contract failure.
 */
internal fun verifyAndSanitizeQnnTextToImagePrivatePaths(
    result: JSONObject,
    options: LocalImageGenerationOptions
) {
    validateQnnNativeDetailStageMask(result)
    require(
        options.taskMode == LocalImageTaskMode.TEXT_TO_IMAGE &&
            options.inputImage == null && options.maskImage == null && options.controlImage == null
    ) { "QNN text-to-image path sanitization requires a request without image inputs." }
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: rejectProductInput(
            "invalid_native_input_evidence",
            "QNN text-to-image did not report nativeEffective input evidence."
        )
    require(
        result.optString("taskMode") == LocalImageTaskMode.TEXT_TO_IMAGE.wireName &&
            nativeEffective.optString("taskMode") == LocalImageTaskMode.TEXT_TO_IMAGE.wireName
    ) { "QNN text-to-image taskMode evidence differs from the request." }
    require(
        result.optInt("batchCount", -1) == options.batchCount &&
            nativeEffective.optInt("batchCount", -1) == options.batchCount
    ) { "QNN text-to-image batchCount evidence differs from the request." }
    listOf("inputImage", "maskImage", "controlImage").forEach { role ->
        val countField = "${role}ExecutionCount"
        val pathField = "${role}Path"
        require(
            result.optInt(countField, -1) == 0 &&
                nativeEffective.optInt(countField, -1) == 0
        ) { "QNN text-to-image reported an unexpected $countField." }
        require(
            result.has(pathField) && !result.isNull(pathField) &&
                nativeEffective.has(pathField) && !nativeEffective.isNull(pathField) &&
                result.getString(pathField).isBlank() &&
                nativeEffective.getString(pathField).isBlank()
        ) { "QNN text-to-image reported invalid $pathField evidence." }
        result.remove(pathField)
        nativeEffective.remove(pathField)
    }
}

private fun rejectProductInput(code: String, message: String): Nothing =
    throw LocalImageProductContractException(code, message)

private fun validateImageInputShape(
    taskMode: LocalImageTaskMode,
    inputPresent: Boolean,
    maskPresent: Boolean,
    controlPresent: Boolean,
    strength: Double?,
    controlStrength: Double?,
    allowMissingRequiredInputs: Boolean = false
) {
    strength?.let { value ->
        require(value.isFinite() && value >= 0.0 && value <= 1.0) {
            "strength must be finite and in the interval [0, 1]."
        }
    }
    controlStrength?.let { value ->
        require(value.isFinite() && value >= 0.0 && value <= 2.0) {
            "controlStrength must be finite and in the interval [0, 2]."
        }
    }
    when (taskMode) {
        LocalImageTaskMode.TEXT_TO_IMAGE -> {
            require(!inputPresent && !maskPresent && !controlPresent) {
                "text_to_image does not accept input, mask, or control images."
            }
            require(strength == null && controlStrength == null) {
                "text_to_image does not accept strength controls."
            }
        }
        LocalImageTaskMode.IMG2IMG -> {
            require(inputPresent || allowMissingRequiredInputs) { "img2img requires inputImage." }
            require(!maskPresent && !controlPresent) { "img2img accepts only inputImage." }
            require(controlStrength == null) { "img2img does not accept controlStrength." }
        }
        LocalImageTaskMode.INPAINT -> {
            require(inputPresent && maskPresent || allowMissingRequiredInputs) {
                "inpaint requires inputImage and maskImage."
            }
            require(!controlPresent) { "inpaint does not accept controlImage." }
            require(controlStrength == null) { "inpaint does not accept controlStrength." }
        }
        LocalImageTaskMode.CONTROL -> {
            require(controlPresent || allowMissingRequiredInputs) { "control requires controlImage." }
            require(!inputPresent && !maskPresent) { "control accepts only controlImage." }
            require(strength == null) { "control does not accept img2img strength." }
        }
        LocalImageTaskMode.EDIT -> {
            require(inputPresent || allowMissingRequiredInputs) { "edit requires inputImage." }
            require(!maskPresent && !controlPresent) { "edit accepts only inputImage." }
            require(controlStrength == null) { "edit does not accept controlStrength." }
        }
    }
}

private fun JSONObject.requiredString(name: String): String {
    require(has(name) && !isNull(name)) { "$name is required." }
    val raw = get(name)
    require(raw is String) { "$name must be a string." }
    return raw.trim().also { require(it.isNotEmpty()) { "$name must not be blank." } }
}

private fun JSONObject.requiredExactInt(name: String): Int {
    val value = requiredExactLong(name)
    require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        "$name must fit a 32-bit integer."
    }
    return value.toInt()
}

private fun JSONObject.requiredExactLong(name: String): Long {
    require(has(name) && !isNull(name)) { "$name is required." }
    val raw = get(name)
    require(raw is Number) { "$name must be an integer." }
    val doubleValue = raw.toDouble()
    require(doubleValue.isFinite() && doubleValue % 1.0 == 0.0) { "$name must be an integer." }
    val longValue = raw.toLong()
    require(longValue.toDouble() == doubleValue) { "$name must be an exact integer." }
    return longValue
}

private fun JSONObject.requiredFiniteDouble(name: String): Double {
    require(has(name) && !isNull(name)) { "$name is required." }
    val raw = get(name)
    require(raw is Number) { "$name must be numeric." }
    return raw.toDouble().also { value -> require(value.isFinite()) { "$name must be finite." } }
}
