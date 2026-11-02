package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunStatsProjectionTest {

    @Test
    fun knownBudgetProjectsRoundsStepsThroughputAndTokens() {
        val run = testRun(
            state = AgentRunState.RUNNING,
            spentBudget = AgentSpentBudget(
                modelAttempts = 1,
                toolInvocations = 1,
                generatedTokens = 14_600,
                modelWallTimeMillis = 63_478L
            )
        )
        val stats = projectRunStats(run)
        assertEquals(1, stats.rounds)
        assertEquals(2, stats.steps)
        assertEquals(
            14_600.0 * 1_000.0 / 63_478.0,
            requireNotNull(stats.tokensPerSecond),
            0.000_001
        )
        assertEquals(14_600, stats.totalTokens)
        assertTrue(stats.running)
    }

    @Test
    fun roundsCountModelAttemptsNotResumedRunOrdinal() {
        val run = testRun(spentBudget = AgentSpentBudget(modelAttempts = 3)).copy(runOrdinal = 7L)
        assertEquals(3, projectRunStats(run).rounds)
    }

    @Test
    fun contextWindowWithoutPromptTokenCountDoesNotInventAPercentage() {
        val run = testRun().copy(modelBinding = testBinding(contextWindowTokens = 8_192))
        assertNull(projectRunStats(run).contextPercent)
    }

    @Test
    fun zeroModelWallTimeOmitsThroughputButKeepsGeneratedTokens() {
        val stats = projectRunStats(
            testRun(
                spentBudget = AgentSpentBudget(
                    generatedTokens = 500,
                    modelWallTimeMillis = 0L
                )
            )
        )
        assertNull(stats.tokensPerSecond)
        assertEquals(500, stats.totalTokens)
    }

    @Test
    fun emptyBudgetIsKnownZeroNotUnknown() {
        val stats = projectRunStats(testRun())
        assertEquals(0, stats.rounds)
        assertEquals(0, stats.steps)
        assertEquals(0, stats.totalTokens)
        assertNull(stats.tokensPerSecond)
        assertNull(stats.contextPercent)
        assertNull(stats.cacheHitPercent)
    }

    @Test
    fun onlyRunningStateDrivesTheProgressRing() {
        assertTrue(projectRunStats(testRun(state = AgentRunState.RUNNING)).running)
        AgentRunState.entries.filterNot { it == AgentRunState.RUNNING }.forEach { state ->
            assertFalse(
                "$state must not animate the running ring",
                projectRunStats(testRun(state = state)).running
            )
        }
    }

    @Test
    fun labelsUseOnlyAvailableSegments() {
        assertEquals("1 轮 2 步", formatRunRoundsAndSteps(1, 2))
        assertEquals("1 轮", formatRunRoundsAndSteps(1, null))
        assertEquals("2 步", formatRunRoundsAndSteps(null, 2))
        assertNull(formatRunRoundsAndSteps(null, null))

        assertEquals("23 tok/s · 14.6K tok", formatRunTokens(23.0, 14_600))
        assertEquals("23 tok/s", formatRunTokens(23.0, null))
        assertEquals("9.6K tok", formatRunTokens(null, 9_600))
        assertNull(formatRunTokens(null, null))

        assertEquals("0%", formatRunContextPercent(0))
        assertEquals("1%", formatRunContextPercent(1))
        assertNull(formatRunContextPercent(null))
        assertEquals("缓存命中 37%", formatRunCacheHitPercent(37))
        assertNull(formatRunCacheHitPercent(null))
    }

    @Test
    fun tokenCountFormattingUsesCompactThresholds() {
        assertEquals("999 tok", formatTokenCount(999))
        assertEquals("1K tok", formatTokenCount(1_000))
        assertEquals("1.5K tok", formatTokenCount(1_500))
        assertEquals("14.6K tok", formatTokenCount(14_600))
        assertEquals("23K tok", formatTokenCount(23_000))
        assertEquals("1M tok", formatTokenCount(1_000_000))
        assertEquals("1.2M tok", formatTokenCount(1_200_000))
        assertEquals("0 tok", formatTokenCount(-1))
    }

    @Test
    fun throughputFormattingKeepsOneDecimalOnlyWhenNeeded() {
        assertEquals("23 tok/s", formatTokensPerSecond(23.0))
        assertEquals("23.4 tok/s", formatTokensPerSecond(23.44))
        assertEquals("0 tok/s", formatTokensPerSecond(0.0))
    }
}
