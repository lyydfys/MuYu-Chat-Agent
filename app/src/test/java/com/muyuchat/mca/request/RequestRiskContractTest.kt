package com.muyuchat.mca.request

import com.muyuchat.core.engine.contract.CallbackEvent
import com.muyuchat.core.engine.contract.RequestState
import com.muyuchat.core.engine.runtime.RuntimeSupervisor
import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.GenerationForegroundLease
import com.muyuchat.mca.GenerationForegroundLeaseRegistry
import com.muyuchat.mca.ImageWorkerStrategy
import com.muyuchat.mca.LocalImageTaskMode
import com.muyuchat.mca.ParentDirectorySyncer
import com.muyuchat.mca.SdxlTwoPhaseRequestLease
import com.muyuchat.mca.adapter.LocalApiFacadeAdapter
import com.muyuchat.mca.adapter.LocalApiResponse
import com.muyuchat.mca.adapter.SseStreamOutcome
import com.muyuchat.mca.adapter.SseStreamOverflowException
import com.muyuchat.mca.facade.ChatExecutionFacade
import com.muyuchat.mca.facade.ImageGenerationFacade
import com.muyuchat.mca.facade.RuntimeFacade
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Request-risk protection for the wired production owners (plan §6 F2): the Local API SSE boundary,
 * the foreground lease registry and chat run ownership.
 *
 * These cases call the real production classes, and each was written to fail against the previous
 * implementation (silently ignored `trySend` results with an unbounded-claim buffer).
 */
class RequestRiskContractTest {

    private class SseHarness(capacity: Int) {
        val supervisor = RuntimeSupervisor(initialEpoch = 1L)
        val chatFacade = ChatExecutionFacade(supervisor)
        val imageFacade = ImageGenerationFacade(supervisor)
        val adapter = LocalApiFacadeAdapter(
            chatFacade = chatFacade,
            imageFacade = imageFacade,
            runtimeFacade = RuntimeFacade(supervisor),
            expectedApiKey = null,
            sseBufferCapacity = capacity
        )

        fun dispatch(idempotencyKey: String): LocalApiResponse.SseStream {
            val response = adapter.handleChatCompletion(
                bodyJson = """{"prompt":"hello streaming","idempotency_key":"$idempotencyKey"}""",
                authHeader = null
            )
            return response as LocalApiResponse.SseStream
        }

        fun routeTokens(requestId: String, count: Int, firstSequence: Int = 1) {
            val identity = requireNotNull(supervisor.requestRegistry.getEntry(requestId)) {
                "request $requestId must be registered before events are routed"
            }.identity
            for (offset in 0 until count) {
                val sequence = (firstSequence + offset).toLong()
                supervisor.callbackRouter.routeEvent(
                    CallbackEvent.token(identity, sequence, "token-$sequence", sequence),
                    currentRuntimeEpoch = supervisor.currentEpoch.value,
                    currentSessionGeneration = identity.sessionGeneration
                )
            }
        }

        fun routeTerminal(requestId: String, sequence: Long) {
            val identity = requireNotNull(supervisor.requestRegistry.getEntry(requestId)) {
                "request $requestId must be registered before events are routed"
            }.identity
            supervisor.callbackRouter.routeEvent(
                CallbackEvent.terminal(identity, sequence, RequestState.Succeeded),
                currentRuntimeEpoch = supervisor.currentEpoch.value,
                currentSessionGeneration = identity.sessionGeneration
            )
        }
    }

    @Test
    fun boundedSseBufferRefusesOverflowExplicitlyInsteadOfDroppingFrames() = runBlocking {
        val harness = SseHarness(capacity = 4)
        val response = harness.dispatch("sse-overflow")
        harness.routeTokens(response.requestId, count = 10)

        val outcome = requireNotNull(harness.adapter.sseOutcome(response.requestId)) {
            "an overflowed stream must have a queryable outcome"
        }
        assertTrue("expected an overflow outcome but was $outcome", outcome is SseStreamOutcome.Overflowed)
        val overflowed = outcome as SseStreamOutcome.Overflowed
        assertFalse("a token frame was refused, not the terminal", overflowed.droppedTerminal)
        assertEquals(5L, overflowed.droppedSequence)
        assertEquals(4, overflowed.deliveredFrames)

        assertFalse(
            "a request whose stream overflowed must not linger as active",
            harness.chatFacade.isChatActive(response.requestId)
        )
        val snapshot = requireNotNull(harness.chatFacade.snapshot(response.requestId))
        assertTrue(snapshot.terminal)
        assertEquals(RequestState.Failed, snapshot.state)

        val frames = mutableListOf<String>()
        val failure = runCatching { response.frames.collect { frames.add(it.event.orEmpty()) } }.exceptionOrNull()
        assertEquals("every accepted frame must still be delivered", 4, frames.size)
        assertTrue("expected an explicit overflow failure but was $failure", failure is SseStreamOverflowException)
        val overflowFailure = failure as SseStreamOverflowException
        assertEquals(5L, overflowFailure.droppedSequence)
        assertFalse(overflowFailure.droppedTerminal)
    }

    @Test
    fun refusedTerminalFrameIsReportedAndNeverSilentlyLost() = runBlocking {
        val harness = SseHarness(capacity = 2)
        val response = harness.dispatch("sse-terminal-refused")
        harness.routeTokens(response.requestId, count = 2)
        harness.routeTerminal(response.requestId, sequence = 3L)

        val overflowed = requireNotNull(harness.adapter.sseOutcome(response.requestId)) as SseStreamOutcome.Overflowed
        assertTrue("a refused terminal must be reported as such", overflowed.droppedTerminal)
        assertEquals(3L, overflowed.droppedSequence)
        assertFalse(harness.chatFacade.isChatActive(response.requestId))
        assertTrue(requireNotNull(harness.chatFacade.snapshot(response.requestId)).terminal)

        val failure = runCatching { response.frames.collect { } }.exceptionOrNull()
        assertTrue(failure is SseStreamOverflowException)
        assertTrue("the failure must say the terminal was refused", (failure as SseStreamOverflowException).droppedTerminal)
    }

    @Test
    fun completedStreamRecordsItsTerminalAndDoesNotCancelTheRequest() = runBlocking {
        val harness = SseHarness(capacity = 4)
        val response = harness.dispatch("sse-completed")
        harness.routeTokens(response.requestId, count = 2)
        harness.routeTerminal(response.requestId, sequence = 3L)

        val frames = mutableListOf<String>()
        response.frames.collect { frames.add(it.event.orEmpty()) }
        assertEquals(listOf("token", "token", "done"), frames)

        val outcome = requireNotNull(harness.adapter.sseOutcome(response.requestId))
        assertTrue("expected a completed outcome but was $outcome", outcome is SseStreamOutcome.Completed)
        assertEquals("done", (outcome as SseStreamOutcome.Completed).terminalEvent)
        assertNull(
            "a fully delivered stream must not be reported as a client disconnect",
            harness.supervisor.cancellationController.getCancellationReason(response.requestId)
        )
    }

    @Test
    fun clientDisconnectCancelsTheRequestAndIsQueryable() = runBlocking {
        val harness = SseHarness(capacity = 4)
        val response = harness.dispatch("sse-disconnect")
        harness.routeTokens(response.requestId, count = 1)

        response.frames.take(1).collect { }

        val outcome = requireNotNull(harness.adapter.sseOutcome(response.requestId))
        assertTrue("expected a disconnected outcome but was $outcome", outcome is SseStreamOutcome.Disconnected)
        assertEquals(
            "Client connection closed",
            harness.supervisor.cancellationController.getCancellationReason(response.requestId)
        )
    }

    @Test
    fun foregroundLeaseIsReleasedOnAnotherThreadAndDuplicateReleaseIsInert() {
        val registry = GenerationForegroundLeaseRegistry()
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val lease = AtomicReference<GenerationForegroundLease?>()
        val acquirer = Thread { lease.set(registry.acquire("image") { starts.incrementAndGet() }) }
        acquirer.start()
        acquirer.join(5_000)
        val acquired = requireNotNull(lease.get())

        val released = AtomicBoolean(false)
        val releaser = Thread { released.set(registry.release(acquired) { stops.incrementAndGet() }) }
        releaser.start()
        releaser.join(5_000)

        assertTrue("the lease must be released from a different thread", released.get())
        assertEquals(1, starts.get())
        assertEquals(1, stops.get())
        assertFalse("a duplicate release must not stop a live owner again", registry.release(acquired) { stops.incrementAndGet() })
        assertEquals(1, stops.get())
        assertNull(registry.withCurrentTask { it })
    }

    @Test
    fun oldForegroundLeaseCannotStopAReplacementOwner() {
        val registry = GenerationForegroundLeaseRegistry()
        val stops = AtomicInteger()
        val first = registry.acquire("chat") { }
        val second = registry.acquire("chat") { }

        assertTrue(registry.release(first) { stops.incrementAndGet() })
        assertEquals("the replacement owner keeps the foreground service alive", 0, stops.get())
        assertEquals(GenerationForegroundLeaseRegistry.Snapshot("chat", 1), registry.withCurrentTask { it })

        assertTrue(registry.release(second) { stops.incrementAndGet() })
        assertEquals(1, stops.get())
        assertNull(registry.withCurrentTask { it })
    }

    @Test
    fun staleChatRunCannotRetireTheNewerPublishedJob() {
        val ownership = ChatRunOwnership()
        val first = Job()
        val second = Job()

        ownership.publishJob(first)
        ownership.publishJob(second)
        ownership.retirePublishedJob(first)

        assertSame("a finishing older run must not clear a newer run's job", second, ownership.activePublishedJob())

        ownership.retirePublishedJob(second)
        assertNull(ownership.activePublishedJob())
        assertTrue(ownership.isCurrent(null))
        assertFalse(ownership.matches(null))
        assertTrue(ownership.matches(ownership.currentRunId()))
    }

    @Test
    fun supersedingARunCancelsTheSupersededJobOnly() {
        val ownership = ChatRunOwnership()
        val activeJob = Job()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, activeJob))

        val superseded = requireNotNull(ownership.reserveStartAndCancelSuperseded())

        assertTrue("the superseded run's job must be cancelled", activeJob.isCancelled)
        assertFalse("the superseded run id is no longer current", ownership.isCurrent(reservation.runId))
        assertTrue(ownership.cancelPending(superseded))
        assertNotNull(ownership.currentRunId())
    }

    @Test
    fun sdxlRequestLeaseProcessLockReleaseIsIdempotentAndThreadAgnostic() {
        val root = File(System.getProperty("java.io.tmpdir"), "mca-sdxl-lease-race-${System.nanoTime()}")
            .apply { mkdirs() }
        try {
            val requestId = "qnn-htp-7777-33333333-3333-3333-3333-333333333333"
            val params = JSONObject()
                .put("workerStrategy", ImageWorkerStrategy.SPLIT_UNET_VAE.name)
                .put("taskMode", LocalImageTaskMode.TEXT_TO_IMAGE.wireName)
                .put("profileId", "sdxl.qnn.split")
                .put("profileRevision", 4)
                .put("modelFingerprint", "a".repeat(64))
                .put("steps", 30)
            val lease = SdxlTwoPhaseRequestLease.acquire(
                requestId = requestId,
                params = params,
                workerPid = 1234,
                coordinationRoot = File(root, "request-journal"),
                embeddingsFile = File(root, "$requestId.sdxl-conditioning.f32"),
                latentFile = File(root, "$requestId.latent.f32"),
                metadataFile = File(root, "$requestId.latent.json"),
                inputTensorFile = File(root, "$requestId.input-rgb-nchw.f32"),
                encoderLatentFile = File(root, "$requestId.encoder-latent.f32"),
                encoderMetadataFile = File(root, "$requestId.encoder-latent.json"),
                outputFile = File(root, "$requestId.png"),
                encoderJournal = File(root, "$requestId.encoder-stage.json"),
                unetJournal = File(root, "$requestId.unet-stage.json"),
                vaeJournal = File(root, "$requestId.vae-stage.json"),
                parentDirectorySyncer = ParentDirectorySyncer { },
                clock = { 1_700_000_000_000L }
            )

            val errors = ConcurrentLinkedQueue<Throwable>()
            val start = CountDownLatch(1)
            val closers = (0 until 2).map { index ->
                Thread({
                    try {
                        start.await()
                        lease.close()
                    } catch (error: Throwable) {
                        errors.add(error)
                    }
                }, "sdxl-lease-closer-$index")
            }
            closers.forEach { it.start() }
            start.countDown()
            closers.forEach { it.join(5_000) }

            assertTrue("a cross-thread close must not throw: $errors", errors.isEmpty())
            lease.close()
            val rejected = runCatching { lease.update(lease.entry) }.exceptionOrNull()
            assertNotNull(
                "a released lease must reject further ownership operations instead of double-releasing",
                rejected
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
