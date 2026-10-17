package com.muyuchat.mca

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Behavioural coverage for the owner-owned Local API image lifecycle. */
class LocalApiImageOperationOwnershipTest {

    @Test
    fun `an active UI image job blocks Local API before lease admission`() = runBlocking {
        val harness = Harness(activeUiJob = true)

        val error = runCatching {
            harness.gate.run("api-request") { error("the block must not run") }
        }.exceptionOrNull()

        assertTrue(error is LocalApiImageOperationBusyException)
        assertEquals(0, harness.leaseAttempts)
        assertNull(harness.coordinator.activeRequestId())
    }

    @Test
    fun `same request id is serialized while active and reusable after release`() = runBlocking {
        val harness = Harness()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            harness.gate.run("same-request") {
                firstEntered.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        firstEntered.await()

        val sameId = runCatching {
            harness.gate.run("same-request") { "must-not-run" }
        }.exceptionOrNull()
        val otherId = runCatching {
            harness.gate.run("other-request") { "must-not-run" }
        }.exceptionOrNull()

        assertTrue(sameId is LocalApiImageOperationBusyException)
        assertTrue(otherId is LocalApiImageOperationBusyException)
        assertEquals("same-request", harness.coordinator.activeRequestId())

        releaseFirst.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("reused", harness.gate.run("same-request") { "reused" })
        assertNull(harness.coordinator.activeRequestId())
    }

    @Test
    fun `cancellation cancels the owned worker and unwinds after the release barrier`() = runBlocking {
        val harness = Harness()
        val started = CompletableDeferred<Unit>()

        val job = launch {
            harness.gate.run("cancel-me") { binding ->
                binding.bindModel("model-a")
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        assertEquals("model-a", harness.activeModelId)

        assertTrue(harness.gate.cancelActive("stop requested"))
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("cancel-me"), harness.cancelledWorkerRequests)
        assertEquals(1, harness.releaseAwaitCount)
        assertEquals(1, harness.uiCleanupCount)
        assertEquals(1, harness.releaseCount)
        assertNull(harness.activeModelId)
        assertNull(harness.coordinator.activeRequestId())
    }

    @Test
    fun `an exception keeps ownership until the worker release barrier completes`() = runBlocking {
        val harness = Harness()
        val releaseEntered = CompletableDeferred<Unit>()
        val releaseBarrier = CompletableDeferred<Unit>()
        harness.releaseBlocker = releaseBarrier
        harness.releaseEntered = releaseEntered

        val failure = async {
            runCatching {
                harness.gate.run("exception-request") { binding ->
                    binding.bindModel("model-b")
                    error("worker failed")
                }
            }.exceptionOrNull()
        }

        releaseEntered.await()
        assertEquals("exception-request", harness.coordinator.activeRequestId())
        assertEquals(0, harness.releaseCount)
        assertEquals(0, harness.uiCleanupCount)
        assertTrue(harness.cancelledWorkerRequests.isEmpty())

        releaseBarrier.complete(Unit)
        val error = failure.await()
        assertTrue(error is IllegalStateException)
        assertEquals(1, harness.releaseAwaitCount)
        assertEquals(1, harness.uiCleanupCount)
        assertEquals(1, harness.releaseCount)
        assertNull(harness.activeModelId)
        assertNull(harness.coordinator.activeRequestId())
        assertEquals(
            listOf("model:model-b", "barrier", "model:null", "ui-cleanup", "release"),
            harness.events
        )
    }

    @Test
    fun `a stale cancellation cannot cancel a newer same id request`() = runBlocking {
        val harness = Harness()
        val firstEntered = CompletableDeferred<Unit>()
        val first = async {
            harness.gate.run("repeat-id") {
                firstEntered.complete(Unit)
                awaitCancellation()
            }
        }
        firstEntered.await()
        assertTrue(harness.gate.cancelActive("cancel first"))
        runCatching { first.await() }
        assertTrue(first.isCancelled)

        val secondEntered = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val second = async {
            harness.gate.run("repeat-id") {
                secondEntered.complete(Unit)
                releaseSecond.await()
            }
        }
        secondEntered.await()

        assertEquals(1, harness.cancelledWorkerRequests.size)
        assertFalse(second.isCancelled)
        releaseSecond.complete(Unit)
        second.await()
        assertNull(harness.coordinator.activeRequestId())
    }

    private class Harness(activeUiJob: Boolean = false) {
        val coordinator = LocalImageGenerationCoordinator()
        private var activeUiJob = activeUiJob
        var activeModelId: String? = null
        var leaseAttempts = 0
        var releaseCount = 0
        var releaseAwaitCount = 0
        var uiCleanupCount = 0
        var releaseBlocker: CompletableDeferred<Unit>? = null
        var releaseEntered: CompletableDeferred<Unit>? = null
        val cancelledWorkerRequests = mutableListOf<String>()
        val events = mutableListOf<String>()

        val gate = LocalApiImageOperationGate(
            hasActiveUiImageJob = { this.activeUiJob },
            tryAcquireLease = { requestId ->
                leaseAttempts += 1
                coordinator.tryAcquire(requestId)
            },
            releaseLease = { lease ->
                releaseCount += 1
                events += "release"
                coordinator.release(lease)
            },
            cancelWorker = { requestId ->
                cancelledWorkerRequests += requestId
                true
            },
            awaitWorkerRelease = {
                releaseAwaitCount += 1
                events += "barrier"
                releaseEntered?.complete(Unit)
                releaseBlocker?.await()
            },
            setActiveModelId = { modelId ->
                activeModelId = modelId
                events += "model:$modelId"
            },
            finishUiOwnership = {
                uiCleanupCount += 1
                events += "ui-cleanup"
            }
        )
    }
}
