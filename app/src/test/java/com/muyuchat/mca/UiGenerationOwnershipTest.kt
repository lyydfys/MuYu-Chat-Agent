package com.muyuchat.mca

import java.util.concurrent.atomic.AtomicLong
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
    fun backgroundInvalidatesAPendingRegenerationBeforeItsRoomCallback() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val pending = requireNotNull(ownership.reserveStart())

        val background = ownership.background()

        assertTrue(background.pendingCancelled)
        assertNull(background.owner)
        assertTrue(sequence.get() > pending.runId)
        assertFalse(ownership.activate(pending, Any()))
    }

    @Test
    fun oldBackgroundActionCannotReleaseOrCancelAReplacementEpoch() {
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
        assertSame(oldOwner, background.owner)
        assertTrue(background.stopLocalRuntime)

        ownership.foreground()
        val replacementOwner = Any()
        val replacement = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(replacement, replacementOwner))
        ownership.finish(oldReservation.runId, oldOwner)

        val current = ownership.cancelCurrent()
        assertSame(replacementOwner, current.owner)
        assertFalse(current.stopLocalRuntime)
    }

    @Test
    fun replacementReservationInvalidatesTheBackgroundFinalizationEpoch() {
        val sequence = AtomicLong(0L)
        val ownership = UiGenerationOwnership(sequence)
        val oldOwner = Any()
        val oldReservation = requireNotNull(ownership.reserveStart())
        assertTrue(ownership.activate(oldReservation, oldOwner))

        val background = ownership.background()
        assertTrue(sequence.get() == background.invalidatedRunId)

        ownership.foreground()
        val replacement = requireNotNull(ownership.reserveStart())

        assertTrue(replacement.runId > background.invalidatedRunId)
        assertFalse(sequence.get() == background.invalidatedRunId)
    }
}
