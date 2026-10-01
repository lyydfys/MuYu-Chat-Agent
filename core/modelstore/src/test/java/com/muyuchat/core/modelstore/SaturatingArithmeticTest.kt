package com.muyuchat.core.modelstore

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the saturating arithmetic that both memory estimates in this file rely on.
 *
 * The identical pair existed twice, once in a private companion object and once in
 * QairtBundleRiskAnalyzer, and is now one file-level helper. The clamping direction is what
 * matters: these feed KV cache and tensor byte estimates, so a wrap would turn an oversized
 * estimate into a small plausible-looking one. For a risk check that is the dangerous
 * direction, because it reads as "this bundle fits".
 */
class SaturatingArithmeticTest {

    @Test
    fun `ordinary values are multiplied normally`() {
        assertEquals(6L, saturatingMultiply(2L, 3L))
        assertEquals(1_024L * 1_024L, saturatingMultiply(1_024L, 1_024L))
    }

    @Test
    fun `multiplication clamps instead of wrapping`() {
        assertEquals(Long.MAX_VALUE, saturatingMultiply(Long.MAX_VALUE, 2L))
        assertEquals(Long.MAX_VALUE, saturatingMultiply(Long.MAX_VALUE / 2L + 1L, 2L))
    }

    @Test
    fun `negative inputs are not guarded, because none reach this function`() {
        // Measured rather than assumed: with left = -1 and right = MAX, the overflow test is
        // `-1 > MAX / MAX` which is `-1 > 1` and false, so this returns -MAX rather than the
        // clamped maximum. Both original copies behaved this way and the hoisted helper is
        // unchanged.
        //
        // Left alone on purpose: every caller passes byte counts built from tensor dimensions
        // and dtypes, so a negative operand would be a bug upstream, and inventing a clamp here
        // would hide it. Pinned so that a later reader does not mistake the result for a
        // wrapping bug in the hoist.
        assertEquals(-Long.MAX_VALUE, saturatingMultiply(-1L, Long.MAX_VALUE))
    }

    @Test
    fun `multiplication by zero is zero, not the clamped maximum`() {
        // The zero check comes first on purpose: without it, 0 * MAX would divide by zero in
        // the overflow test.
        assertEquals(0L, saturatingMultiply(0L, Long.MAX_VALUE))
        assertEquals(0L, saturatingMultiply(Long.MAX_VALUE, 0L))
    }

    @Test
    fun `addition clamps instead of wrapping`() {
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE / 2L + 1L, Long.MAX_VALUE / 2L + 1L))
    }

    @Test
    fun `ordinary values are added normally`() {
        assertEquals(7L, saturatingAdd(3L, 4L))
        assertEquals(Long.MAX_VALUE - 1L, saturatingAdd(Long.MAX_VALUE - 3L, 2L))
    }
}
