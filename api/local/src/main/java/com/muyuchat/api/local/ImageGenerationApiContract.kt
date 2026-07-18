package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

enum class ImageGenerationApiTaskMode(val wireName: String) {
    TEXT_TO_IMAGE("text_to_image"),
    IMG2IMG("img2img"),
    INPAINT("inpaint"),
    CONTROL("control"),
    EDIT("edit");

    companion object {
        fun parse(value: String): ImageGenerationApiTaskMode {
            val normalized = value.trim().lowercase().replace('-', '_')
            return entries.firstOrNull { it.wireName == normalized }
                ?: reject("invalid_task_mode", "Unsupported task_mode: $value")
        }
    }
}

data class ImageGenerationApiVaeTiling(
    val tileSize: Int,
    val overlap: Double
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tile_size", tileSize)
        .put("overlap", overlap)
}

data class ImageGenerationApiPreview(
    val interval: Int,
    val mode: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("interval", interval)
        .put("mode", mode)
}

data class ImageGenerationApiLora(
    val id: String,
    val multiplier: Double
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("multiplier", multiplier)
}

data class ImageGenerationApiRequest(
    val rawBody: String,
    val model: String?,
    val prompt: String,
    /** null = omitted; empty string = explicitly disable the profile default. */
    val negativePrompt: String?,
    val width: Int?,
    val height: Int?,
    val imageCount: Int,
    val responseFormat: String,
    val seed: Int?,
    val steps: Int?,
    val cfgScale: Double?,
    val sampler: String?,
    val taskMode: ImageGenerationApiTaskMode,
    val inputImage: String?,
    val maskImage: String?,
    val controlImage: String?,
    val strength: Double?,
    val controlStrength: Double?,
    val clipSkip: Int?,
    val loras: List<ImageGenerationApiLora>,
    val vaeTiling: ImageGenerationApiVaeTiling?,
    val preview: ImageGenerationApiPreview?
) {
    fun requestedControlsJson(): JSONObject = JSONObject().apply {
        model?.let { put("model", it) }
        put("negativePromptSpecified", negativePrompt != null)
        negativePrompt?.let { put("negativePrompt", it) }
        width?.let { put("width", it) }
        height?.let { put("height", it) }
        put("n", imageCount)
        put("responseFormat", responseFormat)
        seed?.let { put("seed", it) }
        steps?.let { put("steps", it) }
        cfgScale?.let { put("cfgScale", it) }
        sampler?.let { put("sampler", it) }
        put("taskMode", taskMode.wireName)
        put("inputImageSpecified", inputImage != null)
        put("maskImageSpecified", maskImage != null)
        put("controlImageSpecified", controlImage != null)
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
        clipSkip?.let { put("clipSkip", it) }
        put("loras", JSONArray().apply { loras.forEach { put(it.toJson()) } })
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }
}

data class ImageGenerationApiResponse(
    val rawBody: String,
    val requestId: String,
    val execution: JSONObject,
    val data: JSONArray
)

class ImageGenerationContractException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)

/** Structured failure raised by the app-owned image provider after request parsing succeeds. */
class ImageGenerationProviderException(
    code: String,
    val httpStatus: Int,
    override val message: String,
    val detailsJson: String = "{}",
    val retryAfterMs: Long = 0L
) : IllegalStateException(message) {
    val code: String = normalizeProviderErrorCode(code)

    init {
        require(httpStatus in 400..599) { "Image provider failures must use an HTTP error status." }
        require(retryAfterMs >= 0L) { "Image provider retryAfterMs must not be negative." }
    }

    companion object {
        fun fromWorkerFailure(code: String, message: String): ImageGenerationProviderException {
            val normalized = normalizeProviderErrorCode(code)
            return ImageGenerationProviderException(
                code = normalized,
                httpStatus = workerFailureHttpStatus(normalized),
                message = message
            )
        }

        private fun workerFailureHttpStatus(code: String): Int = when {
            code == "worker_busy" || code == "image_generation_busy" -> 409
            code == "qnn_sdxl_worker_timeout" || code.endsWith("_timeout") -> 504
            code in setOf("image_worker_unavailable", "worker_disconnected") -> 503
            code in setOf(
                "image_native_execution_contract_invalid",
                "execution_contract_mismatch",
                "invalid_image_worker_response",
                "invalid_request",
                "result_delivery_failed"
            ) -> 502
            code.startsWith("unsupported_") ||
                code.startsWith("lora_native_") ||
                code == "prompt_weighting_execution_unsupported" ||
                code.startsWith("invalid_image_input") ||
                code == "invalid_image_execution_profile" -> 422
            code.contains("cancel") -> 409
            else -> 500
        }
    }
}

object ImageGenerationApiContract {
    fun parseRequest(rawBody: String): ImageGenerationApiRequest {
        val root = try {
            JSONObject(rawBody)
        } catch (_: Throwable) {
            reject("invalid_json", "Image generation request body must be valid JSON.")
        }
        root.rejectUnknownFields(REQUEST_FIELDS, "image generation request")

        val prompt = root.requiredString("prompt", allowEmpty = false)
        val model = root.optionalString("model", allowEmpty = false)
        val negativePrompt = root.optionalText("negative_prompt", allowEmpty = true)
        val sampler = root.optionalString("sampler", allowEmpty = false)?.also { value ->
            if (canonicalSampler(value) !in SUPPORTED_SAMPLERS) {
                reject("unsupported_sampler", "sampler is not supported by the local image API.")
            }
        }
        val dimensions = root.optionalString("size", allowEmpty = false)?.let(::parseSize)
        val imageCount = root.optionalInt("n") ?: 1
        if (imageCount !in 1..MAX_IMAGE_COUNT) {
            reject(
                "unsupported_image_count",
                "Image generation n must be between 1 and $MAX_IMAGE_COUNT."
            )
        }
        val responseFormat = root.optionalString("response_format", allowEmpty = false)
            ?.lowercase()
            ?: "b64_json"
        if (responseFormat != "b64_json") {
            reject(
                "unsupported_response_format",
                "Image generation currently supports response_format=b64_json only."
            )
        }
        val steps = root.optionalInt("steps")
        if (steps != null && steps !in 1..1_000) {
            reject("invalid_steps", "steps must be between 1 and 1000 when specified.")
        }
        val cfgScale = root.optionalDouble("cfg_scale")?.also { value ->
            if (value !in 0.0..30.0) reject("invalid_cfg_scale", "cfg_scale must be between 0 and 30.")
        }
        val seed = root.optionalInt("seed")?.also { value ->
            if (value < -1) reject("invalid_seed", "seed must be -1 (random) or a non-negative integer.")
        }
        val inputImage = root.optionalAliasedText("input_image", "source_image")
            ?.also(::validateImageReference)
        val maskImage = root.optionalAliasedText("mask_image", "mask")
            ?.also(::validateImageReference)
        val controlImage = root.optionalText("control_image")
            ?.also(::validateImageReference)
        val explicitTask = root.optionalString("task_mode", allowEmpty = false)
            ?.let(ImageGenerationApiTaskMode::parse)
        val taskMode = explicitTask ?: when {
            maskImage != null -> ImageGenerationApiTaskMode.INPAINT
            controlImage != null -> ImageGenerationApiTaskMode.CONTROL
            inputImage != null -> ImageGenerationApiTaskMode.IMG2IMG
            else -> ImageGenerationApiTaskMode.TEXT_TO_IMAGE
        }
        val strength = root.optionalDouble("strength")?.also { value ->
            if (value <= 0.0 || value > 1.0) {
                reject("invalid_strength", "strength must be in the interval (0, 1].")
            }
        }
        val controlStrength = root.optionalDouble("control_strength")?.also { value ->
            if (value < 0.0 || value > 2.0) {
                reject("invalid_control_strength", "control_strength must be in the interval [0, 2].")
            }
        }
        validateInputShape(taskMode, inputImage, maskImage, controlImage, strength, controlStrength)

        val clipSkip = root.optionalInt("clip_skip")?.also { value ->
            if (value !in -1..32) {
                reject("invalid_clip_skip", "clip_skip must be -1 or between 0 and 32.")
            }
        }
        val loras = if (root.has("loras")) {
            val array = root.opt("loras") as? JSONArray
                ?: reject("invalid_lora", "loras must be an array of {id, multiplier} objects.")
            if (array.length() > MAX_LORA_COUNT) {
                reject("invalid_lora", "At most $MAX_LORA_COUNT LoRA adapters may be requested.")
            }
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                        ?: reject("invalid_lora", "Every loras item must be an object.")
                    item.rejectUnknownFields(LORA_FIELDS, "loras[$index]")
                    val id = item.requiredString("id", allowEmpty = false).lowercase()
                    if (!UUID_PATTERN.matches(id)) {
                        reject("invalid_lora", "loras[$index].id must be a UUID.")
                    }
                    val multiplier = item.requiredDouble("multiplier")
                    if (multiplier !in -4.0..4.0 || kotlin.math.abs(multiplier) < 0.01) {
                        reject(
                            "invalid_lora",
                            "loras[$index].multiplier must be in [-4, -0.01] or [0.01, 4]."
                        )
                    }
                    add(ImageGenerationApiLora(id, multiplier))
                }
            }.also { parsed ->
                if (parsed.map(ImageGenerationApiLora::id).distinct().size != parsed.size) {
                    reject("invalid_lora", "LoRA ids must be unique per request.")
                }
            }
        } else {
            emptyList()
        }
        val vaeTiling = root.optionalObject("vae_tiling")?.let(::parseVaeTiling)
        val preview = root.optionalObject("preview")?.let(::parsePreview)

        return ImageGenerationApiRequest(
            rawBody = rawBody,
            model = model,
            prompt = prompt,
            negativePrompt = negativePrompt,
            width = dimensions?.first,
            height = dimensions?.second,
            imageCount = imageCount,
            responseFormat = responseFormat,
            seed = seed,
            steps = steps,
            cfgScale = cfgScale,
            sampler = sampler,
            taskMode = taskMode,
            inputImage = inputImage,
            maskImage = maskImage,
            controlImage = controlImage,
            strength = strength,
            controlStrength = controlStrength,
            clipSkip = clipSkip,
            loras = loras,
            vaeTiling = vaeTiling,
            preview = preview
        )
    }

    internal fun parseResponse(expectedRequestId: String, rawBody: String): ImageGenerationApiResponse =
        parseResponse(expectedRequestId, expectedRequest = null, rawBody = rawBody)

    fun parseResponse(
        expectedRequestId: String,
        expectedRequest: ImageGenerationApiRequest?,
        rawBody: String
    ): ImageGenerationApiResponse {
        val root = try {
            JSONObject(rawBody)
        } catch (_: Throwable) {
            reject("invalid_image_provider_response", "Image provider returned invalid JSON.")
        }
        val requestId = root.optionalString("request_id", allowEmpty = false)
            ?: reject(
                "invalid_image_provider_response",
                "Image provider response is missing request_id."
            )
        if (requestId != expectedRequestId) {
            reject(
                "image_request_identity_mismatch",
                "Image provider response request_id does not match the dispatched request."
            )
        }
        expectedRequest?.model?.let { expectedModel ->
            val actualModel = root.optionalString("model", allowEmpty = false)
                ?: reject(
                    "image_model_identity_mismatch",
                    "Image provider response is missing the requested model identity."
                )
            if (actualModel != expectedModel) {
                reject(
                    "image_model_identity_mismatch",
                    "Image provider response model does not match the requested model."
                )
            }
        }
        val execution = root.optJSONObject("execution")
            ?: reject(
                "invalid_image_provider_response",
                "Image provider response is missing execution evidence."
            )
        val data = root.optJSONArray("data")
            ?: reject(
                "invalid_image_provider_response",
                "Image provider response is missing image data."
            )
        validateExecutionEvidence(execution)
        expectedRequest?.let {
            validateRequestedControlEvidence(it, execution)
            validateRequestedInputEvidence(it, execution)
            validateRequestedLoraEvidence(it, execution)
        }
        validateImageData(
            data = data,
            nativeEffective = execution.getJSONObject("nativeEffective"),
            expectedCount = expectedRequest?.imageCount ?: 1
        )
        return ImageGenerationApiResponse(
            rawBody = rawBody,
            requestId = requestId,
            execution = execution,
            data = data
        )
    }

    private fun parseSize(raw: String): Pair<Int, Int> {
        val match = SIZE_PATTERN.matchEntire(raw)
            ?: reject("invalid_image_size", "size must use WIDTHxHEIGHT with 2 to 4 digits per side.")
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        if (width !in 64..8_192 || height !in 64..8_192 ||
            width % 8 != 0 || height % 8 != 0 ||
            width.toLong() * height > 64L * 1024L * 1024L
        ) {
            reject(
                "invalid_image_size",
                "Image dimensions must be 8-pixel multiples between 64 and 8192 with at most 64M pixels."
            )
        }
        return width to height
    }

    private fun parseVaeTiling(json: JSONObject): ImageGenerationApiVaeTiling {
        json.rejectUnknownFields(VAE_TILING_FIELDS, "vae_tiling")
        val tileSize = json.requiredInt("tile_size")
        val overlap = json.requiredDouble("overlap")
        if (tileSize !in 64..4_096 || tileSize % 8 != 0) {
            reject("invalid_vae_tiling", "vae_tiling.tile_size must be an 8-pixel multiple from 64 to 4096.")
        }
        if (overlap < 0.0 || overlap > 0.5) {
            reject("invalid_vae_tiling", "vae_tiling.overlap must be in the interval [0, 0.5].")
        }
        return ImageGenerationApiVaeTiling(tileSize, overlap)
    }

    private fun parsePreview(json: JSONObject): ImageGenerationApiPreview {
        json.rejectUnknownFields(PREVIEW_FIELDS, "preview")
        val interval = json.requiredInt("interval")
        val mode = json.requiredString("mode", allowEmpty = false).lowercase()
        if (interval !in 1..100) {
            reject("invalid_preview", "preview.interval must be between 1 and 100.")
        }
        if (mode !in PREVIEW_MODES || mode == "none") {
            reject("invalid_preview", "preview.mode must be projection, tae, or vae.")
        }
        return ImageGenerationApiPreview(interval, mode)
    }

    private fun validateImageReference(reference: String) {
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

    private fun validateInputShape(
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

    private fun validateExecutionEvidence(execution: JSONObject) {
        rejectPrivateInputPaths(execution)
        if (!execution.requiredBoolean("nativeExecution")) {
            reject("invalid_image_execution_evidence", "Image execution must prove nativeExecution=true.")
        }
        if (execution.requiredBoolean("fallback")) {
            reject("invalid_image_execution_evidence", "Image execution must prove fallback=false.")
        }
        execution.requiredPositiveLong("nativeGenerationSequence")
        val nativeEffective = execution.optJSONObject("nativeEffective")
            ?: reject(
                "invalid_image_execution_evidence",
                "Image execution is missing strict nativeEffective evidence."
            )
        NATIVE_STRING_FIELDS.forEach { field ->
            val nativeValue = nativeEffective.requiredNonBlankString(field)
            if (execution.requiredNonBlankString(field) != nativeValue) {
                reject(
                    "invalid_image_execution_evidence",
                    "Outer and nativeEffective $field evidence do not match."
                )
            }
        }
        NATIVE_POSITIVE_INTEGER_FIELDS.forEach { field ->
            val nativeValue = nativeEffective.requiredPositiveLong(field)
            if (execution.requiredPositiveLong(field) != nativeValue) {
                reject(
                    "invalid_image_execution_evidence",
                    "Outer and nativeEffective $field evidence do not match."
                )
            }
        }
        val nativeSeed = nativeEffective.requiredInteger("seed", minimum = 0L)
        if (execution.requiredInteger("seed", minimum = 0L) != nativeSeed) {
            reject("invalid_image_execution_evidence", "Outer and nativeEffective seed evidence do not match.")
        }
        val nativeCfg = nativeEffective.requiredFiniteNumber("cfgScale")
        if (!numbersMatch(execution.requiredFiniteNumber("cfgScale"), nativeCfg)) {
            reject("invalid_image_execution_evidence", "Outer and nativeEffective cfgScale evidence do not match.")
        }
        val nativeVaeScale = nativeEffective.requiredPositiveNumber("vaeScalingFactor")
        if (!numbersMatch(execution.requiredPositiveNumber("vaeScalingFactor"), nativeVaeScale)) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective vaeScalingFactor evidence do not match."
            )
        }
        val useCfg = nativeEffective.requiredBoolean("useCfg")
        val unconditionalBranch = nativeEffective.requiredBoolean("unconditionalBranch")
        if (useCfg != unconditionalBranch) {
            reject(
                "invalid_image_execution_evidence",
                "nativeEffective unconditionalBranch must exactly match useCfg."
            )
        }
        if (execution.requiredBoolean("useCfg") != useCfg ||
            execution.requiredBoolean("unconditionalBranch") != unconditionalBranch
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective CFG branch evidence do not match."
            )
        }
        validatePromptWeightingEvidence(execution, nativeEffective)
        val nativeFallback = nativeEffective.requiredBoolean("fallback")
        if (nativeFallback) {
            reject("invalid_image_execution_evidence", "nativeEffective must prove fallback=false.")
        }
        if (execution.requiredBoolean("fallback") != nativeFallback) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective fallback evidence do not match."
            )
        }
        val runtime = execution.requiredNonBlankString("runtime")
        if (runtime == "QNN_HTP") {
            if (!execution.requiredBoolean("npuActive") ||
                !execution.requiredBoolean("qnnGraphExecution")
            ) {
                reject(
                    "invalid_image_execution_evidence",
                    "QNN image execution must prove npuActive and qnnGraphExecution."
                )
            }
        }
    }

    private fun validatePromptWeightingEvidence(
        execution: JSONObject,
        nativeEffective: JSONObject
    ) {
        val supported = nativeEffective.requiredBoolean("promptWeightingSupported")
        val applied = nativeEffective.requiredBoolean("promptWeightingApplied")
        val positiveCount = nativeEffective.requiredInteger("positiveWeightedTokenCount", minimum = 0L)
        val negativeCount = nativeEffective.requiredInteger("negativeWeightedTokenCount", minimum = 0L)
        val fingerprint = nativeEffective.requiredNonBlankString("promptWeightFingerprint")
        val tokenCount = nativeEffective.requiredPositiveLong("tokenCount")

        if (execution.requiredBoolean("promptWeightingSupported") != supported ||
            execution.requiredBoolean("promptWeightingApplied") != applied ||
            execution.requiredInteger("positiveWeightedTokenCount", minimum = 0L) != positiveCount ||
            execution.requiredInteger("negativeWeightedTokenCount", minimum = 0L) != negativeCount ||
            execution.requiredNonBlankString("promptWeightFingerprint") != fingerprint
        ) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective prompt-weighting evidence do not match."
            )
        }
        if (!SHA256_PATTERN.matches(fingerprint)) {
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

    private fun validateRequestedControlEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject
    ) {
        val nativeEffective = execution.getJSONObject("nativeEffective")
        request.width?.let { expected ->
            if (nativeEffective.requiredPositiveLong("width") != expected.toLong()) {
                reject("image_control_mismatch", "Native width does not match the requested size.")
            }
        }
        request.height?.let { expected ->
            if (nativeEffective.requiredPositiveLong("height") != expected.toLong()) {
                reject("image_control_mismatch", "Native height does not match the requested size.")
            }
        }
        request.steps?.let { expected ->
            if (nativeEffective.requiredPositiveLong("steps") != expected.toLong()) {
                reject("image_control_mismatch", "Native steps do not match the request.")
            }
        }
        request.cfgScale?.let { expected ->
            if (!numbersMatch(nativeEffective.requiredFiniteNumber("cfgScale"), expected)) {
                reject("image_control_mismatch", "Native cfg_scale does not match the request.")
            }
        }
        request.seed?.let { expected ->
            val actual = nativeEffective.requiredInteger("seed", minimum = 0L)
            if (expected >= 0 && actual != expected.toLong()) {
                reject("image_control_mismatch", "Native seed does not match the request.")
            }
        }
        request.sampler?.let { expected ->
            val actual = nativeEffective.requiredNonBlankString("scheduler")
            if (canonicalSampler(actual) != canonicalSampler(expected)) {
                reject("image_control_mismatch", "Native sampler does not match the request.")
            }
        }
    }

    private fun validateRequestedInputEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject
    ) {
        val nativeEffective = execution.getJSONObject("nativeEffective")
        val nativeBatchCount = nativeEffective.requiredInteger("batchCount", minimum = 1L)
        if (nativeBatchCount != request.imageCount.toLong() ||
            execution.requiredInteger("batchCount", minimum = 1L) != nativeBatchCount
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
        if (!evidence.requiredBoolean("nativeExecution") ||
            evidence.requiredNonBlankString("taskMode") != request.taskMode.wireName
        ) {
            reject(
                "invalid_image_input_execution_evidence",
                "Image input execution evidence does not match the requested task_mode."
            )
        }
        if (nativeEffective.requiredNonBlankString("taskMode") != request.taskMode.wireName) {
            reject(
                "invalid_image_input_execution_evidence",
                "nativeEffective taskMode does not match the requested task_mode."
            )
        }
        if (evidence.requiredInteger("batchCount", minimum = 1L) != nativeBatchCount) {
            reject(
                "invalid_image_input_execution_evidence",
                "Native batchCount does not match the requested image count."
            )
        }
        fun validateRole(name: String, requested: Boolean) {
            val count = evidence.requiredInteger("${name}ExecutionCount", minimum = 0L)
            val nativeCount = nativeEffective.requiredInteger("${name}ExecutionCount", minimum = 0L)
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
                val sha = item?.requiredNonBlankString("sha256")
                    ?: reject("invalid_image_input_execution_evidence", "$name sha256 evidence is missing.")
                if (!SHA256_PATTERN.matches(sha)) {
                    reject("invalid_image_input_execution_evidence", "$name sha256 evidence is invalid.")
                }
                val nativeSha = nativeEffective.requiredNonBlankString("${name}Sha256")
                if (nativeSha != sha || !SHA256_PATTERN.matches(nativeSha)) {
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
            if (!numbersMatch(nativeEffective.requiredFiniteNumber("strength"), expected) ||
                !numbersMatch(evidence.requiredFiniteNumber("strength"), expected)
            ) {
                reject("invalid_image_input_execution_evidence", "Native strength does not match the request.")
            }
        }
        request.controlStrength?.let { expected ->
            if (!numbersMatch(nativeEffective.requiredFiniteNumber("controlStrength"), expected) ||
                !numbersMatch(evidence.requiredFiniteNumber("controlStrength"), expected)
            ) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "Native control_strength does not match the request."
                )
            }
        }
    }

    private fun validateStableDiffusionBatchEvidence(
        execution: JSONObject,
        nativeEffective: JSONObject,
        batchCount: Long
    ) {
        if (nativeEffective.requiredNonBlankString("runtime") != "STABLE_DIFFUSION_CPP") return
        listOf("outputCount", "n", "samplingPassCount").forEach { field ->
            if (nativeEffective.requiredInteger(field, minimum = 1L) != batchCount ||
                execution.requiredInteger(field, minimum = 1L) != batchCount
            ) {
                reject(
                    "invalid_image_input_execution_evidence",
                    "stable-diffusion.cpp $field does not match the requested image count."
                )
            }
        }
        if (execution.requiredInteger("actualSamplingPassCount", minimum = 1L) != batchCount) {
            reject(
                "invalid_image_input_execution_evidence",
                "stable-diffusion.cpp physical sampling passes do not match the requested image count."
            )
        }
        val timetableCount = nativeEffective.requiredInteger("timetableCount", minimum = 1L)
        val unetExecutionCount = nativeEffective.requiredInteger("unetExecutionCount", minimum = 1L)
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
        if (execution.requiredInteger("actualSamplingStepCount", minimum = 1L) != expectedTotalSteps ||
            nativeEffective.requiredInteger("totalUnetExecutionCount", minimum = 1L) != expectedTotalCompute ||
            execution.requiredInteger("totalUnetExecutionCount", minimum = 1L) != expectedTotalCompute ||
            execution.requiredInteger("actualDiffusionModelComputeCount", minimum = 1L) != expectedTotalCompute
        ) {
            reject(
                "invalid_image_input_execution_evidence",
                "stable-diffusion.cpp total physical execution evidence does not match per-image evidence times n."
            )
        }
    }

    private fun validateRequestedLoraEvidence(
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
            val outerSha = outer.requiredNonBlankString("sha256")
            val nativeSha = native.requiredNonBlankString("sha256")
            if (outer.requiredNonBlankString("id") != expected.id ||
                native.requiredNonBlankString("id") != expected.id ||
                outerSha != nativeSha ||
                !SHA256_PATTERN.matches(outerSha) ||
                !numbersMatch(outer.requiredFiniteNumber("multiplier"), expected.multiplier) ||
                !numbersMatch(native.requiredFiniteNumber("multiplier"), expected.multiplier)
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
            val appliedTensorCount = counts.requiredInteger("appliedTensorCount", minimum = 0L)
            val tensorEvidenceInvalid = if (expectedCount == 0L) {
                appliedTensorCount != 0L
            } else {
                appliedTensorCount <= 0L
            }
            if (counts.requiredInteger("requestedCount", minimum = 0L) != expectedCount ||
                counts.requiredInteger("loadedCount", minimum = 0L) != expectedCount ||
                counts.requiredInteger("appliedCount", minimum = 0L) != expectedCount ||
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
                outerCounts.requiredInteger(field, minimum = 0L) !=
                    nativeCounts.requiredInteger(field, minimum = 0L)
            }
        ) {
            reject(
                "invalid_lora_execution_evidence",
                "Outer and nativeEffective LoRA count evidence do not match."
            )
        }
    }

    private fun rejectPrivateInputPaths(evidence: JSONObject) {
        val keys = evidence.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val normalizedKey = key.lowercase().filter(Char::isLetterOrDigit)
            if ("path" in normalizedKey || normalizedKey in PRIVATE_DIRECTORY_KEYS) {
                reject(
                    "private_image_input_path_exposed",
                    "Image provider response exposed a worker-private path."
                )
            }
            rejectPrivatePathValue(evidence.opt(key))
        }
    }

    private fun rejectPrivatePathValue(value: Any?) {
        when (value) {
            is JSONObject -> rejectPrivateInputPaths(value)
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    rejectPrivatePathValue(value.opt(index))
                }
            }
            is String -> if (value.looksLikePrivateExecutionPath()) {
                reject(
                    "private_image_input_path_exposed",
                    "Image provider response exposed a worker-private path."
                )
            }
        }
    }

    private fun validateImageData(
        data: JSONArray,
        nativeEffective: JSONObject,
        expectedCount: Int
    ) {
        if (data.length() != expectedCount) {
            reject(
                "invalid_image_provider_response",
                "Image provider returned ${data.length()} images; expected $expectedCount."
            )
        }
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index)
                ?: reject("invalid_image_provider_response", "Image data item must be an object.")
            item.requiredNonBlankString("b64_json")
            val mimeType = item.requiredNonBlankString("mime_type")
            if (!mimeType.startsWith("image/")) {
                reject("invalid_image_provider_response", "Image data MIME type must use image/*.")
            }
            val width = item.requiredPositiveLong("width")
            val height = item.requiredPositiveLong("height")
            if (width != nativeEffective.requiredPositiveLong("width") ||
                height != nativeEffective.requiredPositiveLong("height")
            ) {
                reject(
                    "invalid_image_provider_response",
                    "Image data dimensions do not match nativeEffective evidence."
                )
            }
            if (data.length() > 1 && item.requiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image data indices must be contiguous.")
            }
        }
    }

    private fun JSONObject.optionalAliasedText(primary: String, alias: String): String? {
        val primaryValue = optionalText(primary)
        val aliasValue = optionalText(alias)
        if (primaryValue != null && aliasValue != null && primaryValue != aliasValue) {
            reject("conflicting_image_input", "$primary and $alias must not specify different images.")
        }
        return primaryValue ?: aliasValue
    }

    private fun JSONObject.optionalText(name: String, allowEmpty: Boolean = false): String? {
        if (!has(name)) return null
        if (isNull(name)) reject("invalid_$name", "$name must be a string when specified.")
        val raw = get(name)
        if (raw !is String) reject("invalid_$name", "$name must be a string when specified.")
        return raw.trim().also { value ->
            if (!allowEmpty && value.isEmpty()) reject("invalid_$name", "$name must not be empty when specified.")
        }
    }

    private fun JSONObject.requiredString(name: String, allowEmpty: Boolean): String =
        optionalString(name, allowEmpty)
            ?: reject("invalid_$name", "$name is required and must be a string.")

    private fun JSONObject.optionalString(name: String, allowEmpty: Boolean): String? {
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

    private fun JSONObject.optionalObject(name: String): JSONObject? {
        if (!has(name)) return null
        if (isNull(name) || get(name) !is JSONObject) {
            reject("invalid_$name", "$name must be an object when specified.")
        }
        return getJSONObject(name)
    }

    private fun JSONObject.requiredInt(name: String): Int =
        optionalInt(name) ?: reject("invalid_$name", "$name is required and must be an integer.")

    private fun JSONObject.optionalInt(name: String): Int? {
        if (!has(name)) return null
        if (isNull(name)) reject("invalid_$name", "$name must be an integer when specified.")
        val raw = get(name)
        if (raw !is Number) reject("invalid_$name", "$name must be an integer when specified.")
        val value = raw.toExactLongOrNull()
        if (value == null || value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            reject("invalid_$name", "$name must be a finite 32-bit integer.")
        }
        return value.toInt()
    }

    private fun JSONObject.requiredDouble(name: String): Double =
        optionalDouble(name) ?: reject("invalid_$name", "$name is required and must be numeric.")

    private fun JSONObject.optionalDouble(name: String): Double? {
        if (!has(name)) return null
        if (isNull(name)) reject("invalid_$name", "$name must be numeric when specified.")
        val raw = get(name)
        if (raw !is Number) reject("invalid_$name", "$name must be numeric when specified.")
        val value = raw.toDouble()
        if (!value.isFinite()) reject("invalid_$name", "$name must be finite.")
        return value
    }

    private fun JSONObject.requiredNonBlankString(name: String): String =
        optionalString(name, allowEmpty = false)
            ?: reject(
                "invalid_image_execution_evidence",
                "Image execution field $name is required and must be a non-empty string."
            )

    private fun JSONObject.rejectUnknownFields(allowed: Set<String>, objectName: String) {
        val unknown = keys().asSequence().filterNot(allowed::contains).toList().sorted()
        if (unknown.isNotEmpty()) {
            reject(
                "unknown_image_request_field",
                "$objectName contains unsupported field(s): ${unknown.joinToString(", ")}"
            )
        }
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        if (!has(name) || isNull(name) || get(name) !is Boolean) {
            reject(
                "invalid_image_execution_evidence",
                "Image execution field $name is required and must be a boolean."
            )
        }
        return getBoolean(name)
    }

    private fun JSONObject.requiredInteger(name: String, minimum: Long): Long {
        if (!has(name) || isNull(name)) {
            reject("invalid_image_execution_evidence", "Image execution field $name is required.")
        }
        val raw = get(name)
        if (raw !is Number) {
            reject("invalid_image_execution_evidence", "Image execution field $name must be an integer.")
        }
        val longValue = raw.toExactLongOrNull()
        if (longValue == null || longValue < minimum) {
            reject(
                "invalid_image_execution_evidence",
                "Image execution field $name must be an integer no smaller than $minimum."
            )
        }
        return longValue
    }

    private fun Number.toExactLongOrNull(): Long? = runCatching {
        BigDecimal(toString()).toBigIntegerExact().longValueExact()
    }.getOrNull()

    private fun JSONObject.requiredPositiveLong(name: String): Long =
        requiredInteger(name, minimum = 1L)

    private fun JSONObject.requiredFiniteNumber(name: String): Double {
        if (!has(name) || isNull(name) || get(name) !is Number) {
            reject("invalid_image_execution_evidence", "Image execution field $name must be numeric.")
        }
        val value = getDouble(name)
        if (!value.isFinite()) {
            reject("invalid_image_execution_evidence", "Image execution field $name must be finite.")
        }
        return value
    }

    private fun JSONObject.requiredPositiveNumber(name: String): Double =
        requiredFiniteNumber(name).also { value ->
            if (value <= 0.0) {
                reject("invalid_image_execution_evidence", "Image execution field $name must be positive.")
            }
        }

    private fun numbersMatch(first: Double, second: Double): Boolean {
        if (!first.isFinite() || !second.isFinite()) return false
        val scale = maxOf(1.0, kotlin.math.abs(first), kotlin.math.abs(second))
        // Native bridges commonly round request doubles through C float fields.
        return kotlin.math.abs(first - second) <= 1e-6 * scale
    }

    private fun canonicalSampler(raw: String): String {
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

    private val SIZE_PATTERN = Regex("^(\\d{2,4})[xX](\\d{2,4})$")
    private val DATA_IMAGE_PATTERN = Regex(
        "^data:image/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/=\\r\\n]+)$",
        RegexOption.IGNORE_CASE
    )
    private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
    private val UUID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val PREVIEW_MODES = setOf("none", "projection", "tae", "vae")
    private val SUPPORTED_SAMPLERS = setOf(
        "euler",
        "euler_a",
        "ddim",
        "pndm",
        "dpmpp_2m",
        "lcm",
        "flow_match"
    )
    private val REQUEST_FIELDS = setOf(
        "model",
        "prompt",
        "negative_prompt",
        "sampler",
        "size",
        "n",
        "response_format",
        "steps",
        "cfg_scale",
        "seed",
        "task_mode",
        "input_image",
        "source_image",
        "mask_image",
        "mask",
        "control_image",
        "strength",
        "control_strength",
        "clip_skip",
        "loras",
        "vae_tiling",
        "preview"
    )
    private val VAE_TILING_FIELDS = setOf("tile_size", "overlap")
    private val PREVIEW_FIELDS = setOf("interval", "mode")
    private val LORA_FIELDS = setOf("id", "multiplier")
    private const val MAX_LORA_COUNT = 8
    private const val MAX_IMAGE_COUNT = 8
    private const val MAX_IMAGE_INPUT_BYTES = 32L * 1024L * 1024L
    private const val MAX_IMAGE_REFERENCE_CHARS = 48 * 1024 * 1024
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
}

private fun reject(code: String, message: String): Nothing =
    throw ImageGenerationContractException(code, message)

private fun normalizeProviderErrorCode(raw: String): String = raw
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9_.-]+"), "_")
    .trim('_')
    .take(80)
    .ifBlank { "image_generation_failed" }

private fun String.looksLikePrivateExecutionPath(): Boolean {
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
