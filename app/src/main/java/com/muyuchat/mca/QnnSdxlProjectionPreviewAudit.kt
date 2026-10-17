package com.muyuchat.mca

import org.json.JSONObject

/**
 * T2B22-T4（b 工位）拆片：`qnnSdxlProjectionPreviewExecutionAudit` 自
 * LocalImageProviderQnnExecutionAudit.kt 逐字节平移；函数原为 internal 顶层，零改写。
 */

internal fun qnnSdxlProjectionPreviewExecutionAudit(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject {
    require(requestedPreview == null) {
        "Split-SDXL uses isolated workers and does not support live preview."
    }
    fun exactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Split-SDXL preview evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Split-SDXL preview evidence field $field must be an exact integer."
        }
        return value
    }

    val requested = nativeResult.opt("previewRequested") as? Boolean
        ?: error("Split-SDXL previewRequested evidence must be boolean.")
    require(!requested) { "Split-SDXL result claimed a forbidden live-preview request." }
    require(nativeResult.opt("previewMode") is String &&
        nativeResult.getString("previewMode") == "none" &&
        exactLong("previewInterval") == 0L
    ) { "Split-SDXL preview mode or interval must be disabled." }

    val finalVaeExecutionCount = exactLong("finalVaeExecutionCount")
    val finalVaeGraphExecutionCount = exactLong("finalVaeGraphExecutionCount")
    val vaeExecutionCount = exactLong("vaeExecutionCount")
    require(finalVaeExecutionCount == 1L && finalVaeGraphExecutionCount >= 1L &&
        vaeExecutionCount == finalVaeGraphExecutionCount
    ) {
        "Split-SDXL preview counters cannot replace the one final VAE decode."
    }
    val degraded = nativeResult.opt("previewDegraded") as? Boolean
        ?: error("Split-SDXL previewDegraded evidence must be boolean.")
    require(!degraded) { "Split-SDXL result reported a forbidden preview degradation." }
    listOf(
        "previewVaeExecutionAttemptCount",
        "previewVaeExecutionCount",
        "previewVaeExecutionMsTotal",
        "previewPublicationCount",
        "previewLastStep",
        "previewLastRevision",
        "projectionPreviewAttemptCount",
        "projectionPreviewPublicationCount",
        "projectionPreviewProjectionMsTotal",
        "projectionPreviewLastStep",
        "projectionPreviewLastRevision"
    ).forEach { field ->
        require(exactLong(field) == 0L) {
            "Split-SDXL result carried forbidden preview evidence in $field."
        }
    }
    // These fields are optional on legacy isolated-worker result wires, but any
    // occurrence would still be live-preview publication evidence and must stay disabled.
    listOf(
        "previewStep",
        "previewRevision",
        "previewWidth",
        "previewHeight",
        "previewFrameCount"
    ).forEach { field ->
        if (nativeResult.has(field)) {
            require(exactLong(field) == 0L) {
                "Split-SDXL result carried forbidden preview frame evidence in $field."
            }
        }
    }
    require(nativeResult.opt("previewFailureCode") == "" &&
        nativeResult.opt("projectionPreviewFailureCode") == "" &&
        (!nativeResult.has("previewPath") || nativeResult.opt("previewPath") == "") &&
        (!nativeResult.has("previewMimeType") || nativeResult.opt("previewMimeType") == "") &&
        (!nativeResult.has("previewNoisy") || nativeResult.opt("previewNoisy") == false)
    ) { "Split-SDXL result exposed forbidden transient preview evidence." }

    val zero = 0L
    val empty = ""
    val disabledMode = "none"
    val disabled = false
    return JSONObject()
        .put("finalVaeExecutionCount", finalVaeExecutionCount)
        .put("finalVaeGraphExecutionCount", finalVaeGraphExecutionCount)
        .put("vaeExecutionCount", vaeExecutionCount)
        .put("previewRequested", disabled)
        .put("previewMode", disabledMode)
        .put("previewInterval", zero)
        .put("previewVaeExecutionAttemptCount", zero)
        .put("previewVaeExecutionCount", zero)
        .put("previewVaeExecutionMsTotal", zero)
        .put("previewPublicationCount", zero)
        .put("previewLastStep", zero)
        .put("previewLastRevision", zero)
        .put("previewFailureCode", empty)
        .put("projectionPreviewAttemptCount", zero)
        .put("projectionPreviewPublicationCount", zero)
        .put("projectionPreviewProjectionMsTotal", zero)
        .put("projectionPreviewLastStep", zero)
        .put("projectionPreviewLastRevision", zero)
        .put("projectionPreviewFailureCode", empty)
        .put("previewDegraded", disabled)
}
