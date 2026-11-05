package com.muyuchat.mca.agent.inference

import com.muyuchat.core.agent.contract.AgentModelResult
import com.muyuchat.core.engine.GenerateEvent
import com.muyuchat.core.engine.ReasoningMode
import com.muyuchat.core.engine.Role
import com.muyuchat.core.engine.RuntimeStats
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.mca.facade.ChatExecutionFacade
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class McaAgentLocalModelAdapterTest {

    private class Rig(bodyScript: suspend kotlinx.coroutines.flow.FlowCollector<GenerateEvent>.(
        com.muyuchat.core.engine.ChatRequest,
        com.muyuchat.core.engine.LocalChatExecutionContext
    ) -> Unit) {
        val identity = testIdentity()
        val lease = FakeLeaseHandle()
        val engine = FakeEngineSurface(testProfile(identity), testSignatures(identity), lease)
        val body = FakeExecutionBody(bodyScript)
        val facade = ChatExecutionFacade(RuntimeSupervisor(), body)
        val retention = AgentModelLeaseRetention()
        val proof = FixedStopProofSource()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listener = RecordingLeaseSettlementListener()
        var pending = false
        var authority = AgentAttemptAuthority.AlwaysCurrent
        val adapter = McaAgentLocalModelAdapter(
            engine = engine,
            facade = facade,
            pendingProfiles = AgentPendingProfileGuard { pending },
            retention = retention,
            stopProof = proof,
            scope = scope,
            authority = { attempt -> authority.currentState(attempt) },
            stopProofTimeoutMillis = 200,
            stopGraceMillis = 200,
            leaseSettlements = listener,
            lateProofWindowMillis = 2_000,
            lateProofRetryMillis = 50
        )
        val binding = modelBinding(identity.identityHash)

        fun close() = scope.cancel()
    }

    @Test
    fun mapsAgentTranscriptToExplicitChatRequestAndCollectsVisibleTextOnly() = runBlocking {
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("he", RuntimeStats(loaded = true), reasoning = "hidden"))
            emit(GenerateEvent.Chunk("llo", RuntimeStats(loaded = true), reasoning = "more hidden"))
            emit(doneEvent())
        }
        try {
            val request = agentRequest(binding = rig.binding)
            val result = rig.adapter.infer(request) { }

            val success = result as AgentModelResult.Success
            assertEquals("hello", success.visibleText)
            assertEquals(4, success.usage?.outputTokens)
            val sent = requireNotNull(rig.body.lastRequest)
            assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER), sent.messages.map { it.role })
            assertEquals("AGENT SYSTEM", sent.messages.first().content)
            assertEquals(listOf("m-user", "m-assistant", "m-user-2"), sent.messages.drop(1).map { it.id })
            // Local Agent now reuses the active profile's hot n_predict; the loop budget caps
            // generation and the engine would reject a request-scoped hot override.
            // No preset budget and an 8192 window: the budget now follows the window (4096),
        // instead of the old fixed 8192.
        assertEquals(4096, sent.params.nPredict)
            assertEquals(ReasoningMode.OFF, sent.params.reasoningMode)
            assertTrue(sent.params.hideReasoning)
            assertTrue(sent.tools.isEmpty())
            assertTrue(sent.toolExchanges.isEmpty())
            assertNull(sent.persistentPrefixSystemPrompt)
            assertNull(sent.persistentSessionId)
            assertNull(sent.persistentRoleId)
            assertNull(sent.persistentRoleCardVersion)
            assertNull(sent.persistentMemoryVersion)
            assertTrue(sent.runtimeSystemContext.isEmpty())

            val context = requireNotNull(rig.body.lastContext)
            assertFalse(context.includeDeviceClockContext)
            assertSame(rig.lease, context.lifecycleLease)
            assertEquals("agent:run-1:0:1", context.requestId)
            assertEquals(1, rig.lease.releases)
            assertEquals(AgentLeaseState.RELEASED, rig.retention.snapshot(request.attempt)?.state)
        } finally {
            rig.close()
        }
    }

    @Test
    fun requestIdsAreUniquePerAttemptAndAnAttemptCanNeverRunTwice() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            rig.adapter.infer(agentRequest(attempt = attempt(ordinal = 1), binding = rig.binding)) { }
            rig.adapter.infer(agentRequest(attempt = attempt(ordinal = 2), binding = rig.binding)) { }
            assertEquals(2, rig.body.streamCalls)
            assertEquals(
                listOf("agent:run-1:0:1", "agent:run-1:0:2"),
                rig.body.contexts.map { it.requestId }
            )

            val duplicate = rig.adapter.infer(
                agentRequest(attempt = attempt(ordinal = 1), binding = rig.binding)
            ) { }
            assertEquals("INTERNAL_INVARIANT", duplicate.failureCode())
            assertEquals(2, rig.body.streamCalls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun loadBoundSamplingKeysAreRejectedBeforeAcquire() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            val result = rig.adapter.infer(
                agentRequest(binding = rig.binding, samplingJson = """{"n_ctx":4096}""")
            ) { }
            assertEquals("MODEL_BINDING_CHANGED", result.failureCode())
            assertEquals(0, rig.engine.acquires.get())
            assertEquals(0, rig.body.streamCalls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun engineContextLengthErrorMapsToModelContextLimit() = runBlocking {
        val rig = Rig { _, _ -> emit(errorEvent("context_length_exceeded")) }
        try {
            val result = rig.adapter.infer(agentRequest(binding = rig.binding)) { }
            assertEquals("MODEL_CONTEXT_LIMIT", result.failureCode())
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun resourceWaitTimeoutReturnsResourceBusyWithoutLeakingLease() = runBlocking {
        val engine = object : AgentLocalEngineSurface {
            override fun activeExecutionProfile() = null
            override fun parameterSignatureSnapshot() = null
            override suspend fun acquireLifecycleLease(stopActiveGeneration: Boolean) =
                awaitCancellation()
        }
        val body = FakeExecutionBody { _, _ -> emit(doneEvent()) }
        val facade = ChatExecutionFacade(RuntimeSupervisor(), body)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val adapter = McaAgentLocalModelAdapter(
                engine = engine,
                facade = facade,
                pendingProfiles = AgentPendingProfileGuard { false },
                retention = AgentModelLeaseRetention(),
                stopProof = FixedStopProofSource(),
                scope = scope,
                resourceWaitTimeoutMillis = 120
            )
            val result = adapter.infer(agentRequest(binding = modelBinding("identity"))) { }
            assertEquals("RESOURCE_BUSY", result.failureCode())
            assertEquals(0, body.streamCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingDoneIsTypedFailureAndDispatchesOnlyTheExactStop() = runBlocking {
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
        }
        try {
            val request = agentRequest(binding = rig.binding)
            val result = rig.adapter.infer(request) { }

            assertEquals("MODEL_TERMINAL_UNVERIFIED", result.failureCode())
            assertEquals(listOf("agent:run-1:0:1"), rig.body.stoppedRequestIds)
            assertEquals(0, rig.body.globalStops)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun stopTargetsTheExactRequestAndNeverTheGlobalStop() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopSignal = CompletableDeferred<Unit>()
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
            started.complete(Unit)
            stopSignal.await()
            emit(cancelledErrorEvent())
        }
        try {
            val request = agentRequest(binding = rig.binding)
            val inference = async { rig.adapter.infer(request) { } }
            withTimeout(3_000) { started.await() }

            val dispatch = rig.adapter.requestStop(request.attempt)
            assertTrue(dispatch.accepted)
            stopSignal.complete(Unit)
            val result = inference.await()

            assertTrue(result is AgentModelResult.Cancelled)
            assertEquals(listOf("agent:run-1:0:1"), rig.body.stoppedRequestIds)
            assertEquals(0, rig.body.globalStops)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun stopProofTimeoutKeepsFenceAndReleaseIsExactlyOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopSignal = CompletableDeferred<Unit>()
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
            started.complete(Unit)
            stopSignal.await()
            emit(cancelledErrorEvent())
        }
        try {
            rig.proof.proof = AgentStopProof.TimedOut(
                waitedMillis = 200,
                detail = "native generation is still active",
                activity = "ACTIVE"
            )
            val request = agentRequest(binding = rig.binding)
            val inference = async { rig.adapter.infer(request) { } }
            withTimeout(3_000) { started.await() }
            assertTrue(rig.adapter.requestStop(request.attempt).accepted)
            stopSignal.complete(Unit)
            assertTrue(inference.await() is AgentModelResult.Cancelled)

            assertEquals(AgentLeaseState.FENCE_HELD, rig.retention.snapshot(request.attempt)?.state)
            assertEquals(0, rig.lease.releases)

            val late = rig.retention.releaseAfterProof(
                request.attempt,
                AgentStopProof.Confirmed(evidence = "native idle later", waitedMillis = 500)
            )
            assertTrue(late is AgentLeaseRelease.Released)
            assertEquals(1, rig.lease.releases)

            val again = rig.retention.releaseAfterProof(
                request.attempt,
                AgentStopProof.Confirmed(evidence = "native idle again", waitedMillis = 600)
            )
            assertTrue(again is AgentLeaseRelease.AlreadyReleased)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun naturalCompletionReleasesTheLeaseWithoutANativeIdleProbe() = runBlocking {
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("done", RuntimeStats(loaded = true)))
            emit(doneEvent())
        }
        try {
            // Runtimes without a usable native-activity probe return TimedOut/Unknown. A terminal
            // Done is already sufficient proof that this exact request returned.
            rig.proof.proof = AgentStopProof.TimedOut(
                waitedMillis = 200,
                detail = "runtime exposes no native activity probe",
                activity = "UNKNOWN"
            )
            val request = agentRequest(binding = rig.binding)
            val result = rig.adapter.infer(request) { }

            assertTrue(result is AgentModelResult.Success)
            assertEquals(AgentLeaseState.RELEASED, rig.retention.snapshot(request.attempt)?.state)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun staleAttemptAuthorityRejectsBeforeTouchingTheEngine() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            rig.authority = AgentAttemptAuthority { AgentAttemptAuthorityState.SUPERSEDED }
            val result = rig.adapter.infer(agentRequest(binding = rig.binding)) { }
            assertTrue(result is AgentModelResult.Cancelled)
            assertEquals(0, rig.engine.acquires.get())
            assertEquals(0, rig.body.streamCalls)
        } finally {
            rig.close()
        }
    }

    @Test
    fun bindingMismatchNeverDispatchesAndReleasesTheLease() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            val foreign = testIdentity(modelId = "other-model", artifactFingerprint = "artifact-b")
            val result = rig.adapter.infer(
                agentRequest(binding = modelBinding(foreign.identityHash, modelId = "other-model"))
            ) { }
            assertEquals("MODEL_BINDING_CHANGED", result.failureCode())
            assertEquals(0, rig.body.streamCalls)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun pendingProfileTransactionIsResourceBusyAndNeverCommitted() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            rig.pending = true
            val result = rig.adapter.infer(agentRequest(binding = rig.binding)) { }
            assertEquals("RESOURCE_BUSY", result.failureCode())
            assertEquals(0, rig.body.streamCalls)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun stopForUnknownAttemptIsRejected() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            val dispatch = rig.adapter.requestStop(attempt(ordinal = 9))
            assertFalse(dispatch.accepted)
        } finally {
            rig.close()
        }
    }

    /**
     * The cancel command reaches the port as a run id (`AgentRuntimeAssembly.requestCancel` →
     * `stopLiveModelAttempts`), never as a pre-built attempt ref. A run-scoped stop must therefore
     * dispatch the exact engine stop for the attempt that is actually generating.
     */
    @Test
    fun stopRunDispatchesTheExactEngineStopForTheLiveAttempt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopSignal = CompletableDeferred<Unit>()
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
            started.complete(Unit)
            stopSignal.await()
            emit(cancelledErrorEvent())
        }
        try {
            val request = agentRequest(binding = rig.binding)
            val inference = async { rig.adapter.infer(request) { } }
            withTimeout(3_000) { started.await() }

            assertTrue(rig.adapter.stopRun(request.attempt.runId))
            stopSignal.complete(Unit)
            assertTrue(inference.await() is AgentModelResult.Cancelled)

            assertEquals(listOf("agent:run-1:0:1"), rig.body.stoppedRequestIds)
            assertEquals(0, rig.body.globalStops)
            assertEquals(1, rig.lease.releases)
            val event = requireNotNull(rig.listener.lastReleased())
            assertTrue(event.stoppedExecution)
        } finally {
            rig.close()
        }
    }

    @Test
    fun stopRunIgnoresForeignRunsAndFinishedAttempts() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            assertFalse("no live attempt means no dispatch", rig.adapter.stopRun("run-1"))

            val finished = agentRequest(binding = rig.binding)
            rig.adapter.infer(finished) { }
            assertEquals(1, rig.body.streamCalls)
            assertFalse("a returned attempt is not live", rig.adapter.stopRun("run-1"))
            assertTrue(rig.body.stoppedRequestIds.isEmpty())
        } finally {
            rig.close()
        }
    }

    @Test
    fun successfulAttemptReportsAReleasedLeaseWithoutAStopSettlement() = runBlocking {
        val rig = Rig { _, _ -> emit(doneEvent()) }
        try {
            val request = agentRequest(binding = rig.binding)
            rig.adapter.infer(request) { }

            val event = requireNotNull(rig.listener.lastReleased())
            assertFalse(event.stoppedExecution)
            assertEquals(request.attempt, event.attempt)
            assertEquals(AgentLeaseState.RELEASED, rig.retention.snapshot(request.attempt)?.state)
        } finally {
            rig.close()
        }
    }

    @Test
    fun stopDrivenAttemptReportsAReleasedLeaseAsAStopSettlement() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopSignal = CompletableDeferred<Unit>()
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
            started.complete(Unit)
            stopSignal.await()
            emit(cancelledErrorEvent())
        }
        try {
            val request = agentRequest(binding = rig.binding)
            val inference = async { rig.adapter.infer(request) { } }
            withTimeout(3_000) { started.await() }
            assertTrue(rig.adapter.requestStop(request.attempt).accepted)
            stopSignal.complete(Unit)
            assertTrue(inference.await() is AgentModelResult.Cancelled)

            val event = requireNotNull(rig.listener.lastReleased())
            assertTrue(event.stoppedExecution)
            assertEquals(request.attempt, event.attempt)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun lateConfirmedProofReleasesTheFenceExactlyOnceAndIsReportedAsAStopSettlement() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopSignal = CompletableDeferred<Unit>()
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
            started.complete(Unit)
            stopSignal.await()
            emit(cancelledErrorEvent())
        }
        try {
            rig.proof.proof = AgentStopProof.TimedOut(
                waitedMillis = 200,
                detail = "native generation is still active",
                activity = "ACTIVE"
            )
            val request = agentRequest(binding = rig.binding)
            val inference = async { rig.adapter.infer(request) { } }
            withTimeout(3_000) { started.await() }
            assertTrue(rig.adapter.requestStop(request.attempt).accepted)
            stopSignal.complete(Unit)
            assertTrue(inference.await() is AgentModelResult.Cancelled)

            assertEquals(AgentLeaseState.FENCE_HELD, rig.retention.snapshot(request.attempt)?.state)
            assertEquals(0, rig.lease.releases)
            assertNull(rig.listener.lastReleased())

            rig.proof.proof = AgentStopProof.Confirmed(
                evidence = "native idle after the late exit",
                waitedMillis = 400
            )
            withTimeout(5_000) {
                while (rig.lease.releases == 0) delay(20)
            }
            assertEquals(1, rig.lease.releases)
            assertEquals(AgentLeaseState.RELEASED, rig.retention.snapshot(request.attempt)?.state)
            val event = requireNotNull(rig.listener.lastReleased())
            assertTrue(event.stoppedExecution)
            assertEquals(request.attempt, event.attempt)

            // The late watcher must never release the same lease twice.
            delay(300)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }

    @Test
    fun leaseRetentionAbandonBeforeDispatchReleasesOnlyOnce() = runBlocking {
        val lease = FakeLeaseHandle()
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        val registered = retention.register(ref, "agent:run-1:0:1", lease)
        assertEquals(AgentLeaseState.ACQUIRED, registered.snapshot().state)
        val first = retention.abandonBeforeDispatch(ref)
        assertTrue(first is AgentLeaseRelease.Released)
        assertEquals(1, lease.releases)
        val snapshot = requireNotNull(retention.snapshot(ref))
        assertEquals(AgentLeaseState.RELEASED, snapshot.state)
        assertTrue(snapshot.timeline.acquiredAtMillis > 0L)
        assertTrue((snapshot.timeline.releasedAtMillis ?: 0L) >= snapshot.timeline.acquiredAtMillis)
        val second = retention.abandonBeforeDispatch(ref)
        assertTrue(second is AgentLeaseRelease.AlreadyReleased)
        assertEquals(1, lease.releases)
    }

    @Test
    fun dispatchedLeaseCannotBeAbandonedWithoutProof() = runBlocking {
        val lease = FakeLeaseHandle()
        val retention = AgentModelLeaseRetention()
        val ref = attempt()
        retention.register(ref, "agent:run-1:0:1", lease)
        retention.markDispatchStarted(ref)
        val blocked = retention.abandonBeforeDispatch(ref)
        assertTrue(blocked is AgentLeaseRelease.Blocked)
        assertEquals(0, lease.releases)
        assertEquals(AgentLeaseState.STOPPING, retention.snapshot(ref)?.state)
    }

    /**
     * The device-observed hang: the isolated worker is reclaimed mid-prefill, the engine marks the
     * request record superseded at that lifecycle boundary, and no stop proof can ever arrive. Once
     * the engine reports no loaded model, the native session holding this exact attempt is gone, so
     * the attempt's own lease must be released instead of staying fenced for ever.
     */
    @Test
    fun stopSettlesWhenTheEngineReportsNoLoadedModel() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val stopSignal = CompletableDeferred<Unit>()
        val rig = Rig { _, _ ->
            emit(GenerateEvent.Chunk("partial", RuntimeStats(loaded = true)))
            started.complete(Unit)
            stopSignal.await()
            emit(cancelledErrorEvent())
        }
        try {
            rig.proof.proof = AgentStopProof.Unknown("execution proof is stale: native lifecycle transition")
            val request = agentRequest(binding = rig.binding)
            val inference = async { rig.adapter.infer(request) { } }
            withTimeout(3_000) { started.await() }
            assertTrue(rig.adapter.requestStop(request.attempt).accepted)
            // The worker was reclaimed: the engine no longer has a loaded model.
            rig.engine.profile = null
            stopSignal.complete(Unit)
            assertTrue(inference.await() is AgentModelResult.Cancelled)

            assertEquals(AgentLeaseState.RELEASED, rig.retention.snapshot(request.attempt)?.state)
            assertEquals(1, rig.lease.releases)
        } finally {
            rig.close()
        }
    }
}
