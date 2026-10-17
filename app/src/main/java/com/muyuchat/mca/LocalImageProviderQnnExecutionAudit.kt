package com.muyuchat.mca

import org.json.JSONObject

internal fun qnnSharedPreviewExecutionAudit(
    nativeResult: JSONObject,
    requestedPreview: LocalImagePreviewOptions?
): JSONObject {
    require(!nativeResult.toString().contains("\"previewPath\"")) {
        "Final QNN execution metadata must not expose a transient preview path."
    }
    fun exactLong(field: String): Long {
        val number = nativeResult.opt(field) as? Number
            ?: error("Native QNN preview evidence field $field must be numeric.")
        val value = number.toLong()
        require(number.toDouble().isFinite() && number.toDouble() == value.toDouble()) {
            "Native QNN preview evidence field $field must be an exact integer."
        }
        return value
    }
    val finalVaeExecutionCount = exactLong("finalVaeExecutionCount")
    val finalVaeGraphExecutionCount = exactLong("finalVaeGraphExecutionCount")
    val vaeExecutionCount = exactLong("vaeExecutionCount")
    val vaeTileCount = exactLong("vaeTileCount")
    val vaeTiled = nativeResult.opt("vaeTiled") as? Boolean
        ?: error("Native QNN vaeTiled evidence must be boolean.")
    require(finalVaeExecutionCount == 1L) {
        "Final QNN VAE evidence must describe exactly one independent logical decode."
    }
    require(finalVaeGraphExecutionCount >= 1L &&
        vaeExecutionCount == finalVaeGraphExecutionCount &&
        vaeTileCount == finalVaeGraphExecutionCount
    ) {
        "Final QNN VAE physical graph execution evidence is inconsistent."
    }
    if (vaeTiled) {
        require(vaeTileCount > 1L) {
            "A tiled final QNN VAE decode must execute more than one concrete tile graph."
        }
    } else {
        require(vaeTileCount == 1L) {
            "A direct final QNN VAE decode must execute exactly one graph."
        }
    }

    val previewRequested = nativeResult.opt("previewRequested") as? Boolean
        ?: error("Native QNN previewRequested evidence must be boolean.")
    require(previewRequested == (requestedPreview != null)) {
        "Native QNN preview request evidence differs from the Android request."
    }
    val expectedMode = if (requestedPreview == null) "none" else "vae"
    val expectedInterval = requestedPreview?.interval?.toLong() ?: 0L
    require(requestedPreview == null || expectedInterval in 1L..10L) {
        "Shared QNN preview interval must be in [1, 10]."
    }
    require(nativeResult.opt("previewMode") is String &&
        nativeResult.getString("previewMode") == expectedMode &&
        exactLong("previewInterval") == expectedInterval
    ) { "Native QNN preview mode or interval differs from the Android request." }

    val attemptCount = exactLong("previewVaeExecutionAttemptCount")
    val executionCount = exactLong("previewVaeExecutionCount")
    val executionMsTotal = exactLong("previewVaeExecutionMsTotal")
    val publicationCount = exactLong("previewPublicationCount")
    val lastStep = exactLong("previewLastStep")
    val lastRevision = exactLong("previewLastRevision")
    require(attemptCount >= executionCount && executionCount >= publicationCount &&
        executionMsTotal >= 0L && publicationCount >= 0L &&
        lastRevision == publicationCount
    ) { "Native QNN preview counters are inconsistent." }
    val fullSteps = exactLong("steps")
    require(fullSteps > 0L) { "Native QNN preview evidence requires positive scheduler steps." }
    val previewTotalSteps = if (nativeResult.optString("taskMode") in setOf(
            LocalImageTaskMode.IMG2IMG.wireName,
            LocalImageTaskMode.INPAINT.wireName
        )
    ) {
        val effectiveSteps = exactLong("effectiveDenoiseSteps")
        require(effectiveSteps == exactLong("timetableCount") &&
            effectiveSteps in 1L..fullSteps
        ) { "Native QNN image-input preview evidence has an invalid effective scheduler tail." }
        effectiveSteps
    } else {
        fullSteps
    }
    if (publicationCount == 0L) {
        require(lastStep == 0L && lastRevision == 0L) {
            "A pathless QNN preview audit cannot claim a last frame."
        }
    } else {
        require(lastStep in 1 until previewTotalSteps && lastRevision > 0L &&
            expectedInterval > 0L && lastStep % expectedInterval == 0L &&
            lastStep == publicationCount * expectedInterval
        ) {
            "Native QNN preview last-step evidence is invalid or duplicates the final decode."
        }
    }
    val failureCode = nativeResult.opt("previewFailureCode") as? String
        ?: error("Native QNN previewFailureCode evidence must be a string.")
    val knownFailureCodes = setOf(
        "PREVIEW_STORAGE_INVALID",
        "PREVIEW_VAE_INPUT_BIND_FAILED",
        "PREVIEW_VAE_EXECUTE_FAILED",
        "PREVIEW_VAE_OUTPUT_READ_FAILED",
        "PREVIEW_REVISION_INVALID",
        "PREVIEW_PNG_WRITE_FAILED",
        "PREVIEW_PNG_INVALID",
        "PREVIEW_ATOMIC_RENAME_FAILED",
        "PREVIEW_DIRECTORY_FSYNC_FAILED",
        "PREVIEW_JOURNAL_COMMIT_FAILED"
    )
    require(failureCode.length <= 128 &&
        failureCode.all { it.isUpperCase() || it.isDigit() || it == '_' } &&
        (failureCode.isEmpty() || failureCode in knownFailureCodes)
    ) { "Native QNN preview failure code is invalid." }
    val previewDegraded = nativeResult.opt("previewDegraded") as? Boolean
        ?: error("Native QNN previewDegraded evidence must be boolean.")
    require(previewDegraded == failureCode.isNotEmpty()) {
        "Native QNN preview degradation evidence conflicts with previewFailureCode."
    }
    val expectedEligiblePreviewCount = if (requestedPreview == null) {
        0L
    } else {
        (previewTotalSteps - 1L) / expectedInterval
    }
    require(publicationCount <= expectedEligiblePreviewCount) {
        "Native QNN preview published more frames than its interval schedule allows."
    }
    if (vaeTiled && requestedPreview != null && expectedEligiblePreviewCount > 0L &&
        failureCode != "PREVIEW_STORAGE_INVALID" &&
        failureCode != "PREVIEW_JOURNAL_COMMIT_FAILED"
    ) {
        require(failureCode == "PREVIEW_VAE_INPUT_BIND_FAILED" &&
            attemptCount == 1L && executionCount == 0L && publicationCount == 0L
        ) {
            "The current shared QNN publisher cannot directly bind full latents to a tiled VAE graph."
        }
    }
    if (requestedPreview != null && failureCode.isEmpty()) {
        require(publicationCount == expectedEligiblePreviewCount &&
            attemptCount == publicationCount && executionCount == publicationCount
        ) { "Native QNN preview evidence does not match the deterministic eligible-step schedule." }
    } else if (failureCode == "PREVIEW_STORAGE_INVALID" ||
        (failureCode == "PREVIEW_JOURNAL_COMMIT_FAILED" &&
            attemptCount == 0L && executionCount == 0L && publicationCount == 0L)
    ) {
        require(attemptCount == 0L && executionCount == 0L && publicationCount == 0L) {
            "QNN preview initialization failure cannot claim a VAE attempt or publication."
        }
    } else if (failureCode.isNotEmpty()) {
        require(attemptCount == publicationCount + 1L &&
            executionCount in publicationCount..attemptCount
        ) { "QNN preview failure counters do not describe exactly one stopped attempt." }
    }
    if (requestedPreview == null) {
        require(
            attemptCount == 0L && executionCount == 0L && executionMsTotal == 0L &&
                publicationCount == 0L && lastStep == 0L && lastRevision == 0L &&
                failureCode.isEmpty() && !previewDegraded
        ) { "A QNN request without preview carried preview execution evidence." }
    }
    return JSONObject()
        .put("finalVaeExecutionCount", finalVaeExecutionCount)
        .put("finalVaeGraphExecutionCount", finalVaeGraphExecutionCount)
        .put("vaeExecutionCount", vaeExecutionCount)
        .put("vaeTileCount", vaeTileCount)
        .put("vaeTiled", vaeTiled)
        .put("previewRequested", previewRequested)
        .put("previewMode", expectedMode)
        .put("previewInterval", expectedInterval)
        .put("previewVaeExecutionAttemptCount", attemptCount)
        .put("previewVaeExecutionCount", executionCount)
        .put("previewVaeExecutionMsTotal", executionMsTotal)
        .put("previewPublicationCount", publicationCount)
        .put("previewLastStep", lastStep)
        .put("previewLastRevision", lastRevision)
        .put("previewFailureCode", failureCode)
        .put("previewDegraded", previewDegraded)
}
