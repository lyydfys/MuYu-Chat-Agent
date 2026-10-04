package com.muyuchat.mca

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON projections of the image generation options: the path-free audit form and the native
 * parameter form.
 *
 * Split out of LocalImageInputContract.kt; declarations moved verbatim.
 */

internal fun LocalImageGenerationOptions.inputAuditJson(): JSONObject = JSONObject()
    .put("taskMode", taskMode.wireName)
    .put("batchCount", batchCount)
    .apply {
        inputImage?.let { put("inputImage", it.toJson(includePath = false)) }
        maskImage?.let { put("maskImage", it.toJson(includePath = false)) }
        controlImage?.let { put("controlImage", it.toJson(includePath = false)) }
        strength?.let { put("strength", it) }
        controlStrength?.let { put("controlStrength", it) }
        clipSkip?.let { put("clipSkip", it) }
        vaeTiling?.let { put("vaeTiling", it.toJson()) }
        ultraFix?.let { put("ultraFix", it.toJson()) }
        preview?.let { put("preview", it.toJson()) }
    }

internal fun LocalImageGenerationOptions.putProductInputNativeParams(target: JSONObject): JSONObject {
    validateProductInputContract()
    target.put("taskMode", taskMode.wireName)
        .put("batchCount", batchCount)
    inputImage?.let { input ->
        target.put("inputImagePath", input.path)
            .put("inputImageSha256", input.sha256)
    }
    maskImage?.let { input ->
        target.put("maskImagePath", input.path)
            .put("maskImageSha256", input.sha256)
    }
    controlImage?.let { input ->
        target.put("controlImagePath", input.path)
            .put("controlImageSha256", input.sha256)
    }
    strength?.let { target.put("strength", it) }
    controlStrength?.let { target.put("controlStrength", it) }
    clipSkip?.let { target.put("clipSkip", it) }
    target.put("loraCount", loras.size)
    if (loras.isNotEmpty()) {
        val canonicalParents = loras.map { File(it.path).canonicalFile.parentFile?.canonicalPath }.distinct()
        val loraRoot = canonicalParents.singleOrNull()
        require(!loraRoot.isNullOrBlank()) {
            "All LoRA adapters must be direct children of one app-private root."
        }
        target.put("loraRootPath", loraRoot)
        target.put(
            "loras",
            JSONArray().apply { loras.forEach { adapter -> put(adapter.toJson()) } }
        )
    } else {
        target.put("loraRootPath", "")
        target.put("loras", JSONArray())
    }
    vaeTiling?.let { target.put("vaeTiling", it.toJson()) }
    ultraFix?.let { target.put("ultraFix", it.toJson()) }
    preview?.let { target.put("preview", it.toJson()) }
    return target
}
