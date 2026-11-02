package com.muyuchat.mca.agent.security

/**
 * Persistence outcome for a newly authorized egress grant.
 *
 * Reuses the existing [AgentEgressGrantOutcome] shape so the policy layer and the persistence layer
 * expose one typed result (Created(grant) / Rejected(AgentSecurityError)) without a parallel model.
 */
typealias AgentEgressGrantCreateOutcome = AgentEgressGrantOutcome

/**
 * Narrow egress-grant persistence port (K14 groundwork for run-scoped MCP/cloud egress).
 *
 * Implementations persist only after explicit user authorization; they never consult model text,
 * MCP annotations, Skill metadata or provider descriptors for origin/source facts. A rejected,
 * expired, stale or non-canonical grant must never be persisted as active.
 *
 * Task-scoped grants use `runId == null` as templates, but V1.1 has no run-start binding flow: the
 * MCP catalog provider therefore consumes only run-scoped grants that an explicit authorization flow
 * created for that exact run. `AgentNetworkEgressPolicy` only authorizes run-scoped grants, so a
 * task template alone never permits egress. Do not invent a binding flow to make a template usable.
 */
interface AgentEgressGrantStore {
    suspend fun create(grant: AgentEgressGrant): AgentEgressGrantCreateOutcome

    suspend fun listActiveForTask(
        taskId: String,
        nowMillis: Long,
        limit: Int
    ): List<AgentEgressGrant>

    /** CAS revoke; true when the grant exists and is now revoked (idempotent on repeat). */
    suspend fun revoke(grantId: String, nowMillis: Long, reason: String): Boolean

    /** Revokes every task-scoped (runId null) egress grant for this task; returns the count. */
    suspend fun revokeTaskScope(taskId: String, nowMillis: Long, reason: String): Int
}
