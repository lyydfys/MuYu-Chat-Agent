package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic JVM store used by the runtime tests and by owners with no durable outbox yet.
 * Production wiring replaces it with the file/Room adapter; behavior is the same contract.
 */
class InMemoryAgentDispatchOutboxStore : AgentDispatchOutboxStore {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, AgentDispatchOutboxEntry>()

    override suspend fun enqueue(entry: AgentDispatchOutboxEntry): AgentDispatchEnqueueResult =
        mutex.withLock {
            val fresh = entry.copy(
                status = AgentDispatchOutboxStatus.PENDING,
                leaseOwner = null,
                leaseExpiresAtMillis = 0L,
                dispatchAttempts = 0,
                settledInvocationStatus = null,
                errorCode = null,
                revision = 0L
            )
            val existing = entries[fresh.idempotencyKey]
            if (existing != null) {
                return@withLock if (sameIdentity(existing, fresh)) {
                    AgentDispatchEnqueueResult.Recorded(existing)
                } else {
                    AgentDispatchEnqueueResult.Rejected(
                        OUTBOX_IDENTITY_CONFLICT,
                        "The outbox key already belongs to another invocation."
                    )
                }
            }
            entries[fresh.idempotencyKey] = fresh
            AgentDispatchEnqueueResult.Recorded(fresh)
        }

    override suspend fun read(idempotencyKey: String): AgentDispatchOutboxEntry? =
        mutex.withLock { entries[idempotencyKey] }

    override suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntry> {
        require(limit > 0) { "limit must be positive" }
        return mutex.withLock {
            entries.values
                .asSequence()
                .filterNot { it.status.isTerminal }
                .sortedWith(compareBy({ it.updatedAtMillis }, { it.idempotencyKey }))
                .take(limit)
                .toList()
        }
    }

    override suspend fun claim(
        idempotencyKey: String,
        leaseOwner: String,
        nowMillis: Long,
        leaseMillis: Long
    ): AgentDispatchClaimResult = mutex.withLock {
        require(leaseMillis > 0L) { "leaseMillis must be positive" }
        AgentContract.requireNotBlank(leaseOwner, "leaseOwner")
        val current = entries[idempotencyKey] ?: return@withLock AgentDispatchClaimResult.NotFound
        if (current.status.isTerminal) return@withLock AgentDispatchClaimResult.NotAvailable
        if (current.status == AgentDispatchOutboxStatus.DISPATCHING) {
            return@withLock AgentDispatchClaimResult.NotAvailable
        }
        if (current.status == AgentDispatchOutboxStatus.CLAIMED && current.leaseIsActiveAt(nowMillis)) {
            return@withLock AgentDispatchClaimResult.NotAvailable
        }
        val claimed = current.copy(
            status = AgentDispatchOutboxStatus.CLAIMED,
            leaseOwner = leaseOwner,
            leaseExpiresAtMillis = nowMillis + leaseMillis,
            dispatchAttempts = current.dispatchAttempts + 1,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        entries[idempotencyKey] = claimed
        AgentDispatchClaimResult.Granted(claimed)
    }

    override suspend fun markDispatching(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult = mutex.withLock {
        val current = entries[idempotencyKey]
            ?: return@withLock rejection(NOT_FOUND, "The outbox entry was not found.")
        if (current.revision != expectedRevision) {
            return@withLock rejection(CAS_LOST, "The outbox entry changed before dispatch.")
        }
        if (current.status != AgentDispatchOutboxStatus.CLAIMED ||
            current.leaseOwner != leaseOwner ||
            !current.leaseIsActiveAt(nowMillis)
        ) {
            return@withLock rejection(LEASE_LOST, "The dispatch lease is no longer held.")
        }
        val dispatching = current.copy(
            status = AgentDispatchOutboxStatus.DISPATCHING,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        entries[idempotencyKey] = dispatching
        AgentDispatchUpdateResult.Updated(dispatching)
    }

    override suspend fun release(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult = mutex.withLock {
        val current = entries[idempotencyKey]
            ?: return@withLock rejection(NOT_FOUND, "The outbox entry was not found.")
        if (current.revision != expectedRevision) {
            return@withLock rejection(CAS_LOST, "The outbox entry changed before release.")
        }
        if (current.status != AgentDispatchOutboxStatus.CLAIMED || current.leaseOwner != leaseOwner) {
            return@withLock rejection(LEASE_LOST, "Only the lease owner may release the entry.")
        }
        val released = current.copy(
            status = AgentDispatchOutboxStatus.PENDING,
            leaseOwner = null,
            leaseExpiresAtMillis = 0L,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        entries[idempotencyKey] = released
        AgentDispatchUpdateResult.Updated(released)
    }

    override suspend fun settle(
        idempotencyKey: String,
        expectedRevision: Long,
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        nowMillis: Long
    ): AgentDispatchUpdateResult = mutex.withLock {
        require(status.isTerminal) { "settle requires a terminal outbox status" }
        val current = entries[idempotencyKey]
            ?: return@withLock rejection(NOT_FOUND, "The outbox entry was not found.")
        if (current.revision != expectedRevision) {
            return@withLock rejection(CAS_LOST, "The outbox entry changed before settlement.")
        }
        if (current.status.isTerminal) {
            return@withLock rejection(ALREADY_TERMINAL, "The outbox entry is already terminal.")
        }
        val settled = current.settledAs(status, invocationStatus, errorCode, nowMillis)
        entries[idempotencyKey] = settled
        AgentDispatchUpdateResult.Updated(settled)
    }

    private fun sameIdentity(existing: AgentDispatchOutboxEntry, offered: AgentDispatchOutboxEntry): Boolean =
        existing.invocationId == offered.invocationId &&
            existing.runId == offered.runId &&
            existing.stepId == offered.stepId &&
            existing.callOrdinal == offered.callOrdinal &&
            existing.toolKey == offered.toolKey &&
            existing.toolVersion == offered.toolVersion

    private fun rejection(errorCode: String, message: String): AgentDispatchUpdateResult =
        AgentDispatchUpdateResult.Rejected(errorCode, message)

    private companion object {
        const val NOT_FOUND: String = "COMMAND_NOT_FOUND"
        const val CAS_LOST: String = "COMMAND_INVALID_STATE"
        const val LEASE_LOST: String = "RESOURCE_LEASE_LOST"
        const val ALREADY_TERMINAL: String = "COMMAND_INVALID_STATE"
        const val OUTBOX_IDENTITY_CONFLICT: String = "COMMAND_INVALID_STATE"
    }
}
