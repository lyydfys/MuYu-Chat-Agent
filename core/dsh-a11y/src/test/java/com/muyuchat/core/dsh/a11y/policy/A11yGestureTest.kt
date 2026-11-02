package com.muyuchat.core.dsh.a11y.policy

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM behaviour tests for the `/gesture` decoder and the stroke/timeline planner. */
class A11yGestureTest {

    private val geometry = A11yScreenGeometry(widthPx = 1080, heightPx = 1920)
    private val now = 1_000_000L

    // ------------------------------------------------------------------ codec

    @Test
    fun codecAppliesTheReferenceDefaults() {
        val parsed = parseOps(
            """[
                {"kind":"tap","x":10,"y":20},
                {"kind":"swipe","x":1,"y":2,"x2":3,"y2":4},
                {"kind":"hold","fx":0.5,"fy":0.5},
                {"kind":"move","finger":0,"x":5,"y":6},
                {"kind":"up","finger":0},
                {"kind":"wait","ms":250}
            ]"""
        )
        assertEquals(A11yLimits.DEFAULT_TAP_MILLIS, (parsed[0] as A11yStrokeOp.Tap).durationMs)
        assertEquals(A11yLimits.DEFAULT_SWIPE_MILLIS, (parsed[1] as A11yStrokeOp.Swipe).durationMs)
        assertEquals(A11yLimits.DEFAULT_HOLD_MILLIS, (parsed[2] as A11yStrokeOp.Hold).durationMs)
        assertEquals(-1, parsed[0].finger)
        assertEquals(0, parsed[3].finger)
        assertEquals(250L, (parsed[5] as A11yStrokeOp.Wait).durationMs)
    }

    @Test
    fun codecRejectsMalformedGestureBodies() {
        assertRejected("")
        assertRejected("{not json")
        assertRejected("[]")
        assertRejected("""[{"kind":"explode"}]""")
        assertRejected("""[{"kind":"down","x":1,"y":1}]""")
        assertRejected("""[{"kind":"down","finger":8,"x":1,"y":1}]""")
        assertRejected("""[{"kind":"tap","fx":1.5,"fy":0.5}]""")
        assertRejected("""[{"kind":"tap","x":1,"y":2,"durationMs":10001}]""")
        assertRejected("""[{"kind":"wait","ms":-1}]""")
        assertRejected("""[{"kind":"wait","ms":10001}]""")
    }

    @Test
    fun codecAcceptsBothEnvelopeForms() {
        assertEquals(1, parseOps("""[{"kind":"wait","ms":1}]""").size)
        assertEquals(1, parseOps("""{"strokes":[{"kind":"wait","ms":1}]}""").size)
    }

    // ------------------------------------------------------------------ planner

    @Test
    fun tapPlansOneStrokeAndLeavesNoFingerHeld() {
        val plan = planned(
            listOf(A11yStrokeOp.Tap(-1, A11yCoord.Pixels(100, 200), A11yLimits.DEFAULT_TAP_MILLIS))
        )
        assertEquals(A11yLimits.DEFAULT_TAP_MILLIS, plan.totalMs)
        assertEquals(1, plan.strokes.size)
        assertEquals(listOf(A11yPoint(100, 200)), plan.strokes.single().points)
        assertFalse(plan.strokes.single().willContinue)
        assertTrue(plan.heldAfter.isEmpty())
    }

    @Test
    fun downAndTapOnTheSameFingerDragsThenReleases() {
        val plan = planned(
            listOf(
                A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 20)),
                A11yStrokeOp.Tap(0, A11yCoord.Pixels(30, 40), A11yLimits.DEFAULT_TAP_MILLIS)
            )
        )
        assertEquals(100L, plan.totalMs)
        assertEquals(2, plan.strokes.size)
        assertEquals(listOf(A11yPoint(10, 20)), plan.strokes[0].points)
        assertEquals(100L, plan.strokes[0].durationMs)
        assertTrue(plan.strokes[0].willContinue)
        assertEquals(listOf(A11yPoint(10, 20), A11yPoint(30, 40)), plan.strokes[1].points)
        assertEquals(40L, plan.strokes[1].startMs)
        assertFalse(plan.strokes[1].willContinue)
        assertTrue(plan.heldAfter.isEmpty())
    }

    @Test
    fun downKeepsTheFingerHeldForTheNextRequest() {
        val plan = planned(listOf(A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 20))))
        assertEquals(1, plan.heldAfter.size)
        assertEquals(0, plan.heldAfter.single().finger)
        assertEquals(10f, plan.heldAfter.single().x, 0.001f)
        assertEquals(20f, plan.heldAfter.single().y, 0.001f)
        assertEquals(now, plan.heldAfter.single().downAtMillis)
    }

    @Test
    fun twoSimultaneousDownStrokesShareTheTimeline() {
        val plan = planned(
            listOf(
                A11yStrokeOp.Down(0, A11yCoord.Pixels(10, 10)),
                A11yStrokeOp.Down(1, A11yCoord.Pixels(20, 20))
            )
        )
        assertEquals(80L, plan.totalMs)
        assertEquals(2, plan.strokes.size)
        assertEquals(0L, plan.strokes[0].startMs)
        assertEquals(40L, plan.strokes[1].startMs)
        assertEquals(2, plan.heldAfter.size)
    }

    @Test
    fun moveWithoutDownIsRejectedBeforeAnyStateChange() {
        val result = plan(listOf(A11yStrokeOp.Move(0, A11yCoord.Pixels(1, 1), 100)))
        assertTrue(result is A11yPlanResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.INVALID_REQUEST,
            (result as A11yPlanResult.Rejected).rejection.code
        )
    }

    @Test
    fun upWithoutDownIsRejected() {
        val result = plan(listOf(A11yStrokeOp.Up(2, null, 100)))
        assertTrue(result is A11yPlanResult.Rejected)
    }

    @Test
    fun expiredFingerIsRefusedAndReportedAsTimeout() {
        val expired = A11yHeldFingerState(
            finger = 0,
            x = 5f,
            y = 5f,
            downAtMillis = now - A11yLimits.HOLD_TIMEOUT_MILLIS - 1
        )
        val result = A11yGesturePlanner.plan(
            ops = listOf(A11yStrokeOp.Move(0, A11yCoord.Pixels(20, 20), 100)),
            held = listOf(expired),
            geometry = geometry,
            nowMillis = now
        )
        assertTrue(result is A11yPlanResult.Rejected)
        assertEquals(DshCapabilityErrorCode.TIMEOUT, (result as A11yPlanResult.Rejected).rejection.code)
    }

    @Test
    fun heldFingerContinuationStartsAtItsCurrentPosition() {
        val held = A11yHeldFingerState(0, 10f, 10f, now)
        val result = A11yGesturePlanner.plan(
            ops = listOf(A11yStrokeOp.Move(0, A11yCoord.Pixels(200, 300), 100)),
            held = listOf(held),
            geometry = geometry,
            nowMillis = now
        )
        val plan = result as A11yPlanResult.Planned
        assertEquals(1, plan.strokes.size)
        assertEquals(listOf(A11yPoint(10, 10), A11yPoint(200, 300)), plan.strokes.single().points)
        assertTrue(plan.strokes.single().willContinue)
        assertEquals(200f, plan.heldAfter.single().x, 0.001f)
    }

    @Test
    fun swipeOnAHeldFingerIgnoresTheDeclaredStartPoint() {
        val held = A11yHeldFingerState(0, 10f, 10f, now)
        val result = A11yGesturePlanner.plan(
            ops = listOf(
                A11yStrokeOp.Swipe(
                    finger = 0,
                    from = A11yCoord.Pixels(1, 1),
                    to = A11yCoord.Pixels(100, 100),
                    durationMs = 300
                )
            ),
            held = listOf(held),
            geometry = geometry,
            nowMillis = now
        )
        val plan = result as A11yPlanResult.Planned
        assertEquals(listOf(A11yPoint(10, 10), A11yPoint(100, 100)), plan.strokes.single().points)
        assertTrue(plan.heldAfter.isEmpty())
    }

    @Test
    fun waitOnlySequenceHasNoExecutableStroke() {
        val result = plan(listOf(A11yStrokeOp.Wait(100)))
        assertTrue(result is A11yPlanResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.INVALID_REQUEST,
            (result as A11yPlanResult.Rejected).rejection.code
        )
    }

    @Test
    fun totalTimelineIsBounded() {
        val ops = (0 until 7).map { A11yStrokeOp.Wait(A11yLimits.MAX_WAIT_MILLIS) }
        val result = plan(ops)
        assertTrue(result is A11yPlanResult.Rejected)
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, (result as A11yPlanResult.Rejected).rejection.code)
    }

    @Test
    fun expandedStrokeCountIsBounded() {
        val held = listOf(
            A11yHeldFingerState(0, 1f, 1f, now),
            A11yHeldFingerState(1, 2f, 2f, now)
        )
        val taps = (0 until A11yLimits.MAX_GESTURE_STROKES).map { index ->
            A11yStrokeOp.Tap(-1, A11yCoord.Pixels(index + 1, index + 1), 60)
        }
        val result = A11yGesturePlanner.plan(taps, held, geometry, now)
        assertTrue(result is A11yPlanResult.Rejected)
        assertEquals(DshCapabilityErrorCode.TOO_LARGE, (result as A11yPlanResult.Rejected).rejection.code)
    }

    @Test
    fun pixelOutsideTheScreenIsRejected() {
        val result = plan(listOf(A11yStrokeOp.Tap(-1, A11yCoord.Pixels(5_000, 10), 60)))
        assertTrue(result is A11yPlanResult.Rejected)
        assertEquals(
            DshCapabilityErrorCode.INVALID_REQUEST,
            (result as A11yPlanResult.Rejected).rejection.code
        )
    }

    @Test
    fun fractionalCoordinatesResolveAgainstTheLiveGeometry() {
        val plan = planned(
            listOf(A11yStrokeOp.Tap(-1, A11yCoord.Fraction(0.5, 0.5), 60))
        )
        assertEquals(A11yPoint(540, 960), plan.strokes.single().points.single())
    }

    @Test
    fun releaseStrokeLiftsAnExpiredFingerInsteadOfLeavingItDown() {
        val expired = A11yHeldFingerState(
            finger = 0,
            x = 5f,
            y = 5f,
            downAtMillis = now - A11yLimits.HOLD_TIMEOUT_MILLIS - 1
        )
        val result = A11yGesturePlanner.plan(
            ops = listOf(A11yStrokeOp.Tap(-1, A11yCoord.Pixels(20, 20), 60)),
            held = listOf(expired),
            geometry = geometry,
            nowMillis = now
        )
        val plan = result as A11yPlanResult.Planned
        assertTrue(plan.strokes.any { it.points == listOf(A11yPoint(5, 5)) && !it.willContinue })
        assertTrue(plan.heldAfter.isEmpty())
    }

    // ------------------------------------------------------------------ helpers

    private fun plan(ops: List<A11yStrokeOp>): A11yPlanResult =
        A11yGesturePlanner.plan(ops, emptyList(), geometry, now)

    private fun planned(ops: List<A11yStrokeOp>): A11yPlanResult.Planned =
        plan(ops) as A11yPlanResult.Planned

    private fun parseOps(body: String): List<A11yStrokeOp> = when (val parsed = A11yGestureCodec.parse(body)) {
        is A11yGestureParse.Parsed -> parsed.ops
        is A11yGestureParse.Rejected -> throw AssertionError("expected parsed ops: ${parsed.rejection}")
    }

    private fun assertRejected(body: String) {
        assertTrue(
            "expected a rejection for: $body",
            A11yGestureCodec.parse(body) is A11yGestureParse.Rejected
        )
    }
}
