package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot

/** Why a requested binding change was not applied to a live generation. */
enum class DshApplyDeferReason {
    /**
     * The current generation still owns model/tool work, **or** that work could not be observed at
     * all. Unobservable work is not a zero: reloading would terminate a turn MCA cannot see, so an
     * unforced reload is deferred with [DshApplyResult.inFlightUnobservable] set.
     */
    IN_FLIGHT_TASKS,

    /**
     * The requested binding has no MCA gateway while the live generation is serving models
     * through one. This is what an unobservable gateway/catalog failure looks like, so it is not
     * a reason to tear down a working channel; an explicit force is.
     */
    GATEWAY_UNAVAILABLE
}

/** What the owner must do for one desired binding against the current generation. */
sealed interface DshApplyDecision {

    /** The READY generation already runs the desired binding. */
    data class Unchanged(val id: DshBindingId, val epoch: Long) : DshApplyDecision

    /** Keep the current generation untouched and report why. */
    data class Deferred(
        val reason: DshApplyDeferReason,
        val retained: DshBindingId?,
        val inFlightCalls: Int?,
        val change: String
    ) : DshApplyDecision

    /**
     * Start a generation with [change] applied. [reload] is true when a READY generation has to be
     * replaced (the rollback candidate exists); [terminateInFlight] counts the observed calls that
     * the generation stop explicitly ends.
     */
    data class Apply(
        val change: String,
        val reload: Boolean,
        val replaced: DshBindingId?,
        val inFlightCalls: Int?,
        val terminateInFlight: Int
    ) : DshApplyDecision
}

/**
 * Single rule for "should the Node host be restarted for this binding".
 *
 * The host applies its provider/workspace/bridge configuration at process start, so a changed
 * binding is only real when a new generation is started and the readiness gate proves it. This
 * policy never starts anything: it only decides, from the frozen snapshot, the last confirmed
 * binding and the caller's in-flight observation. It is pure and JVM-testable.
 */
object DshApplyPolicy {

    fun decide(
        snapshot: DshRuntimeSnapshot,
        hostAlive: Boolean,
        applied: DshHostConfig?,
        desired: DshHostConfig,
        request: DshApplyRequest
    ): DshApplyDecision {
        val change = changeLabel(applied, desired)
        val ready = snapshot.phase == DshRuntimePhase.READY && hostAlive
        if (!ready) {
            // Nothing is serving; a fresh start is the only way to apply the binding. Any
            // in-flight count belongs to a generation that is already gone, so nothing is
            // terminated here.
            return DshApplyDecision.Apply(
                change = change,
                reload = false,
                replaced = null,
                inFlightCalls = request.inFlightCalls,
                terminateInFlight = 0
            )
        }
        if (applied != null && applied.id == desired.id) {
            return DshApplyDecision.Unchanged(desired.id, snapshot.epoch)
        }
        // A READY generation whose binding this owner did not record (older service instance,
        // restored process) cannot be proven to match the request.
        val gatewayDropped = applied?.gateway != null && desired.gateway == null
        if (!request.force) {
            if (gatewayDropped) {
                return DshApplyDecision.Deferred(
                    DshApplyDeferReason.GATEWAY_UNAVAILABLE,
                    applied?.id,
                    request.inFlightCalls,
                    change
                )
            }
            // Tri-state, explicitly: zero observed calls is the only idle answer. Unobservable is
            // not zero — replacing the generation would terminate work MCA cannot see, so it is
            // deferred and reported as unobservable (force is the caller's explicit override).
            val observed = request.inFlightCalls
            if (observed == null) {
                return DshApplyDecision.Deferred(
                    DshApplyDeferReason.IN_FLIGHT_TASKS,
                    applied?.id,
                    null,
                    change
                )
            }
            if (observed > 0) {
                return DshApplyDecision.Deferred(
                    DshApplyDeferReason.IN_FLIGHT_TASKS,
                    applied?.id,
                    observed,
                    change
                )
            }
        }
        return DshApplyDecision.Apply(
            change = change,
            reload = true,
            replaced = applied?.id,
            inFlightCalls = request.inFlightCalls,
            terminateInFlight = if (request.force) request.inFlightCalls ?: 0 else 0
        )
    }

    /**
     * True when restoring [previous] after a failed apply can actually serve models.
     *
     * The app process owns the loopback gateway generation; once it starts a *new* server (new
     * port and bearer token) the old one is stopped, and the previous host binding would point at a
     * dead endpoint. Reporting ROLLED_BACK + READY for that host would claim a working model
     * channel that does not exist, so a rebound gateway is never rolled back to: the caller gets a
     * truthful FAILED and can retry. A catalog-only, bridge-only or composition-only change keeps
     * the same gateway generation (same URL and token), so a rollback there is meaningful.
     */
    fun canRollbackTo(previous: DshHostConfig, desired: DshHostConfig): Boolean {
        if (previous.id == desired.id) return false
        val oldGateway = previous.gateway
        val newGateway = desired.gateway
        if (oldGateway == null || newGateway == null) return true
        return oldGateway.baseUrl == newGateway.baseUrl &&
            oldGateway.bearerToken == newGateway.bearerToken
    }

    /**
     * Token-free description of what differs between two bindings, in a stable order.
     *
     * Used for the confirmation log and the typed result; it never includes a token or a secret.
     */
    fun changeLabel(previous: DshHostConfig?, desired: DshHostConfig): String {
        if (previous == null) return "first-binding"
        val parts = ArrayList<String>(4)
        val previousGateway = previous.gateway
        val desiredGateway = desired.gateway
        when {
            previousGateway == null && desiredGateway != null -> parts += "gateway-bound"
            previousGateway != null && desiredGateway == null -> parts += "gateway-unbound"
            previousGateway != null && desiredGateway != null -> {
                if (previousGateway.baseUrl != desiredGateway.baseUrl) parts += "gateway-port"
                if (previousGateway.bearerToken != desiredGateway.bearerToken) parts += "gateway-token"
                if (previousGateway.catalogRevision != desiredGateway.catalogRevision) parts += "model-catalog"
            }
        }
        val previousBridge = previous.capability
        val desiredBridge = desired.capability
        when {
            previousBridge == null && desiredBridge != null -> parts += "capability-bound"
            previousBridge != null && desiredBridge == null -> parts += "capability-unbound"
            previousBridge != null && desiredBridge != null -> {
                val different = previousBridge.generation != desiredBridge.generation ||
                    previousBridge.appLocalPort != desiredBridge.appLocalPort ||
                    previousBridge.localToken != desiredBridge.localToken
                if (different) parts += "capability-bridge"
            }
        }
        if (previous.hostBuild != desired.hostBuild) parts += "host-build"
        return if (parts.isEmpty()) "binding-unchanged" else parts.joinToString("+")
    }
}
