package com.muyuchat.mca.agent.reliability

import com.muyuchat.core.agent.contract.AgentErrorCodes
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchClaimResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchEnqueueResult
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxEntry
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxJson
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStatus
import com.muyuchat.core.agent.runtime.journal.AgentDispatchOutboxStore
import com.muyuchat.core.agent.runtime.journal.AgentDispatchUpdateResult
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed dispatch outbox (N03/N19, pre-Room durable form).
 *
 * One canonical document holds all entries, written by an atomic temporary-file replacement. This
 * is deliberately a *single-writer* store: it is correct for the app's one Agent owner process and
 * for JVM tests, and it provides the real atomic-file evidence the task requires. It is **not** a
 * cross-process CAS; the production Room outbox table remains the N03 follow-up.
 *
 * - terminal entries older than [retentionMillis] are pruned on the next write so the document stays
 *   bounded; a replayed approval after pruning settles again from the authoritative invocation row;
 * - a corrupt document is never overwritten: [enqueue] returns a typed rejection and every other
 *   mutation throws, preserving the damaged evidence for review.
 */
class FileAgentDispatchOutboxStore(
    rootDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fileName: String = DEFAULT_FILE_NAME,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS
) : AgentDispatchOutboxStore {
    private val lock = Any()
    private val root: File = File(rootDirectory.absoluteFile, OUTBOX_DIRECTORY).also { it.mkdirs() }
    private val file: File = File(root, fileName).also { target ->
        require(target.canonicalFile.parentFile == root.canonicalFile) {
            "dispatch outbox file must stay inside its private root"
        }
    }

    init {
        require(fileName.matches(SAFE_FILE_NAME)) { "dispatch outbox file name is not safe" }
        require(retentionMillis > 0L) { "retentionMillis must be positive" }
    }

    override suspend fun enqueue(entry: AgentDispatchOutboxEntry): AgentDispatchEnqueueResult =
        synchronized(lock) {
            val loaded = try {
                load()
            } catch (failure: IllegalStateException) {
                return@synchronized AgentDispatchEnqueueResult.Rejected(
                    AgentErrorCodes.STORAGE_CORRUPTION,
                    "The dispatch outbox is not readable; it was left untouched."
                )
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
            val existing = loaded[fresh.idempotencyKey]
            if (existing != null) {
                return@synchronized if (sameIdentity(existing, fresh)) {
                    AgentDispatchEnqueueResult.Recorded(existing)
                } else {
                    AgentDispatchEnqueueResult.Rejected(
                        AgentErrorCodes.COMMAND_INVALID_STATE,
                        "The outbox key already belongs to another invocation."
                    )
                }
            }
            loaded[fresh.idempotencyKey] = fresh
            return@synchronized try {
                persist(loaded)
                AgentDispatchEnqueueResult.Recorded(fresh)
            } catch (failure: IllegalStateException) {
                AgentDispatchEnqueueResult.Rejected(
                    AgentErrorCodes.STORAGE_NO_SPACE,
                    "The dispatch outbox could not be written."
                )
            }
        }

    override suspend fun read(idempotencyKey: String): AgentDispatchOutboxEntry? = synchronized(lock) {
        load()[idempotencyKey]
    }

    override suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntry> {
        require(limit > 0) { "limit must be positive" }
        return synchronized(lock) {
            load().values
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
    ): AgentDispatchClaimResult = synchronized(lock) {
        require(leaseMillis > 0L) { "leaseMillis must be positive" }
        require(leaseOwner.isNotBlank()) { "leaseOwner must not be blank" }
        val entries = load()
        val current = entries[idempotencyKey] ?: return@synchronized AgentDispatchClaimResult.NotFound
        if (current.status.isTerminal) {
            return@synchronized AgentDispatchClaimResult.NotAvailable
        }
        if (current.status == AgentDispatchOutboxStatus.DISPATCHING) {
            return@synchronized AgentDispatchClaimResult.NotAvailable
        }
        if (current.status == AgentDispatchOutboxStatus.CLAIMED && current.leaseIsActiveAt(nowMillis)) {
            return@synchronized AgentDispatchClaimResult.NotAvailable
        }
        val claimed = current.copy(
            status = AgentDispatchOutboxStatus.CLAIMED,
            leaseOwner = leaseOwner,
            leaseExpiresAtMillis = nowMillis + leaseMillis,
            dispatchAttempts = current.dispatchAttempts + 1,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        persist(entries.apply { put(idempotencyKey, claimed) })
        AgentDispatchClaimResult.Granted(claimed)
    }

    override suspend fun markDispatching(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult = synchronized(lock) {
        val entries = load()
        val current = entries[idempotencyKey]
            ?: return@synchronized rejection(AgentErrorCodes.COMMAND_NOT_FOUND, "The outbox entry was not found.")
        if (current.revision != expectedRevision) {
            return@synchronized rejection(AgentErrorCodes.COMMAND_INVALID_STATE, "The outbox entry changed before dispatch.")
        }
        if (current.status != AgentDispatchOutboxStatus.CLAIMED ||
            current.leaseOwner != leaseOwner ||
            !current.leaseIsActiveAt(nowMillis)
        ) {
            return@synchronized rejection(AgentErrorCodes.RESOURCE_LEASE_LOST, "The dispatch lease is no longer held.")
        }
        val dispatching = current.copy(
            status = AgentDispatchOutboxStatus.DISPATCHING,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        persist(entries.apply { put(idempotencyKey, dispatching) })
        AgentDispatchUpdateResult.Updated(dispatching)
    }

    override suspend fun release(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult = synchronized(lock) {
        val entries = load()
        val current = entries[idempotencyKey]
            ?: return@synchronized rejection(AgentErrorCodes.COMMAND_NOT_FOUND, "The outbox entry was not found.")
        if (current.revision != expectedRevision) {
            return@synchronized rejection(AgentErrorCodes.COMMAND_INVALID_STATE, "The outbox entry changed before release.")
        }
        if (current.status != AgentDispatchOutboxStatus.CLAIMED || current.leaseOwner != leaseOwner) {
            return@synchronized rejection(AgentErrorCodes.RESOURCE_LEASE_LOST, "Only the lease owner may release the entry.")
        }
        val released = current.copy(
            status = AgentDispatchOutboxStatus.PENDING,
            leaseOwner = null,
            leaseExpiresAtMillis = 0L,
            updatedAtMillis = nowMillis,
            revision = current.revision + 1L
        )
        persist(entries.apply { put(idempotencyKey, released) })
        AgentDispatchUpdateResult.Updated(released)
    }

    override suspend fun settle(
        idempotencyKey: String,
        expectedRevision: Long,
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        nowMillis: Long
    ): AgentDispatchUpdateResult = synchronized(lock) {
        require(status.isTerminal) { "settle requires a terminal outbox status" }
        val entries = load()
        val current = entries[idempotencyKey]
            ?: return@synchronized rejection(AgentErrorCodes.COMMAND_NOT_FOUND, "The outbox entry was not found.")
        if (current.revision != expectedRevision) {
            return@synchronized rejection(AgentErrorCodes.COMMAND_INVALID_STATE, "The outbox entry changed before settlement.")
        }
        if (current.status.isTerminal) {
            return@synchronized rejection(AgentErrorCodes.COMMAND_INVALID_STATE, "The outbox entry is already terminal.")
        }
        val settled = current.settledAs(status, invocationStatus, errorCode, nowMillis)
        persist(entries.apply { put(idempotencyKey, settled) })
        AgentDispatchUpdateResult.Updated(settled)
    }

    private fun load(): MutableMap<String, AgentDispatchOutboxEntry> {
        if (!file.isFile) return LinkedHashMap()
        if (file.length() > MAX_BYTES) {
            throw IllegalStateException("the dispatch outbox exceeds its byte limit")
        }
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            throw IllegalStateException("the dispatch outbox could not be read", failure)
        }
        val decoded = AgentDispatchOutboxJson.decode(text)
            ?: throw IllegalStateException("the dispatch outbox is corrupt")
        return LinkedHashMap<String, AgentDispatchOutboxEntry>(decoded.size).apply {
            decoded.forEach { put(it.idempotencyKey, it) }
        }
    }

    private fun persist(entries: Map<String, AgentDispatchOutboxEntry>) {
        val now = clock()
        val writeCutoff = now - retentionMillis
        val retained = entries.values.filterNot { it.status.isTerminal && it.updatedAtMillis < writeCutoff }
        val bytes = AgentDispatchOutboxJson.encode(retained).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            throw IllegalStateException("the offered dispatch outbox is oversized")
        }
        val temp = File(root, "$fileName.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (failure: Exception) {
            temp.delete()
            throw IllegalStateException("dispatch outbox write failed", failure)
        }
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
        const val OUTBOX_DIRECTORY: String = "dispatch-outbox"
        const val DEFAULT_FILE_NAME: String = "outbox.json"
        const val DEFAULT_RETENTION_MILLIS: Long = 7L * 24L * 60L * 60L * 1000L
        const val MAX_BYTES: Long = 1024L * 1024L
        val SAFE_FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}
