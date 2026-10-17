package com.muyuchat.mca

import org.json.JSONObject

internal const val IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID =
    "IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID"

internal data class ImageValidatedNativeExecution(
    val nativeEffective: ImageNativeEffectiveExecution,
    val validation: ImageExecutionContractValidation,
    val pixelRange: ImagePixelRange? = null
)

internal class ImageNativeExecutionContractException(
    val code: String,
    val field: String,
    val mismatches: List<ImageExecutionMismatch> = emptyList(),
    message: String,
    cause: Throwable? = null
) : IllegalArgumentException(message, cause)

/**
 * Strict wire contract between a resolved image profile and native execution.
 *
 * Every value that affects generated pixels is sent to native and must be echoed back after real
 * execution. Native output is never treated as valid when a field is absent, has an unknown enum
 * value, or differs from the resolved execution contract.
 */
internal object ImageExecutionProfileNativeContract {
    val requiredFields: Set<String> = linkedSetOf(
        "profileId",
        "profileRevision",
        "modelFingerprint",
        "runtime",
        "scheduler",
        "predictionType",
        "steps",
        "timetableCount",
        "unetExecutionCount",
        "cfgScale",
        "useCfg",
        "unconditionalBranch",
        "tokenizerBackend",
        "tokenCount",
        "promptWeightingSupported",
        "promptWeightingApplied",
        "positiveWeightedTokenCount",
        "negativeWeightedTokenCount",
        "promptWeightFingerprint",
        "embeddingDiskDataType",
        "vaeScalingLocation",
        "vaeScalingFactor",
        "width",
        "height",
        "seed",
        "graphName",
        "fallback"
    )

    val nativeEvidenceOnlyFields: Set<String> = linkedSetOf(
        "promptWeightingApplied",
        "positiveWeightedTokenCount",
        "negativeWeightedTokenCount",
        "promptWeightFingerprint"
    )

    /** Native conditioner evidence passed to QNN and upgraded after graph consumption. */
    val qnnPromptConditioningHandoffFields: Set<String> = linkedSetOf(
        "nativePromptExecutionSha256",
        "nativePromptBindingStage"
    )

    /** QNN-only nativeEffective fields; these must not become global runtime requirements. */
    val qnnNativeEffectiveFields: Set<String> = linkedSetOf(
        "pixelRange",
        "conditioningArtifactSha256",
        "conditioningExecutionMode",
        "conditioningBackend",
        "conditioningGraph",
        "conditioningGraphSha256",
        "conditioningOrder",
        "conditioningEncoderExecutionCount",
        "textEncoderExecutionCount",
        "conditioningArtifactConsumed",
        "runtimeSessionMode",
        "nativePromptExecutionSha256",
        "nativePromptBindingStage"
    )

    /** QNN VAE conversion proof emitted only after actual native pixel conversion. */
    val qnnPixelRangeEvidenceFields: Set<String> = linkedSetOf(
        "pixelRangeConversion",
        "pixelRangeValueCount",
        "pixelRangeClampedValueCount",
        "pixelRangeObservedMin",
        "pixelRangeObservedMax"
    )

    val resolvedRequestFields: Set<String> = requiredFields - nativeEvidenceOnlyFields

    fun parseAndValidate(
        resolution: ImageExecutionProfileResolution,
        nativeExecutionJson: String
    ): ImageValidatedNativeExecution {
        val json = try {
            JSONObject(nativeExecutionJson)
        } catch (error: Exception) {
            throw ImageNativeExecutionContractException(
                code = IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID,
                field = "nativeExecution",
                message = "Native image execution metadata must be a JSON object.",
                cause = error
            )
        }
        return parseAndValidate(resolution, json)
    }

    fun parseAndValidate(
        resolution: ImageExecutionProfileResolution,
        nativeExecution: JSONObject
    ): ImageValidatedNativeExecution {
        val nativeEffectiveJson = nativeExecution.optJSONObject("nativeEffective")
            ?: invalid(
                "nativeEffective",
                "Native image execution must report a complete nativeEffective object."
            )
        val nativeEffective = nativeEffectiveJson.toNativeEffective()
        val pixelRange = if (resolution.layers.resolved.runtime == LocalImageRuntime.QNN_HTP) {
            validateQnnPixelRange(resolution, nativeEffectiveJson)
        } else {
            null
        }
        if (resolution.layers.resolved.runtime == LocalImageRuntime.QNN_HTP) {
            validateQnnConditioningExecutionEvidence(resolution, nativeEffectiveJson)
            validateQnnNativeMultilingualTextEncoderEvidence(resolution, nativeEffectiveJson)
        }
        validateNativeMultilingualLanguageProofReceipt(resolution, nativeEffectiveJson)
        if (resolution.layers.resolved.runtime == LocalImageRuntime.STABLE_DIFFUSION_CPP) {
            validateStableDiffusionTokenEvidence(
                resolution,
                nativeEffective,
                nativeEffectiveJson
            )
        }
        if (resolution.profile.family == LocalImageModelFamily.SANA &&
            resolution.layers.resolved.runtime == LocalImageRuntime.MNN_DIFFUSION
        ) {
            validateMnnSanaConditioningEvidence(
                resolution,
                nativeEffective,
                nativeEffectiveJson
            )
        }
        val validation = ImageExecutionContractValidator.validate(
            resolution.layers.copy(nativeEffective = nativeEffective)
        )
        if (!validation.valid) {
            val fields = validation.mismatches.map(ImageExecutionMismatch::field).distinct()
            throw ImageNativeExecutionContractException(
                code = requireNotNull(validation.errorCode),
                field = fields.joinToString(","),
                mismatches = validation.mismatches,
                message = "Native image execution differs from resolved fields: ${fields.joinToString()}"
            )
        }
        return ImageValidatedNativeExecution(
            nativeEffective = nativeEffective,
            validation = validation,
            pixelRange = pixelRange
        )
    }

    fun qnnPixelRangeConversionName(range: ImagePixelRange): String = when (range) {
        ImagePixelRange.NEGATIVE_ONE_TO_ONE -> "negative_one_to_one_to_u8"
        ImagePixelRange.ZERO_TO_ONE -> "zero_to_one_to_u8"
        ImagePixelRange.ZERO_TO_255 -> "zero_to_255_to_u8"
        ImagePixelRange.RUNTIME_NATIVE -> invalid(
            "pixelRange",
            "QNN image execution cannot infer a runtime-native VAE output pixel range."
        )
    }

}
