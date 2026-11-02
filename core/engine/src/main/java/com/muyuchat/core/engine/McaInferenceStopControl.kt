package com.muyuchat.core.engine

import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
            // The fallback path is still bounded the same way: a blocking native stop must not
            // hold a lifecycle/UI caller for the length of an in-flight prefill.
            dispatchRunnerStopBounded(runner, STOP_REQUEST_TIMEOUT_MS)
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
            // Dispatch can lag behind a model reload or a replacement turn.
            // Recheck after the dispatcher hop before signaling the runner.
            val stillCurrent = synchronized(generationStopGate) {
                activeGenerationStopTarget?.token == expected &&
                    stopRequestedToken == expected
            }
            if (!stillCurrent) return@withContext true
            // The engine-owned token is the active-operation proof. Do not
            // perform a second native active-state query here: a damaged
            // runner may serialize that query behind decode and recreate
            // the very Binder stall this path is meant to avoid. The stop
            // itself is dispatched on its own thread and only acknowledged
            // within the bounded window; a blocked native/Binder stop must
            // never hold the UI, gateway or lifecycle caller.
            dispatchRunnerStopBounded(target.runner, STOP_REQUEST_TIMEOUT_MS)
        }
    }

/**
 * Dispatches one blocking runner `requestStop()` on a disposable daemon thread and bounds only the
 * acknowledgement wait.
 *
 * A native runner may serialize `requestStop()` behind the same native mutex as prefill/decode. The
 * JNI/Binder stop flag is set before that wait (the llama.cpp wrapper stores its stop flag before
 * taking the engine mutex), so the cancellation itself is already requested; this function must never
 * convert that native wait into caller latency. `true` means the stop was accepted and dispatched,
 * never that native work has stopped — quiescence is answered separately by
 * [McaInferenceService.awaitStopSettlement].
 *
 * A [CancellationException] thrown by the runner is re-thrown to the caller instead of being
 * swallowed. The dispatched thread is daemon-only and is never retained: repeated stops for the same
 * generation token are rejected earlier by [McaInferenceService.stopGenerationIfActiveImpl], so at
 * most one dispatch thread can exist per captured token.
 */
internal suspend fun dispatchRunnerStopBounded(
    runner: LocalChatRunner,
    timeoutMs: Long = STOP_REQUEST_TIMEOUT_MS
): Boolean {
    require(timeoutMs > 0L) { "stop dispatch timeout must be positive" }
    val acknowledgement = CompletableDeferred<StopDispatchAcknowledgement>()
    thread(isDaemon = true, name = "mca-native-stop-dispatch") {
        val failure = try {
            runner.requestStop()
            null
        } catch (cancellation: CancellationException) {
            cancellation
        } catch (error: Throwable) {
            error
        }
        acknowledgement.complete(StopDispatchAcknowledgement(failure))
    }
    val acknowledgementResult = withTimeoutOrNull(timeoutMs) { acknowledgement.await() }
        ?: return true // dispatched; the runner-owned recovery watchdog owns the bound from here
    val failure = acknowledgementResult.failure ?: return true
    if (failure is CancellationException) throw failure
    return false
}

internal data class StopDispatchAcknowledgement(val failure: Throwable?)


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
            // Attach the exact epoch to the retained execution record. Clearing this token later
            // must not erase the quiescence proof; the record outlives the token on purpose.
            attachGenerationStopTokenLocked(requestId, token)
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
            // A native worker incarnation boundary: every proof taken before this point is stale
            // and must never be used to release a lease against the replacement worker.
            markLifecycleTransitionLocked("native lifecycle transition")
        }
    }
