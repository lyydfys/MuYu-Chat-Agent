package com.muyuchat.core.dsh.a11y.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM behaviour tests for the virtual-finger state owner behind `/touch-status`. */
class A11yTouchTrackerTest {

    private var clock = 1_000L
    private val tracker = A11yTouchTracker { clock }
    private val geometry = A11yScreenGeometry(widthPx = 1080, heightPx = 1920)

    @Test
    fun reportDescribesHeldFingersWithFractionsAndElapsedTime() {
        val planned = tracker.planAndApply(
            listOf(A11yStrokeOp.Down(0, A11yCoord.Pixels(540, 960))),
            geometry
        )
        assertTrue(planned is A11yPlanResult.Planned)

        clock += 1_500L
        val report = tracker.report(geometry)
        assertEquals(1080, report.screenW)
        assertEquals(1920, report.screenH)
        assertEquals(A11yLimits.MAX_FINGERS, report.maxFingers)
        assertEquals(A11yLimits.HOLD_TIMEOUT_MILLIS, report.holdTimeoutMs)
        val held = report.held.single()
        assertEquals(0, held.finger)
        assertEquals(540, held.x)
        assertEquals(960, held.y)
        assertEquals(0.5, held.fx, 0.0001)
        assertEquals(0.5, held.fy, 0.0001)
        assertEquals(1_500L, held.elapsedMs)
    }

    @Test
    fun rejectedPlanDoesNotTouchTheHeldState() {
        tracker.planAndApply(listOf(A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 10))), geometry)
        val rejected = tracker.planAndApply(
            listOf(A11yStrokeOp.Move(1, A11yCoord.Pixels(20, 20), 100)),
            geometry
        )
        assertTrue(rejected is A11yPlanResult.Rejected)
        assertEquals(1, tracker.report(geometry).held.size)
        assertEquals(0, tracker.current().single().finger)
    }

    @Test
    fun successfulUpClearsTheHeldFinger() {
        tracker.planAndApply(listOf(A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 10))), geometry)
        tracker.planAndApply(listOf(A11yStrokeOp.Up(0, null, 100)), geometry)
        assertTrue(tracker.report(geometry).held.isEmpty())
    }

    @Test
    fun releaseAllReturnsTheStatesThatStillNeedLifting() {
        tracker.planAndApply(
            listOf(
                A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 10)),
                A11yStrokeOp.Down(1, A11yCoord.Pixels(20, 20))
            ),
            geometry
        )
        val released = tracker.releaseAll()
        assertEquals(listOf(0, 1), released.map { it.finger })
        assertTrue(tracker.report(geometry).held.isEmpty())
    }

    @Test
    fun releaseExpiredKeepsFreshFingers() {
        clock = 10_000L
        tracker.planAndApply(listOf(A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 10))), geometry)
        val expired = tracker.releaseExpired()
        assertTrue(expired.isEmpty())

        clock += A11yLimits.HOLD_TIMEOUT_MILLIS + 1
        val late = tracker.releaseExpired()
        assertEquals(1, late.size)
        assertTrue(tracker.report(geometry).held.isEmpty())
    }
}
