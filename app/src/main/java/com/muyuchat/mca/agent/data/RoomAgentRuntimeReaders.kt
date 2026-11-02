package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.runtime.journal.AgentApprovalReader
import com.muyuchat.core.agent.runtime.journal.AgentArtifactReader
import com.muyuchat.core.agent.runtime.journal.AgentQuestionReader
import com.muyuchat.core.agent.runtime.journal.AgentTaskReader

/**
 * Room-backed implementations of K03's narrow read ports.
 *
 * All lookups are bounded primary-key reads; [readArtifacts] is capped at
 * [AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT] committed rows for the task (newest by path/version).
 * The runtime never sees Room types and the journal's write transaction stays the only writer.
 */
class RoomAgentRuntimeReaders(
    private val taskDao: AgentTaskDao,
    private val questionDao: AgentQuestionDao,
    private val approvalDao: AgentApprovalDao,
    private val artifactDao: AgentArtifactDao
) : AgentTaskReader, AgentQuestionReader, AgentApprovalReader, AgentArtifactReader {
    override suspend fun readTask(taskId: String): AgentTask? =
        taskDao.find(taskId)?.let(AgentEntityMappers::toContract)

    override suspend fun readQuestion(questionId: String): AgentQuestion? =
        questionDao.find(questionId)?.let(AgentEntityMappers::toContract)

    override suspend fun readApproval(approvalId: String): AgentApproval? =
        approvalDao.find(approvalId)?.let(AgentEntityMappers::toContract)

    override suspend fun readArtifacts(taskId: String): List<AgentArtifact> =
        artifactDao.listCommitted(taskId, AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT)
            .map { AgentEntityMappers.toStoredArtifact(it).artifact }
}
