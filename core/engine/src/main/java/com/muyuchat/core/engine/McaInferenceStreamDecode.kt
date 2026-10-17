package com.muyuchat.core.engine

import com.muyuchat.core.telemetry.MemorySnapshot
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlin.math.max

internal data class McaInferenceStreamDecodeOutcome(val returnedEarly: Boolean)

internal suspend fun McaInferenceService.runDecodeSamplingLoop(
    started: Long,
    memoryBeforeGenerate: MemorySnapshot,
    promptEndsInsideReasoning: Boolean,
    request: ChatRequest,
    activeRequest: ChatRequest,
    runner: LocalChatRunner,
    generationStopToken: GenerationStopToken,
    executionContext: LocalChatExecutionContext,
    shouldRefreshMnnAfterRequest: Boolean,
    hasImageAttachments: Boolean,
    pendingRollbackContext: PendingRollbackContext?,
    activePersistentPrefixSeed: PreparedPersistentPrefix?,
    managedPersistentPrefixFailureSeed: ManagedPersistentPrefixFailure?,
    markPendingCommitted: () -> Unit,
    markPendingDeferred: () -> Unit,
    emit: suspend (GenerateEvent) -> Unit
): McaInferenceStreamDecodeOutcome {
    var activePersistentPrefix: PreparedPersistentPrefix? = activePersistentPrefixSeed
    var managedPersistentPrefixFailure: ManagedPersistentPrefixFailure? = managedPersistentPrefixFailureSeed
            var firstTokenAt: Long? = null
            var lastTokenAt = started
            var generatedChunks = 0
            var generatedTokens = 0
            var finalStats = _stats.value
            var latestMemory = memoryBeforeGenerate
            var lastStatsSampleAt = 0L
            var lastMemorySampleAt = started
            var cachedNativeStats: JSONObject? = null
            val reasoningFilter = ReasoningContentFilter(startsInsideReasoning = promptEndsInsideReasoning)
            val stopFilter = StreamingStopFilter(activeRequest.params.stopWords)
            val reasoningLoopGuard = ReasoningLoopGuard()
            val hideReasoning = request.params.hideReasoning || request.params.reasoningMode == ReasoningMode.OFF
            var reasoningStartedAt: Long? = null
            var reasoningDurationMs = 0L
            var visibleOutputSeen = false
            var mnnGenerationCompletedNormally = false
            var mnnGenerationWasInterrupted = false
            // A failure thrown by FlowCollector.emit() belongs to the downstream consumer.
            // It must escape unchanged: attempting to emit a second error event from our
            // catch block violates Flow's exception-transparency contract.
            var downstreamEmissionFailed = false

            suspend fun emitGenerated(event: GenerateEvent) {
                try {
                    emit(event)
                } catch (error: Throwable) {
                    downstreamEmissionFailed = true
                    throw error
                }
            }
            var persistPhaseEmitted = false
            suspend fun emitPersistPhase(stats: RuntimeStats) {
                if (!persistPhaseEmitted) {
                    emitGenerated(GenerateEvent.Phase(GenerationPhase.PERSIST, stats))
                    persistPhaseEmitted = true
                }
            }

            try {
                var decodePhaseEmitted = false
                while (true) {
                    val rawChunk = withContext(io) { runner.generateNextChunk() } ?: break
                    if (rawChunk.isEmpty()) continue
                    if (!decodePhaseEmitted) {
                        emitGenerated(GenerateEvent.Phase(GenerationPhase.DECODE, _stats.value))
                        decodePhaseEmitted = true
                    }
                    val chunk = stopFilter.accept(rawChunk)
                    if (stopFilter.stopped) runner.requestStop()
                    // A standalone space/newline is a valid model delta. Do
                    // not drop it here: code indentation, Markdown and word
                    // boundaries are carried by whitespace-only chunks.
                    if (chunk.isEmpty()) {
                        if (stopFilter.stopped) break
                        continue
                    }
                    val now = clock.nowMs()
                    val firstTokenTime = firstTokenAt ?: now.also { firstTokenAt = it }
                    lastTokenAt = now
                    generatedChunks += 1
                    generatedTokens += estimateTokens(chunk)
                    val shouldSampleStats = cachedNativeStats == null ||
                        now - lastStatsSampleAt >= STATS_SAMPLE_INTERVAL_MS
                    val nativeStats = if (shouldSampleStats) {
                        lastStatsSampleAt = now
                        runCatching { JSONObject(nativeStatsJsonOnIo()) }.getOrNull()
                            ?.also { cachedNativeStats = it }
                    } else {
                        cachedNativeStats
                    }
                    val nativeCompletionTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("completionTokens")?.takeIf { it > 0 }
                    } else {
                        null
                    }
                    val nativePromptTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("promptTokens")?.takeIf { it > 0 }
                    } else {
                        null
                    }
                    if (nativeCompletionTokens != null) generatedTokens = nativeCompletionTokens
                    val ttft = (firstTokenTime - started).coerceAtLeast(0L)
                    val decodeMs = if (shouldSampleStats) {
                        nativeStats?.optLong("decodeMs")?.takeIf { it > 0L }
                    } else {
                        null
                    }
                        ?: max(1L, lastTokenAt - firstTokenTime)
                    val totalMs = max(1L, lastTokenAt - started)
                    if (now - lastMemorySampleAt >= MEMORY_SAMPLE_INTERVAL_MS) {
                        latestMemory = telemetry.memorySnapshotDetailed()
                        lastMemorySampleAt = now
                    }
                    val promptTokens = nativePromptTokens ?: estimatePromptTokens(activeRequest)
                    val prefillMs = if (shouldSampleStats) {
                        nativeStats?.optLong("prefillMs") ?: _stats.value.prefillMs
                    } else {
                        _stats.value.prefillMs
                    }
                    val cacheReuse = nativeStats?.optJSONObject("cacheReuse")
                    val gpuEvidence = nativeGpuOffloadEvidence(nativeStats, _stats.value)
                    val backendEvidence = nativeBackendExecutionEvidence(nativeStats, _stats.value)
                    val prefillTokens = if (shouldSampleStats) {
                        nativeStats?.optInt("prefillTokens", -1)?.takeIf { it >= 0 }
                    } else {
                        null
                    } ?: _stats.value.prefillTokens.takeIf { it > 0 }
                        ?: promptTokens
                    finalStats = _stats.value.copy(
                        promptTokens = promptTokens,
                        completionTokens = generatedTokens,
                        promptTokensEstimated = nativePromptTokens == null ||
                            nativeStats?.optBoolean("promptTokensEstimated", false) == true,
                        completionTokensEstimated = nativeCompletionTokens == null ||
                            nativeStats?.optBoolean("completionTokensEstimated", false) == true,
                        ttftMs = ttft,
                        prefillMs = prefillMs,
                        prefillTokens = prefillTokens,
                        prefillTps = nativeStats?.optDouble("prefillTps")
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?: if (prefillMs > 0L) {
                                prefillTokens * 1000.0 / prefillMs
                            } else 0.0,
                        effectivePromptTps = nativeStats?.optDouble("effectivePromptTps")
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                            ?: if (prefillMs > 0L) promptTokens * 1000.0 / prefillMs else 0.0,
                        decodeMs = decodeMs,
                        decodeTps = if (shouldSampleStats) {
                            nativeStats?.optDouble("decodeTps")?.takeIf { it > 0.0 }
                        } else {
                            null
                        }
                            ?: (generatedTokens * 1000.0 / decodeMs),
                        e2eTps = generatedTokens * 1000.0 / totalMs,
                        nThreads = nativeStats?.optInt("nThreads") ?: _stats.value.nThreads,
                        nThreadsBatch = nativeStats?.optInt("nThreadsBatch") ?: _stats.value.nThreadsBatch,
                        nBatch = nativeStats?.optInt("nBatch") ?: _stats.value.nBatch,
                        nUbatch = nativeStats?.optInt("nUbatch") ?: _stats.value.nUbatch,
                        nCtx = nativeStats?.optInt("nCtx")?.takeIf { it > 0 } ?: _stats.value.nCtx,
                        maxAllTokens = nativeStats?.optInt("maxAllTokens")?.takeIf { it > 0 } ?: _stats.value.maxAllTokens,
                        maxNewTokens = nativeStats?.optInt("maxNewTokens")?.takeIf { it > 0 } ?: _stats.value.maxNewTokens,
                        backendDevices = nativeStats?.optJSONArray("backendDevices")?.toString() ?: _stats.value.backendDevices,
                        requestedBackend = backendEvidence.requestedBackend,
                        actualBackend = backendEvidence.actualBackend,
                        backendExecutionObserved = backendEvidence.executionObserved,
                        backendExecutionEvidence = backendEvidence.evidenceSource,
                        npuExecutionObserved = backendEvidence.npuExecutionObserved,
                        npuSupported = backendEvidence.npuSupported,
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
                        cacheReuseReason = cacheReuse?.optString("reason")
                            ?.takeIf { it.isNotBlank() }
                            ?: "unknown",
                        cacheReuseHits = cacheReuse?.optLong("hits", 0) ?: 0,
                        cacheReuseMisses = cacheReuse?.optLong("misses", 0) ?: 0,
                        generationParameterApplicationJson = nativeStats?.optJSONObject("parameterApplication")?.toString() ?: "{}",
                        lastError = null
                    ).withMemory(latestMemory)
                    if (shouldSampleStats) {
                        _stats.value = finalStats
                    }
                    val filtered = reasoningFilter.filter(chunk)
                    if (filtered.visible.any { !it.isWhitespace() }) {
                        visibleOutputSeen = true
                    }
                    var stopForReasoningLoop = false
                    if (filtered.reasoning.isNotEmpty() && !hideReasoning) {
                        if (filtered.reasoning.any { !it.isWhitespace() }) {
                            val reasoningStart = reasoningStartedAt ?: now.also { reasoningStartedAt = it }
                            reasoningDurationMs = (now - reasoningStart).coerceAtLeast(0L)
                            stopForReasoningLoop = reasoningLoopGuard.shouldStop(filtered.reasoning)
                            if (stopForReasoningLoop) {
                                mnnGenerationWasInterrupted = shouldRefreshMnnAfterRequest
                                runner.requestStop()
                            }
                        }
                    }
                    if (filtered.visible.isNotEmpty() || (filtered.reasoning.isNotEmpty() && !hideReasoning)) {
                        emitGenerated(
                            GenerateEvent.Chunk(
                                text = filtered.visible,
                                stats = finalStats,
                                reasoning = if (hideReasoning) "" else filtered.reasoning,
                                reasoningDurationMs = reasoningDurationMs
                            )
                        )
                    } else if (filtered.reasoning.isNotBlank() && hideReasoning && generatedTokens % HIDDEN_REASONING_PROGRESS_STEP_TOKENS == 0) {
                        emitGenerated(
                            GenerateEvent.Chunk(
                                text = "",
                                stats = finalStats,
                                hiddenReasoning = true
                            )
                        )
                    }
                    if (stopForReasoningLoop || stopFilter.stopped) break
                }
                if (synchronized(generationStopGate) { stopRequestedToken == generationStopToken }) {
                    val cancelled = finalStats.copy(lastError = "Generation cancelled by its request owner.")
                    _stats.value = cancelled
                    emitGenerated(GenerateEvent.Error(cancelled.lastError.orEmpty(), cancelled, code = "REQUEST_CANCELLED"))
                    return McaInferenceStreamDecodeOutcome(true)
                }
                val finalNativeStats = runCatching { JSONObject(nativeStatsJsonOnIo()) }.getOrNull()
                finalStats = mergeNativeStats(
                    base = finalStats,
                    nativeStats = finalNativeStats,
                    memory = telemetry.memorySnapshotDetailed(),
                    started = started,
                    lastTokenAt = lastTokenAt,
                    request = activeRequest,
                    managedPrefixFailure = managedPersistentPrefixFailure
                )
                _stats.value = finalStats
                if (shouldRefreshMnnAfterRequest) {
                    val nativeStopReason = finalNativeStats
                        ?.optString("generationStopReason")
                        .orEmpty()
                    if (nativeStopReason in MNN_UNSAFE_STOP_REASONS) {
                        mnnGenerationWasInterrupted = true
                    }
                }
                val stopTail = reasoningFilter.filter(stopFilter.finish())
                if (stopTail.visible.isNotEmpty() || (!hideReasoning && stopTail.reasoning.isNotEmpty())) {
                    if (stopTail.visible.any { !it.isWhitespace() }) visibleOutputSeen = true
                    emitGenerated(GenerateEvent.Chunk(
                        text = stopTail.visible,
                        stats = finalStats,
                        reasoning = if (hideReasoning) "" else stopTail.reasoning,
                        reasoningDurationMs = reasoningDurationMs
                    ))
                }
                val remaining = reasoningFilter.finish()
                if (remaining.reasoning.isNotBlank() && !hideReasoning) {
                    val now = clock.nowMs()
                    val reasoningStart = reasoningStartedAt ?: now.also { reasoningStartedAt = it }
                    reasoningDurationMs = (now - reasoningStart).coerceAtLeast(0L)
                }
                if (remaining.visible.any { !it.isWhitespace() }) {
                    visibleOutputSeen = true
                }
                if (remaining.visible.isNotEmpty() || (remaining.reasoning.isNotEmpty() && !hideReasoning)) {
                    emitGenerated(
                        GenerateEvent.Chunk(
                            text = remaining.visible,
                            stats = finalStats,
                            reasoning = if (hideReasoning) "" else remaining.reasoning,
                            reasoningDurationMs = reasoningDurationMs
                        )
                    )
                }
                if (!visibleOutputSeen) {
                    val message = "本地模型本轮没有生成可见正文。请重试；若持续发生，请降低上下文或更换模型。"
                    // Treat a whitespace-only/filtered turn as failed state.
                    // This prevents LiteRT (and other KV-backed runners) from
                    // reusing a session whose committed history was rejected
                    // by the service-level visible-output guard.
                    withContext(NonCancellable + io) {
                        runCatching { runner.invalidateConversationContext() }
                    }
                    val errorStats = finalStats.copy(lastError = message)
                    _stats.value = errorStats
                    emitPersistPhase(errorStats)
                    writeLog(errorStats, activeRequest.params, error = message)
                    emitGenerated(GenerateEvent.Error(message, errorStats))
                    return McaInferenceStreamDecodeOutcome(true)
                }
                activePersistentPrefix?.takeIf { it.request.fullSessionState }?.let { pending ->
                    withContext(io) {
                        finishPersistentPrefix(pending, 0, runner)?.let { failure ->
                            managedPersistentPrefixFailure = failure
                            finalStats = finalStats.copy(
                                persistentPrefixCacheHit = false,
                                persistentPrefixCacheTokens = failure.tokens,
                                persistentPrefixCacheReason = failure.reason
                            )
                            _stats.value = finalStats
                        }
                        if (activePersistentPrefix === pending) activePersistentPrefix = null
                    }
                }
                emitPersistPhase(finalStats)
                writeLog(finalStats, activeRequest.params, error = null)
                if (activeRuntime == LocalChatRuntime.GENIEX_QAIRT) {
                    qairtDryRunWitness?.sawVisibleCompletion = true
                }
                pendingRollbackContext?.let { transaction ->
                    if (executionContext.pendingProfileDisposition ==
                        PendingProfileDisposition.COMMIT_ON_VISIBLE_SUCCESS
                    ) {
                        val committed = parameterCoordinator.commitAuthorizedPending(transaction.authorization)
                        adoptCommittedProfileSessionLocked(committed)
                        markPendingCommitted()
                    } else if (executionContext.pendingProfileDisposition ==
                        PendingProfileDisposition.DEFER_TO_LEASE_HOLDER
                    ) {
                        val lease = executionContext.lifecycleLease as? EngineLifecycleLease
                            ?: error("deferred pending disposition requires an exclusive lifecycle lease")
                        // Keep the monitor operation out of this very large suspend
                        // state machine.  Kotlin's JVM coroutine transformer can
                        // otherwise recurse through the inlined synchronized block
                        // and overflow while compiling streamChat().
                        deferPendingAuthorization(lease, transaction.authorization)
                        markPendingDeferred()
                    }
                }
                mnnGenerationCompletedNormally = !mnnGenerationWasInterrupted
                emitGenerated(GenerateEvent.Done(finalStats))
            } catch (t: Throwable) {
                // Never let cleanup hide a downstream/cancellation exception.
                runCatching { runner.requestStop() }
                if (t is CancellationException || downstreamEmissionFailed) throw t
                val message = t.message ?: "Generation failed."
                val errorStats = errorStatsForRunnerFailure(runner, message)
                _stats.value = errorStats
                emitPersistPhase(errorStats)
                writeLog(errorStats, activeRequest.params, error = message)
                emit(GenerateEvent.Error(message, errorStats))
            } finally {
                activePersistentPrefix?.let { pending ->
                    withContext(NonCancellable + io) {
                        discardPersistentPrefix(pending)
                        if (activePersistentPrefix === pending) activePersistentPrefix = null
                    }
                }
                // Native beginCompletion() calls reset() for every request. That reset only
                // covers the base LLM context, however; MNN Omni keeps visual state in its
                // subclass. Recreate an MNN session before the next request after every
                // image turn so image embeddings/counters cannot leak into a later turn.
                // Most text-only successful turns remain reusable. MNN 3.5 Gemma 4
                // text-isolation bundles are the narrow exception: under the MNN 3.6
                // loader, their first reset can emit EOP as the entire next answer.
                // Those bundles have no legacy visual graph and have proven safe to
                // reconstruct, so refresh them before the next request.
                val mustRefreshMnnForVisualState = hasImageAttachments
                val mustRefreshMnnForTextCompatibility =
                    !hasImageAttachments &&
                        activeLoadSession?.let { session ->
                            MnnSessionLifecyclePolicy
                                .requiresFreshSessionAfterSuccessfulTextTurn(session.modelPath)
                        } == true
                if (shouldRefreshMnnAfterRequest &&
                    (!mnnGenerationCompletedNormally ||
                        mustRefreshMnnForVisualState ||
                        mustRefreshMnnForTextCompatibility)
                ) {
                    mnnSessionNeedsReloadBeforeNextRequest = true
                }
            }
    return McaInferenceStreamDecodeOutcome(false)
}
