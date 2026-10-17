package com.muyuchat.mca

import android.os.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.muyuchat.mca.LocalImageWorkerClient.BoundWorker
import com.muyuchat.mca.LocalImageWorkerClient.ActiveRequest

private const val WATCHDOG_POLL_INTERVAL_MS = 1_000L

    internal fun LocalImageWorkerClient.cancelRemote(request: LocalImageWorkerClient.ActiveRequest): Boolean {
        val endpoint = currentEndpoint() ?: return false
        val cancelled = runRemoteCall(endpoint) {
            endpoint.service.cancel(LocalImageWorkerProtocol.cancelRequest(request.requestId))
        }.getOrElse { false }
        if (cancelled) {
            // The worker also publishes a cancellation terminal, but a Binder callback is not a
            // reliable prerequisite for releasing the caller. Complete locally as soon as the
            // registered remote request confirms cancellation; the callback remains idempotent.
            request.completion.completeExceptionally(LocalImageWorkerCancelledException())
        }
        return cancelled
    }

internal fun LocalImageWorkerClient.requestRemoteSelfTermination(
        request: LocalImageWorkerClient.ActiveRequest,
        endpoint: LocalImageWorkerClient.BoundWorker
    ): Boolean {
        return runRemoteCall(endpoint) {
            endpoint.service.cancelAndExit(LocalImageWorkerProtocol.cancelRequest(request.requestId))
        }.getOrElse { false }
    }

internal fun LocalImageWorkerClient.completeWatchdogTimeoutAndRequestExit(
        request: LocalImageWorkerClient.ActiveRequest,
        timeout: Throwable,
        endpoint: LocalImageWorkerClient.BoundWorker?
    ) {
        request.watchdogTimedOut = true
        if (!request.completion.completeExceptionally(timeout)) {
            request.watchdogTerminationDispatched.complete(Unit)
            return
        }
        try {
            // The completion wakes the awaiting caller, but its finally block waits for this exact
            // dispatch before releasing the binding. This preserves terminal-result arbitration
            // while preventing unbind from racing ahead of cancelAndExit.
            endpoint?.let { requestRemoteSelfTermination(request, it) }
        } finally {
            request.watchdogTerminationDispatched.complete(Unit)
        }
    }

    internal suspend fun LocalImageWorkerClient.awaitWatchdogTerminationDispatch(request: LocalImageWorkerClient.ActiveRequest) {
        if (!request.watchdogTimedOut) return
        withContext(NonCancellable) {
            request.watchdogTerminationDispatched.await()
        }
    }

    internal fun LocalImageWorkerClient.startWatchdog(request: LocalImageWorkerClient.ActiveRequest, model: LocalImageModelRecord) {
        val policy = localImageWorkerWatchdogPolicy(
            runtime = model.runtime,
            family = model.family,
            steps = request.requestedSteps,
            useCfg = request.requestedUseCfg,
            backendMode = request.requestedBackendMode
        ) ?: return
        request.watchdogJob = scope.launch {
            while (!request.completion.isCompleted) {
                val startedAt = request.watchdogStartedAtMs
                if (startedAt <= 0L) {
                    delay(WATCHDOG_POLL_INTERVAL_MS)
                    continue
                }
                val remaining = policy.timeoutMs - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0L) {
                    delay(remaining.coerceAtMost(WATCHDOG_POLL_INTERVAL_MS))
                    continue
                }
                break
            }
            if (request.completion.isCompleted) return@launch

            val timeout = LocalImageWorkerRemoteException(
                code = policy.timeoutCode,
                message = localImageWorkerWatchdogMessage(
                    timeoutMs = policy.timeoutMs,
                    phase = request.lastProgressPhase,
                    stageTrace = request.lastStageTrace,
                    runtimeLabel = policy.runtimeLabel
                )
            )
            val timedOutEndpoint = currentEndpoint()
            // Native QNN graphExecute/contextCreate are synchronous vendor calls. Ask the exact
            // request-bound disposable service instance to terminate itself; a callback PID may
            // already have been reused and is never safe to kill from this process.
            completeWatchdogTimeoutAndRequestExit(request, timeout, timedOutEndpoint)
        }
    }

    internal fun LocalImageWorkerClient.startUpscaleWatchdog(request: LocalImageWorkerClient.ActiveRequest) {
        val acceptedAt = SystemClock.elapsedRealtime()
        request.watchdogStartedAtMs = acceptedAt
        request.lastWorkerCallbackAtMs = acceptedAt
        request.watchdogJob = scope.launch {
            var timeoutReason = "maximum runtime"
            while (!request.completion.isCompleted) {
                val now = SystemClock.elapsedRealtime()
                val runtimeElapsed = now - request.watchdogStartedAtMs
                val heartbeatElapsed = now - request.lastWorkerCallbackAtMs
                val heartbeatTimeout = localImageUpscaleHeartbeatTimeoutMs(
                    request.lastProgressPhase
                )
                if (runtimeElapsed >= LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS) {
                    timeoutReason = "maximum runtime"
                    break
                }
                if (heartbeatElapsed >= heartbeatTimeout) {
                    timeoutReason = "worker heartbeat"
                    break
                }
                val remainingRuntime = LOCAL_IMAGE_UPSCALE_MAX_RUNTIME_MS - runtimeElapsed
                val remainingHeartbeat = heartbeatTimeout - heartbeatElapsed
                delay(
                    minOf(
                        WATCHDOG_POLL_INTERVAL_MS,
                        remainingRuntime,
                        remainingHeartbeat
                    ).coerceAtLeast(1L)
                )
            }
            if (request.completion.isCompleted) return@launch

            val timeout = LocalImageWorkerRemoteException(
                code = LOCAL_IMAGE_UPSCALE_WATCHDOG_TIMEOUT_CODE,
                message = "Local ESRGAN worker exceeded its bounded $timeoutReason deadline " +
                    "at phase=${request.lastProgressPhase.ifBlank { "unknown" }}."
            )
            val timedOutEndpoint = currentEndpoint()
            completeWatchdogTimeoutAndRequestExit(request, timeout, timedOutEndpoint)
        }
    }

    internal fun LocalImageWorkerClient.currentEndpoint(): LocalImageWorkerClient.BoundWorker? = synchronized(stateLock) {
        val session = bindingSession ?: return@synchronized null
        val service = remote?.takeIf { remoteBinder?.isBinderAlive == true }
            ?: return@synchronized null
        BoundWorker(service, session)
    }

    /**
     * LocalImageWorkerService deliberately exits after every request. A new request issued in the
     * same event loop turn can otherwise attach to that retiring service and receive onNullBinding
     * or a binder-death callback. The short cooldown is transport-only and does not delay native
     * execution once a fresh service is bound.
     */
    internal suspend fun LocalImageWorkerClient.awaitRebindCooldown() {
        val remaining = synchronized(stateLock) {
            if (bindingSession != null || lastBindingReleaseAtElapsedMs <= 0L) {
                0L
            } else {
                (LOCAL_IMAGE_WORKER_REBIND_COOLDOWN_MS -
                    (SystemClock.elapsedRealtime() - lastBindingReleaseAtElapsedMs))
                    .coerceAtLeast(0L)
            }
        }
        if (remaining > 0L) delay(remaining)
    }
