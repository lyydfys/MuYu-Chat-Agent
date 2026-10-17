package com.muyuchat.mca.request

import com.muyuchat.mca.ChatRunOwnership
import com.muyuchat.mca.GenerationForegroundLease
import com.muyuchat.mca.GenerationForegroundLeaseRegistry
import com.muyuchat.mca.ImageWorkerStrategy
import com.muyuchat.mca.LocalImageTaskMode
import com.muyuchat.mca.ParentDirectorySyncer
import com.muyuchat.mca.SdxlTwoPhaseRequestLease
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Request-risk protection for the wired production owners (plan §6 F2): foreground lease ownership
 * and chat run ownership.
 */
class RequestRiskContractTest {

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
