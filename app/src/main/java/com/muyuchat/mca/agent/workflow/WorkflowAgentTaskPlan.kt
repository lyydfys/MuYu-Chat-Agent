package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentRunPolicy

/**
 * The frozen, credential-free execution profile an `AGENT_TASK` node starts its child task with.
 *
 * The model binding is the user's earlier explicit choice (as with the manual recipe launcher) and
 * the policy is the same declarative policy type the owner freezes into a task. The plan carries no
 * API key, token or provider secret: those stay inside the existing model transport.
 */
data class WorkflowAgentTaskPlan(
    val modelBinding: AgentModelBinding,
    val policy: AgentRunPolicy,
    val displayTitle: String = "Workflow agent task"
) {
    init {
        require(displayTitle.isNotBlank()) { "agent task display title must not be blank" }
        require(displayTitle.length <= MAX_DISPLAY_TITLE_CHARS) {
            "agent task display title exceeds $MAX_DISPLAY_TITLE_CHARS characters"
        }
    }

    private companion object {
        const val MAX_DISPLAY_TITLE_CHARS = 200
    }
}
