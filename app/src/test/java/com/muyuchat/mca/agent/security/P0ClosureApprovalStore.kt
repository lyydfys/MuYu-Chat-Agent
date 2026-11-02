package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.core.agent.contract.AgentErrorCategory
import com.muyuchat.core.agent.contract.AgentRetryability
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Independent, strict CAS model of [AgentApprovalStorePort] used only by the P0 closure matrix.
 *
 * It intentionally does not share code with `InMemoryAgentApprovalStore` so the P0 matrix keeps
 * failing if either fixture drifts from the documented adapter contract:
 * - at most one PENDING approval per invocation;
 * - resolve only PENDING -> APPROVED/DENIED (expired PENDING becomes EXPIRED first);
 * - consume only APPROVED -> CONSUMED, after binding/expiry checks, inside one critical section;
 * - invalidate only PENDING.
 *
 * This is still JVM test code. The Room adapter's SQL CAS and the unique pending index are covered
 * by the schema test and remain a separate instrumentation item.
 */
class P0ClosureApprovalStore : AgentApprovalStorePort {
    private val lock = ReentrantLock()
    private val approvals = LinkedHashMap<String, AgentApproval>()
    private val consumedAt = LinkedHashMap<String, Long>()

    var insertCount: Int = 0
        private set

    fun statusOf(approvalId: String): AgentApprovalStatus? = lock.withLock {
        approvals[approvalId]?.status
    }

    fun pendingCountFor(invocationId: String): Int = lock.withLock {
        approvals.values.count {
            it.invocationId == invocationId && it.status == AgentApprovalStatus.PENDING
        }
    }

    fun size(): Int = lock.withLock { approvals.size }

    fun consumedAtOf(approvalId: String): Long? = lock.withLock { consumedAt[approvalId] }

    fun snapshot(): List<AgentApproval> = lock.withLock { approvals.values.toList() }

    override suspend fun insertPending(approval: AgentApproval): AgentApprovalInsertResult = lock.withLock {
        val existing = approvals.values.firstOrNull {
            it.invocationId == approval.invocationId && it.status == AgentApprovalStatus.PENDING
        }
        if (existing != null) {
            return@withLock if (bindingOf(approval).matches(existing)) {
                AgentApprovalInsertResult.AlreadyPending(existing)
            } else {
                AgentApprovalInsertResult.Rejected(
                    AgentSecurityError(
                        code = "INTERNAL_INVARIANT",
                        category = AgentErrorCategory.INTERNAL,
                        retryability = AgentRetryability.NEVER,
                        safeMessage = "The invocation already has a pending approval for different arguments."
                    )
                )
            }
        }
        approvals[approval.approvalId] = approval
        insertCount += 1
        AgentApprovalInsertResult.Inserted(approval)
    }

    override suspend fun resolve(
        approvalId: String,
        binding: AgentApprovalBinding,
        decision: AgentApprovalDecision,
        nowMillis: Long
    ): AgentApprovalResolveOutcome = lock.withLock {
        val approval = approvals[approvalId]
            ?: return@withLock AgentApprovalResolveOutcome.NotFound
        if (!binding.matches(approval)) {
            return@withLock AgentApprovalResolveOutcome.BindingMismatch(approval)
        }
        if (approval.status != AgentApprovalStatus.PENDING) {
            return@withLock AgentApprovalResolveOutcome.AlreadyResolved(approval)
        }
        if (nowMillis >= approval.expiresAtMillis) {
            val expired = approval.copy(status = AgentApprovalStatus.EXPIRED, consumedAtMillis = nowMillis)
            approvals[approvalId] = expired
            return@withLock AgentApprovalResolveOutcome.Expired(expired)
        }
        val resolved = approval.copy(
            status = when (decision) {
                AgentApprovalDecision.ALLOW_ONCE -> AgentApprovalStatus.APPROVED
                AgentApprovalDecision.DENY -> AgentApprovalStatus.DENIED
            }
        )
        approvals[approvalId] = resolved
        AgentApprovalResolveOutcome.Resolved(resolved)
    }

    override suspend fun consumePending(
        approvalId: String,
        binding: AgentApprovalBinding,
        runEpoch: Long,
        policyVersion: Long,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome = lock.withLock {
        val approval = approvals[approvalId]
            ?: return@withLock AgentApprovalConsumeOutcome.NotFound
        if (!binding.matches(approval) ||
            approval.runEpoch != runEpoch ||
            approval.policyVersion != policyVersion
        ) {
            return@withLock AgentApprovalConsumeOutcome.BindingMismatch(approval)
        }
        when (approval.status) {
            AgentApprovalStatus.CONSUMED ->
                AgentApprovalConsumeOutcome.AlreadyConsumed(approval)
            AgentApprovalStatus.PENDING -> {
                if (nowMillis >= approval.expiresAtMillis) {
                    val expired = approval.copy(
                        status = AgentApprovalStatus.EXPIRED,
                        consumedAtMillis = nowMillis
                    )
                    approvals[approvalId] = expired
                    AgentApprovalConsumeOutcome.Expired(expired)
                } else {
                    val consumed = approval.copy(
                        status = AgentApprovalStatus.CONSUMED,
                        consumedAtMillis = nowMillis
                    )
                    approvals[approvalId] = consumed
                    consumedAt[approvalId] = nowMillis
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
    ): AgentApprovalConsumeOutcome = lock.withLock {
        val approval = approvals[approvalId]
            ?: return@withLock AgentApprovalConsumeOutcome.NotFound
        if (!binding.matches(approval)) {
            return@withLock AgentApprovalConsumeOutcome.BindingMismatch(approval)
        }
        if (approval.status == AgentApprovalStatus.CONSUMED) {
            return@withLock AgentApprovalConsumeOutcome.AlreadyConsumed(approval)
        }
        if (approval.status == AgentApprovalStatus.APPROVED && nowMillis >= approval.expiresAtMillis) {
            val expired = approval.copy(status = AgentApprovalStatus.EXPIRED, consumedAtMillis = nowMillis)
            approvals[approvalId] = expired
            return@withLock AgentApprovalConsumeOutcome.Expired(expired)
        }
        if (approval.status != AgentApprovalStatus.APPROVED) {
            return@withLock AgentApprovalConsumeOutcome.NotApproved(approval)
        }
        val consumed = approval.copy(
            status = AgentApprovalStatus.CONSUMED,
            consumedAtMillis = nowMillis
        )
        approvals[approvalId] = consumed
        consumedAt[approvalId] = nowMillis
        AgentApprovalConsumeOutcome.Consumed(consumed, nowMillis)
    }

    override suspend fun invalidate(approvalId: String, nowMillis: Long): Boolean = lock.withLock {
        val approval = approvals[approvalId] ?: return@withLock false
        if (approval.status != AgentApprovalStatus.PENDING) {
            return@withLock false
        }
        approvals[approvalId] = approval.copy(status = AgentApprovalStatus.INVALIDATED)
        true
    }

    override suspend fun read(approvalId: String): AgentApproval? = lock.withLock { approvals[approvalId] }

    private fun bindingOf(approval: AgentApproval): AgentApprovalBinding = AgentApprovalBinding(
        invocationId = approval.invocationId,
        argumentsHash = approval.argumentsHash,
        toolSchemaHash = approval.toolSchemaHash,
        resourceFingerprint = approval.resourceFingerprint,
        runEpoch = approval.runEpoch,
        policyVersion = approval.policyVersion
    )
}
