package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RuntimeLifecycleChainTest {

    @Test
    fun testRuntimeEpochAdvanceAndIsolation() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        assertEquals(RuntimeEpoch(1L), supervisor.currentEpoch)
        assertTrue(supervisor.isHealthy)

        val id1 = RequestIdentity(requestId = "req-1", runtimeEpoch = 1L)
        supervisor.requestRegistry.register(id1)
        assertTrue(supervisor.requestRegistry.isActive("req-1"))

        // Advance epoch
        val newEpoch = supervisor.advanceEpoch()
        assertEquals(RuntimeEpoch(2L), newEpoch)
        assertEquals(RuntimeEpoch(2L), supervisor.currentEpoch)

        // Previous request was drained during epoch migration
        assertFalse(supervisor.requestRegistry.isActive("req-1"))
        assertTrue(supervisor.requestRegistry.isTerminal("req-1"))

        supervisor.shutdownProcess()
        assertFalse(supervisor.isHealthy)
    }

    @Test
    fun testSessionExclusiveLeaseAndGenerationAdvancement() {
        val session = EngineSessionOwner(sessionId = "sess-alpha", initialGeneration = 1L)
        assertEquals(1L, session.currentGeneration.value)
        assertFalse(session.isLeaseActive)

        val id1 = RequestIdentity(requestId = "req-101", sessionGeneration = 1L)
        val lease1 = session.tryAcquireExecutionLease(id1)
        assertNotNull(lease1)
        assertTrue(session.isLeaseActive)

        // Second concurrent request within same session must fail to acquire lease
        val id2 = RequestIdentity(requestId = "req-102", sessionGeneration = 1L)
        val lease2 = session.tryAcquireExecutionLease(id2)
        assertNull("Concurrent request must not acquire execution lease in same session", lease2)

        lease1?.close()
        assertFalse(session.isLeaseActive)

        // Advance generation clears prefix cache
        session.storePrefixCache("prompt-kv", byteArrayOf(1, 2, 3))
        assertNotNull(session.getPrefixCache("prompt-kv"))

        val nextGen = session.advanceGeneration()
        assertEquals(2L, nextGen.value)
        assertNull(session.getPrefixCache("prompt-kv"))

        session.close()
    }

    @Test
    fun testRequestRegistryExactlyOnceTerminalInvariant() {
        val registry = RequestRegistry()
        val identity = RequestIdentity(requestId = "req-fixed", runtimeEpoch = 1L)
        registry.register(identity)

        assertFalse(registry.markTerminal("req-fixed", RequestState.Succeeded))

        assertTrue(registry.transitionState("req-fixed", RequestState.Accepted))
        assertTrue(registry.transitionState("req-fixed", RequestState.Preparing))
        assertTrue(registry.transitionState("req-fixed", RequestState.Running))

        // First terminal transition succeeds
        val firstTerminal = registry.markTerminal("req-fixed", RequestState.Succeeded)
        assertTrue(firstTerminal)
        assertTrue(registry.isTerminal("req-fixed"))

        // Second terminal transition MUST be rejected
        val duplicateTerminal = registry.markTerminal(
            "req-fixed",
            RequestState.Failed,
            NativeErrorTranslator.translate(101)
        )
        assertFalse("Duplicate terminal transition must be strictly rejected", duplicateTerminal)

        val entry = registry.getEntry("req-fixed")
        assertNotNull(entry)
        assertEquals(RequestState.Succeeded, entry?.stateMachine?.currentState)
        assertNull(entry?.terminalError)
    }

    @Test
    fun testCallbackRouterStaleDetectionAndOrdering() {
        val router = CallbackRouter()
        val identity = RequestIdentity(
            requestId = "req-stream",
            runtimeEpoch = 2L,
            sessionGeneration = 2L,
            nativeSequence = 0L
        )

        val receivedEvents = mutableListOf<CallbackEvent>()
        router.subscribe(identity) { event ->
            receivedEvents.add(event)
        }

        // 1. Valid event
        val ev0 = CallbackEvent.token(
            identity = identity.copy(nativeSequence = 0L),
            sequence = 0L,
            text = "token-0",
            tokenIndex = 0L
        )
        val routed0 = router.routeEvent(ev0, currentRuntimeEpoch = 2L, currentSessionGeneration = 2L)
        assertTrue(routed0)

        // 2. Out-of-order sequence dropped
        val evOldSeq = CallbackEvent.token(
            identity = identity.copy(nativeSequence = 0L),
            sequence = 0L,
            text = "token-dup",
            tokenIndex = 0L
        )
        val routedOldSeq = router.routeEvent(evOldSeq, currentRuntimeEpoch = 2L, currentSessionGeneration = 2L)
        assertFalse(routedOldSeq)

        // 3. Stale epoch dropped
        val evOldEpoch = CallbackEvent.token(
            identity = identity.copy(runtimeEpoch = 1L, nativeSequence = 2L),
            sequence = 2L,
            text = "stale-epoch",
            tokenIndex = 1L
        )
        val routedOldEpoch = router.routeEvent(evOldEpoch, currentRuntimeEpoch = 2L, currentSessionGeneration = 2L)
        assertFalse(routedOldEpoch)

        // 4. Stale generation dropped
        val evOldGen = CallbackEvent.token(
            identity = identity.copy(sessionGeneration = 1L, nativeSequence = 3L),
            sequence = 3L,
            text = "stale-gen",
            tokenIndex = 2L
        )
        val routedOldGen = router.routeEvent(evOldGen, currentRuntimeEpoch = 2L, currentSessionGeneration = 2L)
        assertFalse(routedOldGen)

        // 5. Terminal event exactly-once
        val evTerm = CallbackEvent.terminal(
            identity = identity.copy(nativeSequence = 4L),
            sequence = 4L,
            finalState = RequestState.Succeeded
        )
        assertTrue(router.routeEvent(evTerm, currentRuntimeEpoch = 2L, currentSessionGeneration = 2L))

        // Post-terminal event must be dropped
        val evPostTerm = CallbackEvent.token(
            identity = identity.copy(nativeSequence = 5L),
            sequence = 5L,
            text = "post-term",
            tokenIndex = 3L
        )
        assertFalse(router.routeEvent(evPostTerm, currentRuntimeEpoch = 2L, currentSessionGeneration = 2L))

        assertEquals(2, receivedEvents.size)
        assertTrue(router.droppedStaleCount() >= 4)
    }

    @Test
    fun callbackRouterSerializesConcurrentTerminalDeliveryAndRejectsOldAttempts() {
        val router = CallbackRouter()
        val identity = RequestIdentity(requestId = "req-race", attemptId = 2L, runtimeEpoch = 3L)
        val delivered = AtomicInteger()
        router.subscribe(identity) { delivered.incrementAndGet() }

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1L..32L).map { sequence ->
                pool.submit {
                    start.await()
                    router.routeEvent(
                        CallbackEvent.terminal(identity, sequence, RequestState.Succeeded),
                        currentRuntimeEpoch = 3L,
                        currentSessionGeneration = 0L
                    )
                }
            }
            start.countDown()
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, delivered.get())

        router.subscribe(identity.copy(attemptId = 3L)) { delivered.incrementAndGet() }
        assertFalse(
            router.routeEvent(
                CallbackEvent.token(identity, 33L, "late", 0L),
                currentRuntimeEpoch = 3L,
                currentSessionGeneration = 0L
            )
        )
        assertEquals(1, delivered.get())
    }

    @Test
    fun requestStateMachineRejectsCallbacksFromOtherAttemptsAndFutureIdentities() {
        val identity = RequestIdentity(
            requestId = "req-attempt",
            attemptId = 4L,
            sessionGeneration = 2L,
            runtimeEpoch = 7L,
            workerIncarnation = 3L
        )
        val machine = com.muyuchat.core.engine.contract.RequestStateMachine(identity)
        val oldAttempt = CallbackEvent.token(identity.copy(attemptId = 3L), 1L, "old", 0L)
        val futureEpoch = CallbackEvent.token(identity.copy(runtimeEpoch = 8L), 1L, "future", 0L)

        assertTrue(machine.processEvent(oldAttempt, 7L, 2L, 3L) is
            com.muyuchat.core.engine.contract.EventProcessResult.StaleRejected)
        assertTrue(machine.processEvent(futureEpoch, 7L, 2L, 3L) is
            com.muyuchat.core.engine.contract.EventProcessResult.StaleRejected)
        assertEquals(RequestState.Created, machine.currentState)
    }

    @Test
    fun testCancellationIdempotency() {
        val controller = RequestCancellationController()
        val id = RequestIdentity(requestId = "req-cancel-test")
        controller.registerRequest(id)

        assertFalse(controller.isCancellationRequested("req-cancel-test"))

        val firstCancel = controller.requestCancellation("req-cancel-test", "User abort")
        assertTrue(firstCancel)
        assertTrue(controller.isCancellationRequested("req-cancel-test"))
        assertEquals("User abort", controller.getCancellationReason("req-cancel-test"))

        // Second cancel is safe no-op returning false
        val secondCancel = controller.requestCancellation("req-cancel-test", "Duplicate abort")
        assertFalse(secondCancel)
        assertEquals("User abort", controller.getCancellationReason("req-cancel-test"))

        controller.unregisterRequest("req-cancel-test")
        assertFalse(controller.isCancellationRequested("req-cancel-test"))
    }

    @Test
    fun testUniversalDeviceAdmissionAlwaysAdmitted() {
        val backend = BackendRuntimeOwner(
            backendKind = "QnnHtp",
            epoch = RuntimeEpoch(1L),
            initialAdvisory = HardwareProbeAdvisory(
                chipset = "unknown-chipset-999",
                isSnapdragon = false,
                recommendedBackend = "",
                isGenericFallback = true
            )
        )

        val advisory = backend.hardwareAdvisory
        // Never blocked! Generic path selected
        assertEquals("CPU", advisory.recommendedBackend)
        assertTrue(advisory.isGenericFallback)

        backend.close()
    }
}
