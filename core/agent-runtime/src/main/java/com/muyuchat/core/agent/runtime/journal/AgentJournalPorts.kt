package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentArtifact
import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.AgentQuestion
import com.muyuchat.core.agent.contract.AgentTask

/**
 * Typed transactional rejection from an [com.muyuchat.core.agent.contract.AgentJournal]
 * implementation. The owner translates it into [com.muyuchat.core.agent.contract.AgentCommandResult]
 * without string matching.
 */
class AgentJournalRejectedException(
    val errorCode: String,
    override val message: String
) : RuntimeException(message)

/**
 * Narrow read ports that the frozen [com.muyuchat.core.agent.contract.AgentJournal] does not
 * expose. K05's Room implementation must implement the same interfaces; the runtime never reads
 * Room directly.
 */
interface AgentTaskReader {
    suspend fun readTask(taskId: String): AgentTask?
}

interface AgentQuestionReader {
    suspend fun readQuestion(questionId: String): AgentQuestion?
}

interface AgentApprovalReader {
    suspend fun readApproval(approvalId: String): AgentApproval?
}

interface AgentArtifactReader {
    suspend fun readArtifacts(taskId: String): List<AgentArtifact>
}

/**
 * Command-level idempotency record. The frozen journal only knows transition-level idempotency;
 * commands must be replayed *before* revision validation, so the owner needs this narrow ledger.
 * K05 can back it with the `agent_commands` table in the same transaction as the state change.
 */
data class AgentCommandRecord(
    val commandId: String,
    val contentHash: String,
    val appliedRevision: Long,
    val taskRevision: Long? = null,
    val runId: String? = null
)

interface AgentCommandLedger {
    suspend fun findCommand(commandId: String): AgentCommandRecord?

    /** Records a successful command; re-recording identical content is a no-op. */
    suspend fun recordCommand(record: AgentCommandRecord)
}

internal fun journalRejected(errorCode: String, message: String): Nothing =
    throw AgentJournalRejectedException(errorCode, message)

internal fun idempotencyConflict(commandId: String): Nothing = journalRejected(
    AgentErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
    "Command '$commandId' was already applied with different content."
)

internal fun revisionConflict(commandId: String): Nothing = journalRejected(
    AgentErrorCodes.COMMAND_REVISION_CONFLICT,
    "Command '$commandId' expected a different revision; refresh and retry."
)

internal fun invalidState(commandId: String, message: String): Nothing =
    journalRejected(AgentErrorCodes.COMMAND_INVALID_STATE, message)
