package com.muyuchat.mca

import org.json.JSONObject

/**
 * Remote local-chat runner support (P7/S29), moved verbatim from RemoteLocalChatRunner.kt:
 * loaded-model-loss stats confirmation, the worker load-result disposition classifier and the
 * runner exception type. Consumed by the runner body and the worker state tests, same package.
 */

internal fun workerStatsConfirmLoadedModelLoss(expectedLoaded: Boolean, statsJson: String): Boolean {
    if (!expectedLoaded) return false
    val stats = runCatching { JSONObject(statsJson) }.getOrNull() ?: return true
    return !stats.optBoolean("loaded", false) && !stats.optBoolean("runtimeStatsDeferred", false)
}

internal enum class WorkerLoadResultDisposition {
    COMMITTED,
    FAILED,
    LOST_AFTER_SUCCESS,
    STALE
}

internal fun classifyWorkerLoadResult(
    nativeResult: Int,
    endpointStillCurrent: Boolean,
    epochStillCurrent: Boolean
): WorkerLoadResultDisposition = when {
    nativeResult != 0 -> WorkerLoadResultDisposition.FAILED
    !endpointStillCurrent -> WorkerLoadResultDisposition.LOST_AFTER_SUCCESS
    !epochStillCurrent -> WorkerLoadResultDisposition.STALE
    else -> WorkerLoadResultDisposition.COMMITTED
}

internal class RemoteLocalChatRunnerException(
    message: String,
    cause: Throwable? = null,
    internal val workerStageDiagnostic: LocalChatWorkerStageDiagnostic? = null
) :
    IllegalStateException(message, cause)
