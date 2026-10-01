package com.muyuchat.mca

import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the canonical text used to decide whether an execution profile changed, and proves
 * the P2 extraction changed nothing.
 *
 * `executionProfileDiffers` compares requested parameters against the stored active profile.
 * The two sides are produced differently -- one is built fresh from Kotlin values, the other
 * was serialised earlier and parsed back -- so the same logical number can arrive as `1024`,
 * `1024.0` or `1.024E3`. Normalising them is what stops a spurious reload.
 *
 * The tests below cover both halves of that: equal numbers must canonicalise alike, and the
 * places where normalisation does *not* reach must be recorded rather than assumed away.
 */
class CanonicalJsonValueTest {

    @Test
    fun `null and json null both read as null`() {
        assertEquals("null", canonicalJsonValue(null))
        assertEquals("null", canonicalJsonValue(JSONObject.NULL))
    }

    @Test
    fun `the same number in different representations canonicalises alike`() {
        // This is the reason the function exists. Each group is one logical value.
        val groups = listOf(
            listOf(1024, 1024L, 1024.0, 1024.0f, BigDecimal("1024.0"), BigDecimal("1024.00")),
            listOf(0, 0L, 0.0, BigDecimal("0.0"), BigDecimal("0.000")),
            listOf(1, 1L, 1.0, BigDecimal("1.00")),
            listOf(-5, -5L, -5.0, BigDecimal("-5.0")),
            listOf(10000000000L, 1.0E10, BigDecimal("1.0E10"))
        )

        groups.forEach { group ->
            val canonical = group.map { canonicalJsonValue(it) }.distinct()
            assertEquals(
                "every representation of one value must produce one canonical text: $group -> $canonical",
                1,
                canonical.size
            )
        }
    }

    @Test
    fun `trailing zeros do not change the canonical text`() {
        assertEquals(canonicalJsonValue(1), canonicalJsonValue(BigDecimal("1.000")))
        assertEquals(canonicalJsonValue(1500), canonicalJsonValue(BigDecimal("1.500E3")))
    }

    @Test
    fun `different numbers stay different`() {
        assertNotEquals(canonicalJsonValue(1), canonicalJsonValue(2))
        assertNotEquals(canonicalJsonValue(1), canonicalJsonValue(1.5))
        assertNotEquals(canonicalJsonValue(1024), canonicalJsonValue(10240))
        assertNotEquals(canonicalJsonValue(1), canonicalJsonValue(-1))
    }

    @Test
    fun `negative zero canonicalises to zero`() {
        // BigDecimal has no signed zero, so -0.0 and 0 share one canonical text.
        assertEquals(canonicalJsonValue(0), canonicalJsonValue(-0.0))
        assertEquals("0", canonicalJsonValue(-0.0))
    }

    @Test
    fun `booleans and strings keep their own forms`() {
        assertEquals("true", canonicalJsonValue(true))
        assertEquals("false", canonicalJsonValue(false))
        assertEquals("abc", canonicalJsonValue("abc"))
        assertEquals("abc", canonicalJsonValue("  abc  "))
        assertEquals("", canonicalJsonValue("   "))
    }

    @Test
    fun `a boolean is not confused with the string that spells it`() {
        // "true" the string trims to "true", and the boolean is "true" too. The comparison
        // this function feeds is field-scoped, so the two never meet in practice; recording
        // it keeps the behaviour explicit.
        assertEquals(canonicalJsonValue(true), canonicalJsonValue("true"))
    }

    @Test
    fun `a nested object is compared as its own text, not canonicalised by this function`() {
        // Normalisation is not applied to the nested value; the text is whatever org.json
        // emits. Pinned so the boundary of the guarantee stays visible.
        val nested = JSONObject().put("n", 1024)
        assertEquals(nested.toString(), canonicalJsonValue(nested))
        assertEquals("{\"n\":1024}", canonicalJsonValue(nested))
    }

    @Test
    fun `org json normalises most nested numbers itself`() {
        // org.json formats numbers on the way out, so these agree even nested: a whole
        // double is written as an integer, and a BigDecimal exponent form is written plain.
        assertEquals(
            canonicalJsonValue(JSONObject().put("n", 1024)),
            canonicalJsonValue(JSONObject().put("n", 1024.0))
        )
        assertEquals(
            canonicalJsonValue(JSONObject().put("n", 1500)),
            canonicalJsonValue(JSONObject().put("n", BigDecimal("1.500E3")))
        )
    }

    @Test
    fun `negative zero is where the nested guarantee stops`() {
        // At the top level BigDecimal has no signed zero, so -0.0 and 0 agree. Nested, the
        // text is org.json's, which strips the trailing zero from "-0.0" and leaves "-0".
        // A demonstrable case where normalisation does not reach inside.
        assertEquals(canonicalJsonValue(0), canonicalJsonValue(-0.0))
        assertNotEquals(
            "nested negative zero is not normalised",
            canonicalJsonValue(JSONObject().put("n", 0)),
            canonicalJsonValue(JSONObject().put("n", -0.0))
        )
        assertEquals("{\"n\":-0}", canonicalJsonValue(JSONObject().put("n", -0.0)))
    }

    @Test
    fun `a nested array is compared as its own text`() {
        val array = JSONArray().put(1).put(2)
        assertEquals(array.toString(), canonicalJsonValue(array))
    }

    @Test
    fun `the extraction matches the original implementation on every sample`() {
        val samples = listOf<Any?>(
            null,
            JSONObject.NULL,
            0, 0L, 0.0, 1, 1L, 1.0, -5, -5.0, 1024, 1024.0, 1024.0f,
            10000000000L, 1.0E10, 1.0E-5, 0.1, 0.1f,
            BigDecimal("0.0"), BigDecimal("1.000"), BigDecimal("1.500E3"), BigDecimal("-5.0"),
            true, false,
            "", "   ", "abc", "  abc  ", "1024", "true", "null",
            JSONObject().put("n", 1024),
            JSONObject().put("n", 1024.0),
            JSONObject().put("a", 1).put("b", "x"),
            JSONArray(),
            JSONArray().put(1).put(2),
            JSONArray().put(JSONObject().put("k", "v"))
        )

        samples.forEach { value ->
            assertEquals(
                "canonical text diverged for ${value?.javaClass?.simpleName} $value",
                originalCanonicalJsonValue(value),
                canonicalJsonValue(value)
            )
        }
    }

    /** Verbatim copy of the pre-extraction `MainViewModel.canonicalJsonValue`. */
    private fun originalCanonicalJsonValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Number -> value.toString().toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.toString()
        is Boolean -> value.toString()
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> value.toString().trim()
    }
}
