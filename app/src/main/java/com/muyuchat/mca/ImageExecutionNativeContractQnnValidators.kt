package com.muyuchat.mca

import org.json.JSONObject

/** T2B22-T4: QNN/SD/MNN conditioning validators; moved verbatim from ImageExecutionProfileNativeContract.kt. */

internal fun validateStableDiffusionTokenEvidence(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: ImageNativeEffectiveExecution,
        nativeEffectiveJson: JSONObject
    ) {
        val resolved = resolution.layers.resolved
        val resolvedCapacity = nativeEffectiveJson.nativeContractRequiredInt("resolvedTokenCount")
        if (resolvedCapacity != resolved.tokenCount) {
            invalid(
                "resolvedTokenCount",
                "Native stable-diffusion.cpp token capacity differs from the resolved contract."
            )
        }
        val tokenizerMaxLength = nativeEffectiveJson.nativeContractRequiredInt("tokenizerMaxLength")
        if (tokenizerMaxLength != resolution.profile.tokenizer.maxLength) {
            invalid(
                "tokenizerMaxLength",
                "Native stable-diffusion.cpp tokenizer capacity differs from the selected profile."
            )
        }
        val positive = nativeEffectiveJson.nativeContractRequiredInt("positiveConditioningTokenCount")
        val negative = nativeEffectiveJson.nativeContractRequiredInt("negativeConditioningTokenCount")
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

internal fun validateMnnSanaConditioningEvidence(
        resolution: ImageExecutionProfileResolution,
        nativeEffective: ImageNativeEffectiveExecution,
        nativeEffectiveJson: JSONObject
    ) {
        val resolved = resolution.layers.resolved
        val artifactSha256 = nativeEffectiveJson
            .nativeContractRequiredString("conditioningArtifactSha256")
            .lowercase()
        if (!IMAGE_NATIVE_CONTRACT_SHA256.matches(artifactSha256) ||
            artifactSha256 != nativeEffective.promptWeightFingerprint
        ) {
            invalid(
                "conditioningArtifactSha256",
                "MNN Sana must fingerprint the exact executed tokenizer conditioning artifact."
            )
        }
        val conditioningSequenceLength =
            nativeEffectiveJson.nativeContractRequiredInt("conditioningSequenceLength")
        val conditioningBatchSize = nativeEffectiveJson.nativeContractRequiredInt("conditioningBatchSize")
        val conditioningOrder = nativeEffectiveJson.nativeContractRequiredString("conditioningOrder")
        val tokenizerSequenceLength =
            nativeEffectiveJson.nativeContractRequiredInt("tokenizerInputSequenceLength")
        val tokenizerBatchSize = nativeEffectiveJson.nativeContractRequiredInt("tokenizerInputBatchSize")
        val tokenizerNonPaddingTokenCount =
            nativeEffectiveJson.nativeContractRequiredInt("tokenizerNonPaddingTokenCount")
        val tokenizerInputOrder = nativeEffectiveJson.nativeContractRequiredString("tokenizerInputOrder")
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

internal fun validateQnnConditioningExecutionEvidence(
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
        val expectedMode = when {
            qnnTextEncoder -> "qnn_text_encoder"
            sharedSdxlExternalConditioning -> "external_mnn_sdxl_embeddings"
            else -> "external_mnn_embeddings"
        }
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
        val artifactSha256 = nativeEffective.nativeContractRequiredString("conditioningArtifactSha256")
            .lowercase()
        val graphSha256 = nativeEffective.nativeContractRequiredString("conditioningGraphSha256")
            .lowercase()
        val promptWeightFingerprint = nativeEffective.nativeContractRequiredString("promptWeightFingerprint")
            .lowercase()
        val conditioningGraphMatches = when {
            // Signed direct-Chinese evidence accepts either native receipt spelling. Its graph
            // identity is checked alongside the consumed text-encoder asset below.
            hasVerifiedChineseTextEncoderEvidence -> true
            // A generic QNN profile can leave graph selection to the runtime default. Only the
            // verified direct-Chinese path requires an explicit text-encoder graph name.
            qnnTextEncoder && expectedGraph.isBlank() -> true
            else -> nativeEffective.nativeContractRequiredString("conditioningGraph") == expectedGraph
        }
        val mismatches = buildList {
            fun compare(field: String, expected: Any, actual: Any) {
                if (expected != actual) add(ImageExecutionMismatch(field, expected.toString(), actual.toString()))
            }
            if (!IMAGE_NATIVE_CONTRACT_SHA256.matches(artifactSha256)) {
                add(ImageExecutionMismatch("conditioningArtifactSha256", "SHA-256", artifactSha256))
            }
            if (!IMAGE_NATIVE_CONTRACT_SHA256.matches(graphSha256)) {
                add(ImageExecutionMismatch("conditioningGraphSha256", "SHA-256", graphSha256))
            }
            if (!qnnTextEncoder) compare("promptWeightFingerprint", artifactSha256, promptWeightFingerprint)
            compare("conditioningExecutionMode", expectedMode, nativeEffective.nativeContractRequiredString("conditioningExecutionMode"))
            compare("conditioningBackend", expectedBackend, nativeEffective.nativeContractRequiredString("conditioningBackend"))
            if (!conditioningGraphMatches) {
                compare("conditioningGraph", expectedGraph, nativeEffective.nativeContractRequiredString("conditioningGraph"))
            }
            compare("conditioningOrder", expectedOrder, nativeEffective.nativeContractRequiredString("conditioningOrder"))
            compare("conditioningEncoderExecutionCount", expectedExecutionCount, nativeEffective.nativeContractRequiredInt("conditioningEncoderExecutionCount"))
            compare("textEncoderExecutionCount", if (qnnTextEncoder) expectedExecutionCount else 0, nativeEffective.nativeContractRequiredInt("textEncoderExecutionCount"))
            compare("conditioningArtifactConsumed", true, nativeEffective.requiredBoolean("conditioningArtifactConsumed"))
            compare("runtimeSessionMode", expectedRuntimeSessionMode, nativeEffective.nativeContractRequiredString("runtimeSessionMode"))
        }
        if (mismatches.isNotEmpty()) {
            throw ImageNativeExecutionContractException(
                code = IMAGE_NATIVE_EXECUTION_CONTRACT_INVALID,
                field = mismatches.joinToString(",") { it.field },
                mismatches = mismatches,
                message = "QNN conditioning and runtime-session evidence differs from the selected worker strategy: " +
                    mismatches.joinToString("; ") { "${it.field}: expected=${it.resolved}, actual=${it.nativeEffective}" }
            )
        }
    }

    /**
     * A multilingual profile is a semantic claim, so its native receipt must identify the exact
     * immutable QNN context that Android selected.  Do not infer this from UTF-8 transport,
     * graph names, recommendation ids, or device information.
     */
internal fun validateQnnNativeMultilingualTextEncoderEvidence(
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

        val actualPath = nativeEffective.nativeContractRequiredString("consumedTextEncoderPath")
            .replace('\\', '/')
            .trim()
        val actualSha256 = nativeEffective.nativeContractRequiredString("consumedTextEncoderSha256").lowercase()
        val actualSizeBytes = nativeEffective.requiredLong("consumedTextEncoderSizeBytes")
        val conditioningGraphSha256 = nativeEffective.nativeContractRequiredString("conditioningGraphSha256")
            .lowercase()
        val requestedGraphName = nativeEffective.nativeContractRequiredString("textEncoderGraphName").trim()
        val loadedGraphName = nativeEffective.nativeContractRequiredString("loadedTextEncoderGraphName").trim()
        val actualTokenizerPath = nativeEffective.nativeContractRequiredString("consumedTokenizerPath")
            .replace('\\', '/')
            .trim()
        val actualTokenizerSha256 = nativeEffective.nativeContractRequiredString("consumedTokenizerSha256")
            .lowercase()
        val actualTokenizerSizeBytes = nativeEffective.requiredLong("consumedTokenizerSizeBytes")
        val receiptCanonicalPath = nativeEffective
            .nativeContractRequiredString("tokenizerReceiptCanonicalPath")
        val receiptSha256 = nativeEffective.nativeContractRequiredString("tokenizerReceiptSha256").lowercase()
        val receiptSizeBytes = nativeEffective.requiredLong("tokenizerReceiptSizeBytes")
        val receiptBindingStage = nativeEffective.nativeContractRequiredString("tokenizerReceiptBindingStage")
        val actualClosureSha256 = nativeEffective
            .nativeContractRequiredString("consumedPromptToEncoderClosureSha256")
            .lowercase()
        val closureAliasSha256 = nativeEffective
            .nativeContractRequiredString("promptToEncoderClosureSha256")
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
            !IMAGE_NATIVE_CONTRACT_SHA256.matches(actualSha256) ||
            !IMAGE_NATIVE_CONTRACT_SHA256.matches(conditioningGraphSha256) ||
            !IMAGE_NATIVE_CONTRACT_SHA256.matches(actualTokenizerSha256) ||
            !IMAGE_NATIVE_CONTRACT_SHA256.matches(actualClosureSha256) ||
            !nativeEffective.requiredBoolean("consumedTextEncoderAssetVerified") ||
            !nativeEffective.requiredBoolean("consumedTokenizerAssetVerified") ||
            !nativeEffective.requiredBoolean("mnnPromptHandoffVerified") ||
            nativeEffective.nativeContractRequiredString("tokenizerBindingStage") != "tokenizer_consumed" ||
            nativeEffective.nativeContractRequiredString("consumedPromptToEncoderBindingStage") !=
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
