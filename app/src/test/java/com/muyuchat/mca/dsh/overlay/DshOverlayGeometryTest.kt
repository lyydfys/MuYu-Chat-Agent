package com.muyuchat.mca.dsh.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Position math of the draggable mark.
 *
 * The persisted value is a fraction, but the window is placed in pixels: these cases pin down what
 * happens when the two disagree — a rotation, a split-screen resize, a display-size change or a mark
 * that grew past the usable band.
 */
class DshOverlayGeometryTest {

    @Test
    fun fractionsAreClampedToTheUsableBand() {
        assertEquals(0f, DshOverlayGeometry.clampFraction(-1.5f), 0.0001f)
        assertEquals(1f, DshOverlayGeometry.clampFraction(3f), 0.0001f)
        assertEquals(0.4f, DshOverlayGeometry.clampFraction(0.4f), 0.0001f)
        assertEquals("NaN must not reach the window", 0f, DshOverlayGeometry.clampFraction(Float.NaN), 0.0001f)
        assertEquals(0f, DshOverlayGeometry.clampFraction(Float.NEGATIVE_INFINITY), 0.0001f)
    }

    @Test
    fun travelNeverGoesNegativeWhenTheMarkIsLargerThanTheScreen() {
        assertEquals(0, DshOverlayGeometry.travelPx(containerPx = 200, markPx = 300, edgePx = 8))
        assertEquals(0, DshOverlayGeometry.travelPx(containerPx = 16, markPx = 40, edgePx = 8))
        assertEquals(784, DshOverlayGeometry.travelPx(containerPx = 1_080, markPx = 280, edgePx = 8))
    }

    @Test
    fun offsetAndFractionRoundTripInsideTheBand() {
        val travel = 400

        assertEquals(200, DshOverlayGeometry.offsetPx(0.5f, travel))
        assertEquals(0, DshOverlayGeometry.offsetPx(0f, travel))
        assertEquals(travel, DshOverlayGeometry.offsetPx(1f, travel))
        assertEquals(0.5f, DshOverlayGeometry.fractionForOffset(200, travel), 0.0001f)
        assertEquals(0f, DshOverlayGeometry.fractionForOffset(-20, travel), 0.0001f)
        assertEquals(1f, DshOverlayGeometry.fractionForOffset(9_999, travel), 0.0001f)
    }

    @Test
    fun aZeroTravelKeepsTheMarkAtTheOriginInsteadOfDividingByZero() {
        assertEquals(0, DshOverlayGeometry.offsetPx(0.9f, travelPx = 0))
        assertEquals(0f, DshOverlayGeometry.fractionForOffset(120, travelPx = 0), 0.0001f)
        assertEquals(0.9f, DshOverlayGeometry.fractionAfterDrag(0.9f, 40f, travelPx = 0), 0.0001f)
    }

    @Test
    fun rotationKeepsAStoredFractionInsideTheNewBounds() {
        // Portrait: 1080 px wide, a 56 dp mark at 3x density (168 px), 8 dp edge (24 px).
        val portraitTravel = DshOverlayGeometry.travelPx(1_080, 168, 24)
        val stored = DshOverlayGeometry.fractionForOffset(900, portraitTravel)
        assertEquals(1f, stored, 0.0001f)

        // Landscape is wider, not narrower; the same fraction must still be reachable.
        val landscapeTravel = DshOverlayGeometry.travelPx(2_400, 168, 24)
        val restored = DshOverlayGeometry.offsetPx(stored, landscapeTravel)
        assertTrue(restored >= 0)
        assertTrue(restored <= landscapeTravel)

        // Split-screen halves the width: the old pixel offset is out of range, the fraction is not.
        val splitTravel = DshOverlayGeometry.travelPx(540, 168, 24)
        val splitOffset = DshOverlayGeometry.offsetPx(stored, splitTravel)
        assertTrue(splitOffset >= 0)
        assertTrue(splitOffset <= splitTravel)
    }

    @Test
    fun dragIsClampedToTheBandAndIgnoresBadInput() {
        assertEquals(0f, DshOverlayGeometry.fractionAfterDrag(0.1f, -5_000f, 400), 0.0001f)
        assertEquals(1f, DshOverlayGeometry.fractionAfterDrag(0.9f, 5_000f, 400), 0.0001f)
        assertEquals(0.5f, DshOverlayGeometry.fractionAfterDrag(0.4f, 40f, 400), 0.0001f)
        assertEquals(0.4f, DshOverlayGeometry.fractionAfterDrag(0.4f, Float.NaN, 400), 0.0001f)
        assertEquals(0.4f, DshOverlayGeometry.fractionAfterDrag(0.4f, 40f, -1), 0.0001f)
    }

    @Test
    fun normalizedPositionCollapsesNonFiniteFractions() {
        val normalized = DshOverlayPosition(Float.NaN, 0.75f).normalized()

        assertEquals(0f, normalized.xFraction, 0.0001f)
        assertEquals(0.75f, normalized.yFraction, 0.0001f)
    }
}
