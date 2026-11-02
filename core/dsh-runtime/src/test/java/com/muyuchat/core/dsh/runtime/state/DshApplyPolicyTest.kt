package com.muyuchat.core.dsh.runtime.state

import com.muyuchat.core.dsh.contract.DshGatewayEndpoint
import com.muyuchat.core.dsh.contract.DshRuntimePhase
import com.muyuchat.core.dsh.contract.DshRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the reload decision rules that protect a live Node generation.
 *
 * The policy never starts or stops anything; it answers one question: given the frozen snapshot,
 * the binding that last reached READY and the caller's in-flight observation, must the owner
 * reuse, defer or replace the generation? A wrong answer here either leaves a stale gateway
 * (the original defect) or cuts a running agent turn.
 */
class DshApplyPolicyTest {

    private val gateway = DshGatewayEndpoint(
        baseUrl = "http://127.0.0.1:41000/v1",
        bearerToken = "gateway-token-0001",
        catalogRevision = "rev-a"
    )
    private val oldBinding = DshHostConfig(gateway, capability = null, hostBuild = "engine-1")
    private val newBinding = DshHostConfig(
        gateway.copy(bearerToken = "gateway-token-0002", catalogRevision = "rev-b"),
        capability = null,
        hostBuild = "engine-1"
    )

    private fun ready(epoch: Long = 3L) = DshRuntimeSnapshot(
        phase = DshRuntimePhase.READY,
        epoch = epoch,
        origin = "http://127.0.0.1:4321",
        changedAtMillis = 10L
    )


    private fun decide(
        snapshot: DshRuntimeSnapshot = ready(),
        hostAlive: Boolean = true,
        applied: DshHostConfig? = oldBinding,
        desired: DshHostConfig = newBinding,
        request: DshApplyRequest = DshApplyRequest(inFlightCalls = 0)
    ) = DshApplyPolicy.decide(snapshot, hostAlive, applied, desired, request)

    @Test
    fun aStoppedOrDeadHostIsAlwaysStartedForTheRequestedBinding() {
        val stopped = decide(
            snapshot = DshRuntimeSnapshot(phase = DshRuntimePhase.STOPPED, epoch = 2L),
            hostAlive = false,
            applied = null
        )
        assertTrue(stopped is DshApplyDecision.Apply)
        stopped as DshApplyDecision.Apply
        assertEquals(false, stopped.reload)
        assertNull(stopped.replaced)
        assertEquals(0, stopped.terminateInFlight)

        // A READY snapshot with a dead process is not a live generation either.
        val deadReady = decide(hostAlive = false)
        assertTrue(deadReady is DshApplyDecision.Apply)
        assertEquals(false, (deadReady as DshApplyDecision.Apply).reload)
    }

    @Test
    fun unchangedBindingOnAReadyHostIsANoOp() {
        val decision = decide(desired = oldBinding)
        assertTrue(decision is DshApplyDecision.Unchanged)
        decision as DshApplyDecision.Unchanged
        assertEquals(oldBinding.id, decision.id)
        assertEquals(3L, decision.epoch)
    }

    @Test
    fun aChangedBindingReplacesTheReadyGeneration() {
        val decision = decide()
        assertTrue(decision is DshApplyDecision.Apply)
        decision as DshApplyDecision.Apply
        assertEquals(true, decision.reload)
        assertEquals(oldBinding.id, decision.replaced)
        assertEquals("gateway-token+model-catalog", decision.change)
    }

    @Test
    fun aReadyBindingWithoutAConfirmedRecordIsReplaced() {
        val decision = decide(applied = null)
        assertTrue(decision is DshApplyDecision.Apply)
        assertEquals(true, (decision as DshApplyDecision.Apply).reload)
    }

    @Test
    fun inFlightWorkDefersAnUnforcedReload() {
        val decision = decide(request = DshApplyRequest(inFlightCalls = 2))
        assertTrue(decision is DshApplyDecision.Deferred)
        decision as DshApplyDecision.Deferred
        assertEquals(DshApplyDeferReason.IN_FLIGHT_TASKS, decision.reason)
        assertEquals(oldBinding.id, decision.retained)
        assertEquals(2, decision.inFlightCalls)
    }

    @Test
    fun forcedReloadTerminatesTheObservedInFlightWork() {
        val decision = decide(request = DshApplyRequest(inFlightCalls = 2, force = true))
        assertTrue(decision is DshApplyDecision.Apply)
        decision as DshApplyDecision.Apply
        assertEquals(true, decision.reload)
        assertEquals(2, decision.inFlightCalls)
        assertEquals(2, decision.terminateInFlight)
    }

    @Test
    fun observedZeroWorkIsAppliedAndTerminatesNothing() {
        val idle = decide(request = DshApplyRequest(inFlightCalls = 0))
        assertTrue(idle is DshApplyDecision.Apply)
        assertEquals(0, (idle as DshApplyDecision.Apply).terminateInFlight)
        assertEquals(0, idle.inFlightCalls)
    }

    @Test
    fun unobservableWorkIsDeferredInsteadOfBeingTreatedAsZero() {
        // The tri-state contract: only an observed zero means "idle". An unobservable observation
        // must never be silently coerced to 0, because a reload would terminate work MCA cannot
        // see. The deferral stays typed and the caller can force it.
        val unknown = decide(request = DshApplyRequest(inFlightCalls = null))
        assertTrue(unknown is DshApplyDecision.Deferred)
        unknown as DshApplyDecision.Deferred
        assertEquals(DshApplyDeferReason.IN_FLIGHT_TASKS, unknown.reason)
        assertNull(unknown.inFlightCalls)

        // A forced apply still works and reports that nothing was observed.
        val forced = decide(request = DshApplyRequest(inFlightCalls = null, force = true))
        assertTrue(forced is DshApplyDecision.Apply)
        forced as DshApplyDecision.Apply
        assertNull(forced.inFlightCalls)
        assertEquals(0, forced.terminateInFlight)
    }

    @Test
    fun droppingTheGatewayDoesNotTearDownAWorkingModelChannel() {
        val accountOnly = DshHostConfig(gateway = null, capability = null, hostBuild = "engine-1")
        val deferred = decide(desired = accountOnly)
        assertTrue(deferred is DshApplyDecision.Deferred)
        assertEquals(DshApplyDeferReason.GATEWAY_UNAVAILABLE, (deferred as DshApplyDecision.Deferred).reason)

        val forced = decide(desired = accountOnly, request = DshApplyRequest(force = true))
        assertTrue(forced is DshApplyDecision.Apply)
        assertEquals("gateway-unbound", (forced as DshApplyDecision.Apply).change)
    }

    @Test
    fun rollbackIsOnlyMeaningfulWhileThePreviousGatewayGenerationIsAlive() {
        // Same generation, catalog-only change: the loopback server is still up, so a rollback
        // restores a host that can actually serve models.
        assertTrue(
            DshApplyPolicy.canRollbackTo(
                oldBinding,
                DshHostConfig(gateway.copy(catalogRevision = "rev-b"), null, "engine-1")
            )
        )
        // A rebound gateway (new token and/or port) belongs to a stopped generation: restoring the
        // previous binding would produce a READY host with a dead model channel.
        assertFalse(DshApplyPolicy.canRollbackTo(oldBinding, newBinding))
        assertFalse(
            DshApplyPolicy.canRollbackTo(
                oldBinding,
                DshHostConfig(gateway.copy(baseUrl = "http://127.0.0.1:41009/v1"), null, "engine-1")
            )
        )
        // Unbinding/account-only targets never need the gateway, so a rollback there is valid.
        assertTrue(DshApplyPolicy.canRollbackTo(oldBinding, DshHostConfig(null, null, "engine-1")))
        assertTrue(DshApplyPolicy.canRollbackTo(DshHostConfig(null, null, "engine-1"), oldBinding))
        // An identical binding is not a rollback candidate at all.
        assertFalse(DshApplyPolicy.canRollbackTo(oldBinding, oldBinding))
    }

    @Test
    fun anUnboundGenerationCanStillGainAGateway() {
        val unbound = DshHostConfig(gateway = null, capability = null, hostBuild = "engine-1")
        val decision = DshApplyPolicy.decide(
            snapshot = ready(),
            hostAlive = true,
            applied = unbound,
            desired = oldBinding,
            request = DshApplyRequest(inFlightCalls = 0)
        )
        assertTrue(decision is DshApplyDecision.Apply)
        assertEquals("gateway-bound", (decision as DshApplyDecision.Apply).change)
    }
}
