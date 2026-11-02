package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentEventPage
import com.muyuchat.core.agent.contract.AgentRunSnapshot
import com.muyuchat.core.agent.contract.AgentTaskPage
import com.muyuchat.core.agent.contract.AgentTaskQuery
import com.muyuchat.core.agent.contract.AgentWorkspaceQueries
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Read-only Room projection for the Agent page.
 *
 * Every observation is derived from persisted rows and never starts/retries model work. Queries are
 * bounded by [AgentDataLimits]; task paging fetches `limit + 1` rows only to compute the next cursor.
 */
class RoomAgentWorkspaceQueries(
    private val taskDao: AgentTaskDao,
    private val runDao: AgentRunDao,
    private val eventDao: AgentEventDao,
    private val stepDao: AgentStepDao,
    private val approvalDao: AgentApprovalDao,
    private val questionDao: AgentQuestionDao,
    private val artifactDao: AgentArtifactDao
) : AgentWorkspaceQueries {
    override fun observeTasks(query: AgentTaskQuery): Flow<AgentTaskPage> {
        require(query.limit in 1..AgentDataLimits.MAX_TASK_PAGE_LIMIT) {
            "task page limit must be in 1..${AgentDataLimits.MAX_TASK_PAGE_LIMIT}"
        }
        val cursor = query.cursor?.let { raw ->
            AgentTaskCursor.decode(raw)
                ?: throw IllegalArgumentException("task cursor is not a valid page key")
        }
        val fetchLimit = query.limit + 1
        val daoFlow = if (cursor == null) {
            taskDao.observePage(
                includeArchived = query.includeArchived,
                cursorUpdatedAt = null,
                cursorTaskId = null,
                limit = fetchLimit
            )
        } else {
            taskDao.observePage(
                includeArchived = query.includeArchived,
                cursorUpdatedAt = cursor.first,
                cursorTaskId = cursor.second,
                limit = fetchLimit
            )
        }
        return daoFlow.map { entities -> entities.toTaskPage(query.limit) }
    }

    override fun observeRun(runId: String): Flow<AgentRunSnapshot> =
        runDao.observe(runId).map { runEntity ->
            if (runEntity == null) {
                null
            } else {
                val task = taskDao.find(runEntity.taskId)
                if (task == null) {
                    null
                } else {
                    AgentRunSnapshot(
                        run = AgentEntityMappers.toContract(runEntity),
                        task = AgentEntityMappers.toContract(task),
                        activeQuestion = questionDao.findOpenForRun(runId)
                            ?.let(AgentEntityMappers::toContract),
                        pendingApproval = approvalDao.findPendingForRun(runId)
                            ?.let(AgentEntityMappers::toContract),
                        steps = stepDao.listByRun(runId, AgentDataLimits.MAX_STEP_PAGE_LIMIT)
                            .map(AgentEntityMappers::toContract),
                        artifacts = artifactDao
                            .listByRun(runId, AgentDataLimits.MAX_ARTIFACT_PAGE_LIMIT)
                            .map { AgentEntityMappers.toStoredArtifact(it).artifact }
                    )
                }
            }
        }.filterNotNull()

    override fun observeEvents(runId: String, afterSequence: Long, limit: Int): Flow<AgentEventPage> {
        require(afterSequence >= 0L) { "afterSequence must not be negative" }
        require(limit in 1..AgentDataLimits.MAX_EVENT_PAGE_LIMIT) {
            "event page limit must be in 1..${AgentDataLimits.MAX_EVENT_PAGE_LIMIT}"
        }
        return eventDao.observePage(runId, afterSequence, limit).map { entities ->
            val events = entities.map(AgentEntityMappers::toContract)
            AgentEventPage(events = events, nextSequence = events.lastOrNull()?.sequence)
        }
    }
}
