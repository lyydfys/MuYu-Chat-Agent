package com.muyuchat.mca

internal data class LocalImageWorkerWatchdogPolicy(
    val timeoutMs: Long,
    val timeoutCode: String = LOCAL_IMAGE_WORKER_WATCHDOG_TIMEOUT_CODE,
    val runtimeLabel: String = "QNN SDXL"
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
    useCfg: Boolean? = null,
    backendMode: String? = null
): LocalImageWorkerWatchdogPolicy? = when {
    runtime == LocalImageRuntime.QNN_HTP && family == LocalImageModelFamily.SDXL ->
        LocalImageWorkerWatchdogPolicy(
            timeoutMs = sdxlWorkerTimeoutMs(
                steps = (steps ?: SDXL_WATCHDOG_DEFAULT_STEPS).coerceIn(1, 100),
                useCfg = useCfg ?: true
            )
        )

    runtime == LocalImageRuntime.MNN_DIFFUSION &&
        backendMode?.trim()?.lowercase() in setOf("opencl", "gpu") ->
        LocalImageWorkerWatchdogPolicy(
            timeoutMs = mnnOpenClWorkerTimeoutMs(steps ?: MNN_OPENCL_WATCHDOG_DEFAULT_STEPS),
            timeoutCode = MNN_OPENCL_WORKER_WATCHDOG_TIMEOUT_CODE,
            runtimeLabel = "MNN OpenCL"
        )

    else -> null
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
        "context_release",
        // MNN-Diffusion reports these phases while its JNI call is still
        // synchronous. They are the first observable heartbeat before a
        // backend can stall inside an OpenCL kernel.
        "generating",
        "saving"
    )

internal fun localImageWorkerWatchdogMessage(
    timeoutMs: Long,
    phase: String,
    stageTrace: List<String>,
    runtimeLabel: String = "QNN SDXL"
): String {
    val timeoutSeconds = timeoutMs / 1_000L
    val normalizedPhase = phase.ifBlank { "unknown" }
    val trace = stageTrace.distinct().joinToString(" -> ").ifBlank { "none" }
    return "$runtimeLabel worker exceeded the ${timeoutSeconds}s safety deadline " +
        "at phase=$normalizedPhase (stages=$trace); disposable worker termination was requested."
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
internal const val MNN_OPENCL_WORKER_WATCHDOG_TIMEOUT_CODE = "mnn_opencl_worker_timeout"
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
private const val SDXL_ENCODER_BASE_TIMEOUT_MS = 2L * 60L * 1_000L
private const val SDXL_ENCODER_PER_EXECUTION_TIMEOUT_MS = 60L * 1_000L
private const val SDXL_ENCODER_MAX_TIMEOUT_MS = 12L * 60L * 1_000L
private const val SDXL_ENCODER_TIMEOUT_MS =
    SDXL_ENCODER_BASE_TIMEOUT_MS + SDXL_ENCODER_PER_EXECUTION_TIMEOUT_MS
private const val SDXL_WORKER_MAX_TIMEOUT_MS = 40L * 60L * 1_000L
internal const val MNN_OPENCL_WATCHDOG_DEFAULT_STEPS = 20
private const val MNN_OPENCL_BASE_TIMEOUT_MS = 5L * 60L * 1_000L
private const val MNN_OPENCL_PER_STEP_TIMEOUT_MS = 30L * 1_000L
private const val MNN_OPENCL_MAX_TIMEOUT_MS = 30L * 60L * 1_000L

internal fun sdxlUnetPhaseTimeoutMs(unetExecutionCount: Int): Long =
    (SDXL_UNET_BASE_TIMEOUT_MS +
        unetExecutionCount.coerceAtLeast(1).toLong() * SDXL_PER_UNET_EXECUTION_TIMEOUT_MS)
        .coerceIn(SDXL_UNET_MIN_TIMEOUT_MS, SDXL_UNET_MAX_TIMEOUT_MS)

internal fun sdxlVaePhaseTimeoutMs(vaeExecutionCount: Int): Long =
    (SDXL_VAE_BASE_TIMEOUT_MS +
        vaeExecutionCount.coerceIn(1, 64).toLong() * SDXL_VAE_PER_EXECUTION_TIMEOUT_MS)
        .coerceAtMost(SDXL_VAE_MAX_TIMEOUT_MS)

internal fun sdxlEncoderPhaseTimeoutMs(encoderExecutionCount: Int = 1): Long =
    (SDXL_ENCODER_BASE_TIMEOUT_MS +
        encoderExecutionCount.coerceIn(1, 64).toLong() *
            SDXL_ENCODER_PER_EXECUTION_TIMEOUT_MS)
        .coerceAtMost(SDXL_ENCODER_MAX_TIMEOUT_MS)

internal fun sdxlWorkerTimeoutMs(steps: Int, useCfg: Boolean): Long {
    val boundedSteps = steps.coerceIn(1, 100)
    val estimatedTimetableCount = boundedSteps + 1
    val estimatedUnetExecutionCount = estimatedTimetableCount * if (useCfg) 2 else 1
    return (
        SDXL_CONDITIONING_TIMEOUT_BUDGET_MS +
            SDXL_ENCODER_TIMEOUT_MS +
            sdxlUnetPhaseTimeoutMs(estimatedUnetExecutionCount) +
            sdxlVaePhaseTimeoutMs(SDXL_DEFAULT_VAE_EXECUTION_COUNT) +
            SDXL_COORDINATION_TIMEOUT_MARGIN_MS
        ).coerceAtMost(SDXL_WORKER_MAX_TIMEOUT_MS)
}

/**
 * MNN's OpenCL bridge is a blocking JNI call and cannot be interrupted by a
 * cancelled coroutine. The image worker is disposable, so a generous,
 * step-scaled deadline is preferable to leaving a wedged OpenCL process alive
 * forever. The timeout terminates that worker; callers can retry explicitly on
 * CPU after the process has been replaced.
 */
internal fun mnnOpenClWorkerTimeoutMs(steps: Int): Long =
    (MNN_OPENCL_BASE_TIMEOUT_MS +
        steps.coerceIn(1, 100).toLong() * MNN_OPENCL_PER_STEP_TIMEOUT_MS)
        .coerceAtMost(MNN_OPENCL_MAX_TIMEOUT_MS)
