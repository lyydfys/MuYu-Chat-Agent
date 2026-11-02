package com.muyuchat.core.dsh.runtime.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the tri-state contract of the in-flight observation.
 *
 * The only value that may be read as "idle" is an observed zero. An unobservable channel makes the
 * whole observation unobservable (a partial sum would be a lie), and channels this process cannot
 * see at all are reported as uncovered instead of being counted as zero.
 */
class DshInFlightSnapshotTest {

    @Test
    fun anObservedZeroIsTheOnlyIdleAnswer() {
        val idle = DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to 0))
        assertEquals(0, idle.observedCalls)
        assertFalse(idle.unobservable)
        assertEquals(listOf(DshInFlightChannel.MODEL_GATEWAY), idle.observedChannels)

        val busy = DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to 3))
        assertEquals(3, busy.observedCalls)
    }

    @Test
    fun anUnobservableChannelMakesTheWholeObservationUnobservable() {
        val snapshot = DshInFlightSnapshot(
            evidence = listOf(
                DshInFlightEvidence.Observed(DshInFlightChannel.MODEL_GATEWAY, 1),
                DshInFlightEvidence.Unobservable(DshInFlightChannel.TOOL_EXECUTION, "bridge closing")
            )
        )
        assertNull(snapshot.observedCalls)
        assertTrue(snapshot.unobservable)
        assertEquals(listOf(DshInFlightChannel.TOOL_EXECUTION), snapshot.unobservableChannels)
        assertTrue(snapshot.detail.contains("tool-execution=unobservable(bridge closing)"))
    }

    @Test
    fun uncoveredChannelsAreReportedButNeverSummed() {
        val snapshot = DshInFlightSnapshot(
            evidence = listOf(DshInFlightEvidence.Observed(DshInFlightChannel.MODEL_GATEWAY, 2)),
            uncovered = listOf(DshInFlightChannel.ACCOUNT_DIRECT)
        )
        assertEquals(2, snapshot.observedCalls)
        assertTrue(snapshot.detail.contains("uncovered=account-direct"))
    }

    @Test
    fun theObservationViewCarriesTheRealCountOrAnHonestNull() {
        val idle = DshInFlightSnapshot.observed(mapOf(DshInFlightChannel.MODEL_GATEWAY to 0))
        runBlocking { assertEquals(0, idle.asObservation().inFlightCalls()) }

        val unknown = DshInFlightSnapshot.unavailable("no gateway generation is listening")
        runBlocking { assertNull(unknown.asObservation().inFlightCalls()) }
        assertTrue(unknown.detail.contains("no gateway generation is listening"))
    }

    @Test
    fun negativeCountsAreRejectedAtConstruction() {
        var rejected = false
        try {
            DshInFlightEvidence.Observed(DshInFlightChannel.MODEL_GATEWAY, -1)
        } catch (expected: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
