package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.GenieXLlamaCppChatRunner
import com.muyuchat.core.engine.RuntimeMonotonicClock
import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.contract.RuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Behavior protection for the request-risk fixes (plan §6 F2 / audit P1-02, P1-04, P1-05, P1-06,
 * P1-07, P1-08). Every case here is written to observe production behavior of the real
 * implementation, never a copy of it.
 */
class RequestRiskContractTest {

    private class RecordingBackend(
        override val backendKind: String,
        override val epoch: RuntimeEpoch = RuntimeEpoch(1L),
        private val failOnClose: Boolean = false
    ) : BackendPort {
        val closed = AtomicBoolean(false)
        val closeAttempts = AtomicInteger(0)
        override val hardwareAdvisory = HardwareProbeAdvisory()
        override val isInitialized = true

        override fun createEngine(modelPath: String): EnginePort =
            throw UnsupportedOperationException("test backend does not create engines")

        override fun shutdown() {
            close()
        }

        override fun close() {
            closeAttempts.incrementAndGet()
            closed.set(true)
            if (failOnClose) throw IllegalStateException("backend close failed: $backendKind")
        }
    }

    private class FakeMonotonicClock(originMs: Long = 1_000L) : RuntimeMonotonicClock {
        private val now = AtomicLong(originMs)
        override fun nowMs(): Long = now.get()
        fun advanceMs(delta: Long) {
            now.addAndGet(delta)
        }
    }

    // ── session lease (P1-02) ────────────────────────────────────────────────────────────────

    @Test
    fun leaseAcquiredOnOneThreadIsReleasedByCloseOnAnotherThread() {
        val session = EngineSessionOwner(sessionId = "sess-cross-thread", initialGeneration = 1L)
        val acquired = AtomicReference<ExecutionLease?>(null)
        val acquirer = Thread { acquired.set(session.tryAcquireExecutionLease(RequestIdentity("req-a"))) }
        acquirer.start()
        acquirer.join(5_000)

        val lease = requireNotNull(acquired.get()) { "lease must be acquired on the acquirer thread" }
        assertTrue(session.isLeaseActive)

        val closer = Thread { lease.close() }
        closer.start()
        closer.join(5_000)

        assertFalse("a lease closed from another thread must release the session", session.isLeaseActive)
        val reacquired = session.tryAcquireExecutionLease(RequestIdentity("req-b"))
        assertNotNull("the session must be usable again after a cross-thread release", reacquired)
        reacquired?.close()
        session.close()
    }

    @Test
    fun duplicateCloseOfRetiredLeaseCannotReleaseItsSuccessor() {
        val session = EngineSessionOwner(sessionId = "sess-dup-close", initialGeneration = 1L)
        val first = requireNotNull(session.tryAcquireExecutionLease(RequestIdentity("req-1")))
        first.close()

        val second = requireNotNull(session.tryAcquireExecutionLease(RequestIdentity("req-2")))
        first.close()

        assertTrue("a retired lease must not release its successor", session.isLeaseActive)
        assertNull("the successor must still own the session", session.tryAcquireExecutionLease(RequestIdentity("req-3")))

        second.close()
        assertFalse(session.isLeaseActive)
        session.close()
    }

    @Test
    fun concurrentAcquireAndCloseKeepsTheSessionUsable() {
        val session = EngineSessionOwner(sessionId = "sess-race", initialGeneration = 1L)
        val start = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker ->
                pool.submit {
                    try {
                        start.await()
                        repeat(60) { iteration ->
                            val lease = session.tryAcquireExecutionLease(
                                RequestIdentity("req-$worker-$iteration")
                            )
                            if (lease != null) {
                                // Release from a thread other than the acquirer: the lease must not be
                                // thread-affine, and a lost release would make the session unusable.
                                val closer = Thread { lease.close() }
                                closer.start()
                                closer.join(5_000)
                            }
                        }
                    } catch (error: Throwable) {
                        errors.add(error)
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertTrue("lease operations must not fail: $errors", errors.isEmpty())
        assertFalse("every acquired lease must have been released", session.isLeaseActive)
        val finalLease = session.tryAcquireExecutionLease(RequestIdentity("req-final"))
        assertNotNull("the session must be usable after the race", finalLease)
        finalLease?.close()
        session.close()
    }

    @Test
    fun aHeldLeaseRejectsEveryConcurrentAcquirer() {
        val session = EngineSessionOwner(sessionId = "sess-exclusive", initialGeneration = 1L)
        val holder = requireNotNull(session.tryAcquireExecutionLease(RequestIdentity("req-holder")))
        val acquired = AtomicInteger()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val challengers = (0 until 6).map { index ->
                pool.submit {
                    start.await()
                    if (session.tryAcquireExecutionLease(RequestIdentity("req-challenger-$index")) != null) {
                        acquired.incrementAndGet()
                    }
                }
            }
            start.countDown()
            challengers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals("a held lease must exclude every other request", 0, acquired.get())

        holder.close()
        val next = session.tryAcquireExecutionLease(RequestIdentity("req-next"))
        assertNotNull("the session must be free once the holder closes", next)
        next?.close()
        session.close()
    }

    @Test
    fun generationAdvanceDoesNotWaitForTheWholeLeasedRequest() {
        val session = EngineSessionOwner(sessionId = "sess-generation", initialGeneration = 1L)
        val lease = requireNotNull(session.tryAcquireExecutionLease(RequestIdentity("req-gen")))
        val advanced = AtomicBoolean(false)
        val worker = Thread {
            session.advanceGeneration()
            advanced.set(true)
        }
        worker.isDaemon = true
        worker.start()
        worker.join(2_000)

        assertTrue(
            "advanceGeneration must not block until a leased async request finishes",
            advanced.get()
        )
        assertTrue("an unrelated generation advance must keep the active lease", session.isLeaseActive)
        assertEquals(2L, session.currentGeneration.value)

        lease.close()
        assertFalse(session.isLeaseActive)
        session.close()
    }

    // ── admission / epoch / shutdown (P1-05) ─────────────────────────────────────────────────

    @Test
    fun registeringAfterShutdownIsRejectedAndTheBackendIsNotLeaked() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        supervisor.shutdownProcess()

        val late = RecordingBackend("late")
        val failure = runCatching { supervisor.registerBackend("late", late) }.exceptionOrNull()

        assertNotNull("registering into a shut down runtime must fail", failure)
        assertTrue("a rejected backend must be released, not leaked", late.closed.get())
    }

    @Test
    fun replacingABackendOwnerReleasesTheReplacedOne() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val first = RecordingBackend("cpu")
        val second = RecordingBackend("cpu")

        supervisor.registerBackend("cpu", first)
        supervisor.registerBackend("cpu", second)

        assertTrue("a replaced backend owner must be released", first.closed.get())
        assertFalse("the replacement must stay live", second.closed.get())
        assertSame(second, supervisor.getBackend("cpu"))
        supervisor.shutdownProcess()
    }

    @Test
    fun oneFailingBackendCloseStillClosesEveryOtherBackend() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val backends = (0 until 5).map { index ->
            RecordingBackend("kind-$index", failOnClose = true).also { supervisor.registerBackend("kind-$index", it) }
        }

        val failure = runCatching { supervisor.shutdownProcess() }.exceptionOrNull()

        assertEquals(
            "shutdown must attempt every backend close even after a failure",
            backends.size,
            backends.sumOf { it.closeAttempts.get() }
        )
        assertFalse(supervisor.isHealthy)
        assertNotNull("a shutdown failure must surface instead of being swallowed", failure)
    }

    @Test
    fun epochAdvanceDrainsOnlyRequestsFromSupersededEpochs() {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        supervisor.requestRegistry.register(RequestIdentity("req-old", runtimeEpoch = 1L))
        supervisor.requestRegistry.register(RequestIdentity("req-current", runtimeEpoch = 5L))

        supervisor.advanceEpoch()

        assertTrue("requests of the superseded epoch must be drained", supervisor.requestRegistry.isTerminal("req-old"))
        assertFalse(
            "draining must not abort a request that belongs to a newer epoch",
            supervisor.requestRegistry.isTerminal("req-current")
        )
        assertTrue(supervisor.requestRegistry.isActive("req-current"))
        supervisor.shutdownProcess()
    }

    // ── request state (P1-06) ────────────────────────────────────────────────────────────────

    @Test
    fun delayedCleanupOfARetiredAttemptCannotEvictItsReplacement() {
        val registry = RequestRegistry()
        val retired = RequestIdentity("req-reuse", attemptId = 0L)
        registry.register(retired)
        assertTrue(registry.transitionState(retired, RequestState.Accepted))
        assertTrue(registry.markTerminal(retired, RequestState.Failed))
        assertNotNull("a settled attempt may be removed", registry.evictTerminal(retired))

        val replacement = RequestIdentity("req-reuse", attemptId = 1L)
        registry.register(replacement)
        assertTrue(registry.transitionState(replacement, RequestState.Accepted))

        // A delayed callback of the retired attempt must not settle, transition or evict the
        // replacement that reused the same requestId.
        assertFalse(registry.markTerminal(retired, RequestState.Failed))
        assertFalse(registry.transitionState(retired, RequestState.Running))
        assertNull("a retired attempt must not evict the live replacement", registry.evictTerminal(retired))
        assertEquals(1, registry.count())
        assertEquals(replacement, registry.getEntry("req-reuse")?.identity)
        assertEquals(RequestState.Accepted, registry.snapshot("req-reuse")?.state)
    }

    @Test
    fun snapshotIsAReadOnlyViewThatDoesNotChangeUnderTheRegistry() {
        val registry = RequestRegistry()
        val identity = RequestIdentity("req-snapshot", runtimeEpoch = 3L, sessionGeneration = 2L)
        registry.register(identity)
        registry.transitionState(identity, RequestState.Accepted)

        val accepted = requireNotNull(registry.snapshot("req-snapshot"))
        assertEquals(RequestState.Accepted, accepted.state)
        assertFalse(accepted.terminal)
        assertEquals(3L, accepted.registeredAtEpoch)
        assertEquals(2L, accepted.registeredAtGeneration)

        registry.transitionState(identity, RequestState.Running)
        registry.markTerminal(identity, RequestState.Failed, NativeErrorTranslator.translate(101))

        assertEquals("the snapshot must stay a copy of the observed state", RequestState.Accepted, accepted.state)
        assertFalse(accepted.terminal)
        assertNull(accepted.terminalError)

        val settled = requireNotNull(registry.snapshot("req-snapshot"))
        assertEquals(RequestState.Failed, settled.state)
        assertTrue(settled.terminal)
        assertNotNull(settled.terminalError)
        assertNull(registry.snapshot("req-absent"))
    }

    @Test
    fun terminalSettlementAndCleanupSettleExactlyOnce() {
        val registry = RequestRegistry()
        val identity = RequestIdentity("req-settle-once")
        registry.register(identity)
        assertTrue(registry.transitionState(identity, RequestState.Accepted))
        assertTrue(registry.transitionState(identity, RequestState.Preparing))
        assertTrue(registry.transitionState(identity, RequestState.Running))
        val terminals = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val markStart = CountDownLatch(1)
            val markers = (0 until 4).map {
                pool.submit {
                    markStart.await()
                    if (registry.markTerminal(identity, RequestState.Succeeded)) {
                        terminals.incrementAndGet()
                    }
                }
            }
            markStart.countDown()
            markers.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals("the terminal state must be settled exactly once", 1, terminals.get())

            val evictions = AtomicInteger()
            val evictTasks = (0 until 4).map {
                pool.submit {
                    if (registry.evictTerminal(identity) != null) evictions.incrementAndGet()
                }
            }
            evictTasks.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals("cleanup must settle exactly once", 1, evictions.get())
            assertEquals(0, registry.count())
        } finally {
            pool.shutdownNow()
        }
    }

    // ── cancellation (P1-07) ─────────────────────────────────────────────────────────────────

    @Test
    fun delayedUnregisterCannotDropTheReplacementAttemptRecord() {
        val controller = RequestCancellationController()
        val retired = RequestIdentity("req-cancel-reuse", attemptId = 0L)
        controller.registerRequest(retired)
        assertTrue(controller.requestCancellation("req-cancel-reuse", "first attempt abort"))
        controller.unregisterRequest(retired)

        val replacement = RequestIdentity("req-cancel-reuse", attemptId = 1L)
        controller.registerRequest(replacement)
        controller.unregisterRequest(retired)

        assertTrue(
            "a delayed cleanup must not drop the replacement attempt cancel record",
            controller.requestCancellation("req-cancel-reuse", "second attempt abort")
        )
        assertEquals(replacement, controller.cancelRecord("req-cancel-reuse")?.identity)
    }

    @Test
    fun requestTimeoutFollowsAMonotonicClockNotTheWallClock() {
        val clock = FakeMonotonicClock()
        val controller = RequestCancellationController(clock)
        controller.registerRequest(RequestIdentity("req-timeout"))

        assertFalse(controller.isTimedOut("req-timeout", 5_000L))
        clock.advanceMs(10_000L)

        assertTrue("elapsed monotonic time must drive the deadline", controller.isTimedOut("req-timeout", 5_000L))
        assertFalse(controller.isTimedOut("req-timeout", 20_000L))
        assertFalse("a non-positive timeout never times out", controller.isTimedOut("req-timeout", 0L))
    }

    @Test
    fun cancelReasonIsNeverObservableWithoutItsFlagOrBlank() {
        val controller = RequestCancellationController()
        controller.registerRequest(RequestIdentity("req-cancel-atomic"))
        val violations = AtomicInteger()
        val running = AtomicBoolean(true)
        val readers = (0 until 3).map { index ->
            Thread({
                while (running.get()) {
                    val record = controller.cancelRecord("req-cancel-atomic") ?: continue
                    if (record.requested && record.reason.isBlank()) violations.incrementAndGet()
                }
            }, "cancel-reader-$index").apply { isDaemon = true; start() }
        }
        try {
            repeat(4_000) { controller.requestCancellation("req-cancel-atomic", "atomic cancel reason") }
        } finally {
            running.set(false)
        }
        readers.forEach { it.join(2_000) }

        assertEquals("a requested cancellation must always carry its reason", 0, violations.get())
        val record = requireNotNull(controller.cancelRecord("req-cancel-atomic"))
        assertTrue(record.requested)
        assertEquals("atomic cancel reason", record.reason)
    }

    // ── callback routing (P1-08) ─────────────────────────────────────────────────────────────

    @Test
    fun terminalIsRecordedEvenWhenTheConsumerThrows() {
        val router = CallbackRouter()
        val identity = RequestIdentity("req-terminal-failure", runtimeEpoch = 1L)
        router.subscribe(identity) { throw IllegalStateException("consumer exploded") }

        val accepted = router.routeEvent(
            CallbackEvent.terminal(identity, 1L, RequestState.Failed),
            currentRuntimeEpoch = 1L,
            currentSessionGeneration = 0L
        )

        assertTrue("the terminal must be committed even when delivery fails", accepted)
        val record = requireNotNull(router.terminalRecord(identity.requestId)) {
            "a failed delivery must still leave a queryable terminal record"
        }
        assertEquals(RequestState.Failed, record.finalState)
        assertFalse("delivery failure must be distinguishable from a delivered terminal", record.delivered)
        assertNotNull(record.failure)
        assertEquals(1L, router.callbackFailureCount())

        assertFalse(
            "post-terminal events must still be dropped",
            router.routeEvent(
                CallbackEvent.token(identity, 2L, "late", 0L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        )
        assertEquals(1L, router.callbackFailureCount())
    }

    @Test
    fun terminalRecordSeparatesNotReachedFromDelivered() {
        val router = CallbackRouter()
        assertNull(router.terminalRecord("req-never-seen"))

        val identity = RequestIdentity("req-terminal-ok", runtimeEpoch = 1L)
        val received = mutableListOf<String>()
        router.subscribe(identity) { event -> received.add(event.eventType.name) }
        assertTrue(
            router.routeEvent(
                CallbackEvent.terminal(identity, 1L, RequestState.Succeeded),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        )

        val record = requireNotNull(router.terminalRecord(identity.requestId))
        assertEquals(RequestState.Succeeded, record.finalState)
        assertTrue(record.delivered)
        assertNull(record.failure)
        assertEquals(1, received.size)
    }

    @Test
    fun slowConsumerDoesNotBlockUnrelatedRoutesOrUnsubscribe() {
        val router = CallbackRouter()
        val release = CountDownLatch(1)
        val slow = RequestIdentity("req-slow", runtimeEpoch = 1L)
        val other = RequestIdentity("req-other", runtimeEpoch = 1L)
        val otherDelivered = CountDownLatch(1)
        router.subscribe(slow) { release.await(10, TimeUnit.SECONDS) }
        router.subscribe(other) { otherDelivered.countDown() }

        val slowThread = Thread {
            router.routeEvent(
                CallbackEvent.token(slow, 1L, "slow", 0L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        }
        slowThread.isDaemon = true
        slowThread.start()
        Thread.sleep(150)

        val fastThread = Thread {
            router.routeEvent(
                CallbackEvent.token(other, 1L, "fast", 0L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        }
        fastThread.isDaemon = true
        fastThread.start()
        assertTrue(
            "a slow consumer for one request must not stall unrelated requests",
            otherDelivered.await(3, TimeUnit.SECONDS)
        )

        val unsubscribed = CountDownLatch(1)
        Thread {
            router.unsubscribe(slow)
            unsubscribed.countDown()
        }.start()
        assertTrue("unsubscribe must not wait for a slow consumer", unsubscribed.await(3, TimeUnit.SECONDS))

        release.countDown()
        slowThread.join(5_000)
        fastThread.join(5_000)
    }

    @Test
    fun consumerReenteringTheRouterDoesNotDeadlock() {
        val router = CallbackRouter()
        val outer = RequestIdentity("req-reentrant-outer", runtimeEpoch = 1L)
        val inner = RequestIdentity("req-reentrant-inner", runtimeEpoch = 1L)
        val innerDelivered = CountDownLatch(1)
        router.subscribe(outer) {
            router.routeEvent(
                CallbackEvent.token(inner, 1L, "inner", 0L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        }
        router.subscribe(inner) { innerDelivered.countDown() }

        assertTrue(
            router.routeEvent(
                CallbackEvent.token(outer, 1L, "outer", 0L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        )
        assertTrue("a re-entrant consumer must not deadlock the router", innerDelivered.await(3, TimeUnit.SECONDS))
    }

    // ── unwired skeleton must not fake a load (P1-04) ────────────────────────────────────────

    @Test
    fun skeletonEngineIdentityDoesNotCollideForSameBasenameAndTimestamp() {
        val fixedNow = 1_700_000_000_000L
        val backend = BackendRuntimeOwner(
            backendKind = "SkeletonCpu",
            epoch = RuntimeEpoch(1L),
            clock = { fixedNow }
        )
        val root = File(System.getProperty("java.io.tmpdir"), "mca-request-risk-${System.nanoTime()}")
        val firstModel = File(root, "a/model.bin").apply { parentFile?.mkdirs(); writeText("a") }
        val secondModel = File(root, "b/model.bin").apply { parentFile?.mkdirs(); writeText("b") }
        try {
            val first = backend.createEngine(firstModel.path)
            val second = backend.createEngine(secondModel.path)
            assertNotEquals(
                "engine identity must not collide for the same basename in the same millisecond",
                first.engineId,
                second.engineId
            )
            val firstSession = first.createSession("s1")
            val secondSession = second.createSession("s2")
            val firstOwner = requireNotNull(firstSession as? EngineSessionOwner) {
                "the skeleton engine must own a real EngineSessionOwner"
            }
            val secondOwner = requireNotNull(secondSession as? EngineSessionOwner) {
                "the skeleton engine must own a real EngineSessionOwner"
            }
            firstOwner.storePrefixCache("k", byteArrayOf(1))
            secondOwner.storePrefixCache("k", byteArrayOf(1))

            backend.close()

            assertNull("a collided engine must not stay alive after close", firstOwner.getPrefixCache("k"))
            assertNull("a collided engine must not stay alive after close", secondOwner.getPrefixCache("k"))
            assertFalse(first.isReady)
            assertFalse(second.isReady)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unwiredBackendNeverReportsAReadyEngine() {
        val backend = BackendRuntimeOwner(backendKind = "SkeletonCpu", epoch = RuntimeEpoch(1L))
        val model = File.createTempFile("mca-unwired-skeleton", ".bin")
        try {
            val engine = backend.createEngine(model.path)
            assertFalse(
                "an unwired skeleton must not claim a model was loaded",
                engine.isReady
            )
            val session = engine.createSession("skeleton-session")
            val sessionOwner = requireNotNull(session as? EngineSessionOwner) {
                "the skeleton engine must own a real EngineSessionOwner"
            }
            sessionOwner.storePrefixCache("prompt-kv", byteArrayOf(1, 2, 3))
            assertNotNull(sessionOwner.getPrefixCache("prompt-kv"))

            engine.close()

            assertNull("engine close must release its session resources", sessionOwner.getPrefixCache("prompt-kv"))
            assertFalse(engine.isReady)
        } finally {
            model.delete()
        }
    }

    @Test
    fun wiredGenieXRouterSurfacesANativeDelegateUnloadFailure() {
        // The GenieX router is the wired production chat path for MNN/GGUF selections: it must not
        // turn a failed native unload into an apparent clean unload, because the next load would then
        // create a second engine over a native owner that was never released.
        val runner = GenieXLlamaCppChatRunner()
        val model = File.createTempFile("mca-geniex-unload", ".gguf")
        try {
            val loadCode = runner.loadModel(model.path, """{"geniex_compute_unit":"cpu"}""")
            assertTrue(
                "a load without the packaged native library must not report success",
                loadCode != 0
            )

            runner.unloadModel()

            val stats = JSONObject(runner.getRuntimeStatsJson())
            assertTrue(
                "a failed native unload must be reported through lastError, not swallowed",
                stats.optString("lastError").isNotBlank()
            )
        } finally {
            model.delete()
        }
    }

    // ── REV-01..04 independent-review regression protection ─────────────────────────────────

    @Test
    fun registryEntryViewExposesNoStateMachineMutation() {
        val registry = RequestRegistry()
        val identity = RequestIdentity("rev01-view")
        registry.register(identity)

        val view = registry.getEntry("rev01-view")!!.stateMachine
        val mutators = view.javaClass.methods.map { it.name }.filter {
            it in setOf("transitionTo", "abort", "requestCancel", "beginDraining", "completeCancel", "timeout")
        }
        assertTrue(
            "the read-only state view must not expose a mutation path, found $mutators",
            mutators.isEmpty()
        )
        // The review repro drove `getEntry(...).stateMachine.abort()`; after the fix the registry is
        // the only state committer, so the request stays exactly as registered.
        assertEquals(RequestState.Created, registry.snapshot("rev01-view")?.state)
        assertTrue(registry.isActive("rev01-view"))
        assertFalse(registry.isTerminal("rev01-view"))
        assertNull(registry.getEntry("rev01-view")!!.terminalError)
    }

    @Test
    fun snapshotStaysConsistentWithConcurrentTerminalSettlement() {
        val registry = RequestRegistry()
        val identity = RequestIdentity("rev01-consistent")
        registry.register(identity)
        registry.transitionState(identity, RequestState.Accepted)
        registry.transitionState(identity, RequestState.Preparing)
        registry.transitionState(identity, RequestState.Running)
        val error = NativeErrorTranslator.translate(101)
        val settler = Thread { registry.markTerminal(identity, RequestState.Failed, error) }
        settler.start()

        repeat(2_000) {
            val snapshot = registry.snapshot("rev01-consistent") ?: return@repeat
            assertEquals(
                "terminal flag and state must be committed under one monitor",
                snapshot.state.isTerminal,
                snapshot.terminal
            )
            if (snapshot.terminal && snapshot.state == RequestState.Failed) {
                assertNotNull("a settled failure must carry its error envelope", snapshot.terminalError)
            }
        }
        settler.join(5_000)
        assertFalse("the settler thread must finish", settler.isAlive)
    }

    @Test
    fun activeRequestCannotBeRemovedWithoutSettlement() {
        val registry = RequestRegistry()
        val identity = RequestIdentity("rev02-active")
        registry.register(identity)
        registry.transitionState(identity, RequestState.Accepted)

        assertNull("an active request must not be evictable", registry.evictTerminal(identity))
        assertEquals(1, registry.count())
        assertTrue(registry.isActive("rev02-active"))

        val removed = requireNotNull(registry.settleAndRemove(identity, RequestState.Aborted))
        assertEquals(RequestState.Aborted, removed.state)
        assertTrue(removed.terminal)
        assertEquals(0, registry.count())
        assertFalse(registry.isActive("rev02-active"))
    }

    @Test
    fun callbackDeliveryOrderFollowsAcceptanceOrderForReentrantAndConcurrentProducers() {
        val router = CallbackRouter()
        val identity = RequestIdentity("rev03-order", runtimeEpoch = 1L)
        val observed = Collections.synchronizedList(mutableListOf<Long>())
        val firstEntered = CountDownLatch(1)
        val allowNested = CountDownLatch(1)
        val seq1Accepted = CountDownLatch(1)
        router.subscribe(identity) { event ->
            observed.add(event.sequence)
            if (event.sequence == 0L) {
                firstEntered.countDown()
                assertTrue(allowNested.await(5, TimeUnit.SECONDS))
                // Re-entrant event: it must queue behind seq1, which was accepted while we were
                // inside the consumer of seq0.
                router.routeEvent(
                    CallbackEvent.token(identity, 2L, "t2", 2L),
                    currentRuntimeEpoch = 1L,
                    currentSessionGeneration = 0L
                )
            }
        }

        val seq0 = Thread {
            router.routeEvent(
                CallbackEvent.token(identity, 0L, "t0", 0L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        }
        seq0.start()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        val seq1 = Thread {
            router.routeEvent(
                CallbackEvent.token(identity, 1L, "t1", 1L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
            seq1Accepted.countDown()
        }
        seq1.start()
        assertTrue(seq1Accepted.await(5, TimeUnit.SECONDS))
        allowNested.countDown()
        seq0.join(5_000)
        seq1.join(5_000)

        assertFalse("the delivery threads must finish", seq0.isAlive || seq1.isAlive)
        assertEquals(
            "accepted order must be delivery order, including a re-entrant event",
            listOf(0L, 1L, 2L),
            observed.toList()
        )
    }

    @Test
    fun concurrentCallbackProducersAreDeliveredOnceAndInMonotonicOrder() {
        val router = CallbackRouter()
        val identity = RequestIdentity("rev03-concurrent", runtimeEpoch = 1L)
        val observed = Collections.synchronizedList(mutableListOf<Long>())
        router.subscribe(identity) { event -> observed.add(event.sequence) }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0L until 8L).map { sequence ->
                pool.submit {
                    start.await()
                    router.routeEvent(
                        CallbackEvent.token(identity, sequence, "t$sequence", sequence),
                        currentRuntimeEpoch = 1L,
                        currentSessionGeneration = 0L
                    )
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        val delivered = observed.toList()
        assertEquals("no duplicate delivery", delivered.distinct().size, delivered.size)
        assertEquals("accepted events must not be reordered", delivered.sorted(), delivered)
    }

    @Test
    fun terminalRecordOfARetiredAttemptCannotRewriteItsSuccessor() {
        val router = CallbackRouter()
        val retired = RequestIdentity("rev04-terminal", attemptId = 0L, runtimeEpoch = 1L)
        val successor = RequestIdentity("rev04-terminal", attemptId = 1L, runtimeEpoch = 1L)
        val retiredInside = CountDownLatch(1)
        val releaseRetired = CountDownLatch(1)
        router.subscribe(retired) {
            retiredInside.countDown()
            assertTrue(releaseRetired.await(5, TimeUnit.SECONDS))
        }
        val retiredThread = Thread {
            router.routeEvent(
                CallbackEvent.terminal(retired, 1L, RequestState.Succeeded),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        }
        retiredThread.start()
        assertTrue(retiredInside.await(5, TimeUnit.SECONDS))

        router.subscribe(successor) { }
        router.routeEvent(
            CallbackEvent.terminal(successor, 1L, RequestState.Succeeded),
            currentRuntimeEpoch = 1L,
            currentSessionGeneration = 0L
        )
        assertEquals(1L, requireNotNull(router.terminalRecord("rev04-terminal")).identity.attemptId)

        releaseRetired.countDown()
        retiredThread.join(5_000)
        assertFalse("the retired consumer thread must finish", retiredThread.isAlive)
        val after = requireNotNull(router.terminalRecord("rev04-terminal"))
        assertEquals(
            "a late completion of the retired attempt must not rewrite the successor record",
            1L,
            after.identity.attemptId
        )
        assertTrue(after.delivered)
        assertEquals(1L, requireNotNull(router.terminalRecord(successor)).identity.attemptId)
        val retiredRecord = router.terminalRecord(retired)
        assertTrue(
            "the retired attempt may only keep its own record",
            retiredRecord == null || retiredRecord.identity.attemptId == 0L
        )
    }

    @Test
    fun terminalRecordsAreBoundedAndStaleUnsubscribeCannotTearDownTheReplacement() {
        val router = CallbackRouter()
        repeat(400) { index ->
            val identity = RequestIdentity("rev04-bounded-$index", runtimeEpoch = 1L)
            router.subscribe(identity) { }
            router.routeEvent(
                CallbackEvent.terminal(identity, 1L, RequestState.Succeeded),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        }
        val retained = router.terminalRecordCount()
        assertTrue("terminal records must be bounded, retained=$retained", retained in 1..128)
        assertNotNull(
            "the newest terminal record must survive the archive trim",
            router.terminalRecord(RequestIdentity("rev04-bounded-399", runtimeEpoch = 1L))
        )

        val old = RequestIdentity("rev04-unsub", attemptId = 0L, runtimeEpoch = 1L)
        val replacement = RequestIdentity("rev04-unsub", attemptId = 1L, runtimeEpoch = 1L)
        router.subscribe(old) { }
        router.subscribe(replacement) { }
        router.unsubscribe(old)
        assertTrue(
            "a stale unsubscribe must not remove the replacement subscription",
            router.routeEvent(
                CallbackEvent.token(replacement, 1L, "keep", 1L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        )
        router.unsubscribe(replacement)
        assertFalse(
            "the owning attempt can remove its own subscription",
            router.routeEvent(
                CallbackEvent.token(replacement, 2L, "gone", 2L),
                currentRuntimeEpoch = 1L,
                currentSessionGeneration = 0L
            )
        )
    }
}
