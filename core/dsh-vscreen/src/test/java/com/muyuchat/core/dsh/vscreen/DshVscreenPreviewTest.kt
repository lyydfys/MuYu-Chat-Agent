package com.muyuchat.core.dsh.vscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Preview relay: bounded in-process frame, window forwarding, clear-on-stop. */
class DshVscreenPreviewTest {

    private class RecordingWindow(
        override val available: Boolean = true,
        private val throwOnRender: Boolean = false
    ) : DshVscreenPreviewWindow {
        var shown: Triple<Int, Int, Int>? = null
        var renders: Int = 0
        var hides: Int = 0

        override fun show(displayId: Int, frameWidth: Int, frameHeight: Int) {
            shown = Triple(displayId, frameWidth, frameHeight)
        }

        override fun render(frame: DshVscreenFrame) {
            if (throwOnRender) throw IllegalStateException("window died")
            renders += 1
        }

        override fun hide() {
            hides += 1
        }
    }

    @Test
    fun aDeliveredFrameIsKeptAndForwardedToTheWindow() {
        val window = RecordingWindow()
        val relay = DshVscreenPreviewRelay(window = window, clock = { 1_500L })

        relay.onFrame(displayId = 7, frame = testFrame(width = 210, height = 420), deliveredAtMillis = 1_200L)

        val state = relay.state()
        assertTrue(state.hasFrame)
        assertEquals(7, state.displayId)
        assertEquals(210, state.width)
        assertEquals(420, state.height)
        assertEquals(1L, state.deliveredFrames)
        assertEquals(0L, state.droppedFrames)
        assertEquals(300L, state.ageMillis)
        assertTrue(state.windowAvailable)
        assertEquals(Triple(7, 210, 420), window.shown)
        assertEquals(1, window.renders)
        assertTrue(relay.latest() != null)
    }

    @Test
    fun anOversizedOrEmptyFrameIsDroppedInsteadOfShown() {
        val window = RecordingWindow()
        val relay = DshVscreenPreviewRelay(window = window, maxFrameBytes = 16, clock = { 0L })

        relay.onFrame(7, testFrame(sizeBytes = 32), 0L)
        relay.onFrame(7, testFrame(sizeBytes = 0), 0L)

        val state = relay.state()
        assertFalse(state.hasFrame)
        assertEquals(0L, state.deliveredFrames)
        assertEquals(2L, state.droppedFrames)
        assertNull(window.shown)
        assertNull(relay.latest())
    }

    @Test
    fun stoppingTheDisplayClearsTheFrameAndHidesTheWindow() {
        val window = RecordingWindow()
        val relay = DshVscreenPreviewRelay(window = window, clock = { 0L })
        relay.onFrame(7, testFrame(), 0L)

        relay.onDisplayStopped(displayId = 7, reason = "closed")

        assertFalse(relay.state().hasFrame)
        assertNull(relay.latest())
        assertEquals("closed", relay.state().lastStopReason)
        assertEquals(1, window.hides)
    }

    @Test
    fun stoppingAnotherDisplayDoesNotClearTheActiveOne() {
        val relay = DshVscreenPreviewRelay(window = RecordingWindow(), clock = { 0L })
        relay.onFrame(7, testFrame(), 0L)

        relay.onDisplayStopped(displayId = 9, reason = "stale")

        assertTrue(relay.state().hasFrame)
        assertEquals(7, relay.state().displayId)
    }

    @Test
    fun anUnavailableWindowKeepsTheFrameWithoutTryingToRender() {
        val window = RecordingWindow(available = false)
        val relay = DshVscreenPreviewRelay(window = window, clock = { 0L })

        relay.onFrame(7, testFrame(), 0L)

        assertTrue(relay.state().hasFrame)
        assertNull(window.shown)
        assertEquals(0, window.renders)
        assertFalse(relay.state().windowAvailable)
    }

    @Test
    fun aWindowFailureNeverFailsTheCapturePath() {
        val window = RecordingWindow(throwOnRender = true)
        val relay = DshVscreenPreviewRelay(window = window, clock = { 0L })

        relay.onFrame(7, testFrame(), 0L)

        assertTrue(relay.state().hasFrame)
        assertEquals(1L, relay.state().deliveredFrames)
    }
}
