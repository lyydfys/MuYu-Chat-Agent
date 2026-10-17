package com.muyuchat.core.engine

import kotlinx.coroutines.Job
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class LocalChatExecutionContext(
    val requestId: String = UUID.randomUUID().toString(),
    val loadAuthorization: LoadAuthorization? = null,
    /** Kept for source compatibility; a Boolean alone no longer grants trust. */
    val allowTrustedHotOverride: Boolean = false,
    val hotOverrideAuthorization: HotOverrideAuthorization? = null,
    val pendingProfileDisposition: PendingProfileDisposition = PendingProfileDisposition.COMMIT_ON_VISIBLE_SUCCESS,
    val lifecycleLease: EngineLifecycleLease? = null,
    /** Deterministic internal transforms such as prompt translation must not receive wall-clock text. */
    val includeDeviceClockContext: Boolean = true,
    /**
     * Optional request-scoped observer for privacy-reviewed local-vision preparation diagnostics.
     * The engine isolates observer failures from inference and gives this sink its own JSON copy so
     * callers cannot mutate the existing debug diagnostic or another request's evidence.
     */
    val visionDiagnosticSink: ((String, JSONObject) -> Unit)? = null
)

data class RuntimeHealthSnapshot(
    val runtimeStats: RuntimeStats,
    val workerSessionLost: Boolean
)

/** Opaque identity for exactly one request while it owns the native runner. */
class GenerationStopToken internal constructor(
    val epoch: Long,
    val requestId: String
)

internal data class ActiveGenerationStopTarget(
    val token: GenerationStopToken,
    val runner: LocalChatRunner,
    /** The collector that activated this target; proves whether that stream is still alive. */
    val owner: Job?
)

internal data class NativeGpuOffloadEvidence(
    val active: Boolean,
    val allocationObserved: Boolean,
    val executionObserved: Boolean,
    val bytes: Long,
    val layers: Int,
    val layersKnown: Boolean,
    val autoFallbackApplied: Boolean,
    val autoFallbackReason: String?
)

/**
 * Native execution evidence for delegate families that do not expose the
 * llama.cpp GPU allocation counters.  A requested/selected backend and a
 * registered delegate are deliberately not treated as execution proof.
 */
internal data class NativeBackendExecutionEvidence(
    val requestedBackend: String?,
    val actualBackend: String?,
    val executionObserved: Boolean,
    val evidenceSource: String?,
    val npuExecutionObserved: Boolean,
    val npuSupported: Boolean?
)

internal fun normalizeNativeBackend(raw: String?): String? {
    val value = raw?.trim()?.lowercase().orEmpty()
    if (value.isBlank() || value == "unknown" || value == "unavailable" || value == "none") {
        return null
    }
    return when {
        value == "cpu" || value.contains("cpu") -> "cpu"
        value == "gpu" || value.contains("opencl") || value.contains("adreno") ||
            value.contains("gpu") -> "gpu"
        value == "npu" || value.contains("qnn") || value.contains("qairt") ||
            value.contains("htp") || value.contains("qualcomm") || value.contains("npu") -> "npu"
        else -> value
    }
}

/**
 * Parses only explicit execution/capability fields from a native stats
 * payload.  Strings such as `backendDevices=LiteRT-LM NPU` describe a
 * selected delegate and are intentionally not accepted as proof.
 */
internal fun nativeBackendExecutionEvidence(
    nativeStats: JSONObject?,
    fallback: RuntimeStats
): NativeBackendExecutionEvidence {
    if (nativeStats == null) {
        return NativeBackendExecutionEvidence(
            requestedBackend = fallback.requestedBackend,
            actualBackend = fallback.actualBackend,
            executionObserved = fallback.backendExecutionObserved,
            evidenceSource = fallback.backendExecutionEvidence,
            npuExecutionObserved = fallback.npuExecutionObserved,
            npuSupported = fallback.npuSupported
        )
    }
    val executionEvidence = nativeStats.optJSONObject("executionEvidence")
    val requested = listOf(
        nativeStats.optString("requestedBackend"),
        nativeStats.optString("selectedBackend"),
        nativeStats.optString("backendMode")
    ).firstOrNull { it.isNotBlank() }?.trim()
    val actual = normalizeNativeBackend(
        nativeStats.optString("actualBackend").takeIf { it.isNotBlank() }
            ?: nativeStats.optString("effectiveBackend").takeIf { it.isNotBlank() }
    )
    val explicitNpuProof = nativeStats.optBoolean("npuExecutionProven", false) ||
        nativeStats.optBoolean("provesNpuExecution", false) ||
        executionEvidence?.optBoolean("npuExecutionProven", false) == true ||
        executionEvidence?.optBoolean("qnnGraphExecution", false) == true
    val evidenceAvailable = executionEvidence?.optBoolean("available", false) == true
    val decodeObserved = executionEvidence?.optBoolean("decodeObserved", false) == true ||
        nativeStats.optBoolean("nativeExecution", false)
    val executionObserved = explicitNpuProof ||
        (evidenceAvailable && decodeObserved && actual != null)
    val source = listOf(
        executionEvidence?.optString("source"),
        nativeStats.optString("executionEvidenceSource"),
        nativeStats.optString("backendExecutionProof")
    ).firstOrNull { it?.isNotBlank() == true }?.trim()
    val capabilities = nativeStats.optJSONObject("backendCapabilities")
    val npuSupported = if (capabilities?.has("npuSupported") == true) {
        capabilities.optBoolean("npuSupported")
    } else {
        null
    }
    return NativeBackendExecutionEvidence(
        requestedBackend = requested ?: fallback.requestedBackend,
        actualBackend = actual ?: fallback.actualBackend,
        executionObserved = executionObserved || fallback.backendExecutionObserved,
        evidenceSource = source ?: fallback.backendExecutionEvidence,
        npuExecutionObserved = explicitNpuProof || fallback.npuExecutionObserved,
        npuSupported = npuSupported ?: fallback.npuSupported
    )
}

/**
 * Mirrors the native MNN visual-package discovery used before load.  Some
 * exporter bundles omit the optional JSON declaration yet ship visual.mnn;
 * native enables vision for those packages, so their load-bound mmap policy
 * must be resolved the same way on the JVM side.
 */
internal fun isMnnVisualProfileForLoad(modelPath: String, advancedJson: String): Boolean {
    val advanced = runCatching { JSONObject(advancedJson) }.getOrNull()
    if (advanced?.optString("visual_model")?.isNotBlank() == true ||
        advanced?.optBoolean("is_visual", false) == true
    ) return true

    val selected = File(modelPath)
    val config = when {
        selected.isFile && selected.name.equals("config.json", ignoreCase = true) -> selected
        selected.isDirectory -> File(selected, "config.json")
        else -> selected.parentFile?.let { File(it, "config.json") }
    } ?: return false
    val root = runCatching {
        if (!config.isFile || !config.canRead()) return@runCatching null
        JSONObject(config.readText(Charsets.UTF_8))
    }.getOrNull() ?: return false
    if (root.optBoolean("is_visual", false) || root.optString("visual_model").isNotBlank()) {
        return true
    }

    val bundleRoot = config.parentFile ?: return false
    return listOf("visual.mnn", "visual.mnn.weight").any { name ->
        File(bundleRoot, name).let { it.isFile && it.length() > 0L }
    }
}

/**
 * Native allocation alone must never promote a runtime to GPU execution. This
 * is the shared projection used by load, streaming, UI, and Local API stats.
 */
internal fun nativeGpuOffloadEvidence(
    nativeStats: JSONObject?,
    fallback: RuntimeStats
): NativeGpuOffloadEvidence {
    if (nativeStats == null) {
        return NativeGpuOffloadEvidence(
            active = fallback.hasVerifiedGpuExecution,
            allocationObserved = fallback.gpuOffloadAllocationObserved,
            executionObserved = fallback.gpuOffloadExecutionObserved,
            bytes = fallback.gpuOffloadBytes,
            layers = fallback.gpuOffloadLayers,
            layersKnown = fallback.gpuOffloadLayersKnown,
            autoFallbackApplied = fallback.gpuAutoFallbackApplied,
            autoFallbackReason = fallback.gpuAutoFallbackReason
        )
    }
    val allocationObserved = nativeStats.optBoolean("gpuOffloadAllocationObserved", false)
    val executionObserved = nativeStats.optBoolean("gpuOffloadExecutionObserved", false)
    val active = nativeStats.optBoolean("gpuOffloadActive", false) &&
        allocationObserved && executionObserved
    return NativeGpuOffloadEvidence(
        active = active,
        allocationObserved = allocationObserved,
        executionObserved = executionObserved,
        bytes = nativeStats.optLong("gpuOffloadBytes", 0L).coerceAtLeast(0L),
        layers = nativeStats.optInt("gpuOffloadLayers", 0),
        layersKnown = nativeStats.optBoolean("gpuOffloadLayersKnown", false),
        autoFallbackApplied = nativeStats.optBoolean("gpuAutoFallbackApplied", false),
        autoFallbackReason = nativeStats.optString("gpuAutoFallbackReason")
            .takeIf { it.isNotBlank() }
    )
}

internal fun nativeGpuOffloadSupported(
    nativeStats: JSONObject?,
    fallback: RuntimeStats
): Boolean? {
    val capabilities = nativeStats?.optJSONObject("backendCapabilities")
    return if (capabilities?.has("gpuOffloadSupported") == true) {
        capabilities.optBoolean("gpuOffloadSupported")
    } else {
        fallback.gpuOffloadSupported
    }
}

/** Opaque, engine-owned exclusive lifecycle lease used by formal candidate evaluation. */
class EngineLifecycleLease internal constructor(
    internal val service: McaInferenceService,
    internal val ownerToken: Any,
    val leaseId: String = UUID.randomUUID().toString()
) {
    @Volatile
    internal var active: Boolean = true
    internal var deferredAuthorization: LoadAuthorization? = null
    internal var releasing: Boolean = false

    /** Idempotent; release is safe from candidate cleanup/finally blocks. */
    suspend fun release() {
        service.releaseExclusiveLifecycleLease(this)
    }
}

enum class PendingProfileDisposition {
    /** Formal user/apply transaction: commit the exact active candidate before Done. */
    COMMIT_ON_VISIBLE_SUCCESS,

    /** Keep the exact candidate active under an exclusive lease for an external correctness gate. */
    DEFER_TO_LEASE_HOLDER,

    /** Formal candidate evaluation: run under the candidate, then restore the locked rollback target. */
    ROLLBACK_AFTER_REQUEST
}


internal const val PERSISTENT_PREFIX_CACHE_FORMAT = "mca-prefix-cache-v1"
internal const val MAX_PERSISTENT_PREFIX_CACHE_PROMPT_BYTES = 128 * 1024
internal const val LLAMA_SEQUENCE_STATE_FORMAT = "llama-state-seq-v1"
internal const val MANAGED_PREFIX_COMMIT_FAILED_REASON = "managed_commit_failed"
internal const val PREFILL_PROGRESS_POLL_INTERVAL_MS = 50L
internal const val STOP_REQUEST_TIMEOUT_MS = 500L
internal const val ISOLATED_WORKER_SESSION_LOST_FIELD = "workerSessionLost"
internal const val CONTEXT_LENGTH_EXCEEDED_ERROR_CODE = "context_length_exceeded"
internal const val HIDDEN_REASONING_PROGRESS_STEP_TOKENS = 16
internal const val STATS_SAMPLE_INTERVAL_MS = 250L
internal const val MEMORY_SAMPLE_INTERVAL_MS = 1000L
internal val MNN_UNSAFE_STOP_REASONS = setOf(
            "stop_requested",
            "mnn_user_cancel",
            "mnn_internal_error",
            "mnn_timeout",
            "runner_unavailable"
        )
internal val VISION_DIAGNOSTIC_TOKEN_PATTERN = Regex("[a-z0-9_.-]{1,64}")
internal val VISION_DIAGNOSTIC_SHA256_PATTERN = Regex("[a-f0-9]{64}")
internal fun localVisionUnavailableMessage(runtime: LocalChatRuntime, nativeStatsJson: String): String {
    val stats = runCatching { JSONObject(nativeStatsJson) }.getOrNull()
    if (runtime == LocalChatRuntime.LITERT_LM) {
        return if (stats?.optBoolean("visionModelKnownTextOnly", false) == true) {
            LITERT_LM_KNOWN_TEXT_ONLY_VISION_UNAVAILABLE_MESSAGE
        } else {
            LITERT_LM_VISION_TRANSPORT_UNAVAILABLE_MESSAGE
        }
    }
    return "当前本地模型未启用识图。请加载 MNN 多模态包，或加载本地多模态 GGUF 并绑定匹配 mmproj 后重新加载模型。"
}
