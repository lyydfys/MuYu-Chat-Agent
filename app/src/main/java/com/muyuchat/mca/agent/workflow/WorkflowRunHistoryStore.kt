package com.muyuchat.mca.agent.workflow

enum class WorkflowTriggerKind { MANUAL, SCHEDULE }

enum class WorkflowRunStatus { STARTED, COMPLETED, FAILED, REJECTED }

/** One recorded workflow start; [finishedAtMillis]/[errorCode] are filled when the run ends. */
data class WorkflowRunHistoryEntry(
    val runId: String,
    val definitionId: String,
    val version: Long,
    val trigger: WorkflowTriggerKind,
    val slotMillis: Long? = null,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val status: WorkflowRunStatus = WorkflowRunStatus.STARTED,
    val errorCode: String? = null
) {
    init {
        require(runId.isNotBlank()) { "history runId must not be blank" }
        require(definitionId.isNotBlank()) { "history definitionId must not be blank" }
        require(version >= 1L) { "history version must be positive" }
        require(startedAtMillis > 0L) { "history start time must be positive" }
    }
}

/**
 * Bounded run history for workflow entry points (N20/N22).
 *
 * The entry service writes STARTED before execution and the terminal status after it. History is
 * evidence for the user ("what ran, when, triggered how"), not a second execution state machine; a
 * missing/failed history write never changes whether the workflow ran.
 */
interface WorkflowRunHistoryStore {
    /** Inserts or replaces by [entry.runId] so a STARTED row can be completed in place. */
    suspend fun put(entry: WorkflowRunHistoryEntry)

    /** Newest first, bounded by [limit]. */
    suspend fun list(limit: Int): List<WorkflowRunHistoryEntry>
}
