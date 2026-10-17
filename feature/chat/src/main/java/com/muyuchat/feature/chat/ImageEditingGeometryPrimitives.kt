package com.muyuchat.feature.chat

import java.util.Collections
import kotlin.math.abs
import kotlin.math.round

internal fun <T> immutableSnapshot(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Removes only floating-point noise around an exact pixel boundary. */
internal fun stablePixelBoundary(normalized: Double, extent: Int): Double {
    val scaled = normalized * extent.toDouble()
    val nearestInteger = round(scaled)
    val boundaryTolerance = Math.ulp(scaled) * PIXEL_BOUNDARY_ULPS
    return if (abs(scaled - nearestInteger) <= boundaryTolerance) nearestInteger else scaled
}

internal tailrec fun greatestCommonDivisor(first: Int, second: Int): Int =
    if (second == 0) abs(first) else greatestCommonDivisor(second, first % second)

private const val PIXEL_BOUNDARY_ULPS: Double = 4.0
