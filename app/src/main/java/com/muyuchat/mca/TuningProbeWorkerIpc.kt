package com.muyuchat.mca

import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal fun TuningProbeWorkerService.beginBinderStart(): TuningProbeProcessLifecycle.StartToken? {
        var staleExit: Runnable? = null
        val token = synchronized(lock) {
            processLifecycle.beginStart()?.also {
                // Invalidate the Runnable while holding the same lock used by
                // its kill decision. removeCallbacks() is only the best-effort
                // queue cleanup after the logical cancellation is published.
                staleExit = scheduledProcessExit
                scheduledProcessExit = null
            }
        }
        staleExit?.let(handler::removeCallbacks)
        return token
    }

internal fun TuningProbeWorkerService.emit(activeRequest: ActiveRequest, startedAt: Long, stage: String, message: String) {
        if (activeRequest.cancelRequested) throw CancellationException("Tuning probe cancelled.")
        activeRequest.stage = stage
        runCatching {
            activeRequest.callback.onProgress(
                TuningProbeWorkerProtocol.progress(
                    requestId = activeRequest.request.requestId,
                    stage = stage,
                    message = message,
                    elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                )
            )
        }.onFailure { cancelForDeadClient(activeRequest) }
    }

internal fun TuningProbeWorkerService.sendComplete(
        callback: ITuningProbeWorkerCallback,
        result: TuningProbeWorkerProtocol.Result
    ): Boolean = runCatching {
        callback.onComplete(TuningProbeWorkerProtocol.complete(result))
    }.isSuccess

internal fun TuningProbeWorkerService.sendError(
        callback: ITuningProbeWorkerCallback,
        requestId: String,
        code: String,
        message: String
    ) {
        runCatching { callback.onError(TuningProbeWorkerProtocol.error(requestId, code, message)) }
    }

internal fun TuningProbeWorkerService.cancelForDeadClient(activeRequest: ActiveRequest) {
        if (synchronized(lock) { active === activeRequest }) {
            activeRequest.cancelRequested = true
            activeRequest.job?.cancel(CancellationException("Tuning probe client disconnected."))
            scheduleProcessExit(TuningProbeWorkerService.PROCESS_EXIT_GRACE_MS)
        }
    }

internal fun TuningProbeWorkerService.finish(activeRequest: ActiveRequest) {
        synchronized(lock) {
            if (active === activeRequest) {
                active = null
                check(processLifecycle.finishActive(activeRequest.startToken)) {
                    "Process lifecycle lost the active tuning request."
                }
            }
        }
        unlinkDeathRecipient(activeRequest)
    }

internal fun TuningProbeWorkerService.unlinkDeathRecipient(activeRequest: ActiveRequest) {
        activeRequest.deathRecipient?.let { recipient ->
            runCatching { activeRequest.callback.asBinder().unlinkToDeath(recipient, 0) }
        }
    }

internal fun TuningProbeWorkerService.scheduleProcessExit(delayMs: Long) {
        val scheduledEpoch = serviceEpoch
        lateinit var exitTask: Runnable
        exitTask = Runnable {
            synchronized(lock) {
                val isCurrentTask = scheduledProcessExit === exitTask
                if (isCurrentTask) scheduledProcessExit = null
                if (isCurrentTask && processLifecycle.shouldExit(
                        scheduledEpoch = scheduledEpoch,
                        currentEpoch = TuningProbeWorkerService.PROCESS_EPOCH.get(),
                        hasExternalActiveRequest = active != null
                    )) {
                    // Keep the check and kill atomic with start(), which uses
                    // the same lock. A stale Runnable that lost removeCallbacks
                    // never gets to reuse a newer task's exit authorization.
                    Process.killProcess(Process.myPid())
                }
            }
        }
        synchronized(lock) {
            if (processLifecycle.destroyed) return
            scheduledProcessExit?.let(handler::removeCallbacks)
            scheduledProcessExit = exitTask
            handler.postDelayed(exitTask, delayMs)
        }
    }

internal class ActiveRequest(
    val request: TuningProbeWorkerProtocol.Request,
    val callback: ITuningProbeWorkerCallback,
    val startToken: TuningProbeProcessLifecycle.StartToken
) {
    var deathRecipient: IBinder.DeathRecipient? = null
    var job: Job? = null

    @Volatile
    var stage: String = "request"

    @Volatile
    var cancelRequested: Boolean = false
}
