package com.muyuchat.mca.agent.inference

import com.muyuchat.core.engine.EngineExecutionIdentity
import com.muyuchat.core.engine.EngineStopSettlement
import com.muyuchat.core.engine.NativeGenerationActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val REQUEST_ID = "agent:run-1:0:1"

/**
 * C01 / P01: `Confirmed` is only ever produced from the engine's own typed settlement for the
 * exact request. Token disappearance, stream termination, supervisor terminal and read failures
 * are not inputs to the proof anymore, so they cannot become `Confirmed`.
 */
class EngineAgentStopProofSourceTest {

    private class FakeStopSettlementSurface : AgentStopSettlementSurface {
        var settlement: EngineStopSettlement =
            EngineStopSettlement.Unknown(REQUEST_ID, "not configured")
        var failure: Throwable? = null
        var calls = 0
        var lastRequestId: String? = null
        var lastTimeoutMillis: Long? = null

        override suspend fun awaitStopSettlement(
            requestId: String,
            timeoutMillis: Long
        ): EngineStopSettlement {
            calls += 1
            lastRequestId = requestId
            lastTimeoutMillis = timeoutMillis
            failure?.let { throw it }
            return settlement
        }
    }

    private fun identity(requestId: String = REQUEST_ID): EngineExecutionIdentity =
        EngineExecutionIdentity(
            requestId = requestId,
            requestEpoch = 7L,
            loadIncarnation = 3L,
            runtime = "llama_cpp"
        )

    @Test
    fun releasedSettlementConfirmsTheExactRequest() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.Released(
            identity = identity(),
            evidence = "native idle (runtime=llama_cpp, requestEpoch=7, loadIncarnation=3)"
        )
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 10L })

        val proof = source.awaitStopped(attempt(), REQUEST_ID, 1_000L)

        val confirmed = proof as AgentStopProof.Confirmed
        assertTrue(confirmed.evidence.contains("native idle"))
        assertEquals(1, surface.calls)
        assertEquals(REQUEST_ID, surface.lastRequestId)
        assertEquals(1_000L, surface.lastTimeoutMillis ?: -1L)
    }

    @Test
    fun releasedSettlementWaitedMillisComesFromTheMeasuredBoundedWait() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.Released(identity(), "native idle")
        var clockReads = 0
        val clock = AgentInferenceClock {
            clockReads += 1
            if (clockReads == 1) 1_000L else 1_040L
        }
        val source = EngineAgentStopProofSource(surface, clock)

        val confirmed = source.awaitStopped(attempt(), REQUEST_ID, 1_000L) as AgentStopProof.Confirmed

        assertEquals(40L, confirmed.waitedMillis)
    }

    @Test
    fun notDispatchedSettlementConfirmsSafeDirectRelease() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.NotDispatched(identity())
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        val confirmed = source.awaitStopped(attempt(), REQUEST_ID, 1_000L) as AgentStopProof.Confirmed

        assertTrue(confirmed.evidence.contains("before native dispatch"))
    }

    @Test
    fun timedOutSettlementBecomesTimedOutAndIsNeverConfirmed() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.TimedOut(
            identity = identity(),
            waitedMillis = 900L,
            detail = "native generation is still active",
            activity = NativeGenerationActivity.ACTIVE
        )
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        val proof = source.awaitStopped(attempt(), REQUEST_ID, 1_000L)

        assertFalse(proof is AgentStopProof.Confirmed)
        val timedOut = proof as AgentStopProof.TimedOut
        assertEquals(900L, timedOut.waitedMillis)
        assertEquals("ACTIVE", timedOut.activity)
        assertTrue(timedOut.detail.contains("still active"))
    }

    @Test
    fun stoppingUnknownSettlementBecomesUnknownAndIsNeverConfirmed() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.StoppingUnknown(
            identity = identity(),
            detail = "runtime provided no non-blocking native activity evidence",
            activity = NativeGenerationActivity.UNKNOWN
        )
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        val proof = source.awaitStopped(attempt(), REQUEST_ID, 1_000L)

        assertFalse(proof is AgentStopProof.Confirmed)
        val unknown = proof as AgentStopProof.Unknown
        assertTrue(unknown.reason.contains("UNKNOWN"))
        assertTrue(unknown.reason.contains("not proven quiet"))
    }

    @Test
    fun unknownSettlementBecomesUnknownAndIsNeverConfirmed() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.Unknown(
            requestId = REQUEST_ID,
            reason = "no engine execution record exists for this request"
        )
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        val proof = source.awaitStopped(attempt(), REQUEST_ID, 1_000L)

        assertFalse(proof is AgentStopProof.Confirmed)
        assertTrue((proof as AgentStopProof.Unknown).reason.contains("no engine execution record"))
    }

    @Test
    fun releasedSettlementForAForeignRequestIsNeverConfirmed() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.settlement = EngineStopSettlement.Released(
            identity = EngineExecutionIdentity(
                requestId = "agent:run-1:0:9",
                requestEpoch = 7L,
                loadIncarnation = 3L,
                runtime = "llama_cpp"
            ),
            evidence = "native idle for another request"
        )
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        val proof = source.awaitStopped(attempt(), REQUEST_ID, 1_000L)

        assertFalse(proof is AgentStopProof.Confirmed)
        assertTrue((proof as AgentStopProof.Unknown).reason.contains("belongs to request"))
    }

    @Test
    fun settlementReadFailureIsUnknownAndIsNeverConfirmed() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.failure = IllegalStateException("engine probe exploded")
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        val proof = source.awaitStopped(attempt(), REQUEST_ID, 1_000L)

        assertFalse(proof is AgentStopProof.Confirmed)
        assertTrue((proof as AgentStopProof.Unknown).reason.contains("engine probe exploded"))
    }

    @Test
    fun settlementReadCancellationIsRethrown() = runBlocking {
        val surface = FakeStopSettlementSurface()
        surface.failure = CancellationException("scope cancelled")
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        var caught: CancellationException? = null
        try {
            source.awaitStopped(attempt(), REQUEST_ID, 1_000L)
        } catch (cancelled: CancellationException) {
            caught = cancelled
        }

        assertTrue(caught != null)
        assertEquals(1, surface.calls)
    }

    @Test
    fun nonPositiveTimeoutDoesNotQueryTheEngine() = runBlocking {
        val surface = FakeStopSettlementSurface()
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        assertFalse(source.awaitStopped(attempt(), REQUEST_ID, 0L) is AgentStopProof.Confirmed)
        assertEquals(0, surface.calls)
    }

    @Test
    fun blankRequestIdDoesNotQueryTheEngine() = runBlocking {
        val surface = FakeStopSettlementSurface()
        val source = EngineAgentStopProofSource(surface, AgentInferenceClock { 0L })

        assertFalse(source.awaitStopped(attempt(), " ", 1_000L) is AgentStopProof.Confirmed)
        assertEquals(0, surface.calls)
    }
}
