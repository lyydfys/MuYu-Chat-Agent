package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64

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

internal fun compareImageExecutionAssetLabelsUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    val commonLength = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until commonLength) {
        val comparison = (leftBytes[index].toInt() and 0xff)
            .compareTo(rightBytes[index].toInt() and 0xff)
        if (comparison != 0) return comparison
    }
    return leftBytes.size.compareTo(rightBytes.size)
}

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

private fun sha256Utf8(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun ultrafixNoiseSeedFingerprint(seed: Long, denoiseStepCount: Long): String {
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
            return ImageGenerationProviderException(
                code = normalized,
                httpStatus = workerFailureHttpStatus(normalized),
                message = message
            )
        }

        private fun workerFailureHttpStatus(code: String): Int = when {
            code == "worker_busy" || code == "image_generation_busy" ||
                code == "image_prompt_translation_busy" -> 409
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
        root.rejectUnknownFields(REQUEST_FIELDS, "image generation request")

        val prompt = root.requiredString("prompt", allowEmpty = false)
        val model = root.optionalString("model", allowEmpty = false)
        val negativePrompt = root.optionalText("negative_prompt", allowEmpty = true)
        val sampler = root.optionalString("sampler", allowEmpty = false)?.also { value ->
            if (canonicalSampler(value) !in SUPPORTED_SAMPLERS) {
                reject("unsupported_sampler", "sampler is not supported by the local image API.")
            }
        }
        val requestedDimensions = root.optionalString("size", allowEmpty = false)?.let(::parseSize)
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
        validateInputShape(taskMode, inputImage, maskImage, controlImage, strength, controlStrength)
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
        root.rejectUnknownFields(RESPONSE_FIELDS, "image generation response")
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
        expectedRequest?.let { request ->
            val actualModel = root.optionalString("model", allowEmpty = false)
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
            if (root.requiredInteger("created", minimum = 0L) <= 0L) {
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
        validateExecutionEvidence(execution, promptProcessing)
        expectedRequest?.let {
            validateRequestedControlEvidence(it, execution)
            validateRequestedInputEvidence(it, execution)
            validateRequestedLoraEvidence(it, execution)
            validateRequestedTextualInversionEvidence(it, execution, promptProcessing)
        }
        expectedRequest?.let { request ->
            validateRequestedUltraFixEvidence(request, execution, data)
        }
        validateImageData(
            data = data,
            execution = execution,
            nativeEffective = execution.getJSONObject("nativeEffective"),
            expectedCount = expectedRequest?.imageCount ?: 1,
            requireSeedEvidence = expectedRequest != null
        )
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
        json.rejectUnknownFields(ULTRAFIX_FIELDS, "ultrafix")
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

    private fun validateExecutionEvidence(
        execution: JSONObject,
        promptProcessing: ImageGenerationApiPromptProcessing?
    ) {
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
        validateNativePromptExecutionEvidence(execution, nativeEffective, promptProcessing)
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

    private fun validateNativePromptExecutionEvidence(
        execution: JSONObject,
        nativeEffective: JSONObject,
        promptProcessing: ImageGenerationApiPromptProcessing?
    ) {
        val outerSha256 = execution.requiredNonBlankString("nativePromptExecutionSha256")
        val nativeSha256 = nativeEffective.requiredNonBlankString("nativePromptExecutionSha256")
        val outerStage = execution.requiredNonBlankString("nativePromptBindingStage")
        val nativeStage = nativeEffective.requiredNonBlankString("nativePromptBindingStage")
        if (!SHA256_PATTERN.matches(outerSha256) || outerSha256 != nativeSha256) {
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
        val nativeTaskMode = nativeEffective.requiredNonBlankString("taskMode")
        if (nativeTaskMode != request.taskMode.wireName ||
            execution.requiredNonBlankString("taskMode") != nativeTaskMode
        ) {
            reject("image_control_mismatch", "Native task_mode does not match the request.")
        }
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
        request.clipSkip?.let { expected ->
            val nativeValue = nativeEffective.requiredInteger("clipSkip", minimum = -1L)
            if (nativeValue != expected.toLong() ||
                execution.requiredInteger("clipSkip", minimum = -1L) != nativeValue
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
                if (!evidence.requiredBoolean("enabled") ||
                    evidence.requiredInteger("requestedTileSize", minimum = 1L) !=
                    expected.tileSize.toLong() ||
                    !numbersMatch(
                        evidence.requiredFiniteNumber("requestedOverlap"),
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
                    if (evidence.requiredBoolean("enabled") ||
                        evidence.requiredInteger("requestedTileSize", minimum = 0L) != 0L ||
                        !numbersMatch(evidence.requiredFiniteNumber("requestedOverlap"), 0.0)
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

    private fun validateSynchronousPreviewWasNotExecuted(evidence: JSONObject) {
        if (!evidence.has("previewRequested")) return
        if (evidence.requiredBoolean("previewRequested") ||
            evidence.requiredNonBlankString("previewMode") != "none" ||
            evidence.requiredInteger("previewInterval", minimum = 0L) != 0L ||
            evidence.requiredInteger("previewPublicationCount", minimum = 0L) != 0L ||
            evidence.requiredInteger("previewLastStep", minimum = 0L) != 0L ||
            evidence.requiredInteger("previewLastRevision", minimum = 0L) != 0L
        ) {
            reject(
                "invalid_preview_execution_evidence",
                "Synchronous image generation must not execute or publish live preview frames."
            )
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

    private fun validateRequestedTextualInversionEvidence(
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

        val runtime = execution.requiredNonBlankString("runtime")
        val expectedNativeMode = when (runtime) {
            "STABLE_DIFFUSION_CPP" -> "SDCPP_CUSTOM_WORDS"
            "QNN_HTP", "MNN_DIFFUSION" -> "MNN_CLIP_INPUT_EMBEDDING"
            else -> null
        }
        val executionProfileId = execution.requiredNonBlankString("profileId")
        val executionProfileRevision = execution.requiredPositiveLong("profileRevision")
        val executionModelFingerprint = execution.requiredNonBlankString("modelFingerprint")
        val executionProfilePromptFingerprint = promptProcessing?.promptLanguageBindingFingerprint
        if (expectedIds.isNotEmpty() &&
            (expectedNativeMode == null ||
                !SHA256_PATTERN.matches(executionModelFingerprint) ||
                executionProfilePromptFingerprint == null ||
                !SHA256_PATTERN.matches(executionProfilePromptFingerprint))
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
            val outerRuntime = execution.requiredNonBlankString("textualInversionExecutionRuntime")
            val nativeRuntime = nativeEffective.requiredNonBlankString("textualInversionExecutionRuntime")
            val outerProfile = execution.requiredNonBlankString(
                "textualInversionExecutionProfileFingerprint"
            )
            val nativeProfile = nativeEffective.requiredNonBlankString(
                "textualInversionExecutionProfileFingerprint"
            )
            val outerComposite = execution.requiredNonBlankString(
                "textualInversionExecutionAssetsSha256"
            )
            val nativeComposite = nativeEffective.requiredNonBlankString(
                "textualInversionExecutionAssetsSha256"
            )
            if (outerRuntime != runtime || nativeRuntime != outerRuntime ||
                outerProfile != executionProfilePromptFingerprint || nativeProfile != outerProfile ||
                !SHA256_PATTERN.matches(outerProfile) ||
                !SHA256_PATTERN.matches(outerComposite) || nativeComposite != outerComposite
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
                val label = outer.requiredNonBlankString("label")
                val nativeLabel = native.requiredNonBlankString("label")
                val sha256 = outer.requiredNonBlankString("sha256")
                val nativeSha256 = native.requiredNonBlankString("sha256")
                val sizeBytes = outer.requiredPositiveLong("sizeBytes")
                val nativeSizeBytes = native.requiredPositiveLong("sizeBytes")
                val invalidLabel = label.length > MAX_EXECUTION_ASSET_LABEL_CHARS ||
                    '\u0000' in label || '\u001f' in label || '\\' in label ||
                    label.startsWith('/') || label.split('/').any { part ->
                        part.isEmpty() || part == "." || part == ".."
                    }
                if (invalidLabel || label != nativeLabel ||
                    previousLabel?.let { previous ->
                        compareImageExecutionAssetLabelsUtf8(label, previous) <= 0
                    } == true ||
                    !SHA256_PATTERN.matches(sha256) || sha256 != nativeSha256 ||
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
                val outerValue = outer.requiredNonBlankString(field)
                val nativeValue = native.requiredNonBlankString(field)
                if (outerValue != nativeValue) {
                    reject(
                        "invalid_textual_inversion_execution_evidence",
                        "Outer and nativeEffective textual inversion artifact evidence differ."
                    )
                }
            }
            val artifactId = outer.requiredNonBlankString("id")
            val trigger = outer.requiredNonBlankString("trigger")
            val artifactSha256 = outer.requiredNonBlankString("sha256")
            val format = outer.requiredNonBlankString("format")
            val modelFingerprint = outer.requiredNonBlankString("modelFingerprint")
            val tokenizerFingerprint = outer.requiredNonBlankString("tokenizerFingerprint")
            val profileId = outer.requiredNonBlankString("profileId")
            val artifactRuntime = outer.requiredNonBlankString("runtime")
            val bindingFingerprint = outer.requiredNonBlankString("bindingFingerprint")
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
                ).any { !SHA256_PATTERN.matches(it) }
            ) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion trigger, digest, or binding fingerprint is invalid."
                )
            }
            val outerSize = outer.requiredPositiveLong("sizeBytes")
            val nativeSize = native.requiredPositiveLong("sizeBytes")
            val outerRevision = outer.requiredPositiveLong("profileRevision")
            val nativeRevision = native.requiredPositiveLong("profileRevision")
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
            val outer = outerEvidence.requiredInteger(field, minimum = 0L)
            val native = nativeEvidence.requiredInteger(field, minimum = 0L)
            if (outer != expectedCount || native != outer) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion execution counts do not prove the complete request."
                )
            }
        }
        maskFields.forEach { field ->
            val outer = outerEvidence.requiredInteger(field, minimum = 0L)
            val native = nativeEvidence.requiredInteger(field, minimum = 0L)
            if (outer != expectedMask || native != outer) {
                reject(
                    "invalid_textual_inversion_execution_evidence",
                    "Textual inversion execution masks do not prove the complete request."
                )
            }
        }
        val outerConsumption = outerEvidence.requiredInteger(
            "conditioningConsumptionCount",
            minimum = 0L
        )
        val nativeConsumption = nativeEvidence.requiredInteger(
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
        val outerClipGRequired = outerEvidence.requiredInteger("clipGRequiredMask", minimum = 0L)
        val nativeClipGRequired = nativeEvidence.requiredInteger("clipGRequiredMask", minimum = 0L)
        val outerClipG = outerEvidence.requiredInteger("clipGMask", minimum = 0L)
        val nativeClipG = nativeEvidence.requiredInteger("clipGMask", minimum = 0L)
        val outerClipGCount = outerEvidence.requiredInteger("clipGAppliedCount", minimum = 0L)
        val nativeClipGCount = nativeEvidence.requiredInteger("clipGAppliedCount", minimum = 0L)
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
        val outerVectors = outerEvidence.requiredInteger("appliedVectorCount", minimum = 0L)
        val nativeVectors = nativeEvidence.requiredInteger("appliedVectorCount", minimum = 0L)
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
                !SHA256_PATTERN.matches(bindingFingerprint) || expectedNativeMode == null ||
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

    private fun validateRequestedUltraFixEvidence(
        request: ImageGenerationApiRequest,
        execution: JSONObject,
        data: JSONArray
    ) {
        val requested = request.ultraFix ?: return
        when (execution.requiredNonBlankString("runtime")) {
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
            !numbersMatch(outer.overlap, requested.overlap) ||
            outer.inversionSteps != requested.inversionSteps.toLong() ||
            outer.refinementSteps != requested.refinementSteps.toLong() ||
            outer.denoiseStepCount != requested.inversionSteps.toLong() ||
            outer.sampleMethod.isBlank() || outer.nativeScheduler.isBlank() ||
            !numbersMatch(execution.exactDouble("strength", "outer"), requested.strength) ||
            !numbersMatch(
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
                val negative = if (nativeEffective.requiredBoolean("useCfg")) {
                    outer.tiledUnetRefinement.tileInvocationCount
                } else {
                    0L
                }
                Triple(positive, negative, Math.addExact(positive, negative))
            } else {
                if (!SHA256_PATTERN.matches(extended.tilePlanSha256) ||
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
                    (nativeEffective.requiredBoolean("useCfg") &&
                        extended.refinementNegativeGraphExecutionCount !=
                        Math.multiplyExact(extended.tileCount, inversionSteps)) ||
                    (!nativeEffective.requiredBoolean("useCfg") &&
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
            !SHA256_PATTERN.matches(outer.noiseInjectionSeedFingerprint) ||
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
            if (!SHA256_PATTERN.matches(sha256)) invalid("$layer output SHA-256 is malformed.")
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
            if (!SHA256_PATTERN.matches(extended.outputSha256) ||
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
            item.requiredPositiveLong("width") != requested.targetWidth.toLong() ||
            item.requiredPositiveLong("height") != requested.targetHeight.toLong() ||
            decoded.size < PNG_SIGNATURE.size ||
            !decoded.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)
        ) {
            invalid("UltraFix output evidence does not bind the exact returned PNG bytes.")
        }
    }

    private fun validateQnnUltraFixEvidence(
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
            !numbersMatch(outer.overlap, requested.overlap) || outer.tileCount != expectedTileCount ||
            outer.tilePlanSha256 != expectedTilePlanSha256 ||
            !SHA256_PATTERN.matches(outer.tilePlanSha256) ||
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
        val refinementNegative = if (nativeEffective.requiredBoolean("useCfg")) inversionTiles else 0L
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
            execution.requiredBoolean("useCfg") != nativeEffective.requiredBoolean("useCfg") ||
            execution.requiredBoolean("useCfg") != (refinementNegative != 0L) ||
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
            !SHA256_PATTERN.matches(outer.noiseInjectionSeedFingerprint) ||
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
            if (!SHA256_PATTERN.matches(sha)) invalid("$layer output SHA-256 is malformed.")
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

    private fun rejectPrivateInputPaths(evidence: JSONObject) {
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
            item.rejectUnknownFields(RESPONSE_IMAGE_DATA_FIELDS, "data[$index]")
            val evidence = outputEvidence.optJSONObject(index)
                ?: reject(
                    "invalid_image_provider_response",
                    "Image response output evidence item must be an object."
                )
            evidence.rejectUnknownFields(RESPONSE_OUTPUT_EVIDENCE_FIELDS, "responseOutputEvidence[$index]")
            if (evidence.requiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image response output evidence indices must be contiguous.")
            }
            val encoded = item.requiredNonBlankString("b64_json")
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
            val mimeType = item.requiredNonBlankString("mime_type")
            val evidenceMimeType = evidence.requiredNonBlankString("mimeType")
            if (mimeType != "image/png" || evidenceMimeType != mimeType) {
                reject("invalid_image_provider_response", "Image data MIME type must be bound to image/png evidence.")
            }
            val evidenceSize = evidence.requiredPositiveLong("sizeBytes")
            if (evidenceSize > MAX_IMAGE_OUTPUT_PNG_BYTES || evidenceSize != decoded.size.toLong()) {
                reject("invalid_image_provider_response", "Image data byte length does not match output evidence.")
            }
            val expectedSha256 = evidence.requiredNonBlankString("sha256")
            val actualSha256 = MessageDigest.getInstance("SHA-256")
                .digest(decoded)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (!SHA256_PATTERN.matches(expectedSha256) || actualSha256 != expectedSha256) {
                reject("invalid_image_provider_response", "Image data SHA-256 does not match output evidence.")
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
            if (item.requiredInteger("index", minimum = 0L) != index.toLong()) {
                reject("invalid_image_provider_response", "Image data indices must be contiguous.")
            }
            if (requireSeedEvidence) {
                val nativeSeed = nativeEffective.requiredInteger("seed", minimum = 0L)
                val expectedSeed = runCatching { Math.addExact(nativeSeed, index.toLong()) }
                    .getOrElse {
                        reject(
                            "invalid_image_provider_response",
                            "Native image output seed sequence overflowed."
                        )
                    }
                if (item.requiredInteger("seed", minimum = 0L) != expectedSeed) {
                    reject(
                        "invalid_image_provider_response",
                        "Image data seed does not match native execution evidence."
                    )
                }
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
        val value = (get(name) as Number).toExactLongOrNull()
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
    private val TEXTUAL_INVERSION_TRIGGER_PATTERN = Regex("[A-Za-z0-9_:#<>|.-]{1,64}")
    private val TEXTUAL_INVERSION_FORMATS = setOf(
        "safetensors",
        "pytorch",
        "checkpoint",
        "binary"
    )
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
    private val PREVIEW_FIELDS = setOf("interval", "mode")
    private val RESPONSE_OUTPUT_EVIDENCE_FIELDS = setOf(
        "index", "mimeType", "sizeBytes", "sha256"
    )
    private val RESPONSE_FIELDS = setOf(
        "created", "request_id", "model", "prompt_processing", "execution", "data"
    )
    private val RESPONSE_IMAGE_DATA_FIELDS = setOf(
        "index", "b64_json", "mime_type", "width", "height", "seed"
    )
    private val LORA_FIELDS = setOf("id", "multiplier")
    private const val MAX_LORA_COUNT = 8
    private const val MAX_TEXTUAL_INVERSION_BYTES = 100L * 1024L * 1024L
    private const val MAX_ACTIVE_TEXTUAL_INVERSION_BYTES = 256L * 1024L * 1024L
    private const val MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_COUNT = 64
    private const val MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_BYTES =
        64L * 1024L * 1024L * 1024L
    private const val MAX_EXECUTION_ASSET_LABEL_CHARS = 4_096
    private const val MAX_IMAGE_COUNT = 8
    private const val MAX_IMAGE_INPUT_BYTES = 32L * 1024L * 1024L
    private const val MAX_IMAGE_OUTPUT_PNG_BYTES = 256L * 1024L * 1024L
    private const val MAX_IMAGE_REFERENCE_CHARS = 48 * 1024 * 1024
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
    private const val PROMPT_METHOD_DIRECT = "DIRECT"
    private const val PROMPT_METHOD_NATIVE_MULTILINGUAL = "NATIVE_MULTILINGUAL"
    private const val PROMPT_METHOD_DIRECT_UTF8_PASSTHROUGH = "DIRECT_UTF8_PASSTHROUGH"
    private const val PROMPT_METHOD_LOCAL_LLM = "LOCAL_LLM_ZH_TO_EN"
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

private fun String.containsHanScript(): Boolean = codePoints().anyMatch { codePoint ->
    Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
}

private const val SAFE_ASCII_DIFFUSION_PROMPT_PUNCTUATION = "_,.;:!?\"'+-*/\\()[]{}<>|=@#%&"

private fun String.isSafeAsciiDiffusionPrompt(): Boolean = all { char ->
    char == ' ' || char == '\n' || char == '\r' ||
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
        char in SAFE_ASCII_DIFFUSION_PROMPT_PUNCTUATION
}

/** Mirrors the Kotlin/native direct-Chinese grammar before loopback evidence is accepted. */
private fun String.isSupportedNativeChineseHanDiffusionPrompt(): Boolean = codePoints().allMatch {
    codePoint ->
    codePoint in SUPPORTED_NATIVE_CHINESE_HAN_PROMPT_PUNCTUATION ||
        isSafeAsciiDiffusionPromptCodePoint(codePoint) ||
        isNativeChineseHanCodePoint(codePoint)
}

private fun isSafeAsciiDiffusionPromptCodePoint(codePoint: Int): Boolean =
    codePoint == ' '.code || codePoint == '\n'.code || codePoint == '\r'.code ||
        codePoint in 'A'.code..'Z'.code ||
        codePoint in 'a'.code..'z'.code ||
        codePoint in '0'.code..'9'.code ||
        (codePoint <= Char.MAX_VALUE.code &&
            codePoint.toChar() in SAFE_ASCII_DIFFUSION_PROMPT_PUNCTUATION)

private fun isNativeChineseHanCodePoint(codePoint: Int): Boolean =
    codePoint == 0x3007 ||
        codePoint in 0x3400..0x4DBF ||
        codePoint in 0x4E00..0x9FFF ||
        codePoint in 0x20000..0x2A6DF ||
        codePoint in 0x2A700..0x2B73F ||
        codePoint in 0x2B740..0x2B81F ||
        codePoint in 0x2B820..0x2CEAF ||
        codePoint in 0x2CEB0..0x2EBEF ||
        codePoint in 0x30000..0x3134A ||
        codePoint in 0x31350..0x323AF

private val SUPPORTED_NATIVE_CHINESE_HAN_PROMPT_PUNCTUATION = setOf(
    0xFF0C, 0xFF1B, 0x3002, 0xFF01, 0xFF1F, 0xFF08, 0xFF09,
    0xFF3B, 0xFF3D, 0xFF5B, 0xFF5D, 0xFF1C, 0xFF1E, 0xFF1A
)
