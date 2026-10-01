package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the saturating add shared by the context window budget and the prefix cache budget.
 *
 * The two previous copies were identical, so this does not cover behaviour that was untested
 * before. It exists because both callers size a budget, and a wrap would turn an oversized
 * total into a small plausible-looking one -- the failure would look like "it fits".
 */
class SaturatingArithmeticTest {

    @Test
    fun `ordinary values add normally`() {
        assertEquals(7L, saturatedAdd(3L, 4L))
        assertEquals(0L, saturatedAdd(0L, 0L))
        assertEquals(Long.MAX_VALUE - 1L, saturatedAdd(Long.MAX_VALUE - 3L, 2L))
    }

    @Test
    fun `a positive overflow clamps instead of wrapping`() {
        assertEquals(Long.MAX_VALUE, saturatedAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, saturatedAdd(Long.MAX_VALUE / 2L + 1L, Long.MAX_VALUE / 2L + 1L))
    }

    @Test
    fun `a negative right operand is an ordinary subtraction`() {
        // This is what the `right > 0L` guard is for. Without it, MAX - right would overflow
        // positive, the comparison would turn true, and this would clamp to MAX instead of
        // subtracting -- turning a budget reduction into the largest possible budget.
        assertEquals(5L, saturatedAdd(10L, -5L))
        assertEquals(0L, saturatedAdd(5L, -5L))
    }

    @Test
    fun `a negative right operand does not clamp`() {
        // Stated directly, without the arithmetic gymnastics: subtracting from MAX stays MAX.
        assertEquals(Long.MAX_VALUE - 1L, saturatedAdd(Long.MAX_VALUE, -1L))
        assertEquals(0L, saturatedAdd(Long.MAX_VALUE, -Long.MAX_VALUE))
    }
}
