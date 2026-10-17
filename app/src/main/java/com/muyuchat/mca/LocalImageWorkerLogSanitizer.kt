package com.muyuchat.mca

import android.util.Log
import java.io.File
import org.json.JSONObject

internal const val LOCAL_IMAGE_GENERATION_CANCELLED_CODE = "image_generation_cancelled"

private val LOCAL_IMAGE_SAFE_LOG_COUNT_FIELDS = listOf(
    "nativeGenerationSequence",
    "nativeStageMask",
    "nativeDetailStageMask",
    "unetExecutionCount",
    "controlNetExecutionCount",
    "controlImageExecutionCount",
    "actualDiffusionModelComputeCount",
    "actualPositiveDiffusionModelComputeCount",
    "actualNegativeDiffusionModelComputeCount",
    "actualAuxiliaryDiffusionModelComputeCount",
    "physicalComputeCount",
    "physicalComputeSuccessCount",
    "physicalTileComputeCount",
    "physicalTileComputeSuccessCount"
)

private val LOCAL_IMAGE_SAFE_LOG_BOOLEAN_FIELDS = listOf(
    "tiledExecution",
    "executionCompleted"
)

private val LOCAL_IMAGE_SAFE_LOG_UINT64_HEX_FIELDS = listOf(
    QNN_NATIVE_DETAIL_STAGE_MASK_HEX_FIELD,
    "encoderNativeDetailStageMaskHex",
    "unetNativeDetailStageMaskHex",
    "vaeNativeDetailStageMaskHex"
)

private val LOCAL_IMAGE_UINT64_HEX_PATTERN = Regex("^[0-9a-f]{16}$")

private val LOCAL_IMAGE_SAFE_LOG_OPERATIONS = setOf(
    "IMAGE_GENERATION",
    "ESRGAN_UPSCALE"
)

internal fun localImageWorkerCompletionLogSummary(
    requestId: String,
    workerPid: Int,
    operation: String,
    runtime: LocalImageRuntime,
    outputCount: Int,
    outputBytes: Long,
    executionMetadataJson: String
): String {
    val summary = JSONObject()
        .put("requestId", requestId)
        .put("workerPid", workerPid)
        .put("operation", operation.takeIf(LOCAL_IMAGE_SAFE_LOG_OPERATIONS::contains) ?: "UNKNOWN")
        .put("runtime", runtime.name)
        .put("outputCount", outputCount.coerceAtLeast(0))
        .put("outputBytes", outputBytes.coerceAtLeast(0L))
    val metadata = runCatching { JSONObject(executionMetadataJson) }.getOrNull()
        ?: return summary.toString()
    val sources = listOfNotNull(metadata.optJSONObject("nativeEffective"), metadata)

    LOCAL_IMAGE_SAFE_LOG_COUNT_FIELDS.forEach { field ->
        var safeValue: Long? = null
        for (source in sources) {
            val raw = source.opt(field) as? Number ?: continue
            val doubleValue = raw.toDouble()
            val longValue = raw.toLong()
            if (doubleValue.isFinite() && doubleValue == longValue.toDouble() && longValue >= 0L) {
                safeValue = longValue
                break
            }
        }
        safeValue?.let { summary.put(field, it) }
    }
    LOCAL_IMAGE_SAFE_LOG_BOOLEAN_FIELDS.forEach { field ->
        val safeValue = sources.firstNotNullOfOrNull { source -> source.opt(field) as? Boolean }
        safeValue?.let { summary.put(field, it) }
    }
    LOCAL_IMAGE_SAFE_LOG_UINT64_HEX_FIELDS.forEach { field ->
        val safeValue = sources.firstNotNullOfOrNull { source ->
            (source.opt(field) as? String)?.takeIf(LOCAL_IMAGE_UINT64_HEX_PATTERN::matches)
        }
        safeValue?.let { summary.put(field, it) }
    }
    return summary.toString()
}

internal fun MutableCollection<File>.deleteWorkerOutputs() {
    forEach { file -> runCatching { file.delete() } }
    clear()
}

internal fun logLocalImageWorkerInternalFailure(action: String, error: Throwable) {
    val safeAction = action.filter { it.isLetterOrDigit() || it == '_' }.take(48)
        .ifBlank { "unknown" }
    val safeType = error.javaClass.simpleName
        .filter { it.isLetterOrDigit() || it == '_' }
        .take(64)
        .ifBlank { "Throwable" }
    Log.w("MCA-LocalImage", "internal_failure action=$safeAction type=$safeType")
}
