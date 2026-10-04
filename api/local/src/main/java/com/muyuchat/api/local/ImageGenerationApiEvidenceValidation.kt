package com.muyuchat.api.local

import com.muyuchat.core.engine.compareUtf8Unsigned
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Request/response evidence validation for the image-generation contract.
 *
 * Moved verbatim out of [ImageGenerationApiContract], which had grown past 3.9k lines. This
 * file owns one responsibility: deciding whether a parsed image-generation request or a native
 * provider response is acceptable, and which error code and message a rejection must carry.
 * Field names, limits, check order, error codes and messages are unchanged -- this is the
 * outward HTTP/SSE contract, so any edit here is a protocol change.
 *
 * The prefixed top-level helpers below are the JSON field accessors and numeric comparisons
 * shared with [ImageGenerationApiContract]; their bodies are unchanged and they only gained a
 * responsibility prefix, because a new top-level name in this package must not collide.
 */
internal fun JSONObject.imageGenerationApiOptionalString(name: String, allowEmpty: Boolean): String? {
    if (!has(name)) return null
    if (isNull(name)) reject("invalid_$name", "$name must be a string when specified.")
    val raw = get(name)
    if (raw !is String) reject("invalid_$name", "$name must be a string when specified.")
    val value = raw.trim()
    if (!allowEmpty && value.isEmpty()) {
        reject("invalid_$name", "$name must not be empty when specified.")
    }
    return value
}

internal fun JSONObject.imageGenerationApiRequiredNonBlankString(name: String): String =
    imageGenerationApiOptionalString(name, allowEmpty = false)
        ?: reject(
            "invalid_image_execution_evidence",
            "Image execution field $name is required and must be a non-empty string."
        )

internal fun JSONObject.imageGenerationApiRejectUnknownFields(allowed: Set<String>, objectName: String) {
    val unknown = keys().asSequence().filterNot(allowed::contains).toList().sorted()
    if (unknown.isNotEmpty()) {
        reject(
            "unknown_image_request_field",
            "$objectName contains unsupported field(s): ${unknown.joinToString(", ")}"
        )
    }
}

internal fun JSONObject.imageGenerationApiRequiredBoolean(name: String): Boolean {
    if (!has(name) || isNull(name) || get(name) !is Boolean) {
        reject(
            "invalid_image_execution_evidence",
            "Image execution field $name is required and must be a boolean."
        )
    }
    return getBoolean(name)
}

internal fun JSONObject.imageGenerationApiRequiredInteger(name: String, minimum: Long): Long {
    if (!has(name) || isNull(name)) {
        reject("invalid_image_execution_evidence", "Image execution field $name is required.")
    }
    val raw = get(name)
    if (raw !is Number) {
        reject("invalid_image_execution_evidence", "Image execution field $name must be an integer.")
    }
    val longValue = raw.imageGenerationApiToExactLongOrNull()
    if (longValue == null || longValue < minimum) {
        reject(
            "invalid_image_execution_evidence",
            "Image execution field $name must be an integer no smaller than $minimum."
        )
    }
    return longValue
}

internal fun Number.imageGenerationApiToExactLongOrNull(): Long? = runCatching {
    BigDecimal(toString()).toBigIntegerExact().longValueExact()
}.getOrNull()

internal fun JSONObject.imageGenerationApiRequiredPositiveLong(name: String): Long =
    imageGenerationApiRequiredInteger(name, minimum = 1L)

internal fun JSONObject.imageGenerationApiRequiredFiniteNumber(name: String): Double {
    if (!has(name) || isNull(name) || get(name) !is Number) {
        reject("invalid_image_execution_evidence", "Image execution field $name must be numeric.")
    }
    val value = getDouble(name)
    if (!value.isFinite()) {
        reject("invalid_image_execution_evidence", "Image execution field $name must be finite.")
    }
    return value
}

internal fun JSONObject.imageGenerationApiRequiredPositiveNumber(name: String): Double =
    imageGenerationApiRequiredFiniteNumber(name).also { value ->
        if (value <= 0.0) {
            reject("invalid_image_execution_evidence", "Image execution field $name must be positive.")
        }
    }

internal fun imageGenerationApiNumbersMatch(first: Double, second: Double): Boolean {
    if (!first.isFinite() || !second.isFinite()) return false
    val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
    // Native bridges commonly round request doubles through C float fields.
    return kotlin.math.abs(first - second) <= 1e-6 * scale
}

internal fun imageGenerationApiCanonicalSampler(raw: String): String {
    val normalized = raw.trim().lowercase()
        .replace(" ", "")
        .replace("-", "_")
    return when (normalized) {
        "euler" -> "euler"
        "euler_a", "euler_ancestral", "euler_ancestral_discrete" -> "euler_a"
        "ddim" -> "ddim"
        "pndm", "plms", "pndm_plms" -> "pndm"
        "dpm++2m", "dpmpp2m", "dpmpp_2m", "dpm_plus_plus_2m" -> "dpmpp_2m"
        "lcm" -> "lcm"
        "flow", "flowmatch", "flow_match" -> "flow_match"
        else -> normalized
    }
}

internal object ImageGenerationApiEvidenceValidation {

    private val DATA_IMAGE_PATTERN = Regex(
        "^data:image/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/=\\r\\n]+)$",
        RegexOption.IGNORE_CASE
    )

    private val TEXTUAL_INVERSION_TRIGGER_PATTERN = Regex("[A-Za-z0-9_:#<>|.-]{1,64}")

    private val TEXTUAL_INVERSION_FORMATS = setOf(
        "safetensors",
        "pytorch",
        "checkpoint",
        "binary"
    )

    private val ULTRAFIX_STAGE_EVIDENCE_FIELDS = setOf(
        "invocationCount", "successCount", "tileInvocationCount", "tileSuccessCount", "stepCount"
    )

    private val ULTRAFIX_EXECUTION_EVIDENCE_FIELDS = setOf(
        "version",
        "generationCompleted",
        "cancelled",
        "previewPublished",
        "sourceWidth",
        "sourceHeight",
        "targetWidth",
        "targetHeight",
        "sourceFit",
        "sourceResizedWidth",
        "sourceResizedHeight",
        "sourceCropLeft",
        "sourceCropTop",
        "tileSize",
        "overlap",
        "inversionSteps",
        "refinementSteps",
        "denoiseStepCount",
        "sampleMethod",
        "nativeScheduler",
        "vaeEncode",
        "ddimInversion",
        "tiledUnetRefinement",
        "tiledVaeDecode",
        "physicalDiffusionModelComputeCount",
        "qualityStepEvaluationCount",
        "noiseInjectionStepCount",
        "noiseInjectionSeedFingerprint",
        "noiseInjectionChecksum",
        "structureGuidanceStepCount",
        "structureGuidanceChecksum",
        "trajectoryNoiseChecksum"
    )

    private val RESPONSE_OUTPUT_EVIDENCE_FIELDS = setOf(
        "index", "mimeType", "sizeBytes", "sha256"
    )

    private val RESPONSE_IMAGE_DATA_FIELDS = setOf(
        "index", "b64_json", "mime_type", "width", "height", "seed"
    )

    private const val MAX_TEXTUAL_INVERSION_BYTES = 100L * 1024L * 1024L

    private const val MAX_ACTIVE_TEXTUAL_INVERSION_BYTES = 256L * 1024L * 1024L

    private const val MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_COUNT = 64

    private const val MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_BYTES =
        64L * 1024L * 1024L * 1024L

    private const val MAX_EXECUTION_ASSET_LABEL_CHARS = 4_096

    private const val MAX_IMAGE_INPUT_BYTES = 32L * 1024L * 1024L

    private const val MAX_IMAGE_OUTPUT_PNG_BYTES = 256L * 1024L * 1024L

    private const val MAX_IMAGE_REFERENCE_CHARS = 48 * 1024 * 1024

    private const val ULTRAFIX_BASE_EXECUTION_EVIDENCE_VERSION = 5L

    private const val ULTRAFIX_EXTENDED_EXECUTION_EVIDENCE_VERSION = 2L

    private const val QNN_ULTRAFIX_EXECUTION_EVIDENCE_VERSION = 2L

    private const val MIN_ULTRAFIX_OUTPUT_PNG_BYTES = 57L

    private const val MAX_ULTRAFIX_OUTPUT_PNG_BYTES = 256L * 1024L * 1024L

    private val UINT64_HEX_PATTERN = Regex("[a-f0-9]{16}")

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )

    private val PRIVATE_DIRECTORY_KEYS = setOf(
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

    private val NATIVE_STRING_FIELDS = listOf(
        "profileId",
        "modelFingerprint",
        "runtime",
        "scheduler",
        "predictionType",
        "tokenizerBackend",
        "embeddingDiskDataType",
        "vaeScalingLocation",
        "graphName"
    )

    private val NATIVE_POSITIVE_INTEGER_FIELDS = listOf(
        "profileRevision",
        "steps",
        "timetableCount",
        "unetExecutionCount",
        "tokenCount",
        "width",
        "height"
    )

    private const val QWEN_IMAGE_21_EXECUTION_SCHEMA = "qwen_image_21_mnn_v1"

    private const val QWEN_IMAGE_21_PROFILE_ID = "mnn.qwen-image-2.1.opencl"

    private const val QWEN_IMAGE_21_RUNTIME = "MNN_DIFFUSION"

    private const val QWEN_IMAGE_21_BACKEND_PROOF = "mnn_runtime_resolution_after_native_run"

    private const val QWEN_IMAGE_21_BACKEND_SCOPE = "primary_runtime"

    private const val QWEN_IMAGE_21_WIDTH = 512

    private const val QWEN_IMAGE_21_HEIGHT = 512

    private const val QWEN_IMAGE_21_MIN_STEPS = 2L

    private const val QWEN_IMAGE_21_MAX_STEPS = 40L

    private val QWEN_IMAGE_21_BACKENDS = setOf("MNN_OPENCL", "MNN_CPU", "MNN_QNN")

    internal fun validateImageReference(reference: String) {
        if (reference.length > MAX_IMAGE_REFERENCE_CHARS) {
            reject("image_input_too_large", "Image input reference exceeds the request limit.")
        }
        if (reference.startsWith("data:", ignoreCase = true)) {
            val match = DATA_IMAGE_PATTERN.matchEntire(reference)
                ?: reject("invalid_image_input", "Image data URLs must use data:image/*;base64,...")
            val estimatedBytes = match.groupValues[1].length.toLong() * 3L / 4L
            if (estimatedBytes > MAX_IMAGE_INPUT_BYTES + 3L) {
                reject("image_input_too_large", "Decoded image input exceeds 32 MiB.")
            }
            return
        }
        val supported = reference.startsWith("content://", ignoreCase = true) ||
            reference.startsWith("file://", ignoreCase = true) ||
            reference.startsWith("/")
        if (!supported) {
            reject(
                "unsupported_image_input_reference",
                "Image inputs must use data:image, content:, file:, or an absolute readable path."
            )
        }
    }

    internal fun validateInputShape(
        mode: ImageGenerationApiTaskMode,
        inputImage: String?,
        maskImage: String?,
        controlImage: String?,
        strength: Double?,
        controlStrength: Double?
    ) {
        fun rejectShape(message: String): Nothing = reject("invalid_image_input_contract", message)
        when (mode) {
            ImageGenerationApiTaskMode.TEXT_TO_IMAGE -> {
                if (inputImage != null || maskImage != null || controlImage != null ||
                    strength != null || controlStrength != null
                ) rejectShape("text_to_image does not accept image inputs or strength controls.")
            }
            ImageGenerationApiTaskMode.IMG2IMG -> {
                if (inputImage == null || maskImage != null || controlImage != null || controlStrength != null) {
                    rejectShape("img2img requires only input_image and optional strength.")
                }
            }
            ImageGenerationApiTaskMode.INPAINT -> {
                if (inputImage == null || maskImage == null || controlImage != null || controlStrength != null) {
                    rejectShape("inpaint requires input_image and mask_image.")
                }
            }
            ImageGenerationApiTaskMode.CONTROL -> {
                if (controlImage == null || inputImage != null || maskImage != null || strength != null) {
                    rejectShape("control requires only control_image and optional control_strength.")
                }
            }
            ImageGenerationApiTaskMode.EDIT -> {
                if (inputImage == null || maskImage != null || controlImage != null || controlStrength != null) {
                    rejectShape("edit requires only input_image and optional strength.")
                }
            }
        }
    }

    internal fun validateExecutionEvidence(
        execution: JSONObject,
        promptProcessing: ImageGenerationApiPromptProcessing?
    ) {
        rejectPrivateInputPaths(execution)
        if (isQwenImage21Execution(execution)) {
            validateQwenImage21ExecutionEvidence(execution, promptProcessing)
            return
        }
        if (!execution.imageGenerationApiRequiredBoolean("nativeExecution")) {
            reject("invalid_image_execution_evidence", "Image execution must prove nativeExecution=true.")
        }
        if (execution.imageGenerationApiRequiredBoolean("fallback")) {
            reject("invalid_image_execution_evidence", "Image execution must prove fallback=false.")
        }
        execution.imageGenerationApiRequiredPositiveLong("nativeGenerationSequence")
        val nativeEffective = execution.optJSONObject("nativeEffective")
            ?: reject(
                "invalid_image_execution_evidence",
                "Image execution is missing strict nativeEffective evidence."
            )
        validateNativePromptExecutionEvidence(execution, nativeEffective, promptProcessing)
        NATIVE_STRING_FIELDS.forEach { field ->
            val nativeValue = nativeEffective.imageGenerationApiRequiredNonBlankString(field)
            if (execution.imageGenerationApiRequiredNonBlankString(field) != nativeValue) {
                reject(
                    "invalid_image_execution_evidence",
                    "Outer and nativeEffective $field evidence do not match."
                )
            }
        }
        NATIVE_POSITIVE_INTEGER_FIELDS.forEach { field ->
            val nativeValue = nativeEffective.imageGenerationApiRequiredPositiveLong(field)
            if (execution.imageGenerationApiRequiredPositiveLong(field) != nativeValue) {
                reject(
                    "invalid_image_execution_evidence",
                    "Outer and nativeEffective $field evidence do not match."
                )
            }
        }
        val nativeSeed = nativeEffective.imageGenerationApiRequiredInteger("seed", minimum = 0L)
        if (execution.imageGenerationApiRequiredInteger("seed", minimum = 0L) != nativeSeed) {
            reject("invalid_image_execution_evidence", "Outer and nativeEffective seed evidence do not match.")
        }
        val nativeCfg = nativeEffective.imageGenerationApiRequiredFiniteNumber("cfgScale")
        if (!imageGenerationApiNumbersMatch(execution.imageGenerationApiRequiredFiniteNumber("cfgScale"), nativeCfg)) {
            reject("invalid_image_execution_evidence", "Outer and nativeEffective cfgScale evidence do not match.")
        }
        val nativeVaeScale = nativeEffective.imageGenerationApiRequiredPositiveNumber("vaeScalingFactor")
        if (!imageGenerationApiNumbersMatch(execution.imageGenerationApiRequiredPositiveNumber("vaeScalingFactor"), nativeVaeScale)) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective vaeScalingFactor evidence do not match."
            )
        }
        val useCfg = nativeEffective.imageGenerationApiRequiredBoolean("useCfg")
        val unconditionalBranch = nativeEffective.imageGenerationApiRequiredBoolean("unconditionalBranch")
        if (useCfg != unconditionalBranch) {
            reject(
                "invalid_image_execution_evidence",
                "nativeEffective unconditionalBranch must exactly match useCfg."
            )
        }
        if (execution.imageGenerationApiRequiredBoolean("useCfg") != useCfg ||
            execution.imageGenerationApiRequiredBoolean("unconditionalBranch") != unconditionalBranch
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective CFG branch evidence do not match."
            )
        }
        validatePromptWeightingEvidence(execution, nativeEffective)
        val nativeFallback = nativeEffective.imageGenerationApiRequiredBoolean("fallback")
        if (nativeFallback) {
            reject("invalid_image_execution_evidence", "nativeEffective must prove fallback=false.")
        }
        if (execution.imageGenerationApiRequiredBoolean("fallback") != nativeFallback) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective fallback evidence do not match."
            )
        }
        val runtime = execution.imageGenerationApiRequiredNonBlankString("runtime")
        if (runtime == "QNN_HTP") {
            if (!execution.imageGenerationApiRequiredBoolean("npuActive") ||
                !execution.imageGenerationApiRequiredBoolean("qnnGraphExecution")
            ) {
                reject(
                    "invalid_image_execution_evidence",
                    "QNN image execution must prove npuActive and qnnGraphExecution."
                )
            }
        }
    }

    /**
     * Qwen-Image-2.1 uses MNN's integrated text encoder, flow sampler, and VAE path. Its audit is
     * deliberately versioned separately from the SD/CLIP contract below: it must prove the actual
     * native run, selected backend, resolved profile, consumed prompt, request parameters, and
     * published output without fabricating CLIP/UNet/VAE metadata it does not produce.
     */
    internal fun validateQwenImage21ExecutionEvidence(
        execution: JSONObject,
        promptProcessing: ImageGenerationApiPromptProcessing?
    ) {
        val native = execution.optJSONObject("nativeEffective")
            ?: reject(
                "invalid_image_execution_evidence",
                "Qwen-Image-2.1 execution is missing nativeEffective evidence."
            )

        fun invalid(message: String): Nothing =
            reject("invalid_image_execution_evidence", message)

        fun pairedString(field: String): String {
            val outer = execution.imageGenerationApiRequiredNonBlankString(field)
            val inner = native.imageGenerationApiRequiredNonBlankString(field)
            if (outer != inner) invalid("Outer and nativeEffective Qwen $field evidence do not match.")
            return outer
        }

        fun pairedPositiveLong(field: String): Long {
            val outer = execution.imageGenerationApiRequiredPositiveLong(field)
            val inner = native.imageGenerationApiRequiredPositiveLong(field)
            if (outer != inner) invalid("Outer and nativeEffective Qwen $field evidence do not match.")
            return outer
        }

        fun pairedInteger(field: String, minimum: Long = 0L): Long {
            val outer = execution.imageGenerationApiRequiredInteger(field, minimum)
            val inner = native.imageGenerationApiRequiredInteger(field, minimum)
            if (outer != inner) invalid("Outer and nativeEffective Qwen $field evidence do not match.")
            return outer
        }

        fun pairedBoolean(field: String): Boolean {
            val outer = execution.imageGenerationApiRequiredBoolean(field)
            val inner = native.imageGenerationApiRequiredBoolean(field)
            if (outer != inner) invalid("Outer and nativeEffective Qwen $field evidence do not match.")
            return outer
        }

        fun pairedFiniteNumber(field: String): Double {
            val outer = execution.imageGenerationApiRequiredFiniteNumber(field)
            val inner = native.imageGenerationApiRequiredFiniteNumber(field)
            if (!imageGenerationApiNumbersMatch(outer, inner)) invalid("Outer and nativeEffective Qwen $field evidence do not match.")
            return outer
        }

        if (pairedString("executionSchema") != QWEN_IMAGE_21_EXECUTION_SCHEMA ||
            pairedString("profileId") != QWEN_IMAGE_21_PROFILE_ID ||
            pairedString("runtime") != QWEN_IMAGE_21_RUNTIME ||
            pairedString("taskMode") != ImageGenerationApiTaskMode.TEXT_TO_IMAGE.wireName
        ) {
            invalid("Qwen-Image-2.1 execution schema, profile, runtime, or task mode is invalid.")
        }

        val modelFingerprint = pairedString("modelFingerprint")
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(modelFingerprint)) {
            invalid("Qwen modelFingerprint must be a lowercase SHA-256 digest.")
        }
        pairedPositiveLong("profileRevision")
        val profileBinding = pairedString("profileBindingFingerprint")
        val imageProfileBinding = pairedString("imageProfileBindingFingerprint")
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(profileBinding) || profileBinding != imageProfileBinding) {
            invalid("Qwen profile binding fingerprints are invalid or inconsistent.")
        }
        val promptLanguageBinding = pairedString("promptLanguageBindingFingerprint")
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(promptLanguageBinding)) {
            invalid("Qwen prompt language binding must be a lowercase SHA-256 digest.")
        }
        promptProcessing?.let { prompt ->
            if (prompt.imageProfileBindingFingerprint != imageProfileBinding ||
                prompt.promptLanguageBindingFingerprint != promptLanguageBinding
            ) {
                invalid("Qwen profile and language bindings do not match prompt_processing.")
            }
        }

        if (!pairedBoolean("nativeExecution") || pairedBoolean("fallback") ||
            !pairedBoolean("nativeRunCompleted") || !pairedBoolean("backendResolved") ||
            !pairedBoolean("backendExecutionConfirmedByNative")
        ) {
            invalid("Qwen execution did not prove a completed native run on the resolved backend.")
        }
        if (pairedString("backendExecutionProof") != QWEN_IMAGE_21_BACKEND_PROOF ||
            pairedString("backendExecutionScope") != QWEN_IMAGE_21_BACKEND_SCOPE
        ) {
            invalid("Qwen native backend execution proof is missing or unsupported.")
        }
        val requestedBackend = pairedString("requestedBackend")
        val effectiveBackend = pairedString("effectiveBackend")
        if (requestedBackend !in QWEN_IMAGE_21_BACKENDS ||
            effectiveBackend !in QWEN_IMAGE_21_BACKENDS || requestedBackend != effectiveBackend
        ) {
            invalid("Qwen requested and effective native backends do not match a supported backend.")
        }
        execution.optString("backendConfigured")
            .takeIf(String::isNotBlank)
            ?.let { configured ->
                if (configured != effectiveBackend) {
                    invalid("Qwen configured backend does not match native backend evidence.")
                }
            }

        val generationSequence = pairedPositiveLong("nativeGenerationSequence")
        val generationCount = pairedPositiveLong("nativeGenerationCount")
        if (generationSequence != generationCount) {
            invalid("Qwen generation sequence does not match the completed native generation count.")
        }

        val width = pairedPositiveLong("width")
        val height = pairedPositiveLong("height")
        if (width != QWEN_IMAGE_21_WIDTH.toLong() || height != QWEN_IMAGE_21_HEIGHT.toLong()) {
            invalid("Qwen-Image-2.1 schema v1 only supports its packaged 512x512 output graph.")
        }
        val steps = pairedPositiveLong("steps")
        if (steps !in QWEN_IMAGE_21_MIN_STEPS..QWEN_IMAGE_21_MAX_STEPS) {
            invalid("Qwen-Image-2.1 schema v1 requires between 2 and 40 sampling steps.")
        }
        val seed = pairedInteger("seed")
        if (pairedPositiveLong("batchCount") != 1L) {
            invalid("Qwen-Image-2.1 schema v1 must bind exactly one generated image.")
        }
        if (!imageGenerationApiNumbersMatch(pairedFiniteNumber("cfgScale"), 1.0)) {
            invalid("Qwen-Image-2.1 execution must use the model-native guidance value 1.0.")
        }
        if (imageGenerationApiCanonicalSampler(pairedString("scheduler")) != "flow_match") {
            invalid("Qwen-Image-2.1 execution must use its native flow-matching scheduler.")
        }

        val promptSha256 = pairedString("nativePromptExecutionSha256")
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(promptSha256)) {
            invalid("Qwen native prompt execution evidence is not a lowercase SHA-256 digest.")
        }
        if (pairedString("nativePromptBindingStage") != "conditioning_consumed") {
            invalid("Qwen prompt evidence must be published after conditioning consumption.")
        }
        if (pairedString("promptExecutionSha256") != promptSha256) {
            invalid("Qwen provider prompt binding does not match the native consumed prompt.")
        }
        validateNativePromptExecutionEvidence(execution, native, promptProcessing)

        val outputSha256 = pairedString("outputSha256")
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(outputSha256)) {
            invalid("Qwen outputSha256 must be a lowercase SHA-256 digest.")
        }
        pairedPositiveLong("outputBytes")

        // The worker has no support for SD/CLIP editing or conditioning controls. Reject such
        // evidence if present; do not route Qwen through validators that require those schemas.
        val sdOnlyEvidenceFields = setOf(
            "imageInput",
            "loras",
            "loraEvidence",
            "textualInversions",
            "textualInversionEvidence",
            "textualInversionExecutionAssets",
            "ultraFix",
            "vaeTiling"
        )
        if (sdOnlyEvidenceFields.any { execution.has(it) || native.has(it) }) {
            invalid("Qwen response contains unsupported image-input, LoRA, textual-inversion, or tiling evidence.")
        }
        if (seed < 0L) invalid("Qwen native seed must be non-negative.")
    }

    internal fun validateQwenImage21RequestedControls(
        request: ImageGenerationApiRequest,
        execution: JSONObject
    ) {
        fun rejectInput(message: String): Nothing =
            reject("invalid_image_input_execution_evidence", message)
        fun rejectControl(message: String): Nothing =
            reject("image_control_mismatch", message)

        if (request.taskMode != ImageGenerationApiTaskMode.TEXT_TO_IMAGE ||
            request.inputImage != null || request.maskImage != null || request.controlImage != null ||
            request.strength != null || request.controlStrength != null
        ) {
            rejectInput("Qwen-Image-2.1 only accepts text-to-image requests without image inputs.")
        }
        if (request.imageCount != 1) {
            reject("invalid_image_provider_response", "Qwen-Image-2.1 returns exactly one image per request.")
        }
        if (!request.negativePrompt.isNullOrBlank()) {
            rejectControl("Qwen-Image-2.1 does not accept a separate negative prompt.")
        }
        if (request.loras.isNotEmpty()) {
            reject("invalid_lora_execution_evidence", "Qwen-Image-2.1 does not support LoRA requests.")
        }
        if (request.textualInversionIds.isNotEmpty()) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Qwen-Image-2.1 does not support textual-inversion requests."
            )
        }
        if (request.ultraFix != null) {
            reject("invalid_ultrafix_execution_evidence", "Qwen-Image-2.1 does not support UltraFix requests.")
        }
        if (request.vaeTiling != null || request.preview != null || request.clipSkip != null) {
            rejectControl("Qwen-Image-2.1 does not support VAE tiling, previews, or CLIP skip controls.")
        }

        val native = execution.optJSONObject("nativeEffective")
            ?: reject(
                "invalid_image_execution_evidence",
                "Qwen-Image-2.1 execution is missing nativeEffective evidence."
            )
        fun checkInt(field: String, expected: Int?, label: String) {
            expected ?: return
            if (native.imageGenerationApiRequiredInteger(field, 0L) != expected.toLong()) {
                rejectControl("Native Qwen $label does not match the request.")
            }
        }
        checkInt("width", request.width, "width")
        checkInt("height", request.height, "height")
        checkInt("steps", request.steps, "steps")
        request.seed?.takeIf { it >= 0 }?.let { expected ->
            if (native.imageGenerationApiRequiredInteger("seed", 0L) != expected.toLong()) {
                rejectControl("Native Qwen seed does not match the request.")
            }
        }
        request.cfgScale?.let { expected ->
            if (!imageGenerationApiNumbersMatch(native.imageGenerationApiRequiredFiniteNumber("cfgScale"), expected)) {
                rejectControl("Native Qwen cfg_scale does not match the request.")
            }
        }
        request.sampler?.let { expected ->
            if (imageGenerationApiCanonicalSampler(native.imageGenerationApiRequiredNonBlankString("scheduler")) != imageGenerationApiCanonicalSampler(expected)) {
                rejectControl("Native Qwen sampler does not match the request.")
            }
        }
    }

    internal fun validateQwenImage21ImageData(
        data: JSONArray,
        execution: JSONObject,
        nativeEffective: JSONObject,
        expectedCount: Int
    ) {
        if (expectedCount != 1) {
            reject("invalid_image_provider_response", "Qwen-Image-2.1 schema v1 must return one image.")
        }
        validateImageData(
            data = data,
            execution = execution,
            nativeEffective = nativeEffective,
            expectedCount = 1,
            requireSeedEvidence = true
        )
        val item = data.optJSONObject(0)
            ?: reject("invalid_image_provider_response", "Qwen image data item must be an object.")
        val responseOutput = execution.optJSONArray("responseOutputEvidence")
            ?.optJSONObject(0)
            ?: reject("invalid_image_provider_response", "Qwen output byte evidence is missing.")
        val sha256 = item.imageGenerationApiRequiredNonBlankString("b64_json").let { encoded ->
            val bytes = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                reject("invalid_image_provider_response", "Qwen image data Base64 is malformed.")
            }
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        val sizeBytes = responseOutput.imageGenerationApiRequiredPositiveLong("sizeBytes")
        if (execution.imageGenerationApiRequiredNonBlankString("outputSha256") != sha256 ||
            nativeEffective.imageGenerationApiRequiredNonBlankString("outputSha256") != sha256 ||
            execution.imageGenerationApiRequiredPositiveLong("outputBytes") != sizeBytes ||
            nativeEffective.imageGenerationApiRequiredPositiveLong("outputBytes") != sizeBytes
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Qwen output hash/size evidence does not match the returned PNG bytes."
            )
        }
    }

    internal fun isQwenImage21Execution(execution: JSONObject): Boolean {
        val native = execution.optJSONObject("nativeEffective")
        return execution.optString("executionSchema") == QWEN_IMAGE_21_EXECUTION_SCHEMA ||
            native?.optString("executionSchema") == QWEN_IMAGE_21_EXECUTION_SCHEMA ||
            execution.optString("profileId") == QWEN_IMAGE_21_PROFILE_ID ||
            native?.optString("profileId") == QWEN_IMAGE_21_PROFILE_ID
    }

    internal fun validateNativePromptExecutionEvidence(
        execution: JSONObject,
        nativeEffective: JSONObject,
        promptProcessing: ImageGenerationApiPromptProcessing?
    ) {
        val outerSha256 = execution.imageGenerationApiRequiredNonBlankString("nativePromptExecutionSha256")
        val nativeSha256 = nativeEffective.imageGenerationApiRequiredNonBlankString("nativePromptExecutionSha256")
        val outerStage = execution.imageGenerationApiRequiredNonBlankString("nativePromptBindingStage")
        val nativeStage = nativeEffective.imageGenerationApiRequiredNonBlankString("nativePromptBindingStage")
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(outerSha256) || outerSha256 != nativeSha256) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective prompt execution SHA-256 evidence must match."
            )
        }
        if (outerStage != "conditioning_consumed" || nativeStage != outerStage) {
            reject(
                "invalid_image_execution_evidence",
                "Native prompt binding evidence must be published after conditioning consumption."
            )
        }
        promptProcessing?.let { evidence ->
            if (outerSha256 != imagePromptExecutionSha256(
                    evidence.effectivePrompt,
                    evidence.effectiveNegativePrompt
                )
            ) {
                reject(
                    "prompt_processing_execution_mismatch",
                    "Native prompt execution evidence does not match prompt_processing."
                )
            }
        }
    }

    internal fun validatePromptWeightingEvidence(
        execution: JSONObject,
        nativeEffective: JSONObject
    ) {
        val supported = nativeEffective.imageGenerationApiRequiredBoolean("promptWeightingSupported")
        val applied = nativeEffective.imageGenerationApiRequiredBoolean("promptWeightingApplied")
        val positiveCount = nativeEffective.imageGenerationApiRequiredInteger("positiveWeightedTokenCount", minimum = 0L)
        val negativeCount = nativeEffective.imageGenerationApiRequiredInteger("negativeWeightedTokenCount", minimum = 0L)
        val fingerprint = nativeEffective.imageGenerationApiRequiredNonBlankString("promptWeightFingerprint")
        val tokenCount = nativeEffective.imageGenerationApiRequiredPositiveLong("tokenCount")

        if (execution.imageGenerationApiRequiredBoolean("promptWeightingSupported") != supported ||
            execution.imageGenerationApiRequiredBoolean("promptWeightingApplied") != applied ||
            execution.imageGenerationApiRequiredInteger("positiveWeightedTokenCount", minimum = 0L) != positiveCount ||
            execution.imageGenerationApiRequiredInteger("negativeWeightedTokenCount", minimum = 0L) != negativeCount ||
            execution.imageGenerationApiRequiredNonBlankString("promptWeightFingerprint") != fingerprint
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective prompt-weighting evidence do not match."
            )
        }
        if (!ImageGenerationApiContract.SHA256_PATTERN.matches(fingerprint)) {
            reject(
                "invalid_image_execution_evidence",
                "promptWeightFingerprint must be a lowercase 64-character SHA-256 value."
            )
        }
        if (positiveCount > tokenCount || negativeCount > tokenCount ||
            positiveCount + negativeCount > tokenCount
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Prompt-weighted token counts exceed the native token capacity."
            )
        }
        val weightedCount = positiveCount + negativeCount
        if ((applied && (!supported || weightedCount == 0L)) || (!applied && weightedCount != 0L)) {
            reject(
                "invalid_image_execution_evidence",
                "Prompt-weighting flags and weighted-token counts are inconsistent."
            )
        }
    }

    internal fun validateRequestedControlEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject
    ) {
        val nativeEffective = execution.getJSONObject("nativeEffective")
        val nativeTaskMode = nativeEffective.imageGenerationApiRequiredNonBlankString("taskMode")
        if (nativeTaskMode != request.taskMode.wireName ||
            execution.imageGenerationApiRequiredNonBlankString("taskMode") != nativeTaskMode
        ) {
            reject("image_control_mismatch", "Native task_mode does not match the request.")
        }
        request.width?.let { expected ->
            if (nativeEffective.imageGenerationApiRequiredPositiveLong("width") != expected.toLong()) {
                reject("image_control_mismatch", "Native width does not match the requested size.")
            }
        }
        request.height?.let { expected ->
            if (nativeEffective.imageGenerationApiRequiredPositiveLong("height") != expected.toLong()) {
                reject("image_control_mismatch", "Native height does not match the requested size.")
            }
        }
        request.steps?.let { expected ->
            if (nativeEffective.imageGenerationApiRequiredPositiveLong("steps") != expected.toLong()) {
                reject("image_control_mismatch", "Native steps do not match the request.")
            }
        }
        request.cfgScale?.let { expected ->
            if (!imageGenerationApiNumbersMatch(nativeEffective.imageGenerationApiRequiredFiniteNumber("cfgScale"), expected)) {
                reject("image_control_mismatch", "Native cfg_scale does not match the request.")
            }
        }
        request.seed?.let { expected ->
            val actual = nativeEffective.imageGenerationApiRequiredInteger("seed", minimum = 0L)
            if (expected >= 0 && actual != expected.toLong()) {
                reject("image_control_mismatch", "Native seed does not match the request.")
            }
        }
        request.sampler?.let { expected ->
            val actual = nativeEffective.imageGenerationApiRequiredNonBlankString("scheduler")
            if (imageGenerationApiCanonicalSampler(actual) != imageGenerationApiCanonicalSampler(expected)) {
                reject("image_control_mismatch", "Native sampler does not match the request.")
            }
        }
        request.clipSkip?.let { expected ->
            val nativeValue = nativeEffective.imageGenerationApiRequiredInteger("clipSkip", minimum = -1L)
            if (nativeValue != expected.toLong() ||
                execution.imageGenerationApiRequiredInteger("clipSkip", minimum = -1L) != nativeValue
            ) {
                reject("image_control_mismatch", "Native clip_skip does not match the request.")
            }
        }
        request.vaeTiling?.takeIf { request.ultraFix == null }?.let { expected ->
            val outer = execution.optJSONObject("vaeTiling")
                ?: reject("image_control_mismatch", "Outer VAE tiling evidence is missing.")
            val native = nativeEffective.optJSONObject("vaeTiling")
                ?: reject("image_control_mismatch", "Native VAE tiling evidence is missing.")
            listOf(outer, native).forEach { evidence ->
                if (!evidence.imageGenerationApiRequiredBoolean("enabled") ||
                    evidence.imageGenerationApiRequiredInteger("requestedTileSize", minimum = 1L) !=
                    expected.tileSize.toLong() ||
                    !imageGenerationApiNumbersMatch(
                        evidence.imageGenerationApiRequiredFiniteNumber("requestedOverlap"),
                        expected.overlap
                    )
                ) {
                    reject(
                        "image_control_mismatch",
                        "Native VAE tiling does not match the requested tile plan."
                    )
                }
            }
        }
        if (request.vaeTiling == null && request.ultraFix == null) {
            val outer = execution.optJSONObject("vaeTiling")
            val native = nativeEffective.optJSONObject("vaeTiling")
            if (outer != null || native != null) {
                if (outer == null || native == null) {
                    reject(
                        "image_control_mismatch",
                        "Outer and native VAE tiling evidence must either both exist or both be absent."
                    )
                }
                listOf(outer, native).forEach { evidence ->
                    if (evidence.imageGenerationApiRequiredBoolean("enabled") ||
                        evidence.imageGenerationApiRequiredInteger("requestedTileSize", minimum = 0L) != 0L ||
                        !imageGenerationApiNumbersMatch(evidence.imageGenerationApiRequiredFiniteNumber("requestedOverlap"), 0.0)
                    ) {
                        reject(
                            "image_control_mismatch",
                            "Native VAE tiling executed even though the request omitted it."
                        )
                    }
                }
            }
        }
        validateSynchronousPreviewWasNotExecuted(execution)
        validateSynchronousPreviewWasNotExecuted(nativeEffective)
    }

    internal fun validateSynchronousPreviewWasNotExecuted(evidence: JSONObject) {
        if (!evidence.has("previewRequested")) return
        if (evidence.imageGenerationApiRequiredBoolean("previewRequested") ||
            evidence.imageGenerationApiRequiredNonBlankString("previewMode") != "none" ||
            evidence.imageGenerationApiRequiredInteger("previewInterval", minimum = 0L) != 0L ||
            evidence.imageGenerationApiRequiredInteger("previewPublicationCount", minimum = 0L) != 0L ||
            evidence.imageGenerationApiRequiredInteger("previewLastStep", minimum = 0L) != 0L ||
            evidence.imageGenerationApiRequiredInteger("previewLastRevision", minimum = 0L) != 0L
        ) {
            reject(
                "invalid_preview_execution_evidence",
                "Synchronous image generation must not execute or publish live preview frames."
            )
        }
    }

    internal fun validateRequestedInputEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject
    ) {
        val nativeEffective = execution.getJSONObject("nativeEffective")
        val nativeBatchCount = nativeEffective.imageGenerationApiRequiredInteger("batchCount", minimum = 1L)
        if (nativeBatchCount != request.imageCount.toLong() ||
            execution.imageGenerationApiRequiredInteger("batchCount", minimum = 1L) != nativeBatchCount
        ) {
            reject(
                "invalid_image_input_execution_evidence",
                "Native batchCount does not match the requested image count."
            )
        }
        validateStableDiffusionBatchEvidence(execution, nativeEffective, nativeBatchCount)
        listOf("inputImagePath", "maskImagePath", "controlImagePath").forEach { privateField ->
            if (execution.has(privateField) || nativeEffective.has(privateField)) {
                reject(
                    "private_image_input_path_exposed",
                    "Image provider response exposed a worker-private input path."
                )
            }
        }
        val evidence = execution.optJSONObject("imageInput")
        if (evidence == null) {
            if (request.taskMode == ImageGenerationApiTaskMode.TEXT_TO_IMAGE) return
            reject(
                "invalid_image_input_execution_evidence",
                "Non-text image generation is missing imageInput execution evidence."
            )
        }
        rejectPrivateInputPaths(evidence)
        if (!evidence.imageGenerationApiRequiredBoolean("nativeExecution") ||
            evidence.imageGenerationApiRequiredNonBlankString("taskMode") != request.taskMode.wireName
        ) {
            reject(
                "invalid_image_input_execution_evidence",
                "Image input execution evidence does not match the requested task_mode."
            )
        }
        if (nativeEffective.imageGenerationApiRequiredNonBlankString("taskMode") != request.taskMode.wireName) {
            reject(
                "invalid_image_input_execution_evidence",
                "nativeEffective taskMode does not match the requested task_mode."
            )
        }
        if (evidence.imageGenerationApiRequiredInteger("batchCount", minimum = 1L) != nativeBatchCount) {
            reject(
                "invalid_image_input_execution_evidence",
                "Native batchCount does not match the requested image count."
            )
        }
        fun validateRole(name: String, requested: Boolean) {
            val count = evidence.imageGenerationApiRequiredInteger("${name}ExecutionCount", minimum = 0L)
            val nativeCount = nativeEffective.imageGenerationApiRequiredInteger("${name}ExecutionCount", minimum = 0L)
            if (nativeCount != count) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "imageInput and nativeEffective execution counts for $name do not match."
                )
            }
            if ((requested && count <= 0L) || (!requested && count != 0L)) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "Image input execution count for $name does not match the request."
                )
            }
            val item = evidence.optJSONObject(name)
            if (requested) {
                val sha = item?.imageGenerationApiRequiredNonBlankString("sha256")
                    ?: reject("invalid_image_input_execution_evidence", "$name sha256 evidence is missing.")
                if (!ImageGenerationApiContract.SHA256_PATTERN.matches(sha)) {
                    reject("invalid_image_input_execution_evidence", "$name sha256 evidence is invalid.")
                }
                val nativeSha = nativeEffective.imageGenerationApiRequiredNonBlankString("${name}Sha256")
                if (nativeSha != sha || !ImageGenerationApiContract.SHA256_PATTERN.matches(nativeSha)) {
                    reject(
                        "invalid_image_input_execution_evidence",
                        "imageInput and nativeEffective sha256 evidence for $name do not match."
                    )
                }
            } else if (item != null) {
                reject("invalid_image_input_execution_evidence", "Unexpected $name evidence was returned.")
            } else if (nativeEffective.has("${name}Sha256")) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "Unexpected nativeEffective sha256 evidence was returned for $name."
                )
            }
        }
        validateRole("inputImage", request.inputImage != null)
        validateRole("maskImage", request.maskImage != null)
        validateRole("controlImage", request.controlImage != null)
        request.strength?.let { expected ->
            if (!imageGenerationApiNumbersMatch(nativeEffective.imageGenerationApiRequiredFiniteNumber("strength"), expected) ||
                !imageGenerationApiNumbersMatch(evidence.imageGenerationApiRequiredFiniteNumber("strength"), expected)
            ) {
                reject("invalid_image_input_execution_evidence", "Native strength does not match the request.")
            }
        }
        request.controlStrength?.let { expected ->
            if (!imageGenerationApiNumbersMatch(nativeEffective.imageGenerationApiRequiredFiniteNumber("controlStrength"), expected) ||
                !imageGenerationApiNumbersMatch(evidence.imageGenerationApiRequiredFiniteNumber("controlStrength"), expected)
            ) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "Native control_strength does not match the request."
                )
            }
        }
    }

    internal fun validateStableDiffusionBatchEvidence(
        execution: JSONObject,
        nativeEffective: JSONObject,
        batchCount: Long
    ) {
        if (nativeEffective.imageGenerationApiRequiredNonBlankString("runtime") != "STABLE_DIFFUSION_CPP") return
        listOf("outputCount", "n", "samplingPassCount").forEach { field ->
            if (nativeEffective.imageGenerationApiRequiredInteger(field, minimum = 1L) != batchCount ||
                execution.imageGenerationApiRequiredInteger(field, minimum = 1L) != batchCount
            ) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "stable-diffusion.cpp $field does not match the requested image count."
                )
            }
        }
        if (execution.imageGenerationApiRequiredInteger("actualSamplingPassCount", minimum = 1L) != batchCount) {
            reject(
                "invalid_image_input_execution_evidence",
                "stable-diffusion.cpp physical sampling passes do not match the requested image count."
            )
        }
        val timetableCount = nativeEffective.imageGenerationApiRequiredInteger("timetableCount", minimum = 1L)
        val unetExecutionCount = nativeEffective.imageGenerationApiRequiredInteger("unetExecutionCount", minimum = 1L)
        val expectedTotalSteps = runCatching { Math.multiplyExact(timetableCount, batchCount) }
            .getOrElse {
                reject(
                    "invalid_image_input_execution_evidence",
                    "stable-diffusion.cpp total sampling-step evidence overflowed."
                )
            }
        val expectedTotalCompute = runCatching { Math.multiplyExact(unetExecutionCount, batchCount) }
            .getOrElse {
                reject(
                    "invalid_image_input_execution_evidence",
                    "stable-diffusion.cpp total compute evidence overflowed."
                )
            }
        if (execution.imageGenerationApiRequiredInteger("actualSamplingStepCount", minimum = 1L) != expectedTotalSteps ||
            nativeEffective.imageGenerationApiRequiredInteger("totalUnetExecutionCount", minimum = 1L) != expectedTotalCompute ||
            execution.imageGenerationApiRequiredInteger("totalUnetExecutionCount", minimum = 1L) != expectedTotalCompute ||
            execution.imageGenerationApiRequiredInteger("actualDiffusionModelComputeCount", minimum = 1L) != expectedTotalCompute
        ) {
            reject(
                "invalid_image_input_execution_evidence",
                "stable-diffusion.cpp total physical execution evidence does not match per-image evidence times n."
            )
        }
    }

    internal fun validateRequestedLoraEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject
    ) {
        val nativeEffective = execution.getJSONObject("nativeEffective")
        val optionalOuterLoras = execution.optJSONArray("loras")
        val optionalNativeLoras = nativeEffective.optJSONArray("loras")
        val optionalOuterCounts = execution.optJSONObject("loraEvidence")
        val optionalNativeCounts = nativeEffective.optJSONObject("loraEvidence")
        if (request.loras.isEmpty() &&
            optionalOuterLoras == null && optionalNativeLoras == null &&
            optionalOuterCounts == null && optionalNativeCounts == null
        ) {
            return
        }
        val outerLoras = optionalOuterLoras
            ?: reject("invalid_lora_execution_evidence", "Outer LoRA execution evidence is missing.")
        val nativeLoras = optionalNativeLoras
            ?: reject(
                "invalid_lora_execution_evidence",
                "nativeEffective LoRA execution evidence is missing."
            )
        if (outerLoras.length() != request.loras.size || nativeLoras.length() != request.loras.size) {
            reject(
                "invalid_lora_execution_evidence",
                "Native LoRA evidence count does not match the request."
            )
        }
        request.loras.forEachIndexed { index, expected ->
            val outer = outerLoras.optJSONObject(index)
                ?: reject("invalid_lora_execution_evidence", "Outer LoRA item is invalid.")
            val native = nativeLoras.optJSONObject(index)
                ?: reject("invalid_lora_execution_evidence", "nativeEffective LoRA item is invalid.")
            rejectPrivateInputPaths(outer)
            rejectPrivateInputPaths(native)
            val outerSha = outer.imageGenerationApiRequiredNonBlankString("sha256")
            val nativeSha = native.imageGenerationApiRequiredNonBlankString("sha256")
            if (outer.imageGenerationApiRequiredNonBlankString("id") != expected.id ||
                native.imageGenerationApiRequiredNonBlankString("id") != expected.id ||
                outerSha != nativeSha ||
                !ImageGenerationApiContract.SHA256_PATTERN.matches(outerSha) ||
                !imageGenerationApiNumbersMatch(outer.imageGenerationApiRequiredFiniteNumber("multiplier"), expected.multiplier) ||
                !imageGenerationApiNumbersMatch(native.imageGenerationApiRequiredFiniteNumber("multiplier"), expected.multiplier)
            ) {
                reject(
                    "invalid_lora_execution_evidence",
                    "Native LoRA identity, digest, or multiplier does not match the request."
                )
            }
        }
        val outerCounts = optionalOuterCounts
            ?: reject("invalid_lora_execution_evidence", "Outer LoRA count evidence is missing.")
        val nativeCounts = optionalNativeCounts
            ?: reject(
                "invalid_lora_execution_evidence",
                "nativeEffective LoRA count evidence is missing."
            )
        listOf(outerCounts, nativeCounts).forEach { counts ->
            val expectedCount = request.loras.size.toLong()
            val appliedTensorCount = counts.imageGenerationApiRequiredInteger("appliedTensorCount", minimum = 0L)
            val tensorEvidenceInvalid = if (expectedCount == 0L) {
                appliedTensorCount != 0L
            } else {
                appliedTensorCount <= 0L
            }
            if (counts.imageGenerationApiRequiredInteger("requestedCount", minimum = 0L) != expectedCount ||
                counts.imageGenerationApiRequiredInteger("loadedCount", minimum = 0L) != expectedCount ||
                counts.imageGenerationApiRequiredInteger("appliedCount", minimum = 0L) != expectedCount ||
                tensorEvidenceInvalid
            ) {
                reject(
                    "invalid_lora_execution_evidence",
                    "Native execution did not load and apply the complete requested LoRA set."
                )
            }
        }
        val countFields = listOf("requestedCount", "loadedCount", "appliedCount", "appliedTensorCount")
        if (countFields.any { field ->
                outerCounts.imageGenerationApiRequiredInteger(field, minimum = 0L) !=
                    nativeCounts.imageGenerationApiRequiredInteger(field, minimum = 0L)
            }
        ) {
            reject(
                "invalid_lora_execution_evidence",
                "Outer and nativeEffective LoRA count evidence do not match."
            )
        }
    }

    internal fun validateRequestedTextualInversionEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject,
        promptProcessing: ImageGenerationApiPromptProcessing?
    ) {
        val nativeEffective = execution.getJSONObject("nativeEffective")
        val outerArtifacts = execution.optJSONArray("textualInversions")
        val nativeArtifacts = nativeEffective.optJSONArray("textualInversions")
        val outerEvidence = execution.optJSONObject("textualInversionEvidence")
        val nativeEvidence = nativeEffective.optJSONObject("textualInversionEvidence")
        val outerExecutionAssets = execution.optJSONArray("textualInversionExecutionAssets")
        val nativeExecutionAssets = nativeEffective.optJSONArray("textualInversionExecutionAssets")
        val expectedIds = request.textualInversionIds
        val anyEvidencePresent = listOf(
            outerArtifacts,
            nativeArtifacts,
            outerEvidence,
            nativeEvidence,
            outerExecutionAssets,
            nativeExecutionAssets
        ).any { it != null } || listOf(
            "textualInversionExecutionAssetsSha256",
            "textualInversionExecutionRuntime",
            "textualInversionExecutionProfileFingerprint",
            "textualInversionExecutionBundleRoot"
        ).any(execution::has) || listOf(
            "textualInversionExecutionAssetsSha256",
            "textualInversionExecutionRuntime",
            "textualInversionExecutionProfileFingerprint",
            "textualInversionExecutionBundleRoot"
        ).any(nativeEffective::has)
        if (expectedIds.isEmpty() && !anyEvidencePresent) return
        if (outerArtifacts == null || nativeArtifacts == null ||
            outerEvidence == null || nativeEvidence == null
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion execution evidence must be complete in both evidence layers."
            )
        }
        if (outerArtifacts.length() != expectedIds.size ||
            nativeArtifacts.length() != expectedIds.size
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion artifact count does not match the request."
            )
        }

        val runtime = execution.imageGenerationApiRequiredNonBlankString("runtime")
        val expectedNativeMode = when (runtime) {
            "STABLE_DIFFUSION_CPP" -> "SDCPP_CUSTOM_WORDS"
            "QNN_HTP", "MNN_DIFFUSION" -> "MNN_CLIP_INPUT_EMBEDDING"
            else -> null
        }
        val executionProfileId = execution.imageGenerationApiRequiredNonBlankString("profileId")
        val executionProfileRevision = execution.imageGenerationApiRequiredPositiveLong("profileRevision")
        val executionModelFingerprint = execution.imageGenerationApiRequiredNonBlankString("modelFingerprint")
        val executionProfilePromptFingerprint = promptProcessing?.promptLanguageBindingFingerprint
        if (expectedIds.isNotEmpty() &&
            (expectedNativeMode == null ||
                !ImageGenerationApiContract.SHA256_PATTERN.matches(executionModelFingerprint) ||
                executionProfilePromptFingerprint == null ||
                !ImageGenerationApiContract.SHA256_PATTERN.matches(executionProfilePromptFingerprint))
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion evidence is not bound to a supported runtime and prompt profile."
            )
        }

        val executionAssetComposite = if (expectedIds.isEmpty()) {
            val unexpectedFields = listOf(
                "textualInversionExecutionAssetsSha256",
                "textualInversionExecutionRuntime",
                "textualInversionExecutionProfileFingerprint",
                "textualInversionExecutionBundleRoot"
            ).any(execution::has) || listOf(
                "textualInversionExecutionAssetsSha256",
                "textualInversionExecutionRuntime",
                "textualInversionExecutionProfileFingerprint",
                "textualInversionExecutionBundleRoot"
            ).any(nativeEffective::has) || outerExecutionAssets != null || nativeExecutionAssets != null
            if (unexpectedFields) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "A request without textual inversions returned consumer-asset evidence."
                )
            }
            null
        } else {
            val outerAssets = outerExecutionAssets ?: reject(
                "invalid_textual_inversion_execution_evidence",
                "Outer textual inversion consumer-asset evidence is missing."
            )
            val nativeAssets = nativeExecutionAssets ?: reject(
                "invalid_textual_inversion_execution_evidence",
                "nativeEffective textual inversion consumer-asset evidence is missing."
            )
            if (outerAssets.length() !in 1..MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_COUNT ||
                nativeAssets.length() != outerAssets.length()
            ) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion consumer-asset count is invalid."
                )
            }
            val outerRuntime = execution.imageGenerationApiRequiredNonBlankString("textualInversionExecutionRuntime")
            val nativeRuntime = nativeEffective.imageGenerationApiRequiredNonBlankString("textualInversionExecutionRuntime")
            val outerProfile = execution.imageGenerationApiRequiredNonBlankString(
                "textualInversionExecutionProfileFingerprint"
            )
            val nativeProfile = nativeEffective.imageGenerationApiRequiredNonBlankString(
                "textualInversionExecutionProfileFingerprint"
            )
            val outerComposite = execution.imageGenerationApiRequiredNonBlankString(
                "textualInversionExecutionAssetsSha256"
            )
            val nativeComposite = nativeEffective.imageGenerationApiRequiredNonBlankString(
                "textualInversionExecutionAssetsSha256"
            )
            if (outerRuntime != runtime || nativeRuntime != outerRuntime ||
                outerProfile != executionProfilePromptFingerprint || nativeProfile != outerProfile ||
                !ImageGenerationApiContract.SHA256_PATTERN.matches(outerProfile) ||
                !ImageGenerationApiContract.SHA256_PATTERN.matches(outerComposite) || nativeComposite != outerComposite
            ) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion consumer assets do not match the executed runtime and prompt profile."
                )
            }
            val canonicalAssets = mutableListOf<ImageTextualInversionExecutionAsset>()
            var previousLabel: String? = null
            var totalBytes = 0L
            for (index in 0 until outerAssets.length()) {
                val outer = outerAssets.optJSONObject(index) ?: reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Outer textual inversion consumer asset must be an object."
                )
                val native = nativeAssets.optJSONObject(index) ?: reject(
                    "invalid_textual_inversion_execution_evidence",
                    "nativeEffective textual inversion consumer asset must be an object."
                )
                val label = outer.imageGenerationApiRequiredNonBlankString("label")
                val nativeLabel = native.imageGenerationApiRequiredNonBlankString("label")
                val sha256 = outer.imageGenerationApiRequiredNonBlankString("sha256")
                val nativeSha256 = native.imageGenerationApiRequiredNonBlankString("sha256")
                val sizeBytes = outer.imageGenerationApiRequiredPositiveLong("sizeBytes")
                val nativeSizeBytes = native.imageGenerationApiRequiredPositiveLong("sizeBytes")
                val invalidLabel = label.length > MAX_EXECUTION_ASSET_LABEL_CHARS ||
                    '\u0000' in label || '\u001f' in label || '\\' in label ||
                    label.startsWith('/') || label.split('/').any { part ->
                        part.isEmpty() || part == "." || part == ".."
                    }
                if (invalidLabel || label != nativeLabel ||
                    previousLabel?.let { previous ->
                        compareUtf8Unsigned(label, previous) <= 0
                    } == true ||
                    !ImageGenerationApiContract.SHA256_PATTERN.matches(sha256) || sha256 != nativeSha256 ||
                    sizeBytes != nativeSizeBytes ||
                    sizeBytes > MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_BYTES - totalBytes ||
                    outer.has("path") || native.has("path")
                ) {
                    reject(
                        "invalid_textual_inversion_execution_evidence",
                        "Textual inversion consumer asset descriptors are not canonical."
                    )
                }
                totalBytes += sizeBytes
                previousLabel = label
                canonicalAssets += ImageTextualInversionExecutionAsset(label, sizeBytes, sha256)
            }
            val canonicalComposite = imageTextualInversionExecutionAssetsSha256(
                runtime = outerRuntime,
                profilePromptFingerprint = outerProfile,
                assets = canonicalAssets
            )
            if (canonicalComposite != outerComposite) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion consumer-asset composite is invalid."
                )
            }
            canonicalComposite
        }

        val artifactStringFields = listOf(
            "id",
            "trigger",
            "sha256",
            "format",
            "modelFingerprint",
            "tokenizerFingerprint",
            "profileId",
            "runtime",
            "bindingFingerprint"
        )
        val canonicalBindings = mutableListOf<Pair<String, String>>()
        val observedTriggers = mutableSetOf<String>()
        var activeArtifactBytes = 0L
        expectedIds.forEachIndexed { index, expectedId ->
            val outer = outerArtifacts.optJSONObject(index)
                ?: reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Outer textual inversion artifact evidence is invalid."
                )
            val native = nativeArtifacts.optJSONObject(index)
                ?: reject(
                    "invalid_textual_inversion_execution_evidence",
                    "nativeEffective textual inversion artifact evidence is invalid."
                )
            artifactStringFields.forEach { field ->
                val outerValue = outer.imageGenerationApiRequiredNonBlankString(field)
                val nativeValue = native.imageGenerationApiRequiredNonBlankString(field)
                if (outerValue != nativeValue) {
                    reject(
                        "invalid_textual_inversion_execution_evidence",
                        "Outer and nativeEffective textual inversion artifact evidence differ."
                    )
                }
            }
            val artifactId = outer.imageGenerationApiRequiredNonBlankString("id")
            val trigger = outer.imageGenerationApiRequiredNonBlankString("trigger")
            val artifactSha256 = outer.imageGenerationApiRequiredNonBlankString("sha256")
            val format = outer.imageGenerationApiRequiredNonBlankString("format")
            val modelFingerprint = outer.imageGenerationApiRequiredNonBlankString("modelFingerprint")
            val tokenizerFingerprint = outer.imageGenerationApiRequiredNonBlankString("tokenizerFingerprint")
            val profileId = outer.imageGenerationApiRequiredNonBlankString("profileId")
            val artifactRuntime = outer.imageGenerationApiRequiredNonBlankString("runtime")
            val bindingFingerprint = outer.imageGenerationApiRequiredNonBlankString("bindingFingerprint")
            if (artifactId != expectedId) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion artifact identity does not match the request."
                )
            }
            if (!TEXTUAL_INVERSION_TRIGGER_PATTERN.matches(trigger) ||
                !observedTriggers.add(trigger.lowercase()) ||
                listOf(
                    artifactSha256,
                    modelFingerprint,
                    tokenizerFingerprint,
                    bindingFingerprint
                ).any { !ImageGenerationApiContract.SHA256_PATTERN.matches(it) }
            ) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion trigger, digest, or binding fingerprint is invalid."
                )
            }
            val outerSize = outer.imageGenerationApiRequiredPositiveLong("sizeBytes")
            val nativeSize = native.imageGenerationApiRequiredPositiveLong("sizeBytes")
            val outerRevision = outer.imageGenerationApiRequiredPositiveLong("profileRevision")
            val nativeRevision = native.imageGenerationApiRequiredPositiveLong("profileRevision")
            val formatSupported = format in TEXTUAL_INVERSION_FORMATS &&
                (runtime == "STABLE_DIFFUSION_CPP" || format == "safetensors")
            if (outerSize > MAX_ACTIVE_TEXTUAL_INVERSION_BYTES - activeArtifactBytes) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Selected textual inversion artifacts exceed the active-request quota."
                )
            }
            activeArtifactBytes += outerSize
            if (outerSize != nativeSize || outerSize > MAX_TEXTUAL_INVERSION_BYTES ||
                outerRevision != nativeRevision ||
                modelFingerprint != executionModelFingerprint ||
                tokenizerFingerprint != executionAssetComposite ||
                profileId != executionProfileId ||
                artifactRuntime != runtime ||
                outerRevision != executionProfileRevision ||
                !formatSupported
            ) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion artifact metadata does not match the executed profile."
                )
            }
            val canonicalBinding = imageTextualInversionBindingFingerprint(
                id = artifactId,
                sha256 = artifactSha256,
                trigger = trigger,
                modelFingerprint = modelFingerprint,
                tokenizerFingerprint = tokenizerFingerprint,
                profileId = profileId,
                profileRevision = outerRevision,
                runtime = artifactRuntime
            )
            if (bindingFingerprint != canonicalBinding) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion artifact binding fingerprint is invalid."
                )
            }
            canonicalBindings += trigger to canonicalBinding
        }

        val countFields = listOf(
            "requestedCount",
            "validatedCount",
            "loadAttemptCount",
            "loadedCount",
            "tokenizerMatchCount",
            "appliedCount",
            "clipLAppliedCount"
        )
        val maskFields = listOf(
            "requestedMask",
            "loadedMask",
            "tokenizerMatchMask",
            "appliedMask",
            "consumedMask",
            "clipLMask"
        )
        val expectedCount = expectedIds.size.toLong()
        val expectedMask = if (expectedIds.isEmpty()) 0L else (1L shl expectedIds.size) - 1L
        countFields.forEach { field ->
            val outer = outerEvidence.imageGenerationApiRequiredInteger(field, minimum = 0L)
            val native = nativeEvidence.imageGenerationApiRequiredInteger(field, minimum = 0L)
            if (outer != expectedCount || native != outer) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion execution counts do not prove the complete request."
                )
            }
        }
        maskFields.forEach { field ->
            val outer = outerEvidence.imageGenerationApiRequiredInteger(field, minimum = 0L)
            val native = nativeEvidence.imageGenerationApiRequiredInteger(field, minimum = 0L)
            if (outer != expectedMask || native != outer) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion execution masks do not prove the complete request."
                )
            }
        }
        val outerConsumption = outerEvidence.imageGenerationApiRequiredInteger(
            "conditioningConsumptionCount",
            minimum = 0L
        )
        val nativeConsumption = nativeEvidence.imageGenerationApiRequiredInteger(
            "conditioningConsumptionCount",
            minimum = 0L
        )
        if (outerConsumption != nativeConsumption ||
            (expectedIds.isEmpty() && outerConsumption != 0L) ||
            (expectedIds.isNotEmpty() && outerConsumption < expectedCount)
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion consumption evidence does not prove the complete request."
            )
        }
        val outerClipGRequired = outerEvidence.imageGenerationApiRequiredInteger("clipGRequiredMask", minimum = 0L)
        val nativeClipGRequired = nativeEvidence.imageGenerationApiRequiredInteger("clipGRequiredMask", minimum = 0L)
        val outerClipG = outerEvidence.imageGenerationApiRequiredInteger("clipGMask", minimum = 0L)
        val nativeClipG = nativeEvidence.imageGenerationApiRequiredInteger("clipGMask", minimum = 0L)
        val outerClipGCount = outerEvidence.imageGenerationApiRequiredInteger("clipGAppliedCount", minimum = 0L)
        val nativeClipGCount = nativeEvidence.imageGenerationApiRequiredInteger("clipGAppliedCount", minimum = 0L)
        if (outerClipGRequired !in setOf(0L, expectedMask) ||
            nativeClipGRequired != outerClipGRequired ||
            outerClipG != outerClipGRequired || nativeClipG != outerClipG ||
            outerClipGCount != java.lang.Long.bitCount(outerClipGRequired).toLong() ||
            nativeClipGCount != outerClipGCount
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion CLIP-G evidence is internally inconsistent."
            )
        }
        val outerVectors = outerEvidence.imageGenerationApiRequiredInteger("appliedVectorCount", minimum = 0L)
        val nativeVectors = nativeEvidence.imageGenerationApiRequiredInteger("appliedVectorCount", minimum = 0L)
        if (outerVectors != nativeVectors ||
            (expectedIds.isEmpty() && outerVectors != 0L) ||
            (expectedIds.isNotEmpty() && outerVectors < expectedCount)
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion applied-vector evidence is invalid."
            )
        }
        val stringFields = listOf("failureCode", "bindingFingerprint", "nativeMode", "bindingStage")
        stringFields.forEach { field ->
            val outer = outerEvidence.opt(field) as? String
                ?: reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion evidence field $field must be a string."
                )
            val native = nativeEvidence.opt(field) as? String
                ?: reject(
                    "invalid_textual_inversion_execution_evidence",
                    "nativeEffective textual inversion evidence field $field must be a string."
                )
            if (outer != native) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Outer and nativeEffective textual inversion evidence differ."
                )
            }
        }
        val bindingFingerprint = outerEvidence.getString("bindingFingerprint")
        val canonicalSelectionFingerprint = if (expectedIds.isEmpty()) {
            ""
        } else {
            imageTextualInversionSelectionFingerprint(canonicalBindings)
        }
        if (outerEvidence.getString("failureCode") != "none" ||
            if (expectedIds.isEmpty()) {
                bindingFingerprint.isNotEmpty() ||
                    outerEvidence.getString("nativeMode") != "none" ||
                    outerEvidence.getString("bindingStage") != "none"
            } else {
                !ImageGenerationApiContract.SHA256_PATTERN.matches(bindingFingerprint) || expectedNativeMode == null ||
                    bindingFingerprint != canonicalSelectionFingerprint ||
                    outerEvidence.getString("nativeMode") != expectedNativeMode ||
                    outerEvidence.getString("bindingStage") != "conditioning_consumed"
            }
        ) {
            reject(
                "invalid_textual_inversion_execution_evidence",
                "Textual inversion evidence does not prove successful native conditioning consumption."
            )
        }
    }

    internal fun validateRequestedUltraFixEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject,
        data: JSONArray
    ) {
        val requested = request.ultraFix ?: return
        when (execution.imageGenerationApiRequiredNonBlankString("runtime")) {
            "QNN_HTP" -> {
                validateQnnUltraFixEvidence(request, execution, data)
                return
            }
            "STABLE_DIFFUSION_CPP" -> Unit
            else -> reject(
                "invalid_ultrafix_execution_evidence",
                "UltraFix is only supported by the stable v5 or QNN v2 execution schemas."
            )
        }
        val nativeEffective = execution.getJSONObject("nativeEffective")

        fun invalid(message: String): Nothing =
            reject("invalid_ultrafix_execution_evidence", message)

        if (request.taskMode != ImageGenerationApiTaskMode.IMG2IMG ||
            request.imageCount != 1 || data.length() != 1
        ) {
            invalid("UltraFix execution must return exactly one img2img output.")
        }

        fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
            if (keys().asSequence().toSet() != expected) {
                invalid("$layer fields do not match the strict UltraFix schema.")
            }
        }

        fun JSONObject.exactLong(field: String, layer: String): Long {
            if (!has(field) || isNull(field)) invalid("$layer is missing $field.")
            val raw = get(field)
            if (raw !is Byte && raw !is Short && raw !is Int && raw !is Long) {
                invalid("$layer $field must be an exact integer.")
            }
            return (raw as Number).toLong()
        }

        fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
            if (!has(field) || isNull(field) || get(field) !is Boolean) {
                invalid("$layer $field must be boolean.")
            }
            return getBoolean(field)
        }

        fun JSONObject.exactString(field: String, layer: String): String {
            if (!has(field) || isNull(field) || get(field) !is String) {
                invalid("$layer $field must be a string.")
            }
            return getString(field)
        }

        fun JSONObject.exactDouble(field: String, layer: String): Double {
            if (!has(field) || isNull(field) || get(field) !is Number) {
                invalid("$layer $field must be numeric.")
            }
            val value = (get(field) as Number).toDouble()
            if (!value.isFinite()) invalid("$layer $field must be finite.")
            return value
        }

        data class StageEvidence(
            val invocationCount: Long,
            val successCount: Long,
            val tileInvocationCount: Long,
            val tileSuccessCount: Long,
            val stepCount: Long
        )

        data class ExtendedEvidence(
            val tileCount: Long,
            val tilePlanSha256: String,
            val encoderGraphExecutionCount: Long,
            val inversionPositiveGraphExecutionCount: Long,
            val refinementPositiveGraphExecutionCount: Long,
            val refinementNegativeGraphExecutionCount: Long,
            val decoderGraphExecutionCount: Long,
            val outputSha256: String,
            val outputBytes: Long,
            val outputAtomicCommit: Boolean
        )

        data class UltraFixEvidence(
            val version: Long,
            val generationCompleted: Boolean,
            val cancelled: Boolean,
            val previewPublished: Boolean,
            val sourceWidth: Long,
            val sourceHeight: Long,
            val targetWidth: Long,
            val targetHeight: Long,
            val sourceFit: String,
            val sourceResizedWidth: Long,
            val sourceResizedHeight: Long,
            val sourceCropLeft: Long,
            val sourceCropTop: Long,
            val tileSize: Long,
            val overlap: Double,
            val inversionSteps: Long,
            val refinementSteps: Long,
            val denoiseStepCount: Long,
            val sampleMethod: String,
            val nativeScheduler: String,
            val vaeEncode: StageEvidence,
            val ddimInversion: StageEvidence,
            val tiledUnetRefinement: StageEvidence,
            val tiledVaeDecode: StageEvidence,
            val physicalDiffusionModelComputeCount: Long,
            val qualityStepEvaluationCount: Long,
            val noiseInjectionStepCount: Long,
            val noiseInjectionSeedFingerprint: String,
            val noiseInjectionChecksum: String,
            val structureGuidanceStepCount: Long,
            val structureGuidanceChecksum: String,
            val trajectoryNoiseChecksum: String,
            val extended: ExtendedEvidence?
        )

        fun parseStage(parent: JSONObject, field: String, layer: String): StageEvidence {
            val stage = parent.optJSONObject(field)
                ?: invalid("$layer is missing $field.")
            stage.requireExactKeys(ULTRAFIX_STAGE_EVIDENCE_FIELDS, "$layer.$field")
            return StageEvidence(
                invocationCount = stage.exactLong("invocationCount", "$layer.$field"),
                successCount = stage.exactLong("successCount", "$layer.$field"),
                tileInvocationCount = stage.exactLong("tileInvocationCount", "$layer.$field"),
                tileSuccessCount = stage.exactLong("tileSuccessCount", "$layer.$field"),
                stepCount = stage.exactLong("stepCount", "$layer.$field")
            )
        }

        fun parseEvidence(parent: JSONObject, layer: String): UltraFixEvidence {
            val evidence = parent.optJSONObject("ultraFix")
                ?: invalid("$layer UltraFix execution evidence is missing.")
            val evidenceKeys = evidence.keys().asSequence().toSet()
            if (evidenceKeys != ULTRAFIX_EXECUTION_EVIDENCE_FIELDS) {
                invalid("$layer.ultraFix fields do not match the strict stable v5 schema.")
            }
            val extended: ExtendedEvidence? = null
            return UltraFixEvidence(
                version = evidence.exactLong("version", "$layer.ultraFix"),
                generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
                cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
                previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
                sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
                sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
                targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
                targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
                sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
                sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
                sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
                sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
                sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
                tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
                overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
                inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
                refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
                denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
                sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
                nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
                vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
                ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
                tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
                tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
                physicalDiffusionModelComputeCount = evidence.exactLong(
                    "physicalDiffusionModelComputeCount",
                    "$layer.ultraFix"
                ),
                qualityStepEvaluationCount = evidence.exactLong(
                    "qualityStepEvaluationCount",
                    "$layer.ultraFix"
                ),
                noiseInjectionStepCount = evidence.exactLong(
                    "noiseInjectionStepCount",
                    "$layer.ultraFix"
                ),
                noiseInjectionSeedFingerprint = evidence.exactString(
                    "noiseInjectionSeedFingerprint",
                    "$layer.ultraFix"
                ),
                noiseInjectionChecksum = evidence.exactString(
                    "noiseInjectionChecksum",
                    "$layer.ultraFix"
                ),
                structureGuidanceStepCount = evidence.exactLong(
                    "structureGuidanceStepCount",
                    "$layer.ultraFix"
                ),
                structureGuidanceChecksum = evidence.exactString(
                    "structureGuidanceChecksum",
                    "$layer.ultraFix"
                ),
                trajectoryNoiseChecksum = evidence.exactString(
                    "trajectoryNoiseChecksum",
                    "$layer.ultraFix"
                ),
                extended = extended
            )
        }

        val outer = parseEvidence(execution, "outer")
        val native = parseEvidence(nativeEffective, "nativeEffective")
        if (outer != native) {
            invalid("Outer and nativeEffective UltraFix evidence do not match.")
        }
        val outerStrengthMechanism = execution.exactString("strengthMechanism", "outer")
        val nativeStrengthMechanism = nativeEffective.exactString(
            "strengthMechanism",
            "nativeEffective"
        )
        if (outerStrengthMechanism != "ddim_inversion" ||
            nativeStrengthMechanism != outerStrengthMechanism
        ) {
            invalid("UltraFix execution did not prove the DDIM inversion strength mechanism.")
        }

        val expectedEvidenceVersion = if (outer.extended == null) {
            ULTRAFIX_BASE_EXECUTION_EVIDENCE_VERSION
        } else {
            ULTRAFIX_EXTENDED_EXECUTION_EVIDENCE_VERSION
        }
        if (outer.version != expectedEvidenceVersion ||
            !outer.generationCompleted || outer.cancelled || outer.previewPublished ||
            outer.sourceWidth <= 0L || outer.sourceHeight <= 0L ||
            outer.targetWidth != requested.targetWidth.toLong() ||
            outer.targetHeight != requested.targetHeight.toLong() ||
            outer.sourceFit != "cover_center" ||
            outer.sourceResizedWidth < outer.targetWidth ||
            outer.sourceResizedHeight < outer.targetHeight ||
            outer.sourceCropLeft != (outer.sourceResizedWidth - outer.targetWidth) / 2L ||
            outer.sourceCropTop != (outer.sourceResizedHeight - outer.targetHeight) / 2L ||
            outer.tileSize != requested.tileSize.toLong() ||
            !imageGenerationApiNumbersMatch(outer.overlap, requested.overlap) ||
            outer.inversionSteps != requested.inversionSteps.toLong() ||
            outer.refinementSteps != requested.refinementSteps.toLong() ||
            outer.denoiseStepCount != requested.inversionSteps.toLong() ||
            outer.sampleMethod.isBlank() || outer.nativeScheduler.isBlank() ||
            !imageGenerationApiNumbersMatch(execution.exactDouble("strength", "outer"), requested.strength) ||
            !imageGenerationApiNumbersMatch(
                nativeEffective.exactDouble("strength", "nativeEffective"),
                requested.strength
            )
        ) {
            invalid("UltraFix evidence does not match the immutable API request.")
        }

        fun requireStage(
            name: String,
            stage: StageEvidence,
            invocations: Long,
            steps: Long
        ) {
            if (stage.invocationCount != invocations || stage.successCount != invocations ||
                stage.stepCount != steps || stage.tileInvocationCount <= 0L ||
                stage.tileSuccessCount != stage.tileInvocationCount
            ) {
                invalid("$name UltraFix stage evidence is incomplete.")
            }
        }

        val inversionSteps = requested.inversionSteps.toLong()
        requireStage("VAE encode", outer.vaeEncode, 1L, 1L)
        requireStage("DDIM inversion", outer.ddimInversion, inversionSteps, inversionSteps)
        requireStage(
            "tiled UNet refinement",
            outer.tiledUnetRefinement,
            inversionSteps,
            inversionSteps
        )
        requireStage("tiled VAE decode", outer.tiledVaeDecode, 1L, 1L)
        if (outer.ddimInversion.tileInvocationCount % inversionSteps != 0L ||
            outer.tiledUnetRefinement.tileInvocationCount % inversionSteps != 0L
        ) {
            invalid("UltraFix tile evidence is not divisible by the completed timestep count.")
        }
        if (outer.ddimInversion.tileInvocationCount / inversionSteps !=
            outer.tiledUnetRefinement.tileInvocationCount / inversionSteps
        ) {
            invalid("UltraFix DDIM inversion and refinement tile plans do not match.")
        }

        val (expectedPositive, expectedNegative, expectedPhysical) = try {
            val extended = outer.extended
            if (extended == null) {
                val positive = Math.addExact(
                    outer.ddimInversion.tileInvocationCount,
                    outer.tiledUnetRefinement.tileInvocationCount
                )
                val negative = if (nativeEffective.imageGenerationApiRequiredBoolean("useCfg")) {
                    outer.tiledUnetRefinement.tileInvocationCount
                } else {
                    0L
                }
                Triple(positive, negative, Math.addExact(positive, negative))
            } else {
                if (!ImageGenerationApiContract.SHA256_PATTERN.matches(extended.tilePlanSha256) ||
                    extended.tileCount <= 0L ||
                    extended.encoderGraphExecutionCount != outer.vaeEncode.tileInvocationCount ||
                    extended.encoderGraphExecutionCount != outer.vaeEncode.tileSuccessCount ||
                    extended.inversionPositiveGraphExecutionCount !=
                        outer.ddimInversion.tileInvocationCount ||
                    Math.addExact(
                        extended.refinementPositiveGraphExecutionCount,
                        extended.refinementNegativeGraphExecutionCount
                    ) != outer.tiledUnetRefinement.tileInvocationCount ||
                    extended.decoderGraphExecutionCount !=
                        outer.tiledVaeDecode.tileInvocationCount ||
                    extended.decoderGraphExecutionCount !=
                        outer.tiledVaeDecode.tileSuccessCount ||
                    extended.encoderGraphExecutionCount != extended.tileCount ||
                    extended.inversionPositiveGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps) ||
                    extended.refinementPositiveGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps) ||
                    extended.decoderGraphExecutionCount != extended.tileCount ||
                    (nativeEffective.imageGenerationApiRequiredBoolean("useCfg") &&
                        extended.refinementNegativeGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps)) ||
                    (!nativeEffective.imageGenerationApiRequiredBoolean("useCfg") &&
                        extended.refinementNegativeGraphExecutionCount != 0L)
                ) {
                    invalid("Extended UltraFix tile-plan and graph counters are inconsistent.")
                }
                val positive = Math.addExact(
                    extended.inversionPositiveGraphExecutionCount,
                    extended.refinementPositiveGraphExecutionCount
                )
                val negative = extended.refinementNegativeGraphExecutionCount
                Triple(positive, negative, Math.addExact(positive, negative))
            }
        } catch (_: ArithmeticException) {
            invalid("UltraFix physical execution evidence overflowed.")
        }
        val nativeCounterEvidenceMatches = outer.extended != null ||
            (nativeEffective.exactLong(
                "positiveDiffusionModelComputeCount",
                "nativeEffective"
            ) == expectedPositive &&
                nativeEffective.exactLong(
                    "negativeDiffusionModelComputeCount",
                    "nativeEffective"
                ) == expectedNegative &&
                nativeEffective.exactLong(
                    "auxiliaryDiffusionModelComputeCount",
                    "nativeEffective"
                ) == 0L &&
                nativeEffective.exactLong("samplingPassCount", "nativeEffective") == 1L &&
                nativeEffective.exactLong(
                    "totalUnetExecutionCount",
                    "nativeEffective"
                ) == expectedPhysical)
        if (outer.physicalDiffusionModelComputeCount != expectedPhysical ||
            execution.exactLong("actualDiffusionModelComputeCount", "outer") != expectedPhysical ||
            execution.exactLong("actualPositiveDiffusionModelComputeCount", "outer") != expectedPositive ||
            execution.exactLong("actualNegativeDiffusionModelComputeCount", "outer") != expectedNegative ||
            execution.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") != 0L ||
            execution.exactLong("actualSamplingStepCount", "outer") != inversionSteps ||
            execution.exactLong("actualSamplingPassCount", "outer") != 1L ||
            execution.exactLong("totalUnetExecutionCount", "outer") != expectedPhysical ||
            !nativeCounterEvidenceMatches
        ) {
            invalid("UltraFix physical execution evidence is incomplete or misclassified.")
        }

        val qualityStepCount = Math.max(inversionSteps - 1L, 0L)
        val seed = execution.exactLong("seed", "outer")
        val zeroQualityChecksum = "0000000000000000"
        val noiseEvidenceConsistent =
            (outer.noiseInjectionStepCount == 0L) ==
                (outer.noiseInjectionChecksum == zeroQualityChecksum)
        val structureEvidenceConsistent =
            (outer.structureGuidanceStepCount == 0L) ==
                (outer.structureGuidanceChecksum == zeroQualityChecksum)
        val qualityActionCount = runCatching {
            Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
        }.getOrDefault(-1L)
        if (seed < 0L || nativeEffective.exactLong("seed", "nativeEffective") != seed ||
            outer.qualityStepEvaluationCount != qualityStepCount ||
            outer.noiseInjectionStepCount !in 0L..qualityStepCount ||
            outer.structureGuidanceStepCount !in 0L..qualityStepCount ||
            outer.noiseInjectionSeedFingerprint !=
                ultrafixNoiseSeedFingerprint(seed, inversionSteps) ||
            !ImageGenerationApiContract.SHA256_PATTERN.matches(outer.noiseInjectionSeedFingerprint) ||
            !UINT64_HEX_PATTERN.matches(outer.noiseInjectionChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.structureGuidanceChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.trajectoryNoiseChecksum) ||
            if (qualityStepCount == 0L) {
                outer.noiseInjectionStepCount != 0L ||
                    outer.structureGuidanceStepCount != 0L ||
                    outer.noiseInjectionChecksum != zeroQualityChecksum ||
                    outer.structureGuidanceChecksum != zeroQualityChecksum ||
                    outer.trajectoryNoiseChecksum != zeroQualityChecksum
            } else {
                !noiseEvidenceConsistent || !structureEvidenceConsistent ||
                    qualityActionCount < qualityStepCount ||
                    outer.trajectoryNoiseChecksum == zeroQualityChecksum
            }
        ) {
            invalid("UltraFix quality, seed, or checksum evidence is invalid.")
        }

        data class CommitEvidence(val sha256: String, val sizeBytes: Long, val atomic: Boolean)

        fun parseCommit(parent: JSONObject, layer: String): CommitEvidence {
            val sha256 = parent.exactString("outputSha256", layer)
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(sha256)) invalid("$layer output SHA-256 is malformed.")
            return CommitEvidence(
                sha256 = sha256,
                sizeBytes = parent.exactLong("outputSizeBytes", layer),
                atomic = parent.exactBoolean("outputAtomicCommit", layer)
            )
        }

        val outerCommit = parseCommit(execution, "outer")
        val nativeCommit = parseCommit(nativeEffective, "nativeEffective")
        if (outerCommit != nativeCommit || !outerCommit.atomic ||
            outerCommit.sizeBytes !in MIN_ULTRAFIX_OUTPUT_PNG_BYTES..MAX_ULTRAFIX_OUTPUT_PNG_BYTES
        ) {
            invalid("UltraFix output commit evidence is incomplete or conflicts across layers.")
        }
        outer.extended?.let { extended ->
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(extended.outputSha256) ||
                extended.outputSha256 != outerCommit.sha256 ||
                extended.outputBytes != outerCommit.sizeBytes ||
                !extended.outputAtomicCommit
            ) {
                invalid("Extended UltraFix output evidence conflicts with the committed output.")
            }
        }

        val item = data.optJSONObject(0)
            ?: invalid("UltraFix output data must contain one object.")
        val encoded = item.exactString("b64_json", "data[0]")
        val maxEncodedLength = ((MAX_ULTRAFIX_OUTPUT_PNG_BYTES + 2L) / 3L) * 4L
        if (encoded.isEmpty() || encoded.length.toLong() > maxEncodedLength ||
            encoded.length % 4 != 0
        ) {
            invalid("UltraFix output Base64 is malformed or exceeds the response limit.")
        }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            invalid("UltraFix output Base64 is malformed.")
        }
        if (Base64.getEncoder().encodeToString(decoded) != encoded) {
            invalid("UltraFix output Base64 is not canonical.")
        }
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(decoded)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (decoded.size.toLong() != outerCommit.sizeBytes ||
            actualSha256 != outerCommit.sha256 ||
            item.exactString("mime_type", "data[0]") != "image/png" ||
            item.imageGenerationApiRequiredPositiveLong("width") != requested.targetWidth.toLong() ||
            item.imageGenerationApiRequiredPositiveLong("height") != requested.targetHeight.toLong() ||
            decoded.size < PNG_SIGNATURE.size ||
            !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            invalid("UltraFix output evidence does not bind the exact returned PNG bytes.")
        }
    }

    internal fun validateQnnUltraFixEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject,
        data: JSONArray
    ) {
        val requested = request.ultraFix ?: return
        val nativeEffective = execution.getJSONObject("nativeEffective")
        fun invalid(message: String): Nothing =
            reject("invalid_ultrafix_execution_evidence", message)
        if (request.taskMode != ImageGenerationApiTaskMode.IMG2IMG ||
            request.imageCount != 1 || data.length() != 1
        ) invalid("QNN UltraFix execution must return exactly one img2img output.")

        fun JSONObject.requireExactKeys(expected: Set<String>, layer: String) {
            if (keys().asSequence().toSet() != expected) {
                invalid("$layer fields do not match the strict QNN UltraFix v2 schema.")
            }
        }
        fun JSONObject.exactLong(field: String, layer: String): Long {
            if (!has(field) || isNull(field)) invalid("$layer is missing $field.")
            val value = get(field)
            if (value !is Byte && value !is Short && value !is Int && value !is Long) {
                invalid("$layer $field must be an exact integer.")
            }
            return (value as Number).toLong()
        }
        fun JSONObject.exactBoolean(field: String, layer: String): Boolean {
            if (!has(field) || isNull(field) || get(field) !is Boolean) {
                invalid("$layer $field must be boolean.")
            }
            return getBoolean(field)
        }
        fun JSONObject.exactString(field: String, layer: String): String {
            if (!has(field) || isNull(field) || get(field) !is String) {
                invalid("$layer $field must be a string.")
            }
            return getString(field)
        }
        fun JSONObject.exactDouble(field: String, layer: String): Double {
            if (!has(field) || isNull(field) || get(field) !is Number) {
                invalid("$layer $field must be numeric.")
            }
            return (get(field) as Number).toDouble().also {
                if (!it.isFinite()) invalid("$layer $field must be finite.")
            }
        }
        data class Stage(
            val invocations: Long,
            val successes: Long,
            val tileInvocations: Long,
            val tileSuccesses: Long,
            val steps: Long
        )
        data class Evidence(
            val version: Long,
            val generationCompleted: Boolean,
            val cancelled: Boolean,
            val previewPublished: Boolean,
            val sourceWidth: Long,
            val sourceHeight: Long,
            val targetWidth: Long,
            val targetHeight: Long,
            val sourceFit: String,
            val sourceResizedWidth: Long,
            val sourceResizedHeight: Long,
            val sourceCropLeft: Long,
            val sourceCropTop: Long,
            val tileSize: Long,
            val overlap: Double,
            val tileCount: Long,
            val tilePlanSha256: String,
            val inversionSteps: Long,
            val refinementSteps: Long,
            val denoiseStepCount: Long,
            val sampleMethod: String,
            val nativeScheduler: String,
            val vaeEncode: Stage,
            val ddimInversion: Stage,
            val tiledUnetRefinement: Stage,
            val tiledVaeDecode: Stage,
            val encoderGraphExecutionCount: Long,
            val inversionPositiveGraphExecutionCount: Long,
            val refinementPositiveGraphExecutionCount: Long,
            val refinementNegativeGraphExecutionCount: Long,
            val decoderGraphExecutionCount: Long,
            val physicalDiffusionModelComputeCount: Long,
            val qualityStepEvaluationCount: Long,
            val noiseInjectionStepCount: Long,
            val noiseInjectionSeedFingerprint: String,
            val noiseInjectionChecksum: String,
            val structureGuidanceStepCount: Long,
            val structureGuidanceChecksum: String,
            val trajectoryNoiseChecksum: String,
            val outputSha256: String,
            val outputBytes: Long,
            val outputAtomicCommit: Boolean
        )
        val stageFields = setOf(
            "invocationCount", "successCount", "tileInvocationCount", "tileSuccessCount", "stepCount"
        )
        val evidenceFields = setOf(
            "version", "generationCompleted", "cancelled", "previewPublished",
            "sourceWidth", "sourceHeight", "targetWidth", "targetHeight", "sourceFit",
            "sourceResizedWidth", "sourceResizedHeight", "sourceCropLeft", "sourceCropTop",
            "tileSize", "overlap", "tileCount", "tilePlanSha256", "inversionSteps",
            "refinementSteps", "denoiseStepCount", "sampleMethod", "nativeScheduler",
            "vaeEncode", "ddimInversion", "tiledUnetRefinement", "tiledVaeDecode",
            "encoderGraphExecutionCount", "inversionPositiveGraphExecutionCount",
            "refinementPositiveGraphExecutionCount", "refinementNegativeGraphExecutionCount",
            "decoderGraphExecutionCount", "physicalDiffusionModelComputeCount",
            "qualityStepEvaluationCount", "noiseInjectionStepCount",
            "noiseInjectionSeedFingerprint", "noiseInjectionChecksum",
            "structureGuidanceStepCount", "structureGuidanceChecksum", "trajectoryNoiseChecksum",
            "outputSha256", "outputBytes", "outputAtomicCommit"
        )
        fun parseStage(parent: JSONObject, field: String, layer: String): Stage {
            val stage = parent.optJSONObject(field) ?: invalid("$layer is missing $field.")
            stage.requireExactKeys(stageFields, "$layer.$field")
            return Stage(
                invocations = stage.exactLong("invocationCount", "$layer.$field"),
                successes = stage.exactLong("successCount", "$layer.$field"),
                tileInvocations = stage.exactLong("tileInvocationCount", "$layer.$field"),
                tileSuccesses = stage.exactLong("tileSuccessCount", "$layer.$field"),
                steps = stage.exactLong("stepCount", "$layer.$field")
            )
        }
        fun parse(parent: JSONObject, layer: String): Evidence {
            val evidence = parent.optJSONObject("ultraFix")
                ?: invalid("$layer QNN UltraFix evidence is missing.")
            evidence.requireExactKeys(evidenceFields, "$layer.ultraFix")
            return Evidence(
                version = evidence.exactLong("version", "$layer.ultraFix"),
                generationCompleted = evidence.exactBoolean("generationCompleted", "$layer.ultraFix"),
                cancelled = evidence.exactBoolean("cancelled", "$layer.ultraFix"),
                previewPublished = evidence.exactBoolean("previewPublished", "$layer.ultraFix"),
                sourceWidth = evidence.exactLong("sourceWidth", "$layer.ultraFix"),
                sourceHeight = evidence.exactLong("sourceHeight", "$layer.ultraFix"),
                targetWidth = evidence.exactLong("targetWidth", "$layer.ultraFix"),
                targetHeight = evidence.exactLong("targetHeight", "$layer.ultraFix"),
                sourceFit = evidence.exactString("sourceFit", "$layer.ultraFix"),
                sourceResizedWidth = evidence.exactLong("sourceResizedWidth", "$layer.ultraFix"),
                sourceResizedHeight = evidence.exactLong("sourceResizedHeight", "$layer.ultraFix"),
                sourceCropLeft = evidence.exactLong("sourceCropLeft", "$layer.ultraFix"),
                sourceCropTop = evidence.exactLong("sourceCropTop", "$layer.ultraFix"),
                tileSize = evidence.exactLong("tileSize", "$layer.ultraFix"),
                overlap = evidence.exactDouble("overlap", "$layer.ultraFix"),
                tileCount = evidence.exactLong("tileCount", "$layer.ultraFix"),
                tilePlanSha256 = evidence.exactString("tilePlanSha256", "$layer.ultraFix"),
                inversionSteps = evidence.exactLong("inversionSteps", "$layer.ultraFix"),
                refinementSteps = evidence.exactLong("refinementSteps", "$layer.ultraFix"),
                denoiseStepCount = evidence.exactLong("denoiseStepCount", "$layer.ultraFix"),
                sampleMethod = evidence.exactString("sampleMethod", "$layer.ultraFix"),
                nativeScheduler = evidence.exactString("nativeScheduler", "$layer.ultraFix"),
                vaeEncode = parseStage(evidence, "vaeEncode", "$layer.ultraFix"),
                ddimInversion = parseStage(evidence, "ddimInversion", "$layer.ultraFix"),
                tiledUnetRefinement = parseStage(evidence, "tiledUnetRefinement", "$layer.ultraFix"),
                tiledVaeDecode = parseStage(evidence, "tiledVaeDecode", "$layer.ultraFix"),
                encoderGraphExecutionCount = evidence.exactLong(
                    "encoderGraphExecutionCount", "$layer.ultraFix"
                ),
                inversionPositiveGraphExecutionCount = evidence.exactLong(
                    "inversionPositiveGraphExecutionCount", "$layer.ultraFix"
                ),
                refinementPositiveGraphExecutionCount = evidence.exactLong(
                    "refinementPositiveGraphExecutionCount", "$layer.ultraFix"
                ),
                refinementNegativeGraphExecutionCount = evidence.exactLong(
                    "refinementNegativeGraphExecutionCount", "$layer.ultraFix"
                ),
                decoderGraphExecutionCount = evidence.exactLong(
                    "decoderGraphExecutionCount", "$layer.ultraFix"
                ),
                physicalDiffusionModelComputeCount = evidence.exactLong(
                    "physicalDiffusionModelComputeCount", "$layer.ultraFix"
                ),
                qualityStepEvaluationCount = evidence.exactLong(
                    "qualityStepEvaluationCount", "$layer.ultraFix"
                ),
                noiseInjectionStepCount = evidence.exactLong(
                    "noiseInjectionStepCount", "$layer.ultraFix"
                ),
                noiseInjectionSeedFingerprint = evidence.exactString(
                    "noiseInjectionSeedFingerprint", "$layer.ultraFix"
                ),
                noiseInjectionChecksum = evidence.exactString(
                    "noiseInjectionChecksum", "$layer.ultraFix"
                ),
                structureGuidanceStepCount = evidence.exactLong(
                    "structureGuidanceStepCount", "$layer.ultraFix"
                ),
                structureGuidanceChecksum = evidence.exactString(
                    "structureGuidanceChecksum", "$layer.ultraFix"
                ),
                trajectoryNoiseChecksum = evidence.exactString(
                    "trajectoryNoiseChecksum", "$layer.ultraFix"
                ),
                outputSha256 = evidence.exactString("outputSha256", "$layer.ultraFix"),
                outputBytes = evidence.exactLong("outputBytes", "$layer.ultraFix"),
                outputAtomicCommit = evidence.exactBoolean("outputAtomicCommit", "$layer.ultraFix")
            )
        }
        val outer = parse(execution, "outer")
        val inner = parse(nativeEffective, "nativeEffective")
        if (outer != inner) invalid("Outer and nativeEffective QNN UltraFix evidence do not match.")
        if (execution.exactString("strengthMechanism", "outer") != "ddim_inversion" ||
            nativeEffective.exactString("strengthMechanism", "nativeEffective") != "ddim_inversion"
        ) invalid("QNN UltraFix execution did not prove the DDIM inversion strength mechanism.")
        if (outer.version != QNN_ULTRAFIX_EXECUTION_EVIDENCE_VERSION ||
            !outer.generationCompleted || outer.cancelled || outer.previewPublished
        ) invalid("QNN UltraFix evidence is incomplete or has an unsupported version.")

        fun overlapBits(value: Double): String =
            value.toRawBits().toULong().toString(radix = 16).padStart(16, '0')
        fun qnnPlan(): Pair<Long, String> {
            val scale = 8
            val alignment = 8
            if (requested.targetWidth < requested.tileSize || requested.targetHeight < requested.tileSize ||
                requested.targetWidth % (scale * alignment) != 0 ||
                requested.targetHeight % (scale * alignment) != 0 ||
                requested.tileSize % (scale * alignment) != 0
            ) invalid("QNN UltraFix dimensions do not align to the fixed graph tile plan.")
            fun axis(extentPixels: Int): List<Int> {
                val extent = extentPixels / scale
                val tile = requested.tileSize / scale
                if (extent <= 0 || tile <= 0 || tile > extent) invalid("QNN UltraFix tile plan is empty.")
                if (extent == tile) return listOf(0)
                val rawStride = kotlin.math.floor(tile.toDouble() * (1.0 - requested.overlap)).toInt()
                val stride = maxOf(alignment, rawStride / alignment * alignment)
                val finalStart = extent - tile
                val positions = ArrayList<Int>()
                var cursor = 0
                while (true) {
                    positions += cursor
                    if (cursor == finalStart) return positions
                    val next = minOf(cursor + stride, finalStart) / alignment * alignment
                    if (next <= cursor) invalid("QNN UltraFix tile stride did not advance.")
                    cursor = next
                }
            }
            val xs = axis(requested.targetWidth)
            val ys = axis(requested.targetHeight)
            val tile = requested.tileSize / scale
            val descriptor = buildString {
                append("ultrafix-tile-plan-v2|target=")
                    .append(requested.targetWidth).append('x').append(requested.targetHeight)
                    .append("|tile=").append(requested.tileSize)
                    .append("|scale=").append(scale)
                    .append("|alignment=").append(alignment)
                    .append("|overlapBits=").append(overlapBits(requested.overlap))
                    .append("|tiles=").append(xs.size.toLong() * ys.size.toLong())
                ys.forEachIndexed { yi, y ->
                    xs.forEachIndexed { xi, x ->
                        val left = if (xi == 0) 0 else xs[xi - 1] + tile - x
                        val right = if (xi + 1 == xs.size) 0 else x + tile - xs[xi + 1]
                        val top = if (yi == 0) 0 else ys[yi - 1] + tile - y
                        val bottom = if (yi + 1 == ys.size) 0 else y + tile - ys[yi + 1]
                        if (left !in 0 until tile || right !in 0 until tile ||
                            top !in 0 until tile || bottom !in 0 until tile
                        ) invalid("QNN UltraFix tile overlap is invalid.")
                        append('|').append(x * scale).append(',').append(y * scale).append(',')
                            .append(x).append(',').append(y).append(',')
                            .append(left * scale).append(',').append(right * scale).append(',')
                            .append(top * scale).append(',').append(bottom * scale).append(',')
                            .append(left).append(',').append(right).append(',')
                            .append(top).append(',').append(bottom)
                    }
                }
            }
            return xs.size.toLong() * ys.size.toLong() to sha256Utf8(descriptor)
        }
        val (expectedTileCount, expectedTilePlanSha256) = qnnPlan()
        val expectedTargetWidth = requested.targetWidth.toLong()
        val expectedTargetHeight = requested.targetHeight.toLong()
        if (outer.sourceWidth <= 0L || outer.sourceHeight <= 0L ||
            outer.sourceWidth > expectedTargetWidth || outer.sourceHeight > expectedTargetHeight
        ) invalid("QNN UltraFix source geometry is invalid.")
        val fitByWidth = Math.multiplyExact(expectedTargetWidth, outer.sourceHeight) >=
            Math.multiplyExact(expectedTargetHeight, outer.sourceWidth)
        val resizedWidth = if (fitByWidth) expectedTargetWidth else
            Math.multiplyExact(outer.sourceWidth, expectedTargetHeight) / outer.sourceHeight
        val resizedHeight = if (fitByWidth) Math.multiplyExact(outer.sourceHeight, expectedTargetWidth) /
            outer.sourceWidth else expectedTargetHeight
        if (outer.targetWidth != expectedTargetWidth || outer.targetHeight != expectedTargetHeight ||
            outer.sourceFit != "cover_center" || outer.sourceResizedWidth != resizedWidth ||
            outer.sourceResizedHeight != resizedHeight ||
            outer.sourceCropLeft != (resizedWidth - expectedTargetWidth) / 2L ||
            outer.sourceCropTop != (resizedHeight - expectedTargetHeight) / 2L ||
            outer.tileSize != requested.tileSize.toLong() ||
            !imageGenerationApiNumbersMatch(outer.overlap, requested.overlap) || outer.tileCount != expectedTileCount ||
            outer.tilePlanSha256 != expectedTilePlanSha256 ||
            !ImageGenerationApiContract.SHA256_PATTERN.matches(outer.tilePlanSha256) ||
            outer.inversionSteps != requested.inversionSteps.toLong() ||
            outer.refinementSteps != requested.refinementSteps.toLong() ||
            outer.denoiseStepCount != requested.inversionSteps.toLong()
        ) invalid("QNN UltraFix evidence does not match the immutable request or tile plan.")

        fun stage(name: String, value: Stage, invocations: Long, tiles: Long, steps: Long) {
            if (value.invocations != invocations || value.successes != invocations ||
                value.tileInvocations != tiles || value.tileSuccesses != tiles || value.steps != steps
            ) invalid("$name QNN UltraFix stage evidence is incomplete.")
        }
        val tail = requested.inversionSteps.toLong()
        val inversionTiles = Math.multiplyExact(expectedTileCount, tail)
        val refinementPositive = inversionTiles
        val refinementNegative = if (nativeEffective.imageGenerationApiRequiredBoolean("useCfg")) inversionTiles else 0L
        val refinementTiles = Math.addExact(refinementPositive, refinementNegative)
        val physical = Math.addExact(inversionTiles, refinementTiles)
        stage("VAE encode", outer.vaeEncode, 1L, expectedTileCount, 1L)
        stage("DDIM inversion", outer.ddimInversion, tail, inversionTiles, tail)
        stage("tiled UNet refinement", outer.tiledUnetRefinement, tail, refinementTiles, tail)
        stage("tiled VAE decode", outer.tiledVaeDecode, 1L, expectedTileCount, 1L)
        if (outer.encoderGraphExecutionCount != expectedTileCount ||
            outer.inversionPositiveGraphExecutionCount != inversionTiles ||
            outer.refinementPositiveGraphExecutionCount != refinementPositive ||
            outer.refinementNegativeGraphExecutionCount != refinementNegative ||
            outer.decoderGraphExecutionCount != expectedTileCount ||
            outer.physicalDiffusionModelComputeCount != physical ||
            execution.imageGenerationApiRequiredBoolean("useCfg") != nativeEffective.imageGenerationApiRequiredBoolean("useCfg") ||
            execution.imageGenerationApiRequiredBoolean("useCfg") != (refinementNegative != 0L) ||
            nativeEffective.exactLong("positiveDiffusionModelComputeCount", "nativeEffective") !=
                inversionTiles + refinementPositive ||
            nativeEffective.exactLong("negativeDiffusionModelComputeCount", "nativeEffective") != refinementNegative ||
            nativeEffective.exactLong("auxiliaryDiffusionModelComputeCount", "nativeEffective") != 0L ||
            nativeEffective.exactLong("samplingPassCount", "nativeEffective") != 1L ||
            nativeEffective.exactLong("totalUnetExecutionCount", "nativeEffective") != physical
        ) invalid("QNN UltraFix logical and physical graph counts are inconsistent.")
        if (execution.has("actualDiffusionModelComputeCount") &&
            (execution.exactLong("actualDiffusionModelComputeCount", "outer") != physical ||
                execution.exactLong("actualPositiveDiffusionModelComputeCount", "outer") !=
                    inversionTiles + refinementPositive ||
                execution.exactLong("actualNegativeDiffusionModelComputeCount", "outer") != refinementNegative ||
                execution.exactLong("actualAuxiliaryDiffusionModelComputeCount", "outer") != 0L)
        ) invalid("QNN UltraFix outer physical counters are inconsistent.")

        val seed = execution.exactLong("seed", "outer")
        val quality = Math.max(tail - 1L, 0L)
        val zeroQualityChecksum = "0000000000000000"
        val noiseEvidenceConsistent =
            (outer.noiseInjectionStepCount == 0L) ==
                (outer.noiseInjectionChecksum == zeroQualityChecksum)
        val structureEvidenceConsistent =
            (outer.structureGuidanceStepCount == 0L) ==
                (outer.structureGuidanceChecksum == zeroQualityChecksum)
        val qualityActionCount = runCatching {
            Math.addExact(outer.noiseInjectionStepCount, outer.structureGuidanceStepCount)
        }.getOrDefault(-1L)
        if (seed !in 0L..0xffff_ffffL || nativeEffective.exactLong("seed", "nativeEffective") != seed ||
            outer.qualityStepEvaluationCount != quality || outer.noiseInjectionStepCount !in 0L..quality ||
            outer.structureGuidanceStepCount !in 0L..quality ||
            outer.noiseInjectionSeedFingerprint != ultrafixNoiseSeedFingerprint(seed, tail) ||
            !ImageGenerationApiContract.SHA256_PATTERN.matches(outer.noiseInjectionSeedFingerprint) ||
            !UINT64_HEX_PATTERN.matches(outer.noiseInjectionChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.structureGuidanceChecksum) ||
            !UINT64_HEX_PATTERN.matches(outer.trajectoryNoiseChecksum) ||
            if (quality == 0L) {
                outer.noiseInjectionStepCount != 0L || outer.structureGuidanceStepCount != 0L ||
                    outer.noiseInjectionChecksum != zeroQualityChecksum ||
                    outer.structureGuidanceChecksum != zeroQualityChecksum ||
                    outer.trajectoryNoiseChecksum != zeroQualityChecksum
            } else {
                !noiseEvidenceConsistent || !structureEvidenceConsistent ||
                    qualityActionCount < quality ||
                    outer.trajectoryNoiseChecksum == zeroQualityChecksum
            }
        ) invalid("QNN UltraFix quality, seed, or checksum evidence is invalid.")
        listOf(execution, nativeEffective).forEach { layer ->
            if (!layer.has("img2imgAddNoiseApplied") || layer.get("img2imgAddNoiseApplied") != false ||
                layer.exactString("img2imgNoiseChecksum", "QNN UltraFix") != "0000000000000000"
            ) invalid("QNN UltraFix must prove addNoise=false.")
        }

        data class Commit(val sha256: String, val bytes: Long, val atomic: Boolean)
        fun commit(parent: JSONObject, layer: String): Commit {
            val sha = parent.exactString("outputSha256", layer)
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(sha)) invalid("$layer output SHA-256 is malformed.")
            return Commit(
                sha256 = sha,
                bytes = parent.exactLong("outputSizeBytes", layer),
                atomic = parent.exactBoolean("outputAtomicCommit", layer)
            )
        }
        val outerCommit = commit(execution, "outer")
        val innerCommit = commit(nativeEffective, "nativeEffective")
        val nestedCommit = Commit(outer.outputSha256, outer.outputBytes, outer.outputAtomicCommit)
        if (outerCommit != innerCommit || outerCommit != nestedCommit || !outerCommit.atomic ||
            outerCommit.bytes !in MIN_ULTRAFIX_OUTPUT_PNG_BYTES..MAX_ULTRAFIX_OUTPUT_PNG_BYTES
        ) invalid("QNN UltraFix output commit evidence is incomplete or conflicts across layers.")
        val item = data.optJSONObject(0) ?: invalid("QNN UltraFix output data must contain one object.")
        val encoded = item.exactString("b64_json", "data[0]")
        val maxEncodedLength = ((MAX_ULTRAFIX_OUTPUT_PNG_BYTES + 2L) / 3L) * 4L
        if (encoded.isEmpty() || encoded.length.toLong() > maxEncodedLength ||
            encoded.length % 4 != 0
        ) invalid("QNN UltraFix output Base64 is malformed or exceeds the response limit.")
        val decoded = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
            invalid("QNN UltraFix output Base64 is malformed.")
        }
        if (Base64.getEncoder().encodeToString(decoded) != encoded ||
            decoded.size.toLong() != outerCommit.bytes ||
            item.exactString("mime_type", "data[0]") != "image/png" ||
            item.exactLong("width", "data[0]") != requested.targetWidth.toLong() ||
            item.exactLong("height", "data[0]") != requested.targetHeight.toLong()
        ) {
            invalid("QNN UltraFix output payload is not canonical image/png evidence.")
        }
        val actualSha = MessageDigest.getInstance("SHA-256").digest(decoded)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        if (actualSha != outerCommit.sha256 || decoded.size < PNG_SIGNATURE.size ||
            !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) ||
            item.exactString("mime_type", "data[0]") != "image/png"
        ) invalid("QNN UltraFix output evidence does not bind the exact returned PNG bytes.")
    }

    internal fun rejectPrivateInputPaths(evidence: JSONObject) {
        val keys = evidence.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val normalizedKey = key.lowercase().filter(Char::isLetterOrDigit)
            if ("path" in normalizedKey ||
                PRIVATE_DIRECTORY_KEYS.any(normalizedKey::contains)
            ) {
                reject(
                    "private_image_input_path_exposed",
                    "Image provider response exposed a worker-private path."
                )
            }
            rejectPrivatePathValue(evidence.opt(key))
        }
    }

    internal fun rejectPrivatePathValue(value: Any?) {
        when (value) {
            is JSONObject -> rejectPrivateInputPaths(value)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    rejectPrivatePathValue(value.opt(index))
                }
            }
            is String -> if (value.looksLikePrivatePath()) {
                reject(
                    "private_image_input_path_exposed",
                    "Image provider response exposed a worker-private path."
                )
            }
        }
    }

    internal fun validateImageData(
        data: JSONArray,
        execution: JSONObject,
        nativeEffective: JSONObject,
        expectedCount: Int,
        requireSeedEvidence: Boolean
    ) {
        if (data.length() != expectedCount) {
            reject(
                "invalid_image_provider_response",
                "Image provider returned ${data.length()} images; expected $expectedCount."
            )
        }
        val outputEvidence = execution.optJSONArray("responseOutputEvidence")
            ?: reject(
                "invalid_image_provider_response",
                "Image provider response is missing responseOutputEvidence."
            )
        if (outputEvidence.length() != data.length()) {
            reject(
                "invalid_image_provider_response",
                "Image response output evidence count does not match image data."
            )
        }
        val maxEncodedLength = ((MAX_IMAGE_OUTPUT_PNG_BYTES + 2L) / 3L) * 4L
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index)
                ?: reject("invalid_image_provider_response", "Image data item must be an object.")
            item.imageGenerationApiRejectUnknownFields(RESPONSE_IMAGE_DATA_FIELDS, "data[$index]")
            val evidence = outputEvidence.optJSONObject(index)
                ?: reject(
                    "invalid_image_provider_response",
                    "Image response output evidence item must be an object."
                )
            evidence.imageGenerationApiRejectUnknownFields(RESPONSE_OUTPUT_EVIDENCE_FIELDS, "responseOutputEvidence[$index]")
            if (evidence.imageGenerationApiRequiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image response output evidence indices must be contiguous.")
            }
            val encoded = item.imageGenerationApiRequiredNonBlankString("b64_json")
            if (encoded.length.toLong() > maxEncodedLength || encoded.length % 4 != 0) {
                reject("invalid_image_provider_response", "Image data Base64 is malformed or exceeds the response limit.")
            }
            val decoded = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                reject("invalid_image_provider_response", "Image data Base64 is malformed.")
            }
            if (Base64.getEncoder().encodeToString(decoded) != encoded ||
                decoded.size < PNG_SIGNATURE.size ||
                !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
            ) {
                reject("invalid_image_provider_response", "Image data is not canonical PNG Base64.")
            }
            val mimeType = item.imageGenerationApiRequiredNonBlankString("mime_type")
            val evidenceMimeType = evidence.imageGenerationApiRequiredNonBlankString("mimeType")
            if (mimeType != "image/png" || evidenceMimeType != mimeType) {
                reject("invalid_image_provider_response", "Image data MIME type must be bound to image/png evidence.")
            }
            val evidenceSize = evidence.imageGenerationApiRequiredPositiveLong("sizeBytes")
            if (evidenceSize > MAX_IMAGE_OUTPUT_PNG_BYTES || evidenceSize != decoded.size.toLong()) {
                reject("invalid_image_provider_response", "Image data byte length does not match output evidence.")
            }
            val expectedSha256 = evidence.imageGenerationApiRequiredNonBlankString("sha256")
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(decoded)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (!ImageGenerationApiContract.SHA256_PATTERN.matches(expectedSha256) || actualSha256 != expectedSha256) {
                reject("invalid_image_provider_response", "Image data SHA-256 does not match output evidence.")
            }
            val width = item.imageGenerationApiRequiredPositiveLong("width")
            val height = item.imageGenerationApiRequiredPositiveLong("height")
            if (width != nativeEffective.imageGenerationApiRequiredPositiveLong("width") ||
                height != nativeEffective.imageGenerationApiRequiredPositiveLong("height")
            ) {
                reject(
                    "invalid_image_provider_response",
                    "Image data dimensions do not match nativeEffective evidence."
                )
            }
            if (item.imageGenerationApiRequiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image data indices must be contiguous.")
            }
            if (requireSeedEvidence) {
                val nativeSeed = nativeEffective.imageGenerationApiRequiredInteger("seed", minimum = 0L)
                val expectedSeed = runCatching { Math.addExact(nativeSeed, index.toLong()) }
                    .getOrElse {
                        reject(
                            "invalid_image_provider_response",
                            "Native image output seed sequence overflowed."
                        )
                    }
                if (item.imageGenerationApiRequiredInteger("seed", minimum = 0L) != expectedSeed) {
                    reject(
                        "invalid_image_provider_response",
                        "Image data seed does not match native execution evidence."
                    )
                }
            }
        }
    }

}
