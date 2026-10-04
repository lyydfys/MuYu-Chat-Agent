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
}
