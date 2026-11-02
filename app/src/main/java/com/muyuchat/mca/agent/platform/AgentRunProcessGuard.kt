package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import kotlinx.coroutines.CancellationException

/**
 * One event produced by applying an Agent run-state transition to the process guard. The owner can
 * inspect [AgentRunProcessGuardEvent.LeaseUnavailable] and pause the run with
 * [AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE]; no event throws and no event aborts a run.
 */
sealed interface AgentRunProcessGuardEvent {
    data class Acquired(val run: AgentStopAction) : AgentRunProcessGuardEvent

    data class Released(
        val run: AgentStopAction?,
        val alreadyReleased: Boolean = false
    ) : AgentRunProcessGuardEvent

    data class Retained(val run: AgentStopAction) : AgentRunProcessGuardEvent

    data class LeaseUnavailable(
        val error: AgentError,
        val pauseReason: AgentPauseReason = AgentPauseReason.FOREGROUND_SERVICE_UNAVAILABLE
    ) : AgentRunProcessGuardEvent

    data class ReleaseFailed(val error: AgentError) : AgentRunProcessGuardEvent
}

data class AgentRunProcessGuardOutcome(
    val previousRun: AgentStopAction?,
    val previousState: AgentRunState?,
    val run: AgentStopAction?,
    val state: AgentRunState,
    val events: List<AgentRunProcessGuardEvent>
) {
    /** Convenience for the owner: non-null means pause with FOREGROUND_SERVICE_UNAVAILABLE. */
    val foregroundUnavailable: AgentRunProcessGuardEvent.LeaseUnavailable?
        get() = events.filterIsInstance<AgentRunProcessGuardEvent.LeaseUnavailable>().firstOrNull()
}

data class AgentRunProcessGuardSnapshot(
    val activeRun: AgentStopAction?,
    val state: AgentRunState?,
    val leasedRun: AgentStopAction?,
    val hasLease: Boolean
)

/**
 * Observes Agent run-state transitions and owns exactly one existing Agent foreground-service lease.
 *
 * Policy:
 *  * RUNNING acquires (only if no lease is already held for that same run);
 *  * WAITING_APPROVAL / WAITING_RESOURCE / WAITING_USER release immediately;
 *  * PAUSING / STOPPING retain an existing lease until terminal settlement or an explicit release;
 *  * terminal states release exactly once;
 *  * QUEUED / PAUSED / NEEDS_REVIEW / INTERRUPTED never hold a lease.
 *
 * A run-key change (new runId or epoch) releases the old run's lease before the new state is applied.
 * The guard is synchronous and is intended to be called by the single Agent owner after a committed
 * state transition; it never writes state and never calls a model/network/UI callback.
 */
class AgentRunProcessGuard(
    private val leaseSource: AgentForegroundLeaseSource
) {
    private val lock = Any()
    private var lease: AgentForegroundLease? = null
    private var leasedRun: AgentStopAction? = null
    private var currentRun: AgentStopAction? = null
    private var currentState: AgentRunState? = null

    /** Convenience for the owner: the committed run record already carries runId, epoch and state. */
    fun update(run: AgentRun, state: AgentRunState = run.state): AgentRunProcessGuardOutcome =
        update(AgentStopAction(runId = run.runId, epoch = run.epoch), state)

    fun update(run: AgentStopAction, state: AgentRunState): AgentRunProcessGuardOutcome =
        synchronized(lock) {
            val previousRun = currentRun
            val previousState = currentState
            val events = mutableListOf<AgentRunProcessGuardEvent>()
            val keyChanged = previousRun != run
            if (lease != null && (keyChanged || !AgentForegroundLeasePolicy.shouldRetain(state))) {
                releaseLocked(events)
            }

            currentRun = if (state.holdsActiveSlot) run else null
            currentState = state

            if (lease == null && AgentForegroundLeasePolicy.shouldAcquire(state)) {
                acquireLocked(run, events)
            } else if (lease != null &&
                (state == AgentRunState.PAUSING || state == AgentRunState.STOPPING)
            ) {
                events += AgentRunProcessGuardEvent.Retained(leasedRun ?: run)
            }

            AgentRunProcessGuardOutcome(
                previousRun = previousRun,
                previousState = previousState,
                run = currentRun,
                state = state,
                events = events.toList()
            )
        }

    /** Stop-action key for the currently active run; null for PAUSED/terminal/stable review states. */
    fun activeStopAction(): AgentStopAction? = synchronized(lock) {
        if (currentState?.holdsActiveSlot == true) currentRun else null
    }

    fun snapshot(): AgentRunProcessGuardSnapshot = synchronized(lock) {
        AgentRunProcessGuardSnapshot(
            activeRun = if (currentState?.holdsActiveSlot == true) currentRun else null,
            state = currentState,
            leasedRun = leasedRun,
            hasLease = lease != null
        )
    }

    /**
     * Owner teardown / explicit cancel path. Releases at most once and clears the active key; a second
     * call returns null. A failed release is reported to the caller and never retried implicitly.
     */
    fun releaseNow(): AgentForegroundLeaseRelease? = synchronized(lock) {
        val held = lease ?: return@synchronized null
        lease = null
        leasedRun = null
        currentRun = null
        currentState = null
        held.release()
    }

    private fun acquireLocked(
        run: AgentStopAction,
        events: MutableList<AgentRunProcessGuardEvent>
    ) {
        val result = try {
            leaseSource.acquire(run.runId, run)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            AgentForegroundLeaseResult.Unavailable(
                platformError(
                    code = AgentPlatformErrorCodes.FOREGROUND_SERVICE_UNAVAILABLE,
                    correlationId = run.runId,
                    causeRef = "foreground_acquire_throw:${failure.javaClass.simpleName}"
                )
            )
        }
        when (result) {
            is AgentForegroundLeaseResult.Acquired -> {
                lease = result.lease
                leasedRun = run
                events += AgentRunProcessGuardEvent.Acquired(run)
            }

            is AgentForegroundLeaseResult.Unavailable -> {
                events += AgentRunProcessGuardEvent.LeaseUnavailable(
                    error = result.error,
                    pauseReason = result.pauseReason
                )
            }
        }
    }

    private fun releaseLocked(events: MutableList<AgentRunProcessGuardEvent>) {
        val held = lease ?: return
        val heldRun = leasedRun
        lease = null
        leasedRun = null
        when (val result = held.release()) {
            AgentForegroundLeaseRelease.Released ->
                events += AgentRunProcessGuardEvent.Released(heldRun, alreadyReleased = false)

            AgentForegroundLeaseRelease.AlreadyReleased ->
                events += AgentRunProcessGuardEvent.Released(heldRun, alreadyReleased = true)

            is AgentForegroundLeaseRelease.Failed ->
                events += AgentRunProcessGuardEvent.ReleaseFailed(result.error)
        }
    }
}
