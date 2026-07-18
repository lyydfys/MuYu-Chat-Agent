package com.muyuchat.mca

import java.math.BigDecimal
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

    /** QNN-only nativeEffective fields; these must not become global runtime requirements. */
    val qnnNativeEffectiveFields: Set<String> = linkedSetOf("pixelRange")

    /** QNN VAE conversion proof emitted only after actual native pixel conversion. */
    val qnnPixelRangeEvidenceFields: Set<String> = linkedSetOf(
        "pixelRangeConversion",
        "pixelRangeValueCount",
        "pixelRangeClampedValueCount",
        "pixelRangeObservedMin",
        "pixelRangeObservedMax"
    )

    val resolvedRequestFields: Set<String> = requiredFields - nativeEvidenceOnlyFields

    fun toNativeParamsJson(resolution: ImageExecutionProfileResolution): JSONObject =
        resolution.layers.resolved.toJson().apply {
            val profile = resolution.profile
            val scheduler = profile.scheduler
            put("sampleMethod", imageSchedulerProductName(scheduler.algorithm))
            put("numTrainTimesteps", scheduler.numTrainTimesteps)
            put("noiseSchedule", scheduler.noiseSchedule.name)
            scheduler.betaStart?.let { put("betaStart", it) }
            scheduler.betaEnd?.let { put("betaEnd", it) }
            put("timestepSpacing", scheduler.timestepSpacing.name)
            put("stepsOffset", scheduler.stepsOffset)
            put("setAlphaToOne", scheduler.setAlphaToOne)
            put("skipPrkSteps", scheduler.skipPrkSteps)
            put("finalSigmaType", scheduler.finalSigmaType.name)
            put("clipSample", scheduler.clipSample)
            put("clipSampleRange", scheduler.clipSampleRange)
            put("thresholding", scheduler.thresholding)
            put("eta", scheduler.eta)
            put("lowerOrderFinal", scheduler.lowerOrderFinal)
            put("scaleModelInput", scheduler.scaleModelInput)
            put("expectedTimetableCount", resolution.layers.resolved.timetableCount)
            put("expectedUnetExecutionCount", resolution.layers.resolved.unetExecutionCount)
            profile.tokenizer.bosId?.let { put("tokenizerBosId", it) }
            profile.tokenizer.eosId?.let { put("tokenizerEosId", it) }
            profile.tokenizer.padId?.let { put("tokenizerPadId", it) }
            put("tokenizerMaxLength", profile.tokenizer.maxLength)
            put("tokenizerClip1PadRule", profile.tokenizer.clip1PadRule.name)
            profile.tokenizer.clip2PadRule?.let { put("tokenizerClip2PadRule", it.name) }
            profile.conditioning.textEncoderOutputShapes
                .firstOrNull()
                ?.lastOrNull()
                ?.let { put("expectedConditioningWidth", it) }
            if (profile.runtime == LocalImageRuntime.QNN_HTP) {
                val pixelRange = profile.vae.outputRange
                if (pixelRange == ImagePixelRange.RUNTIME_NATIVE) {
                    invalid(
                        "pixelRange",
                        "QNN image execution requires an explicit VAE output pixel range."
                    )
                }
                put("pixelRange", pixelRange.name)
            }
        }

    fun toNativeParamsJsonString(resolution: ImageExecutionProfileResolution): String =
        toNativeParamsJson(resolution).toString()

    fun parseAndValidate(
        resolution: ImageExecutionProfileResolution,
        nativeExecutionJson: String
    ): ImageValidatedNativeExecution {
        val json = try {
            JSONObject(nativeExecutionJson)
        } catch (error: Throwable) {
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

    private fun validateStableDiffusionTokenEvidence(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: ImageNativeEffectiveExecution,
        nativeEffectiveJson: JSONObject
    ) {
        val resolved = resolution.layers.resolved
        val resolvedCapacity = nativeEffectiveJson.requiredInt("resolvedTokenCount")
        if (resolvedCapacity != resolved.tokenCount) {
            invalid(
                "resolvedTokenCount",
                "Native stable-diffusion.cpp token capacity differs from the resolved contract."
            )
        }
        val tokenizerMaxLength = nativeEffectiveJson.requiredInt("tokenizerMaxLength")
        if (tokenizerMaxLength != resolution.profile.tokenizer.maxLength) {
            invalid(
                "tokenizerMaxLength",
                "Native stable-diffusion.cpp tokenizer capacity differs from the selected profile."
            )
        }
        val positive = nativeEffectiveJson.requiredInt("positiveConditioningTokenCount")
        val negative = nativeEffectiveJson.requiredInt("negativeConditioningTokenCount")
        if (positive <= 0 || negative < 0 || positive + negative != nativeEffective.tokenCount) {
            invalid(
                "tokenCount",
                "Native stable-diffusion.cpp token count must equal its actual positive/negative conditioning evidence."
            )
        }
        if ((resolved.useCfg && negative <= 0) || (!resolved.useCfg && negative != 0)) {
            invalid(
                "negativeConditioningTokenCount",
                "Native negative conditioning evidence differs from the resolved CFG mode."
            )
        }
    }

    private fun validateMnnSanaConditioningEvidence(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: ImageNativeEffectiveExecution,
        nativeEffectiveJson: JSONObject
    ) {
        val resolved = resolution.layers.resolved
        val artifactSha256 = nativeEffectiveJson
            .requiredString("conditioningArtifactSha256")
            .lowercase()
        if (!SHA256.matches(artifactSha256) ||
            artifactSha256 != nativeEffective.promptWeightFingerprint
        ) {
            invalid(
                "conditioningArtifactSha256",
                "MNN Sana must fingerprint the exact executed tokenizer conditioning artifact."
            )
        }
        val conditioningSequenceLength =
            nativeEffectiveJson.requiredInt("conditioningSequenceLength")
        val conditioningBatchSize = nativeEffectiveJson.requiredInt("conditioningBatchSize")
        val conditioningOrder = nativeEffectiveJson.requiredString("conditioningOrder")
        val tokenizerSequenceLength =
            nativeEffectiveJson.requiredInt("tokenizerInputSequenceLength")
        val tokenizerBatchSize = nativeEffectiveJson.requiredInt("tokenizerInputBatchSize")
        val tokenizerNonPaddingTokenCount =
            nativeEffectiveJson.requiredInt("tokenizerNonPaddingTokenCount")
        val tokenizerInputOrder = nativeEffectiveJson.requiredString("tokenizerInputOrder")
        val expectedBatch = if (resolved.useCfg) 2 else 1
        if (conditioningSequenceLength != resolved.tokenCount ||
            conditioningSequenceLength != 256 ||
            conditioningBatchSize != expectedBatch ||
            conditioningOrder != if (resolved.useCfg) {
                "negative_then_positive"
            } else {
                "positive_only"
            }
        ) {
            invalid(
                "conditioningSequenceLength,conditioningBatchSize,conditioningOrder",
                "MNN Sana conditioning output differs from the executed 256-query graph contract."
            )
        }
        if (tokenizerSequenceLength <= 0 || tokenizerBatchSize != expectedBatch ||
            tokenizerNonPaddingTokenCount <= 0 ||
            tokenizerNonPaddingTokenCount.toLong() >
                tokenizerSequenceLength.toLong() * tokenizerBatchSize.toLong() ||
            tokenizerInputOrder != if (resolved.useCfg) {
                "positive_then_negative"
            } else {
                "positive_only"
            }
        ) {
            invalid(
                "tokenizerInputSequenceLength,tokenizerInputBatchSize,tokenizerNonPaddingTokenCount,tokenizerInputOrder",
                "MNN Sana tokenizer input evidence is incomplete or has the wrong source order."
            )
        }
    }

    private fun validateQnnPixelRange(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: JSONObject
    ): ImagePixelRange {
        val expected = resolution.profile.vae.outputRange
        if (expected == ImagePixelRange.RUNTIME_NATIVE) {
            invalid(
                "pixelRange",
                "Resolved QNN image profile must declare an explicit VAE output pixel range."
            )
        }
        val actual = nativeEffective.requiredEnum("pixelRange", ImagePixelRange.entries)
        if (actual == ImagePixelRange.RUNTIME_NATIVE) {
            invalid(
                "pixelRange",
                "Native QNN image execution must report an explicit VAE output pixel range."
            )
        }
        if (actual != expected) {
            val mismatch = ImageExecutionMismatch(
                field = "pixelRange",
                resolved = expected.name,
                nativeEffective = actual.name
            )
            throw ImageNativeExecutionContractException(
                code = EXECUTION_CONTRACT_MISMATCH,
                field = "pixelRange",
                mismatches = listOf(mismatch),
                message = "Native QNN pixel range differs from the resolved VAE contract."
            )
        }
        return actual
    }

    private fun ImageResolvedExecution.toJson(): JSONObject = JSONObject()
        .put("profileId", profileId)
        .put("profileRevision", profileRevision)
        .put("modelFingerprint", modelFingerprint)
        .put("runtime", runtime.name)
        .put("scheduler", scheduler.name)
        .put("predictionType", predictionType.name)
        .put("steps", steps)
        .put("timetableCount", timetableCount)
        .put("unetExecutionCount", unetExecutionCount)
        .put("cfgScale", cfgScale)
        .put("useCfg", useCfg)
        .put("unconditionalBranch", unconditionalBranch)
        .put("tokenizerBackend", tokenizerBackend.name)
        .put("tokenCount", tokenCount)
        .put("promptWeightingSupported", promptWeightingSupported)
        .put("embeddingDiskDataType", embeddingDiskDataType.name)
        .put("vaeScalingLocation", vaeScalingLocation.name)
        .put("vaeScalingFactor", vaeScalingFactor)
        .put("width", width)
        .put("height", height)
        .put("seed", seed)
        .put("graphName", graphName)
        .put("fallback", fallback)

    private fun JSONObject.toNativeEffective(): ImageNativeEffectiveExecution {
        val native = ImageNativeEffectiveExecution(
            profileId = requiredString("profileId"),
            profileRevision = requiredInt("profileRevision"),
            modelFingerprint = requiredString("modelFingerprint"),
            runtime = requiredEnum("runtime", LocalImageRuntime.entries),
            scheduler = requiredEnum("scheduler", ImageSchedulerAlgorithm.entries),
            predictionType = requiredEnum("predictionType", ImagePredictionType.entries),
            steps = requiredInt("steps"),
            timetableCount = requiredInt("timetableCount"),
            unetExecutionCount = requiredInt("unetExecutionCount"),
            cfgScale = requiredDouble("cfgScale"),
            useCfg = requiredBoolean("useCfg"),
            unconditionalBranch = requiredBoolean("unconditionalBranch"),
            tokenizerBackend = requiredEnum("tokenizerBackend", ImageTokenizerBackend.entries),
            tokenCount = requiredInt("tokenCount"),
            promptWeightingSupported = requiredBoolean("promptWeightingSupported"),
            promptWeightingApplied = requiredBoolean("promptWeightingApplied"),
            positiveWeightedTokenCount = requiredInt("positiveWeightedTokenCount"),
            negativeWeightedTokenCount = requiredInt("negativeWeightedTokenCount"),
            promptWeightFingerprint = requiredString("promptWeightFingerprint").lowercase(),
            embeddingDiskDataType = requiredEnum(
                "embeddingDiskDataType",
                ImageEmbeddingDiskDataType.entries
            ),
            vaeScalingLocation = requiredEnum(
                "vaeScalingLocation",
                ImageVaeScalingLocation.entries
            ),
            vaeScalingFactor = requiredDouble("vaeScalingFactor"),
            width = requiredInt("width"),
            height = requiredInt("height"),
            seed = requiredLong("seed"),
            graphName = requiredString("graphName"),
            fallback = requiredBoolean("fallback")
        )
        return validatePromptWeightingEvidence(native)
    }

    internal fun validatePromptWeightingEvidence(
        native: ImageNativeEffectiveExecution
    ): ImageNativeEffectiveExecution {
        if (native.positiveWeightedTokenCount !in 0..native.tokenCount) {
            invalid(
                "positiveWeightedTokenCount",
                "Native positive weighted-token count must fit the executed token capacity."
            )
        }
        if (native.negativeWeightedTokenCount !in 0..native.tokenCount) {
            invalid(
                "negativeWeightedTokenCount",
                "Native negative weighted-token count must fit the executed token capacity."
            )
        }
        if (
            native.positiveWeightedTokenCount + native.negativeWeightedTokenCount >
            native.tokenCount
        ) {
            invalid(
                "positiveWeightedTokenCount,negativeWeightedTokenCount",
                "Native weighted-token counts exceed the executed positive/negative capacity."
            )
        }
        if (!SHA256.matches(native.promptWeightFingerprint)) {
            invalid(
                "promptWeightFingerprint",
                "Native prompt-weight fingerprint must be a 64-character SHA-256 value."
            )
        }
        val weightedTokenCount =
            native.positiveWeightedTokenCount + native.negativeWeightedTokenCount
        if (native.promptWeightingApplied && weightedTokenCount == 0) {
            invalid(
                "promptWeightingApplied",
                "Applied prompt weighting requires at least one weighted prompt token."
            )
        }
        if (!native.promptWeightingApplied && weightedTokenCount != 0) {
            invalid(
                "promptWeightingApplied",
                "Weighted-token counts must be zero when prompt weighting was not applied."
            )
        }
        return native
    }

    private fun JSONObject.requiredValue(field: String): Any {
        if (!has(field) || isNull(field)) invalid(field, "Required native execution field is missing.")
        return get(field)
    }

    private fun JSONObject.requiredString(field: String): String {
        val value = requiredValue(field)
        if (value !is String || value.isBlank()) {
            invalid(field, "Native execution field must be a non-blank string.")
        }
        return value
    }

    private fun JSONObject.requiredBoolean(field: String): Boolean {
        val value = requiredValue(field)
        if (value !is Boolean) invalid(field, "Native execution field must be a boolean.")
        return value
    }

    private fun JSONObject.requiredInt(field: String): Int {
        val value = requiredNumber(field)
        return try {
            BigDecimal(value.toString()).intValueExact()
        } catch (error: ArithmeticException) {
            invalid(field, "Native execution field must be an exact 32-bit integer.", error)
        } catch (error: NumberFormatException) {
            invalid(field, "Native execution field must be an exact 32-bit integer.", error)
        }
    }

    private fun JSONObject.requiredLong(field: String): Long {
        val value = requiredNumber(field)
        return try {
            BigDecimal(value.toString()).longValueExact()
        } catch (error: ArithmeticException) {
            invalid(field, "Native execution field must be an exact 64-bit integer.", error)
        } catch (error: NumberFormatException) {
            invalid(field, "Native execution field must be an exact 64-bit integer.", error)
        }
    }

    private fun JSONObject.requiredDouble(field: String): Double {
        val value = requiredNumber(field).toDouble()
        if (!value.isFinite()) invalid(field, "Native execution field must be finite.")
        return value
    }

    private fun JSONObject.requiredNumber(field: String): Number {
        val value = requiredValue(field)
        if (value !is Number) invalid(field, "Native execution field must be numeric.")
        return value
    }

    private fun <T : Enum<T>> JSONObject.requiredEnum(field: String, values: List<T>): T {
        val wireValue = requiredString(field)
        return values.firstOrNull { it.name == wireValue }
            ?: invalid(field, "Unknown native execution enum value: $wireValue")
    }

    private fun invalid(field: String, message: String, cause: Throwable? = null): Nothing =
        throw ImageNativeExecutionContractException(
            code = IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID,
            field = field,
            message = message,
            cause = cause
        )

    private val SHA256 = Regex("^[0-9a-f]{64}$")
}
