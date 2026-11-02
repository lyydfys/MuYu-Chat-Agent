package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentSpentBudget
import kotlin.math.min

enum class AgentBudgetKind {
    MODEL_ATTEMPTS,
    TOOL_INVOCATIONS,
    CALLS_PER_MODEL_RESPONSE,
    PROTOCOL_REPAIRS,
    MODEL_WALL_TIME,
    RUN_ACTIVE_TIME,
    RESOURCE_WAIT,
    GENERATED_TOKENS,
    TOOL_RESULT_TO_MODEL,
    TOOL_RESULT_PERSIST
}

sealed interface AgentBudgetDecision {
    data object Allowed : AgentBudgetDecision

    data class Exceeded(
        val kind: AgentBudgetKind,
        val error: AgentError,
        val pauseReason: AgentPauseReason
    ) : AgentBudgetDecision
}

/** Bounded view of a tool result relative to the two byte budgets (architecture 6.6). */
data class AgentToolResultSizing(
    val totalBytes: Int,
    val toModelBytes: Int,
    val needsExcerpt: Boolean,
    val exceedsPersistLimit: Boolean
)

/**
 * Deterministic budget accounting for one run.
 *
 * The caller persists [AgentSpentBudget] and charges it through the same policy; limits are
 * inclusive maxima and every timeout uses the injected monotonic clock. It performs no I/O.
 */
class AgentBudgetPolicy(private val clock: AgentMonotonicClock) {

    fun checkNextModelAttempt(spent: AgentSpentBudget, budget: AgentBudget): AgentBudgetDecision =
        if (spent.modelAttempts >= budget.modelAttempts) {
            exceeded(
                AgentBudgetKind.MODEL_ATTEMPTS,
                AgentErrorCodes.BUDGET_STEP_LIMIT,
                "The run reached its ${budget.modelAttempts}-attempt model budget."
            )
        } else {
            AgentBudgetDecision.Allowed
        }

    fun checkNextToolInvocation(spent: AgentSpentBudget, budget: AgentBudget): AgentBudgetDecision =
        if (spent.toolInvocations >= budget.toolInvocations) {
            exceeded(
                AgentBudgetKind.TOOL_INVOCATIONS,
                AgentErrorCodes.BUDGET_STEP_LIMIT,
                "The run reached its ${budget.toolInvocations}-tool budget."
            )
        } else {
            AgentBudgetDecision.Allowed
        }

    /**
     * Precheck for a native read-only batch: the whole batch must fit before the first call, so a
     * partially executable batch never produces a partial set of side effects.
     */
    fun checkToolInvocationBatch(
        count: Int,
        spent: AgentSpentBudget,
        budget: AgentBudget
    ): AgentBudgetDecision {
        if (count <= 0) return AgentBudgetDecision.Allowed
        val remaining = (budget.toolInvocations - spent.toolInvocations).coerceAtLeast(0)
        return if (count > remaining) {
            exceeded(
                AgentBudgetKind.TOOL_INVOCATIONS,
                AgentErrorCodes.BUDGET_STEP_LIMIT,
                "The run has $remaining tool call(s) left but this batch needs $count."
            )
        } else {
            AgentBudgetDecision.Allowed
        }
    }

    fun checkProtocolRepair(spent: AgentSpentBudget, budget: AgentBudget): AgentBudgetDecision =
        if (spent.protocolRepairs >= budget.protocolRepairs) {
            exceeded(
                AgentBudgetKind.PROTOCOL_REPAIRS,
                AgentErrorCodes.BUDGET_STEP_LIMIT,
                "The run reached its ${budget.protocolRepairs}-repair protocol budget."
            )
        } else {
            AgentBudgetDecision.Allowed
        }

    fun checkCallsPerResponse(callCount: Int, budget: AgentBudget): AgentBudgetDecision =
        if (callCount > budget.callsPerModelResponse) {
            AgentBudgetDecision.Exceeded(
                kind = AgentBudgetKind.CALLS_PER_MODEL_RESPONSE,
                error = AgentError(
                    code = AgentErrorCodes.MODEL_PROTOCOL_ERROR,
                    category = AgentErrorCategory.MODEL,
                    retryability = AgentRetryability.AFTER_USER_ACTION,
                    safeMessage = "V1 executes exactly one tool call per model response.",
                    correlationId = CORRELATION_ID
                ),
                pauseReason = AgentPauseReason.MODEL_PROTOCOL_ERROR
            )
        } else {
            AgentBudgetDecision.Allowed
        }

    fun checkModelWallTime(
        modelStartedAtMillis: Long,
        budget: AgentBudget
    ): AgentBudgetDecision {
        val elapsed = elapsedSince(modelStartedAtMillis)
        return if (elapsed > budget.modelWallTimeMillis) {
            exceeded(
                AgentBudgetKind.MODEL_WALL_TIME,
                AgentErrorCodes.BUDGET_TIME_LIMIT,
                "One model attempt exceeded its ${budget.modelWallTimeMillis} ms wall-time budget."
            )
        } else {
            AgentBudgetDecision.Allowed
        }
    }

    fun checkRunActiveTime(
        activeSinceMillis: Long,
        budget: AgentBudget
    ): AgentBudgetDecision {
        val elapsed = elapsedSince(activeSinceMillis)
        return if (elapsed > budget.runActiveTimeMillis) {
            exceeded(
                AgentBudgetKind.RUN_ACTIVE_TIME,
                AgentErrorCodes.BUDGET_TIME_LIMIT,
                "The run exceeded its ${budget.runActiveTimeMillis} ms active-time budget."
            )
        } else {
            AgentBudgetDecision.Allowed
        }
    }

    fun checkResourceWait(
        waitStartedAtMillis: Long,
        budget: AgentBudget
    ): AgentBudgetDecision {
        val elapsed = elapsedSince(waitStartedAtMillis)
        return if (elapsed > budget.resourceWaitMillis) {
            AgentBudgetDecision.Exceeded(
                kind = AgentBudgetKind.RESOURCE_WAIT,
                error = AgentError(
                    code = AgentErrorCodes.RESOURCE_BUSY,
                    category = AgentErrorCategory.RESOURCE,
                    retryability = AgentRetryability.AFTER_USER_ACTION,
                    safeMessage = "Waiting for the model resource exceeded ${budget.resourceWaitMillis} ms.",
                    correlationId = CORRELATION_ID
                ),
                pauseReason = AgentPauseReason.RESOURCE_BUSY
            )
        } else {
            AgentBudgetDecision.Allowed
        }
    }

    fun checkGeneratedTokens(
        spent: AgentSpentBudget,
        budget: AgentBudget,
        additionalTokens: Int = 0
    ): AgentBudgetDecision {
        require(additionalTokens >= 0) { "additionalTokens must not be negative" }
        val exhausted = spent.generatedTokens >= budget.totalGeneratedTokens
        return if (exhausted ||
            spent.generatedTokens + additionalTokens > budget.totalGeneratedTokens
        ) {
            exceeded(
                AgentBudgetKind.GENERATED_TOKENS,
                AgentErrorCodes.BUDGET_TOKEN_LIMIT,
                "The run reached its ${budget.totalGeneratedTokens}-token generation budget."
            )
        } else {
            AgentBudgetDecision.Allowed
        }
    }

    /** Remaining output-token allowance for the next model attempt, never negative. */
    fun maxOutputTokens(spent: AgentSpentBudget, budget: AgentBudget): Int {
        val remaining = budget.totalGeneratedTokens - spent.generatedTokens
        if (remaining <= 0) return 0
        return min(budget.textOutputTokens, remaining)
    }

    fun sizeToolResult(totalBytes: Int, budget: AgentBudget): AgentToolResultSizing {
        require(totalBytes >= 0) { "totalBytes must not be negative" }
        return AgentToolResultSizing(
            totalBytes = totalBytes,
            toModelBytes = min(totalBytes, budget.toolResultToModelBytes),
            needsExcerpt = totalBytes > budget.toolResultToModelBytes,
            exceedsPersistLimit = totalBytes > budget.toolResultPersistBytes
        )
    }

    fun chargeModelAttempt(
        spent: AgentSpentBudget,
        modelStartedAtMillis: Long,
        generatedTokens: Int
    ): AgentSpentBudget {
        require(generatedTokens >= 0) { "generatedTokens must not be negative" }
        return spent.copy(
            modelAttempts = spent.modelAttempts + 1,
            modelWallTimeMillis = spent.modelWallTimeMillis + elapsedSince(modelStartedAtMillis),
            generatedTokens = spent.generatedTokens + generatedTokens
        )
    }

    fun chargeToolInvocation(spent: AgentSpentBudget): AgentSpentBudget =
        spent.copy(toolInvocations = spent.toolInvocations + 1)

    fun chargeProtocolRepair(spent: AgentSpentBudget): AgentSpentBudget =
        spent.copy(protocolRepairs = spent.protocolRepairs + 1)

    fun chargeRunActiveTime(spent: AgentSpentBudget, activeSinceMillis: Long): AgentSpentBudget =
        spent.copy(runActiveTimeMillis = spent.runActiveTimeMillis + elapsedSince(activeSinceMillis))

    private fun elapsedSince(startedAtMillis: Long): Long =
        (clock.nowMillis() - startedAtMillis).coerceAtLeast(0L)

    private fun exceeded(
        kind: AgentBudgetKind,
        code: String,
        message: String
    ): AgentBudgetDecision.Exceeded = AgentBudgetDecision.Exceeded(
        kind = kind,
        error = AgentError(
            code = code,
            category = AgentErrorCategory.BUDGET,
            retryability = AgentRetryability.AFTER_USER_ACTION,
            safeMessage = message,
            correlationId = CORRELATION_ID
        ),
        pauseReason = AgentPauseReason.BUDGET_LIMIT
    )

    companion object {
        private const val CORRELATION_ID = "agent-budget-policy"
    }
}
