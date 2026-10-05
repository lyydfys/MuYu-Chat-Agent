package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import org.json.JSONObject

/**
 * Local chat worker support (P7/S28), moved verbatim from LocalChatWorkerService.kt: the
 * runtime-stats snapshot helpers and the watchdog operation targets/timeout policies.
 * Consumed by the service body and the worker state tests in the same package.
 */

internal fun isRuntimeStatsSnapshotCurrent(
    capturedEpoch: Long,
    currentEpoch: Long,
    sameRunner: Boolean
): Boolean = sameRunner && capturedEpoch == currentEpoch

internal fun withRuntimeStatsLoadGeneration(statsJson: String, loadGeneration: Long): String =
    (runCatching { JSONObject(statsJson) }.getOrNull()
        ?: JSONObject().put("loaded", false).put("runnerReady", true))
        .put("loadGeneration", loadGeneration)
        .toString()

internal fun buildDeferredRuntimeStatsJson(stableStatsJson: String, stage: String): String =
    runCatching {
        JSONObject(stableStatsJson)
            .apply {
                if (stage in setOf("load", "unload", "shutdown", "session_changed")) {
                    // A prior handle's readiness is not evidence for an in-flight replacement.
                    put("loaded", false)
                    put("visionReady", false)
                }
            }
            .put("runtimeStatsDeferred", true)
            .put("runtimeBusyStage", stage)
            .toString()
    }.getOrElse {
        JSONObject()
            .put("loaded", false)
            .put("runnerReady", true)
            .put("runtimeStatsDeferred", true)
            .put("runtimeBusyStage", stage)
            .toString()
    }

/** The selected text runtime/backend at the load boundary, never a device admission decision. */
internal data class LocalChatWorkerOperationTarget(
    val runtime: LocalChatRuntime,
    val backend: String?
)

/**
 * Per-operation hard limit for an isolated text worker. The generic limits
 * preserve existing long-context behavior; only currently observed accelerator
 * wedges use the shorter bounds below.
 */
internal data class LocalChatWorkerOperationPolicy(
    val timeoutMs: Long,
    val timeoutFailureCode: String,
    val operationLabel: String? = null,
    val forceProcessRecoveryOnCancel: Boolean = false
)

internal fun localChatWorkerOperationTarget(
    runtime: LocalChatRuntime,
    paramsJson: String?
): LocalChatWorkerOperationTarget {
    val root = runCatching { JSONObject(paramsJson.orEmpty().ifBlank { "{}" }) }.getOrNull()
    val advanced = root?.opt("advanced_json")?.let { raw ->
        when (raw) {
            is JSONObject -> raw
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> null
        }
    }
    fun value(vararg names: String): Any? = names.asSequence().mapNotNull { name ->
        root
            ?.takeIf { it.has(name) && !it.isNull(name) }
            ?.opt(name)
            ?: advanced
                ?.takeIf { it.has(name) && !it.isNull(name) }
                ?.opt(name)
    }.firstOrNull()
    val rawBackend = when (runtime) {
        LocalChatRuntime.MNN_CPU -> value("backend_type", "backendType", "backend")
        LocalChatRuntime.LITERT_LM -> value("backend", "backend_type", "backendType")
        else -> value("backend", "backend_type", "backendType")
    }
    val backend = rawBackend
        ?.toString()
        ?.trim()
        ?.lowercase()
        ?.replace('-', '_')
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            when (value) {
                "gpu", "opencl", "open_cl" ->
                    if (runtime == LocalChatRuntime.MNN_CPU) "opencl" else "gpu"
                "cpu", "host" -> "cpu"
                "npu", "qnn", "qualcomm" -> "npu"
                else -> value
            }
        }
    return LocalChatWorkerOperationTarget(runtime = runtime, backend = backend)
}

internal fun localChatWorkerOperationPolicy(
    target: LocalChatWorkerOperationTarget?,
    rawStage: String
): LocalChatWorkerOperationPolicy {
    val stage = when {
        rawStage.equals("load", ignoreCase = true) -> "load"
        rawStage.contains("prefill", ignoreCase = true) -> "prefill"
        rawStage.contains("decode", ignoreCase = true) -> "decode"
        rawStage.equals("stats", ignoreCase = true) -> "stats"
        else -> "operation"
    }
    fun accelerated(
        timeoutMs: Long,
        failureCode: String,
        operationLabel: String
    ) = LocalChatWorkerOperationPolicy(
        timeoutMs = timeoutMs,
        timeoutFailureCode = failureCode,
        operationLabel = operationLabel,
        forceProcessRecoveryOnCancel = true
    )

    return when {
        target?.runtime == LocalChatRuntime.LITERT_LM && target.backend == "gpu" -> when (stage) {
            "load" -> accelerated(120_000L, "litert_gpu_load_timeout", "LiteRT-LM GPU")
            "prefill" -> accelerated(90_000L, "litert_gpu_prefill_timeout", "LiteRT-LM GPU")
            "decode" -> accelerated(90_000L, "litert_gpu_decode_timeout", "LiteRT-LM GPU")
            else -> defaultLocalChatWorkerOperationPolicy(stage).copy(forceProcessRecoveryOnCancel = true)
        }
        // CPU keeps the generous normal-operation budget required for long
        // context/background generation. A user cancellation is different:
        // if LiteRT's native cancel callback is stuck, recover only this
        // isolated worker after the short cancellation grace period.
        target?.runtime == LocalChatRuntime.LITERT_LM && target.backend in listOf(null, "cpu") -> when (stage) {
            "prefill", "decode" -> LocalChatWorkerOperationPolicy(
                timeoutMs = 30L * 60L * 1_000L,
                timeoutFailureCode = "litert_cpu_cancel_timeout",
                operationLabel = "LiteRT-LM CPU",
                forceProcessRecoveryOnCancel = true
            )
            else -> defaultLocalChatWorkerOperationPolicy(stage).copy(forceProcessRecoveryOnCancel = true)
        }
        // NPU, Google Tensor and future generic LiteRT delegates retain their normal budgets,
        // but none may leave an explicit user cancellation waiting on a native mutex.
        target?.runtime == LocalChatRuntime.LITERT_LM ->
            defaultLocalChatWorkerOperationPolicy(stage).copy(forceProcessRecoveryOnCancel = true)
        target?.runtime == LocalChatRuntime.MNN_CPU && target.backend == "opencl" -> when (stage) {
            "load" -> accelerated(120_000L, "mnn_opencl_load_timeout", "MNN OpenCL")
            "prefill" -> accelerated(75_000L, "mnn_opencl_prefill_timeout", "MNN OpenCL")
            "decode" -> accelerated(45_000L, "mnn_opencl_decode_timeout", "MNN OpenCL")
            else -> defaultLocalChatWorkerOperationPolicy(stage)
        }
        target?.runtime == LocalChatRuntime.MNN_CPU && target.backend in listOf(null, "cpu") -> when (stage) {
            "prefill", "decode" -> LocalChatWorkerOperationPolicy(
                timeoutMs = 30L * 60L * 1_000L,
                timeoutFailureCode = "mnn_cpu_cancel_timeout",
                operationLabel = "MNN CPU",
                forceProcessRecoveryOnCancel = true
            )
            else -> defaultLocalChatWorkerOperationPolicy(stage).copy(forceProcessRecoveryOnCancel = true)
        }
        target?.runtime == LocalChatRuntime.LLAMA_CPP ||
            target?.runtime == LocalChatRuntime.GENIEX_LLAMA_CPP ->
            defaultLocalChatWorkerOperationPolicy(stage).copy(
                timeoutFailureCode = "gguf_native_timeout",
                operationLabel = "GGUF llama.cpp",
                forceProcessRecoveryOnCancel = true
            )
        else -> defaultLocalChatWorkerOperationPolicy(stage)
    }
}

private fun defaultLocalChatWorkerOperationPolicy(stage: String): LocalChatWorkerOperationPolicy =
    when (stage) {
        "load" -> LocalChatWorkerOperationPolicy(30L * 60L * 1_000L, "worker_watchdog_timeout")
        "prefill" -> LocalChatWorkerOperationPolicy(30L * 60L * 1_000L, "worker_watchdog_timeout")
        // Stats are advisory and can legitimately wait behind a long native
        // prefill/decode. A short timeout here used to kill healthy workers.
        "stats" -> LocalChatWorkerOperationPolicy(30L * 60L * 1_000L, "worker_watchdog_timeout")
        else -> LocalChatWorkerOperationPolicy(3L * 60L * 1_000L, "worker_watchdog_timeout")
    }
