package com.muyuchat.core.dsh.runtime

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import com.muyuchat.core.dsh.runtime.state.DshApplyDecision
import com.muyuchat.core.dsh.runtime.state.DshApplyDeferReason
import com.muyuchat.core.dsh.runtime.state.DshApplyPolicy
import com.muyuchat.core.dsh.runtime.state.DshApplyRequest
import com.muyuchat.core.dsh.runtime.state.DshApplyResult
import com.muyuchat.core.dsh.runtime.state.DshApplyStatus
import com.muyuchat.core.dsh.runtime.state.DshBindingId
import com.muyuchat.core.dsh.runtime.state.DshHostConfig

/**
 * Applies one desired host binding and reports exactly what happened.
 *
 * Responsibility: the *reconciliation* half of the runtime owner. The owner keeps the single
 * serialization mutex, the process tree, the stop fence and the state machine; this class only
 * turns a desired binding into one of the typed outcomes:
 *
 *  - [DshApplyStatus.UNCHANGED] when the READY generation already runs it (no process, same epoch);
 *  - [DshApplyStatus.DEFERRED_IN_FLIGHT] / [DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE] when work
 *    still owns the current generation and the caller did not force a reload;
 *  - [DshApplyStatus.APPLIED] only after the readiness gate confirmed the new generation;
 *  - [DshApplyStatus.ROLLED_BACK] when the new binding failed and the previous one was started
 *    again and reached READY;
 *  - [DshApplyStatus.FAILED] when neither is READY.
 *
 * Rollback is only attempted for a *replacement* of a confirmed READY generation whose gateway
 * generation is still usable (see [DshApplyPolicy.canRollbackTo]). A first start that fails has
 * nothing to restore, and a failed rollback is never reported as success.
 *
 * The class is platform-free and takes every dependency as a typed accessor, so the reconciliation
 * rules are testable without an Android process.
 */
internal class DshHostReloadCoordinator(
    private val log: (String) -> Unit,
    private val snapshot: () -> DshRuntimeSnapshot,
    /** The binding that last reached READY, if it is still the rollback candidate. */
    private val appliedBinding: () -> DshHostConfig?,
    /** Token-free id of the last confirmed READY binding, retained across a stop/exit. */
    private val lastConfirmedId: () -> DshBindingId?,
    private val hostAlive: () -> Boolean,
    /** True while the owner has an outstanding stop request; a rollback must never fight it. */
    private val stopRequested: () -> Boolean,
    /** Starts one generation with [DshHostConfig]; the owner's full start pipeline. */
    private val start: suspend (DshHostConfig, Boolean) -> DshRuntimeSnapshot
) {

    /**
     * Applies [desired]. The caller must hold the owner's operation mutex: this method may start
     * and stop the single Node process.
     */
    suspend fun apply(desired: DshHostConfig, request: DshApplyRequest): DshApplyResult {
        val current = snapshot()
        val decision = DshApplyPolicy.decide(
            snapshot = current,
            hostAlive = hostAlive(),
            applied = appliedBinding(),
            desired = desired,
            request = request
        )
        return when (decision) {
            is DshApplyDecision.Unchanged -> unchanged(desired, request, current, decision)
            is DshApplyDecision.Deferred -> deferred(desired, request, current, decision)
            is DshApplyDecision.Apply -> applyNow(desired, request, decision)
        }
    }

    private fun unchanged(
        desired: DshHostConfig,
        request: DshApplyRequest,
        current: DshRuntimeSnapshot,
        decision: DshApplyDecision.Unchanged
    ): DshApplyResult {
        log("dsh: binding unchanged id=${decision.id} epoch=${decision.epoch}")
        return DshApplyResult(
            status = DshApplyStatus.UNCHANGED,
            requested = desired.id,
            applied = desired.id,
            retained = desired.id,
            epoch = decision.epoch,
            snapshot = current,
            change = "binding-unchanged",
            inFlightCalls = request.inFlightCalls,
            inFlightUnobservable = request.inFlightCalls == null
        )
    }

    private fun deferred(
        desired: DshHostConfig,
        request: DshApplyRequest,
        current: DshRuntimeSnapshot,
        decision: DshApplyDecision.Deferred
    ): DshApplyResult {
        log(
            "dsh: binding change deferred reason=${decision.reason} retained=${decision.retained} " +
                "inFlight=${decision.inFlightCalls ?: "unknown"} change=${decision.change}"
        )
        val status = when (decision.reason) {
            DshApplyDeferReason.IN_FLIGHT_TASKS -> DshApplyStatus.DEFERRED_IN_FLIGHT
            DshApplyDeferReason.GATEWAY_UNAVAILABLE -> DshApplyStatus.DEFERRED_GATEWAY_UNAVAILABLE
        }
        return DshApplyResult(
            status = status,
            requested = desired.id,
            applied = null,
            retained = decision.retained ?: appliedBinding()?.id,
            epoch = current.epoch,
            snapshot = current,
            change = decision.change,
            inFlightCalls = decision.inFlightCalls ?: request.inFlightCalls,
            inFlightUnobservable = (decision.inFlightCalls ?: request.inFlightCalls) == null,
            detail = "the current generation was left running"
        )
    }

    private suspend fun applyNow(
        desired: DshHostConfig,
        request: DshApplyRequest,
        decision: DshApplyDecision.Apply
    ): DshApplyResult {
        val previous = appliedBinding()
        val started = start(desired, decision.reload)
        if (started.phase == DshRuntimePhase.READY) {
            log(
                "dsh: binding applied id=${desired.id} epoch=${started.epoch} change=${decision.change} " +
                    "inFlightTerminated=${decision.terminateInFlight}"
            )
            return DshApplyResult(
                status = DshApplyStatus.APPLIED,
                requested = desired.id,
                applied = desired.id,
                retained = desired.id,
                epoch = started.epoch,
                snapshot = started,
                change = decision.change,
                inFlightCalls = decision.inFlightCalls ?: request.inFlightCalls,
                inFlightTerminated = decision.terminateInFlight,
                inFlightUnobservable = (decision.inFlightCalls ?: request.inFlightCalls) == null
            )
        }
        // Rollback only when this call replaced a generation that had actually been confirmed.
        // A stop that arrived while this apply was running wins: restoring the old binding here
        // would launch a host the user just asked to stop.
        if (stopRequested()) {
            log("dsh: stop requested during the binding apply; not restoring id=${previous?.id}")
            return DshApplyResult(
                status = DshApplyStatus.FAILED,
                requested = desired.id,
                applied = null,
                retained = previous?.id ?: lastConfirmedId(),
                epoch = started.epoch,
                snapshot = started,
                change = decision.change,
                inFlightCalls = decision.inFlightCalls ?: request.inFlightCalls,
                inFlightUnobservable = (decision.inFlightCalls ?: request.inFlightCalls) == null,
                detail = "a stop was requested while the binding was being applied"
            )
        }
        val restore = previous?.takeIf {
            decision.reload && DshApplyPolicy.canRollbackTo(it, desired)
        }
        if (restore == null) {
            if (decision.reload && previous != null) {
                log("dsh: apply failed; no rollback because the previous gateway generation is gone")
            }
            return DshApplyResult(
                status = DshApplyStatus.FAILED,
                requested = desired.id,
                applied = null,
                retained = previous?.id ?: lastConfirmedId(),
                epoch = started.epoch,
                snapshot = started,
                change = decision.change,
                inFlightCalls = decision.inFlightCalls ?: request.inFlightCalls,
                inFlightUnobservable = (decision.inFlightCalls ?: request.inFlightCalls) == null,
                detail = if (decision.reload && previous != null) {
                    "the requested binding failed and the previous gateway generation is gone"
                } else {
                    "the host could not be started with the requested binding"
                }
            )
        }
        log("dsh: binding apply failed; restoring previous binding id=${restore.id}")
        // The failed attempt already left FAILED (no READY generation), so a normal start applies.
        val restored = start(restore, false)
        if (restored.phase == DshRuntimePhase.READY) {
            log("dsh: previous binding restored id=${restore.id} epoch=${restored.epoch}")
            return DshApplyResult(
                status = DshApplyStatus.ROLLED_BACK,
                requested = desired.id,
                applied = restore.id,
                retained = restore.id,
                epoch = restored.epoch,
                snapshot = restored,
                change = decision.change,
                inFlightCalls = decision.inFlightCalls ?: request.inFlightCalls,
                inFlightUnobservable = (decision.inFlightCalls ?: request.inFlightCalls) == null,
                detail = "the requested binding failed; the previous binding was restored"
            )
        }
        log("dsh: rollback to id=${restore.id} also failed; no generation is READY")
        return DshApplyResult(
            status = DshApplyStatus.FAILED,
            requested = desired.id,
            applied = null,
            retained = restore.id,
            epoch = restored.epoch,
            snapshot = restored,
            change = decision.change,
            inFlightCalls = decision.inFlightCalls ?: request.inFlightCalls,
            inFlightUnobservable = (decision.inFlightCalls ?: request.inFlightCalls) == null,
            detail = "the requested binding and its rollback both failed"
        )
    }
}
