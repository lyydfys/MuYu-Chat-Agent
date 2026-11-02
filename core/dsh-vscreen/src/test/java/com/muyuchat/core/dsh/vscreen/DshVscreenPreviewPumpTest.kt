package com.muyuchat.core.dsh.vscreen

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview pump is a low-rate pull over the display handle. Virtual time makes the cadence
 * assertions exact; the fake handle implements the same contract the Android ImageReader handle has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DshVscreenPreviewPumpTest {

    private val geometry = DshVscreenGeometry.canonical(DshVscreenOrientation.PORTRAIT)

    @Test
    fun framesAreDeliveredAtTheConfiguredCadence() = runTest {
        val handle = FakeDisplayHandle(displayId = 7, geometry = geometry)
        handle.frameProvider = { testFrame() }
        val delivered = mutableListOf<DshVscreenFrame>()
        val pump = DshVscreenPreviewPump(
            framePolicy = DshVscreenFramePolicy(),
            intervalMillis = 200L,
            clock = { testScheduler.currentTime }
        )

        val job = pump.start(
            scope = backgroundScope,
            handle = handle,
            stillOwned = { true },
            onFrame = { frame, _ -> delivered += frame }
        )
        advanceTimeBy(650L)
        runCurrent()

        assertEquals(3, delivered.size)
        assertTrue(handle.latestFrameCalls >= 3)
        job.cancel()
    }

    @Test
    fun nothingIsDeliveredAfterTheOwnerStopsOwningTheDisplay() = runTest {
        val handle = FakeDisplayHandle(displayId = 7, geometry = geometry)
        val delivered = mutableListOf<DshVscreenFrame>()
        handle.frameProvider = { testFrame() }
        var owned = true
        val pump = DshVscreenPreviewPump(
            framePolicy = DshVscreenFramePolicy(),
            intervalMillis = 200L,
            clock = { testScheduler.currentTime }
        )

        pump.start(
            scope = backgroundScope,
            handle = handle,
            stillOwned = { owned },
            onFrame = { frame, _ -> delivered += frame }
        )
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(1, delivered.size)

        owned = false
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("a stopped pump must not keep delivering", 1, delivered.size)
    }

    @Test
    fun aFrameOutsideTheBudgetIsNeverDelivered() = runTest {
        val handle = FakeDisplayHandle(displayId = 7, geometry = geometry)
        handle.frameProvider = { testFrame(sizeBytes = 64) }
        val delivered = mutableListOf<DshVscreenFrame>()
        val pump = DshVscreenPreviewPump(
            framePolicy = DshVscreenFramePolicy(maxFrameBytes = 16),
            intervalMillis = 200L,
            clock = { testScheduler.currentTime }
        )

        pump.start(
            scope = backgroundScope,
            handle = handle,
            stillOwned = { true },
            onFrame = { frame, _ -> delivered += frame }
        )
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(delivered.isEmpty())
    }

    @Test
    fun anEmptyReadIsSkippedWithoutEndingThePump() = runTest {
        val handle = FakeDisplayHandle(displayId = 7, geometry = geometry)
        var calls = 0
        handle.frameProvider = {
            calls += 1
            if (calls == 1) null else testFrame()
        }
        val delivered = mutableListOf<DshVscreenFrame>()
        val pump = DshVscreenPreviewPump(
            framePolicy = DshVscreenFramePolicy(),
            intervalMillis = 200L,
            clock = { testScheduler.currentTime }
        )

        pump.start(
            scope = backgroundScope,
            handle = handle,
            stillOwned = { true },
            onFrame = { frame, _ -> delivered += frame }
        )
        advanceTimeBy(650L)
        runCurrent()

        assertEquals(2, delivered.size)
    }

    @Test
    fun anOutOfRangeIntervalIsRejectedAtConstruction() {
        try {
            DshVscreenPreviewPump(framePolicy = DshVscreenFramePolicy(), intervalMillis = 10L)
            throw AssertionError("expected the interval to be rejected")
        } catch (expected: IllegalArgumentException) {
            // Expected branch.
        }
    }
}
