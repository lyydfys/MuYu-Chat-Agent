package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.CallbackEventType
import com.muyuchat.core.engine.contract.CallbackSource
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import com.muyuchat.core.engine.contract.TokenPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * R1 protection tests for the request lifecycle transaction of the runtime owner.
 *
 * Every test pins one observable contract of `RuntimeSupervisor` / `CallbackRouter` /
 * `BackendRuntimeOwner` / `EngineSessionOwner`:
 *
 * * a terminal outcome recorded before `clear()` must never be re-inserted after it (Q03);
 * * shutdown must leave zero cancel handles, zero live registry entries and zero terminal records
 *   (Q04/Q07);
 * * admission plus cancel registration plus callback subscription is one transaction, so a racing
 *   shutdown can never leave a route or a cancel handle behind (Q02);
 * * a completed request releases its own cancel handle, route and live entry while keeping a bounded
 *   queryable outcome (Q04/Q07);
 * * an engine must not silently replace (and leak) a live session with the same id (Q06);
 * * a closed session never hands out an execution lease, and close is idempotent (Q04).
 */
class RuntimeAdmissionTransactionTest {

    private val noopConsumer = EventConsumer { }

    private fun tokenEvent(identity: RequestIdentity, sequence: Long = 0L): CallbackEvent =
        CallbackEvent(
            identity = identity,
            eventType = CallbackEventType.Token,
            sequence = sequence,
            monotonicTimeMs = sequence,
            payload = TokenPayload("t$sequence", sequence),
            isTerminal = false,
            source = CallbackSource.Engine
        )

    private fun terminalEvent(identity: RequestIdentity, sequence: Long = 10L): CallbackEvent =
        CallbackEvent.terminal(
            identity = identity,
            sequence = sequence,
            finalState = RequestState.Succeeded,
            monotonicTimeMs = sequence,
            source = CallbackSource.Engine
        )

    // ── Q03: a terminal that was in flight during clear() must not come back ─────────────────────

    @Test
    fun inFlightTerminalIsNotReinsertedAfterTheRouterIsCleared() {
        val router = CallbackRouter()
        val identity = RequestIdentity(requestId = "clear-race", attemptId = 1L, runtimeEpoch = 1L)
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)

        router.subscribe(identity) { event ->
            if (event.isTerminal) {
                consumerEntered.countDown()
                releaseConsumer.await(5, TimeUnit.SECONDS)
            }
        }

        val producer = Thread {
            router.routeEvent(terminalEvent(identity), currentRuntimeEpoch = 1L, currentSessionGeneration = 0L)
        }
        producer.start()

        assertTrue("the terminal must reach the consumer", consumerEntered.await(5, TimeUnit.SECONDS))
        // The record already exists at this point (published before delivery). clear() is what
        // shutdown does, and the in-flight completion must not put it back.
        assertNotNull("the pending outcome must be recorded before delivery", router.terminalRecord(identity))
        router.clear()
        releaseConsumer.countDown()
        producer.join(5_000)

        assertEquals(
            "a terminal completion that was in flight during clear() must not re-insert its record",
            0,
            router.terminalRecordCount()
        )
        assertNull("no outcome may be resurrected after clear()", router.terminalRecord(identity))
    }

    @Test
    fun terminalAcceptedBeforeClearIsDeliveredButNotArchivedAfterwards() {
        val router = CallbackRouter()
        val identity = RequestIdentity(requestId = "clear-race-2", attemptId = 3L, runtimeEpoch = 1L)
        val delivered = AtomicInteger(0)
        val consumerEntered = CountDownLatch(1)
        val releaseConsumer = CountDownLatch(1)

        router.subscribe(identity) { event ->
            if (event.isTerminal) {
                consumerEntered.countDown()
                releaseConsumer.await(5, TimeUnit.SECONDS)
                delivered.incrementAndGet()
            }
        }

        val producer = Thread {
            router.routeEvent(terminalEvent(identity), currentRuntimeEpoch = 1L, currentSessionGeneration = 0L)
        }
        producer.start()
        assertTrue(consumerEntered.await(5, TimeUnit.SECONDS))
        router.clear()
        releaseConsumer.countDown()
        producer.join(5_000)

        assertEquals("the already-accepted delivery still completes exactly once", 1, delivered.get())
        assertEquals("but nothing is archived after clear()", 0, router.terminalRecordCount())
        assertNull(router.terminalRecord("clear-race-2"))
    }

    // ── Q04/Q07: shutdown releases every per-request resource ────────────────────────────────────

    @Test
    fun shutdownReleasesCancelHandlesLiveEntriesAndTerminalRecords() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val entry = supervisor.admitRequest("shutdown-resources", noopConsumer)
        assertNotNull("the request must be admitted before shutdown", entry)
        assertEquals(
            "admission already reserved the cancel handle",
            1,
            supervisor.cancellationController.activeCount()
        )

        supervisor.shutdownProcess()

        assertEquals(
            "shutdown must clear every cancel handle",
            0,
            supervisor.cancellationController.activeCount()
        )
        assertEquals(
            "shutdown must clear the live request registry",
            0,
            supervisor.requestRegistry.count()
        )
        assertEquals(
            "shutdown must clear the terminal archive",
            0,
            supervisor.callbackRouter.terminalRecordCount()
        )
        assertEquals(
            "health must not report a live request after shutdown",
            0,
            supervisor.evaluateHealth().activeRequestCount
        )
        assertFalse("shutdown is not healthy", supervisor.isHealthy)
    }

    @Test
    fun shutdownIsIdempotentAndKeepsEveryResourceAtZero() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        supervisor.admitRequest("shutdown-idempotent", noopConsumer)
        supervisor.shutdownProcess()
        supervisor.shutdownProcess()
        supervisor.close()

        assertEquals(0, supervisor.cancellationController.activeCount())
        assertEquals(0, supervisor.requestRegistry.count())
        assertEquals(0, supervisor.callbackRouter.terminalRecordCount())
        assertNull("a shut-down runtime must reject new work", supervisor.admitRequest("after-shutdown"))
    }

    // ── Q02: admission + cancel registration + route subscription is one transaction ─────────────

    @Test
    fun concurrentAdmissionAndShutdownLeaveNoResidualRouteOrCancelHandle() {
        val iterations = 400
        repeat(iterations) { index ->
            val supervisor = RuntimeSupervisor(initialEpoch = 1L)
            val barrier = CyclicBarrier(2)
            val admitted = AtomicReference<RequestIdentity?>(null)
            val requestId = "admit-race-$index"

            val dispatcher = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                val entry = supervisor.admitRequest(requestId, noopConsumer) ?: return@Thread
                admitted.set(entry.identity)
            }
            val closer = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                supervisor.shutdownProcess()
            }
            dispatcher.start()
            closer.start()
            dispatcher.join(5_000)
            closer.join(5_000)

            assertEquals(
                "iteration $index: a cancel handle registered by admission must not outlive shutdown",
                0,
                supervisor.cancellationController.activeCount()
            )
            assertEquals(
                "iteration $index: a request admitted before shutdown must not stay live after it",
                0,
                supervisor.requestRegistry.count()
            )
            assertEquals(
                "iteration $index: no terminal record may survive shutdown",
                0,
                supervisor.callbackRouter.terminalRecordCount()
            )
            admitted.get()?.let { identity ->
                assertFalse(
                    "iteration $index: no event may be routable after shutdown",
                    supervisor.callbackRouter.routeEvent(
                        tokenEvent(identity, sequence = 1L),
                        currentRuntimeEpoch = identity.runtimeEpoch,
                        currentSessionGeneration = identity.sessionGeneration
                    )
                )
            }
        }
    }

    @Test
    fun aDuplicateRequestIdRollsBackEveryReservationItMade() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val first = supervisor.admitRequest("duplicate-rollback", noopConsumer)
        assertNotNull(first)

        val failure = runCatching { supervisor.admitRequest("duplicate-rollback", noopConsumer) }
            .exceptionOrNull()
        assertTrue(
            "a duplicate requestId must surface the registry conflict",
            failure is IllegalStateException
        )

        assertEquals("exactly one cancel handle may remain", 1, supervisor.cancellationController.activeCount())
        assertEquals("exactly one live entry may remain", 1, supervisor.requestRegistry.count())
        assertTrue(
            "the accepted attempt must still be routable",
            supervisor.callbackRouter.routeEvent(
                tokenEvent(first!!.identity, sequence = 1L),
                currentRuntimeEpoch = first.identity.runtimeEpoch,
                currentSessionGeneration = first.identity.sessionGeneration
            )
        )
    }

    @Test
    fun anAdmittedRequestOwnsItsCancelHandleAndRouteBeforeAdmissionReturns() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val entry = supervisor.admitRequest("atomic-reservations", noopConsumer)!!

        assertEquals(
            "the cancel handle must exist the moment admission returns",
            1,
            supervisor.cancellationController.activeCount()
        )
        assertTrue(
            "the route must exist the moment admission returns",
            supervisor.callbackRouter.routeEvent(
                tokenEvent(entry.identity, sequence = 0L),
                currentRuntimeEpoch = entry.identity.runtimeEpoch,
                currentSessionGeneration = entry.identity.sessionGeneration
            )
        )
    }

    // ── Q04/Q07: terminal settlement cleans up its own resources, exactly once ───────────────────

    @Test
    fun settledRequestReleasesItsOwnCancelRouteAndLiveEntryButKeepsAQueryableOutcome() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val entry = supervisor.admitRequest("settle-cleanup", noopConsumer)!!
        val identity = entry.identity
        assertTrue(supervisor.requestRegistry.transitionState(identity, RequestState.Preparing))
        assertTrue(supervisor.requestRegistry.transitionState(identity, RequestState.Running))

        assertTrue(
            supervisor.callbackRouter.routeEvent(
                terminalEvent(identity),
                currentRuntimeEpoch = identity.runtimeEpoch,
                currentSessionGeneration = identity.sessionGeneration
            )
        )
        assertTrue("the terminal must settle exactly once", supervisor.settleRequest(identity, RequestState.Succeeded))
        assertFalse(
            "a second settlement is not a second terminal",
            supervisor.settleRequest(identity, RequestState.Succeeded)
        )

        assertEquals(0, supervisor.cancellationController.activeCount())
        assertEquals("the live entry is evicted after settlement", 0, supervisor.requestRegistry.count())
        assertFalse("the request is no longer active", supervisor.requestRegistry.isActive("settle-cleanup"))

        val outcome = supervisor.callbackRouter.terminalRecord("settle-cleanup")
        assertNotNull("the bounded outcome must stay queryable after cleanup", outcome)
        assertEquals(RequestState.Succeeded, outcome!!.finalState)
        assertTrue("the terminal was delivered to the consumer", outcome.delivered)
        assertEquals(0, supervisor.evaluateHealth().activeRequestCount)
    }

    @Test
    fun lateCleanupOfARetiredAttemptCannotReleaseItsSuccessor() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val first = supervisor.admitRequest("reuse-id", noopConsumer)!!
        assertTrue(supervisor.settleRequest(first.identity, RequestState.Aborted))

        val second = supervisor.admitRequest("reuse-id", noopConsumer)!!
        assertEquals(second.identity, supervisor.requestRegistry.getEntry("reuse-id")?.identity)

        // The retired attempt's late cleanup must be a no-op for the live successor.
        assertFalse(supervisor.settleRequest(first.identity, RequestState.Aborted))
        assertEquals("the successor keeps its live entry", 1, supervisor.requestRegistry.count())
        assertEquals("the successor keeps its cancel handle", 1, supervisor.cancellationController.activeCount())
        assertNull(
            "the successor's outcome slot was not written by the retired attempt",
            supervisor.callbackRouter.terminalRecord(first.identity)
        )
        assertEquals(second.identity, supervisor.requestRegistry.getEntry("reuse-id")?.identity)
    }

    @Test
    fun shutdownRejectedAdmissionLeavesNoReservationForTheCallerToCleanUp() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        supervisor.shutdownProcess()
        assertNull("a shut-down runtime rejects admission", supervisor.admitRequest("post-shutdown", noopConsumer))
        assertEquals(0, supervisor.cancellationController.activeCount())
        assertEquals(0, supervisor.requestRegistry.count())
        assertEquals(0, supervisor.callbackRouter.terminalRecordCount())
    }

    // ── Q06: engine session identity is not silently replaced ────────────────────────────────────

    @Test
    fun engineRejectsDuplicateSessionIdInsteadOfReplacingTheLiveSession() {
        val backend = BackendRuntimeOwner(backendKind = "GenericCpu", epoch = RuntimeEpoch(1L))
        val engine = backend.createEngine("/models/duplicate-session.bin")
        val first = engine.createSession("session-dup")

        val failure = runCatching { engine.createSession("session-dup") }.exceptionOrNull()
        assertTrue(
            "a second session with the same id must be rejected, not silently replace the first",
            failure is IllegalStateException
        )

        // Leak evidence: with a silent replacement the first session is no longer reachable from the
        // engine map, so unload() can never close it.
        engine.unload()
        assertNull(
            "unload() must have closed the session registered under that id",
            first.tryAcquireExecutionLease(RequestIdentity("s", attemptId = 1L))
        )
    }

    @Test
    fun engineUnloadClosesEverySessionAndSecondUnloadIsSafe() {
        val backend = BackendRuntimeOwner(backendKind = "GenericCpu", epoch = RuntimeEpoch(1L))
        val engine = backend.createEngine("/models/unload-twice.bin")
        val session = engine.createSession("only-session")
        engine.unload()
        engine.unload()
        engine.close()
        assertNull(
            "a closed session must not hand out a lease",
            session.tryAcquireExecutionLease(RequestIdentity("s", attemptId = 1L))
        )
    }

    // ── Q04: session close versus lease acquire ─────────────────────────────────────────────────

    @Test
    fun closedSessionNeverHandsOutALeaseAndCloseIsIdempotent() {
        val session = EngineSessionOwner(sessionId = "close-lease")
        val lease = session.tryAcquireExecutionLease(RequestIdentity("a", attemptId = 1L))
        assertNotNull(lease)
        lease!!.close()
        lease.close()

        session.close()
        session.close()
        session.close()

        assertNull(session.tryAcquireExecutionLease(RequestIdentity("b", attemptId = 2L)))
        assertFalse(session.isLeaseActive)
    }

    @Test
    fun racingCloseAndAcquireNeverLeavesAnOrphanLease() {
        val iterations = 2_000
        repeat(iterations) { index ->
            val session = EngineSessionOwner(sessionId = "race-$index")
            val barrier = CyclicBarrier(2)
            val acquired = AtomicReference<ExecutionLease?>(null)

            val acquirer = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                acquired.set(session.tryAcquireExecutionLease(RequestIdentity("a", attemptId = 1L)))
            }
            val closer = Thread {
                barrier.await(5, TimeUnit.SECONDS)
                session.close()
            }
            acquirer.start()
            closer.start()
            acquirer.join(5_000)
            closer.join(5_000)

            acquired.get()?.close()
            assertFalse("iteration $index: no lease may stay active after it is closed", session.isLeaseActive)
            assertNull(
                "iteration $index: a closed session must refuse every later acquisition",
                session.tryAcquireExecutionLease(RequestIdentity("b", attemptId = 2L))
            )
        }
    }

    // ── Q05: prefix cache is generation and close checked ───────────────────────────────────────

    @Test
    fun prefixCacheFromASupersededGenerationIsRefusedAndCleared() {
        val session = EngineSessionOwner(sessionId = "cache-gen")
        val lease = session.tryAcquireExecutionLease(RequestIdentity("a", attemptId = 1L))!!
        assertTrue(session.storePrefixCache("k", byteArrayOf(1, 2, 3)))
        assertNotNull(session.getPrefixCache("k"))

        session.advanceGeneration()
        assertNull("an entry of a superseded generation must not be readable", session.getPrefixCache("k"))
        assertFalse(
            "a writer whose lease belongs to a superseded generation must be refused",
            session.storePrefixCache("k2", byteArrayOf(9))
        )

        lease.close()
        session.close()
        assertFalse("a closed session must refuse cache writes", session.storePrefixCache("k3", byteArrayOf(7)))
        assertNull("a closed session must refuse cache reads", session.getPrefixCache("k"))
    }
}
