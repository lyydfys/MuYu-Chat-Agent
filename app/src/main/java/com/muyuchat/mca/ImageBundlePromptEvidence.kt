package com.muyuchat.mca

import com.muyuchat.api.local.imagePromptExecutionSha256
import org.json.JSONObject

/**
 * T2B22-T5（b 工位）拆片：原生 prompt 证据（NativePromptEncodingEvidence /
 * requireNativePromptEncodingEvidence / putPromptExecutionBinding）自
 * LocalImageProviderBundleManifest.kt 逐字节平移；全部已是 internal，零改写。
 */

// Provider binding for the effective strings handed to the selected runtime. Runtime-specific
// verifiers independently establish native graph and conditioning execution.
internal data class NativePromptEncodingEvidence(
    val promptWeightingApplied: Boolean,
    val positiveWeightedTokenCount: Int,
    val negativeWeightedTokenCount: Int,
    val promptWeightFingerprint: String,
    val nativePromptExecutionSha256: String,
    val nativePromptBindingStage: String
)

internal fun requireNativePromptEncodingEvidence(
    source: JSONObject,
    prompt: String,
    negativePrompt: String?,
    requireNoAppliedWeights: Boolean = false
): NativePromptEncodingEvidence {
    fun strictBoolean(field: String): Boolean =
        (source.opt(field) as? Boolean)
            ?: error("Native prompt encoding evidence field $field must be boolean.")

    fun strictNonNegativeInt(field: String): Int {
        val number = source.opt(field) as? Number
            ?: error("Native prompt encoding evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble() &&
            value in 0L..Int.MAX_VALUE.toLong()
        ) { "Native prompt encoding evidence field $field must be a non-negative integer." }
        return value.toInt()
    }

    fun strictSha256(field: String): String {
        val value = source.opt(field) as? String
            ?: error("Native prompt encoding evidence field $field must be a string.")
        require(Regex("[0-9a-f]{64}").matches(value)) {
            "Native prompt encoding evidence field $field must be a lowercase SHA-256 value."
        }
        return value
    }

    val applied = strictBoolean("promptWeightingApplied")
    val positiveCount = strictNonNegativeInt("positiveWeightedTokenCount")
    val negativeCount = strictNonNegativeInt("negativeWeightedTokenCount")
    require(applied == (positiveCount.toLong() + negativeCount.toLong() > 0L)) {
        "Native prompt weighting flag conflicts with its token counts."
    }
    if (requireNoAppliedWeights) {
        require(!applied && positiveCount == 0 && negativeCount == 0) {
            "The non-weighted QNN token path cannot accept applied prompt weights."
        }
    }
    val promptSha256 = strictSha256("nativePromptExecutionSha256")
    require(promptSha256 == imagePromptExecutionSha256(prompt, negativePrompt)) {
        "Native prompt encoding evidence does not match the effective positive and negative prompts."
    }
    val stage = source.opt("nativePromptBindingStage") as? String
        ?: error("Native prompt encoding evidence field nativePromptBindingStage must be a string.")
    require(stage == "conditioning_encoded") {
        "Native prompt encoding evidence must be published at conditioning_encoded."
    }
    return NativePromptEncodingEvidence(
        promptWeightingApplied = applied,
        positiveWeightedTokenCount = positiveCount,
        negativeWeightedTokenCount = negativeCount,
        promptWeightFingerprint = strictSha256("promptWeightFingerprint"),
        nativePromptExecutionSha256 = promptSha256,
        nativePromptBindingStage = stage
    )
}

internal fun JSONObject.putPromptExecutionBinding(
    profile: ImageExecutionProfile,
    prompt: String,
    negativePrompt: String?
): JSONObject {
    val expectedPromptSha256 = imagePromptExecutionSha256(prompt, negativePrompt)
    val nativeEffective = optJSONObject("nativeEffective")
        ?: error("Native image execution metadata is missing nativeEffective prompt evidence.")
    fun requireConsumedPromptEvidence(source: JSONObject, layer: String) {
        val sha256 = source.opt("nativePromptExecutionSha256") as? String
            ?: error("Native $layer prompt execution SHA-256 must be a string.")
        val stage = source.opt("nativePromptBindingStage") as? String
            ?: error("Native $layer prompt binding stage must be a string.")
        require(sha256 == expectedPromptSha256) {
            "Native $layer prompt evidence does not match the effective prompts."
        }
        require(stage == "conditioning_consumed") {
            "Native $layer prompt evidence was not published after conditioning consumption."
        }
    }
    requireConsumedPromptEvidence(this, "outer")
    requireConsumedPromptEvidence(nativeEffective, "nativeEffective")
    require(
        getString("nativePromptExecutionSha256") ==
            nativeEffective.getString("nativePromptExecutionSha256") &&
            getString("nativePromptBindingStage") ==
            nativeEffective.getString("nativePromptBindingStage")
    ) { "Native outer and nativeEffective prompt evidence conflict." }
    profile.verifiedNativeSimplifiedChineseLanguageProofSha256()?.let { expectedProof ->
        val nativeProof = nativeEffective.opt("languageProofSha256") as? String
            ?: error("Native multilingual receipt is missing languageProofSha256.")
        require(nativeProof == expectedProof) {
            "Native multilingual receipt returned a different text-encoder semantic proof."
        }
        put("languageProofSha256", expectedProof)
    }
    return put("imageProfileBindingFingerprint", profile.bindingFingerprint)
        .put("promptLanguageBindingFingerprint", profile.promptLanguageBindingFingerprint)
        .put("textEncoderLanguageCapability", profile.textEncoderLanguageCapability().name)
        .put("promptExecutionSha256", expectedPromptSha256)
}
