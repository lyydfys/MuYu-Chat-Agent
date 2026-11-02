package com.muyuchat.mca.video.worker

import kotlinx.coroutines.launch
import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.NativeMediaExecutionLeaseCancelledException

/**
 * The single-active execution core of the :local_video worker (proposal §7.3, V1-02b / P0-B).
 *
 * Pure JVM (no Android imports) so every protocol rule is testable without a device:
 * single active attempt with explicit duplicate refusal, identity-checked cancel, cooperative
 * stop propagation, client-death cancellation and an honest executor seam. The physical
 * execution body is injected and runs INSIDE [withGate], so the shared
 * NativeMediaExecutionGate lease covers the whole pipeline, not just its acquisition. The
 * default [UNAVAILABLE_EXECUTOR] exists only for isolated tests and fails OPENLY with
 * BACKEND_UNAVAILABLE instead of simulating progress or frames.
 */
class VideoWorkerCore(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val withGate: suspend (
        notifyWaiting: () -> Unit,
        isCancelled: () -> Boolean,
        block: suspend () -> Unit,
    ) -> Unit,
    private val executorBody: suspend (Execution) -> Unit = UNAVAILABLE_EXECUTOR,
    private val exitProcess: () -> Nothing = { throw UnsupportedOperationException("exit reserved for the service shell") },
) {

    /** One admitted attempt handed to the executor body. */
    interface Execution {
        val runId: String
        val attemptId: String
        val requestId: String

        /** Transient executable payload (paths/prompt); never persisted by the worker. */
        val payloadJson: String

        /** Cooperative stop flag; the executor polls it at its real cancellation points. */
        fun cancelRequested(): Boolean

        /** Report a REAL stage; hasProgress=false keeps percent bars honest. */
        fun reportStage(stage: VideoRunStage, step: Long? = null, stepTotal: Long? = null)

        /** Settle: succeeded | failed(code) | stopped. Call exactly once. */
        fun complete(outcome: WorkerOutcome)

        /**
         * Diagnostic execution evidence for THIS attempt (for example the external backend that
         * actually ran). Delivered to the owner together with a successful outcome; never a
         * business state write.
         */
        fun reportExecutionDetail(detail: String)
    }

    sealed class WorkerOutcome {
        data object Succeeded : WorkerOutcome()

        data class Failed(val code: VideoErrorCode, val detail: String?) : WorkerOutcome()

        data object Stopped : WorkerOutcome()
    }

    data class Attempt(
        val runId: String,
        val attemptId: String,
        val requestId: String,
        val snapshotJson: String,
        val backend: String,
        val executionJson: String = "",
    )

    interface Events {
        fun onStage(attemptId: String, stage: VideoRunStage, step: Long?, stepTotal: Long?)
        fun onOutcome(attemptId: String, outcome: WorkerOutcome)
        fun onWaitingForLease(attemptId: String)

        /** The single-active slot is now free; the shell may release FGS/stop itself. */
        fun onIdle() {}
    }

    @Volatile
    var activeAttemptId: String? = null
        private set

    @Volatile
    private var activeRequestId: String? = null

    /**
     * Admission binds the request identity BEFORE the coroutine starts waiting for the shared
     * gate. A cancellation that arrives while [currentExecution] does not exist yet is therefore
     * remembered by request id and handed to the gate, instead of being dropped.
     */
    @Synchronized
    fun begin(attempt: Attempt, events: Events): Boolean {
        if (activeAttemptId != null) return false // explicit single-active refusal
        cancelFlag = false
        activeAttemptId = attempt.attemptId
        activeRequestId = attempt.requestId
        scope.launch {
            try {
                withGate(
                    { events.onWaitingForLease(attempt.attemptId) },
                    { cancelFlag },
                ) {
                    runExecutor(attempt, events)
                }
            } catch (_: NativeMediaExecutionLeaseCancelledException) {
                // Cancelled while waiting for the shared gate. The block never ran, so native was
                // never started; report a confirmed stop so the owner settles CANCELLED/RELEASED.
                events.onOutcome(attempt.attemptId, WorkerOutcome.Stopped)
            } finally {
                synchronized(this@VideoWorkerCore) {
                    currentExecution = null
                    currentEvents = null
                    cancelFlag = false
                    activeRequestId = null
                    activeAttemptId = null
                }
                runCatching { events.onIdle() }
            }
        }
        return true
    }

    /** Identity-checked cooperative cancel; unknown ids are ignored (they cannot stop anyone). */
    fun cancel(requestId: String) {
        synchronized(this) {
            if (activeRequestId == requestId) cancelFlag = true
        }
    }

    /** Watchdog escalation: stop flag + hard worker exit (service shell kills the process). */
    fun cancelAndExit(requestId: String) {
        val matched = synchronized(this) {
            if (activeRequestId != requestId) {
                false
            } else {
                cancelFlag = true
                true
            }
        }
        if (!matched) return
        exitWorkerProcess()
    }

    /** Client binder died: treat as cancel of ITS attempt only. */
    fun onClientDied(requestId: String) {
        cancel(requestId)
    }

    private var currentExecution: Execution? = null
    @Volatile
    private var cancelFlag = false

    @Volatile
    private var currentEvents: Events? = null

    /** Per-attempt execution evidence, consumed exactly once when the outcome is delivered. */
    private val executionDetails = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal fun executionDetailFor(attemptId: String): String? = executionDetails.remove(attemptId)

    private fun exitWorkerProcess(): Nothing = exitProcess()

    private suspend fun runExecutor(attempt: Attempt, events: Events) {
        currentEvents = events
        var settled = false
        val execution = object : Execution {
            override val runId = attempt.runId
            override val attemptId = attempt.attemptId
            override val requestId = attempt.requestId
            override val payloadJson = attempt.executionJson

            override fun cancelRequested(): Boolean = cancelFlag

            override fun reportStage(stage: VideoRunStage, step: Long?, stepTotal: Long?) {
                events.onStage(attempt.attemptId, stage, step, stepTotal)
            }

            override fun complete(outcome: WorkerOutcome) {
                if (settled) return // settle exactly once
                settled = true
                events.onOutcome(attempt.attemptId, outcome)
            }

            override fun reportExecutionDetail(detail: String) {
                executionDetails[attempt.attemptId] = detail
            }
        }
        synchronized(this) { currentExecution = execution }
        try {
            if (cancelFlag) {
                // A cancel won the race after the gate check but before the body: stop without
                // starting native, and release the lease through the outer finally.
                execution.complete(WorkerOutcome.Stopped)
                return
            }
            executorBody(execution)
            if (!settled) {
                // An executor that returns without settling is itself a bug: fail loudly, never
                // leave the owner waiting on a silent worker.
                execution.complete(
                    if (cancelFlag) {
                        WorkerOutcome.Stopped
                    } else {
                        WorkerOutcome.Failed(VideoErrorCode.NATIVE_EXECUTION_FAILED, "executor returned without an outcome")
                    },
                )
            }
        } finally {
            synchronized(this) {
                currentExecution = null
                currentEvents = null
            }
        }
    }

    companion object {
        /** Honest pre-V1-03 body: no bridge, no fake stages, no fake frames. */
        val UNAVAILABLE_EXECUTOR: suspend (Execution) -> Unit = { execution ->
            execution.complete(WorkerOutcome.Failed(VideoErrorCode.BACKEND_UNAVAILABLE, "视频 native bridge 尚未接入（V1-03）"))
        }
    }
}
