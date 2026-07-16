package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject

internal data class ImageGenerationApiRequest(
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
    val sampler: String?
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
    }
}

internal data class ImageGenerationApiResponse(
    val rawBody: String,
    val requestId: String,
    val execution: JSONObject,
    val data: JSONArray
)

internal class ImageGenerationContractException(
    val code: String,
    override val message: String
) : IllegalArgumentException(message)

internal object ImageGenerationApiContract {
    fun parseRequest(rawBody: String): ImageGenerationApiRequest {
        val root = try {
            JSONObject(rawBody)
        } catch (_: Throwable) {
            reject("invalid_json", "Image generation request body must be valid JSON.")
        }

        val prompt = root.requiredString("prompt", allowEmpty = false)
        val model = root.optionalString("model", allowEmpty = false)
        val negativePrompt = root.optionalText("negative_prompt")
        val sampler = root.optionalString("sampler", allowEmpty = false)
        val dimensions = root.optionalString("size", allowEmpty = false)?.let(::parseSize)
        val imageCount = root.optionalInt("n") ?: 1
        if (imageCount != 1) {
            reject(
                "unsupported_image_count",
                "Image generation currently supports n=1 only."
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
        if (steps != null && steps <= 0) {
            reject("invalid_steps", "steps must be a positive integer when specified.")
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
            seed = root.optionalInt("seed"),
            steps = steps,
            cfgScale = root.optionalDouble("cfg_scale"),
            sampler = sampler
        )
    }

    fun parseResponse(expectedRequestId: String, rawBody: String): ImageGenerationApiResponse {
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
        validateImageData(data, execution.getJSONObject("nativeEffective"))
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
        if (width <= 0 || height <= 0) {
            reject("invalid_image_size", "Image width and height must be positive.")
        }
        return width to height
    }

    private fun validateExecutionEvidence(execution: JSONObject) {
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
        NATIVE_STRING_FIELDS.forEach { field -> nativeEffective.requiredNonBlankString(field) }
        NATIVE_POSITIVE_INTEGER_FIELDS.forEach { field -> nativeEffective.requiredPositiveLong(field) }
        nativeEffective.requiredInteger("seed", minimum = 0L)
        nativeEffective.requiredFiniteNumber("cfgScale")
        nativeEffective.requiredPositiveNumber("vaeScalingFactor")
        val useCfg = nativeEffective.requiredBoolean("useCfg")
        val unconditionalBranch = nativeEffective.requiredBoolean("unconditionalBranch")
        if (useCfg != unconditionalBranch) {
            reject(
                "invalid_image_execution_evidence",
                "nativeEffective unconditionalBranch must exactly match useCfg."
            )
        }
        if (nativeEffective.requiredBoolean("fallback")) {
            reject("invalid_image_execution_evidence", "nativeEffective must prove fallback=false.")
        }
        val runtime = execution.requiredNonBlankString("runtime")
        if (nativeEffective.requiredNonBlankString("runtime") != runtime) {
            reject(
                "invalid_image_execution_evidence",
                "Outer and nativeEffective runtime evidence do not match."
            )
        }
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

    private fun validateImageData(data: JSONArray, nativeEffective: JSONObject) {
        if (data.length() != 1) {
            reject("invalid_image_provider_response", "Image provider must return exactly one image item.")
        }
        val item = data.optJSONObject(0)
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
    }

    private fun JSONObject.optionalText(name: String): String? {
        if (!has(name)) return null
        if (isNull(name)) reject("invalid_$name", "$name must be a string when specified.")
        val raw = get(name)
        if (raw !is String) reject("invalid_$name", "$name must be a string when specified.")
        return raw
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

    private fun JSONObject.optionalInt(name: String): Int? {
        if (!has(name)) return null
        if (isNull(name)) reject("invalid_$name", "$name must be an integer when specified.")
        val raw = get(name)
        if (raw !is Number) reject("invalid_$name", "$name must be an integer when specified.")
        val value = raw.toDouble()
        if (!value.isFinite() || value % 1.0 != 0.0 ||
            value < Int.MIN_VALUE.toDouble() || value > Int.MAX_VALUE.toDouble()
        ) {
            reject("invalid_$name", "$name must be a finite 32-bit integer.")
        }
        return value.toInt()
    }

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
        val doubleValue = raw.toDouble()
        val longValue = raw.toLong()
        if (!doubleValue.isFinite() || doubleValue % 1.0 != 0.0 || longValue < minimum) {
            reject(
                "invalid_image_execution_evidence",
                "Image execution field $name must be an integer no smaller than $minimum."
            )
        }
        return longValue
    }

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

    private fun reject(code: String, message: String): Nothing =
        throw ImageGenerationContractException(code, message)

    private val SIZE_PATTERN = Regex("^(\\d{2,4})[xX](\\d{2,4})$")
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
