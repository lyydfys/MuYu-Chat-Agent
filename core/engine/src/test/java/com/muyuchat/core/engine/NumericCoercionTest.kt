package com.muyuchat.core.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the integer coercion now that the chat and runtime parameter paths share it.
 *
 * The two previous copies were byte-identical, so this does not cover behaviour that was
 * untested before. It matters because the function decides which supplied parameters take
 * effect: a value it returns null for is dropped and the caller falls back to its default, and
 * the request still succeeds. Nothing surfaces as an error, so the difference between accepting
 * and rejecting a form has to be pinned somewhere.
 */
class NumericCoercionTest {

    @Test
    fun `numbers are narrowed`() {
        assertEquals(512, integerValue(512))
        assertEquals(512, integerValue(512L))
        assertEquals(512, integerValue(512.0))
        // Narrowing, not rounding: this is what toInt() does and both copies did it.
        assertEquals(512, integerValue(512.9))
    }

    @Test
    fun `whole-number strings are accepted, with whitespace trimmed`() {
        assertEquals(512, integerValue("512"))
        assertEquals(512, integerValue("  512  "))
        assertEquals(-1, integerValue("-1"))
    }

    @Test
    fun `strings that are not whole numbers are rejected rather than truncated`() {
        // The reason this matters: "512.5" yields null and the caller keeps its default,
        // instead of quietly generating with 512.
        assertNull(integerValue("512.5"))
        assertNull(integerValue("512abc"))
        assertNull(integerValue(""))
        assertNull(integerValue("   "))
        assertNull(integerValue("0x200"))
        assertNull(integerValue("1e3"))
    }

    @Test
    fun `non-numeric values are rejected`() {
        assertNull(integerValue(null))
        assertNull(integerValue(JSONObject.NULL))
        assertNull(integerValue(true))
        assertNull(integerValue(listOf(512)))
    }

    @Test
    fun `a value too large for an int is rejected`() {
        // toIntOrNull, not toInt: an out-of-range string must not wrap around into a
        // plausible-looking thread count or context size.
        assertNull(integerValue("99999999999999999999"))
    }
}
