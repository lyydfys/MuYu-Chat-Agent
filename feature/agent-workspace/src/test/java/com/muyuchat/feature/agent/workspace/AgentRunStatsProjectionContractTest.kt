package com.muyuchat.feature.agent.workspace

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentSpentBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent contract tests for the production `AgentRunStats` projection.
 *
 * These tests are display-contract tests, not a second copy of the unit tests written beside the
 * implementation. They pin only observable, user-visible honesty rules:
 * - known runtime counters project into rounds/steps/tokens/throughput;
 * - a missing (zero or negative) model wall time never yields a fabricated `0 tok/s`;
 * - unavailable optional segments render as no segment at all;
 * - the context window denominator never becomes a percentage without a prompt-token numerator;
 * - terminal runs keep their known counters but are not marked running;
 * - cache-hit text appears only when a real percentage is supplied;
 * - compact token units are formatted with exactly one unit suffix (production renders
 *   `14.6K tok` for 14,600; the earlier `14.6KK` double-suffix bug must stay fixed).
 */
class AgentRunStatsProjectionContractTest {

    @Test
    fun knownBudgetProjectsRoundsStepsTokensAndThroughput() {
        val stats = projectRunStats(
            statsRun(
                state = AgentRunState.RUNNING,
                modelAttempts = 3,
                toolInvocations = 2,
                generatedTokens = 14_600,
                modelWallTimeMillis = 63_478L,
                contextWindowTokens = 8_192
            )
        )

        assertEquals(3, stats.rounds)
        assertEquals(5, stats.steps)
        assertEquals(14_600, stats.totalTokens)
        assertEquals(
            14_600.0 * 1_000.0 / 63_478.0,
            requireNotNull(stats.tokensPerSecond),
            0.000_001
        )
        assertTrue(stats.running)
        assertEquals("3 轮 5 步", formatRunRoundsAndSteps(stats.rounds, stats.steps))
        assertEquals("230 tok/s", formatTokensPerSecond(requireNotNull(stats.tokensPerSecond)))
    }

    @Test
    fun missingWallTimeOmitsThroughputWithoutFabricatingAZeroRate() {
        val zeroWallTime = projectRunStats(
            statsRun(
                state = AgentRunState.SUCCEEDED,
                modelAttempts = 1,
                toolInvocations = 1,
                generatedTokens = 500,
                modelWallTimeMillis = 0L
            )
        )
        assertNull(zeroWallTime.tokensPerSecond)
        assertEquals(
            "500 tok",
            formatRunTokens(zeroWallTime.tokensPerSecond, zeroWallTime.totalTokens)
        )
        assertEquals("1 轮 2 步", formatRunRoundsAndSteps(zeroWallTime.rounds, zeroWallTime.steps))

        val negativeWallTime = projectRunStats(
            statsRun(generatedTokens = 500, modelWallTimeMillis = -1L)
        )
        assertNull(negativeWallTime.tokensPerSecond)
    }

    @Test
    fun unknownSegmentsAreOmittedWithoutHidingKnownSegments() {
        assertNull(formatRunRoundsAndSteps(null, null))
        assertNull(formatRunTokens(null, null))
        assertNull(formatRunContextPercent(null))
        assertNull(formatRunCacheHitPercent(null))

        assertEquals("3 轮", formatRunRoundsAndSteps(3, null))
        assertEquals("2 步", formatRunRoundsAndSteps(null, 2))
        assertEquals("500 tok", formatRunTokens(null, 500))
        assertEquals("23 tok/s", formatRunTokens(23.0, null))
    }

    @Test
    fun contextWindowAloneDoesNotBecomeAContextPercentage() {
        val stats = projectRunStats(
            statsRun(
                state = AgentRunState.RUNNING,
                modelAttempts = 2,
                toolInvocations = 1,
                generatedTokens = 1_200,
                modelWallTimeMillis = 60_000L,
                contextWindowTokens = 8_192
            )
        )

        // The denominator is known, but AgentRunSnapshot exposes no prompt-token numerator.
        assertNull(stats.contextPercent)
        // The formatter is allowed to render only an explicitly supplied real value.
        assertNull(formatRunContextPercent(null))
        assertEquals("25%", formatRunContextPercent(25))
    }

    @Test
    fun terminalRunsKeepStatsButAreNotMarkedRunning() {
        AgentRunState.entries.filter { it.isTerminal }.forEach { state ->
            val stats = projectRunStats(
                statsRun(
                    state = state,
                    modelAttempts = 2,
                    toolInvocations = 1,
                    generatedTokens = 1_200,
                    modelWallTimeMillis = 60_000L
                )
            )
            assertFalse("$state must not be marked running", stats.running)
            assertEquals(2, stats.rounds)
            assertEquals(3, stats.steps)
            assertEquals(1_200, stats.totalTokens)
            assertNotNull("$state keeps its real throughput", stats.tokensPerSecond)
        }

        assertTrue(projectRunStats(statsRun(state = AgentRunState.RUNNING)).running)
        assertFalse(projectRunStats(statsRun(state = AgentRunState.PAUSED)).running)
    }

    @Test
    fun cacheHitIsNeverFabricatedWithoutARealUsageSource() {
        val stats = projectRunStats(
            statsRun(
                state = AgentRunState.RUNNING,
                generatedTokens = 500,
                modelWallTimeMillis = 10_000L,
                contextWindowTokens = 8_192
            )
        )
        assertNull(stats.cacheHitPercent)
        assertNull(formatRunCacheHitPercent(null))
        assertEquals("缓存命中 37%", formatRunCacheHitPercent(37))
    }

    @Test
    fun compactTokenCountsKeepASingleUnitSuffix() {
        assertEquals("999 tok", formatTokenCount(999))
        assertEquals("1K tok", formatTokenCount(1_000))
        assertEquals("1.5K tok", formatTokenCount(1_500))
        assertEquals("14.6K tok", formatTokenCount(14_600))
        assertEquals("1M tok", formatTokenCount(1_000_000))
        assertEquals("1.2M tok", formatTokenCount(1_200_000))
    }
}

private const val STATS_RUN_ID = "run-stats"
private const val STATS_TASK_ID = "task-stats"

private fun statsRun(
    state: AgentRunState = AgentRunState.RUNNING,
    modelAttempts: Int = 0,
    toolInvocations: Int = 0,
    generatedTokens: Int = 0,
    modelWallTimeMillis: Long = 0L,
    contextWindowTokens: Int? = null
): AgentRun = AgentRun(
    runId = STATS_RUN_ID,
    taskId = STATS_TASK_ID,
    runOrdinal = 1L,
    state = state,
    reason = AgentPauseReason.NONE,
    epoch = 1L,
    modelBinding = AgentModelBinding(
        providerId = "local",
        modelId = "model-1",
        runtime = "mnn",
        assetFingerprint = "fingerprint-1",
        protocolMode = AgentProtocolMode.PROMPTED_V1,
        modelDisplayName = "Qwen3 4B",
        contextWindowTokens = contextWindowTokens
    ),
    policy = AgentRunPolicy(),
    budget = AgentBudget(),
    spentBudget = AgentSpentBudget(
        modelAttempts = modelAttempts,
        toolInvocations = toolInvocations,
        generatedTokens = generatedTokens,
        modelWallTimeMillis = modelWallTimeMillis
    ),
    lastSequence = 1L,
    revision = 1L,
    createdAtMillis = 0L,
    updatedAtMillis = 0L
)
