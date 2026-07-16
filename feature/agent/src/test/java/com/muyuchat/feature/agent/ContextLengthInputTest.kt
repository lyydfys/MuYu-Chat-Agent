package com.muyuchat.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextLengthInputTest {
    @Test
    fun exactNonPowerOfTwoContextIsAcceptedWithoutNormalization() {
        val result = validateContextLengthInput("8190")

        assertTrue(result.isValid)
        assertEquals(8190, result.value)
        assertNull(result.error)
    }

    @Test
    fun fullNativeContextRangeIsAccepted() {
        assertEquals(
            MIN_CUSTOM_CONTEXT_LENGTH,
            validateContextLengthInput(MIN_CUSTOM_CONTEXT_LENGTH.toString()).value
        )
        assertEquals(
            MAX_CUSTOM_CONTEXT_LENGTH,
            validateContextLengthInput(MAX_CUSTOM_CONTEXT_LENGTH.toString()).value
        )
        assertEquals(131_072, validateContextLengthInput("131072").value)
    }

    @Test
    fun blankFractionOverflowAndOutOfRangeValuesStayInvalid() {
        listOf(
            "",
            "8192.0",
            "not-a-number",
            "999999999999999999999999999999",
            (MIN_CUSTOM_CONTEXT_LENGTH - 1).toString(),
            (MAX_CUSTOM_CONTEXT_LENGTH + 1).toString()
        ).forEach { input ->
            val result = validateContextLengthInput(input)
            assertFalse("input=$input", result.isValid)
            assertNull("input=$input", result.value)
            assertTrue("input=$input", result.error.orEmpty().isNotBlank())
        }
    }

    @Test
    fun shortcutSliderCoversWholeRangeAndDoesNotRewriteExactValue() {
        assertEquals(MIN_CUSTOM_CONTEXT_LENGTH, contextLengthShortcutValue(0))
        assertEquals(MAX_CUSTOM_CONTEXT_LENGTH, contextLengthShortcutValue(13))
        assertEquals(8_192, contextLengthShortcutValue(nearestContextLengthShortcutStep(8_190)))

        val exact = validateContextLengthInput("8190")
        assertEquals(8_190, exact.value)
    }
}
