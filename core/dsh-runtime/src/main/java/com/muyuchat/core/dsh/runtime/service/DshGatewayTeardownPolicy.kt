package com.muyuchat.core.dsh.runtime.service

import com.muyuchat.core.dsh.contract.DshFailureCode
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/**
 * Composition-side rule for when the loopback model gateway generation may be torn down (D01).
 *
 * The gateway is the still-running Node host's model channel. A `stop()` whose outcome is
 * unknown (STOP_TIMEOUT / FAILED / any non-STOPPED snapshot) must not close that channel while
 * the owner may still be running: closing it would silently break a live host and would also
 * claim that resources were released without evidence. Only a confirmed STOPPED snapshot
 * authorises teardown.
 *
 * A start failure is different: when [afterStartAttempt] is true the caller has just tried to
 * start and received a non-READY snapshot, so there is no live host authorised to use the
 * gateway and the half-created generation can be closed.
 *
 * Kept pure so JVM tests pin the rule without Android or a Binder.
 */
object DshGatewayTeardownPolicy {

    /** True only for a confirmed stop; unknown outcomes keep the gateway alive. */
    fun shouldStopAfterStop(snapshot: DshRuntimeSnapshot): Boolean =
        snapshot.phase == DshRuntimePhase.STOPPED

    /**
     * After an explicit start/restart/apply attempt failed, the gateway generation is normally
     * unusable and must be closed. STOPPED also qualifies (nothing to serve); READY/STARTING/
     * STOPPING never do.
     *
     * STOP_TIMEOUT is the exception: the owner could not prove that the process tree is gone, so a
     * surviving Node generation may still be calling this gateway. Closing it would cut a live
     * host's model channel on the strength of an unknown outcome, which is exactly what
     * [shouldStopAfterStop] exists to prevent.
     */
    fun shouldStopAfterStartAttempt(snapshot: DshRuntimeSnapshot): Boolean =
        snapshot.phase != DshRuntimePhase.READY &&
            snapshot.phase != DshRuntimePhase.STARTING &&
            snapshot.phase != DshRuntimePhase.STOPPING &&
            snapshot.failure?.code != DshFailureCode.STOP_TIMEOUT

    /**
     * Before a new start/restart, a gateway generation from a stopped, stopping or failed host must
     * be replaced so a new Node can never receive the previous generation's bearer token. A READY
     * host keeps its token (re-attach); an in-flight START must not have its channel rotated.
     *
     * This is credential supersession, not a stop-fence bypass: the owner's start scan still
     * decides whether a host may start. For an unconfirmed stop it may refuse the start, leaving a
     * fresh gateway with no Node; the previous token is already invalid.
     */
    fun shouldRotateBeforeStart(snapshot: DshRuntimeSnapshot): Boolean =
        snapshot.phase != DshRuntimePhase.READY &&
            snapshot.phase != DshRuntimePhase.STARTING
}
