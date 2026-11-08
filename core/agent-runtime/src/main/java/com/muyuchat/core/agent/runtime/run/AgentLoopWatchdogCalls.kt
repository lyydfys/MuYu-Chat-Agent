package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.runtime.AgentTranscriptEntry

/**
 * Watchdog glue for the prompted loop.
 *
 * [AgentStepWatchdog] decides nothing; this file is the only place that turns its answer into a loop
 * action. Keeping it here lets `AgentPromptedLoopCoordinator` — already at its line ceiling — gain the
 * integration with a single call per iteration.
 *
 * One call does all three things a step boundary needs, because the loop advances `stepIndex`
 * monotonically and therefore "the previous step finished" is implied by "a new step started":
 *
 * 1. report the **previous** step finished (before touching the new one),
 * 2. answer whether that previous step overran its limit,
 * 3. register the new step's start time.
 *
 * Checking *before* finishing matters: the watchdog only reports steps it still considers open, so a
 * step must be tested while it is still registered.
 */

/** Correlation id carried by the synthetic timeout error, for log grep-ability. */
internal const val WATCHDOG_CORRELATION_ID: String = "agent-step-watchdog"

/**
 * Begins [stepIndex] and reports whether the step before it overran the watchdog limit.
 *
 * Returns the pause result to return from the loop, or null when the run may continue. The very first
 * iteration has no previous step, so nothing can be overdue then.
 */
internal fun AgentPromptedLoopCoordinator.watchdogBeginStep(
    run: com.muyuchat.core.agent.contract.AgentRun,
    entries: MutableList<AgentTranscriptEntry>,
    stepIndex: Int,
    repairs: Int,
    spent: com.muyuchat.core.agent.contract.AgentSpentBudget
): AgentLoopResult.Paused? {
    val overdue = stepWatchdog.isCurrentStepOverdue()
    if (stepIndex > 0) stepWatchdog.onStepFinished(stepIndex - 1)
    if (!overdue) {
        stepWatchdog.onStepStarted(stepIndex)
        return null
    }
    // The step that overran is the one just finished; report it, not the one about to start.
    val failedStep = (stepIndex - 1).coerceAtLeast(0)
    entries += AgentTranscriptEntry.ProtocolError(
        errorCode = AgentErrorCodes.BUDGET_STEP_TIMEOUT,
        schemaHintJson = watchdogTimeoutHintJson(failedStep)
    )
    return AgentLoopResult.Paused(
        run = run.copy(spentBudget = spent),
        transcript = transcript(entries, stepIndex, repairs),
        reason = AgentPauseReason.BUDGET_LIMIT,
        error = AgentError(
            code = AgentErrorCodes.BUDGET_STEP_TIMEOUT,
            category = AgentErrorCategory.BUDGET,
            retryability = AgentRetryability.AFTER_USER_ACTION,
            safeMessage = "Step $failedStep exceeded the per-step watchdog limit and was paused.",
            correlationId = WATCHDOG_CORRELATION_ID
        )
    )
}

/**
 * Transcript hint for the overdue step.
 *
 * Hand-built JSON rather than a codec call: the hint has two fixed fields, it must never be able to
 * fail, and a serializer round-trip here would run inside the pause path.
 */
internal fun watchdogTimeoutHintJson(stepIndex: Int): String =
    """{"watchdog":"step_timeout","stepIndex":$stepIndex}"""
