package com.muyuchat.api.local

import org.json.JSONArray
import org.json.JSONObject
internal object ImageGenerationApiResponseEvidenceValidation {

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

}
