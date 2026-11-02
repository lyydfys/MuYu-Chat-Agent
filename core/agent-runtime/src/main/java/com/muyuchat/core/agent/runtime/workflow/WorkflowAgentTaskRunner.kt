package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.runtime.AgentJsonValue

/**
 * One child `AgentTask` requested by an `AGENT_TASK` workflow node (N18/N21/N22).
 *
 * The request contains only frozen, credential-free values: the explicit model binding, the run
 * policy and the remaining budget. It is not a second run engine; an implementation must start the
 * child through the one existing run owner command surface.
 */
data class WorkflowAgentTaskRequest(
    val workflowRunId: String,
    val nodeId: String,
    val goal: String,
    val modelBinding: AgentModelBinding,
    val policy: AgentRunPolicy,
    val budget: AgentBudget,
    val displayTitle: String = "Workflow agent task",
    /** Wall-clock ceiling for waiting on the child run; the parent workflow duration stays shared. */
    val waitTimeoutMillis: Long
) {
    init {
        require(workflowRunId.isNotBlank()) { "workflow run id must not be blank" }
        require(nodeId.isNotBlank()) { "workflow node id must not be blank" }
        require(goal.isNotBlank()) { "workflow agent task goal must not be blank" }
        require(goal.toByteArray(Charsets.UTF_8).size <= MAX_GOAL_BYTES) {
            "workflow agent task goal exceeds $MAX_GOAL_BYTES bytes"
        }
        require(displayTitle.isNotBlank()) { "workflow agent task display title must not be blank" }
        require(waitTimeoutMillis > 0L) { "waitTimeoutMillis must be positive" }
    }

    private companion object {
        const val MAX_GOAL_BYTES = 8 * 1024
    }
}

/**
 * Terminal answer of the child run.
 *
 * [Failed] necessarily carries the child's spent budget so the parent can account shared budget even
 * when the child failed. [NotSupported] is the honest "no run owner is wired" answer and must never
 * be converted into a node success.
 */
sealed interface WorkflowAgentTaskOutcome {
    data class Succeeded(
        val runId: String,
        val taskId: String,
        val output: AgentJsonValue,
        val spent: AgentSpentBudget
    ) : WorkflowAgentTaskOutcome

    data class Failed(
        val errorCode: String,
        val safeMessage: String,
        val spent: AgentSpentBudget = AgentSpentBudget()
    ) : WorkflowAgentTaskOutcome

    data class NotSupported(val safeMessage: String) : WorkflowAgentTaskOutcome
}

/**
 * Narrow seam between the graph node executor and the one production run owner.
 *
 * It exposes exactly one operation and no engine, loop, scheduler, model adapter or Android type.
 * Implementations must start the child through the existing owner commands so budget, cancellation,
 * approval and artifact accounting stay in the single writer.
 */
fun interface WorkflowAgentTaskRunner {
    suspend fun run(request: WorkflowAgentTaskRequest): WorkflowAgentTaskOutcome
}

/** Fail-closed default: without an owner the node returns a typed NOT_SUPPORTED, not a success. */
object NoWorkflowAgentTaskRunner : WorkflowAgentTaskRunner {
    override suspend fun run(request: WorkflowAgentTaskRequest): WorkflowAgentTaskOutcome =
        WorkflowAgentTaskOutcome.NotSupported(
            "No run owner is wired, so an agent-task node cannot start a child task."
        )
}

/** Stable child-run codes; every one of them is a typed failure, never a fake success. */
object WorkflowAgentTaskErrorCodes {
    const val CREATE_REJECTED = "WF_AGENT_TASK_CREATE_REJECTED"
    const val CREATE_FAILED = "WF_AGENT_TASK_CREATE_FAILED"
    const val ANCHOR_MISSING = "WF_AGENT_TASK_ANCHOR_MISSING"
    const val START_REJECTED = "WF_AGENT_TASK_START_REJECTED"
    const val RUN_ID_MISSING = "WF_AGENT_TASK_RUN_ID_MISSING"
    const val OBSERVER_LOST = "WF_AGENT_TASK_OBSERVER_LOST"
    const val TIMEOUT = "WF_AGENT_TASK_TIMEOUT"
    const val CANCELLED = "WF_AGENT_TASK_CANCELLED"
    const val FAILED = "WF_AGENT_TASK_FAILED"
    const val SUPERSEDED = "WF_AGENT_TASK_SUPERSEDED"
    const val PAUSED = "WF_AGENT_TASK_PAUSED"
    const val NEEDS_REVIEW = "WF_AGENT_TASK_NEEDS_REVIEW"
    const val INTERRUPTED = "WF_AGENT_TASK_INTERRUPTED"
    const val NOT_SETTLED = "WF_AGENT_TASK_NOT_SETTLED"
}
