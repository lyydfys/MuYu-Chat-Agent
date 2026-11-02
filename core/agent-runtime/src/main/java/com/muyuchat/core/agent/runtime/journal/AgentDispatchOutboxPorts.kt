package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.ToolInvocation
import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.protocol.AgentHashes

/**
 * Durable dispatch-outbox state, separate from the invocation state machine.
 *
 * The outbox records *when an approved invocation may be handed to the single executor*; it never
 * duplicates the invocation statuses. `PENDING/CLAIMED/DISPATCHING` describe the dispatch attempt,
 * while [AgentToolInvocationLedger] stays the only writer of `APPROVED/RUNNING/terminal`.
 *
 * Crash contract (N03 / N19, WF-15..16):
 * - a `PENDING` or lease-expired `CLAIMED` entry was never handed to an executor and may be
 *   dispatched again after revalidating the authoritative invocation row;
 * - a `DISPATCHING` entry has a dispatch intent recorded, so an unknown receipt is fenced as
 *   [AgentDispatchOutboxStatus.NEEDS_REVIEW] and is never resent automatically;
 * - terminal invocations settle the entry as [AgentDispatchOutboxStatus.SETTLED] without executing
 *   anything again;
 * - [AgentDispatchOutboxStatus.BYPASSED] records that an authorized in-process path already took the
 *   invocation, so the outbox must never dispatch it (no success is implied either).
 */
enum class AgentDispatchOutboxStatus {
    PENDING,
    CLAIMED,
    DISPATCHING,
    SETTLED,
    EXPIRED,
    FAILED,
    NEEDS_REVIEW,
    BYPASSED;

    val isTerminal: Boolean
        get() = this == SETTLED || this == EXPIRED || this == FAILED || this == NEEDS_REVIEW ||
            this == BYPASSED

    val isLeaseHolding: Boolean
        get() = this == CLAIMED || this == DISPATCHING
}

/**
 * One durable "approved, awaiting dispatch" record.
 *
 * [idempotencyKey] is derived deterministically from [invocationId] (`obx-<sha256>`), so a repeated
 * approval completion, a repeated wake or a restarted dispatcher always address the same row.
 * [revision] is a compare-and-set token: every transition must present the revision it observed.
 */
data class AgentDispatchOutboxEntry(
    val idempotencyKey: String,
    val invocationId: String,
    val runId: String,
    val stepId: String,
    val callOrdinal: Int,
    val toolKey: String,
    val toolVersion: String,
    val approvedAtMillis: Long,
    val status: AgentDispatchOutboxStatus = AgentDispatchOutboxStatus.PENDING,
    val leaseOwner: String? = null,
    val leaseExpiresAtMillis: Long = 0L,
    val dispatchAttempts: Int = 0,
    val settledInvocationStatus: ToolInvocationStatus? = null,
    val errorCode: String? = null,
    val updatedAtMillis: Long = approvedAtMillis,
    val revision: Long = 0L
) {
    init {
        AgentContract.requireNotBlank(idempotencyKey, "idempotencyKey")
        AgentContract.requireId(invocationId, "invocationId")
        AgentContract.requireId(runId, "runId")
        AgentContract.requireId(stepId, "stepId")
        require(callOrdinal >= 0) { "callOrdinal must not be negative" }
        AgentContract.requireNotBlank(toolKey, "toolKey")
        AgentContract.requireNotBlank(toolVersion, "toolVersion")
        require(approvedAtMillis >= 0L) { "approvedAtMillis must not be negative" }
        require(dispatchAttempts >= 0) { "dispatchAttempts must not be negative" }
        require(revision >= 0L) { "revision must not be negative" }
        if (status.isLeaseHolding) {
            val owner = requireNotNull(leaseOwner) { "a lease-holding entry must name its owner" }
            AgentContract.requireNotBlank(owner, "leaseOwner")
            require(leaseExpiresAtMillis > 0L) { "a lease-holding entry must carry an expiry" }
        } else {
            require(leaseOwner == null) { "a non-lease entry must not name a lease owner" }
            require(leaseExpiresAtMillis == 0L) { "a non-lease entry must not carry a lease expiry" }
        }
        if (status == AgentDispatchOutboxStatus.SETTLED) {
            require(settledInvocationStatus != null && settledInvocationStatus.isTerminal) {
                "SETTLED must record the terminal invocation status"
            }
        } else {
            require(settledInvocationStatus == null) {
                "only a SETTLED entry may record a terminal invocation status"
            }
        }
        if (status == AgentDispatchOutboxStatus.EXPIRED ||
            status == AgentDispatchOutboxStatus.FAILED ||
            status == AgentDispatchOutboxStatus.NEEDS_REVIEW ||
            status == AgentDispatchOutboxStatus.BYPASSED
        ) {
            val code = requireNotNull(errorCode) { "$status must carry a reason code" }
            AgentContract.requireNotBlank(code, "errorCode")
        } else {
            require(errorCode == null) { "$status must not carry a failure code" }
        }
    }

    fun leaseIsActiveAt(nowMillis: Long): Boolean =
        status.isLeaseHolding && leaseExpiresAtMillis > nowMillis

    /** Terminal transition shared by every store adapter; enforces the outbox invariants. */
    fun settledAs(
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        atMillis: Long
    ): AgentDispatchOutboxEntry {
        require(status.isTerminal) { "settledAs requires a terminal status" }
        require(!this.status.isTerminal) { "an outbox entry can only settle once" }
        return copy(
            status = status,
            leaseOwner = null,
            leaseExpiresAtMillis = 0L,
            settledInvocationStatus = invocationStatus,
            errorCode = errorCode,
            updatedAtMillis = atMillis,
            revision = revision + 1L
        )
    }

    companion object {
        /** Stable outbox identity for one invocation: replays cannot create a second row. */
        fun keyFor(invocationId: String): String =
            "obx-" + AgentHashes.sha256HexUtf8(invocationId).take(48)

        /**
         * Builds the durable dispatch record from the authoritative approved invocation row.
         * The caller must only use this after the approval transaction committed `APPROVED`.
         */
        fun approved(invocation: ToolInvocation, atMillis: Long): AgentDispatchOutboxEntry {
            require(invocation.status == ToolInvocationStatus.APPROVED) {
                "an outbox entry requires an APPROVED invocation"
            }
            return AgentDispatchOutboxEntry(
                idempotencyKey = keyFor(invocation.invocationId),
                invocationId = invocation.invocationId,
                runId = invocation.runId,
                stepId = invocation.stepId,
                callOrdinal = invocation.callOrdinal,
                toolKey = invocation.toolKey,
                toolVersion = invocation.toolVersion,
                approvedAtMillis = atMillis,
                updatedAtMillis = atMillis
            )
        }
    }
}

sealed interface AgentDispatchEnqueueResult {
    /**
     * The entry was written, or the same idempotency key already carried the same invocation.
     * A terminal existing entry is returned unchanged: a duplicate approval/wake must not re-open it.
     */
    data class Recorded(val entry: AgentDispatchOutboxEntry) : AgentDispatchEnqueueResult

    /** The store refused the write; no dispatch may be inferred from it. */
    data class Rejected(val errorCode: String, val safeMessage: String) : AgentDispatchEnqueueResult
}

sealed interface AgentDispatchClaimResult {
    /** This dispatcher owns a fresh lease; it may record the dispatch intent next. */
    data class Granted(val entry: AgentDispatchOutboxEntry) : AgentDispatchClaimResult

    /** Another live lease, a dispatch already in flight, or a terminal entry owns the row. */
    data object NotAvailable : AgentDispatchClaimResult

    data object NotFound : AgentDispatchClaimResult
}

sealed interface AgentDispatchUpdateResult {
    data class Updated(val entry: AgentDispatchOutboxEntry) : AgentDispatchUpdateResult

    /** CAS loss, a lease owned by another dispatcher, or a forbidden transition. */
    data class Rejected(val errorCode: String, val safeMessage: String) : AgentDispatchUpdateResult
}

/**
 * Persistence seam for the dispatch outbox.
 *
 * Implementations must:
 * - make [enqueue] idempotent on [AgentDispatchOutboxEntry.idempotencyKey];
 * - make [claim] exclusive: at most one caller receives a fresh lease for a row;
 * - reject [markDispatching]/[release] when the lease owner or revision does not match;
 * - make [settle] a single terminal transition that can never be re-opened.
 *
 * `DISPATCHING` is deliberately not claimable. A crashed dispatch must be reconciled from the
 * invocation ledger (and fenced as NEEDS_REVIEW when the receipt is unknown), never resent.
 */
interface AgentDispatchOutboxStore {
    suspend fun enqueue(entry: AgentDispatchOutboxEntry): AgentDispatchEnqueueResult

    suspend fun read(idempotencyKey: String): AgentDispatchOutboxEntry?

    /** Non-terminal rows, oldest update first; used by the dispatcher's bounded pass. */
    suspend fun listActive(limit: Int): List<AgentDispatchOutboxEntry>

    suspend fun claim(
        idempotencyKey: String,
        leaseOwner: String,
        nowMillis: Long,
        leaseMillis: Long
    ): AgentDispatchClaimResult

    suspend fun markDispatching(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult

    /** Returns the lease to `PENDING` after a post-claim revalidation found nothing to execute. */
    suspend fun release(
        idempotencyKey: String,
        expectedRevision: Long,
        leaseOwner: String,
        nowMillis: Long
    ): AgentDispatchUpdateResult

    suspend fun settle(
        idempotencyKey: String,
        expectedRevision: Long,
        status: AgentDispatchOutboxStatus,
        invocationStatus: ToolInvocationStatus?,
        errorCode: String?,
        nowMillis: Long
    ): AgentDispatchUpdateResult
}