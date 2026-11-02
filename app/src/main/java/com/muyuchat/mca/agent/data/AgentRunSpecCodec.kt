package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.runtime.run.AgentRunSpec

/**
 * Decodes the frozen run creation context persisted on `agent_tasks` (preferred) or, for tasks
 * created through the binding-less `CreateTaskTransition`, the latest run's frozen context.
 *
 * Returns null rather than inventing defaults when no frozen context exists; the owner must then
 * reject the start with a typed invalid-state error.
 */
object AgentRunSpecCodec {
    fun decode(task: AgentTaskEntity, latestRun: AgentRunEntity?): AgentRunSpec? {
        val bindingJson = task.modelBindingJson ?: latestRun?.modelBindingJson ?: return null
        val policyJson = if (task.modelBindingJson != null) {
            task.policyJson ?: return null
        } else {
            latestRun?.policyJson ?: return null
        }
        val budgetJson = if (task.modelBindingJson != null) {
            task.budgetJson ?: return null
        } else {
            latestRun?.budgetJson ?: return null
        }
        return AgentRunSpec(
            modelBinding = AgentRecordJson.decodeBinding(bindingJson),
            policy = AgentRecordJson.decodePolicy(policyJson),
            budget = AgentRecordJson.decodeBudget(budgetJson)
        )
    }
}
