package com.muyuchat.mca.agent.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `agent_dispatch_outbox` — durable approved-dispatch rows (N03/N19 follow-up).
 *
 * The row is written by [com.muyuchat.mca.agent.reliability.AgentApprovalOutboxLedger] inside the
 * owner's Room transaction, so the approval CAS, the invocation APPROVED state and the outbox row
 * commit together. [revision] is the compare-and-set token for every later transition; terminal
 * rows are pruned by the store's lazy retention pass.
 */
@Entity(
    tableName = "agent_dispatch_outbox",
    indices = [
        Index(
            value = ["status", "updatedAtMillis"],
            name = "index_agent_dispatch_outbox_status_updatedAtMillis"
        )
    ]
)
data class AgentDispatchOutboxEntity(
    @PrimaryKey val idempotencyKey: String,
    val invocationId: String,
    val runId: String,
    val stepId: String,
    val callOrdinal: Int,
    val toolKey: String,
    val toolVersion: String,
    val approvedAtMillis: Long,
    val status: String,
    val leaseOwner: String?,
    val leaseExpiresAtMillis: Long,
    val dispatchAttempts: Int,
    val settledInvocationStatus: String?,
    val errorCode: String?,
    val updatedAtMillis: Long,
    val revision: Long
)
