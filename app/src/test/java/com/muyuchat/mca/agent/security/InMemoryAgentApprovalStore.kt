package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus

/**
 * Thread-safe CAS fake for the [AgentApprovalStorePort] contract. It exists so K07's concurrency
 * requirement can be proven on the JVM before the Room adapter exists; it is not production code.
 */
class InMemoryAgentApprovalStore : AgentApprovalStorePort {
    private val lock = Any()
    private val approvals = LinkedHashMap<String, AgentApproval>()
    var insertCount: Int = 0
        private set

    fun statusOf(approvalId: String): AgentApprovalStatus? = synchronized(lock) {
        approvals[approvalId]?.status
    }

    fun size(): Int = synchronized(lock) { approvals.size }

    override suspend fun insertPending(approval: AgentApproval): AgentApprovalInsertResult = synchronized(lock) {
        val existing = approvals.values.firstOrNull {
            it.invocationId == approval.invocationId &&
                (it.status == AgentApprovalStatus.PENDING || it.status == AgentApprovalStatus.APPROVED)
        }
        if (existing != null) {
            AgentApprovalInsertResult.AlreadyPending(existing)
        } else {
            approvals[approval.approvalId] = approval
            insertCount += 1
            AgentApprovalInsertResult.Inserted(approval)
        }
    }

    override suspend fun resolve(
        approvalId: String,
        binding: AgentApprovalBinding,
        decision: AgentApprovalDecision,
        nowMillis: Long
    ): AgentApprovalResolveOutcome = synchronized(lock) {
        val approval = approvals[approvalId] ?: return@synchronized AgentApprovalResolveOutcome.NotFound
        if (!binding.matches(approval)) return@synchronized AgentApprovalResolveOutcome.BindingMismatch(approval)
        if (approval.status == AgentApprovalStatus.PENDING && approval.expiresAtMillis <= nowMillis) {
            val expired = approval.copy(status = AgentApprovalStatus.EXPIRED)
            approvals[approvalId] = expired
            return@synchronized AgentApprovalResolveOutcome.Expired(expired)
        }
        if (approval.status != AgentApprovalStatus.PENDING) {
            return@synchronized AgentApprovalResolveOutcome.AlreadyResolved(approval)
        }
        val updated = approval.copy(
            status = if (decision == AgentApprovalDecision.ALLOW_ONCE) {
                AgentApprovalStatus.APPROVED
            } else {
                AgentApprovalStatus.DENIED
            }
        )
        approvals[approvalId] = updated
        AgentApprovalResolveOutcome.Resolved(updated)
    }

    override suspend fun consumePending(
        approvalId: String,
        binding: AgentApprovalBinding,
        runEpoch: Long,
        policyVersion: Long,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome = synchronized(lock) {
        val approval = approvals[approvalId] ?: return@synchronized AgentApprovalConsumeOutcome.NotFound
        if (!binding.matches(approval) ||
            approval.runEpoch != runEpoch ||
            approval.policyVersion != policyVersion
        ) {
            return@synchronized AgentApprovalConsumeOutcome.BindingMismatch(approval)
        }
        when (approval.status) {
            AgentApprovalStatus.CONSUMED -> AgentApprovalConsumeOutcome.AlreadyConsumed(approval)
            AgentApprovalStatus.PENDING -> {
                if (approval.expiresAtMillis <= nowMillis) {
                    val expired = approval.copy(status = AgentApprovalStatus.EXPIRED)
                    approvals[approvalId] = expired
                    AgentApprovalConsumeOutcome.Expired(expired)
                } else {
                    val consumed = approval.copy(
                        status = AgentApprovalStatus.CONSUMED,
                        consumedAtMillis = nowMillis
                    )
                    approvals[approvalId] = consumed
                    AgentApprovalConsumeOutcome.Consumed(consumed, nowMillis)
                }
            }
            else -> AgentApprovalConsumeOutcome.NotApproved(approval)
        }
    }

    override suspend fun consume(
        approvalId: String,
        binding: AgentApprovalBinding,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome = synchronized(lock) {
        val approval = approvals[approvalId] ?: return@synchronized AgentApprovalConsumeOutcome.NotFound
        if (!binding.matches(approval)) {
            return@synchronized AgentApprovalConsumeOutcome.BindingMismatch(approval)
        }
        if (approval.status == AgentApprovalStatus.APPROVED && approval.expiresAtMillis <= nowMillis) {
            val expired = approval.copy(status = AgentApprovalStatus.EXPIRED)
            approvals[approvalId] = expired
            return@synchronized AgentApprovalConsumeOutcome.Expired(expired)
        }
        when (approval.status) {
            AgentApprovalStatus.APPROVED -> {
                val consumed = approval.copy(
                    status = AgentApprovalStatus.CONSUMED,
                    consumedAtMillis = nowMillis
                )
                approvals[approvalId] = consumed
                AgentApprovalConsumeOutcome.Consumed(consumed, nowMillis)
            }
            AgentApprovalStatus.CONSUMED -> AgentApprovalConsumeOutcome.AlreadyConsumed(approval)
            AgentApprovalStatus.EXPIRED -> AgentApprovalConsumeOutcome.Expired(approval)
            else -> AgentApprovalConsumeOutcome.NotApproved(approval)
        }
    }

    override suspend fun invalidate(approvalId: String, nowMillis: Long): Boolean = synchronized(lock) {
        val approval = approvals[approvalId] ?: return@synchronized false
        if (approval.status != AgentApprovalStatus.PENDING && approval.status != AgentApprovalStatus.APPROVED) {
            return@synchronized false
        }
        approvals[approvalId] = approval.copy(status = AgentApprovalStatus.INVALIDATED)
        true
    }

    override suspend fun read(approvalId: String): AgentApproval? = synchronized(lock) { approvals[approvalId] }
}
