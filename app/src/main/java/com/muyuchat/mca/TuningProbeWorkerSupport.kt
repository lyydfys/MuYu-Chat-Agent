package com.muyuchat.mca

import com.muyuchat.core.engine.LocalChatRuntime
import com.muyuchat.core.engine.ModelExecutionProfile
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.modelstore.ChatModelRuntime
import com.muyuchat.core.tuning.ExecutionProfileKind
import com.muyuchat.core.tuning.HotExecutionParams
import com.muyuchat.core.tuning.LoadBoundExecutionParams
import com.muyuchat.core.tuning.ProfileVerificationLevel
import com.muyuchat.core.tuning.TuningExecutionProfile
import org.json.JSONObject

/**
 * Tuning-probe worker support (P7/S20), moved verbatim from TuningProbeWorkerService.kt:
 * the Binder start-resolution enum, the lock-owned process lifecycle, and the pure
 * profile/runtime/stats mappers. Consumed by the service class body in the same package.
 */

internal enum class TuningProbeStartResolution {
    ACCEPTED,
    BUSY,
    CLOSED
}

/**
 * Pure lock-owned lifecycle for Binder parsing and active-request ownership.
 * JSON parsing deliberately happens while a token is held, so an exit task
 * cannot mistake that interval for an idle process.
 */
internal class TuningProbeProcessLifecycle {
    class StartToken internal constructor(internal val value: Long)

    private var nextStartToken = 0L
    private val startsInProgress = mutableSetOf<Long>()
    private var activeStartToken: Long? = null

    var destroyed: Boolean = false
        private set

    val startInProgressCount: Int
        get() = startsInProgress.size

    val hasActiveRequest: Boolean
        get() = activeStartToken != null

    fun beginStart(): StartToken? {
        if (destroyed) return null
        val token = ++nextStartToken
        startsInProgress += token
        return StartToken(token)
    }

    /** Releases malformed/invalid input before it can publish an active request. */
    fun abandonStart(token: StartToken): Boolean =
        startsInProgress.remove(token.value) && !destroyed

    fun resolveStart(token: StartToken): TuningProbeStartResolution {
        if (!startsInProgress.remove(token.value) || destroyed) {
            return TuningProbeStartResolution.CLOSED
        }
        if (activeStartToken != null) return TuningProbeStartResolution.BUSY
        activeStartToken = token.value
        return TuningProbeStartResolution.ACCEPTED
    }

    /** Releases linkToDeath failures, completion, cancellation, and client death. */
    fun finishActive(token: StartToken): Boolean {
        if (activeStartToken != token.value) return false
        activeStartToken = null
        return !destroyed
    }

    fun destroy() {
        destroyed = true
        startsInProgress.clear()
        activeStartToken = null
    }

    fun shouldExit(
        scheduledEpoch: Long,
        currentEpoch: Long,
        hasExternalActiveRequest: Boolean
    ): Boolean = !destroyed &&
        scheduledEpoch == currentEpoch &&
        startsInProgress.isEmpty() &&
        activeStartToken == null &&
        !hasExternalActiveRequest
}

// P7/S20: widened private->internal (consumed by the service body in TuningProbeWorkerService.kt).
internal fun ModelExecutionProfile.toIsolatedTuningProfile(): TuningExecutionProfile {
    fun resolvedInt(field: String): Int? = (resolvedLoadBoundValues.value(field) as? Number)?.toInt()
    fun hotInt(field: String): Int? = (hotExecutionValues.value(field) as? Number)?.toInt()
    fun resolvedString(field: String): String? = resolvedLoadBoundValues.value(field)?.toString()
    fun resolvedBoolean(field: String): Boolean? = resolvedLoadBoundValues.value(field) as? Boolean
    return TuningExecutionProfile(
        engineProfile = this,
        kind = ExecutionProfileKind.BALANCED,
        loadBound = LoadBoundExecutionParams(
            nCtx = resolvedInt("n_ctx") ?: error("The persisted candidate has no n_ctx."),
            nBatch = resolvedInt("n_batch"),
            nUbatch = resolvedInt("n_ubatch"),
            cacheTypeK = resolvedString("cache_type_k"),
            cacheTypeV = resolvedString("cache_type_v"),
            flashAttention = resolvedString("flash_attn"),
            gpuLayers = resolvedInt("n_gpu_layers"),
            mainGpu = resolvedInt("main_gpu"),
            cpuMoeLayers = resolvedInt("n_cpu_moe"),
            speculativeType = resolvedString("spec_type"),
            speculativeDraftMax = resolvedInt("spec_draft_n_max"),
            nParallel = resolvedInt("n_parallel") ?: 1,
            mmap = resolvedBoolean("mmap") ?: true,
            mlock = resolvedBoolean("mlock") ?: false,
            backend = resolvedString("backend") ?: runtimeIdentity.runtime.backendId
        ),
        hotExecution = HotExecutionParams(
            nThreads = hotInt("n_threads") ?: 1,
            nThreadsBatch = hotInt("n_threads_batch")
        ),
        verificationLevel = ProfileVerificationLevel.COMPATIBLE,
        reason = "Exact persisted isolated tuning probe."
    )
}

// P7/S20: widened private->internal (consumed by the service body in TuningProbeWorkerService.kt).
internal fun ChatModelRuntime.toTuningRuntime(): LocalChatRuntime = when (this) {
    ChatModelRuntime.MNN -> LocalChatRuntime.MNN_CPU
    ChatModelRuntime.LLAMA_CPP -> LocalChatRuntime.LLAMA_CPP
    ChatModelRuntime.GENIEX_LLAMA_CPP -> LocalChatRuntime.GENIEX_LLAMA_CPP
    ChatModelRuntime.GENIEX_QAIRT -> LocalChatRuntime.GENIEX_QAIRT
    ChatModelRuntime.LITERT_LM -> LocalChatRuntime.LITERT_LM
}

// P7/S20: widened private->internal (consumed by the service body in TuningProbeWorkerService.kt).
internal fun RuntimeStats.toWorkerJson(nativeStatsJson: String = "{}"): String = JSONObject()
    .put("loaded", loaded)
    .put("backend", backend)
    .put("loadMs", loadMs)
    .put("promptTokens", promptTokens)
    .put("completionTokens", completionTokens)
    .put("ttftMs", ttftMs)
    .put("prefillMs", prefillMs)
    .put("decodeMs", decodeMs)
    .put("decodeTps", decodeTps.takeIf(Double::isFinite))
    .put("e2eTps", e2eTps.takeIf(Double::isFinite))
    .put("nativePssKb", nativePssKb)
    .put("processRssKb", processRssKb)
    .put("availMemKb", availMemKb)
    .put("totalMemKb", totalMemKb)
    .put("modelMemoryBudgetKb", modelMemoryBudgetKb)
    .put("nThreads", nThreads)
    .put("nThreadsBatch", nThreadsBatch)
    .put("nBatch", nBatch)
    .put("nUbatch", nUbatch)
    .put("nCtx", nCtx)
    .put("maxAllTokens", maxAllTokens)
    .put("maxNewTokens", maxNewTokens)
    .put("isLowMemory", isLowMemory)
    .put("lastError", LocalDiagnosticRedactor.sanitize(lastError))
    .put(
        "loadFailureCode",
        runCatching { JSONObject(nativeStatsJson).optString("loadFailureCode") }
            .getOrDefault("")
            .takeIf(String::isNotBlank)
    )
    .toString()
