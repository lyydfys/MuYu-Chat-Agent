package com.muyuchat.mca

import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.video.worker.VideoWorkerCore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Concurrency contract of [NativeMediaExecutionGate] — the single physical native media
 * execution domain shared by image generate/upscale and (from V1-02b) the video pipeline
 * (proposal §7.4). The lock FILE name is the cross-process domain identity and stays frozen;
 * these JVM tests drive the real protocol through the File-based core:
 *
 *  - one holder at a time per process, fair hand-off to the next waiter;
 *  - a waiter cancelled while waiting throws, never runs its block, and leaks no lease;
 *  - normal completion always returns the lease so the next task proceeds immediately.
 *
 * NOT covered here (NOT_RUN by design): a second OS process contending on the same lock file and
 * lease release after holder process death — those need the real worker processes of V1-02b/V1-03.
 */
class NativeMediaExecutionGateConcurrencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun lockFile(): File = File(tmp.newFolder(), "native_image_execution.lock")

    @Test
    fun `a second holder waits until the first releases`() = runTest {
        val lockFile = lockFile()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var secondEntered = false

        val first = launch {
            NativeMediaExecutionGate.withLease(lockFile) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                "first"
            }
        }
        withTimeout(5_000) { firstEntered.await() }

        val second = launch {
            NativeMediaExecutionGate.withLease(lockFile) {
                secondEntered = true
                "second"
            }
        }
        // Give the waiter time to (incorrectly) enter if serialization were broken.
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertFalse("second holder must not run while the first holds the lease", secondEntered)

        releaseFirst.complete(Unit)
        first.join()
        withTimeout(5_000) {
            while (!secondEntered) {
                testScheduler.advanceTimeBy(200)
                testScheduler.runCurrent()
            }
        }
        second.join()
        assertTrue(secondEntered)
    }

    @Test
    fun `cancel while waiting throws, never runs the block and leaks no lease`() = runTest {
        val lockFile = lockFile()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var blockRan = false
        var waitingReported = 0

        val first = launch {
            NativeMediaExecutionGate.withLease(lockFile) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        withTimeout(5_000) { firstEntered.await() }

        var cancelledByWait: Throwable? = null
        val second = launch {
            try {
                NativeMediaExecutionGate.withLease(
                    lockFile,
                    // Cancel kicks in only once the waiter has actually entered the waiting
                    // loop — a cancel that was already active at entry is a different case.
                    isCancelled = { waitingReported > 0 },
                    onWaiting = { waitingReported++ },
                ) {
                    blockRan = true
                }
            } catch (expected: NativeMediaExecutionLeaseCancelledException) {
                cancelledByWait = expected
            }
        }
        second.join()
        assertTrue("cancelled waiter must surface the typed exception", cancelledByWait is NativeMediaExecutionLeaseCancelledException)
        assertFalse("cancelled waiter must never run its block", blockRan)
        assertTrue("the waiter must have reported the waiting state", waitingReported > 0)

        // The cancelled attempt leaked nothing: the next task acquires immediately.
        releaseFirst.complete(Unit)
        first.join()
        var nextEntered = false
        val next = async {
            NativeMediaExecutionGate.withLease(lockFile) {
                nextEntered = true
            }
        }
        withTimeout(5_000) { next.await() }
        assertTrue(nextEntered)
    }

    @Test
    fun `sequential leases hand over immediately`() = runTest {
        val lockFile = lockFile()
        val results = mutableListOf<String>()
        withTimeout(5_000) {
            NativeMediaExecutionGate.withLease(lockFile) { results += "a" }
            NativeMediaExecutionGate.withLease(lockFile) { results += "b" }
        }
        assertEquals(listOf("a", "b"), results)
    }

    @Test
    fun `video worker cancelled while waiting for the real gate never runs or leaks`() = runTest {
        val lockFile = lockFile()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val outcome = CompletableDeferred<VideoWorkerCore.WorkerOutcome>()
        var executorRan = false

        val events = object : VideoWorkerCore.Events {
            override fun onStage(attemptId: String, stage: VideoRunStage, step: Long?, stepTotal: Long?) = Unit
            override fun onOutcome(attemptId: String, outcomeValue: VideoWorkerCore.WorkerOutcome) {
                outcome.complete(outcomeValue)
            }
            override fun onWaitingForLease(attemptId: String) = Unit
        }
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { notifyWaiting, isCancelled, block ->
                NativeMediaExecutionGate.withLease(lockFile, isCancelled, notifyWaiting) { block() }
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )

        val holder = launch {
            NativeMediaExecutionGate.withLease(lockFile) {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        withTimeout(5_000) { holderEntered.await() }
        assertTrue(
            core.begin(
                VideoWorkerCore.Attempt("v-run", "v-att", "v-req", "{}", "LOCAL_NATIVE"),
                events,
            ),
        )
        // Let the worker reach the real gate and enter its retry loop while the holder is active.
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        core.cancel("v-req")
        releaseHolder.complete(Unit)
        holder.join()
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()

        assertFalse("a cancelled waiter must never enter the executor", executorRan)
        assertTrue("the cancelled waiter must settle an outcome", outcome.isCompleted)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, outcome.await())

        // The cancelled wait leaked neither the process lease nor the file lock.
        var nextEntered = false
        withTimeout(5_000) {
            NativeMediaExecutionGate.withLease(lockFile) { nextEntered = true }
        }
        assertTrue("a cancelled waiter must release the lease", nextEntered)
    }
}
