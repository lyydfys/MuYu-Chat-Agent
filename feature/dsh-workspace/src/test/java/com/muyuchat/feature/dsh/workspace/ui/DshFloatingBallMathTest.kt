package com.muyuchat.feature.dsh.workspace.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure drag math of the movable DSH ball.
 *
 * The composable itself needs a device; this covers the only branch that can silently park the ball
 * outside the page or make it immovable on a tiny travel range.
 */
class DshFloatingBallMathTest {

    @Test
    fun `dragging adds the delta relative to the travel range`() {
        assertEquals(0.6f, moveDshBallFraction(0.5f, 100f, 1000f), 0.0001f)
        assertEquals(0.4f, moveDshBallFraction(0.5f, -100f, 1000f), 0.0001f)
    }

    @Test
    fun `dragging clamps to the page edges`() {
        assertEquals(1f, moveDshBallFraction(0.9f, 500f, 1000f), 0.0001f)
        assertEquals(0f, moveDshBallFraction(0.1f, -500f, 1000f), 0.0001f)
    }

    @Test
    fun `a zero or negative travel range keeps the current fraction`() {
        assertEquals(0.5f, moveDshBallFraction(0.5f, 100f, 0f), 0.0001f)
        assertEquals(0.5f, moveDshBallFraction(0.5f, 100f, -10f), 0.0001f)
    }

    @Test
    fun `non finite input never moves the ball outside the page`() {
        assertEquals(0.5f, moveDshBallFraction(0.5f, Float.POSITIVE_INFINITY, 1000f), 0.0001f)
        assertEquals(0.5f, moveDshBallFraction(0.5f, 100f, Float.NaN), 0.0001f)
    }
}
