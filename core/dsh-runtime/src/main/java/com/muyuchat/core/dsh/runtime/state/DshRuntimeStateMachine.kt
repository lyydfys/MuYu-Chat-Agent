package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimeFailure
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * Single-owner lifecycle state machine for the Android DSH host.
 *
 * It describes the host process only, never DSH session/task state. One instance belongs to
 * one [com.muyuchat.core.dsh.runtime.DshRuntimeOwner]; the owner serializes calls, this class
 * only guards its own fields.
 *
 * Invariants:
 * - every start (including a restart after failure) allocates a strictly larger epoch, and a
 *   READY snapshot's epoch identifies exactly one Node process + launch token generation;
 * - STOPPING never allocates a new epoch;
 * - a stop that cannot settle raises a stop fence: the host reports FAILED/STOP_TIMEOUT and
 *   refuses a new start until the process tree is confirmed gone.
 */
class DshRuntimeStateMachine(
    /**
     * Last epoch allocated by a previous owner instance. The next start allocates
     * [initialEpoch] + 1, so a recreated Service never republishes a used epoch.
     */
    initialEpoch: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis
) {
    init {
        require(initialEpoch >= 0L) { "initialEpoch must be non-negative" }
    }

    private var phase: DshRuntimePhase = DshRuntimePhase.STOPPED
    private var epoch: Long = initialEpoch
    private var origin: String? = null
    private var failure: DshRuntimeFailure? = null
    private var changedAtMillis: Long = clock()
    private var stopFence: Boolean = false

    @Synchronized
    fun snapshot(): DshRuntimeSnapshot = DshRuntimeSnapshot(
        phase = phase,
        epoch = epoch,
        origin = origin,
        failure = failure,
        changedAtMillis = changedAtMillis
    )

    @Synchronized
    fun currentEpoch(): Long = epoch

    @Synchronized
    fun isStopFenceRaised(): Boolean = stopFence

    /**
     * True when a new generation may start. READY/STARTING/STOPPING and an unresolved stop
     * fence forbid a second Node process.
     */
    @Synchronized
    fun canStart(): Boolean = canStartLocked(allowReadyReplacement = false)

    /** Allocates the next epoch and enters STARTING, or returns null when start is forbidden. */
    @Synchronized
    fun beginStart(): DshRuntimeSnapshot? = beginStartLocked(allowReadyReplacement = false)

    /**
     * Allocates the next epoch and enters STARTING from a READY generation for a binding reload.
     *
     * Only the serialized runtime owner may call this, and only after it has stopped the READY
     * process tree and proved it gone. A reload must not publish an intermediate STOPPED: that
     * would release the app-process model gateway and project an "unexpected stop" shell, while
     * the reload is precisely the transition that keeps the same gateway and page generation
     * alive. [beginStart] stays strict so no other caller can start a second process.
     */
    @Synchronized
    fun beginReload(): DshRuntimeSnapshot? = beginStartLocked(allowReadyReplacement = true)

    private fun canStartLocked(allowReadyReplacement: Boolean): Boolean {
        if (stopFence) return false
        if (allowReadyReplacement && phase == DshRuntimePhase.READY) return true
        return phase == DshRuntimePhase.STOPPED || phase == DshRuntimePhase.FAILED
    }

    private fun beginStartLocked(allowReadyReplacement: Boolean): DshRuntimeSnapshot? {
        if (!canStartLocked(allowReadyReplacement)) return null
        epoch += 1
        phase = DshRuntimePhase.STARTING
        origin = null
        failure = null
        changedAtMillis = clock()
        return snapshot()
    }

    /** Publishes the official loopback origin for the current generation. */
    @Synchronized
    fun markReady(origin: String): DshRuntimeSnapshot {
        check(phase == DshRuntimePhase.STARTING) {
            "dsh: READY is only reachable from STARTING, was $phase"
        }
        check(origin.startsWith("http://")) { "dsh: ready origin must be an http loopback origin" }
        this.origin = origin
        failure = null
        phase = DshRuntimePhase.READY
        changedAtMillis = clock()
        return snapshot()
    }

    /**
     * Enters FAILED with a concrete host failure. A call after STOPPING/STOPPED is a no-op so
     * an in-flight start failure cannot overwrite the stop outcome.
     */
    @Synchronized
    fun fail(
        code: DshFailureCode,
        message: String,
        retryable: Boolean = true,
        diagnostics: String? = null
    ): DshRuntimeSnapshot {
        if (phase == DshRuntimePhase.STOPPED || phase == DshRuntimePhase.STOPPING) return snapshot()
        phase = DshRuntimePhase.FAILED
        origin = null
        failure = DshRuntimeFailure(code = code, message = message, retryable = retryable, diagnostics = diagnostics)
        changedAtMillis = clock()
        return snapshot()
    }

    /** Marks the beginning of a stop for the current generation (epoch is preserved). */
    @Synchronized
    fun beginStop(): DshRuntimeSnapshot {
        if (phase == DshRuntimePhase.STOPPED) return snapshot()
        phase = DshRuntimePhase.STOPPING
        origin = null
        failure = null
        changedAtMillis = clock()
        return snapshot()
    }

    /** Stop completed and the process tree is confirmed gone. */
    @Synchronized
    fun markStopped(): DshRuntimeSnapshot {
        phase = DshRuntimePhase.STOPPED
        origin = null
        failure = null
        stopFence = false
        changedAtMillis = clock()
        return snapshot()
    }

    /** Stop did not settle; the fence stays up and a new start is refused. */
    @Synchronized
    fun markStopTimeout(diagnostics: String? = null): DshRuntimeSnapshot {
        phase = DshRuntimePhase.FAILED
        origin = null
        failure = DshRuntimeFailure(
            code = DshFailureCode.STOP_TIMEOUT,
            message = "the DSH host process tree did not stop in time",
            retryable = false,
            diagnostics = diagnostics
        )
        stopFence = true
        changedAtMillis = clock()
        return snapshot()
    }

    /**
     * The previously fenced process tree is now confirmed gone; the fence is lifted and the
     * host returns to STOPPED without allocating an epoch.
     */
    @Synchronized
    fun confirmExitedAfterFence(): DshRuntimeSnapshot {
        check(stopFence) { "dsh: no stop fence to clear" }
        stopFence = false
        phase = DshRuntimePhase.STOPPED
        origin = null
        failure = null
        changedAtMillis = clock()
        return snapshot()
    }
}
