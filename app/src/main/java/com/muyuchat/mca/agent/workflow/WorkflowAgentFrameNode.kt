package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeExecution
import com.muyuchat.mca.agent.tools.WorkflowToolRunState
import kotlinx.coroutines.CancellationException

/**
 * S3a: prompt-only `AGENT_TASK` node over the one model loop under the parent run/task.
 *
 * This is a node executor, not a second loop: it hands the parent run/task to
 * [WorkflowAgentFrameExecutor], which runs with an explicit empty tool catalog. It never creates a
 * child task/run. Tool/skill/memory frames are rejected by the workflow preflight before this
 * class; a loop that still asks for the user is a typed failure. The frame's spent budget is
 * folded into the workflow run state so the owner settles the shared budget, and a parent tool
 * resume is never consumed here (a prompt-only frame cannot have produced it).
 */
internal class WorkflowAgentFrameNode(
    private val executor: WorkflowAgentFrameExecutor?
) {
    suspend fun execute(
        run: AgentRun?,
        task: AgentTask?,
        state: WorkflowToolRunState?,
        progress: AgentModelProgressSink?,
        goal: String,
        policy: AgentRunPolicy
    ): WorkflowNodeExecution {
        val parentRun = run
            ?: return failed(
                WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED,
                "The same-root frame has no parent run identity."
            )
        val parentTask = task
            ?: return failed(
                WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED,
                "The same-root frame has no parent task identity."
            )
        val frameExecutor = executor
            ?: return failed(
                WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED,
                "The same-root frame executor is not configured."
            )
        // Start from the latest state.run so prior workflow tool/model spending is not lost.
        val frameRun = (state?.run ?: parentRun).copy(policy = policy.copy(workflow = null))
        val frameTask = try {
            parentTask.copy(goal = goal)
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.CONFIG_INVALID,
                "The agent-task node goal is not a valid task goal."
            )
        }
        val result = try {
            frameExecutor.execute(
                frameRun,
                frameTask,
                null,
                progress ?: AgentModelProgressSink {}
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return failed(
                WorkflowExecutionErrorCodes.AGENT_TASK_FAILED,
                "The same-root agent frame stopped unexpectedly."
            )
        }
        state?.let { owned ->
            owned.updateRun(owned.run.copy(spentBudget = result.run.spentBudget))
        }
        return when (result) {
            is AgentLoopResult.Completed ->
                WorkflowNodeExecution.Succeeded(AgentJsonValue.Str(result.finalText))

            is AgentLoopResult.WaitingApproval,
            is AgentLoopResult.WaitingUser -> failed(
                WorkflowExecutionErrorCodes.FRAME_NOT_SUPPORTED,
                "An agent frame that needs user/approval interaction is not wired."
            )

            is AgentLoopResult.Paused -> failed(
                WorkflowExecutionErrorCodes.AGENT_TASK_FAILED,
                result.error?.safeMessage ?: "The same-root agent frame paused."
            )

            is AgentLoopResult.NeedsReview -> failed(
                WorkflowExecutionErrorCodes.AGENT_TASK_FAILED,
                result.error.safeMessage
            )

            is AgentLoopResult.Cancelled -> failed(
                WorkflowExecutionErrorCodes.AGENT_TASK_FAILED,
                "The same-root agent frame was cancelled."
            )

            is AgentLoopResult.Failed -> failed(
                WorkflowExecutionErrorCodes.AGENT_TASK_FAILED,
                result.error.safeMessage
            )
        }
    }

    private fun failed(code: String, message: String): WorkflowNodeExecution =
        WorkflowNodeExecution.Failed(code, message)
}
