package com.muyuchat.mca.agent.platform

import com.muyuchat.core.agent.contract.AgentRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunStopActionRouterTest {

    @Test
    fun malformedPayloadIsRejectedBeforeProvider() {
        var providerCalls = 0
        val router = AgentRunStopActionRouter(
            activeRunProvider = { providerCalls++; AgentStopAction("run-1", 7L) }
        )
        assertRejected(
            router.route("run 1", 7L),
            AgentRunStopActionRejection.MALFORMED
        )
        assertRejected(router.route("run-1", 0L), AgentRunStopActionRejection.MALFORMED)
        assertRejected(router.route(null, 7L), AgentRunStopActionRejection.MALFORMED)
        assertEquals(0, providerCalls)
    }

    @Test
    fun noActiveRunIsRejected() {
        val router = AgentRunStopActionRouter(activeRunProvider = { null })
        assertRejected(router.route("run-1", 7L), AgentRunStopActionRejection.NOT_ACTIVE)
    }

    @Test
    fun differentRunIsRejectedBeforeCallback() {
        var callbackCalls = 0
        val router = AgentRunStopActionRouter(
            activeRunProvider = { AgentStopAction("run-1", 7L) },
            callback = { callbackCalls++; accepted() }
        )
        assertRejected(router.route("run-2", 7L), AgentRunStopActionRejection.DIFFERENT)
        assertEquals(0, callbackCalls)
    }

    @Test
    fun staleEpochIsRejectedBeforeCallback() {
        var callbackCalls = 0
        val router = AgentRunStopActionRouter(
            activeRunProvider = { AgentStopAction("run-1", 8L) },
            callback = { callbackCalls++; accepted() }
        )
        assertRejected(router.route("run-1", 7L), AgentRunStopActionRejection.STALE)
        assertEquals(0, callbackCalls)
    }

    @Test
    fun exactRunAndEpochDispatchesToOwnerCallback() {
        val active = AgentStopAction("run-1", 7L)
        var received: AgentStopAction? = null
        val router = AgentRunStopActionRouter(
            activeRunProvider = { active },
            callback = {
                received = it
                AgentRunStopCallbackResult(AgentRunStopCallbackOutcome.ACCEPTED)
            }
        )
        val result = router.route("run-1", 7L) as AgentRunStopActionRouteResult.Dispatched
        assertEquals(active, result.action)
        assertEquals(AgentRunStopCallbackOutcome.ACCEPTED, result.callbackResult.outcome)
        assertEquals(active, received)
    }

    @Test
    fun ownerAlreadyStoppingIsReportedAsTypedCallbackOutcome() {
        val router = AgentRunStopActionRouter(
            activeRunProvider = { AgentStopAction("run-1", 7L) },
            callback = {
                AgentRunStopCallbackResult(
                    AgentRunStopCallbackOutcome.ALREADY_STOPPING,
                    "owner_stopping"
                )
            }
        )
        val result = router.route("run-1", 7L) as AgentRunStopActionRouteResult.Dispatched
        assertEquals(AgentRunStopCallbackOutcome.ALREADY_STOPPING, result.callbackResult.outcome)
        assertEquals("owner_stopping", result.callbackResult.detailRef)
    }

    @Test
    fun callbackFailureIsTypedAndDoesNotEscape() {
        val router = AgentRunStopActionRouter(
            activeRunProvider = { AgentStopAction("run-1", 7L) },
            callback = { throw IllegalStateException("owner crashed") }
        )
        val result = router.route("run-1", 7L)
            as AgentRunStopActionRouteResult.Rejected
        assertEquals(AgentRunStopActionRejection.CALLBACK_FAILED, result.reason)
        assertTrue(result.error != null)
        assertEquals("run-1", result.error!!.correlationId)
    }

    @Test
    fun guardAndRouterRejectStaleAndDifferentRuns() {
        val source = FakeLeaseSource()
        val guard = AgentRunProcessGuard(source)
        val router = AgentRunStopActionRouter(
            activeRunProvider = guard::activeStopAction,
            callback = { AgentRunStopCallbackResult(AgentRunStopCallbackOutcome.ACCEPTED) }
        )
        val first = AgentStopAction("run-1", 7L)
        guard.update(first, AgentRunState.RUNNING)

        assertTrue(router.route("run-1", 7L) is AgentRunStopActionRouteResult.Dispatched)
        assertRejected(router.route("run-1", 6L), AgentRunStopActionRejection.STALE)

        guard.update(first, AgentRunState.CANCELLED)
        assertRejected(router.route("run-1", 7L), AgentRunStopActionRejection.NOT_ACTIVE)

        val second = AgentStopAction("run-2", 8L)
        guard.update(second, AgentRunState.WAITING_APPROVAL)
        assertRejected(router.route("run-1", 7L), AgentRunStopActionRejection.DIFFERENT)
        assertRejected(router.route("run-2", 7L), AgentRunStopActionRejection.STALE)
        assertTrue(router.route("run-2", 8L) is AgentRunStopActionRouteResult.Dispatched)
    }

    @Test
    fun defaultIgnoreCallbackKeepsRejectionTyped() {
        val router = AgentRunStopActionRouter(activeRunProvider = { AgentStopAction("run-1", 7L) })
        val result = router.route("run-1", 7L)
        assertTrue(result is AgentRunStopActionRouteResult.Dispatched)
        assertEquals(
            AgentRunStopCallbackOutcome.REJECTED,
            (result as AgentRunStopActionRouteResult.Dispatched).callbackResult.outcome
        )
    }

    private fun assertRejected(result: AgentRunStopActionRouteResult, reason: AgentRunStopActionRejection) {
        val rejected = result as AgentRunStopActionRouteResult.Rejected
        assertEquals(reason, rejected.reason)
        assertNull(rejected.error)
    }

    private fun accepted(): AgentRunStopCallbackResult =
        AgentRunStopCallbackResult(AgentRunStopCallbackOutcome.ACCEPTED)

    private class FakeLeaseSource : AgentForegroundLeaseSource {
        override fun acquire(correlationId: String): AgentForegroundLeaseResult =
            AgentForegroundLease.acquire(
                correlationId = correlationId,
                starter = AgentProcessProtectionStarter { object : AgentProcessProtectionLease { override fun release() = Unit } }
            )
    }
}
