package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UiGenerationOwnershipTest {
    @Test
    fun apiOnlyGenerationCannotClaimUiOwnership() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val apiOwner = Any()

        assertFalse(
            ownership.markPhase(
                runId = sequence.get(),
                owner = apiOwner,
                phase = UiGenerationRuntimePhase.LOCAL_ACTIVE
            )
        )

        val background = ownership.background()
        assertFalse(background.cancelled)
        assertFalse(background.stopLocalRuntime)
        assertNull(background.owner)
    }

    @Test
    fun backgroundKeepsAPendingGenerationEligibleForActivation() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val pending = requireNotNull(ownership.reserveStart())

        val background = ownership.background()

        assertFalse(background.pendingCancelled)
        assertNull(background.owner)
        assertEquals(pending.runId, sequence.get())
        assertTrue(ownership.activate(pending, Any()))
    }

    @Test
    fun explicitCancelStillWinsAfterBackgrounding() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val oldOwner = Any()
        val oldReservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(oldReservation, oldOwner))
        assertTrue(
            ownership.markPhase(
                oldReservation.runId,
                oldOwner,
                UiGenerationRuntimePhase.LOCAL_ACTIVE
            )
        )

        val background = ownership.background()
        assertNull(background.owner)
        assertFalse(background.stopLocalRuntime)

        // The active request remains owned while backgrounded. A user pressing
        // Stop is still an explicit cancellation and must invalidate it.
        val stopped = ownership.cancelCurrent()
        assertSame(oldOwner, stopped.owner)
        assertTrue(stopped.stopLocalRuntime)

        ownership.foreground()
        val replacementOwner = Any()
        val replacement = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(replacement, replacementOwner))
        val current = ownership.cancelCurrent()
        assertSame(replacementOwner, current.owner)
        assertFalse(current.stopLocalRuntime)
    }

    @Test
    fun backgroundDoesNotAdvanceGenerationEpoch() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val oldOwner = Any()
        val oldReservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(oldReservation, oldOwner))

        val background = ownership.background()
        assertTrue(sequence.get() == oldReservation.runId)
        assertTrue(background.invalidatedRunId == oldReservation.runId)

        ownership.foreground()
        val replacement = requireNotNull(ownership.reserveStart())

        assertTrue(replacement.runId > background.invalidatedRunId)
        assertFalse(sequence.get() == background.invalidatedRunId)
    }

    @Test
    fun stopDuringRegenerationPersistenceRejectsLateGenerationStart() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val pending = requireNotNull(ownership.reserveStart())

        val stopped = ownership.cancelCurrent()

        assertTrue(stopped.pendingCancelled)
        assertNull(stopped.owner)
        assertFalse(stopped.stopLocalRuntime)
        assertFalse(ownership.activate(pending, Any()))
        assertTrue(sequence.get() > pending.runId)
    }
}
