package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentApproval
import com.muyuchat.core.agent.contract.AgentApprovalDecision
import com.muyuchat.core.agent.contract.AgentApprovalStatus
import com.muyuchat.mca.agent.data.AgentApprovalDao
import com.muyuchat.mca.agent.data.AgentApprovalEntity
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentEntityMappers
import com.muyuchat.mca.agent.data.AgentInvocationDao
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.security.AgentApprovalBinding
import com.muyuchat.mca.agent.security.AgentApprovalConsumeOutcome
import com.muyuchat.mca.agent.security.AgentApprovalInsertResult
import com.muyuchat.mca.agent.security.AgentApprovalResolveOutcome
import com.muyuchat.mca.agent.security.AgentApprovalStorePort
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.isExpiredAt

/**
 * Room-backed one-shot approval store for K07.
 *
 * Every operation runs in one Room transaction and re-reads status/expiry inside it. The CAS
 * transitions are:
 * - insert: at most one PENDING row per invocation (unique `pendingInvocationKey` + transaction);
 * - resolve: PENDING -> APPROVED/DENIED, expired PENDING -> EXPIRED;
 * - consume: APPROVED -> CONSUMED exactly once, with the full binding compared first.
 */
class RoomAgentApprovalStore(
    private val approvalDao: AgentApprovalDao,
    private val invocationDao: AgentInvocationDao,
    private val transactions: AgentTransactionRunner,
    private val clock: AgentClock = AgentClock.system()
) : AgentApprovalStorePort {

    override suspend fun insertPending(approval: AgentApproval): AgentApprovalInsertResult =
        transactions.inTransaction {
            val existing = approvalDao.findPendingForInvocation(approval.invocationId)
            if (existing != null) {
                val contract = AgentEntityMappers.toContract(existing)
                val sameBinding = bindingOf(approval).matches(contract)
                return@inTransaction if (sameBinding) {
                    AgentApprovalInsertResult.AlreadyPending(contract)
                } else {
                    AgentApprovalInsertResult.Rejected(
                        AgentSecurityErrors.internalInvariant(
                            "The invocation already has a pending approval for different arguments."
                        )
                    )
                }
            }
            val invocation = invocationDao.find(approval.invocationId)
                ?: return@inTransaction AgentApprovalInsertResult.Rejected(
                    AgentSecurityErrors.internalInvariant("Approval references a missing invocation.")
                )
            approvalDao.upsert(AgentEntityMappers.toEntity(approval, invocation.runId))
            AgentApprovalInsertResult.Inserted(approval)
        }

    override suspend fun resolve(
        approvalId: String,
        binding: AgentApprovalBinding,
        decision: AgentApprovalDecision,
        nowMillis: Long
    ): AgentApprovalResolveOutcome = transactions.inTransaction {
        val entity = approvalDao.find(approvalId)
            ?: return@inTransaction AgentApprovalResolveOutcome.NotFound
        val approval = AgentEntityMappers.toContract(entity)
        when {
            approval.status == AgentApprovalStatus.CONSUMED ->
                return@inTransaction AgentApprovalResolveOutcome.AlreadyResolved(approval)
            approval.status == AgentApprovalStatus.APPROVED ||
                approval.status == AgentApprovalStatus.DENIED ->
                return@inTransaction AgentApprovalResolveOutcome.AlreadyResolved(approval)
            approval.status == AgentApprovalStatus.EXPIRED ->
                return@inTransaction AgentApprovalResolveOutcome.Expired(approval)
            !binding.matches(approval) ->
                return@inTransaction AgentApprovalResolveOutcome.BindingMismatch(approval)
            approval.status == AgentApprovalStatus.PENDING && approval.isExpiredAt(nowMillis) -> {
                approvalDao.upsert(expired(entity, nowMillis))
                return@inTransaction AgentApprovalResolveOutcome.Expired(
                    AgentEntityMappers.toContract(expired(entity, nowMillis))
                )
            }
            approval.status != AgentApprovalStatus.PENDING ->
                return@inTransaction AgentApprovalResolveOutcome.AlreadyResolved(approval)
        }
        val nextStatus = when (decision) {
            AgentApprovalDecision.ALLOW_ONCE -> AgentApprovalStatus.APPROVED
            AgentApprovalDecision.DENY -> AgentApprovalStatus.DENIED
        }
        val updated = approvalDao.consumeCas(
            approvalId = approvalId,
            status = nextStatus.name,
            decision = decision.name,
            consumedAt = nowMillis
        )
        if (updated == 1) {
            AgentApprovalResolveOutcome.Resolved(
                AgentEntityMappers.toContract(requireNotNull(approvalDao.find(approvalId)))
            )
        } else {
            val current = approvalDao.find(approvalId)
                ?: return@inTransaction AgentApprovalResolveOutcome.NotFound
            val currentContract = AgentEntityMappers.toContract(current)
            when (currentContract.status) {
                AgentApprovalStatus.EXPIRED -> AgentApprovalResolveOutcome.Expired(currentContract)
                AgentApprovalStatus.PENDING -> AgentApprovalResolveOutcome.Rejected(
                    AgentSecurityErrors.internalInvariant("Approval CAS lost without a status change.")
                )
                else -> AgentApprovalResolveOutcome.AlreadyResolved(currentContract)
            }
        }
    }

    override suspend fun consumePending(
        approvalId: String,
        binding: AgentApprovalBinding,
        runEpoch: Long,
        policyVersion: Long,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome = transactions.inTransaction {
        val entity = approvalDao.find(approvalId)
            ?: return@inTransaction AgentApprovalConsumeOutcome.NotFound
        val approval = AgentEntityMappers.toContract(entity)
        when {
            !binding.matches(approval) ||
                approval.runEpoch != runEpoch ||
                approval.policyVersion != policyVersion ->
                return@inTransaction AgentApprovalConsumeOutcome.BindingMismatch(approval)
            approval.status == AgentApprovalStatus.CONSUMED ->
                return@inTransaction AgentApprovalConsumeOutcome.AlreadyConsumed(approval)
            approval.status != AgentApprovalStatus.PENDING ->
                return@inTransaction AgentApprovalConsumeOutcome.NotApproved(approval)
            approval.isExpiredAt(nowMillis) -> {
                val expiredEntity = expired(entity, nowMillis)
                approvalDao.upsert(expiredEntity)
                return@inTransaction AgentApprovalConsumeOutcome.Expired(
                    AgentEntityMappers.toContract(expiredEntity)
                )
            }
        }
        val updated = approvalDao.consumePendingCas(
            approvalId = approvalId,
            invocationId = binding.invocationId,
            argumentsHash = binding.argumentsHash,
            toolSchemaHash = binding.toolSchemaHash,
            resourceFingerprint = binding.resourceFingerprint,
            runEpoch = runEpoch,
            policyVersion = policyVersion,
            now = nowMillis
        )
        if (updated == 1) {
            AgentApprovalConsumeOutcome.Consumed(
                AgentEntityMappers.toContract(requireNotNull(approvalDao.find(approvalId))),
                nowMillis
            )
        } else {
            val currentEntity = approvalDao.find(approvalId)
                ?: return@inTransaction AgentApprovalConsumeOutcome.NotFound
            val current = AgentEntityMappers.toContract(currentEntity)
            when {
                current.status == AgentApprovalStatus.CONSUMED ->
                    AgentApprovalConsumeOutcome.AlreadyConsumed(current)
                current.isExpiredAt(nowMillis) ->
                    AgentApprovalConsumeOutcome.Expired(current)
                current.status != AgentApprovalStatus.PENDING ->
                    AgentApprovalConsumeOutcome.NotApproved(current)
                else -> AgentApprovalConsumeOutcome.Rejected(
                    AgentSecurityErrors.internalInvariant(
                        "The PENDING approval CAS lost without a status change."
                    )
                )
            }
        }
    }

    override suspend fun consume(
        approvalId: String,
        binding: AgentApprovalBinding,
        nowMillis: Long
    ): AgentApprovalConsumeOutcome = transactions.inTransaction {
        val entity = approvalDao.find(approvalId)
            ?: return@inTransaction AgentApprovalConsumeOutcome.NotFound
        val approval = AgentEntityMappers.toContract(entity)
        when {
            !binding.matches(approval) ->
                return@inTransaction AgentApprovalConsumeOutcome.BindingMismatch(approval)
            approval.status == AgentApprovalStatus.CONSUMED ->
                return@inTransaction AgentApprovalConsumeOutcome.AlreadyConsumed(approval)
            approval.isExpiredAt(nowMillis) -> {
                approvalDao.upsert(expired(entity, nowMillis))
                return@inTransaction AgentApprovalConsumeOutcome.Expired(
                    AgentEntityMappers.toContract(expired(entity, nowMillis))
                )
            }
            approval.status != AgentApprovalStatus.APPROVED ->
                return@inTransaction AgentApprovalConsumeOutcome.NotApproved(approval)
        }
        approvalDao.upsert(
            entity.copy(
                status = AgentApprovalStatus.CONSUMED.name,
                consumedAt = nowMillis,
                pendingInvocationKey = null
            )
        )
        AgentApprovalConsumeOutcome.Consumed(
            AgentEntityMappers.toContract(requireNotNull(approvalDao.find(approvalId))),
            nowMillis
        )
    }

    override suspend fun invalidate(approvalId: String, nowMillis: Long): Boolean =
        transactions.inTransaction {
            val entity = approvalDao.find(approvalId) ?: return@inTransaction false
            if (AgentEntityMappers.toContract(entity).status != AgentApprovalStatus.PENDING) {
                return@inTransaction false
            }
            approvalDao.upsert(
                entity.copy(
                    status = AgentApprovalStatus.INVALIDATED.name,
                    pendingInvocationKey = null
                )
            )
            true
        }

    override suspend fun read(approvalId: String): AgentApproval? =
        approvalDao.find(approvalId)?.let(AgentEntityMappers::toContract)

    override suspend fun invalidatePendingForRun(runId: String): Int =
        approvalDao.invalidatePendingForRun(runId)

    private fun expired(entity: AgentApprovalEntity, nowMillis: Long): AgentApprovalEntity =
        entity.copy(
            status = AgentApprovalStatus.EXPIRED.name,
            consumedAt = entity.consumedAt ?: nowMillis,
            pendingInvocationKey = null
        )

    private fun bindingOf(approval: AgentApproval): AgentApprovalBinding = AgentApprovalBinding(
        invocationId = approval.invocationId,
        argumentsHash = approval.argumentsHash,
        toolSchemaHash = approval.toolSchemaHash,
        resourceFingerprint = approval.resourceFingerprint,
        runEpoch = approval.runEpoch,
        policyVersion = approval.policyVersion
    )
}
