package com.muyuchat.core.agent.contract

/** Task lifecycle is independent from run execution state (architecture 10.1). */
enum class AgentTaskLifecycle {
    ACTIVE,
    IMPORTED_ARCHIVED,
    TOMBSTONED
}

/**
 * Transactional projection of the task's latest run. It is not a second task state machine:
 * the run remains authoritative and this value is updated in the same commit.
 */
enum class AgentDisplayStatus {
    IDLE,
    QUEUED,
    RUNNING,
    WAITING_RESOURCE,
    WAITING_APPROVAL,
    WAITING_USER,
    PAUSING,
    STOPPING,
    PAUSED,
    NEEDS_REVIEW,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}

data class AgentTask(
    val taskId: String,
    val workspaceId: String,
    val goal: String,
    val lifecycle: AgentTaskLifecycle,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val displayStatus: AgentDisplayStatus,
    val lastRunId: String?,
    val revision: Long,
    val schemaVersion: Int = AgentContract.SCHEMA_VERSION
) {
    init {
        AgentContract.requireId(taskId, "taskId")
        AgentContract.requireId(workspaceId, "workspaceId")
        AgentContract.requireNotBlank(goal, "goal")
        require(createdAtMillis >= 0L) { "createdAtMillis must not be negative" }
        require(updatedAtMillis >= createdAtMillis) { "updatedAtMillis must not precede createdAtMillis" }
        require(revision >= 0L) { "task revision must not be negative" }
        lastRunId?.let { AgentContract.requireId(it, "lastRunId") }
    }
}

/** Cursor-free, bounded task list query. Observing never starts a run. */
data class AgentTaskQuery(
    val limit: Int = 50,
    val cursor: String? = null,
    val includeArchived: Boolean = false
) {
    init {
        require(limit in 1..200) { "AgentTaskQuery.limit must be in 1..200" }
    }
}

data class AgentTaskPage(
    val tasks: List<AgentTask>,
    val nextCursor: String? = null
)

/**
 * Creates the task and its private workspace grant. No run is created here; [StartAgentRun]
 * is a separate idempotent command so page recomposition can never start generation.
 */
data class CreateAgentTask(
    val commandId: String,
    val goal: String,
    val displayTitle: String,
    val modelBinding: AgentModelBinding,
    val policy: AgentRunPolicy,
    val budget: AgentBudget = AgentBudget(),
    /**
     * Optional memory spaces selected by the user for this task. Empty means "no memory tool".
     *
     * The value is frozen into the task's [AgentRunPolicy.memorySpaceIds] by the task-creation
     * path; a run resolves its read grant from that frozen selection, never from this command
     * object or from model text. Defaults to empty so existing callers stay source-compatible.
     */
    val memorySpaceIds: Set<String> = emptySet()
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireNotBlank(goal, "goal")
    }
}

/** Continues a task by creating a successor run; the old run is superseded atomically. */
data class ContinueAgentTask(
    val commandId: String,
    val taskId: String,
    val expectedTaskRevision: Long,
    val goalOverride: String? = null,
    val modelBinding: AgentModelBinding? = null,
    val policy: AgentRunPolicy? = null,
    val budget: AgentBudget? = null
) {
    init {
        AgentContract.requireId(commandId, "commandId")
        AgentContract.requireId(taskId, "taskId")
        require(expectedTaskRevision >= 0L) { "expectedTaskRevision must not be negative" }
    }
}
