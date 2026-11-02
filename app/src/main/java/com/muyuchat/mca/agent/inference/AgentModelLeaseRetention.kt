package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelAttemptRef
import com.muyuchat.core.engine.EngineLifecycleLeaseHandle

/** Lifecycle of one retained Agent model lease. */
internal enum class AgentLeaseState {
    /** Acquired and registered, no generation dispatched yet. */
    ACQUIRED,

    /** Generation was dispatched and the stream has not been proven stopped. */
    DISPATCHED,

    /** Stop was requested; the lease is fenced until a stop proof exists. */
    STOPPING,

    /** Stop proof timed out. The lease is deliberately retained (no forced unlock). */
    FENCE_HELD,

    /** The lease was released exactly once. */
    RELEASED
}

internal data class AgentLeaseTimeline(
    val acquiredAtMillis: Long,
    val dispatchedAtMillis: Long? = null,
    val stopRequestedAtMillis: Long? = null,
    val releasedAtMillis: Long? = null
)

internal data class AgentLeaseSnapshot(
    val attempt: AgentModelAttemptRef,
    val requestId: String,
    val state: AgentLeaseState,
    val timeline: AgentLeaseTimeline,
    val lastError: String? = null
)

/** One lease retained for the app scope. */
internal class AgentRetainedLease(
    val attempt: AgentModelAttemptRef,
    val requestId: String,
    private val lease: EngineLifecycleLeaseHandle,
    private val acquiredAtMillis: Long
) {
    private val lock = Any()
    private var state = AgentLeaseState.ACQUIRED
    private var dispatched = false
    private var dispatchedAt: Long? = null
    private var stopRequestedAt: Long? = null
    private var releasedAt: Long? = null
    private var lastError: String? = null
    private var releasing = false

    internal fun markDispatchStarted(nowMillis: Long) {
        synchronized(lock) {
            if (state == AgentLeaseState.ACQUIRED || state == AgentLeaseState.STOPPING) {
                state = AgentLeaseState.DISPATCHED
            }
            if (!dispatched) {
                dispatched = true
                dispatchedAt = nowMillis
            }
        }
    }

    internal fun markStopRequested(nowMillis: Long) {
        synchronized(lock) {
            if (state == AgentLeaseState.ACQUIRED || state == AgentLeaseState.DISPATCHED) {
                state = AgentLeaseState.STOPPING
                stopRequestedAt = nowMillis
            }
        }
    }

    internal fun fence(reason: String) {
        synchronized(lock) {
            if (state != AgentLeaseState.RELEASED) {
                state = AgentLeaseState.FENCE_HELD
                lastError = reason
            }
        }
    }

    internal fun dispatchStarted(): Boolean = synchronized(lock) { dispatched }

    internal fun isReleased(): Boolean = synchronized(lock) { state == AgentLeaseState.RELEASED }

    internal fun snapshot(): AgentLeaseSnapshot = synchronized(lock) {
        AgentLeaseSnapshot(
            attempt = attempt,
            requestId = requestId,
            state = state,
            timeline = AgentLeaseTimeline(
                acquiredAtMillis = acquiredAtMillis,
                dispatchedAtMillis = dispatchedAt,
                stopRequestedAtMillis = stopRequestedAt,
                releasedAtMillis = releasedAt
            ),
            lastError = lastError
        )
    }

    /**
     * Claims the single release. Returns false when another caller already released it or a
     * release is in flight. The actual engine release then runs outside the monitor.
     */
    internal fun claimRelease(nowMillis: Long, note: String?): Boolean = synchronized(lock) {
        if (state == AgentLeaseState.RELEASED || releasing) return false
        releasing = true
        lastError = note ?: lastError
        true
    }

    internal suspend fun performRelease(nowMillis: Long) {
        try {
            lease.release()
            synchronized(lock) {
                state = AgentLeaseState.RELEASED
                releasedAt = nowMillis
                releasing = false
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                state = AgentLeaseState.FENCE_HELD
                lastError = "lease release failed: ${failure.message ?: failure::class.java.simpleName}"
                releasing = false
            }
            throw failure
        }
    }
}

internal sealed interface AgentLeaseRelease {
    data class Released(val snapshot: AgentLeaseSnapshot) : AgentLeaseRelease
    data class AlreadyReleased(val snapshot: AgentLeaseSnapshot?) : AgentLeaseRelease
    data class Blocked(val snapshot: AgentLeaseSnapshot?) : AgentLeaseRelease
    data object UnknownAttempt : AgentLeaseRelease
}

/**
 * App-scope accounting for the engine lifecycle leases held by Agent model attempts.
 *
 * Contract:
 *  * register immediately after acquire and before any suspension;
 *  * the retention owns the lease, so cancelling the attempt coroutine does not unlock it;
 *  * release happens exactly once and only with a confirmed stop proof (or before dispatch,
 *    when no native work was ever started);
 *  * a timed-out proof keeps the fence and the lease; there is no forced unlock path.
 */
internal class AgentModelLeaseRetention(
    private val clock: AgentInferenceClock = SystemAgentInferenceClock
) {
    private val lock = Any()
    private val entries = linkedMapOf<String, AgentRetainedLease>()

    fun register(
        attempt: AgentModelAttemptRef,
        requestId: String,
        lease: EngineLifecycleLeaseHandle
    ): AgentRetainedLease = synchronized(lock) {
        val key = attempt.attemptId
        val existing = entries[key]
        if (existing != null && !existing.dispatchStarted()) {
            error("lifecycle lease for attempt ${attempt.attemptId} is already registered")
        }
        AgentRetainedLease(attempt, requestId, lease, clock.nowMillis()).also { entries[key] = it }
    }

    fun markDispatchStarted(attempt: AgentModelAttemptRef): AgentLeaseSnapshot? {
        val entry = synchronized(lock) { entries[attempt.attemptId] } ?: return null
        entry.markDispatchStarted(clock.nowMillis())
        return entry.snapshot()
    }

    fun markStopRequested(attempt: AgentModelAttemptRef): AgentLeaseSnapshot? {
        val entry = synchronized(lock) { entries[attempt.attemptId] } ?: return null
        entry.markStopRequested(clock.nowMillis())
        return entry.snapshot()
    }

    fun snapshot(attempt: AgentModelAttemptRef): AgentLeaseSnapshot? {
        val entry = synchronized(lock) { entries[attempt.attemptId] } ?: return null
        return entry.snapshot()
    }

    fun snapshots(): List<AgentLeaseSnapshot> = synchronized(lock) {
        entries.values.map(AgentRetainedLease::snapshot)
    }

    /**
     * Releases only when [proof] confirms the exact request stopped. A timed-out or unknown proof
     * moves the entry to FENCE_HELD/STOPPING and leaves the lease held.
     */
    suspend fun releaseAfterProof(
        attempt: AgentModelAttemptRef,
        proof: AgentStopProof
    ): AgentLeaseRelease {
        val entry = synchronized(lock) { entries[attempt.attemptId] } ?: return AgentLeaseRelease.UnknownAttempt
        if (entry.isReleased()) return AgentLeaseRelease.AlreadyReleased(entry.snapshot())
        if (proof !is AgentStopProof.Confirmed) {
            when (proof) {
                is AgentStopProof.TimedOut -> entry.fence(proof.describe())
                is AgentStopProof.Unknown -> entry.fence(proof.reason)
                else -> Unit
            }
            return AgentLeaseRelease.Blocked(entry.snapshot())
        }
        if (!entry.claimRelease(clock.nowMillis(), proof.describe())) {
            return if (entry.isReleased()) AgentLeaseRelease.AlreadyReleased(entry.snapshot())
            else AgentLeaseRelease.Blocked(entry.snapshot())
        }
        return try {
            entry.performRelease(clock.nowMillis())
            AgentLeaseRelease.Released(entry.snapshot())
        } catch (failure: Throwable) {
            AgentLeaseRelease.Blocked(entry.snapshot())
        }
    }

    /**
     * Releases a lease acquired for an attempt that was cancelled before any generation dispatch.
     * This is the only release path that does not require a stop proof, and it is rejected once
     * a stream was dispatched (a native owner may still be reading).
     */
    suspend fun abandonBeforeDispatch(attempt: AgentModelAttemptRef): AgentLeaseRelease {
        val entry = synchronized(lock) { entries[attempt.attemptId] } ?: return AgentLeaseRelease.UnknownAttempt
        if (entry.isReleased()) return AgentLeaseRelease.AlreadyReleased(entry.snapshot())
        if (entry.dispatchStarted()) {
            entry.markStopRequested(clock.nowMillis())
            return AgentLeaseRelease.Blocked(entry.snapshot())
        }
        if (!entry.claimRelease(clock.nowMillis(), "cancelled before generation dispatch")) {
            return AgentLeaseRelease.Blocked(entry.snapshot())
        }
        return try {
            entry.performRelease(clock.nowMillis())
            AgentLeaseRelease.Released(entry.snapshot())
        } catch (failure: Throwable) {
            AgentLeaseRelease.Blocked(entry.snapshot())
        }
    }
}
