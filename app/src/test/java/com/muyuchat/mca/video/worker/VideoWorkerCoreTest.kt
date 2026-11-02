package com.muyuchat.mca.video.worker

import com.muyuchat.core.video.contract.VideoErrorCode
import com.muyuchat.core.video.contract.VideoRunStage
import com.muyuchat.mca.NativeMediaExecutionLeaseCancelledException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol contract of the :local_video worker core (V1-02b / P0-B): single-active admission,
 * duplicate refusal, identity-checked cancel, binder-death cancellation, honest
 * bridge-unavailable failure, the settle-exactly-once guard and the watchdog exit.
 *
 * These are PROTOCOL tests with an injected executor body — they are not evidence of any real
 * video generation (that begins with V1-03's JNI on a real device).
 */
class VideoWorkerCoreTest {

    private class RecordingEvents : VideoWorkerCore.Events {
        val stages = mutableListOf<Pair<String, VideoRunStage>>()
        val outcomes = mutableListOf<VideoWorkerCore.WorkerOutcome>()
        val waiting = mutableListOf<String>()
        val outcomeDelivered = CompletableDeferred<VideoWorkerCore.WorkerOutcome>()

        override fun onStage(attemptId: String, stage: VideoRunStage, step: Long?, stepTotal: Long?) {
            stages += attemptId to stage
        }

        override fun onOutcome(attemptId: String, outcome: VideoWorkerCore.WorkerOutcome) {
            outcomes += outcome
            outcomeDelivered.complete(outcome)
        }

        override fun onWaitingForLease(attemptId: String) {
            waiting += attemptId
        }
    }

    private fun attempt(id: String = "a1") = VideoWorkerCore.Attempt(
        runId = "v-run-$id",
        attemptId = "v-att-$id",
        requestId = "v-req-$id",
        snapshotJson = "{}",
        backend = "LOCAL_NATIVE",
    )

    @Test
    fun `second begin is explicitly refused while an attempt is active`() = runTest {
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block -> block() },
            executorBody = { execution -> execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded) },
        )
        assertTrue(core.begin(attempt("1"), RecordingEvents()))
        assertFalse("duplicate begin must be refused", core.begin(attempt("2"), RecordingEvents()))
        testScheduler.runCurrent()
        assertNull(core.activeAttemptId)
    }

    @Test
    fun `unavailable executor fails openly with BACKEND_UNAVAILABLE`() = runTest {
        val core = VideoWorkerCore(scope = backgroundScope, withGate = { _, _, block -> block() })
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        val outcome = events.outcomes.single()
        assertTrue(outcome is VideoWorkerCore.WorkerOutcome.Failed)
        assertEquals(VideoErrorCode.BACKEND_UNAVAILABLE, (outcome as VideoWorkerCore.WorkerOutcome.Failed).code)
        assertTrue(events.stages.isEmpty()) // no fake stages were ever reported
    }

    @Test
    fun `cancel is identity checked`() = runTest {
        var observedCancel = false
        val gate = CompletableDeferred<Unit>()
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block ->
                gate.complete(Unit)
                block()
            },
            executorBody = { execution ->
                gate.await()
                while (!execution.cancelRequested()) kotlinx.coroutines.delay(1)
                observedCancel = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        // A foreign request id must not stop anything.
        core.cancel("v-req-FOREIGN")
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertFalse(observedCancel)
        core.cancel("v-req-a1")
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertTrue(observedCancel)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomes.single())
    }

    @Test
    fun `client death cancels the in-flight attempt`() = runTest {
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block -> block() },
            executorBody = { execution ->
                while (!execution.cancelRequested()) kotlinx.coroutines.delay(1)
                execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        core.onClientDied("v-req-a1")
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomes.single())
    }

    @Test
    fun `executor returning without an outcome is loudly failed`() = runTest {
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block -> block() },
            executorBody = { }, // returns silently
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        val outcome = events.outcomes.single() as VideoWorkerCore.WorkerOutcome.Failed
        assertEquals(VideoErrorCode.NATIVE_EXECUTION_FAILED, outcome.code)
    }

    @Test
    fun `settle happens exactly once even if the executor completes twice`() = runTest {
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block -> block() },
            executorBody = { execution ->
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
                execution.complete(VideoWorkerCore.WorkerOutcome.Failed(VideoErrorCode.DISK_FULL, null))
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        assertEquals(listOf<VideoWorkerCore.WorkerOutcome>(VideoWorkerCore.WorkerOutcome.Succeeded), events.outcomes)
    }

    @Test
    fun `cancelAndExit escalates to process exit`() = runTest {
        var exited = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block -> block() },
            executorBody = { execution ->
                while (!execution.cancelRequested()) kotlinx.coroutines.delay(1)
                execution.complete(VideoWorkerCore.WorkerOutcome.Stopped)
            },
            exitProcess = { exited = true; throw IllegalStateException("worker process exit") },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        val escalated = runCatching { core.cancelAndExit("v-req-a1") }
        assertTrue("escalation terminates the caller by design", escalated.isFailure)
        assertTrue(exited)
    }

    @Test
    fun `gate waiting is reported before execution starts`() = runTest {
        var inExecutor = false
        val release = CompletableDeferred<Unit>()
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { notify, _, block ->
                // The gate is contended: the attempt waits and reports it, then proceeds.
                notify()
                block()
            },
            executorBody = { execution ->
                inExecutor = true
                release.await()
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        assertTrue(events.waiting.isNotEmpty())
        assertTrue(inExecutor)
        release.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(VideoWorkerCore.WorkerOutcome.Succeeded, events.outcomes.single())
    }

    @Test
    fun `executor runs inside the shared gate lease`() = runTest {
        var gateHeld = false
        var executorObservedLease = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, _, block ->
                gateHeld = true
                try {
                    block()
                } finally {
                    gateHeld = false
                }
            },
            executorBody = { execution ->
                executorObservedLease = gateHeld
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        assertTrue("the executor must observe the gate lease held", executorObservedLease)
        assertFalse("the gate must be released after the executor settles", gateHeld)
        assertEquals(VideoWorkerCore.WorkerOutcome.Succeeded, events.outcomes.single())
    }

    @Test
    fun `cancel while waiting for the gate never runs the executor`() = runTest {
        val gateEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executorRan = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { notify, isCancelled, block ->
                notify()
                gateEntered.complete(Unit)
                release.await()
                if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        gateEntered.await()
        core.cancel("v-req-a1")
        release.complete(Unit)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertFalse("cancel while waiting must not start the executor", executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomeDelivered.await())
    }

    @Test
    fun `cancel before the gate is entered never runs the executor`() = runTest {
        var executorRan = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, isCancelled, block ->
                if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        core.cancel("v-req-a1")
        testScheduler.runCurrent()
        assertFalse("a cancel recorded at admission must abort before the gate", executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomeDelivered.await())
    }

    @Test
    fun `foreign cancel while waiting is ignored and execution proceeds`() = runTest {
        val gateEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executorRan = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, isCancelled, block ->
                gateEntered.complete(Unit)
                release.await()
                if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        gateEntered.await()
        core.cancel("v-req-FOREIGN")
        release.complete(Unit)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue("a foreign request id must not cancel the waiting attempt", executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Succeeded, events.outcomeDelivered.await())
    }

    @Test
    fun `client death while waiting never runs the executor`() = runTest {
        val gateEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executorRan = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, isCancelled, block ->
                gateEntered.complete(Unit)
                release.await()
                if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        gateEntered.await()
        core.onClientDied("v-req-a1")
        release.complete(Unit)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertFalse("client death must cancel its waiting attempt before native starts", executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomeDelivered.await())
    }

    @Test
    fun `cancelAndExit while waiting exits without running the executor`() = runTest {
        val gateEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executorRan = false
        var exited = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, isCancelled, block ->
                gateEntered.complete(Unit)
                release.await()
                if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
            exitProcess = { exited = true; throw IllegalStateException("worker process exit") },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        gateEntered.await()
        val escalated = runCatching { core.cancelAndExit("v-req-a1") }
        assertTrue("watchdog escalation terminates its caller", escalated.isFailure)
        assertTrue(exited)
        release.complete(Unit)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertFalse("process exit while waiting must not start the executor", executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomeDelivered.await())
    }

    @Test
    fun `cancel that lands after acquisition still prevents the executor`() = runTest {
        val acquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executorRan = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            // This gate deliberately ignores isCancelled once the lock is acquired, modelling the
            // race where cancellation arrives between acquisition and the executor body.
            withGate = { _, _, block ->
                acquired.complete(Unit)
                release.await()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        acquired.await()
        core.cancel("v-req-a1")
        release.complete(Unit)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertFalse("a post-acquisition cancel must not start native", executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Stopped, events.outcomeDelivered.await())
    }

    @Test
    fun `foreign cancelAndExit does not exit or touch the attempt`() = runTest {
        val gateEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var executorRan = false
        var exited = false
        val core = VideoWorkerCore(
            scope = backgroundScope,
            withGate = { _, isCancelled, block ->
                gateEntered.complete(Unit)
                release.await()
                if (isCancelled()) throw NativeMediaExecutionLeaseCancelledException()
                block()
            },
            executorBody = { execution ->
                executorRan = true
                execution.complete(VideoWorkerCore.WorkerOutcome.Succeeded)
            },
            exitProcess = { exited = true; throw IllegalStateException("worker process exit") },
        )
        val events = RecordingEvents()
        assertTrue(core.begin(attempt(), events))
        testScheduler.runCurrent()
        gateEntered.await()
        core.cancelAndExit("v-req-FOREIGN")
        assertFalse("a foreign request id must not exit the worker", exited)
        release.complete(Unit)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertTrue(executorRan)
        assertEquals(VideoWorkerCore.WorkerOutcome.Succeeded, events.outcomeDelivered.await())
    }
}
