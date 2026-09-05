package com.muyuchat.mca

import org.json.JSONObject

/**
 * Describes how the MNN SD1.5 backend was requested and which backend actually
 * produced the result.  The native graph is allowed to prove a compatibility
 * fallback; a device/chipset guess is never used as an admission decision.
 */
internal data class MnnDiffusionBackendAttempt(
    val backend: String,
    val ok: Boolean,
    val errorCode: String? = null,
    val error: String? = null
)

internal fun requestedMnnDiffusionBackendMode(raw: String?): String = when {
    raw == null -> "auto"
    raw.trim().isEmpty() -> "invalid"
    raw.trim().lowercase() in setOf("gpu", "opencl") -> "opencl"
    raw.trim().lowercase() == "cpu" -> "cpu"
    else -> raw.trim().lowercase()
}

/**
 * Only the native NOT_SUPPORT/resize failure emitted by the SD1.5 UNet marks
 * the CPU graph as incompatible.  Other CPU failures (bad files, OOM, prompt
 * errors, cancellation) must not be retried on another backend.
 */
internal fun isMnnCpuBackendUnsupported(result: JSONObject): Boolean {
    val code = result.optString("errorCode").trim()
    if (code.equals(MNN_UNET_BACKEND_UNSUPPORTED_ERROR_CODE, ignoreCase = true)) return true
    if (result.optString("backendMode").trim().lowercase() != "cpu") return false
    val nativeStatus = result.optInt("nativeStatus", Int.MIN_VALUE)
    if (nativeStatus == MNN_NOT_SUPPORT_STATUS) return true
    val error = sequenceOf(
        result.optString("error"),
        result.optString("message")
    ).joinToString(" ").lowercase()
    return error.contains("resizesession failed with status 2") ||
        error.contains("not_support") ||
        error.contains("create execution error : 304") ||
        error.contains("groupnorm")
}

/**
 * Adds a stable, backwards-compatible backend audit to the outer native
 * result.  `backendMode` remains the historical effective value; the new
 * fields make an automatic CPU→OpenCL (or future reverse) retry explicit.
 */
internal fun JSONObject.withMnnBackendAudit(
    requested: String,
    effective: String,
    attempts: List<MnnDiffusionBackendAttempt>,
    fallback: Boolean = false,
    fallbackReason: String? = null
): JSONObject = apply {
    put("requestedBackendMode", requested)
    put("effectiveBackendMode", effective)
    put("backendMode", effective)
    put("backendFallback", fallback)
    if (fallbackReason.isNullOrBlank()) {
        remove("backendFallbackReason")
    } else {
        put("backendFallbackReason", fallbackReason)
    }
    val attemptsJson = org.json.JSONArray()
    attempts.forEach { attempt ->
        attemptsJson.put(JSONObject().apply {
            put("backend", attempt.backend)
            put("ok", attempt.ok)
            attempt.errorCode?.takeIf(String::isNotBlank)?.let { put("errorCode", it) }
            attempt.error?.takeIf(String::isNotBlank)?.let { put("error", it) }
        })
    }
    put("backendAttempts", attemptsJson)
}

internal const val MNN_UNET_BACKEND_UNSUPPORTED_ERROR_CODE =
    "MNN_UNET_BACKEND_UNSUPPORTED"
private const val MNN_NOT_SUPPORT_STATUS = 2
