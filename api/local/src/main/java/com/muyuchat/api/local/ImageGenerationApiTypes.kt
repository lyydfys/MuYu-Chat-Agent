package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject

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

private fun normalizeProviderErrorCode(raw: String): String = raw
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9_.-]+"), "_")
    .trim('_')
    .take(80)
    .ifBlank { "image_generation_failed" }
