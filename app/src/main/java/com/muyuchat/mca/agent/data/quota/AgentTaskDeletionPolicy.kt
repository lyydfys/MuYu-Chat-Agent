package com.muyuchat.mca.agent.data.quota

import com.muyuchat.core.agent.contract.AgentRun
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import com.muyuchat.mca.agent.data.AgentClock

/** Room/owner adapter supplied by composition; the policy never touches Room or the runtime. */
interface AgentTaskDeletionPort {
    suspend fun findTask(taskId: String): AgentTask?

    /** Latest non-terminal run for the task, or null when no run holds a slot. */
    suspend fun findLatestNonTerminalRun(taskId: String): AgentRun?

    /** Sends the exact scoped cancel for this run; never a global stop. */
    suspend fun requestCancel(run: AgentRun): AgentCancelOutcome

    /** CAS marks the task TOMBSTONED; false on a revision conflict. */
    suspend fun markTombstoned(taskId: String, expectedRevision: Long): Boolean

    /** Deletes related DB rows after the tombstone is visible. */
    suspend fun deleteRelatedRecords(taskId: String): Boolean

    /** Best-effort private file cleanup; never other tasks. */
    suspend fun deleteTaskFiles(workspaceId: String, taskId: String): Boolean
}

sealed interface AgentCancelOutcome {
    data object Accepted : AgentCancelOutcome

    data object Missing : AgentCancelOutcome

    data class Rejected(val code: String) : AgentCancelOutcome
}

enum class AgentDeletionErrorCode {
    NOT_FOUND,
    CANCEL_REJECTED,
    REVISION_CONFLICT,
    INVALID_STATE
}

sealed interface AgentFileCleanupResult {
    data object Cleaned : AgentFileCleanupResult

    data object NotNeeded : AgentFileCleanupResult

    data class Deferred(val record: AgentFileCleanupRetryRecord) : AgentFileCleanupResult
}

sealed interface AgentTaskDeletionResult {
    data class Deleted(
        val taskId: String,
        val recordsDeleted: Boolean,
        val fileCleanup: AgentFileCleanupResult
    ) : AgentTaskDeletionResult

    /** Cancel command accepted; retry deleteTask after the run reaches a terminal state. */
    data class CancellationRequested(val taskId: String, val runId: String) : AgentTaskDeletionResult

    /** Run is already PAUSING/STOPPING; retry after the owner confirms stop. */
    data class AwaitingStop(val taskId: String, val runId: String) : AgentTaskDeletionResult

    data class Rejected(
        val code: AgentDeletionErrorCode,
        val safeMessage: String
    ) : AgentTaskDeletionResult
}

data class AgentTaskDeletionRetryResult(
    val record: AgentFileCleanupRetryRecord,
    val cleared: Boolean,
    val recordAfter: AgentFileCleanupRetryRecord?
)

/**
 * Ordered task deletion policy (architecture 10.5 / acceptance D16):
 *
 * 1. an active (slot-holding) run is cancelled first; the task is never tombstoned while the run is
 *    still active;
 * 2. TOMBSTONED is written only after no non-terminal active run remains;
 * 3. related DB rows are deleted after the tombstone;
 * 4. private file cleanup is best-effort and failures are recorded in a bounded retry store.
 *
 * PAUSED/NEEDS_REVIEW/INTERRUPTED runs hold no execution slot and do not block the tombstone.
 */
class AgentTaskDeletionPolicy(
    private val port: AgentTaskDeletionPort,
    private val retryStore: AgentFileCleanupRetryStore,
    private val clock: AgentClock = AgentClock.system(),
    private val maxCleanupAttempts: Int = 5,
    private val retryDelayMillis: Long = 60_000L
) {
    suspend fun deleteTask(taskId: String): AgentTaskDeletionResult {
        val task = safe { port.findTask(taskId) } ?: return rejected(
            AgentDeletionErrorCode.NOT_FOUND,
            "task was not found"
        )
        if (task.lifecycle == AgentTaskLifecycle.TOMBSTONED) {
            // Idempotent completion: a previous attempt may have tombstoned the row but failed to
            // delete all relational rows or files. Never cancel or tombstone again.
            val recordsDeleted = safe { port.deleteRelatedRecords(taskId) } ?: false
            val cleanup = if (recordsDeleted) {
                cleanupFiles(taskId, task.workspaceId)
            } else {
                AgentFileCleanupResult.NotNeeded
            }
            return AgentTaskDeletionResult.Deleted(taskId, recordsDeleted, cleanup)
        }
        val nonTerminal = safe { port.findLatestNonTerminalRun(taskId) }
        if (nonTerminal != null && nonTerminal.state.holdsActiveSlot) {
            val outcome = safe { port.requestCancel(nonTerminal) }
                ?: return rejected(AgentDeletionErrorCode.CANCEL_REJECTED, "cancel request failed")
            when (outcome) {
                is AgentCancelOutcome.Rejected -> return rejected(
                    AgentDeletionErrorCode.CANCEL_REJECTED,
                    "active run refused cancellation"
                )
                AgentCancelOutcome.Missing -> Unit
                AgentCancelOutcome.Accepted -> Unit
            }
            val stillActive = safe { port.findLatestNonTerminalRun(taskId) }
            if (stillActive != null && stillActive.state.holdsActiveSlot) {
                return when (stillActive.state.name) {
                    "STOPPING", "PAUSING" -> AgentTaskDeletionResult.AwaitingStop(taskId, stillActive.runId)
                    else -> AgentTaskDeletionResult.CancellationRequested(taskId, stillActive.runId)
                }
            }
        }
        val tombstoned = safe { port.markTombstoned(taskId, task.revision) } ?: false
        if (!tombstoned) {
            return rejected(
                AgentDeletionErrorCode.REVISION_CONFLICT,
                "task changed while deletion was being prepared"
            )
        }
        val recordsDeleted = safe { port.deleteRelatedRecords(taskId) } ?: false
        // Only clean files after the relational rows are gone; deleting files while DB rows remain
        // would leave dangling artifact references. A failed record deletion is retried by the caller.
        val cleanup = if (recordsDeleted) {
            cleanupFiles(taskId, task.workspaceId)
        } else {
            AgentFileCleanupResult.NotNeeded
        }
        return AgentTaskDeletionResult.Deleted(
            taskId = taskId,
            recordsDeleted = recordsDeleted,
            fileCleanup = cleanup
        )
    }

    /** Bounded retry of previously deferred file cleanups, used by startup/maintenance. */
    suspend fun retryPendingCleanup(limit: Int = 20): List<AgentTaskDeletionRetryResult> {
        require(limit in 1..200) { "limit must be in 1..200" }
        val now = clock.nowMillis()
        return retryStore.pending(limit).mapNotNull { record ->
            if (record.nextAttemptAtMillis > now) return@mapNotNull null
            if (record.attempts >= maxCleanupAttempts) {
                return@mapNotNull AgentTaskDeletionRetryResult(
                    record = record,
                    cleared = false,
                    recordAfter = record
                )
            }
            val task = safe { port.findTask(record.taskId) }
            if (task != null && task.lifecycle != AgentTaskLifecycle.TOMBSTONED) {
                // Never delete files for a live/imported task; drop the stale maintenance hint.
                retryStore.clear(record.taskId)
                return@mapNotNull AgentTaskDeletionRetryResult(record, cleared = true, recordAfter = null)
            }
            val deleted = safe { port.deleteTaskFiles(record.workspaceId, record.taskId) } ?: false
            if (deleted) {
                retryStore.clear(record.taskId)
                AgentTaskDeletionRetryResult(record, cleared = true, recordAfter = null)
            } else {
                val failure = record.copy(
                    attempts = record.attempts + 1,
                    lastFailedAtMillis = now,
                    nextAttemptAtMillis = now + retryDelay(record.attempts + 1)
                )
                retryStore.recordFailure(failure)
                AgentTaskDeletionRetryResult(record, cleared = false, recordAfter = failure)
            }
        }
    }

    private suspend fun cleanupFiles(taskId: String, workspaceId: String): AgentFileCleanupResult {
        val previous = safe { retryStore.find(taskId) }
        if (previous != null && previous.attempts >= maxCleanupAttempts) {
            return AgentFileCleanupResult.Deferred(previous)
        }
        val deleted = safe { port.deleteTaskFiles(workspaceId, taskId) } ?: false
        if (deleted) {
            safe { retryStore.clear(taskId) }
            return AgentFileCleanupResult.Cleaned
        }
        val now = clock.nowMillis()
        val attempts = (previous?.attempts ?: 0) + 1
        val record = AgentFileCleanupRetryRecord(
            taskId = taskId,
            workspaceId = workspaceId,
            attempts = attempts,
            firstFailedAtMillis = previous?.firstFailedAtMillis ?: now,
            lastFailedAtMillis = now,
            nextAttemptAtMillis = now + retryDelay(attempts),
            reasonCode = "CLEANUP_INCOMPLETE"
        )
        safe { retryStore.recordFailure(record) }
        return AgentFileCleanupResult.Deferred(record)
    }

    private fun retryDelay(attempts: Int): Long {
        val multiplier = 1L shl minOf(attempts - 1, 6)
        return retryDelayMillis * multiplier
    }

    private fun rejected(code: AgentDeletionErrorCode, message: String): AgentTaskDeletionResult.Rejected =
        AgentTaskDeletionResult.Rejected(code, message)

    private suspend fun <T> safe(block: suspend () -> T): T? = try {
        block()
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
