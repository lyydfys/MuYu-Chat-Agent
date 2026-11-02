package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.runtime.journal.AgentCommandLedger
import com.muyuchat.core.agent.runtime.journal.AgentCommandRecord

/**
 * Room-backed runtime [AgentCommandLedger].
 *
 * This is the command-level idempotency record used by K03's owner BEFORE revision validation. It is
 * intentionally a different table from `agent_commands` (transition-level idempotency written by
 * [RoomAgentJournal]): the owner hashes the command DTO while the journal hashes the frozen
 * transition, so a shared primary key would produce false `IDEMPOTENCY_CONFLICT`s.
 *
 * [recordCommand] is transactional:
 *  * absent → insert the record;
 *  * present with the same content hash → no-op (a replay is allowed even after revisions advanced);
 *  * present with different content → `COMMAND_IDEMPOTENCY_CONFLICT`.
 */
class RoomAgentCommandLedger(
    private val dao: AgentCommandResultDao,
    private val transactions: AgentTransactionRunner,
    private val clock: AgentClock = AgentClock.system()
) : AgentCommandLedger {
    override suspend fun findCommand(commandId: String): AgentCommandRecord? {
        AgentContract.requireId(commandId, "commandId")
        return transactions.inTransaction {
            dao.find(commandId)?.let(::toRecord)
        }
    }

    override suspend fun recordCommand(record: AgentCommandRecord) {
        AgentContract.requireId(record.commandId, "commandId")
        require(record.contentHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "contentHash must be a lowercase SHA-256"
        }
        require(record.appliedRevision >= 0L) { "appliedRevision must not be negative" }
        record.taskRevision?.let { require(it >= 0L) { "taskRevision must not be negative" } }
        record.runId?.let { AgentContract.requireId(it, "runId") }
        transactions.inTransaction {
            translatingJournalErrors {
                val existing = dao.find(record.commandId)
                when {
                    existing == null -> dao.insert(
                        AgentCommandResultEntity(
                            commandId = record.commandId,
                            contentHash = record.contentHash,
                            appliedRevision = record.appliedRevision,
                            taskRevision = record.taskRevision,
                            runId = record.runId,
                            createdAt = clock.nowMillis()
                        )
                    )
                    existing.contentHash == record.contentHash -> Unit
                    else -> throw AgentJournalException(
                        AgentDataErrorCodes.COMMAND_IDEMPOTENCY_CONFLICT,
                        "command ${record.commandId} was already recorded with different content"
                    )
                }
            }
        }
    }

    private fun toRecord(entity: AgentCommandResultEntity): AgentCommandRecord =
        AgentCommandRecord(
            commandId = entity.commandId,
            contentHash = entity.contentHash,
            appliedRevision = entity.appliedRevision,
            taskRevision = entity.taskRevision,
            runId = entity.runId
        )
}
