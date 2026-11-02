package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong

// The single-owner lease for UI generation.
//
// Moved here verbatim from MainViewModel during P2. The class and its three companion types
// were already top-level declarations above the class, so the move gives them a file named for
// what they do.
//
// This is the mechanism the task asks to be preserved and verified: one owner per generation,
// a run id that advances with every reservation, and a phase that says whether the shared
// engine is currently held by this UI run. Every transition here is atomic with that epoch,
// which is what lets a delayed Room callback or a lifecycle stop act only on the exact UI
// request that created it -- stale callbacks are isolated rather than trusted.
//
// The class doc kept from the original says the same thing in one line: it separates UI
// generation ownership from the shared engine projection used by Local API calls. That
// separation is why a Local API request and a UI request cannot both believe they own the
// runtime.
//
// Kept in com.muyuchat.mca: no import changes anywhere. Every declaration in this file is
// internal, and there is no top-level private declaration in the block, so nothing needed its
// visibility widened. The private members inside the class -- ActiveOwner, the lock, the
// counters -- move with it and stay private.
//
// P03 later added the exact local attempt identity (ChatAttemptIdentity) to the existing active
// owner, captured atomically by cancellation. Every original field, phase, branch and epoch rule
// is unchanged.

internal enum class UiGenerationRuntimePhase {
    PENDING,
    LOCAL_ACTIVE,
    CLOUD_ACTIVE,
    TERMINAL
}

internal data class UiGenerationReservation(
    val runId: Long,
    val supersededOwner: Any?
)

/**
 * Frozen identity of the one local engine request a UI chat run dispatched.
 *
 * The UI request id already encodes the monotonic run id (`ui-<runId>-<uuid>`): the run id never
 * repeats for the lifetime of the process and the UUID makes accidental reuse impossible, so this
 * value can target exactly one attempt without ever consulting "the token that is current" later.
 * The init check is the identity verification: an attempt can only ever be attributed to the run
 * named in its own id.
 */
internal data class ChatAttemptIdentity(
    val runId: Long,
    val requestId: String
) {
    init {
        require(runId > 0L) { "A chat attempt must belong to a real generation run." }
        require(requestId.startsWith("ui-$runId-")) {
            "A chat attempt id must name its own generation run."
        }
    }
}

internal data class UiGenerationCancellation(
    val owner: Any?,
    val pendingCancelled: Boolean,
    val stopLocalRuntime: Boolean,
    val invalidatedRunId: Long,
    /**
     * The exact local engine request registered by the cancelled run, captured atomically under the
     * ownership lock. Null means the run never dispatched a local attempt (cloud, not yet started, or
     * already idle), so a stop must not target the engine at all -- never read a current token here.
     */
    val localAttempt: ChatAttemptIdentity? = null
) {
    val cancelled: Boolean
        get() = owner != null || pendingCancelled
}

/**
 * Separates UI generation ownership from the shared engine projection used by Local API calls.
 * Every transition is atomic with the generation epoch, so a delayed Room callback or lifecycle
 * stop can only act on the exact UI request that created it.
 */
internal class UiGenerationOwnership(
    private val sequence: AtomicLong
) {
    private data class ActiveOwner(
        val runId: Long,
        val owner: Any,
        var phase: UiGenerationRuntimePhase,
        var localAttempt: ChatAttemptIdentity? = null
    )

    private val lock = Any()
    private var foreground = true
    private var pendingRunId: Long? = null
    private var activeOwner: ActiveOwner? = null

    fun reserveStart(): UiGenerationReservation? = synchronized(lock) {
        if (!foreground) return@synchronized null
        val supersededOwner = activeOwner?.owner
        val runId = sequence.incrementAndGet()
        activeOwner = null
        pendingRunId = runId
        UiGenerationReservation(runId, supersededOwner)
    }

    fun activate(reservation: UiGenerationReservation, owner: Any): Boolean = synchronized(lock) {
        // A request admitted while the UI was visible must still be allowed to
        // enter the engine if the process moves to the background in the small
        // hand-off window before activation.  Backgrounding no longer cancels
        // an owned request; explicit user cancellation remains authoritative.
        if (pendingRunId != reservation.runId || sequence.get() != reservation.runId) {
            return@synchronized false
        }
        pendingRunId = null
        activeOwner = ActiveOwner(
            runId = reservation.runId,
            owner = owner,
            phase = UiGenerationRuntimePhase.PENDING
        )
        true
    }

    fun markPhase(
        runId: Long,
        owner: Any,
        phase: UiGenerationRuntimePhase
    ): Boolean = synchronized(lock) {
        val active = activeOwner
        if (active?.runId != runId || active.owner !== owner || sequence.get() != runId) {
            return@synchronized false
        }
        active.phase = phase
        true
    }

    /**
     * Registers the exact local engine request of [runId], once.
     *
     * A run dispatches at most one local attempt. Re-registering the same identity is accepted so a
     * resume/replay path stays idempotent; a different id for the same run, a stale run or another
     * owner's run is refused. The registration is what a later cancel freezes, so it must happen
     * before the engine can be dispatched and before any phase transition that permits a stop.
     */
    fun markLocalAttempt(
        runId: Long,
        owner: Any,
        attempt: ChatAttemptIdentity
    ): Boolean = synchronized(lock) {
        if (attempt.runId != runId) return@synchronized false
        val active = activeOwner
        if (active?.runId != runId || active.owner !== owner || sequence.get() != runId) {
            return@synchronized false
        }
        val existing = active.localAttempt
        if (existing == null) {
            active.localAttempt = attempt
            return@synchronized true
        }
        existing == attempt
    }

    fun finish(runId: Long, owner: Any) = synchronized(lock) {
        val active = activeOwner
        if (active?.runId == runId && active.owner === owner) {
            activeOwner = null
        }
    }

    fun cancelPending(reservation: UiGenerationReservation): Boolean = synchronized(lock) {
        if (pendingRunId != reservation.runId || sequence.get() != reservation.runId) {
            return@synchronized false
        }
        pendingRunId = null
        sequence.incrementAndGet()
        true
    }

    fun cancelCurrent(): UiGenerationCancellation = synchronized(lock) {
        val active = activeOwner
        val pendingCancelled = pendingRunId != null
        pendingRunId = null
        activeOwner = null
        val invalidatedRunId = sequence.incrementAndGet()
        UiGenerationCancellation(
            owner = active?.owner,
            pendingCancelled = pendingCancelled,
            stopLocalRuntime = active?.phase == UiGenerationRuntimePhase.LOCAL_ACTIVE,
            invalidatedRunId = invalidatedRunId,
            localAttempt = active?.localAttempt
        )
    }

    fun background(): UiGenerationCancellation = synchronized(lock) {
        foreground = false
        // Process backgrounding is not a cancellation request.  Local chat,
        // cloud chat, and their persistence callbacks must finish while the
        // foreground service keeps the process alive.  Keep both pending and
        // active ownership intact so late terminal events still belong to the
        // same UI run when the activity returns.
        UiGenerationCancellation(
            owner = null,
            pendingCancelled = false,
            stopLocalRuntime = false,
            invalidatedRunId = sequence.get()
        )
    }

    fun foreground() = synchronized(lock) {
        foreground = true
    }
}
