package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour contract for [ChatRunOwnership] -- the seam that answers "does this callback still
 * own the UI?" for the whole chat run.
 *
 * This is the counterweight to the extraction: twenty-odd call sites in `MainViewModel` used to
 * compare a raw `AtomicLong` by hand, and the run `Job` was a bare field. Those primitives are now
 * private to the owner, so the rules they encoded have to be pinned here instead of being implied by
 * the call sites. What is pinned is exactly the set of ways a stale callback could corrupt a newer
 * request: activating a superseded reservation, retiring someone else's run, resuming a backgrounded
 * one, or treating an equal-but-different job as the same job.
 */
class ChatRunOwnershipContractTest {

    private fun ownership() = ChatRunOwnership(AtomicLong(0L))

    @Test
    fun `an unbound run id is always current`() {
        val runs = ownership()

        // Every hand-written guard was `if (runId != null && seq != runId) return`, so a null id
        // meant "no ownership check at all". That has to stay true.
        assertTrue(runs.isCurrent(null))
        assertFalse("matches() is the strict form and must reject null", runs.matches(null))
    }

    @Test
    fun `reserving a run advances the epoch and supersedes the previous one`() {
        val runs = ownership()
        val first = runs.reserveStart()!!
        assertTrue(runs.isCurrent(first.runId))

        val second = runs.reserveStart()!!

        assertNotEquals(first.runId, second.runId)
        assertTrue("the newer reservation owns the UI", runs.isCurrent(second.runId))
        assertFalse("the superseded run must no longer be current", runs.isCurrent(first.runId))
    }

    @Test
    fun `activate only accepts the pending reservation`() {
        val runs = ownership()
        val first = runs.reserveStart()!!
        val second = runs.reserveStart()!!
        val job = Job()

        assertFalse("a superseded reservation can never activate", runs.activate(first, job))
        assertTrue(runs.activate(second, job))
    }

    @Test
    fun `activate reports failure once the epoch has moved on`() {
        val runs = ownership()
        val reservation = runs.reserveStart()!!
        runs.cancelPending(reservation)

        assertFalse(
            "cancelling a pending reservation advances the epoch, so activation must fail",
            runs.activate(reservation, Job())
        )
    }

    @Test
    fun `markPhase binds both the run id and the owner identity`() {
        val runs = ownership()
        val reservation = runs.reserveStart()!!
        val job = Job()
        assertTrue(runs.activate(reservation, job))

        assertTrue(runs.markPhase(reservation.runId, job, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        assertFalse(
            "a different instance must not be able to move the phase",
            runs.markPhase(reservation.runId, Job(), UiGenerationRuntimePhase.LOCAL_ACTIVE)
        )
        assertFalse(
            "an older run id must not be able to move the phase",
            runs.markPhase(reservation.runId - 1, job, UiGenerationRuntimePhase.LOCAL_ACTIVE)
        )
    }

    @Test
    fun `a stale finish cannot retire a newer run`() {
        val runs = ownership()
        val first = runs.reserveStart()!!
        val firstJob = Job()
        assertTrue(runs.activate(first, firstJob))

        val second = runs.reserveStart()!!
        val secondJob = Job()
        assertTrue(runs.activate(second, secondJob))

        runs.finish(first.runId, firstJob)

        // The newer run must still be able to move its phase -- if the stale finish had retired it,
        // this would fail.
        assertTrue(runs.markPhase(second.runId, secondJob, UiGenerationRuntimePhase.LOCAL_ACTIVE))
    }

    @Test
    fun `cancelPending kills the reservation permanently`() {
        val runs = ownership()
        val reservation = runs.reserveStart()!!

        assertTrue(runs.cancelPending(reservation))
        assertFalse("the epoch moved on, so a second cancel is a no-op", runs.cancelPending(reservation))
        assertFalse(runs.isCurrent(reservation.runId))
    }

    @Test
    fun `cancelCurrent reports the owner and only stops the local runtime when it is active`() {
        val runs = ownership()
        val reservation = runs.reserveStart()!!
        val job = Job()
        assertTrue(runs.activate(reservation, job))

        val pendingCancellation = runs.cancelCurrent()
        assertSame(job, pendingCancellation.owner)
        assertFalse(
            "a run that never reached the native engine must not ask for a runtime stop",
            pendingCancellation.stopLocalRuntime
        )

        val second = runs.reserveStart()!!
        val secondJob = Job()
        assertTrue(runs.activate(second, secondJob))
        assertTrue(runs.markPhase(second.runId, secondJob, UiGenerationRuntimePhase.LOCAL_ACTIVE))

        val activeCancellation = runs.cancelCurrent()
        assertTrue(activeCancellation.stopLocalRuntime)
        assertFalse("the epoch advanced, so the cancelled run is no longer current", runs.isCurrent(second.runId))
    }

    @Test
    fun `backgrounding stops new runs but does not cancel the owned one`() {
        val runs = ownership()
        val reservation = runs.reserveStart()!!
        val job = Job()
        assertTrue(runs.activate(reservation, job))

        val cancellation = runs.background()

        assertFalse("backgrounding is not a cancellation", cancellation.pendingCancelled)
        assertNull(cancellation.owner)
        assertNull("no new work is admitted while backgrounded", runs.reserveStart())
        assertTrue(
            "the owned run keeps its phase transitions across backgrounding",
            runs.markPhase(reservation.runId, job, UiGenerationRuntimePhase.LOCAL_ACTIVE)
        )

        runs.foreground()
        assertTrue("foregrounding admits new work again", runs.reserveStart() != null)
    }

    @Test
    fun `the published job is retired by identity only`() {
        val runs = ownership()
        val job = Job()
        val other = Job()

        runs.publishJob(job)
        runs.retirePublishedJob(other)
        assertSame("an equal-looking but different job must not retire it", job, runs.activePublishedJob())

        runs.retirePublishedJob(job)
        assertNull(runs.activePublishedJob())
    }

    @Test
    fun `only an active published job is handed out`() {
        val runs = ownership()
        val job = Job()
        runs.publishJob(job)
        assertSame(job, runs.activePublishedJob())

        job.cancel()
        assertNull("a completed or cancelled job is not something to join", runs.activePublishedJob())
    }

    @Test
    fun `the published job can be excluded so a run does not join itself`() {
        val runs = ownership()
        val published = Job()
        val recovering = Job()
        runs.publishJob(published)

        assertSame(published, runs.publishedJobUnless(recovering))
        assertNull(
            "the caller asks for 'the published job unless it is the one I am cancelling'",
            runs.publishedJobUnless(published)
        )
    }

    @Test
    fun `the epoch never moves backwards`() {
        val runs = ownership()
        val seen = mutableListOf(runs.currentRunId())
        repeat(3) {
            runs.reserveStart()
            seen += runs.currentRunId()
        }
        runs.cancelCurrent()
        seen += runs.currentRunId()

        assertEquals("the counter is monotonic", seen.sorted(), seen)
        assertEquals("and it actually advanced", seen.size, seen.toSet().size)
    }

    // ── published-job concurrency ─────────────────────────────────────────────────────────────

    @Test
    fun `a late completion callback cannot clear a newer run's published job`() {
        val runs = ownership()
        val older = Job()
        val newer = Job()

        runs.publishJob(older)
        runs.publishJob(newer)

        // This is the race the reference has to survive: `older`'s invokeOnCompletion may fire on
        // any thread after `newer` has already been published.
        runs.retirePublishedJob(older)

        assertSame(
            "retiring the superseded job must leave the newer one published",
            newer,
            runs.activePublishedJob()
        )
    }

    @Test
    fun `retiring the current job still works when it really is current`() {
        val runs = ownership()
        val job = Job()
        runs.publishJob(job)

        runs.retirePublishedJob(job)

        assertNull(runs.activePublishedJob())
        assertNull(runs.publishedJobUnless(null))
    }

    @Test
    fun `a concurrent publish and retire never strands the owner with a retired handle`() {
        // Interleaves publish/retire from several threads on the real implementation. With a plain
        // `var` this can lose a write and leave a completed job published; the identity CAS makes
        // the final state well defined.
        val runs = ownership()
        val jobs = List(64) { Job() }
        val threads = jobs.map { job ->
            Thread {
                runs.publishJob(job)
                runs.retirePublishedJob(job)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val remaining = runs.activePublishedJob()
        assertTrue(
            "the published handle must never be a job that already retired itself",
            remaining == null || jobs.none { it === remaining && it.isCompleted }
        )
    }

    @Test
    fun `publishing is visible to a reader on another thread`() {
        val runs = ownership()
        val job = Job()
        val seen = java.util.concurrent.atomic.AtomicReference<Job?>(null)

        runs.publishJob(job)
        Thread { seen.set(runs.activePublishedJob()) }.apply { start(); join() }

        assertSame(job, seen.get())
    }

    // ── superseded / cancelled job cancellation policy ────────────────────────────────────────

    @Test
    fun `reserving cancels the job of the run it superseded and returns the new reservation`() {
        val runs = ownership()
        val first = runs.reserveStart()!!
        val firstJob = Job()
        assertTrue(runs.activate(first, firstJob))

        val second = runs.reserveStartAndCancelSuperseded()!!

        assertTrue("the superseded run's job must be cancelled", firstJob.isCancelled)
        assertNotEquals(first.runId, second.runId)
        assertTrue("the new reservation is the current one", runs.isCurrent(second.runId))
    }

    @Test
    fun `reserving while backgrounded cancels nothing and returns null`() {
        val runs = ownership()
        val first = runs.reserveStart()!!
        val firstJob = Job()
        assertTrue(runs.activate(first, firstJob))
        runs.background()

        assertNull("a refused reservation must be null", runs.reserveStartAndCancelSuperseded())
        assertFalse(
            "the short-circuit is the behaviour: nothing may be cancelled when no run is admitted",
            firstJob.isCancelled
        )
    }

    @Test
    fun `the epoch is advanced before the superseded job is cancelled`() {
        // The order matters: a cancellation handler that runs because of the cancel() must already
        // observe the new epoch, otherwise late cleanup could claim the replacement request.
        val runs = ownership()
        val first = runs.reserveStart()!!
        val seenEpochWhenCancelled = java.util.concurrent.atomic.AtomicLong(-1L)
        val firstJob = Job().apply {
            invokeOnCompletion { seenEpochWhenCancelled.set(runs.currentRunId()) }
        }
        assertTrue(runs.activate(first, firstJob))

        val second = runs.reserveStartAndCancelSuperseded()!!

        assertEquals(
            "the completion callback must observe the new run, not the one it just left",
            second.runId,
            seenEpochWhenCancelled.get()
        )
    }

    @Test
    fun `cancelling returns the original owner, not null, and cancels it`() {
        val runs = ownership()
        val reservation = runs.reserveStart()!!
        val job = Job()
        assertTrue(runs.activate(reservation, job))

        val cancellation = runs.cancelCurrentAndCancelOwner()

        assertSame(
            "callers read this afterwards to decide whether the local runtime needs a stop",
            job,
            cancellation.owner
        )
        assertTrue("and the owner's job must actually be cancelled", job.isCancelled)
    }

    @Test
    fun `cancelling an idle owner is harmless and reports no owner`() {
        val runs = ownership()

        val cancellation = runs.cancelCurrentAndCancelOwner()

        assertNull(cancellation.owner)
        assertFalse(cancellation.pendingCancelled)
    }
}
