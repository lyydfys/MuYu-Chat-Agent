package com.muyuchat.mca

import org.json.JSONObject

/**
 * Binds Qwen's native execution receipt to the selected image profile and the
 * exact PNG committed by the provider. Qwen uses a distinct execution schema;
 * these fields must be mirrored into nativeEffective for the Local API to
 * distinguish a real MNN run from a configured-but-fallback result.
 */
internal fun bindQwenImage21ExecutionEvidence(
    execution: JSONObject,
    profileId: String,
    profileRevision: Int,
    modelFingerprint: String,
    profileBindingFingerprint: String,
    promptLanguageBindingFingerprint: String,
    runtime: String,
    width: Int,
    height: Int,
    steps: Int,
    seed: Int,
    outputBytes: Long,
    outputSha256: String
): JSONObject {
    require(profileId == QWEN_IMAGE_21_PROFILE_ID) {
        "Qwen execution evidence is bound to an unexpected profile."
    }
    require(runtime == QWEN_IMAGE_21_RUNTIME) {
        "Qwen execution evidence is bound to an unexpected runtime."
    }
    require(profileRevision > 0 && QwenImage21SizeContract.isSupported(width, height) && steps >= 2 &&
        seed >= 0 && outputBytes > 0L && SHA256.matches(modelFingerprint) &&
        SHA256.matches(profileBindingFingerprint) &&
        SHA256.matches(promptLanguageBindingFingerprint) && SHA256.matches(outputSha256)
    ) {
        "Qwen execution evidence contains an invalid profile, verified size, request, or output binding."
    }

    val native = execution.optJSONObject("nativeEffective")
        ?: error("Qwen execution evidence is missing nativeEffective.")
    val promptSha256 = execution.optString("nativePromptExecutionSha256")
    require(SHA256.matches(promptSha256) &&
        execution.optString("nativePromptBindingStage") == "conditioning_consumed" &&
        native.optString("nativePromptExecutionSha256") == promptSha256 &&
        native.optString("nativePromptBindingStage") == "conditioning_consumed"
    ) { "Qwen native prompt evidence is missing or inconsistent." }

    val paired = JSONObject()
        .put("executionSchema", QWEN_IMAGE_21_EXECUTION_SCHEMA)
        .put("profileId", profileId)
        .put("profileRevision", profileRevision)
        .put("modelFingerprint", modelFingerprint.lowercase())
        .put("profileBindingFingerprint", profileBindingFingerprint.lowercase())
        .put("imageProfileBindingFingerprint", profileBindingFingerprint.lowercase())
        .put("promptLanguageBindingFingerprint", promptLanguageBindingFingerprint.lowercase())
        .put("runtime", runtime)
        .put("taskMode", "text_to_image")
        .put("scheduler", "flow_match")
        .put("width", width)
        .put("height", height)
        .put("steps", steps)
        .put("seed", seed)
        .put("batchCount", 1)
        .put("cfgScale", 1.0)
        .put("promptExecutionSha256", promptSha256)
        .put("outputSha256", outputSha256.lowercase())
        .put("outputBytes", outputBytes)

    val keys = paired.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val value = paired.get(key)
        execution.put(key, value)
        native.put(key, value)
    }
    return execution
}

private const val QWEN_IMAGE_21_EXECUTION_SCHEMA = "qwen_image_21_mnn_v1"
private const val QWEN_IMAGE_21_PROFILE_ID = "mnn.qwen-image-2.1.opencl"
private const val QWEN_IMAGE_21_RUNTIME = "MNN_DIFFUSION"
private val SHA256 = Regex("[a-f0-9]{64}")
