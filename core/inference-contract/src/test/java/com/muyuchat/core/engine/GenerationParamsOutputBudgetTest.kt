package com.muyuchat.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The output budget must never be a fixed 8192: it follows the context window, stays sane at both
 * ends, and an explicit preset value always wins.
 */
class GenerationParamsOutputBudgetTest {

    @Test
    fun `the default follows the context window`() {
        assertEquals(4_096, GenerationParams(nCtx = 8_192).nPredict)
        assertEquals(32_768, GenerationParams(nCtx = 65_536).nPredict)
        assertEquals(2_048, GenerationParams(nCtx = 4_096).nPredict)
    }

    @Test
    fun `the helper always lands inside the documented bounds`() {
        listOf(0, 128, 1_024, 4_096, 8_192, 32_768, 65_536, 131_072, 1_000_000).forEach { window ->
            val budget = defaultOutputBudget(window)
            assertTrue(
                "window=$window produced $budget",
                budget >= MIN_OUTPUT_TOKENS && budget <= MAX_OUTPUT_TOKENS
            )
        }
        assertEquals(MAX_OUTPUT_TOKENS, defaultOutputBudget(1_000_000))
    }

    @Test
    fun `a preset output budget is ignored in favour of the local window`() {
        val withMaxTokens = GenerationParams.fromJson(
            """{"n_ctx":65536,"max_tokens":1234}"""
        )
        assertEquals(65_536, withMaxTokens.nCtx)
        assertEquals(
            "the window decides the budget, not the preset",
            defaultOutputBudget(65_536),
            withMaxTokens.nPredict
        )

        val withNPredict = GenerationParams.fromJson(
            """{"n_ctx":32768,"n_predict":900}"""
        )
        assertEquals(defaultOutputBudget(32_768), withNPredict.nPredict)
    }

    @Test
    fun `a non-positive preset budget falls back to the derived default`() {
        val params = GenerationParams.fromJson("""{"n_ctx":32768,"n_predict":0}""")
        assertEquals(defaultOutputBudget(32_768), params.nPredict)
        assertTrue("a zero budget must not survive as zero", params.nPredict > 0)
    }

    @Test
    fun `output never exceeds the window it belongs to`() {
        listOf(2_048, 8_192, 32_768, 65_536, 131_072).forEach { window ->
            val budget = GenerationParams(nCtx = window).nPredict
            assertTrue("window=$window budget=$budget", budget <= window)
        }
    }
}
