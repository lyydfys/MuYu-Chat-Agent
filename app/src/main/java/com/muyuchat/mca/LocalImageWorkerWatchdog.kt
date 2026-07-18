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
    family: LocalImageModelFamily,
    steps: Int? = null,
    useCfg: Boolean? = null
): LocalImageWorkerWatchdogPolicy? =
    if (runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL) {
        LocalImageWorkerWatchdogPolicy(
            timeoutMs = sdxlWorkerTimeoutMs(
                steps = (steps ?: SDXL_WATCHDOG_DEFAULT_STEPS).coerceIn(1, 100),
                useCfg = useCfg ?: true
            )
        )
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
private const val SDXL_WATCHDOG_DEFAULT_STEPS = 30
private const val SDXL_UNET_BASE_TIMEOUT_MS = 3L * 60L * 1_000L
private const val SDXL_PER_UNET_EXECUTION_TIMEOUT_MS = 12L * 1_000L
private const val SDXL_UNET_MIN_TIMEOUT_MS = 4L * 60L * 1_000L
private const val SDXL_UNET_MAX_TIMEOUT_MS = 30L * 60L * 1_000L
private const val SDXL_VAE_BASE_TIMEOUT_MS = 90L * 1_000L
private const val SDXL_VAE_PER_EXECUTION_TIMEOUT_MS = 20L * 1_000L
internal const val SDXL_DEFAULT_VAE_EXECUTION_COUNT = 9
private const val SDXL_VAE_MAX_TIMEOUT_MS = 8L * 60L * 1_000L
private const val SDXL_CONDITIONING_TIMEOUT_BUDGET_MS = 3L * 60L * 1_000L
private const val SDXL_COORDINATION_TIMEOUT_MARGIN_MS = 70L * 1_000L
private const val SDXL_WORKER_MAX_TIMEOUT_MS = 40L * 60L * 1_000L

internal fun sdxlUnetPhaseTimeoutMs(unetExecutionCount: Int): Long =
    (SDXL_UNET_BASE_TIMEOUT_MS +
        unetExecutionCount.coerceAtLeast(1).toLong() * SDXL_PER_UNET_EXECUTION_TIMEOUT_MS)
        .coerceIn(SDXL_UNET_MIN_TIMEOUT_MS, SDXL_UNET_MAX_TIMEOUT_MS)

internal fun sdxlVaePhaseTimeoutMs(vaeExecutionCount: Int): Long =
    (SDXL_VAE_BASE_TIMEOUT_MS +
        vaeExecutionCount.coerceIn(1, 64).toLong() * SDXL_VAE_PER_EXECUTION_TIMEOUT_MS)
        .coerceAtMost(SDXL_VAE_MAX_TIMEOUT_MS)

internal fun sdxlWorkerTimeoutMs(steps: Int, useCfg: Boolean): Long {
    val boundedSteps = steps.coerceIn(1, 100)
    val estimatedTimetableCount = boundedSteps + 1
    val estimatedUnetExecutionCount = estimatedTimetableCount * if (useCfg) 2 else 1
    return (
        SDXL_CONDITIONING_TIMEOUT_BUDGET_MS +
            sdxlUnetPhaseTimeoutMs(estimatedUnetExecutionCount) +
            sdxlVaePhaseTimeoutMs(SDXL_DEFAULT_VAE_EXECUTION_COUNT) +
            SDXL_COORDINATION_TIMEOUT_MARGIN_MS
        ).coerceAtMost(SDXL_WORKER_MAX_TIMEOUT_MS)
}
