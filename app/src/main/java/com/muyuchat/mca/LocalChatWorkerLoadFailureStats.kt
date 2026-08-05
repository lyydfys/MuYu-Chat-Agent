package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import org.json.JSONObject

/** Prompt-free stats retained after a failed worker load releases its native handle. */
internal object LocalChatWorkerLoadFailureStats {
    private const val MAX_FAILURE_CODE_CHARS = 128

    fun capture(
        runtime: LocalChatRuntime,
        nativeLoadResult: Int,
        nativeStatsJson: String?,
        modelPath: String
    ): String {
        val nativeStats = runCatching { JSONObject(nativeStatsJson.orEmpty()) }.getOrNull()
        val failureCode = nativeStats?.let(::failureCode)
        val lastError = LocalDiagnosticRedactor.sanitize(
            nativeStats?.optString("lastError"),
            redactedLiterals = listOf(modelPath)
        ).ifBlank {
            "Native loadModel failed with result $nativeLoadResult."
        }

        return JSONObject()
            .put("backend", runtime.backendId)
            .put("loaded", false)
            .put("runnerReady", true)
            .put("nativeLoadResult", nativeLoadResult)
            .put("lastError", lastError)
            .apply {
                failureCode?.let { put("loadFailureCode", it) }
            }
            .toString()
    }

    private fun failureCode(stats: JSONObject): String? =
        sequenceOf("loadFailureCode", "lastErrorCode", "errorCode")
            .map(stats::optString)
            .map(String::trim)
            .firstOrNull(String::isNotBlank)
            ?.take(MAX_FAILURE_CODE_CHARS)
            ?.takeIf { code ->
                code.all { character ->
                    character.isLetterOrDigit() || character == '_' || character == '-' ||
                        character == '.' || character == ':'
                }
            }
}
