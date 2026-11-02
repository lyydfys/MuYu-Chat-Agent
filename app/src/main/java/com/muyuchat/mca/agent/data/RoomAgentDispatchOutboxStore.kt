package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchEnqueueResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult

/**
 * Room-backed dispatch outbox (N03/N19 follow-up).
 *
 * Every mutation runs inside the shared [AgentTransactionRunner], so when the owner calls
 * `markApproved` inside its approval transaction, the outbox row is written in the *same* Room
 * transaction as the approval CAS and the invocation APPROVED state. The file store remains only
 * as the legacy source imported once at startup.
 *
 * Invariants match [com.muyuchat.mca.agent.reliability.FileAgentDispatchOutboxStore]: enqueue is
 * idempotent per key; `claim` grants one live lease; `DISPATCHING` is never claimable; terminal
 * rows never re-open; every transition CASes on `revision`.
 */
class RoomAgentDispatchOutboxStore(
    private val dao: AgentDispatchOutboxDao,
    private val transactions: AgentTransactionRunner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS
) : AgentDispatchOutboxStore {
    init {
        require(retentionMillis > 0L) { "retentionMillis must be positive" }
    }

    override suspend fun enqueue(entry: AgentDispatchOutboxEntry): AgentDispatchEnqueueResult =
        transactions.inTransaction {
            try {
                dao.pruneTerminal(clock() - retentionMillis)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Retention is best effort; it must not block a new approval.
            }
            val fresh = entry.copy(
                status = AgentDispatchOutboxStatus.PENDING,
                leaseOwner = null,
                leaseExpiresAtMillis = 0L,
                dispatchAttempts = 0,
                settledInvocationStatus = null,
                errorCode = null,
                updatedAtMillis = clock(),
                revision = 0L
            )
            val existing = dao.find(fresh.idempotencyKey)
            if (existing != null) {
                val decoded = existing.toCoreOrNull()
                    ?: return@inTransaction AgentDispatchEnqueueResult.Rejected(
                        AgentErrorCodes.STORAGE_CORRUPTION,
                        "The existing outbox row is not readable."
                    )
                return@inTransaction if (sameIdentity(decoded, fresh)) {
                    AgentDispatchEnqueueResult.Recorded(decoded)
                } else {
                    AgentDispatchEnqueueResult.Rejected(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "The outbox key already belongs to another invocation."
                    )
                }
            }
            try {
                dao.insert(fresh.toEntity())
                AgentDispatchEnqueueResult.Recorded(fresh)
            } catch (_: Throwable) {
                AgentDispatchEnqueueResult.Rejected(
                    AgentErrorCodes.STORAGE_NO_SPACE,
                    "The dispatch outbox row could not be written."
                )
            }
        }

    override suspend fun read(idempotencyKey: String): AgentDispatchOutboxEntry? =
        transactions.inTransaction { dao.find(idempotencyKey)?.toCoreOrNull() }

    override suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntry> {
        require(limit > 0) { "limit must be positive" }
        return transactions.inTransaction {
            dao.listActive(limit).mapNotNull { it.toCoreOrNull() }
        }
    }

    override suspend fun claim(
        idempotencyKey: String,
        leaseOwner: String,
        nowMillis: Long,
        leaseMillis: Long
    ): AgentDispatchClaimResult {
        require(leaseMillis > 0L) { "leaseMillis must be positive" }
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        return transactions.inTransaction {
            val current = dao.find(idempotencyKey)?.toCoreOrNull()
                ?: return@inTransaction AgentDispatchClaimResult.NotFound
            if (current.status.isTerminal) {
                return@inTransaction AgentDispatchClaimResult.NotAvailable
            }
            if (current.status == AgentDispatchOutboxStatus.DISPATCHING) {
                return@inTransaction AgentDispatchClaimResult.NotAvailable
            }
            if (current.status == AgentDispatchOutboxStatus.CLAIMED &&
                current.leaseIsActiveAt(nowMillis)
            ) {
                return@inTransaction AgentDispatchClaimResult.NotAvailable
            }
            val claimed = current.copy(
                status = AgentDispatchOutboxStatus.CLAIMED,
                leaseOwner = leaseOwner,
                leaseExpiresAtMillis = nowMillis + leaseMillis,
                dispatchAttempts = current.dispatchAttempts + 1,
                updatedAtMillis = nowMillis,
                revision = current.revision + 1L
            )
            if (!claimed.persistCas(dao, current.revision)) {
                return@inTransaction AgentDispatchClaimResult.NotAvailable
            }
            AgentDispatchClaimResult.Granted(claimed)
        }
    }

    override suspend fun markDispatching(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult = transactions.inTransaction {
        val current = dao.find(idempotencyKey)?.toCoreOrNull()
            ?: return@inTransaction rejection(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "The outbox entry was not found."
            )
        if (current.revision != expectedRevision) {
            return@inTransaction rejection(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The outbox entry changed before dispatch."
            )
        }
        if (current.status != AgentDispatchOutboxStatus.CLAIMED ||
            current.leaseOwner != leaseOwner ||
            !current.leaseIsActiveAt(nowMillis)
        ) {
            return@inTransaction rejection(
                AgentErrorCodes.RESOURCE_LEASE_LOST,
                "The dispatch lease is no longer held."
            )
        }
        val dispatching = current.copy(
            status = AgentDispatchOutboxStatus.DISPATCHING,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        if (!dispatching.persistCas(dao, current.revision)) {
            return@inTransaction rejection(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The dispatch intent CAS lost."
            )
        }
        AgentDispatchUpdateResult.Updated(dispatching)
    }

    override suspend fun release(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult = transactions.inTransaction {
        val current = dao.find(idempotencyKey)?.toCoreOrNull()
            ?: return@inTransaction rejection(
                AgentErrorCodes.COMMAND_NOT_FOUND,
                "The outbox entry was not found."
            )
        if (current.revision != expectedRevision) {
            return@inTransaction rejection(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The outbox entry changed before release."
            )
        }
        if (current.status != AgentDispatchOutboxStatus.CLAIMED ||
            current.leaseOwner != leaseOwner
        ) {
            return@inTransaction rejection(
                AgentErrorCodes.RESOURCE_LEASE_LOST,
                "Only the lease owner may release the entry."
            )
        }
        val released = current.copy(
            status = AgentDispatchOutboxStatus.PENDING,
            leaseOwner = null,
            leaseExpiresAtMillis = 0L,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        if (!released.persistCas(dao, current.revision)) {
            return@inTransaction rejection(
                AgentErrorCodes.COMMAND_INVALID_STATE,
                "The release CAS lost."
            )
        }
        AgentDispatchUpdateResult.Updated(released)
    }

    override suspend fun settle(
        idempotencyKey: String,
        expectedRevision: Long,
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        nowMillis: Long
    ): AgentDispatchUpdateResult {
        require(status.isTerminal) { "settle requires a terminal outbox status" }
        return transactions.inTransaction {
            val current = dao.find(idempotencyKey)?.toCoreOrNull()
                ?: return@inTransaction rejection(
                    AgentErrorCodes.COMMAND_NOT_FOUND,
                    "The outbox entry was not found."
                )
            if (current.revision != expectedRevision) {
                return@inTransaction rejection(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The outbox entry changed before settlement."
                )
            }
            if (current.status.isTerminal) {
                return@inTransaction rejection(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The outbox entry is already terminal."
                )
            }
            val settled = current.settledAs(status, invocationStatus, errorCode, nowMillis)
            if (!settled.persistCas(dao, current.revision)) {
                return@inTransaction rejection(
                    AgentErrorCodes.COMMAND_INVALID_STATE,
                    "The settlement CAS lost."
                )
            }
            AgentDispatchUpdateResult.Updated(settled)
        }
    }

    /**
     * One-time import of a legacy file-outbox row, preserving its exact status/lease/revision.
     * Idempotent by idempotency key; a key already present in Room is never overwritten.
     */
    suspend fun importLegacy(entry: AgentDispatchOutboxEntry): Boolean = transactions.inTransaction {
        if (dao.find(entry.idempotencyKey) != null) return@inTransaction false
        dao.insert(entry.toEntity())
        true
    }

    private suspend fun AgentDispatchOutboxEntry.persistCas(
        dao: AgentDispatchOutboxDao,
        expectedRevision: Long
    ): Boolean = dao.updateCas(
        idempotencyKey = idempotencyKey,
        expectedRevision = expectedRevision,
        status = status.name,
        leaseOwner = leaseOwner,
        leaseExpiresAtMillis = leaseExpiresAtMillis,
        dispatchAttempts = dispatchAttempts,
        settledInvocationStatus = settledInvocationStatus?.name,
        errorCode = errorCode,
        updatedAtMillis = updatedAtMillis,
        newRevision = revision
    ) == 1

    private fun AgentDispatchOutboxEntry.toEntity(): AgentDispatchOutboxEntity =
        AgentDispatchOutboxEntity(
            idempotencyKey = idempotencyKey,
            invocationId = invocationId,
            runId = runId,
            stepId = stepId,
            callOrdinal = callOrdinal,
            toolKey = toolKey,
            toolVersion = toolVersion,
            approvedAtMillis = approvedAtMillis,
            status = status.name,
            leaseOwner = leaseOwner,
            leaseExpiresAtMillis = leaseExpiresAtMillis,
            dispatchAttempts = dispatchAttempts,
            settledInvocationStatus = settledInvocationStatus?.name,
            errorCode = errorCode,
            updatedAtMillis = updatedAtMillis,
            revision = revision
        )

    private fun AgentDispatchOutboxEntity.toCoreOrNull(): AgentDispatchOutboxEntry? {
        val status = AgentDispatchOutboxStatus.entries.firstOrNull { it.name == this.status }
            ?: return null
        val settled = settledInvocationStatus?.let { name ->
            ToolInvocationStatus.entries.firstOrNull { it.name == name } ?: return null
        }
        return runCatching {
            AgentDispatchOutboxEntry(
                idempotencyKey = idempotencyKey,
                invocationId = invocationId,
                runId = runId,
                stepId = stepId,
                callOrdinal = callOrdinal,
                toolKey = toolKey,
                toolVersion = toolVersion,
                approvedAtMillis = approvedAtMillis,
                status = status,
                leaseOwner = leaseOwner,
                leaseExpiresAtMillis = leaseExpiresAtMillis,
                dispatchAttempts = dispatchAttempts,
                settledInvocationStatus = settled,
                errorCode = errorCode,
                updatedAtMillis = updatedAtMillis,
                revision = revision
            )
        }.getOrNull()
    }

    private fun sameIdentity(
        existing: AgentDispatchOutboxEntry,
        offered: AgentDispatchOutboxEntry
    ): Boolean =
        existing.invocationId == offered.invocationId &&
            existing.runId == offered.runId &&
            existing.stepId == offered.stepId &&
            existing.callOrdinal == offered.callOrdinal &&
            existing.toolKey == offered.toolKey &&
            existing.toolVersion == offered.toolVersion

    private fun rejection(errorCode: String, message: String): AgentDispatchUpdateResult =
        AgentDispatchUpdateResult.Rejected(errorCode, message)

    companion object {
        const val DEFAULT_RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
    }
}
