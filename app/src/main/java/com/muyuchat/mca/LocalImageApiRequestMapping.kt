package com.muyuchat.mca

import com.muyuchat.api.local.ImageGenerationApiRequest
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal data class LocalImageApiDispatch(
    val options: LocalImageGenerationOptions,
    val inputDraft: LocalImageInputDraft
)

internal fun sanitizedLocalImageApiExecution(raw: String): JSONObject {
    val sanitized = sanitizeNativeExecutionJson(raw)
    require(sanitized.isNotBlank()) { "Local image execution metadata is invalid." }
    return JSONObject(sanitized).also(::removeEmptyUnusedImageRoleDigests)
}

private fun removeEmptyUnusedImageRoleDigests(execution: JSONObject) {
    val layers = listOfNotNull(execution, execution.optJSONObject("nativeEffective"))
    listOf("inputImage", "maskImage", "controlImage").forEach { role ->
        val countField = "${role}ExecutionCount"
        val digestField = "${role}Sha256"
        layers.forEach { layer ->
            val count = (layer.opt(countField) as? Number)?.toLong()
            if (count == 0L && (layer.opt(digestField) as? String)?.isEmpty() == true) {
                layer.remove(digestField)
            }
        }
    }
}

internal fun localImageApiResponseOutputEvidence(outputs: List<LocalImageOutput>): JSONArray {
    require(outputs.isNotEmpty() && outputs.map(LocalImageOutput::index) == outputs.indices.toList()) {
        "Local API output evidence requires contiguous non-empty outputs."
    }
    return JSONArray().apply {
        outputs.forEach { output ->
            require(output.mimeType == "image/png") {
                "Local API image generation output must use image/png."
            }
            val sha256 = MessageDigest.getInstance("SHA-256")
                .digest(output.bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
            put(
                JSONObject()
                    .put("index", output.index)
                    .put("mimeType", output.mimeType)
                    .put("sizeBytes", output.bytes.size)
                    .put("sha256", sha256)
            )
        }
    }
}

/**
 * Local API execution-evidence gate. QNN keeps its nativeEffective/partition
 * contract; MediaTek proves its own native path (Neuron backend, runtime and
 * output digest) instead of borrowing QNN-shaped fields. Both fail closed.
 */
internal fun requireNativeImageExecutionEvidence(execution: JSONObject, runtime: LocalImageRuntime) {
    if (runtime == LocalImageRuntime.MEDIATEK_NEURON) {
        require(
            execution.optString("backend") == runtime.name && execution.optString("runtime") == runtime.name
        ) {
            "The local image result did not identify the MediaTek Neuron backend."
        }
        require(execution.optString("outputSha256").isNotBlank()) {
            "The local image result is missing its output digest."
        }
        require(!execution.optBoolean("fallback", false)) {
            "The MediaTek image result must not report a fallback."
        }
        return
    }
    val nativeEffective = execution.optJSONObject("nativeEffective")
        ?: throw IllegalArgumentException("The local image result is missing nativeEffective execution evidence.")
    require(
        execution.optBoolean("nativeExecution", false) && !execution.optBoolean("fallback", true)
    ) {
        "The local image result did not prove direct native execution without fallback."
    }
    require(execution.optLong("nativeGenerationSequence", 0L) > 0L) {
        "The local image result is missing a positive native generation sequence."
    }
    require(nativeEffective.getString("runtime") == runtime.name) {
        "The local image result runtime does not match the selected model runtime."
    }
    if (runtime == LocalImageRuntime.QNN_HTP) {
        require(execution.optBoolean("npuActive", false) && execution.optBoolean("qnnGraphExecution", false)) {
            "The local image result did not prove native QNN graph execution."
        }
    }
}

/** Exact Local Images API to worker request mapping; no execution control is inferred here. */
internal fun ImageGenerationApiRequest.toLocalImageApiDispatch(): LocalImageApiDispatch {
    val effectiveStrength = ultraFix?.strength ?: strength
    return LocalImageApiDispatch(
        options = LocalImageGenerationOptions(
            negativePrompt = negativePrompt,
            width = width,
            height = height,
            steps = steps,
            seed = seed,
            cfgScale = cfgScale,
            sampleMethod = sampler,
            clipSkip = clipSkip,
            batchCount = imageCount,
            strength = effectiveStrength,
            vaeTiling = vaeTiling?.let { tiling ->
                LocalImageVaeTilingOptions(tiling.tileSize, tiling.overlap)
            },
            textualInversionIds = textualInversionIds,
            ultraFix = ultraFix?.let { value ->
                LocalImageUltraFixOptions(
                    value.targetWidth,
                    value.targetHeight,
                    value.strength,
                    value.inversionSteps,
                    value.refinementSteps,
                    value.tileSize,
                    value.overlap
                )
            },
            preview = preview?.let { requestedPreview ->
                LocalImagePreviewOptions(
                    interval = requestedPreview.interval,
                    mode = LocalImagePreviewMode.fromWireName(requestedPreview.mode)
                )
            }
        ),
        inputDraft = LocalImageInputDraft(
            taskMode = LocalImageTaskMode.fromWireName(taskMode.wireName),
            inputImageReference = inputImage,
            maskImageReference = maskImage,
            controlImageReference = controlImage,
            strength = effectiveStrength,
            controlStrength = controlStrength
        )
    )
}
