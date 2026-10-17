package com.muyuchat.api.local

import org.json.JSONObject

internal class LocalApiRuntimeTraceState {
    internal fun reset(requestId: String) {
        synchronized(traceLock) {
            resetRequestTraceLocked(requestId)
        }
    }

    internal fun recordGenerationSequence(requestId: String, generationSequence: Long) {
        if (requestId.isBlank() || generationSequence < 0L) return
        synchronized(traceLock) {
            if (lastDispatchedRequestId == requestId) {
                lastDispatchedGenerationSequence = generationSequence
            }
        }
    }

    internal fun clearRequestTrace() {
        synchronized(traceLock) {
            resetRequestTraceLocked("")
        }
    }
    private val traceLock = Any()
    private var lastDispatchedRequestId: String = ""
    private var lastDispatchedGenerationSequence: Long? = null
    private var lastMediaPreparedCount: Int = 0
    private var lastMediaFailedCount: Int = 0
    private var lastMediaPreprocessingCount: Int = 0
    private val lastMediaPreprocessingCounts = linkedMapOf<String, Int>()
    private val lastMediaInputSha256s = linkedSetOf<String>()

    private fun resetRequestTraceLocked(requestId: String) {
        lastDispatchedRequestId = requestId
        lastDispatchedGenerationSequence = null
        lastMediaPreparedCount = 0
        lastMediaFailedCount = 0
        lastMediaPreprocessingCount = 0
        lastMediaPreprocessingCounts.clear()
        lastMediaInputSha256s.clear()
    }

    internal fun recordVisionDiagnostic(requestId: String, stage: String, details: JSONObject) {
        val prepared = stage == VISION_PREPARED_STAGE
        val failed = stage == VISION_FAILED_STAGE
        if (!prepared && !failed) return
        val preprocessing = details.optString("preprocessing")
            .trim()
            .lowercase()
            .takeIf(MEDIA_PREPROCESSING_TOKEN_PATTERN::matches)
            ?: "unknown"
        val inputSha256 = details.optString("inputSha256")
            .trim()
            .lowercase()
            .takeIf(MEDIA_SHA256_PATTERN::matches)

        synchronized(traceLock) {
            if (lastDispatchedRequestId != requestId) return
            if (prepared) {
                lastMediaPreparedCount = lastMediaPreparedCount.safeIncrement()
                if (inputSha256 != null && lastMediaInputSha256s.size < MAX_RECORDED_MEDIA_HASHES) {
                    lastMediaInputSha256s += inputSha256
                }
            } else {
                lastMediaFailedCount = lastMediaFailedCount.safeIncrement()
            }
            lastMediaPreprocessingCount = lastMediaPreprocessingCount.safeIncrement()
            lastMediaPreprocessingCounts[preprocessing] =
                lastMediaPreprocessingCounts.getOrDefault(preprocessing, 0).safeIncrement()
        }
    }

    private fun Int.safeIncrement(): Int = if (this == Int.MAX_VALUE) this else this + 1

    private fun mediaTraceObjectLocked(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("preparedCount", lastMediaPreparedCount)
        .put("failedCount", lastMediaFailedCount)
        .put("preprocessingCount", lastMediaPreprocessingCount)
        .put(
            "preprocessingCounts",
            JSONObject().apply {
                lastMediaPreprocessingCounts.toSortedMap().forEach { (name, count) ->
                    put(name, count)
                }
            }
        )
        .put(
            "inputSha256s",
            org.json.JSONArray().apply {
                lastMediaInputSha256s.sorted().forEach { put(it) }
            }
        )
        .apply {
            singleImageSha256Locked()?.let { put("inputSha256", it) }
        }

    private fun singleImageSha256Locked(): String? =
        lastMediaInputSha256s.singleOrNull()
            ?.takeIf { lastMediaPreparedCount == 1 && lastMediaFailedCount == 0 }

    internal fun requestTraceObject(): JSONObject = synchronized(traceLock) {
        JSONObject()
            .apply {
                lastDispatchedRequestId.takeIf(String::isNotBlank)?.let { requestId ->
                    put("requestId", requestId)
                    put("mediaTrace", mediaTraceObjectLocked())
                    singleImageSha256Locked()?.let { imageSha256 ->
                        when {
                            requestId.startsWith("ui-") -> put("uiImageSha256", imageSha256)
                            requestId.startsWith("chatcmpl-") -> put("apiImageSha256", imageSha256)
                        }
                    }
                }
                lastDispatchedGenerationSequence?.let { put("generationSequence", it) }
            }
    }
    private val MAX_RECORDED_MEDIA_HASHES = 32
    private val VISION_PREPARED_STAGE = "local_vision_input_prepared"
    private val VISION_FAILED_STAGE = "local_vision_input_prepare_failed"
    private val MEDIA_SHA256_PATTERN = Regex("[a-f0-9]{64}")
    private val MEDIA_PREPROCESSING_TOKEN_PATTERN = Regex("[a-z0-9_.-]{1,64}")

}
