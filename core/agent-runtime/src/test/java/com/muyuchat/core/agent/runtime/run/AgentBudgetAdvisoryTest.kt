package com.muyuchat.core.agent.runtime.run

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budget-pressure projection: the four bands, their exact boundaries, the stricter-wins rule and the
 * advisory strings.
 *
 * Pure JUnit: no scheduler, no clock, no Android.
 */
class AgentBudgetAdvisoryTest {

    @Test
    fun bandsStartExactlyAtTheirRatios() {
        // Token dimension only (steps stay at 0/100).
        assertEquals(AgentBudgetPressure.NONE, assess(tokens = 0.79, steps = 0.0))
        assertEquals(AgentBudgetPressure.ELEVATED, assess(tokens = 0.80, steps = 0.0))
        assertEquals(AgentBudgetPressure.ELEVATED, assess(tokens = 0.949, steps = 0.0))
        assertEquals(AgentBudgetPressure.CRITICAL, assess(tokens = 0.95, steps = 0.0))
        assertEquals(AgentBudgetPressure.CRITICAL, assess(tokens = 0.999, steps = 0.0))
        assertEquals(AgentBudgetPressure.EXHAUSTED, assess(tokens = 1.0, steps = 0.0))
        assertEquals(AgentBudgetPressure.EXHAUSTED, assess(tokens = 1.5, steps = 0.0))
    }

    @Test
    fun stepDimensionUsesTheSameBoundaries() {
        assertEquals(AgentBudgetPressure.NONE, assess(tokens = 0.0, steps = 0.79))
        assertEquals(AgentBudgetPressure.ELEVATED, assess(tokens = 0.0, steps = 0.80))
        assertEquals(AgentBudgetPressure.CRITICAL, assess(tokens = 0.0, steps = 0.95))
        assertEquals(AgentBudgetPressure.EXHAUSTED, assess(tokens = 0.0, steps = 1.0))
    }

    @Test
    fun theStricterDimensionWins() {
        // Deep in steps, comfortable on tokens → still CRITICAL.
        assertEquals(AgentBudgetPressure.CRITICAL, assess(tokens = 0.10, steps = 0.95))
        // Spending everything on tokens, few steps → CRITICAL, not ELEVATED.
        assertEquals(AgentBudgetPressure.CRITICAL, assess(tokens = 0.95, steps = 0.10))
        // One dimension exhausted beats the other being merely elevated.
        assertEquals(AgentBudgetPressure.EXHAUSTED, assess(tokens = 0.85, steps = 1.0))
        assertEquals(AgentBudgetPressure.EXHAUSTED, assess(tokens = 1.0, steps = 0.85))
        // Both elevated stays elevated.
        assertEquals(AgentBudgetPressure.ELEVATED, assess(tokens = 0.85, steps = 0.85))
    }

    @Test
    fun unlimitedDimensionsAreIgnoredRatherThanExhausted() {
        // budget = 0 means "no token ceiling": spending anything must not report pressure.
        assertEquals(
            AgentBudgetPressure.NONE,
            AgentBudgetAdvisory.assess(spentTokens = 999_999L, budget = 0L, stepIndex = 0, maxSteps = 0)
        )
        // maxSteps = 0 means "no step ceiling", while the token dimension still reports normally.
        assertEquals(
            AgentBudgetPressure.ELEVATED,
            AgentBudgetAdvisory.assess(spentTokens = 80L, budget = 100L, stepIndex = 42, maxSteps = 0)
        )
        // Negative counters clamp to zero instead of producing a negative ratio.
        assertEquals(
            AgentBudgetPressure.NONE,
            AgentBudgetAdvisory.assess(spentTokens = -10L, budget = 100L, stepIndex = -1, maxSteps = 10)
        )
    }

    @Test
    fun realisticRunShapesMapToTheExpectedBand() {
        // 12 model attempts / 8 tool calls is the default budget table's shape; 8 of 12 steps.
        assertEquals(
            AgentBudgetPressure.NONE,
            AgentBudgetAdvisory.assess(spentTokens = 1_000L, budget = 8_192L, stepIndex = 8, maxSteps = 12)
        )
        // Half the tokens, deep in steps: 10/12 = 0.833 ≥ 0.80, so steps lift it to ELEVATED while the
        // token dimension alone would be NONE (4_000/8_192 = 0.49).
        assertEquals(
            AgentBudgetPressure.ELEVATED,
            AgentBudgetAdvisory.assess(spentTokens = 4_000L, budget = 8_192L, stepIndex = 10, maxSteps = 12)
        )
        // 12 of 13 steps (0.923) is still below the 0.95 critical line.
        assertEquals(
            AgentBudgetPressure.ELEVATED,
            AgentBudgetAdvisory.assess(spentTokens = 4_000L, budget = 8_192L, stepIndex = 12, maxSteps = 13)
        )
        // Reaching the last allowed step is EXHAUSTED, not CRITICAL: the run has consumed the ceiling.
        assertEquals(
            AgentBudgetPressure.EXHAUSTED,
            AgentBudgetAdvisory.assess(spentTokens = 4_000L, budget = 8_192L, stepIndex = 12, maxSteps = 12)
        )
    }

    @Test
    fun advisoryTextIsEmptyOnlyForNone() {
        assertEquals("", AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.NONE))
        assertFalse(AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.ELEVATED).isEmpty())
        assertFalse(AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.CRITICAL).isEmpty())
        assertFalse(AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.EXHAUSTED).isEmpty())
    }

    @Test
    fun advisoryTextIsStableChineseAndWarnsAboutDifferentOutcomes() {
        assertEquals("剩余预算已不足两成，后续回复可能会缩短。", AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.ELEVATED))
        assertEquals("剩余预算不足一成，本轮很可能被截断，建议先保存结果。", AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.CRITICAL))
        assertEquals("本轮预算已耗尽，输出已被截断，请减少上下文后重试。", AgentBudgetAdvisory.advisoryText(AgentBudgetPressure.EXHAUSTED))
        // The three texts must be distinct: the console shows one line, so a copy-paste would hide a band.
        val texts = listOf(
            AgentBudgetPressure.ELEVATED,
            AgentBudgetPressure.CRITICAL,
            AgentBudgetPressure.EXHAUSTED
        ).map(AgentBudgetAdvisory::advisoryText)
        assertEquals(3, texts.toSet().size)
        assertTrue("each hint is one sentence", texts.all { it.endsWith("。") })
    }

    @Test
    fun isAtLeastComparesSeverity() {
        assertTrue(AgentBudgetAdvisory.isAtLeast(AgentBudgetPressure.EXHAUSTED, AgentBudgetPressure.ELEVATED))
        assertTrue(AgentBudgetAdvisory.isAtLeast(AgentBudgetPressure.CRITICAL, AgentBudgetPressure.CRITICAL))
        assertFalse(AgentBudgetAdvisory.isAtLeast(AgentBudgetPressure.ELEVATED, AgentBudgetPressure.CRITICAL))
        assertTrue(AgentBudgetAdvisory.isAtLeast(AgentBudgetPressure.NONE, AgentBudgetPressure.NONE))
    }

    @Test
    fun enumOrderIsTheSeverityOrder() {
        assertEquals(
            listOf(
                AgentBudgetPressure.NONE,
                AgentBudgetPressure.ELEVATED,
                AgentBudgetPressure.CRITICAL,
                AgentBudgetPressure.EXHAUSTED
            ),
            AgentBudgetPressure.entries.toList()
        )
    }

    /** Builds integer inputs from a ratio so the exact boundary values are readable. */
    private fun assess(tokens: Double, steps: Double): AgentBudgetPressure =
        AgentBudgetAdvisory.assess(
            spentTokens = (tokens * 1_000).toLong(),
            budget = 1_000L,
            stepIndex = (steps * 100).toInt(),
            maxSteps = 100
        )
}
