package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentError
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentPauseReason
import com.muyuchat.core.agent.contract.AgentRetryability
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.run.AgentLoopResume
import com.muyuchat.core.agent.runtime.run.AgentLoopTranscript
import com.muyuchat.core.agent.runtime.run.AgentRunExecutor
import com.muyuchat.core.agent.runtime.workflow.WorkflowUserDecisions
import com.muyuchat.core.agent.runtime.workflow.WorkflowUserWait
import com.muyuchat.mca.agent.tools.WorkflowToolRunState

/**
 * The graph execution strategy of the one Agent run owner (N18/N19).
 *
 * It is selected only when the run policy pins a published workflow version. Tool nodes execute
 * through the shared invocation pipeline against this exact run/task, approvals park the run in the
 * owner's real WAITING_APPROVAL state, and a resume executes only the authorized command before the
 * runner continues from its committed checkpoint. No second loop, model attempt or tool executor is
 * created here.
 */
class AgentWorkflowGraphRunExecutor(
    private val service: WorkflowExecutionService
) : AgentRunExecutor {

    override suspend fun execute(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult {
        val state = WorkflowToolRunState(
            run = run,
            transcript = resume?.transcript?.entries.orEmpty()
        )
        val outcome = try {
            service.executeFrame(run, task, resume, state, progress)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return AgentLoopResult.Failed(
                run = state.run,
                transcript = transcript(state, resume),
                error = error(
                    run,
                    "WF_EXECUTION_FAILED",
                    "The workflow graph execution stopped unexpectedly.",
                    AgentErrorCategory.INTERNAL
                )
            )
        }
        return when (outcome) {
            is WorkflowFrameOutcome.Completed -> AgentLoopResult.Completed(
                run = state.run,
                transcript = transcript(state, resume),
                finalText = COMPLETION_TEXT,
                artifactIds = emptyList()
            )
            is WorkflowFrameOutcome.Suspended -> AgentLoopResult.WaitingApproval(
                run = state.run,
                transcript = transcript(state, resume),
                invocationId = outcome.invocationId,
                callHash = outcome.callHash,
                approval = outcome.approval
            )
            // A workflow wait is a durable user park on the one run owner: the same WAITING_USER
            // commit, park token, slot release and wake path as an ask-user turn. The waiting node
            // was never committed, so the resumed execution re-enters it with the answer.
            is WorkflowFrameOutcome.SuspendedForUser -> AgentLoopResult.WaitingUser(
                run = state.run,
                transcript = transcript(state, resume),
                questionText = WorkflowWaitText.questionFor(outcome.wait),
                options = WorkflowWaitText.optionsFor(outcome.wait)
            )
            is WorkflowFrameOutcome.Failed -> {
                if (outcome.errorCode == WorkflowExecutionErrorCodes.TOOL_UNKNOWN) {
                    // The ledger row is UNKNOWN; retrying the node is refused by the invocation
                    // pipeline, so park the run for review instead of claiming success/failure.
                    AgentLoopResult.NeedsReview(
                        run = state.run,
                        transcript = transcript(state, resume),
                        error = error(
                            run,
                            WorkflowExecutionErrorCodes.TOOL_UNKNOWN,
                            outcome.safeMessage,
                            AgentErrorCategory.TOOL,
                            possibleSideEffects = true
                        )
                    )
                } else {
                    // The graph already ran its compensations; if one of them failed the effect is
                    // no longer fully undone, so the run error must not look side-effect free. The
                    // terminal code stays the original failure.
                    val compensationFailed = outcome.outcomes.any {
                        it.errorCode == COMPENSATION_FAILED
                    }
                    AgentLoopResult.Failed(
                        run = state.run,
                        transcript = transcript(state, resume),
                        error = error(
                            run,
                            stableCode(outcome.errorCode),
                            outcome.safeMessage,
                            AgentErrorCategory.TOOL,
                            possibleSideEffects = compensationFailed
                        )
                    )
                }
            }
            is WorkflowFrameOutcome.Rejected -> AgentLoopResult.Failed(
                run = state.run,
                transcript = transcript(state, resume),
                error = error(
                    run,
                    stableCode(outcome.errorCode),
                    outcome.safeMessage,
                    AgentErrorCategory.POLICY
                )
            )
        }
    }

    private fun transcript(
        state: WorkflowToolRunState,
        resume: AgentLoopResume?
    ): AgentLoopTranscript {
        val entries = state.transcriptSnapshot()
        return AgentLoopTranscript(
            entries = entries,
            nextStepIndex = entries.size,
            repairsUsed = resume?.transcript?.repairsUsed ?: 0
        )
    }

    private fun error(
        run: AgentRun,
        code: String,
        message: String,
        category: AgentErrorCategory,
        possibleSideEffects: Boolean = false
    ): AgentError = AgentError(
        code = code,
        category = category,
        retryability = AgentRetryability.AFTER_USER_ACTION,
        safeMessage = message.take(MAX_ERROR_CHARS),
        correlationId = run.runId,
        possibleSideEffects = possibleSideEffects
    )

    /** AgentError codes are UPPER_SNAKE_CASE; workflow codes already follow that shape. */
    private fun stableCode(code: String): String {
        val normalized = code.uppercase().filter { it.isLetterOrDigit() || it == '_' }
        return normalized.takeIf { it.length >= 2 } ?: "WF_NODE_FAILED"
    }

    private companion object {
        const val COMPLETION_TEXT = "Workflow completed."
        const val COMPENSATION_FAILED = "WF_COMPENSATION_FAILED"
        const val MAX_ERROR_CHARS = 512
    }
}

/**
 * The only user-visible text for a workflow wait, plus the stable decision mapping.
 *
 * The answer the owner persists is whatever option the user picked, so both the localized labels
 * and the core tokens are accepted; anything else becomes a typed invalid-decision failure inside
 * the runner instead of an accidental approval.
 */
internal object WorkflowWaitText {
    private const val APPROVE_LABEL = "批准"
    private const val DENY_LABEL = "拒绝"
    private const val CONTINUE_LABEL = "继续"
    private const val ABORT_LABEL = "中止"

    fun questionFor(wait: WorkflowUserWait): String = when (wait) {
        is WorkflowUserWait.Approval ->
            "工作流节点 ${wait.nodeId} 正在等待你的批准，是否继续执行？"
        is WorkflowUserWait.NodeFailure ->
            "工作流节点 ${wait.nodeId} 失败（${wait.errorCode}）：${wait.safeMessage}" +
                "是否继续其余分支？"
    }

    fun optionsFor(wait: WorkflowUserWait): List<String> = when (wait) {
        is WorkflowUserWait.Approval -> listOf(APPROVE_LABEL, DENY_LABEL)
        is WorkflowUserWait.NodeFailure -> listOf(CONTINUE_LABEL, ABORT_LABEL)
    }

    /** Null for a blank or unrecognized answer, which the runner treats as "no decision yet". */
    fun decisionFor(answer: String?): String? = when (answer?.trim()) {
        null, "" -> null
        WorkflowUserDecisions.APPROVE, APPROVE_LABEL -> WorkflowUserDecisions.APPROVE
        WorkflowUserDecisions.DENY, DENY_LABEL -> WorkflowUserDecisions.DENY
        WorkflowUserDecisions.CONTINUE, CONTINUE_LABEL -> WorkflowUserDecisions.CONTINUE
        WorkflowUserDecisions.ABORT, ABORT_LABEL -> WorkflowUserDecisions.ABORT
        // An unknown answer must fail closed: return a non-token so the runner rejects it rather
        // than silently re-parking or approving.
        else -> INVALID_DECISION
    }

    /** Not a valid token; the runner's decision `when` rejects it with `WF_WAIT_DECISION_INVALID`. */
    const val INVALID_DECISION = "invalid"
}
