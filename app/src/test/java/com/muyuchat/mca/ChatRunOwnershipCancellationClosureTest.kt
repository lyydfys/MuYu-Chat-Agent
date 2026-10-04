package com.muyuchat.mca

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancellation-closure boundaries for [ChatRunOwnership] -- the R1 exit conditions.
 *
 * Complements `ChatRunOwnershipContractTest` (which covers reservation, activation, the job handle
 * and epoch monotonicity). This file covers only the closure questions: what a **repeated** cancel
 * may do, what the **previous** run may still do once a new one owns the UI, what **backgrounding**
 * allows, and that a publication buffered by the previous run can never land.
 *
 * Every assertion is observable behaviour: return values, resulting ownership, side-effect presence.
 * Nothing here reads source text or asserts that one function called another.
 */
class ChatRunOwnershipCancellationClosureTest {

    private class Started(val ownership: ChatRunOwnership, val owner: Any, val runId: Long)

    private fun started(ownership: ChatRunOwnership = ChatRunOwnership()): Started {
        val reservation = requireNotNull(ownership.reserveStart())
        val owner = Any()
        assertTrue(ownership.activate(reservation, owner))
        return Started(ownership, owner, reservation.runId)
    }

    // ------------------------------------------------------------ 1. repeated cancellation

    @Test
    fun `repeated cancelCurrent never reports a second owner to release`() {
        val started = started()

        val first = started.ownership.cancelCurrent()
        val second = started.ownership.cancelCurrent()

        assertSame(started.owner, first.owner)
        assertNull("the resource must not be handed out twice for release", second.owner)
        assertFalse(second.pendingCancelled)
        assertFalse("a second cancel is not a cancellation", second.cancelled)
    }

    @Test
    fun `every cancelCurrent invalidates, so the epoch never moves backwards`() {
        // NOTE: cancelCurrent() advances the epoch unconditionally -- including when nothing is
        // owned. This test pins the observable rule (monotonic, always invalidating) rather than an
        // assumption that an idle cancel is a no-op; see the report for why that distinction matters.
        val ownership = ChatRunOwnership()
        val idleCancel = ownership.cancelCurrent()
        val firstStarted = started(ownership)
        val cancelled = ownership.cancelCurrent()

        assertTrue("an idle cancel still invalidates whatever held the previous epoch",
            idleCancel.invalidatedRunId > 0L)
        assertTrue(cancelled.invalidatedRunId > idleCancel.invalidatedRunId)
        assertFalse("the cancelled run is no longer current",
            firstStarted.ownership.isCurrent(firstStarted.runId))
    }

    // ------------------------------------------------------------ 2. cancel, then start anew

    @Test
    fun `the whole lifecycle works again for the run started after a cancellation`() {
        val ownership = ChatRunOwnership()
        val cancelled = started(ownership)
        ownership.cancelCurrent()

        val reservation = requireNotNull(ownership.reserveStart())
        val owner = Any()
        assertTrue("a new run may activate after a cancellation",
            ownership.activate(reservation, owner))
        assertTrue("and advance its phase",
            ownership.markPhase(reservation.runId, owner, UiGenerationRuntimePhase.LOCAL_ACTIVE))

        assertTrue(ownership.isCurrent(reservation.runId))
        assertFalse("the cancelled run must not be current any more",
            ownership.isCurrent(cancelled.runId))
        assertFalse(ownership.matches(cancelled.runId))
        assertTrue(ownership.matches(reservation.runId))
    }

    @Test
    fun `an unbound run id stays current across a cancellation`() {
        val ownership = ChatRunOwnership()
        started(ownership)

        ownership.cancelCurrent()

        assertTrue("null means unbound and is always current",
            ownership.isCurrent(null))
        assertFalse("the strict form refuses an unbound id", ownership.matches(null))
    }

    // ------------------------------------------------------------ 3. late callbacks of an old run

    @Test
    fun `a late markPhase for the old run cannot touch the new one`() {
        val ownership = ChatRunOwnership()
        val old = started(ownership)
        assertTrue(ownership.cancelCurrent().cancelled)

        val reservation = requireNotNull(ownership.reserveStart())
        val newOwner = Any()
        assertTrue(ownership.activate(reservation, newOwner))

        assertFalse("the old run may not re-enter an active phase",
            ownership.markPhase(old.runId, old.owner, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        assertFalse("nor may it drive the new run's phase",
            ownership.markPhase(reservation.runId, old.owner, UiGenerationRuntimePhase.TERMINAL))
        assertTrue("the new owner still advances its own phase",
            ownership.markPhase(reservation.runId, newOwner, UiGenerationRuntimePhase.CLOUD_ACTIVE))
    }

    @Test
    fun `a late finish for the old run cannot retire the new owner`() {
        val ownership = ChatRunOwnership()
        val old = started(ownership)
        ownership.cancelCurrent()
        val reservation = requireNotNull(ownership.reserveStart())
        val newOwner = Any()
        assertTrue(ownership.activate(reservation, newOwner))

        ownership.finish(old.runId, old.owner)

        assertSame("the new run still owns the UI after the stale finish",
            newOwner, ownership.cancelCurrent().owner)
    }

    @Test
    fun `a late cancelPending for a superseded reservation is refused`() {
        val ownership = ChatRunOwnership()
        val superseded = requireNotNull(ownership.reserveStart())
        val current = requireNotNull(ownership.reserveStart())

        assertFalse(ownership.cancelPending(superseded))
        assertTrue("the live reservation is untouched and still activatable",
            ownership.activate(current, Any()))
    }

    @Test
    fun `an equal but distinct owner instance cannot act on the live run`() {
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStart())
        val owner = mutableListOf("owner")
        assertTrue(ownership.activate(reservation, owner))

        val equalButDistinct = mutableListOf("owner")
        assertEquals(owner, equalButDistinct)
        assertFalse("identity, not equality, decides who may advance the run",
            ownership.markPhase(reservation.runId, equalButDistinct, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        ownership.finish(reservation.runId, equalButDistinct)
        assertSame("and a value-equal owner cannot retire it either",
            owner, ownership.cancelCurrent().owner)
    }

    // ------------------------------------------------------------ 4. background / foreground

    @Test
    fun `background refuses new reservations while the owned run still advances`() {
        val started = started()

        val backgrounded = started.ownership.background()

        assertFalse("backgrounding is not a cancellation", backgrounded.cancelled)
        assertNull(started.ownership.reserveStart())
        assertTrue("the owned run keeps advancing while backgrounded",
            started.ownership.markPhase(
                started.runId, started.owner, UiGenerationRuntimePhase.LOCAL_ACTIVE
            ))
        assertTrue("and it is still current", started.ownership.isCurrent(started.runId))
    }

    @Test
    fun `background never advances the epoch and repeated toggling leaks nothing`() {
        val ownership = ChatRunOwnership()
        val started = started(ownership)
        val epochBefore = ownership.currentRunId()

        repeat(5) {
            val backgrounded = ownership.background()
            assertEquals("backgrounding must not invalidate a run",
                epochBefore, backgrounded.invalidatedRunId)
            ownership.foreground()
        }

        assertEquals("toggling must not move the epoch", epochBefore, ownership.currentRunId())
        assertTrue("the run survived the toggling", ownership.isCurrent(started.runId))
        val next = requireNotNull(ownership.reserveStart())
        assertTrue("and admission still works afterwards", ownership.activate(next, Any()))
    }

    @Test
    fun `foreground restores admission after a background only period`() {
        val ownership = ChatRunOwnership()
        ownership.background()
        assertNull(ownership.reserveStart())

        ownership.foreground()

        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, Any()))
    }

    // ------------------------------------------------------------ 6. finish is terminal

    @Test
    fun `finish is terminal for that run`() {
        val started = started()
        assertTrue(started.ownership.markPhase(
            started.runId, started.owner, UiGenerationRuntimePhase.LOCAL_ACTIVE
        ))

        started.ownership.finish(started.runId, started.owner)

        started.ownership.finish(started.runId, started.owner)
        assertFalse("a finished run cannot be re-activated into a phase",
            started.ownership.markPhase(
                started.runId, started.owner, UiGenerationRuntimePhase.CLOUD_ACTIVE
            ))
        assertNull("and nothing is left to cancel", started.ownership.cancelCurrent().owner)
    }

    // ------------------------------------------------------------ 5. with the stream accumulator

    @Test
    fun `a publication buffered by the previous run never lands after a cancellation`() = runBlocking {
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, Any()))
        val commits = mutableListOf<ChatStreamOutputBatch>()
        val accumulator = ChatStreamAccumulator(
            scope = this,
            currentRunId = { ownership.currentRunId() },
            activeSessionId = { "session" },
            hasAssistantMessage = { true },
            commit = { batch, _ -> commits += batch },
            now = { 0L }
        )

        // Small delta at clock 0: deferred, not published, so a publish job is scheduled.
        accumulator.append("stale-output", generationRunId = reservation.runId)
        ownership.cancelCurrent()

        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)

        assertTrue("a deferred publication from a cancelled run must never commit",
            commits.isEmpty())
    }

    @Test
    fun `a publication buffered by the live run does land`() = runBlocking {
        // Positive control: without it the test above could pass for the wrong reason.
        val ownership = ChatRunOwnership()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, Any()))
        val commits = mutableListOf<ChatStreamOutputBatch>()
        val accumulator = ChatStreamAccumulator(
            scope = this,
            currentRunId = { ownership.currentRunId() },
            activeSessionId = { "session" },
            hasAssistantMessage = { true },
            commit = { batch, _ -> commits += batch },
            now = { 0L }
        )

        accumulator.append("live-output", generationRunId = reservation.runId)
        delay(CHAT_STREAM_PUBLISH_INTERVAL_MS * 3)

        assertEquals(1, commits.size)
        assertEquals("live-output", commits[0].content)
    }
}
