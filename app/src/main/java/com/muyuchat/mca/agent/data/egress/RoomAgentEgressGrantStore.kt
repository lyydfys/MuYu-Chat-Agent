package com.muyuchat.mca.agent.data.egress

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.mca.agent.data.AgentClock
import com.muyuchat.mca.agent.data.AgentGrantDao
import com.muyuchat.mca.agent.data.AgentGrantScope
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressGrantCreateOutcome
import com.muyuchat.mca.agent.security.AgentEgressGrantOutcome
import com.muyuchat.mca.agent.security.AgentEgressGrantStore
import com.muyuchat.mca.agent.security.AgentEgressOrigins
import com.muyuchat.mca.agent.security.AgentEgressTransport
import com.muyuchat.mca.agent.security.AgentNetworkEgressPolicy
import com.muyuchat.mca.agent.security.AgentOriginResult
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import kotlinx.coroutines.CancellationException

/** Bounds for the egress grant store; all list/revoke inputs stay bounded. */
data class AgentEgressGrantLimits(
    val maxListLimit: Int = 200,
    val maxReasonChars: Int = 128
) {
    init {
        require(maxListLimit in 1..1_000) { "maxListLimit must be in 1..1000" }
        require(maxReasonChars in 1..512) { "maxReasonChars must be in 1..512" }
    }
}

/**
 * Room-backed egress grants over the existing `agent_grants` table (no schema change).
 *
 * Fail-closed rules:
 * - origin must be canonical and legal for the transport; LOCAL_ONLY only permits LOCAL_SERVER;
 * - task/run must exist and the frozen policyVersion must match exactly;
 * - expired/revoked rows are never returned as active;
 * - duplicate grantId replaces only within the same task;
 * - revoke is a CAS and is idempotent for an already-revoked egress grant.
 *
 * Task-scoped grants (`runId == null`) are templates; [AgentNetworkEgressPolicy] only authorizes
 * run-scoped grants, so the run-start path must bind a task template to a run-bound copy.
 */
class RoomAgentEgressGrantStore(
    private val grantDao: AgentGrantDao,
    private val transactions: AgentTransactionRunner,
    private val contextReader: AgentEgressGrantContextReader,
    private val clock: AgentClock = AgentClock.system(),
    private val limits: AgentEgressGrantLimits = AgentEgressGrantLimits()
) : AgentEgressGrantStore {
    override suspend fun create(grant: AgentEgressGrant): AgentEgressGrantCreateOutcome {
        if (grant.revoked) return rejected("An already-revoked egress grant cannot be created.")
        return try {
            transactions.inTransaction { createInTransaction(grant) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            rejected("The egress grant could not be persisted.")
        }
    }

    override suspend fun listActiveForTask(
        taskId: String,
        nowMillis: Long,
        limit: Int
    ): List<AgentEgressGrant> {
        AgentContract.requireId(taskId, "taskId")
        require(nowMillis >= 0L) { "nowMillis must not be negative" }
        require(limit in 1..limits.maxListLimit) { "limit must be in 1..${limits.maxListLimit}" }
        val rows = grantDao.listActiveEgressForTask(taskId, nowMillis, limit)
        val result = ArrayList<AgentEgressGrant>(rows.size)
        for (row in rows) {
            val grant = AgentEgressGrantMapper.toContract(row, nowMillis) ?: continue
            val context = contextReader.read(taskId, grant.runId) ?: continue
            if (context.taskId != taskId) continue
            if (context.policyVersion != grant.policyVersion) continue
            result += grant
        }
        return result
    }

    override suspend fun revoke(grantId: String, nowMillis: Long, reason: String): Boolean {
        if (!validRevokeInput(grantId, nowMillis, reason)) return false
        val normalizedReason = reason.trim()
        return try {
            transactions.inTransaction {
                val existing = grantDao.find(grantId) ?: return@inTransaction false
                if (existing.scopeType != AgentGrantScope.EGRESS_PROVIDER.name) {
                    return@inTransaction false
                }
                if (existing.revokedAt != null) return@inTransaction true
                grantDao.revokeCas(
                    grantId = grantId,
                    scopeType = AgentGrantScope.EGRESS_PROVIDER.name,
                    revokedAt = nowMillis,
                    reason = normalizedReason
                ) == 1 || grantDao.find(grantId)?.revokedAt != null
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun revokeTaskScope(taskId: String, nowMillis: Long, reason: String): Int {
        if (!validRevokeInput(taskId, nowMillis, reason)) return 0
        return try {
            grantDao.revokeTaskScope(
                taskId = taskId,
                scopeType = AgentGrantScope.EGRESS_PROVIDER.name,
                revokedAt = nowMillis,
                reason = reason.trim()
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun createInTransaction(grant: AgentEgressGrant): AgentEgressGrantCreateOutcome {
        if (grant.runId == null && grant.taskId == null) {
            return rejected("An egress grant needs a task or run scope.")
        }
        val context = contextReader.read(grant.taskId, grant.runId)
            ?: return rejected("The task or run policy is unavailable; the egress grant was not saved.")
        if (grant.taskId != null && grant.taskId != context.taskId) {
            return rejected("The egress grant task scope does not match the target.")
        }
        if (context.policyVersion != grant.policyVersion) {
            return rejected("The egress grant policy version is stale; request authorization again.")
        }
        val canonical = when (
            val result = AgentNetworkEgressPolicy().canonicalOrigin(grant.origin, grant.transport)
        ) {
            is AgentOriginResult.Valid -> result.origin
            is AgentOriginResult.Invalid -> return rejected("The egress origin is not valid.")
        }
        if (canonical != grant.origin) {
            return rejected("The egress origin must be canonical before it is granted.")
        }
        if (context.networkPolicy == AgentNetworkPolicy.LOCAL_ONLY &&
            grant.transport != AgentEgressTransport.LOCAL_SERVER
        ) {
            return AgentEgressGrantOutcome.Rejected(AgentSecurityErrors.policyOffline())
        }
        if (grant.transport == AgentEgressTransport.LOCAL_SERVER &&
            !AgentEgressOrigins.isLocalOrPrivate(canonical)
        ) {
            return rejected("LOCAL_SERVER must target a loopback or private address.")
        }
        if (grant.transport == AgentEgressTransport.REMOTE_MCP &&
            AgentEgressOrigins.isLocalOrPrivate(canonical)
        ) {
            return rejected("REMOTE_MCP must not target a loopback or private address.")
        }
        val now = clock.nowMillis()
        if (grant.expiresAtMillis != null && grant.expiresAtMillis <= now) {
            return rejected("The egress grant is already expired.")
        }
        val existing = grantDao.find(grant.grantId)
        if (existing != null) {
            if (existing.taskId != context.taskId) {
                return rejected("The egress grant id belongs to another task.")
            }
            if (existing.scopeType != AgentGrantScope.EGRESS_PROVIDER.name) {
                return rejected("The egress grant id is already used by another grant scope.")
            }
        }
        grantDao.upsert(
            AgentEgressGrantMapper.toEntity(
                grant = grant,
                taskId = context.taskId,
                grantedAtMillis = now
            )
        )
        return AgentEgressGrantOutcome.Created(
            grant.copy(taskId = context.taskId, revoked = false)
        )
    }

    private fun validRevokeInput(id: String, nowMillis: Long, reason: String): Boolean {
        if (!AgentContract.isValidId(id)) return false
        if (nowMillis < 0L) return false
        val normalized = reason.trim()
        return normalized.isNotEmpty() && normalized.length <= limits.maxReasonChars &&
            normalized.none { it == '\n' || it == '\r' || it == '\u0000' }
    }

    private fun rejected(message: String): AgentEgressGrantOutcome.Rejected =
        AgentEgressGrantOutcome.Rejected(AgentSecurityErrors.egressDenied(message))
}
