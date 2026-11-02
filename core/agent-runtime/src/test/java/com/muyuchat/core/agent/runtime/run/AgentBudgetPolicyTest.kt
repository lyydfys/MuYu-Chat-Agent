package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentSpentBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** K03 budget-table tests (T07/T08 time and count boundaries) with a fake monotonic clock. */
class AgentBudgetPolicyTest {

    private val clock = ManualAgentMonotonicClock()
    private val policy = AgentBudgetPolicy(clock)
    private val budget = AgentBudget()

    @Test
    fun defaultBudgetMatchesArchitectureDefaultTable() {
        assertEquals(12, budget.modelAttempts)
        assertEquals(8, budget.toolInvocations)
        assertEquals(1, budget.callsPerModelResponse)
        assertEquals(1, budget.protocolRepairs)
        assertEquals(180_000L, budget.modelWallTimeMillis)
        assertEquals(30_000L, budget.toolTimeoutMillis)
        assertEquals(900_000L, budget.runActiveTimeMillis)
        assertEquals(120_000L, budget.resourceWaitMillis)
        assertEquals(16 * 1024, budget.toolResultToModelBytes)
        assertEquals(64 * 1024, budget.toolResultPersistBytes)
        assertEquals(2_048, budget.textOutputTokens)
        assertEquals(24_576, budget.totalGeneratedTokens)
    }

    @Test
    fun attemptToolAndRepairLimitsAreInclusiveMaxima() {
        assertTrue(policy.checkNextModelAttempt(AgentSpentBudget(modelAttempts = 11), budget) is AgentBudgetDecision.Allowed)
        val attempts = exceeded(policy.checkNextModelAttempt(AgentSpentBudget(modelAttempts = 12), budget))
        assertEquals(AgentBudgetKind.MODEL_ATTEMPTS, attempts.kind)
        assertEquals(AgentErrorCodes.BUDGET_STEP_LIMIT, attempts.error.code)
        assertEquals(AgentPauseReason.BUDGET_LIMIT, attempts.pauseReason)

        assertTrue(policy.checkNextToolInvocation(AgentSpentBudget(toolInvocations = 7), budget) is AgentBudgetDecision.Allowed)
        val tools = exceeded(policy.checkNextToolInvocation(AgentSpentBudget(toolInvocations = 8), budget))
        assertEquals(AgentBudgetKind.TOOL_INVOCATIONS, tools.kind)

        assertTrue(policy.checkProtocolRepair(AgentSpentBudget(protocolRepairs = 0), budget) is AgentBudgetDecision.Allowed)
        val repairs = exceeded(policy.checkProtocolRepair(AgentSpentBudget(protocolRepairs = 1), budget))
        assertEquals(AgentBudgetKind.PROTOCOL_REPAIRS, repairs.kind)
    }

    @Test
    fun oneCallPerResponseIsAProtocolBoundary() {
        assertTrue(policy.checkCallsPerResponse(1, budget) is AgentBudgetDecision.Allowed)
        val multi = exceeded(policy.checkCallsPerResponse(2, budget))
        assertEquals(AgentBudgetKind.CALLS_PER_MODEL_RESPONSE, multi.kind)
        assertEquals(AgentErrorCodes.MODEL_PROTOCOL_ERROR, multi.error.code)
        assertEquals(AgentPauseReason.MODEL_PROTOCOL_ERROR, multi.pauseReason)
    }

    @Test
    fun modelWallTimeAndRunActiveTimeUseTheMonotonicClock() {
        val start = clock.nowMillis()
        assertTrue(policy.checkModelWallTime(start, budget) is AgentBudgetDecision.Allowed)
        clock.advanceMillis(180_000L)
        assertTrue(policy.checkModelWallTime(start, budget) is AgentBudgetDecision.Allowed)
        clock.advanceMillis(1L)
        val wall = exceeded(policy.checkModelWallTime(start, budget))
        assertEquals(AgentBudgetKind.MODEL_WALL_TIME, wall.kind)
        assertEquals(AgentErrorCodes.BUDGET_TIME_LIMIT, wall.error.code)

        val activeStart = clock.nowMillis()
        clock.advanceMillis(900_000L)
        assertTrue(policy.checkRunActiveTime(activeStart, budget) is AgentBudgetDecision.Allowed)
        clock.advanceMillis(1L)
        val active = exceeded(policy.checkRunActiveTime(activeStart, budget))
        assertEquals(AgentBudgetKind.RUN_ACTIVE_TIME, active.kind)
        assertEquals(AgentErrorCodes.BUDGET_TIME_LIMIT, active.error.code)
    }

    @Test
    fun resourceWaitUsesItsOwnPauseReasonAndInclusiveLimit() {
        val start = clock.nowMillis()
        clock.advanceMillis(120_000L)
        assertTrue(policy.checkResourceWait(start, budget) is AgentBudgetDecision.Allowed)
        clock.advanceMillis(1L)
        val exceeded = exceeded(policy.checkResourceWait(start, budget))
        assertEquals(AgentBudgetKind.RESOURCE_WAIT, exceeded.kind)
        assertEquals(AgentErrorCodes.RESOURCE_BUSY, exceeded.error.code)
        assertEquals(AgentPauseReason.RESOURCE_BUSY, exceeded.pauseReason)
    }

    @Test
    fun generatedTokenLimitAndOutputAllowance() {
        assertTrue(
            policy.checkGeneratedTokens(AgentSpentBudget(generatedTokens = 24_575), budget) is
                AgentBudgetDecision.Allowed
        )
        val tokens = exceeded(
            policy.checkGeneratedTokens(AgentSpentBudget(generatedTokens = 24_576), budget)
        )
        assertEquals(AgentBudgetKind.GENERATED_TOKENS, tokens.kind)
        assertEquals(AgentErrorCodes.BUDGET_TOKEN_LIMIT, tokens.error.code)

        assertEquals(2_048, policy.maxOutputTokens(AgentSpentBudget(generatedTokens = 0), budget))
        assertEquals(100, policy.maxOutputTokens(AgentSpentBudget(generatedTokens = 24_476), budget))
        assertEquals(0, policy.maxOutputTokens(AgentSpentBudget(generatedTokens = 24_576), budget))
    }

    @Test
    fun toolResultByteBudgetsAreExact() {
        val exact = policy.sizeToolResult(16 * 1024, budget)
        assertTrue(!exact.needsExcerpt)
        assertEquals(16 * 1024, exact.toModelBytes)

        val excerpt = policy.sizeToolResult(16 * 1024 + 1, budget)
        assertTrue(excerpt.needsExcerpt)
        assertEquals(16 * 1024, excerpt.toModelBytes)

        val persist = policy.sizeToolResult(64 * 1024 + 1, budget)
        assertTrue(persist.exceedsPersistLimit)
        assertTrue(!policy.sizeToolResult(64 * 1024, budget).exceedsPersistLimit)
    }

    @Test
    fun chargingAccumulatesFactsWithoutApplyingCountersTwice() {
        var spent = AgentSpentBudget()
        clock.advanceMillis(500L)
        val start = clock.nowMillis()
        clock.advanceMillis(1_500L)
        spent = policy.chargeModelAttempt(spent, start, generatedTokens = 120)
        assertEquals(1, spent.modelAttempts)
        assertEquals(1_500L, spent.modelWallTimeMillis)
        assertEquals(120, spent.generatedTokens)

        spent = policy.chargeToolInvocation(spent)
        spent = policy.chargeProtocolRepair(spent)
        assertEquals(1, spent.toolInvocations)
        assertEquals(1, spent.protocolRepairs)

        val activeStart = clock.nowMillis()
        clock.advanceMillis(250L)
        spent = policy.chargeRunActiveTime(spent, activeStart)
        assertEquals(250L, spent.runActiveTimeMillis)

        var negativeClockAdvance = false
        try {
            clock.advanceMillis(-1L)
        } catch (_: IllegalArgumentException) {
            negativeClockAdvance = true
        }
        assertTrue(negativeClockAdvance)
    }

    @Test
    fun `a batch must fit entirely or it is rejected before any call`() {
        assertTrue(
            policy.checkToolInvocationBatch(3, AgentSpentBudget(toolInvocations = 5), budget) is
                AgentBudgetDecision.Allowed
        )
        val exceeded = exceeded(
            policy.checkToolInvocationBatch(4, AgentSpentBudget(toolInvocations = 5), budget)
        )
        assertEquals(AgentBudgetKind.TOOL_INVOCATIONS, exceeded.kind)
        assertEquals(AgentErrorCodes.BUDGET_STEP_LIMIT, exceeded.error.code)
        assertEquals(AgentPauseReason.BUDGET_LIMIT, exceeded.pauseReason)
    }

    @Test
    fun `an empty or over-spent batch check stays deterministic`() {
        assertTrue(
            policy.checkToolInvocationBatch(0, AgentSpentBudget(toolInvocations = 8), budget) is
                AgentBudgetDecision.Allowed
        )
        val spentAll = exceeded(
            policy.checkToolInvocationBatch(1, AgentSpentBudget(toolInvocations = 8), budget)
        )
        assertEquals(AgentBudgetKind.TOOL_INVOCATIONS, spentAll.kind)
    }

    private fun exceeded(decision: AgentBudgetDecision): AgentBudgetDecision.Exceeded {
        assertTrue("expected exceeded, got $decision", decision is AgentBudgetDecision.Exceeded)
        return decision as AgentBudgetDecision.Exceeded
    }
}
