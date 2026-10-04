package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import com.muyuchat.core.engine.compareUtf8Unsigned
import com.muyuchat.core.engine.isNativeChineseHanCodePoint
import com.muyuchat.core.engine.SAFE_ASCII_PROMPT_PUNCTUATION
import com.muyuchat.core.engine.isSafeAsciiDiffusionPromptCodePoint

fun imagePromptExecutionSha256(prompt: String, negativePrompt: String?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String?) {
        if (value == null) {
            digest.update(0.toByte())
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(1.toByte())
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    update(prompt)
    update(negativePrompt)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun imageTextualInversionBindingFingerprint(
    id: String,
    sha256: String,
    trigger: String,
    modelFingerprint: String,
    tokenizerFingerprint: String,
    profileId: String,
    profileRevision: Long,
    runtime: String
): String = sha256Utf8(
    listOf(
        "textual-inversion-binding-v1",
        id,
        sha256,
        trigger.lowercase(),
        modelFingerprint.lowercase(),
        tokenizerFingerprint.lowercase(),
        profileId,
        profileRevision.toString(),
        runtime
    ).joinToString("\u001f")
)

internal fun imageTextualInversionSelectionFingerprint(
    bindings: List<Pair<String, String>>
): String = sha256Utf8(
    (
        listOf("textual-inversion-selection-v1") +
            bindings.sortedBy { (trigger, _) -> trigger.lowercase() }.map { (_, fingerprint) ->
                fingerprint
            }
        ).joinToString("\u001f")
)

internal data class ImageTextualInversionExecutionAsset(
    val label: String,
    val sizeBytes: Long,
    val sha256: String
)

internal fun imageTextualInversionExecutionAssetsSha256(
    runtime: String,
    profilePromptFingerprint: String,
    assets: List<ImageTextualInversionExecutionAsset>
): String = sha256Utf8(
    (
        listOf(
            "textual-inversion-execution-assets-v1",
            runtime,
            profilePromptFingerprint
        ) + assets.flatMap { asset ->
            listOf(asset.label, asset.sizeBytes.toString(), asset.sha256)
        }
        ).joinToString("\u001f")
)

internal fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

internal fun ultrafixNoiseSeedFingerprint(seed: Long, denoiseStepCount: Long): String {
    require(denoiseStepCount > 0L)
    return sha256Utf8(
        "mca-ultrafix-quality-noise-v1|seed=${seed.toULong()}|steps=$denoiseStepCount"
    )
}

/**
 * Portable consistency checksum for app-produced prompt-processing metadata. This is intentionally
 * not a signature or remote attestation; the live app provider is the trust boundary, and persisted
 * translated history must be reverified before it can become executable again.
 */
fun imagePromptTranslationProofFingerprint(
    contractVersion: Int,
    originalPrompt: String,
    effectivePrompt: String,
    originalNegativePrompt: String?,
    effectiveNegativePrompt: String,
    negativePromptSource: String,
    translationPlanSha256: String,
    verificationReceiptSha256: String,
    translationPhaseSystemPromptSha256: String,
    verificationPhaseSystemPromptSha256: String,
    translatorRuntime: String,
    translatorModelSha256: String,
    promptLanguageBindingFingerprint: String
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun update(value: String?) {
        if (value == null) {
            digest.update(0.toByte())
            return
        }
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(1.toByte())
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        digest.update(bytes)
    }
    update("mca-local-image-prompt-translation-proof-v4")
    update(contractVersion.toString())
    update(originalPrompt)
    update(effectivePrompt)
    update(originalNegativePrompt)
    update(if (negativePromptSource == "USER") effectiveNegativePrompt else "")
    update(negativePromptSource)
    update(translationPlanSha256)
    update(verificationReceiptSha256)
    update(translationPhaseSystemPromptSha256)
    update(verificationPhaseSystemPromptSha256)
    update(translatorRuntime)
    update(translatorModelSha256)
    update(promptLanguageBindingFingerprint)
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

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

data class ImageGenerationApiUltraFix(
    val targetWidth: Int,
    val targetHeight: Int,
    val strength: Double,
    val inversionSteps: Int,
    val refinementSteps: Int,
    val tileSize: Int,
    val overlap: Double
)

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
    val preview: ImageGenerationApiPreview?,
    val textualInversionIds: List<String> = emptyList(),
    val ultraFix: ImageGenerationApiUltraFix? = null
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
        put("textualInversionIds", JSONArray(textualInversionIds))
        ultraFix?.let { value ->
            put("ultraFix", JSONObject()
                .put("targetWidth", value.targetWidth)
                .put("targetHeight", value.targetHeight)
                .put("strength", value.strength)
                .put("inversionSteps", value.inversionSteps)
                .put("refinementSteps", value.refinementSteps)
                .put("tileSize", value.tileSize)
                .put("overlap", value.overlap))
        }
        preview?.let { put("preview", it.toJson()) }
    }
}

data class ImageGenerationApiResponse(
    val rawBody: String,
    val requestId: String,
    val promptProcessing: ImageGenerationApiPromptProcessing?,
    val execution: JSONObject,
    val data: JSONArray
)

data class ImageGenerationApiPromptProcessing(
    val version: Int,
    val originalPrompt: String,
    val effectivePrompt: String,
    val originalNegativePrompt: String?,
    val effectiveNegativePrompt: String,
    val negativePromptSource: String,
    val method: String,
    val translationContractVersion: Int?,
    val imageProfileBindingFingerprint: String,
    val promptLanguageBindingFingerprint: String,
    val translatorModelId: String?,
    val translatorModelName: String?,
    val translatorRuntime: String?,
    val translatorModelSha256: String?,
    val translationPlanSha256: String?,
    val verificationReceiptSha256: String?,
    val translationPhaseSystemPromptSha256: String?,
    val verificationPhaseSystemPromptSha256: String?,
    val translationProofFingerprint: String?
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
            val normalizedMessage = message.trim().lowercase()
            if (
                normalizedMessage.contains("typed graph bindings") ||
                normalizedMessage.contains("qnn sdk headers") ||
                normalizedMessage.contains("sdk headers were not available")
            ) {
                return ImageGenerationProviderException(
                    code = "image_runtime_unavailable",
                    httpStatus = 503,
                    message = "The installed APK does not contain the typed QNN image runtime. " +
                        "Install a complete MCA build made with the QAIRT/QNN SDK, then retry."
                )
            }
            return ImageGenerationProviderException(
                code = normalized,
                httpStatus = workerFailureHttpStatus(normalized),
                message = message
            )
        }

        private fun workerFailureHttpStatus(code: String): Int = when {
            code == "worker_busy" || code == "image_generation_busy" ||
                code == "image_prompt_translation_busy" || code == "image_model_not_ready" -> 409
            code == "qnn_sdxl_worker_timeout" || code.endsWith("_timeout") -> 504
            code in setOf(
                "image_worker_unavailable",
                "worker_disconnected",
                "image_prompt_translation_unavailable"
            ) -> 503
            code in setOf(
                "image_native_execution_contract_invalid",
                "execution_contract_mismatch",
                "invalid_image_worker_response",
                "invalid_request",
                "result_delivery_failed",
                "image_prompt_translation_failed"
            ) -> 502
            code.startsWith("unsupported_") ||
                code.startsWith("lora_native_") ||
                code == "execution_contract_unsupported" ||
                code == "prompt_weighting_execution_unsupported" ||
                code.startsWith("invalid_image_input") ||
                code == "invalid_image_execution_profile" ||
                code == "invalid_image_prompt" ||
                code == "invalid_image_profile_prompt_language" ||
                code == "invalid_image_prompt_language_evidence" ||
                code == "image_prompt_unsupported_native_language" ||
                code == "image_prompt_requires_canonical_english_tags" ||
                code == "image_prompt_translation_input_too_large" ||
                code == "image_prompt_translation_input_too_complex" ||
                code == "image_prompt_translation_invalid" -> 422
            code.contains("cancel") -> 409
            else -> 500
        }
    }
}

object ImageGenerationApiContract {
    fun parseRequest(rawBody: String): ImageGenerationApiRequest {
        val root = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            reject("invalid_json", "Image generation request body must be valid JSON.")
        }
        root.imageGenerationApiRejectUnknownFields(REQUEST_FIELDS, "image generation request")

        val prompt = root.requiredString("prompt", allowEmpty = false)
        val model = root.imageGenerationApiOptionalString("model", allowEmpty = false)
        val negativePrompt = root.optionalText("negative_prompt", allowEmpty = true)
        val sampler = root.imageGenerationApiOptionalString("sampler", allowEmpty = false)?.also { value ->
            if (imageGenerationApiCanonicalSampler(value) !in SUPPORTED_SAMPLERS) {
                reject("unsupported_sampler", "sampler is not supported by the local image API.")
            }
        }
        val requestedDimensions = root.imageGenerationApiOptionalString("size", allowEmpty = false)?.let(::parseSize)
        val imageCount = root.optionalInt("n") ?: 1
        if (imageCount !in 1..MAX_IMAGE_COUNT) {
            reject(
                "unsupported_image_count",
                "Image generation n must be between 1 and $MAX_IMAGE_COUNT."
            )
        }
        val responseFormat = root.imageGenerationApiOptionalString("response_format", allowEmpty = false)
            ?.lowercase()
            ?: "b64_json"
        if (responseFormat != "b64_json") {
            reject(
                "unsupported_response_format",
                "Image generation currently supports response_format=b64_json only."
            )
        }
        val requestedSteps = root.optionalInt("steps")
        if (requestedSteps != null && requestedSteps !in 1..1_000) {
            reject("invalid_steps", "steps must be between 1 and 1000 when specified.")
        }
        val cfgScale = root.optionalDouble("cfg_scale")?.also { value ->
            if (value !in 0.0..30.0) reject("invalid_cfg_scale", "cfg_scale must be between 0 and 30.")
        }
        val seed = root.optionalInt("seed")?.also { value ->
            if (value < -1) reject("invalid_seed", "seed must be -1 (random) or a non-negative integer.")
        }
        val inputImage = root.optionalAliasedText("input_image", "source_image")
            ?.also(ImageGenerationApiEvidenceValidation::validateImageReference)
        val maskImage = root.optionalAliasedText("mask_image", "mask")
            ?.also(ImageGenerationApiEvidenceValidation::validateImageReference)
        val controlImage = root.optionalText("control_image")
            ?.also(ImageGenerationApiEvidenceValidation::validateImageReference)
        val explicitTask = root.imageGenerationApiOptionalString("task_mode", allowEmpty = false)
            ?.let(ImageGenerationApiTaskMode::parse)
        val taskMode = explicitTask ?: when {
            maskImage != null -> ImageGenerationApiTaskMode.INPAINT
            controlImage != null -> ImageGenerationApiTaskMode.CONTROL
            inputImage != null -> ImageGenerationApiTaskMode.IMG2IMG
            else -> ImageGenerationApiTaskMode.TEXT_TO_IMAGE
        }
        val requestedStrength = root.optionalDouble("strength")?.also { value ->
            if (value < 0.0 || value > 1.0) {
                reject("invalid_strength", "strength must be in the interval [0, 1].")
            }
        }
        val controlStrength = root.optionalDouble("control_strength")?.also { value ->
            if (value < 0.0 || value > 2.0) {
                reject("invalid_control_strength", "control_strength must be in the interval [0, 2].")
            }
        }
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
                    item.imageGenerationApiRejectUnknownFields(LORA_FIELDS, "loras[$index]")
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
        val requestedVaeTiling = root.optionalObject("vae_tiling")?.let(::parseVaeTiling)
        val textualInversionIds = parseTextualInversionIds(root)
        val ultraFix = root.optionalObject("ultrafix")?.let(::parseUltraFix)
        if (ultraFix != null) {
            if (taskMode != ImageGenerationApiTaskMode.IMG2IMG || inputImage == null ||
                maskImage != null || controlImage != null || controlStrength != null
            ) {
                reject(
                    "invalid_ultrafix",
                    "ultrafix requires img2img with exactly one input_image and no mask or control input."
                )
            }
            if (imageCount != 1) {
                reject("invalid_ultrafix", "ultrafix supports exactly one output per request.")
            }
            if (requestedDimensions != null && requestedDimensions !=
                (ultraFix.targetWidth to ultraFix.targetHeight)
            ) {
                reject("invalid_ultrafix", "size conflicts with the structured ultrafix target.")
            }
            if (requestedSteps != null && requestedSteps != ultraFix.refinementSteps) {
                reject("invalid_ultrafix", "steps conflicts with ultrafix.refinement_steps.")
            }
            if (requestedStrength != null &&
                kotlin.math.abs(requestedStrength - ultraFix.strength) > 1.0e-12
            ) {
                reject("invalid_ultrafix", "strength conflicts with ultrafix.strength.")
            }
            if (requestedVaeTiling != null &&
                (requestedVaeTiling.tileSize != ultraFix.tileSize ||
                    kotlin.math.abs(requestedVaeTiling.overlap - ultraFix.overlap) > 1.0e-12)
            ) {
                reject("invalid_ultrafix", "vae_tiling conflicts with the structured ultrafix tile plan.")
            }
        }
        val dimensions = ultraFix?.let { it.targetWidth to it.targetHeight } ?: requestedDimensions
        val steps = ultraFix?.refinementSteps ?: requestedSteps
        val strength = ultraFix?.strength ?: requestedStrength
        val vaeTiling = ultraFix?.let {
            ImageGenerationApiVaeTiling(tileSize = it.tileSize, overlap = it.overlap)
        } ?: requestedVaeTiling
        ImageGenerationApiEvidenceValidation.validateInputShape(taskMode, inputImage, maskImage, controlImage, strength, controlStrength)
        if (root.has("preview")) {
            reject("unsupported_preview", "Synchronous local image API requests do not support preview.")
        }

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
            preview = null,
            textualInversionIds = textualInversionIds,
            ultraFix = ultraFix
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
        } catch (_: Exception) {
            reject("invalid_image_provider_response", "Image provider returned invalid JSON.")
        }
        root.imageGenerationApiRejectUnknownFields(RESPONSE_FIELDS, "image generation response")
        val requestId = root.imageGenerationApiOptionalString("request_id", allowEmpty = false)
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
        expectedRequest?.let { request ->
            val actualModel = root.imageGenerationApiOptionalString("model", allowEmpty = false)
                ?: reject(
                    "image_model_identity_mismatch",
                    "Image provider response is missing the executed model identity."
                )
            request.model?.let { expectedModel ->
                if (actualModel != expectedModel) {
                    reject(
                        "image_model_identity_mismatch",
                        "Image provider response model does not match the requested model."
                    )
                }
            }
            if (root.imageGenerationApiRequiredInteger("created", minimum = 0L) <= 0L) {
                reject(
                    "invalid_image_provider_response",
                    "Image provider response created timestamp must be positive."
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
        val promptProcessing = root.optJSONObject("prompt_processing")?.let { evidence ->
            parsePromptProcessingEvidence(evidence, expectedRequest, execution)
        } ?: expectedRequest?.let {
            reject(
                "invalid_prompt_processing_evidence",
                "Image provider response is missing prompt_processing evidence."
            )
        }
        // Identify the Qwen-Image-2.1 profile before validating the generic execution proof.
        // Its request contract has intentionally more specific error classes (for example a
        // negative prompt is an image_control_mismatch).  Running the generic evidence checks
        // first can mask that actionable request error when the provider response is otherwise
        // malformed or incomplete.  The full native evidence validation still runs below after
        // the request has been classified.
        val qwenImage21 = ImageGenerationApiEvidenceValidation.isQwenImage21Execution(execution)
        if (qwenImage21) {
            expectedRequest?.let { ImageGenerationApiEvidenceValidation.validateQwenImage21RequestedControls(it, execution) }
        }
        ImageGenerationApiEvidenceValidation.validateExecutionEvidence(execution, promptProcessing)
        if (qwenImage21) {
            ImageGenerationApiEvidenceValidation.validateQwenImage21ImageData(
                data = data,
                execution = execution,
                nativeEffective = execution.getJSONObject("nativeEffective"),
                expectedCount = expectedRequest?.imageCount ?: 1
            )
        } else {
            expectedRequest?.let {
                ImageGenerationApiEvidenceValidation.validateRequestedControlEvidence(it, execution)
                ImageGenerationApiEvidenceValidation.validateRequestedInputEvidence(it, execution)
                ImageGenerationApiEvidenceValidation.validateRequestedLoraEvidence(it, execution)
                ImageGenerationApiEvidenceValidation.validateRequestedTextualInversionEvidence(it, execution, promptProcessing)
            }
            expectedRequest?.let { request ->
                ImageGenerationApiEvidenceValidation.validateRequestedUltraFixEvidence(request, execution, data)
            }
            ImageGenerationApiEvidenceValidation.validateImageData(
                data = data,
                execution = execution,
                nativeEffective = execution.getJSONObject("nativeEffective"),
                expectedCount = expectedRequest?.imageCount ?: 1,
                requireSeedEvidence = expectedRequest != null
            )
        }
        return ImageGenerationApiResponse(
            rawBody = rawBody,
            requestId = requestId,
            promptProcessing = promptProcessing,
            execution = execution,
            data = data
        )
    }

    private fun parsePromptProcessingEvidence(
        json: JSONObject,
        expectedRequest: ImageGenerationApiRequest?,
        execution: JSONObject
    ): ImageGenerationApiPromptProcessing {
        val unknown = json.keys().asSequence()
            .filterNot(PROMPT_PROCESSING_FIELDS::contains)
            .toList()
            .sorted()
        if (unknown.isNotEmpty()) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing contains unsupported field(s): ${unknown.joinToString(", ")}."
            )
        }
        val version = json.promptEvidenceRequiredInt("version")
        if (version != PROMPT_PROCESSING_VERSION) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing version must be $PROMPT_PROCESSING_VERSION."
            )
        }
        val originalPrompt = json.promptEvidenceRequiredString("originalPrompt", allowEmpty = false)
        val effectivePrompt = json.promptEvidenceRequiredString("effectivePrompt", allowEmpty = false)
        val originalNegativePrompt = json.promptEvidenceRequiredNullableString("originalNegativePrompt")
        val effectiveNegativePrompt =
            json.promptEvidenceRequiredString("effectiveNegativePrompt", allowEmpty = true)
        val negativePromptSource =
            json.promptEvidenceRequiredString("negativePromptSource", allowEmpty = false)
        val method = json.promptEvidenceRequiredString("method", allowEmpty = false)
        val translationContractVersion = if (json.has("translationContractVersion")) {
            json.promptEvidenceRequiredInt("translationContractVersion")
        } else {
            null
        }
        val imageProfileBindingFingerprint =
            json.promptEvidenceRequiredString("imageProfileBindingFingerprint", allowEmpty = false)
        val promptLanguageBindingFingerprint =
            json.promptEvidenceRequiredString("promptLanguageBindingFingerprint", allowEmpty = false)
        val translatorModelId = json.promptEvidenceOptionalString("translatorModelId")
        val translatorModelName = json.promptEvidenceOptionalString("translatorModelName")
        val translatorRuntime = json.promptEvidenceOptionalString("translatorRuntime")
        val translatorModelSha256 = json.promptEvidenceOptionalString("translatorModelSha256")
        val translationPlanSha256 = json.promptEvidenceOptionalString("translationPlanSha256")
        val verificationReceiptSha256 =
            json.promptEvidenceOptionalString("verificationReceiptSha256")
        val translationPhaseSystemPromptSha256 =
            json.promptEvidenceOptionalString("translationPhaseSystemPromptSha256")
        val verificationPhaseSystemPromptSha256 =
            json.promptEvidenceOptionalString("verificationPhaseSystemPromptSha256")
        val translationProofFingerprint =
            json.promptEvidenceOptionalString("translationProofFingerprint")

        if (!SHA256_PATTERN.matches(imageProfileBindingFingerprint) ||
            !SHA256_PATTERN.matches(promptLanguageBindingFingerprint)
        ) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing profile fingerprints must be lowercase SHA-256 values."
            )
        }
        if (originalPrompt.length > MAX_PROMPT_PROCESSING_ORIGINAL_CHARS ||
            originalNegativePrompt?.length?.let { it > MAX_PROMPT_PROCESSING_ORIGINAL_CHARS } == true ||
            effectivePrompt.length > MAX_PROMPT_PROCESSING_EFFECTIVE_CHARS ||
            effectiveNegativePrompt.length > MAX_PROMPT_PROCESSING_EFFECTIVE_CHARS
        ) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing contains a prompt that exceeds the verified execution limits."
            )
        }
        expectedRequest?.let { request ->
            if (originalPrompt != request.prompt || originalNegativePrompt != request.negativePrompt) {
                reject(
                    "prompt_processing_request_mismatch",
                    "prompt_processing original prompts do not match the authenticated request."
                )
            }
        }

        val executedProfileFingerprint = execution.promptEvidenceExecutionString(
            "imageProfileBindingFingerprint"
        )
        val executedPromptLanguageBindingFingerprint = execution.promptEvidenceExecutionString(
            "promptLanguageBindingFingerprint"
        )
        val executedLanguageCapability = execution.promptEvidenceExecutionString(
            "textEncoderLanguageCapability"
        )
        val executedPromptSha256 = execution.promptEvidenceExecutionString(
            "promptExecutionSha256"
        )
        // promptExecutionSha256 binds the provider's final effective strings. Native graph and
        // conditioning execution are proven separately by validateExecutionEvidence below; this
        // provider binding must not be interpreted as a native-origin prompt echo.
        if (!SHA256_PATTERN.matches(executedProfileFingerprint) ||
            executedProfileFingerprint != imageProfileBindingFingerprint ||
            !SHA256_PATTERN.matches(executedPromptLanguageBindingFingerprint) ||
            executedPromptLanguageBindingFingerprint != promptLanguageBindingFingerprint ||
            executedPromptSha256 != imagePromptExecutionSha256(
                effectivePrompt,
                effectiveNegativePrompt
            )
        ) {
            reject(
                "prompt_processing_execution_mismatch",
                "prompt_processing is not bound to the profile and prompts executed by the image worker."
            )
        }
        if (executedLanguageCapability !in PROMPT_LANGUAGE_CAPABILITIES) {
            reject(
                "invalid_prompt_processing_evidence",
                "Image execution reported an unsupported text encoder language capability."
            )
        }

        when (negativePromptSource) {
            NEGATIVE_PROMPT_SOURCE_USER -> if (originalNegativePrompt == null) {
                reject(
                    "invalid_prompt_processing_evidence",
                    "A user negative prompt source requires an original negative prompt."
                )
            }

            NEGATIVE_PROMPT_SOURCE_MODEL_DEFAULT -> if (originalNegativePrompt != null) {
                reject(
                    "invalid_prompt_processing_evidence",
                    "A model-default negative prompt source cannot replace a user value."
                )
            }

            NEGATIVE_PROMPT_SOURCE_EMPTY -> if (
                originalNegativePrompt != null || effectiveNegativePrompt.isNotEmpty()
            ) {
                reject(
                    "invalid_prompt_processing_evidence",
                    "An empty negative prompt source must execute an empty negative prompt."
                )
            }

            else -> reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing negativePromptSource is unsupported."
            )
        }

        val sourceContainsHan = originalPrompt.containsHanScript() ||
            originalNegativePrompt?.containsHanScript() == true
        // A profile default negative prompt is native conditioning input even when the caller did
        // not submit a negative prompt. Method evidence must therefore describe the final pair
        // actually encoded, rather than only the user-originated source strings.
        val effectivePairRequiresNativeMultilingualEncoding =
            !effectivePrompt.isSafeAsciiDiffusionPrompt() ||
                !effectiveNegativePrompt.isSafeAsciiDiffusionPrompt()
        val effectivePairUsesSupportedNativeChineseHanGrammar =
            effectivePrompt.isSupportedNativeChineseHanDiffusionPrompt() &&
                effectiveNegativePrompt.isSupportedNativeChineseHanDiffusionPrompt()
        // These values remain parseable in persisted app history so older records stay readable,
        // but a current loopback response must never re-admit either deprecated execution path.
        // New requests use canonical English tags or evidence-bound native multilingual encoding.
        if (method == PROMPT_METHOD_LOCAL_LLM ||
            method == PROMPT_METHOD_DIRECT_UTF8_PASSTHROUGH
        ) {
            reject(
                "unsupported_legacy_prompt_processing_method",
                "Legacy prompt translation and UTF-8 pass-through execution evidence cannot be accepted for a current image response."
            )
        }
        when (method) {
            PROMPT_METHOD_OFFLINE_HY_MT2 -> {
                if ((!sourceContainsHan &&
                        negativePromptSource != NEGATIVE_PROMPT_SOURCE_MODEL_DEFAULT) ||
                    executedLanguageCapability != PROMPT_LANGUAGE_ENGLISH_DOMINANT ||
                    translationContractVersion != OFFLINE_PROMPT_TRANSLATION_CONTRACT_VERSION ||
                    effectivePrompt.containsHanScript() ||
                    effectiveNegativePrompt.containsHanScript() ||
                    !effectivePrompt.isSafeAsciiDiffusionPrompt() ||
                    !effectiveNegativePrompt.isSafeAsciiDiffusionPrompt() ||
                    (!originalPrompt.containsHanScript() && effectivePrompt != originalPrompt) ||
                    (negativePromptSource == NEGATIVE_PROMPT_SOURCE_USER &&
                        originalNegativePrompt != null &&
                        !originalNegativePrompt.containsHanScript() &&
                        effectiveNegativePrompt != originalNegativePrompt) ||
                    translatorModelId != OFFLINE_PROMPT_TRANSLATOR_MODEL_ID ||
                    translatorModelName != OFFLINE_PROMPT_TRANSLATOR_MODEL_NAME ||
                    translatorRuntime != OFFLINE_PROMPT_TRANSLATOR_RUNTIME ||
                    translatorModelSha256 != OFFLINE_PROMPT_TRANSLATOR_MODEL_SHA256 ||
                    translationPlanSha256?.let(SHA256_PATTERN::matches) != true ||
                    verificationReceiptSha256?.let(SHA256_PATTERN::matches) != true ||
                    translationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) != true ||
                    verificationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) != true ||
                    translationProofFingerprint?.let(SHA256_PATTERN::matches) != true ||
                    translationProofFingerprint != imagePromptTranslationProofFingerprint(
                        contractVersion = translationContractVersion,
                        originalPrompt = originalPrompt,
                        effectivePrompt = effectivePrompt,
                        originalNegativePrompt = originalNegativePrompt,
                        effectiveNegativePrompt = effectiveNegativePrompt,
                        negativePromptSource = negativePromptSource,
                        translationPlanSha256 = translationPlanSha256.orEmpty(),
                        verificationReceiptSha256 = verificationReceiptSha256.orEmpty(),
                        translationPhaseSystemPromptSha256 =
                            translationPhaseSystemPromptSha256.orEmpty(),
                        verificationPhaseSystemPromptSha256 =
                            verificationPhaseSystemPromptSha256.orEmpty(),
                        translatorRuntime = translatorRuntime.orEmpty(),
                        translatorModelSha256 = translatorModelSha256.orEmpty(),
                        promptLanguageBindingFingerprint = promptLanguageBindingFingerprint
                    )
                ) {
                    reject(
                        "invalid_prompt_processing_evidence",
                        "Offline Hy-MT2 prompt_processing evidence is incomplete or does not match the pinned translator contract."
                    )
                }
            }

            PROMPT_METHOD_LOCAL_LLM -> {
                if (!sourceContainsHan ||
                    executedLanguageCapability != PROMPT_LANGUAGE_ENGLISH_DOMINANT ||
                    translationContractVersion != PROMPT_TRANSLATION_CONTRACT_VERSION ||
                    effectivePrompt.containsHanScript() ||
                    effectiveNegativePrompt.containsHanScript() ||
                    // Keep the retained legacy parser branch on the same diffusion grammar as
                    // current executions. The branch is rejected above, but accepting broader
                    // visible ASCII here would make any future migration accidentally weaken
                    // the evidence boundary.
                    !effectivePrompt.isSafeAsciiDiffusionPrompt() ||
                    !effectiveNegativePrompt.isSafeAsciiDiffusionPrompt() ||
                    (!originalPrompt.containsHanScript() && effectivePrompt != originalPrompt) ||
                    (negativePromptSource == NEGATIVE_PROMPT_SOURCE_USER &&
                        originalNegativePrompt != null &&
                        !originalNegativePrompt.containsHanScript() &&
                        effectiveNegativePrompt != originalNegativePrompt) ||
                    translatorModelId.isNullOrBlank() ||
                    translatorModelName.isNullOrBlank() ||
                    translatorRuntime.isNullOrBlank() ||
                    translatorModelSha256?.let(SHA256_PATTERN::matches) != true ||
                    translationPlanSha256?.let(SHA256_PATTERN::matches) != true ||
                    verificationReceiptSha256?.let(SHA256_PATTERN::matches) != true ||
                    translationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) != true ||
                    verificationPhaseSystemPromptSha256?.let(SHA256_PATTERN::matches) != true ||
                    translationProofFingerprint?.let(SHA256_PATTERN::matches) != true ||
                    translationProofFingerprint != imagePromptTranslationProofFingerprint(
                        contractVersion = translationContractVersion,
                        originalPrompt = originalPrompt,
                        effectivePrompt = effectivePrompt,
                        originalNegativePrompt = originalNegativePrompt,
                        effectiveNegativePrompt = effectiveNegativePrompt,
                        negativePromptSource = negativePromptSource,
                        translationPlanSha256 = translationPlanSha256.orEmpty(),
                        verificationReceiptSha256 = verificationReceiptSha256.orEmpty(),
                        translationPhaseSystemPromptSha256 =
                            translationPhaseSystemPromptSha256.orEmpty(),
                        verificationPhaseSystemPromptSha256 =
                            verificationPhaseSystemPromptSha256.orEmpty(),
                        translatorRuntime = translatorRuntime.orEmpty(),
                        translatorModelSha256 = translatorModelSha256.orEmpty(),
                        promptLanguageBindingFingerprint = promptLanguageBindingFingerprint
                    )
                ) {
                    reject(
                        "invalid_prompt_processing_evidence",
                        "Translated prompt_processing evidence is incomplete or violates contract version $PROMPT_TRANSLATION_CONTRACT_VERSION."
                    )
                }
            }

            PROMPT_METHOD_DIRECT,
            PROMPT_METHOD_NATIVE_MULTILINGUAL,
            PROMPT_METHOD_DIRECT_UTF8_PASSTHROUGH -> {
                val methodMatchesSource = when (method) {
                    PROMPT_METHOD_DIRECT -> !effectivePairRequiresNativeMultilingualEncoding
                    PROMPT_METHOD_NATIVE_MULTILINGUAL ->
                        effectivePairRequiresNativeMultilingualEncoding &&
                            effectivePairUsesSupportedNativeChineseHanGrammar
                    PROMPT_METHOD_DIRECT_UTF8_PASSTHROUGH -> sourceContainsHan
                    else -> false
                }
                if (!methodMatchesSource ||
                    (method == PROMPT_METHOD_NATIVE_MULTILINGUAL &&
                        executedLanguageCapability != PROMPT_LANGUAGE_NATIVE_MULTILINGUAL) ||
                    (method == PROMPT_METHOD_DIRECT_UTF8_PASSTHROUGH &&
                        executedLanguageCapability != PROMPT_LANGUAGE_ENGLISH_DOMINANT) ||
                    effectivePrompt != originalPrompt ||
                    (negativePromptSource == NEGATIVE_PROMPT_SOURCE_USER &&
                        effectiveNegativePrompt != originalNegativePrompt) ||
                    translationContractVersion != null ||
                    translatorModelId != null ||
                    translatorModelName != null ||
                    translatorRuntime != null ||
                    translatorModelSha256 != null ||
                    translationPlanSha256 != null ||
                    verificationReceiptSha256 != null ||
                    translationPhaseSystemPromptSha256 != null ||
                    verificationPhaseSystemPromptSha256 != null ||
                    translationProofFingerprint != null
                ) {
                    reject(
                        "invalid_prompt_processing_evidence",
                        "Pass-through prompt_processing evidence does not match its declared method."
                    )
                }
            }

            else -> reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing method is unsupported."
            )
        }

        return ImageGenerationApiPromptProcessing(
            version = version,
            originalPrompt = originalPrompt,
            effectivePrompt = effectivePrompt,
            originalNegativePrompt = originalNegativePrompt,
            effectiveNegativePrompt = effectiveNegativePrompt,
            negativePromptSource = negativePromptSource,
            method = method,
            translationContractVersion = translationContractVersion,
            imageProfileBindingFingerprint = imageProfileBindingFingerprint,
            promptLanguageBindingFingerprint = promptLanguageBindingFingerprint,
            translatorModelId = translatorModelId,
            translatorModelName = translatorModelName,
            translatorRuntime = translatorRuntime,
            translatorModelSha256 = translatorModelSha256,
            translationPlanSha256 = translationPlanSha256,
            verificationReceiptSha256 = verificationReceiptSha256,
            translationPhaseSystemPromptSha256 = translationPhaseSystemPromptSha256,
            verificationPhaseSystemPromptSha256 = verificationPhaseSystemPromptSha256,
            translationProofFingerprint = translationProofFingerprint
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
        json.imageGenerationApiRejectUnknownFields(VAE_TILING_FIELDS, "vae_tiling")
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
        json.imageGenerationApiRejectUnknownFields(PREVIEW_FIELDS, "preview")
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

    private fun parseTextualInversionIds(root: JSONObject): List<String> {
        if (!root.has("textual_inversion_ids")) return emptyList()
        val values = root.opt("textual_inversion_ids") as? JSONArray
            ?: reject("invalid_textual_inversion", "textual_inversion_ids must be an array of UUIDs.")
        if (values.length() > 8) {
            reject("invalid_textual_inversion", "At most 8 textual inversion ids are allowed.")
        }
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.opt(index) as? String
                    ?: reject("invalid_textual_inversion", "textual_inversion_ids[$index] must be a UUID.")
                val id = value.lowercase()
                if (!UUID_PATTERN.matches(id)) {
                    reject("invalid_textual_inversion", "textual_inversion_ids[$index] must be a UUID.")
                }
                add(id)
            }
        }.also { ids ->
            if (ids.distinct().size != ids.size) {
                reject("invalid_textual_inversion", "textual inversion ids must be unique.")
            }
        }
    }

    private fun parseUltraFix(json: JSONObject): ImageGenerationApiUltraFix {
        json.imageGenerationApiRejectUnknownFields(ULTRAFIX_FIELDS, "ultrafix")
        val targetWidth = json.requiredInt("target_width")
        val targetHeight = json.requiredInt("target_height")
        val strength = json.requiredDouble("strength")
        val inversionSteps = json.requiredInt("inversion_steps")
        val refinementSteps = json.requiredInt("refinement_steps")
        val tileSize = json.requiredInt("tile_size")
        val overlap = json.requiredDouble("overlap")
        val expectedInversionSteps = if (refinementSteps > 0 && strength.isFinite()) {
            val wireStrength = strength.toFloat()
            refinementSteps - (refinementSteps.toFloat() * (1.0f - wireStrength))
                .toInt()
                .coerceIn(0, refinementSteps - 1)
        } else {
            -1
        }
        if (targetWidth !in 64..8192 || targetWidth % 8 != 0 ||
            targetHeight !in 64..8192 || targetHeight % 8 != 0 ||
            targetWidth.toLong() * targetHeight.toLong() > 64L * 1024L * 1024L ||
            strength <= 0.0 || strength > 1.0 || inversionSteps !in 1..100 || refinementSteps !in 1..100 ||
            inversionSteps != expectedInversionSteps ||
            tileSize !in 128..2048 || tileSize % 8 != 0 ||
            tileSize > minOf(targetWidth, targetHeight) || overlap !in 0.0..0.5
        ) reject("invalid_ultrafix", "ultrafix values are outside the supported structured range.")
        return ImageGenerationApiUltraFix(
            targetWidth, targetHeight, strength, inversionSteps, refinementSteps, tileSize, overlap
        )
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
        imageGenerationApiOptionalString(name, allowEmpty)
            ?: reject("invalid_$name", "$name is required and must be a string.")

    private fun JSONObject.promptEvidenceRequiredString(name: String, allowEmpty: Boolean): String {
        if (!has(name) || isNull(name) || get(name) !is String) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing $name must be a string."
            )
        }
        return getString(name).also { value ->
            if (!allowEmpty && value.isBlank()) {
                reject(
                    "invalid_prompt_processing_evidence",
                    "prompt_processing $name must not be blank."
                )
            }
        }
    }

    private fun JSONObject.promptEvidenceRequiredNullableString(name: String): String? {
        if (!has(name)) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing $name is required and may be null."
            )
        }
        if (isNull(name)) return null
        if (get(name) !is String) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing $name must be a string or null."
            )
        }
        return getString(name)
    }

    private fun JSONObject.promptEvidenceOptionalString(name: String): String? {
        if (!has(name)) return null
        if (isNull(name) || get(name) !is String || getString(name).isBlank()) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing $name must be a non-blank string when present."
            )
        }
        return getString(name)
    }

    private fun JSONObject.promptEvidenceRequiredInt(name: String): Int {
        if (!has(name) || isNull(name) || get(name) !is Number) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing $name must be an integer."
            )
        }
        val value = (get(name) as Number).imageGenerationApiToExactLongOrNull()
        if (value == null || value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            reject(
                "invalid_prompt_processing_evidence",
                "prompt_processing $name must be a 32-bit integer."
            )
        }
        return value.toInt()
    }

    private fun JSONObject.promptEvidenceExecutionString(name: String): String {
        if (!has(name) || isNull(name) || get(name) !is String || getString(name).isBlank()) {
            reject(
                "invalid_prompt_processing_evidence",
                "Image execution must report non-blank $name prompt binding evidence."
            )
        }
        return getString(name)
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
        val value = raw.imageGenerationApiToExactLongOrNull()
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

    private val SIZE_PATTERN = Regex("^(\\d{2,4})[xX](\\d{2,4})$")
    internal val SHA256_PATTERN = Regex("[a-f0-9]{64}")
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
        "textual_inversion_ids",
        "ultrafix",
        "preview"
    )
    private val VAE_TILING_FIELDS = setOf("tile_size", "overlap")
    private val ULTRAFIX_FIELDS = setOf(
        "target_width", "target_height", "strength", "inversion_steps", "refinement_steps",
        "tile_size", "overlap"
    )
    private val PREVIEW_FIELDS = setOf("interval", "mode")
    private val RESPONSE_FIELDS = setOf(
        "created", "request_id", "model", "prompt_processing", "execution", "data"
    )
    private val LORA_FIELDS = setOf("id", "multiplier")
    private const val MAX_LORA_COUNT = 8
    private const val MAX_IMAGE_COUNT = 8
    private val ULTRAFIX_EXTENDED_EVIDENCE_FIELDS = setOf(
        "tileCount",
        "tilePlanSha256",
        "encoderGraphExecutionCount",
        "inversionPositiveGraphExecutionCount",
        "refinementPositiveGraphExecutionCount",
        "refinementNegativeGraphExecutionCount",
        "decoderGraphExecutionCount",
        "outputSha256",
        "outputBytes",
        "outputAtomicCommit"
    )
    private val PROMPT_PROCESSING_FIELDS = setOf(
        "version",
        "originalPrompt",
        "effectivePrompt",
        "originalNegativePrompt",
        "effectiveNegativePrompt",
        "negativePromptSource",
        "method",
        "translationContractVersion",
        "imageProfileBindingFingerprint",
        "promptLanguageBindingFingerprint",
        "translatorModelId",
        "translatorModelName",
        "translatorRuntime",
        "translatorModelSha256",
        "translationPlanSha256",
        "verificationReceiptSha256",
        "translationPhaseSystemPromptSha256",
        "verificationPhaseSystemPromptSha256",
        "translationProofFingerprint"
    )
    private const val PROMPT_PROCESSING_VERSION = 4
    private const val PROMPT_TRANSLATION_CONTRACT_VERSION = 4
    private const val OFFLINE_PROMPT_TRANSLATION_CONTRACT_VERSION = 5
    private const val PROMPT_METHOD_DIRECT = "DIRECT"
    private const val PROMPT_METHOD_NATIVE_MULTILINGUAL = "NATIVE_MULTILINGUAL"
    private const val PROMPT_METHOD_DIRECT_UTF8_PASSTHROUGH = "DIRECT_UTF8_PASSTHROUGH"
    private const val PROMPT_METHOD_LOCAL_LLM = "LOCAL_LLM_ZH_TO_EN"
    private const val PROMPT_METHOD_OFFLINE_HY_MT2 = "OFFLINE_HY_MT2_ZH_TO_EN"
    private const val OFFLINE_PROMPT_TRANSLATOR_MODEL_ID = "tencent/Hy-MT2-1.8B-GGUF"
    private const val OFFLINE_PROMPT_TRANSLATOR_MODEL_NAME = "Hy-MT2 1.8B Q4_K_M"
    private const val OFFLINE_PROMPT_TRANSLATOR_RUNTIME = "llama.cpp/offline_translation"
    private const val OFFLINE_PROMPT_TRANSLATOR_MODEL_SHA256 =
        "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699"
    private const val PROMPT_LANGUAGE_ENGLISH_DOMINANT = "ENGLISH_DOMINANT"
    private const val PROMPT_LANGUAGE_NATIVE_MULTILINGUAL = "NATIVE_MULTILINGUAL"
    private const val NEGATIVE_PROMPT_SOURCE_USER = "USER"
    private const val NEGATIVE_PROMPT_SOURCE_MODEL_DEFAULT = "MODEL_DEFAULT"
    private const val NEGATIVE_PROMPT_SOURCE_EMPTY = "EMPTY"
    private val PROMPT_LANGUAGE_CAPABILITIES = setOf(
        PROMPT_LANGUAGE_ENGLISH_DOMINANT,
        PROMPT_LANGUAGE_NATIVE_MULTILINGUAL
    )
    private const val MAX_PROMPT_PROCESSING_ORIGINAL_CHARS = 16_384
    private const val MAX_PROMPT_PROCESSING_EFFECTIVE_CHARS = 4_096
}

internal fun reject(code: String, message: String): Nothing =
    throw ImageGenerationContractException(code, message)

private fun normalizeProviderErrorCode(raw: String): String = raw
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9_.-]+"), "_")
    .trim('_')
    .take(80)
    .ifBlank { "image_generation_failed" }

private fun String.containsHanScript(): Boolean = codePoints().anyMatch { codePoint ->
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

private fun String.isSafeAsciiDiffusionPrompt(): Boolean = all { char ->
    char == ' ' || char == '\n' || char == '\r' ||
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
        char in SAFE_ASCII_PROMPT_PUNCTUATION
}

/** Mirrors the Kotlin/native direct-Chinese grammar before loopback evidence is accepted. */
private fun String.isSupportedNativeChineseHanDiffusionPrompt(): Boolean = codePoints().allMatch {
    codePoint ->
    codePoint in SUPPORTED_NATIVE_CHINESE_HAN_PROMPT_PUNCTUATION ||
        isSafeAsciiDiffusionPromptCodePoint(codePoint) ||
        isNativeChineseHanCodePoint(codePoint)
}

private val SUPPORTED_NATIVE_CHINESE_HAN_PROMPT_PUNCTUATION = setOf(
    0xFF0C, 0xFF1B, 0x3002, 0xFF01, 0xFF1F, 0xFF08, 0xFF09,
    0xFF3B, 0xFF3D, 0xFF5B, 0xFF5D, 0xFF1C, 0xFF1E, 0xFF1A
)
