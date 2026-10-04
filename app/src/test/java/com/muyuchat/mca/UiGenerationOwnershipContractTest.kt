package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [UiGenerationOwnership] -- the single-owner lease for UI generation.
 *
 * These pin the **epoch rules** that make a delayed Room callback or a lifecycle stop act only on
 * the exact UI request that created it. Every assertion below comes from a rule visible in the
 * production source; none of them is an implementation mirror:
 *
 *  - a reservation is only activatable while it is the current pending run and the epoch still
 *    equals it ([UiGenerationOwnership.activate]);
 *  - phase transitions and completion are bound to **run id + owner identity**, so a stale
 *    completion cannot clear a newer run ([markPhase], [finish]);
 *  - process backgrounding is explicitly **not** a cancellation ([background]);
 *  - `cancelCurrent` reports exactly which resource, if any, must be stopped.
 *
 * The class takes its epoch counter by injection, so the whole state machine is testable on a
 * plain JVM with no Android, no clock and no coroutines.
 */
class UiGenerationOwnershipContractTest {

    private fun ownership() = UiGenerationOwnership(AtomicLong())

    // --------------------------------------------------------------- reserve / activate

    @Test
    fun `reserveStart advances the epoch and reports the owner it superseded`() {
        val ownership = ownership()
        val first = requireNotNull(ownership.reserveStart())
        val owner = Any()
        assertTrue(ownership.activate(first, owner))

        val second = requireNotNull(ownership.reserveStart())
        assertTrue("a new reservation must advance the run id", second.runId > first.runId)
        assertSame("the superseded owner is handed back so its resource can be released",
            owner, second.supersededOwner)
    }

    @Test
    fun `backgrounding prevents new reservations without consuming an epoch`() {
        val sequence = AtomicLong()
        val ownership = UiGenerationOwnership(sequence)
        ownership.background()
        val before = sequence.get()

        assertNull("no new UI generation may start while backgrounded", ownership.reserveStart())
        assertEquals("a refused reservation must not consume an epoch", before, sequence.get())
    }

    @Test
    fun `a superseded reservation can never activate`() {
        val ownership = ownership()
        val stale = requireNotNull(ownership.reserveStart())
        val current = requireNotNull(ownership.reserveStart())

        assertFalse("stale reservation must not enter the engine",
            ownership.activate(stale, Any()))
        assertTrue(ownership.activate(current, Any()))
    }

    @Test
    fun `a reservation cannot activate twice`() {
        val ownership = ownership()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, Any()))
        assertFalse("activation clears pending, so a replayed activation is stale",
            ownership.activate(reservation, Any()))
    }

    @Test
    fun `backgrounding during the hand-off window does not block activation`() {
        // The production comment is explicit: a request admitted while the UI was visible must
        // still be able to enter the engine if the process moves to the background before it
        // activates. This is the regression guard for that rule.
        val ownership = ownership()
        val reservation = requireNotNull(ownership.reserveStart())
        ownership.background()

        assertTrue(ownership.activate(reservation, Any()))
    }

    // --------------------------------------------------------------- phase transitions

    @Test
    fun `markPhase is bound to run id and owner identity`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        assertTrue(ownership.markPhase(reservation.runId, owner, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        assertFalse("a different run id must not change this run's phase",
            ownership.markPhase(reservation.runId + 1, owner, UiGenerationRuntimePhase.CLOUD_ACTIVE))
    }

    @Test
    fun `markPhase compares the owner by identity not equality`() {
        val ownership = ownership()
        val owner = mutableListOf("owner")
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        val equalButDistinct = mutableListOf("owner")
        assertEquals("precondition: the two owners are equal", owner, equalButDistinct)
        assertFalse("identity, not equality, decides ownership",
            ownership.markPhase(reservation.runId, equalButDistinct, UiGenerationRuntimePhase.LOCAL_ACTIVE))
        assertTrue(ownership.markPhase(reservation.runId, owner, UiGenerationRuntimePhase.LOCAL_ACTIVE))
    }

    @Test
    fun `a stale finish cannot clear a newer run`() {
        val ownership = ownership()
        val firstOwner = Any()
        val first = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(first, firstOwner))

        val secondOwner = Any()
        val second = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(second, secondOwner))

        ownership.finish(first.runId, firstOwner)

        assertTrue("the newer run must still own the engine after a stale finish",
            ownership.markPhase(second.runId, secondOwner, UiGenerationRuntimePhase.CLOUD_ACTIVE))
        val cancellation = ownership.cancelCurrent()
        assertSame(secondOwner, cancellation.owner)
    }

    @Test
    fun `finish with a stale run id leaves the current run owned`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        ownership.finish(reservation.runId + 1, owner)

        assertSame(owner, ownership.cancelCurrent().owner)
    }

    @Test
    fun `finish with a different owner instance leaves the current run owned`() {
        val ownership = ownership()
        val owner = mutableListOf("owner")
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        ownership.finish(reservation.runId, mutableListOf("owner"))

        assertSame(owner, ownership.cancelCurrent().owner)
    }

    @Test
    fun `finish on the matching run and owner releases ownership`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        ownership.finish(reservation.runId, owner)

        val cancellation = ownership.cancelCurrent()
        assertNull("nothing owns the engine after a matching finish", cancellation.owner)
        assertFalse(cancellation.cancelled)
    }

    // --------------------------------------------------------------- cancellation

    @Test
    fun `cancelPending invalidates the reservation it cancels`() {
        val ownership = ownership()
        val reservation = requireNotNull(ownership.reserveStart())

        assertTrue(ownership.cancelPending(reservation))
        assertFalse("a cancelled reservation must not activate",
            ownership.activate(reservation, Any()))
    }

    @Test
    fun `cancelPending refuses a reservation that is no longer pending`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        assertFalse("an activated run is no longer pending",
            ownership.cancelPending(reservation))
        assertSame("and it still owns the engine", owner, ownership.cancelCurrent().owner)
    }

    @Test
    fun `cancelPending refuses a superseded reservation`() {
        val ownership = ownership()
        val stale = requireNotNull(ownership.reserveStart())
        requireNotNull(ownership.reserveStart())

        assertFalse("only the current pending run can be cancelled",
            ownership.cancelPending(stale))
    }

    @Test
    fun `cancelCurrent reports the owner and invalidates that run`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        val cancellation = ownership.cancelCurrent()

        assertSame(owner, cancellation.owner)
        assertFalse(cancellation.pendingCancelled)
        assertTrue("the run id is invalidated, so late callbacks are stale",
            cancellation.invalidatedRunId > reservation.runId)
        assertTrue(cancellation.cancelled)
    }

    @Test
    fun `cancelCurrent reports a pending run that never activated`() {
        val ownership = ownership()
        requireNotNull(ownership.reserveStart())

        val cancellation = ownership.cancelCurrent()

        assertNull(cancellation.owner)
        assertTrue(cancellation.pendingCancelled)
        assertTrue(cancellation.cancelled)
    }

    @Test
    fun `cancelCurrent is idempotent and never reports a second release`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        assertTrue(ownership.cancelCurrent().cancelled)

        val second = ownership.cancelCurrent()
        assertNull("a second cancel must not report the same owner again", second.owner)
        assertFalse(second.pendingCancelled)
        assertFalse("and it is not a cancellation at all", second.cancelled)
    }

    @Test
    fun `cancelCurrent on an idle owner is not a cancellation`() {
        val cancellation = ownership().cancelCurrent()

        assertNull(cancellation.owner)
        assertFalse(cancellation.pendingCancelled)
        assertFalse(cancellation.cancelled)
    }

    @Test
    fun `stopLocalRuntime is only requested while the local runtime is the active phase`() {
        for (phase in enumValues<UiGenerationRuntimePhase>()) {
            val ownership = ownership()
            val owner = Any()
            val reservation = requireNotNull(ownership.reserveStart())
            assertTrue(ownership.activate(reservation, owner))
            assertTrue(ownership.markPhase(reservation.runId, owner, phase))

            val cancellation = ownership.cancelCurrent()

            assertEquals("stopLocalRuntime must track the LOCAL_ACTIVE phase only",
                phase == UiGenerationRuntimePhase.LOCAL_ACTIVE, cancellation.stopLocalRuntime)
        }
    }

    // --------------------------------------------------------------- background / foreground

    @Test
    fun `background is not a cancellation and keeps ownership intact`() {
        val ownership = ownership()
        val owner = Any()
        val reservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(reservation, owner))

        val backgrounded = ownership.background()

        assertNull("backgrounding must not claim an owner to cancel", backgrounded.owner)
        assertFalse(backgrounded.pendingCancelled)
        assertFalse(backgrounded.stopLocalRuntime)
        assertFalse("backgrounding is not a cancellation", backgrounded.cancelled)
        assertSame("the run still owns the engine", owner, ownership.cancelCurrent().owner)
    }

    @Test
    fun `background leaves a pending reservation activatable and foreground restores admission`() {
        val ownership = ownership()
        val owner = Any()
        val pending = requireNotNull(ownership.reserveStart())

        val backgrounded = ownership.background()
        assertFalse("backgrounding must not cancel the pending run", backgrounded.pendingCancelled)
        assertTrue("a run admitted while visible may still enter the engine after backgrounding",
            ownership.activate(pending, owner))

        ownership.foreground()
        assertNotNull("foreground restores admission", ownership.reserveStart())
    }

    @Test
    fun `an explicit cancellation stays authoritative across a background round trip`() {
        val ownership = ownership()
        val reservation = requireNotNull(ownership.reserveStart())
        ownership.background()
        ownership.foreground()

        assertTrue("the run survived backgrounding, so it is still cancellable",
            ownership.cancelPending(reservation))
        assertFalse("and once cancelled it can never activate",
            ownership.activate(reservation, Any()))
    }

    @Test
    fun `an independent lock instance does not share ownership`() {
        // Two owners must never both believe they own the runtime.
        val first = ownership()
        val second = ownership()
        val firstOwner = Any()
        val firstReservation = requireNotNull(first.reserveStart())
        val secondReservation = requireNotNull(second.reserveStart())

        assertTrue(first.activate(firstReservation, firstOwner))
        assertTrue(second.activate(secondReservation, Any()))
        assertSame(firstOwner, first.cancelCurrent().owner)
    }
}
