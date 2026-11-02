package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentContract

/** Terminal projection of one entry-facade call; it never contains a "started" row. */
enum class WorkflowEntryStatus { COMPLETED, FAILED, REJECTED, PAUSED }

/**
 * Durable receipt of one manual/scheduled entry command (WF-18 idempotency).
 *
 * The receipt is keyed by [commandId] and duplicated behind [runId], so a double click or a replayed
 * schedule wake returns the original run id/status/outputs instead of starting a second run. It is
 * evidence of the terminal result, not a second execution state machine: the run checkpoint remains
 * authoritative for resuming execution.
 *
 * [idempotentReplay] is a response projection only; it is never persisted (a decoded receipt starts
 * false and the facade sets it true when serving a cached row).
 */
data class WorkflowEntryReceipt(
    val commandId: String,
    val definitionId: String,
    val version: Long,
    val trigger: WorkflowTriggerKind,
    val runId: String?,
    val slotMillis: Long? = null,
    val status: WorkflowEntryStatus,
    val errorCode: String? = null,
    val safeMessage: String? = null,
    val outputsJson: String? = null,
    val completedAtMillis: Long,
    val idempotentReplay: Boolean = false
) {
    init {
        AgentContract.requireId(commandId, "entry receipt commandId")
        require(definitionId.isNotBlank()) { "entry receipt definitionId must not be blank" }
        require(version >= 1L) { "entry receipt version must be positive" }
        require(completedAtMillis > 0L) { "entry receipt completion time must be positive" }
        require(safeMessage == null || safeMessage.length <= MAX_MESSAGE_CHARS) {
            "entry receipt safe message is too long"
        }
        require(outputsJson == null || outputsJson.toByteArray(Charsets.UTF_8).size <= MAX_OUTPUT_BYTES) {
            "entry receipt outputs exceed $MAX_OUTPUT_BYTES bytes"
        }
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 400
        const val MAX_OUTPUT_BYTES = 64 * 1024
    }
}

/**
 * Bounded durable receipt lookup for the manual/schedule entry facade.
 *
 * Implementations must be fail-closed: a corrupt store must throw rather than report "no receipt",
 * because silently forgetting a receipt could start the same command twice (the run checkpoint still
 * prevents node re-execution, but the entry decision would be wrong).
 */
interface WorkflowEntryReceiptStore {
    suspend fun get(commandId: String): WorkflowEntryReceipt?

    suspend fun findByRunId(runId: String): WorkflowEntryReceipt?

    /** Inserts or replaces by [commandId]; bounded newest-first retention. */
    suspend fun put(receipt: WorkflowEntryReceipt)

    suspend fun list(limit: Int): List<WorkflowEntryReceipt>
}

/**
 * In-memory receipt store for tests and for a single-process owner that already has durable
 * checkpoints. It keeps the same bounded retention as the file store so callers see identical
 * semantics; it does not survive process death.
 */
class InMemoryWorkflowEntryReceiptStore(
    initial: List<WorkflowEntryReceipt> = emptyList()
) : WorkflowEntryReceiptStore {
    private val lock = Any()
    private val receipts = LinkedHashMap<String, WorkflowEntryReceipt>()

    init {
        initial.forEach { receipts[it.commandId] = it }
        trim()
    }

    override suspend fun get(commandId: String): WorkflowEntryReceipt? = synchronized(lock) {
        receipts[commandId]
    }

    override suspend fun findByRunId(runId: String): WorkflowEntryReceipt? = synchronized(lock) {
        receipts.values
            .filter { it.runId == runId }
            .maxByOrNull { it.completedAtMillis }
    }

    override suspend fun put(receipt: WorkflowEntryReceipt) {
        synchronized(lock) {
            receipts[receipt.commandId] = receipt.copy(idempotentReplay = false)
            trim()
        }
    }

    override suspend fun list(limit: Int): List<WorkflowEntryReceipt> = synchronized(lock) {
        receipts.values
            .sortedByDescending { it.completedAtMillis }
            .take(limit.coerceAtLeast(0))
    }

    private fun trim() {
        if (receipts.size <= MAX_ENTRIES) return
        val keep = receipts.values
            .sortedByDescending { it.completedAtMillis }
            .take(MAX_ENTRIES)
            .mapTo(LinkedHashSet()) { it.commandId }
        receipts.keys.retainAll(keep)
    }

    private companion object {
        const val MAX_ENTRIES = 256
    }
}
