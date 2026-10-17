package com.muyuchat.core.engine

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

    /**
     * Sends cancellation without allowing a wedged native/Binder stop call to
     * hold the caller forever.  The isolated worker owns the longer recovery
     * watchdog; this boundary only provides a bounded acknowledgement window
     * for UI, Local API, and lifecycle callers.
     */
internal suspend fun McaInferenceService.stopGenerationImpl() {
        // Prefer the engine-owned request token. This prevents a delayed stop
        // from an older UI/API request from reaching a replacement generation.
        activeGenerationStopToken()?.let { token ->
            stopGenerationIfActive(token)
            return
        }
        val runner = runCatching { runnerFor(activeRuntime) }.getOrNull() ?: return
        withContext(io) {
            withTimeoutOrNull(STOP_REQUEST_TIMEOUT_MS) {
                runCatching { runner.requestStop() }
            }
        }
    }

    /** Returns the exact request currently owning native prefill/decode, if any. */
internal fun McaInferenceService.activeGenerationStopTokenImpl(): GenerationStopToken? = synchronized(generationStopGate) {
        activeGenerationStopTarget?.token
    }

    /**
     * Stops only [expected]. The default token is evaluated by the caller before dispatcher
     * suspension, so a delayed background stop can never target a replacement request. The
     * engine-level ownership proof also covers native prefill, where runner-level active flags
     * may not become visible until beginCompletion returns.
     */
internal suspend fun McaInferenceService.stopGenerationIfActiveImpl(
        expected: GenerationStopToken? = activeGenerationStopToken()
    ): Boolean {
        // A missing captured owner is a definitive no-op. Avoiding dispatcher suspension here
        // lets lifecycle callers cancel pre-native work immediately.
        if (expected == null) return false
        val capture = synchronized(generationStopGate) {
            val target = activeGenerationStopTarget
            when {
                target?.token != expected -> false to null
                stopRequestedToken == expected -> true to null
                else -> {
                    stopRequestedToken = expected
                    true to target
                }
            }
        }
        if (!capture.first) return false
        val target = capture.second ?: return true
        return withContext(io) {
            withTimeoutOrNull(STOP_REQUEST_TIMEOUT_MS) {
                // Dispatch can lag behind a model reload or a replacement turn.
                // Recheck after the dispatcher hop before signaling the runner.
                val stillCurrent = synchronized(generationStopGate) {
                    activeGenerationStopTarget?.token == expected &&
                        stopRequestedToken == expected
                }
                if (!stillCurrent) return@withTimeoutOrNull true
                // The engine-owned token is the active-operation proof. Do not
                // perform a second native active-state query here: a damaged
                // runner may serialize that query behind decode and recreate
                // the very Binder stall this path is meant to avoid.
                runCatching {
                    target.runner.requestStop()
                    true
                }.getOrDefault(false)
            } ?: true
        }
    }


internal fun McaInferenceService.activateGenerationStopTargetImpl(
        requestId: String,
        runner: LocalChatRunner,
        collectorJob: Job?
    ): GenerationStopToken = synchronized(generationStopGate) {
        val admission = decideGenerationStopTargetAdmission(
            hasStopTarget = activeGenerationStopTarget != null,
            stopTargetOwner = activeGenerationStopTarget?.owner,
            stopRequestedTokenPresent = stopRequestedToken != null,
            stopRequestedTokenBelongsToTarget = stopRequestedToken == activeGenerationStopTarget?.token
        )
        admission.rejection?.let { throw IllegalStateException(it) }
        if (admission.reclaimTarget) {
            // The previous stream's collector is gone, so its finally can never release these:
            // reclaiming is what keeps a stopped conversation usable.
            activeGenerationStopTarget = null
            if (admission.clearStopRequestedToken) stopRequestedToken = null
        }
        generationStopEpoch += 1L
        GenerationStopToken(generationStopEpoch, requestId).also { token ->
            activeGenerationStopTarget = ActiveGenerationStopTarget(token, runner, collectorJob)
        }
    }

internal fun McaInferenceService.clearGenerationStopTargetImpl(token: GenerationStopToken) {
        synchronized(generationStopGate) {
            if (activeGenerationStopTarget?.token == token) {
                activeGenerationStopTarget = null
                if (stopRequestedToken == token) stopRequestedToken = null
            }
        }
    }

    /** Called with the engine lifecycle mutex held, after the prior stream has exited. */
internal fun McaInferenceService.invalidateGenerationStopTargetAfterLifecycleTransition() {
        synchronized(generationStopGate) {
            generationStopEpoch += 1L
            activeGenerationStopTarget = null
            stopRequestedToken = null
        }
    }
