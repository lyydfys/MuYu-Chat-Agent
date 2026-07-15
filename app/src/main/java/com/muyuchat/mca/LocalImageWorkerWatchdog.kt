package com.muyuchat.mca

internal data class LocalImageWorkerWatchdogPolicy(
    val timeoutMs: Long
)

/**
 * The SDXL QNN path is deliberately isolated because vendor context creation
 * and graph execution are synchronous and are not interruptible from Kotlin.
 * A hard deadline therefore protects the app process; it does not certify
 * image quality or claim that the native operation itself is recoverable.
 */
internal fun localImageWorkerWatchdogPolicy(
    runtime: LocalImageRuntime,
    family: LocalImageModelFamily
): LocalImageWorkerWatchdogPolicy? =
    if (runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL) {
        LocalImageWorkerWatchdogPolicy(timeoutMs = SDXL_QNN_WORKER_TIMEOUT_MS)
    } else {
        null
    }

internal fun localImageWorkerWatchdogStartsAtPhase(phase: String): Boolean =
    phase in setOf(
        "conditioning",
        "loading",
        "context_lock",
        "context_binary_mmap",
        "context_create",
        "graph_execute",
        "sampling",
        "decoding",
        "png_write",
        "context_release"
    )

internal fun localImageWorkerWatchdogMessage(
    timeoutMs: Long,
    phase: String,
    stageTrace: List<String>
): String {
    val timeoutSeconds = timeoutMs / 1_000L
    val normalizedPhase = phase.ifBlank { "unknown" }
    val trace = stageTrace.distinct().joinToString(" -> ").ifBlank { "none" }
    return "QNN SDXL worker exceeded the ${timeoutSeconds}s safety deadline " +
        "at phase=$normalizedPhase (stages=$trace); the disposable worker was terminated."
}

internal fun accumulateNativeStageTrace(
    previous: List<String>,
    incoming: List<String>
): List<String> = when {
    incoming.size < previous.size -> previous
    incoming.take(previous.size) != previous -> previous
    else -> incoming
}

internal const val LOCAL_IMAGE_WORKER_WATCHDOG_TIMEOUT_CODE = "qnn_sdxl_worker_timeout"
internal const val SDXL_QNN_WORKER_TIMEOUT_MS = 6L * 60L * 1_000L
