package com.muyuchat.mca.agent.data.quota

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentRunState
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentEntityMappers
import com.muyuchat.mca.agent.data.AgentGrantDao
import com.muyuchat.mca.agent.data.AgentJournalDaos
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import kotlinx.coroutines.CancellationException

/** Composition-supplied exact scoped cancel; must never call a global stop. */
fun interface AgentRunCancelRequester {
    suspend fun requestCancel(run: AgentRun): AgentCancelOutcome
}

/**
 * Room implementation of [AgentTaskDeletionPort] (K14).
 *
 * `deleteRelatedRecords` removes child rows in FK-safe order and deliberately keeps the TOMBSTONED
 * task row for audit. Commands/events are audit records; child rows are deleted but the task shell
 * remains so a retry cannot resurrect execution.
 */
class RoomAgentTaskDeletionPort(
    private val daos: AgentJournalDaos,
    private val transactions: AgentTransactionRunner,
    private val cancelRequester: AgentRunCancelRequester,
    private val fileCleaner: AgentTaskFileCleaner,
    private val grantDao: AgentGrantDao,
    private val clock: AgentClock = AgentClock.system()
) : AgentTaskDeletionPort {
    override suspend fun findTask(taskId: String): AgentTask? =
        daos.task.find(taskId)?.let(AgentEntityMappers::toContract)

    override suspend fun findLatestNonTerminalRun(taskId: String): AgentRun? {
        val states = AgentRunState.entries.filterNot { it.isTerminal }.map { it.name }
        return daos.run.listByTaskAndStates(taskId, states, 1)
            .firstOrNull()
            ?.let(AgentEntityMappers::toContract)
    }

    override suspend fun requestCancel(run: AgentRun): AgentCancelOutcome =
        cancelRequester.requestCancel(run)

    override suspend fun markTombstoned(taskId: String, expectedRevision: Long): Boolean =
        transactions.inTransaction {
            daos.task.markTombstonedCas(
                taskId = taskId,
                expectedRevision = expectedRevision,
                newRevision = expectedRevision + 1,
                updatedAt = clock.nowMillis()
            ) == 1
        }

    override suspend fun deleteRelatedRecords(taskId: String): Boolean = try {
        transactions.inTransaction {
            // FK-safe order: commits -> artifacts -> approval/question/event/grant -> invocations ->
            // steps -> attempts -> runs. The task row itself stays TOMBSTONED for audit/idempotency.
            daos.artifactCommit.deleteForTaskArtifacts(taskId)
            daos.artifact.deleteByTaskId(taskId)
            daos.approval.deleteForRunsOfTask(taskId)
            daos.question.deleteForRunsOfTask(taskId)
            daos.event.deleteForRunsOfTask(taskId)
            grantDao.deleteForTask(taskId)
            daos.invocation.deleteForRunsOfTask(taskId)
            daos.step.deleteForRunsOfTask(taskId)
            daos.attempt.deleteForRunsOfTask(taskId)
            daos.run.deleteByTaskId(taskId)
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    override suspend fun deleteTaskFiles(workspaceId: String, taskId: String): Boolean =
        fileCleaner.deleteTaskFiles(workspaceId, taskId)
}
