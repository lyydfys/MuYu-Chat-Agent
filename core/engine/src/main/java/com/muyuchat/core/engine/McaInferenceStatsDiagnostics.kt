package com.muyuchat.core.engine

import com.muyuchat.core.modelstore.QairtExecutionAdmission
import com.muyuchat.core.modelstore.QairtBundleRiskAnalyzer
import com.muyuchat.core.telemetry.MemorySnapshot
import com.muyuchat.core.telemetry.RuntimeMetrics
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.max

    /** Releases every configured native runner, including inactive QAIRT sessions retained by callers. */
internal suspend fun McaInferenceService.shutdownImpl() = withContext(io) {
        // Interrupt an active stream before waiting for streamChat's mutex ownership.
        runCatching { runnerFor(activeRuntime).requestStop() }
        mutex.withLock {
            invalidateGenerationStopTargetAfterLifecycleTransition()
            runners.values.distinct().forEach { runner ->
                runCatching { runner.requestStop() }
                runCatching { runner.shutdown() }
            }
            parameterCoordinator.markUnloaded()
            activeLoadSession = null
            qairtDryRunWitness = null
            lastQairtExecutionAdmission = null
            mnnSessionNeedsReloadBeforeNextRequest = false
            _stats.value = RuntimeStats(loaded = false, backend = activeRuntime.backendId)
        }
    }

    /**
     * Reads only process-local runner state when no load, unload, generation, or tuning lease owns
     * the lifecycle. This must never make a Binder/JNI call: a damaged native stats path cannot be
     * allowed to retain the engine lifecycle mutex.
     */
internal suspend fun McaInferenceService.tryRuntimeHealthSnapshotImpl(): RuntimeHealthSnapshot? = withContext(io) {
        if (!mutex.tryLock()) return@withContext null
        try {
            var runtimeStats = _stats.value
            val workerSessionLost = runnerFor(activeRuntime).isSessionKnownLost()
            if (workerSessionLost) {
                isolatedWorkerSessionNeedsReload = activeLoadSession != null
                parameterCoordinator.markUnloaded()
                runtimeStats = runtimeStats.copy(
                    loaded = false,
                    lastError = runtimeStats.lastError
                        ?: "The isolated local text worker session was lost; the model will reload on the next request."
                )
                _stats.value = runtimeStats
            }
            RuntimeHealthSnapshot(
                runtimeStats = runtimeStats,
                workerSessionLost = workerSessionLost
            )
        } finally {
            mutex.unlock()
        }
    }

internal suspend fun McaInferenceService.nativeStatsJsonOnIo(): String = withContext(io) { nativeStatsJson() }

internal fun McaInferenceService.nativeStatsJsonImpl(): String = runCatching { runnerFor(activeRuntime).getRuntimeStatsJson() }.getOrElse {
        JSONObject().put("error", it.message).toString()
    }

internal fun McaInferenceService.recentLogsImpl(limit: Int = 200): List<RuntimeMetrics> = telemetry.recent(limit)

internal fun McaInferenceService.dispatchVisionDiagnostic(
        executionContext: LocalChatExecutionContext,
        stage: String,
        details: JSONObject
    ) {
        val snapshot = runCatching { details.toString() }.getOrDefault("{}")
        runCatching { LocalChatRunnerDebug.emit(stage, JSONObject(snapshot)) }
        executionContext.visionDiagnosticSink?.let { sink ->
            val redacted = JSONObject().apply {
                details.optString("status")
                    .trim()
                    .lowercase()
                    .takeIf(VISION_DIAGNOSTIC_TOKEN_PATTERN::matches)
                    ?.let { put("status", it) }
                details.optString("preprocessing")
                    .trim()
                    .lowercase()
                    .takeIf(VISION_DIAGNOSTIC_TOKEN_PATTERN::matches)
                    ?.let { put("preprocessing", it) }
                details.optString("inputSha256")
                    .trim()
                    .lowercase()
                    .takeIf(VISION_DIAGNOSTIC_SHA256_PATTERN::matches)
                    ?.let { put("inputSha256", it) }
            }
            runCatching { sink(stage, redacted) }
        }
    }

internal fun McaInferenceService.writeLog(stats: RuntimeStats, params: GenerationParams, error: String?) {
        telemetry.append(
            RuntimeMetrics(
                model = stats.modelPath.orEmpty(),
                backend = stats.backend,
                soc = socInfo.family.name.lowercase(),
                promptTokens = stats.promptTokens,
                genTokens = stats.completionTokens,
                loadMs = stats.loadMs,
                ttftMs = stats.ttftMs,
                prefillMs = stats.prefillMs,
                decodeMs = stats.decodeMs,
                decodeTps = stats.decodeTps,
                e2eTps = stats.e2eTps,
                nativePssKb = stats.nativePssKb,
                processRssKb = stats.processRssKb,
                nativeHeapKb = stats.nativeHeapKb,
                nativeHeapSizeKb = stats.nativeHeapSizeKb,
                javaHeapKb = stats.javaHeapKb,
                availMemKb = stats.availMemKb,
                totalMemKb = stats.totalMemKb,
                advertisedMemKb = stats.advertisedMemKb,
                memoryThresholdKb = stats.memoryThresholdKb,
                isLowMemory = stats.isLowMemory,
                procMemAvailableKb = stats.procMemAvailableKb,
                procMemFreeKb = stats.procMemFreeKb,
                cachedKb = stats.cachedKb,
                reclaimableKb = stats.reclaimableKb,
                modelMemoryBudgetKb = stats.modelMemoryBudgetKb,
                params = params.toJson(),
                error = error
            )
        )
    }

internal fun McaInferenceService.loadedStatsFromNative(
        modelPath: String,
        runtime: LocalChatRuntime,
        params: LoadParams,
        loadMs: Long
    ): RuntimeStats {
        val memory = telemetry.memorySnapshotDetailed()
        val nativeStats = runCatching { JSONObject(nativeStatsJson()) }.getOrNull()
        val cacheReuse = nativeStats?.optJSONObject("cacheReuse")
        val gpuEvidence = nativeGpuOffloadEvidence(nativeStats, RuntimeStats())
        val backendEvidence = nativeBackendExecutionEvidence(nativeStats, RuntimeStats())
        return RuntimeStats(
            loaded = true,
            modelPath = modelPath,
            backend = nativeStats?.optString("backend")?.takeIf { it.isNotBlank() } ?: runtime.backendId,
            loadMs = loadMs,
            promptTokens = nativeStats?.optInt("promptTokens") ?: 0,
            // Older workers do not publish prefillTokens. Keep that absence
            // distinguishable from a real zero-token prefill so the caller
            // can fall back to its prompt estimate.
            prefillTokens = nativeStats?.optInt("prefillTokens", -1)
                ?.takeIf { it >= 0 }
                ?: 0,
            prefillMs = nativeStats?.optLong("prefillMs") ?: 0L,
            prefillTps = nativeStats?.optDouble("prefillTps")
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            effectivePromptTps = nativeStats?.optDouble("effectivePromptTps")
                ?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0,
            nThreads = nativeStats?.optInt("nThreads") ?: params.nThreads,
            nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: params.nThreads,
            nBatch = nativeStats?.optInt("nBatch") ?: 0,
            nUbatch = nativeStats?.optInt("nUbatch") ?: 0,
            nCtx = nativeStats?.optInt("nCtx")?.takeIf { it > 0 } ?: params.nCtx,
            maxAllTokens = nativeStats?.optInt("maxAllTokens")?.takeIf { it > 0 } ?: params.nCtx,
            maxNewTokens = nativeStats?.optInt("maxNewTokens")?.takeIf { it > 0 } ?: 0,
            backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: "[]",
            requestedBackend = backendEvidence.requestedBackend,
            actualBackend = backendEvidence.actualBackend,
            backendExecutionObserved = backendEvidence.executionObserved,
            backendExecutionEvidence = backendEvidence.evidenceSource,
            npuExecutionObserved = backendEvidence.npuExecutionObserved,
            npuSupported = backendEvidence.npuSupported,
            gpuOffloadSupported = nativeGpuOffloadSupported(nativeStats, RuntimeStats()),
            gpuOffloadActive = gpuEvidence.active,
            gpuOffloadAllocationObserved = gpuEvidence.allocationObserved,
            gpuOffloadExecutionObserved = gpuEvidence.executionObserved,
            gpuOffloadBytes = gpuEvidence.bytes,
            gpuOffloadLayers = gpuEvidence.layers,
            gpuOffloadLayersKnown = gpuEvidence.layersKnown,
            gpuAutoFallbackApplied = gpuEvidence.autoFallbackApplied,
            gpuAutoFallbackReason = gpuEvidence.autoFallbackReason,
            cacheReuseHit = cacheReuse?.optBoolean("hit", false) ?: false,
            cacheReusedTokens = cacheReuse?.optInt("reusedTokens", 0) ?: 0,
            cacheReuseReason = cacheReuse?.optString("reason")?.takeIf { it.isNotBlank() },
            cacheReuseHits = cacheReuse?.optLong("hits", 0L) ?: 0L,
            cacheReuseMisses = cacheReuse?.optLong("misses", 0L) ?: 0L,
            persistentPrefixCacheHit = nativeStats
                ?.optJSONObject("persistentPrefixCache")
                ?.optBoolean("hit", false) == true,
            persistentPrefixCacheTokens = nativeStats
                ?.optJSONObject("persistentPrefixCache")
                ?.optInt("tokens", 0)
                ?: 0,
            persistentPrefixCacheReason = nativeStats
                ?.optJSONObject("persistentPrefixCache")
                ?.optString("reason")
                ?.takeIf { it.isNotBlank() }
        ).withMemory(memory)
    }

internal fun McaInferenceService.qairtExecutionAdmissionForLoad(
        modelPath: String,
        runtime: LocalChatRuntime,
        memory: MemorySnapshot,
        bundleSha256: String?
    ): QairtExecutionAdmission? {
        if (runtime != LocalChatRuntime.GENIEX_QAIRT) return null
        val totalRamBytes = memory.totalMemKb.takeIf { it > 0L }
            ?.times(1024L)
            ?: memory.advertisedMemKb.takeIf { it > 0L }?.times(1024L)
            ?: 0L
        val availableRamBytes = memory.availMemKb.coerceAtLeast(0L).times(1024L)
        val observedIdentity = qairtIdentityProvider(bundleSha256)
        return QairtBundleRiskAnalyzer.analyze(File(modelPath))
            .admissionForDeviceMemory(
                totalRamBytes = totalRamBytes,
                availableRamBytes = availableRamBytes,
                observedIdentity = observedIdentity,
                verifiedIdentities = qairtVerificationStore.verifiedIdentities()
            )
    }

    /**
     * Isolated QAIRT smoke is diagnostic-only. Normal QAIRT loads use the
     * generic isolated text worker, so device verification never gates a
     * user-facing load or run attempt.
     */
internal fun McaInferenceService.requireQairtExecutionPurpose(
        runtime: LocalChatRuntime,
        purpose: QairtExecutionPurpose
    ) {
        if (runtime != LocalChatRuntime.GENIEX_QAIRT) {
            require(purpose == QairtExecutionPurpose.NORMAL) {
                "Only QAIRT may use the isolated dry-run execution purpose."
            }
            return
        }
        if (purpose == QairtExecutionPurpose.ISOLATED_DRY_RUN) {
            check(qairtDryRunProcessVerifier()) {
                "QAIRT 隔离安全启动只能在 :qairt_smoke 独立进程中运行。"
            }
        }
    }

internal fun McaInferenceService.hasQairtNpuExecutionEvidence(nativeStats: JSONObject): Boolean {
        if (!nativeStats.optString("backend").equals(LocalChatRuntime.GENIEX_QAIRT.backendId, ignoreCase = true)) {
            return false
        }
        val devices = nativeStats.opt("backendDevices")?.toString().orEmpty().lowercase()
        return "qairt" in devices && ("npu" in devices || "htp" in devices)
    }

internal fun McaInferenceService.formatMb(kb: Long): String = "%.0f MB".format(kb / 1024.0)

internal fun McaInferenceService.mergeNativeStats(
        base: RuntimeStats,
        nativeStats: JSONObject?,
        memory: MemorySnapshot,
        started: Long,
        lastTokenAt: Long,
        request: ChatRequest,
        managedPrefixFailure: ManagedPersistentPrefixFailure? = null
    ): RuntimeStats {
        val completionTokens = nativeStats?.optInt("completionTokens")?.takeIf { it > 0 }
            ?: base.completionTokens
        val decodeMs = nativeStats?.optLong("decodeMs")?.takeIf { it > 0L }
            ?: base.decodeMs.takeIf { it > 0L }
            ?: max(1L, lastTokenAt - started)
        val promptTokens = nativeStats?.optInt("promptTokens")?.takeIf { it > 0 }
            ?: base.promptTokens.takeIf { it > 0 }
            ?: estimatePromptTokens(request)
        val prefillTokens = nativeStats?.optInt("prefillTokens", -1)?.takeIf { it >= 0 }
            ?: base.prefillTokens.takeIf { it > 0 }
            ?: promptTokens
        val prefillMs = nativeStats?.optLong("prefillMs") ?: base.prefillMs
        val cacheReuse = nativeStats?.optJSONObject("cacheReuse")
        val gpuEvidence = nativeGpuOffloadEvidence(nativeStats, base)
        val backendEvidence = nativeBackendExecutionEvidence(nativeStats, base)
        val totalMs = max(1L, lastTokenAt - started)
        return base.copy(
            backend = nativeStats?.optString("backend")?.takeIf { it.isNotBlank() } ?: base.backend,
            requestedBackend = backendEvidence.requestedBackend,
            actualBackend = backendEvidence.actualBackend,
            backendExecutionObserved = backendEvidence.executionObserved,
            backendExecutionEvidence = backendEvidence.evidenceSource,
            npuExecutionObserved = backendEvidence.npuExecutionObserved,
            npuSupported = backendEvidence.npuSupported,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            promptTokensEstimated = if ((nativeStats?.optInt("promptTokens") ?: 0) > 0) {
                nativeStats?.optBoolean("promptTokensEstimated", false) == true
            } else base.promptTokensEstimated || base.promptTokens <= 0,
            completionTokensEstimated = if ((nativeStats?.optInt("completionTokens") ?: 0) > 0) {
                nativeStats?.optBoolean("completionTokensEstimated", false) == true
            } else base.completionTokensEstimated,
            prefillMs = prefillMs,
            prefillTokens = prefillTokens,
            prefillTps = nativeStats?.optDouble("prefillTps")
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: if (prefillMs > 0L) prefillTokens * 1000.0 / prefillMs else 0.0,
            effectivePromptTps = nativeStats?.optDouble("effectivePromptTps")
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?: if (prefillMs > 0L) promptTokens * 1000.0 / prefillMs else 0.0,
            decodeMs = decodeMs,
            decodeTps = nativeStats?.optDouble("decodeTps")?.takeIf { it > 0.0 }
                ?: (completionTokens * 1000.0 / decodeMs),
            e2eTps = completionTokens * 1000.0 / totalMs,
            nThreads = nativeStats?.optInt("nThreads") ?: base.nThreads,
            nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: base.nThreadsBatch,
            nBatch = nativeStats?.optInt("nBatch") ?: base.nBatch,
            nUbatch = nativeStats?.optInt("nUbatch") ?: base.nUbatch,
            nCtx = nativeStats?.optInt("nCtx")?.takeIf { it > 0 } ?: base.nCtx,
            maxAllTokens = nativeStats?.optInt("maxAllTokens")?.takeIf { it > 0 } ?: base.maxAllTokens,
            maxNewTokens = nativeStats?.optInt("maxNewTokens")?.takeIf { it > 0 } ?: base.maxNewTokens,
            backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: base.backendDevices,
            gpuOffloadSupported = nativeGpuOffloadSupported(nativeStats, base),
            gpuOffloadActive = gpuEvidence.active,
            gpuOffloadAllocationObserved = gpuEvidence.allocationObserved,
            gpuOffloadExecutionObserved = gpuEvidence.executionObserved,
            gpuOffloadBytes = gpuEvidence.bytes,
            gpuOffloadLayers = gpuEvidence.layers,
            gpuOffloadLayersKnown = gpuEvidence.layersKnown,
            gpuAutoFallbackApplied = gpuEvidence.autoFallbackApplied,
            gpuAutoFallbackReason = gpuEvidence.autoFallbackReason,
            cacheReuseHit = cacheReuse?.optBoolean("hit", false) ?: false,
            cacheReusedTokens = cacheReuse?.optInt("reusedTokens", 0) ?: 0,
            cacheReuseReason = cacheReuse?.optString("reason")?.takeIf { it.isNotBlank() }
                ?: "unknown",
            cacheReuseHits = cacheReuse?.optLong("hits", 0) ?: 0,
            cacheReuseMisses = cacheReuse?.optLong("misses", 0) ?: 0,
            generationParameterApplicationJson = nativeStats?.optJSONObject("parameterApplication")?.toString() ?: "{}",
            persistentPrefixCacheHit = if (managedPrefixFailure != null) {
                false
            } else {
                nativeStats
                    ?.optJSONObject("persistentPrefixCache")
                    ?.optBoolean("hit", false)
                    ?: base.persistentPrefixCacheHit
            },
            persistentPrefixCacheTokens = managedPrefixFailure?.tokens
                ?: nativeStats
                    ?.optJSONObject("persistentPrefixCache")
                    ?.optInt("tokens", 0)
                ?: base.persistentPrefixCacheTokens,
            persistentPrefixCacheReason = managedPrefixFailure?.reason
                ?: nativeStats
                    ?.optJSONObject("persistentPrefixCache")
                    ?.optString("reason")
                    ?.takeIf { it.isNotBlank() }
                ?: base.persistentPrefixCacheReason,
            lastError = null
        ).withMemory(memory)
    }

internal fun RuntimeStats.withMemory(memory: MemorySnapshot): RuntimeStats = copy(
        nativePssKb = memory.processPssKb,
        processRssKb = memory.processRssKb,
        nativeHeapKb = memory.nativeHeapKb,
        nativeHeapSizeKb = memory.nativeHeapSizeKb,
        javaHeapKb = memory.javaHeapKb,
        availMemKb = memory.availMemKb,
        totalMemKb = memory.totalMemKb,
        advertisedMemKb = memory.advertisedMemKb,
        memoryThresholdKb = memory.memoryThresholdKb,
        isLowMemory = memory.isLowMemory,
        procMemAvailableKb = memory.procMemAvailableKb,
        procMemFreeKb = memory.procMemFreeKb,
        cachedKb = memory.cachedKb,
        reclaimableKb = memory.reclaimableKb,
        modelMemoryBudgetKb = memory.modelMemoryBudgetKb
    )
