package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.workflow.WorkflowBudget

/**
 * Shared budget accounting for one workflow run (N19/N20/N21).
 *
 * A workflow definition declares one [WorkflowBudget] shared by every node and every pinned
 * subworkflow frame. Tool calls are consumed locally; an `AGENT_TASK` child receives the remaining
 * limits as its own [AgentBudget], and its spent budget is charged back afterwards. Exceeding the
 * shared total is a typed run failure, never a silent truncation.
 */
internal class WorkflowRunBudget(
    private val budget: WorkflowBudget,
    private val clock: () -> Long
) {
    private val startedAtMillis: Long = clock()
    private var modelCalls: Int = 0
    private var toolCalls: Int = 0
    private var estimatedTokens: Int = 0

    fun elapsedMillis(): Long = (clock() - startedAtMillis).coerceAtLeast(0L)

    fun durationExhausted(): Boolean = elapsedMillis() > budget.maxDurationMillis

    fun remainingDurationMillis(): Long =
        (budget.maxDurationMillis - elapsedMillis()).coerceAtLeast(1L)

    /** Reserves one tool call; false means the shared tool budget is already exhausted. */
    fun consumeToolCall(): Boolean {
        if (toolCalls >= budget.maxToolCalls) return false
        toolCalls += 1
        return true
    }

    /** Reserves one model call for a MODEL node; false means the shared model budget is spent. */
    fun consumeModelCall(): Boolean {
        if (modelCalls >= budget.maxModelCalls) return false
        modelCalls += 1
        return true
    }

    /** Charges model tokens; false means the shared token ceiling was exceeded. */
    fun chargeModelTokens(tokens: Int): Boolean {
        estimatedTokens += tokens.coerceAtLeast(0)
        return estimatedTokens <= budget.maxEstimatedTokens
    }

    /**
     * The remaining limits as one child [AgentBudget], capped by the fixed V1 child ceilings.
     * Null means no honest child execution is possible (no model calls/tokens/time left).
     */
    fun childAgentBudget(): AgentBudget? {
        val remainingModels = budget.maxModelCalls - modelCalls
        val remainingTools = (budget.maxToolCalls - toolCalls).coerceAtLeast(0)
        val remainingTokens = budget.maxEstimatedTokens - estimatedTokens
        if (remainingModels <= 0 || remainingTokens <= 0 || durationExhausted()) return null
        val remainingDuration = remainingDurationMillis()
        val base = AgentBudget()
        return base.copy(
            modelAttempts = minOf(base.modelAttempts, remainingModels),
            toolInvocations = minOf(base.toolInvocations, remainingTools),
            modelWallTimeMillis = minOf(base.modelWallTimeMillis, remainingDuration),
            runActiveTimeMillis = minOf(base.runActiveTimeMillis, remainingDuration),
            resourceWaitMillis = minOf(base.resourceWaitMillis, remainingDuration),
            textOutputTokens = minOf(base.textOutputTokens, remainingTokens),
            totalGeneratedTokens = minOf(base.totalGeneratedTokens, remainingTokens)
        )
    }

    /** Charges a settled child; false means the shared total was exceeded. */
    fun charge(spent: AgentSpentBudget): Boolean {
        modelCalls += spent.modelAttempts.coerceAtLeast(0)
        toolCalls += spent.toolInvocations.coerceAtLeast(0)
        estimatedTokens += spent.generatedTokens.coerceAtLeast(0)
        return modelCalls <= budget.maxModelCalls &&
            toolCalls <= budget.maxToolCalls &&
            estimatedTokens <= budget.maxEstimatedTokens
    }

    fun hasModelBudget(): Boolean = budget.maxModelCalls - modelCalls > 0
}
