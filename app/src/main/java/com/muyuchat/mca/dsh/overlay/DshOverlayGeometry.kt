package com.muyuchat.mca.dsh.overlay

import kotlin.math.roundToInt

/**
 * Pixel math for the draggable overlay mark.
 *
 * Pure so the rotation/restore behavior is unit-tested without a device: the window is measured in
 * pixels, but the persisted value is a fraction, and the conversion must never produce an offset
 * outside the usable band even when the mark size, the edge inset or the display size changed
 * between two runs (rotation, split-screen resize, display-size change).
 */
internal object DshOverlayGeometry {

    /** Inset from the screen edge, so the mark never touches the status bar or the cutout. */
    const val EDGE_DP = 8

    /** A finite fraction in `0..1`; NaN and infinities collapse to the left/top edge. */
    fun clampFraction(value: Float): Float =
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    /** Pixels the mark may travel on one axis; never negative, even for a mark wider than the screen. */
    fun travelPx(containerPx: Int, markPx: Int, edgePx: Int): Int =
        (containerPx - markPx - edgePx * 2).coerceAtLeast(0)

    /** Offset from the edge for [fraction], clamped into `0..travelPx`. */
    fun offsetPx(fraction: Float, travelPx: Int): Int {
        if (travelPx <= 0) return 0
        return (clampFraction(fraction) * travelPx)
            .roundToInt()
            .coerceIn(0, travelPx)
    }

    /** Persisted fraction for an absolute offset; a zero travel keeps the origin. */
    fun fractionForOffset(offsetPx: Int, travelPx: Int): Float {
        if (travelPx <= 0) return 0f
        return (offsetPx.toFloat() / travelPx).coerceIn(0f, 1f)
    }

    /**
     * Fraction after a drag delta.
     *
     * A drag that would leave the band is clamped here rather than at the window layer, so the
     * persisted fraction and the visible position can never disagree.
     */
    fun fractionAfterDrag(currentFraction: Float, deltaPx: Float, travelPx: Int): Float {
        if (travelPx <= 0 || !deltaPx.isFinite()) return clampFraction(currentFraction)
        val moved = currentFraction + deltaPx / travelPx
        return if (moved.isFinite()) moved.coerceIn(0f, 1f) else clampFraction(currentFraction)
    }
}
