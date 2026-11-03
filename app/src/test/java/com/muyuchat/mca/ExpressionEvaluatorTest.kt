package com.muyuchat.mca

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `calc` tool feeds model-authored text into an arithmetic parser. These tests pin the two
 * properties that matter: ordinary school arithmetic must be exactly right, and everything that is
 * not arithmetic must fail instead of being evaluated, partially computed, or silently ignored.
 */
class ExpressionEvaluatorTest {

    private fun value(expression: String): String =
        when (val result = ExpressionEvaluator.evaluate(expression)) {
            is ExpressionEvaluator.Result.Value -> result.formatted
            is ExpressionEvaluator.Result.Failure -> error("expected a value for '$expression': ${result.reason}")
        }

    private fun failure(expression: String): String =
        when (val result = ExpressionEvaluator.evaluate(expression)) {
            is ExpressionEvaluator.Result.Value -> error("expected a failure for '$expression', got ${result.formatted}")
            is ExpressionEvaluator.Result.Failure -> result.reason
        }

    @Test
    fun `basic arithmetic keeps integer results readable`() {
        assertEquals("7", value("1+2*3"))
        assertEquals("9", value("(1+2)*3"))
        assertEquals("1", value("7%3"))
        assertEquals("2.5", value("5/2"))
        assertEquals("-3", value("2-5"))
        assertEquals("0.25", value("1/4"))
    }

    @Test
    fun `power is right associative and binds tighter than unary minus`() {
        assertEquals("512", value("2^3^2"))
        assertEquals("-9", value("-3^2"))
        assertEquals("9", value("(-3)^2"))
        assertEquals("8", value("2^3"))
    }

    @Test
    fun `decimal and exponent literals parse like numbers`() {
        assertEquals("0.5", value(".5"))
        assertEquals("1000", value("1e3"))
        assertEquals("0.001", value("1e-3"))
        assertEquals("2.5", value("2.50"))
    }

    @Test
    fun `fixed function table is available and bounded`() {
        assertEquals("3", value("sqrt(9)"))
        assertEquals("3", value("abs(0-3)"))
        assertEquals("4", value("max(2,4)"))
        assertEquals("2", value("min(2,4)"))
        assertEquals("3", value("round(2.6)"))
        assertEquals("2", value("floor(2.9)"))
        assertEquals("3", value("ceil(2.1)"))
        assertEquals("8", value("pow(2,3)"))
    }

    @Test
    fun `constants are available`() {
        assertTrue(value("pi").startsWith("3.14159"))
        // ln(e) is 1.0 in IEEE double arithmetic, but only up to the last bit; assert the value.
        assertEquals(1.0, value("ln(e)").toDouble(), 1e-12)
    }

    @Test
    fun `division by zero and invalid domains are failures`() {
        assertTrue(failure("1/0").contains("零"))
        assertTrue(failure("5%0").contains("零"))
        assertTrue(failure("sqrt(0-1)").contains("平方根"))
        assertTrue(failure("ln(0)").contains("大于零"))
    }

    @Test
    fun `non arithmetic input is rejected instead of evaluated`() {
        assertTrue(failure("Runtime.getRuntime().exec(\"id\")").isNotBlank())
        assertTrue(failure("1;2").isNotBlank())
        assertTrue(failure("a=1").isNotBlank())
        assertTrue(failure("${'"'}unterminated").isNotBlank())
        assertTrue(failure("2(3)").isNotBlank())
        assertTrue(failure("2 3").isNotBlank())
        assertTrue(failure("alert(1)").isNotBlank())
        assertTrue(failure("unknown(1)").isNotBlank())
        assertTrue(failure("min(1)").isNotBlank())
    }

    @Test
    fun `structurally broken expressions are failures`() {
        assertTrue(failure("").isNotBlank())
        assertTrue(failure("   ").isNotBlank())
        assertTrue(failure("(1+2").contains("括号"))
        assertTrue(failure("1+2)").isNotBlank())
        assertTrue(failure("1+").isNotBlank())
        assertTrue(failure("*2").isNotBlank())
        assertTrue(failure("1 2 +").isNotBlank())
    }

    @Test
    fun `input length and nesting depth are bounded`() {
        assertTrue(failure("1".repeat(ExpressionEvaluator.MAX_EXPRESSION_CHARS + 1)).contains("限制"))
        val deep = "(".repeat(ExpressionEvaluator.MAX_NESTING_DEPTH + 2) + "1" +
            ")".repeat(ExpressionEvaluator.MAX_NESTING_DEPTH + 2)
        assertTrue(failure(deep).contains("限制"))
    }

    @Test
    fun `overflow is reported instead of returning infinity`() {
        val result = ExpressionEvaluator.evaluate("1e308*10")
        assertTrue(result is ExpressionEvaluator.Result.Failure)
    }
}
