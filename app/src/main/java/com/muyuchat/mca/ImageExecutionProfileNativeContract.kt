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

    fun toNativeParamsJson(resolution: ImageExecutionProfileResolution): JSONObject =
        resolution.layers.resolved.toJson().apply {
            val profile = resolution.profile
            val scheduler = profile.scheduler
            val resolved = resolution.layers.resolved
            if (scheduler.algorithm != resolved.scheduler ||
                scheduler.predictionType != resolved.predictionType
            ) {
                invalid(
                    "scheduler",
                    "Resolved scheduler details disagree with the execution-layer algorithm or prediction type."
                )
            }
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
            profile.verifiedNativeSimplifiedChineseLanguageProofSha256()?.let { proofSha256 ->
                // Native receives only the verified opaque digest, never the publisher signature
                // or app signing certificate. It must echo this after consuming the same closure.
                put("languageProofSha256", proofSha256)
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

    private fun validateQnnConditioningExecutionEvidence(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: JSONObject
    ) {
        val strategy = resolution.profile.graph.workerStrategy
        if (strategy != ImageWorkerStrategy.SHARED_UNET_VAE &&
            strategy != ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        ) {
            return
        }
        val resolved = resolution.layers.resolved
        val qnnTextEncoder = strategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        val control = resolution.profile.task == ImageTask.CONTROL_IMAGE
        // Shared SDXL keeps its MNN dual-CLIP handoff ABI even when the UNet
        // is distilled to a positive-only sampling pass.
        val sharedSdxlExternalConditioning =
            resolution.profile.family == LocalImageModelFamily.SDXL && !qnnTextEncoder
        val expectedMode = if (qnnTextEncoder) "qnn_text_encoder" else "external_mnn_embeddings"
        val expectedBackend = if (qnnTextEncoder) "QNN" else "MNN"
        val hasVerifiedChineseTextEncoderEvidence = qnnTextEncoder &&
            resolution.profile.hasVerifiedNativeSimplifiedChineseTextEncoder()
        val expectedGraph = when {
            sharedSdxlExternalConditioning -> "clip.mnn+clip_2.mnn"
            qnnTextEncoder -> {
                resolution.profile.graph.textEncoder?.graphName.orEmpty()
            }
            else -> {
                resolution.profile.graph.textEncoder
                    ?.relativePath
                    .orEmpty()
                    .substringAfterLast('/')
                    .substringAfterLast('\\')
            }
        }
        val expectedExecutionCount = if (sharedSdxlExternalConditioning) {
            4
        } else if (resolved.useCfg) {
            2
        } else {
            1
        }
        val expectedOrder = if (sharedSdxlExternalConditioning || resolved.useCfg) {
            "negative_then_positive"
        } else {
            "positive_only"
        }
        val expectedRuntimeSessionMode = when {
            qnnTextEncoder && control -> "shared_text_unet_controlnet_vae"
            qnnTextEncoder -> "shared_text_unet_vae"
            control -> "shared_unet_controlnet_vae"
            else -> "shared_unet_vae"
        }
        val artifactSha256 = nativeEffective.requiredString("conditioningArtifactSha256")
            .lowercase()
        val graphSha256 = nativeEffective.requiredString("conditioningGraphSha256")
            .lowercase()
        val promptWeightFingerprint = nativeEffective.requiredString("promptWeightFingerprint")
            .lowercase()
        val conditioningGraphMatches = when {
            // Signed direct-Chinese evidence accepts either native receipt spelling. Its graph
            // identity is checked alongside the consumed text-encoder asset below.
            hasVerifiedChineseTextEncoderEvidence -> true
            // A generic QNN profile can leave graph selection to the runtime default. Only the
            // verified direct-Chinese path requires an explicit text-encoder graph name.
            qnnTextEncoder && expectedGraph.isBlank() -> true
            else -> nativeEffective.requiredString("conditioningGraph") == expectedGraph
        }
        if (!SHA256.matches(artifactSha256) ||
            !SHA256.matches(graphSha256) ||
            (!qnnTextEncoder && promptWeightFingerprint != artifactSha256) ||
            nativeEffective.requiredString("conditioningExecutionMode") != expectedMode ||
            nativeEffective.requiredString("conditioningBackend") != expectedBackend ||
            !conditioningGraphMatches ||
            nativeEffective.requiredString("conditioningOrder") != expectedOrder ||
            nativeEffective.requiredInt("conditioningEncoderExecutionCount") !=
            expectedExecutionCount ||
            nativeEffective.requiredInt("textEncoderExecutionCount") !=
            (if (qnnTextEncoder) expectedExecutionCount else 0) ||
            !nativeEffective.requiredBoolean("conditioningArtifactConsumed") ||
            nativeEffective.requiredString("runtimeSessionMode") != expectedRuntimeSessionMode
        ) {
            invalid(
                "conditioningExecutionMode,conditioningBackend,conditioningGraph," +
                    "conditioningGraphSha256,promptWeightFingerprint,conditioningOrder," +
                    "conditioningEncoderExecutionCount,textEncoderExecutionCount," +
                    "conditioningArtifactConsumed,runtimeSessionMode",
                "QNN conditioning and runtime-session evidence differs from the selected worker strategy."
            )
        }
    }

    /**
     * A multilingual profile is a semantic claim, so its native receipt must identify the exact
     * immutable QNN context that Android selected.  Do not infer this from UTF-8 transport,
     * graph names, recommendation ids, or device information.
     */
    private fun validateQnnNativeMultilingualTextEncoderEvidence(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: JSONObject
    ) {
        val profile = resolution.profile
        if (!profile.hasVerifiedNativeSimplifiedChineseTextEncoder()) return

        require(profile.runtime == LocalImageRuntime.QNN_HTP &&
            profile.graph.workerStrategy == ImageWorkerStrategy.SHARED_TEXT_UNET_VAE
        ) {
            "Verified direct Chinese QNN execution requires a shared native text-encoder graph."
        }
        val evidence = requireNotNull(profile.textEncoderLanguage?.evidence) {
            "Verified direct Chinese QNN execution requires pinned text-encoder evidence."
        }
        val expectedClosureAssets = evidence.promptToEncoderAssets
            .associate { entry -> entry.role to entry.asset }
        val expectedTokenizer = expectedClosureAssets[ImagePromptToEncoderAssetRole.TOKENIZER_JSON]
            ?: invalid(
            "textEncoderLanguage.evidence.promptToEncoderAssets",
            "Verified direct Chinese QNN execution requires a TOKENIZER_JSON closure asset."
        )
        val expectedPath = evidence.textEncoderAsset.relativePath
            .replace('\\', '/')
            .trim()
        val expectedSha256 = evidence.textEncoderAsset.fingerprint.lowercase()
        val expectedSizeBytes = requireNotNull(evidence.textEncoderAsset.sizeBytes)
        val expectedGraphName = profile.graph.textEncoder
            ?.graphName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: invalid(
                "graph.textEncoder.graphName",
                "Verified direct Chinese QNN execution requires an explicit text-encoder graph name."
            )

        val actualPath = nativeEffective.requiredString("consumedTextEncoderPath")
            .replace('\\', '/')
            .trim()
        val actualSha256 = nativeEffective.requiredString("consumedTextEncoderSha256").lowercase()
        val actualSizeBytes = nativeEffective.requiredLong("consumedTextEncoderSizeBytes")
        val conditioningGraphSha256 = nativeEffective.requiredString("conditioningGraphSha256")
            .lowercase()
        val requestedGraphName = nativeEffective.requiredString("textEncoderGraphName").trim()
        val loadedGraphName = nativeEffective.requiredString("loadedTextEncoderGraphName").trim()
        val actualTokenizerPath = nativeEffective.requiredString("consumedTokenizerPath")
            .replace('\\', '/')
            .trim()
        val actualTokenizerSha256 = nativeEffective.requiredString("consumedTokenizerSha256")
            .lowercase()
        val actualTokenizerSizeBytes = nativeEffective.requiredLong("consumedTokenizerSizeBytes")
        val receiptCanonicalPath = nativeEffective
            .requiredString("tokenizerReceiptCanonicalPath")
        val receiptSha256 = nativeEffective.requiredString("tokenizerReceiptSha256").lowercase()
        val receiptSizeBytes = nativeEffective.requiredLong("tokenizerReceiptSizeBytes")
        val receiptBindingStage = nativeEffective.requiredString("tokenizerReceiptBindingStage")
        val actualClosureSha256 = nativeEffective
            .requiredString("consumedPromptToEncoderClosureSha256")
            .lowercase()
        val closureAliasSha256 = nativeEffective
            .requiredString("promptToEncoderClosureSha256")
            .lowercase()
        val expectedClosureSha256 = evidence.promptToEncoderClosureSha256()
        if (actualPath != expectedPath ||
            actualSha256 != expectedSha256 ||
            conditioningGraphSha256 != expectedSha256 ||
            actualSizeBytes != expectedSizeBytes ||
            requestedGraphName != expectedGraphName ||
            loadedGraphName != expectedGraphName ||
            actualTokenizerPath != expectedTokenizer.relativePath.replace('\\', '/').trim() ||
            actualTokenizerSha256 != expectedTokenizer.fingerprint.lowercase() ||
            actualTokenizerSizeBytes != expectedTokenizer.sizeBytes ||
            !receiptCanonicalPath.isSafeCanonicalNativePath() ||
            receiptSha256 != expectedTokenizer.fingerprint.lowercase() ||
            receiptSizeBytes != expectedTokenizer.sizeBytes ||
            receiptBindingStage != "tokenizer_consumed" ||
            actualClosureSha256 != expectedClosureSha256 ||
            closureAliasSha256 != expectedClosureSha256 ||
            !SHA256.matches(actualSha256) ||
            !SHA256.matches(conditioningGraphSha256) ||
            !SHA256.matches(actualTokenizerSha256) ||
            !SHA256.matches(actualClosureSha256) ||
            !nativeEffective.requiredBoolean("consumedTextEncoderAssetVerified") ||
            !nativeEffective.requiredBoolean("consumedTokenizerAssetVerified") ||
            !nativeEffective.requiredBoolean("mnnPromptHandoffVerified") ||
            nativeEffective.requiredString("tokenizerBindingStage") != "tokenizer_consumed" ||
            nativeEffective.requiredString("consumedPromptToEncoderBindingStage") !=
                "conditioning_consumed" ||
            !nativeEffective.matchesPromptToEncoderClosure(expectedClosureAssets)
        ) {
            invalid(
                "consumedTextEncoderPath,consumedTextEncoderSha256," +
                    "consumedTextEncoderSizeBytes,consumedTokenizerPath,consumedTokenizerSha256," +
                    "consumedTokenizerSizeBytes,tokenizerReceiptCanonicalPath," +
                    "tokenizerReceiptSha256,tokenizerReceiptSizeBytes," +
                    "mnnPromptHandoffVerified," +
                    "consumedPromptToEncoderAssets," +
                    "consumedPromptToEncoderClosureSha256,promptToEncoderClosureSha256",
                "QNN native prompt-to-encoder receipt differs from the evidence-bound multilingual profile."
            )
        }
    }

    private fun String.isSafeCanonicalNativePath(): Boolean =
        startsWith('/') &&
            length < 4_096 &&
            '\\' !in this &&
            "//" !in this &&
            "/./" !in this &&
            "/../" !in this &&
            !endsWith("/.") &&
            !endsWith("/..") &&
            '\u0000' !in this

    /** Compares the native role closure exactly; no path, role, or duplicate may be ignored. */
    private fun JSONObject.matchesPromptToEncoderClosure(
        expected: Map<ImagePromptToEncoderAssetRole, ImageProfileAsset>
    ): Boolean {
        val values = requiredValue("consumedPromptToEncoderAssets") as? org.json.JSONArray
            ?: invalid(
                "consumedPromptToEncoderAssets",
                "Native QNN prompt-to-encoder receipt must be an array."
            )
        if (values.length() != expected.size) return false
        val consumed = linkedMapOf<ImagePromptToEncoderAssetRole, ImageProfileAsset>()
        for (index in 0 until values.length()) {
            val entry = values.optJSONObject(index) ?: invalid(
                "consumedPromptToEncoderAssets[$index]",
                "Native QNN prompt-to-encoder receipt entry must be an object."
            )
            val role = entry.requiredEnum(
                "role",
                ImagePromptToEncoderAssetRole.entries
            )
            val asset = ImageProfileAsset(
                relativePath = entry.requiredString("path"),
                fingerprint = entry.requiredString("sha256"),
                sizeBytes = entry.requiredLong("sizeBytes")
            )
            if (consumed.put(role, asset) != null) return false
        }
        return consumed.all { (role, actual) ->
            val expectedAsset = expected[role] ?: return@all false
            actual.relativePath.replace('\\', '/').trim() ==
                expectedAsset.relativePath.replace('\\', '/').trim() &&
                actual.fingerprint.lowercase() == expectedAsset.fingerprint.lowercase() &&
                actual.sizeBytes == expectedAsset.sizeBytes
        }
    }

    /**
     * A native receipt may claim that an encoder file was consumed, but that alone does not prove
     * its language semantics. Android verifies the publisher signature first and requires native
     * to return the exact resulting payload digest only after conditioning consumed that closure.
     */
    private fun validateNativeMultilingualLanguageProofReceipt(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: JSONObject
    ) {
        val expected = resolution.profile.verifiedNativeSimplifiedChineseLanguageProofSha256()
            ?: return
        val actual = nativeEffective.requiredString("languageProofSha256").lowercase()
        if (!SHA256.matches(actual) || actual != expected) {
            invalid(
                "languageProofSha256",
                "Native multilingual receipt did not bind Android's verified text-encoder semantic proof."
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
