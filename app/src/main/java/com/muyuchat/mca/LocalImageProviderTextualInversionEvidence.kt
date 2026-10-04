package com.muyuchat.mca

import org.json.JSONArray
import org.json.JSONObject

internal data class VerifiedTextualInversionExecutionEvidence(
    val bindingFingerprint: String,
    val bindingStage: String,
    val conditioningConsumptionCount: Long
)

internal fun verifyStableDiffusionTextualInversionEvidence(
    result: JSONObject,
    selection: TextualInversionSelection?,
    expectedNativeMode: String = TextualInversionRuntime.STABLE_DIFFUSION_CPP.nativeMode,
    expectedBindingStage: String = "conditioning_consumed",
    requireConditioningConsumption: Boolean = true
): VerifiedTextualInversionExecutionEvidence {
    val expectedBindings = selection?.bindings.orEmpty()
    val expectedCount = expectedBindings.size
    val expectedMask = if (expectedCount == 0) 0L else (1L shl expectedCount) - 1L
    val expectedFingerprint = selection?.bindingFingerprint.orEmpty()

    fun strictLong(source: JSONObject, field: String, layer: String): Long {
        require(source.has(field) && !source.isNull(field)) {
            "$layer textual inversion evidence is missing $field."
        }
        val number = source.get(field) as? Number
            ?: error("$layer textual inversion evidence $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() && value >= 0L) {
            "$layer textual inversion evidence $field must be a non-negative integer."
        }
        return value
    }

    fun verifyArtifacts(source: JSONObject, layer: String) {
        val artifacts = source.optJSONArray("textualInversions")
            ?: error("$layer textual inversion artifact evidence is missing.")
        require(artifacts.length() == expectedCount) {
            "$layer textual inversion artifact count does not match the request."
        }
        expectedBindings.forEachIndexed { index, binding ->
            val actual = artifacts.optJSONObject(index)
                ?: error("$layer textual inversion artifact $index must be an object.")
            require(actual.optString("id") == binding.artifact.id &&
                actual.optString("trigger") == binding.artifact.trigger &&
                actual.optString("sha256") == binding.artifact.sha256 &&
                strictLong(actual, "sizeBytes", "$layer artifact $index") ==
                    binding.artifact.sizeBytes &&
                actual.optString("format") == binding.artifact.format.wireName &&
                actual.optString("modelFingerprint") == binding.modelFingerprint.lowercase() &&
                actual.optString("tokenizerFingerprint") ==
                    binding.tokenizerFingerprint.lowercase() &&
                actual.optString("profileId") == binding.profileId &&
                strictLong(actual, "profileRevision", "$layer artifact $index") ==
                    binding.profileRevision.toLong() &&
                actual.optString("runtime") == binding.runtime.wireName &&
                actual.optString("bindingFingerprint") == binding.bindingFingerprint
            ) { "$layer textual inversion artifact $index does not match its leased binding." }
        }
    }

    fun verifyExecutionAssets(source: JSONObject, layer: String) {
        val expected = selection?.executionAssetBinding ?: return
        val assets = source.optJSONArray("textualInversionExecutionAssets")
            ?: error("$layer textual inversion execution-asset evidence is missing.")
        require(source.optString("textualInversionExecutionAssetsSha256") == expected.compositeSha256 &&
            source.optString("textualInversionExecutionRuntime") == expected.runtime.wireName &&
            source.optString("textualInversionExecutionBundleRoot") == expected.bundleRoot &&
            source.optString("textualInversionExecutionProfileFingerprint") ==
                expected.profilePromptFingerprint &&
            assets.length() == expected.assets.size
        ) { "$layer textual inversion execution-asset binding does not match the leased snapshot." }
        expected.assets.forEachIndexed { index, descriptor ->
            val actual = assets.optJSONObject(index)
                ?: error("$layer textual inversion execution asset $index must be an object.")
            require(actual.optString("label") == descriptor.label &&
                actual.optString("path") == descriptor.path &&
                strictLong(actual, "sizeBytes", "$layer execution asset $index") ==
                    descriptor.sizeBytes &&
                actual.optString("sha256") == descriptor.sha256
            ) { "$layer textual inversion execution asset $index differs from the leased bytes." }
        }
    }

    fun verifyEvidence(source: JSONObject, layer: String): VerifiedTextualInversionExecutionEvidence {
        val evidence = source.optJSONObject("textualInversionEvidence")
            ?: error("$layer textual inversion execution evidence is missing.")
        val requestedCount = strictLong(evidence, "requestedCount", layer)
        val validatedCount = strictLong(evidence, "validatedCount", layer)
        val loadAttemptCount = strictLong(evidence, "loadAttemptCount", layer)
        val loadedCount = strictLong(evidence, "loadedCount", layer)
        val tokenizerMatchCount = strictLong(evidence, "tokenizerMatchCount", layer)
        val appliedCount = strictLong(evidence, "appliedCount", layer)
        val appliedVectorCount = strictLong(evidence, "appliedVectorCount", layer)
        val consumptionCount = strictLong(evidence, "conditioningConsumptionCount", layer)
        val clipLAppliedCount = strictLong(evidence, "clipLAppliedCount", layer)
        val clipGAppliedCount = strictLong(evidence, "clipGAppliedCount", layer)
        val requestedMask = strictLong(evidence, "requestedMask", layer)
        val loadedMask = strictLong(evidence, "loadedMask", layer)
        val tokenizerMatchMask = strictLong(evidence, "tokenizerMatchMask", layer)
        val appliedMask = strictLong(evidence, "appliedMask", layer)
        val consumedMask = strictLong(evidence, "consumedMask", layer)
        val clipLMask = strictLong(evidence, "clipLMask", layer)
        val clipGMask = strictLong(evidence, "clipGMask", layer)
        val clipGRequiredMask = strictLong(evidence, "clipGRequiredMask", layer)
        val bindingFingerprint = evidence.optString("bindingFingerprint")
        val nativeMode = evidence.optString("nativeMode")
        val bindingStage = evidence.optString("bindingStage")

        val expectedConsumedMask = if (requireConditioningConsumption) expectedMask else 0L
        val consumptionComplete = if (requireConditioningConsumption) {
            if (expectedCount == 0) consumptionCount == 0L else consumptionCount >= expectedCount.toLong()
        } else {
            consumptionCount == 0L
        }
        require(requestedCount == expectedCount.toLong() &&
            validatedCount == expectedCount.toLong() &&
            loadAttemptCount == expectedCount.toLong() &&
            loadedCount == expectedCount.toLong() &&
            tokenizerMatchCount == expectedCount.toLong() &&
            appliedCount == expectedCount.toLong() &&
            consumptionComplete &&
            requestedMask == expectedMask && loadedMask == expectedMask &&
            tokenizerMatchMask == expectedMask && appliedMask == expectedMask &&
            consumedMask == expectedConsumedMask && clipLMask == expectedMask &&
            clipLAppliedCount == expectedCount.toLong() &&
            (clipGRequiredMask == 0L || clipGRequiredMask == expectedMask) &&
            clipGMask == clipGRequiredMask &&
            clipGAppliedCount == java.lang.Long.bitCount(clipGRequiredMask).toLong() &&
            evidence.optString("failureCode") == "none"
        ) { "$layer textual inversion execution was incomplete or internally inconsistent." }

        if (expectedCount == 0) {
            require(appliedVectorCount == 0L && consumptionCount == 0L &&
                bindingFingerprint.isEmpty() &&
                nativeMode == "none" && bindingStage == "none"
            ) { "$layer unexpectedly reported textual inversion execution." }
        } else {
            require(appliedVectorCount >= expectedCount.toLong() &&
                bindingFingerprint == expectedFingerprint &&
                nativeMode == expectedNativeMode &&
                bindingStage == expectedBindingStage
            ) { "$layer textual inversion binding evidence does not prove native consumption." }
        }
        return VerifiedTextualInversionExecutionEvidence(
            bindingFingerprint = bindingFingerprint,
            bindingStage = bindingStage,
            conditioningConsumptionCount = consumptionCount
        )
    }

    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("stable-diffusion.cpp result is missing nativeEffective evidence.")
    verifyExecutionAssets(result, "outer")
    verifyExecutionAssets(nativeEffective, "nativeEffective")
    verifyArtifacts(result, "outer")
    verifyArtifacts(nativeEffective, "nativeEffective")
    val outer = verifyEvidence(result, "outer")
    val inner = verifyEvidence(nativeEffective, "nativeEffective")
    require(outer == inner) {
        "Outer and nativeEffective textual inversion evidence conflict."
    }
    return inner
}

internal fun verifyEncodedMnnTextualInversionEvidence(
    encoded: JSONObject,
    selection: TextualInversionSelection,
    runtime: TextualInversionRuntime
) {
    require(!encoded.has("nativeEffective")) {
        "The standalone MNN conditioner must not impersonate final runtime consumption evidence."
    }
    val wrapper = JSONObject(encoded.toString())
        .put("nativeEffective", JSONObject(encoded.toString()))
    verifyStableDiffusionTextualInversionEvidence(
        result = wrapper,
        selection = selection,
        expectedNativeMode = runtime.nativeMode,
        expectedBindingStage = "conditioning_encoded",
        requireConditioningConsumption = false
    )
}

internal fun promoteConsumedQnnTextualInversionEvidence(
    result: JSONObject,
    encoded: JSONObject,
    selection: TextualInversionSelection,
    expectedConditioningSha256: String,
    splitWorkers: Boolean
): VerifiedTextualInversionExecutionEvidence {
    verifyEncodedMnnTextualInversionEvidence(
        encoded = encoded,
        selection = selection,
        runtime = TextualInversionRuntime.QNN_HTP
    )
    val nativeEffective = result.optJSONObject("nativeEffective")
        ?: error("QNN textual inversion requires final nativeEffective evidence.")
    require(result.optString("conditioningArtifactSha256").lowercase() == expectedConditioningSha256 &&
        nativeEffective.optString("conditioningArtifactSha256").lowercase() == expectedConditioningSha256
    ) { "QNN textual inversion conditioning bytes differ from the native-consumed artifact." }
    if (splitWorkers) {
        require(result.optInt("unetExecutionCount", 0) > 0 &&
            nativeEffective.optInt("unetExecutionCount", 0) > 0 &&
            result.optJSONObject("sdxlPhaseProof") != null &&
            nativeEffective.optJSONObject("sdxlPhaseProof") != null
        ) { "Split QNN textual inversion lacks the completed UNet phase proof." }
    } else {
        require(result.optBoolean("conditioningArtifactConsumed", false) &&
            nativeEffective.optBoolean("conditioningArtifactConsumed", false)
        ) { "Shared QNN textual inversion conditioning was not consumed by native UNet execution." }
    }
    require(!result.has("textualInversions") && !result.has("textualInversionEvidence") &&
        !nativeEffective.has("textualInversions") &&
        !nativeEffective.has("textualInversionEvidence")
    ) { "QNN runtime returned untrusted textual inversion evidence instead of the MNN encoder proof." }

    val expectedCount = selection.bindings.size
    val expectedMask = (1L shl expectedCount) - 1L
    val artifacts = encoded.getJSONArray("textualInversions")
    val consumedEvidence = JSONObject(encoded.getJSONObject("textualInversionEvidence").toString())
        .put("conditioningConsumptionCount", expectedCount)
        .put("consumedMask", expectedMask)
        .put("bindingStage", "conditioning_consumed")
    result
        .put("textualInversions", JSONArray(artifacts.toString()))
        .put("textualInversionEvidence", JSONObject(consumedEvidence.toString()))
    nativeEffective
        .put("textualInversions", JSONArray(artifacts.toString()))
        .put("textualInversionEvidence", JSONObject(consumedEvidence.toString()))
    selection.executionAssetBinding?.let {
        val assets = encoded.getJSONArray("textualInversionExecutionAssets")
        listOf(result, nativeEffective).forEach { target ->
            target
                .put("textualInversionExecutionAssets", JSONArray(assets.toString()))
                .put(
                    "textualInversionExecutionAssetsSha256",
                    encoded.getString("textualInversionExecutionAssetsSha256")
                )
                .put(
                    "textualInversionExecutionRuntime",
                    encoded.getString("textualInversionExecutionRuntime")
                )
                .put(
                    "textualInversionExecutionBundleRoot",
                    encoded.getString("textualInversionExecutionBundleRoot")
                )
                .put(
                    "textualInversionExecutionProfileFingerprint",
                    encoded.getString("textualInversionExecutionProfileFingerprint")
                )
        }
    }
    return verifyStableDiffusionTextualInversionEvidence(
        result = result,
        selection = selection,
        expectedNativeMode = TextualInversionRuntime.QNN_HTP.nativeMode
    )
}
