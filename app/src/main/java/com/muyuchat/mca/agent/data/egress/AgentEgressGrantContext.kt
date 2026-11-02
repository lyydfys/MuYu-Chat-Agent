package com.muyuchat.mca.agent.data.egress

import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.mca.agent.data.AgentRecordJson
import com.muyuchat.mca.agent.data.AgentRunDao
import com.muyuchat.mca.agent.data.AgentTaskDao

/** Current authoritative policy context for one task-scope or run-scope grant. */
data class AgentEgressGrantContext(
    val taskId: String,
    val policyVersion: Long,
    val networkPolicy: AgentNetworkPolicy
)

/**
 * Reads the current policyVersion/networkPolicy that a grant must match. Returning null (missing
 * task/run, missing frozen policy, malformed policy JSON) makes the store fail closed.
 */
fun interface AgentEgressGrantContextReader {
    suspend fun read(taskId: String?, runId: String?): AgentEgressGrantContext?
}

/** Room adapter over the task/run frozen policy JSON. No schema change is needed. */
class RoomAgentEgressGrantContextReader(
    private val taskDao: AgentTaskDao,
    private val runDao: AgentRunDao
) : AgentEgressGrantContextReader {
    override suspend fun read(taskId: String?, runId: String?): AgentEgressGrantContext? {
        if (runId != null) {
            val run = runDao.find(runId) ?: return null
            if (taskId != null && run.taskId != taskId) return null
            val policy = runCatching { AgentRecordJson.decodePolicy(run.policyJson) }.getOrNull()
                ?: return null
            return AgentEgressGrantContext(run.taskId, policy.policyVersion, policy.networkPolicy)
        }
        val effectiveTaskId = taskId ?: return null
        val task = taskDao.find(effectiveTaskId) ?: return null
        val policyJson = task.policyJson ?: return null
        val policy = runCatching { AgentRecordJson.decodePolicy(policyJson) }.getOrNull()
            ?: return null
        return AgentEgressGrantContext(task.taskId, policy.policyVersion, policy.networkPolicy)
    }
}
