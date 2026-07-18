package com.muyuchat.mca

import java.io.File
import org.json.JSONObject

private const val NATIVE_FLOAT_RELATIVE_TOLERANCE = 1e-6

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

data class LocalImageVaeTilingOptions(
    val tileSize: Int,
    val overlap: Double
) {
    init {
        require(tileSize in MIN_TILE_SIZE..MAX_TILE_SIZE && tileSize % 8 == 0) {
            "vaeTiling.tileSize must be an 8-pixel multiple between $MIN_TILE_SIZE and $MAX_TILE_SIZE."
        }
        require(overlap.isFinite() && overlap >= 0.0 && overlap < 1.0) {
            "vaeTiling.overlap must be a finite ratio in [0, 1)."
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
    val width: Int,
    val height: Int
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
    }

    fun toJson(includePath: Boolean = true): JSONObject = JSONObject()
        .apply { if (includePath) put("path", path) }
        .put("mimeType", mimeType)
        .put("sha256", sha256)
        .put("sizeBytes", sizeBytes)
        .put("width", width)
        .put("height", height)

    companion object {
        const val MAX_INPUT_BYTES: Long = 32L * 1024L * 1024L
        const val MAX_IMAGE_SIDE: Int = 8_192
        const val MAX_IMAGE_PIXELS: Long = 64L * 1024L * 1024L
        private val SHA256_PATTERN = Regex("[a-f0-9]{64}")

        fun fromJson(json: JSONObject): LocalImagePreparedInput = LocalImagePreparedInput(
            path = json.requiredString("path"),
            mimeType = json.requiredString("mimeType"),
            sha256 = json.requiredString("sha256").lowercase(),
            sizeBytes = json.requiredExactLong("sizeBytes"),
            width = json.requiredExactInt("width"),
            height = json.requiredExactInt("height")
        )
    }
}

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
    clipSkip?.let { value ->
        require(value in -1..32) { "clipSkip must be -1 (model default) or between 0 and 32." }
    }
    if (maskImage != null && inputImage != null) {
        require(maskImage.width == inputImage.width && maskImage.height == inputImage.height) {
            "maskImage dimensions must exactly match inputImage dimensions."
        }
    }
    if (requirePreparedPaths) {
        listOfNotNull(inputImage, maskImage, controlImage).forEach { input ->
            require(input.path.isNotBlank()) { "Prepared image input path must not be blank." }
        }
    }
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
    vaeTiling?.let { target.put("vaeTiling", it.toJson()) }
    preview?.let { target.put("preview", it.toJson()) }
    return target
}

internal fun validateLocalImageRuntimeProductOptions(
    runtime: LocalImageRuntime,
    options: LocalImageGenerationOptions
) {
    options.validateProductInputContract()
    if (options.batchCount != 1 && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        rejectProductInput(
            "unsupported_batch_count",
            "Runtime ${runtime.name} currently supports one output per request; native execution was not started."
        )
    }
    if (options.preview != null && runtime != LocalImageRuntime.STABLE_DIFFUSION_CPP) {
        rejectProductInput(
            "unsupported_preview",
            "Runtime ${runtime.name} does not expose a native preview callback."
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
        if (options.clipSkip != null || options.vaeTiling != null) {
            rejectProductInput(
                "unsupported_edit_option",
                "This MNN edit execution path does not consume clipSkip or vaeTiling; native execution was not started."
            )
        }
    }
    // Runtime/profile capability checks belong to the resolved execution path. This generic
    // boundary only enforces product-wide lifecycle limits; it must not pre-emptively block an
    // edit or control request merely because a runtime is new or was not previously validated.
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

    val nativeTiling = nativeEffective.optJSONObject("vaeTiling")
        ?: error("stable-diffusion.cpp did not report vaeTiling evidence.")
    val outerTiling = result.optJSONObject("vaeTiling")
        ?: error("stable-diffusion.cpp did not report outer vaeTiling evidence.")
    val expectedTiling = options.vaeTiling
    val expectedEnabled = expectedTiling != null
    require(nativeTiling.optBoolean("enabled") == expectedEnabled &&
        outerTiling.optBoolean("enabled") == expectedEnabled
    ) { "stable-diffusion.cpp VAE tiling enablement did not match the request." }
    if (expectedTiling != null) {
        listOf(nativeTiling, outerTiling).forEach { tiling ->
            require(tiling.optInt("tileSize", -1) == expectedTiling.tileSize) {
                "stable-diffusion.cpp VAE tileSize did not match the request."
            }
            val overlap = tiling.optDouble("overlap", Double.NaN)
            require(nativeFloatMatches(overlap, expectedTiling.overlap)) {
                "stable-diffusion.cpp VAE overlap did not match the request."
            }
        }
    }

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

    val audit = options.inputAuditJson()
        .put("nativeExecution", true)
        .put("inputImageExecutionCount", if (options.inputImage == null) 0 else 1)
        .put("maskImageExecutionCount", if (options.maskImage == null) 0 else 1)
        .put("controlImageExecutionCount", if (options.controlImage == null) 0 else 1)
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
    for (index in 0 until outputs.length()) {
        val output = outputs.optJSONObject(index)
            ?: error("MNN-Diffusion output item must be an object.")
        require(output.optInt("index", -1) == index) {
            "MNN-Diffusion output indices are not contiguous."
        }
        require(output.optString("path").isNotBlank()) {
            "MNN-Diffusion output item is missing its concrete path."
        }
        require(output.optString("mimeType").startsWith("image/")) {
            "MNN-Diffusion output item has an invalid MIME type."
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
internal fun verifyAndSanitizeQnnProductInput(
    result: JSONObject,
    options: LocalImageGenerationOptions
): JSONObject {
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

/**
 * QNN text-to-image reports three empty worker path slots as native evidence. Verify the slots
 * before removing them so a Local API response exposes neither private paths nor private path
 * field names. Missing or non-empty slots remain a native contract failure.
 */
internal fun verifyAndSanitizeQnnTextToImagePrivatePaths(
    result: JSONObject,
    options: LocalImageGenerationOptions
) {
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
    controlStrength: Double?
) {
    strength?.let { value ->
        require(value.isFinite() && value > 0.0 && value <= 1.0) {
            "strength must be finite and in the interval (0, 1]."
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
            require(inputPresent) { "img2img requires inputImage." }
            require(!maskPresent && !controlPresent) { "img2img accepts only inputImage." }
            require(controlStrength == null) { "img2img does not accept controlStrength." }
        }
        LocalImageTaskMode.INPAINT -> {
            require(inputPresent && maskPresent) { "inpaint requires inputImage and maskImage." }
            require(!controlPresent) { "inpaint does not accept controlImage." }
            require(controlStrength == null) { "inpaint does not accept controlStrength." }
        }
        LocalImageTaskMode.CONTROL -> {
            require(controlPresent) { "control requires controlImage." }
            require(!inputPresent && !maskPresent) { "control accepts only controlImage." }
            require(strength == null) { "control does not accept img2img strength." }
        }
        LocalImageTaskMode.EDIT -> {
            require(inputPresent) { "edit requires inputImage." }
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
