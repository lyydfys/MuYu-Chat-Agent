package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelProgressSink
import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.runtime.run.AgentLoopResult
import com.muyuchat.core.agent.runtime.run.AgentLoopResume

/**
 * Narrow production seam for a same-root `AGENT_TASK` frame.
 *
 * The only implementation is the one model loop with an explicit empty tool catalog. It receives
 * the parent run/task identities and never creates a child task/run; the workflow service maps the
 * loop result to one node outcome. Tool/approval frames are rejected before this seam.
 */
fun interface WorkflowAgentFrameExecutor {
    suspend fun execute(
        run: AgentRun,
        task: AgentTask,
        resume: AgentLoopResume?,
        progress: AgentModelProgressSink
    ): AgentLoopResult
}
