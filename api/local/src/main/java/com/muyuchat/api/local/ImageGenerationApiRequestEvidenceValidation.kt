package com.muyuchat.api.local

import com.muyuchat.api.local.ImageGenerationApiResponseEvidenceValidation.rejectPrivateInputPaths
import com.muyuchat.core.engine.compareUtf8Unsigned
import org.json.JSONArray
import org.json.JSONObject

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

    private const val MAX_TEXTUAL_INVERSION_BYTES = 100L * 1024L * 1024L

    private const val MAX_ACTIVE_TEXTUAL_INVERSION_BYTES = 256L * 1024L * 1024L

    private const val MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_COUNT = 64

    private const val MAX_TEXTUAL_INVERSION_EXECUTION_ASSET_BYTES =
        64L * 1024L * 1024L * 1024L

    private const val MAX_EXECUTION_ASSET_LABEL_CHARS = 4_096

    private const val MAX_IMAGE_INPUT_BYTES = 32L * 1024L * 1024L

    private const val MAX_IMAGE_REFERENCE_CHARS = 48 * 1024 * 1024

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

}
