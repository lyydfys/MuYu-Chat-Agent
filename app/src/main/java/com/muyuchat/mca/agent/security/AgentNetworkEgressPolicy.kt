package com.muyuchat.mca.agent.security

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.core.agent.contract.AgentNetworkPolicy

/** The transport class the run owner is about to open. */
enum class AgentEgressTransport {
    CLOUD_PROVIDER,
    REMOTE_MCP,
    /** Explicit local/private server; the only transport that may still exist under LOCAL_ONLY. */
    LOCAL_SERVER,
    BROWSER_SEARCH
}

/** Run-scoped egress policy. [policyVersion] must be the version the run owner currently holds. */
data class AgentEgressScope(
    val runId: String,
    val networkPolicy: AgentNetworkPolicy,
    val policyVersion: Long
) {
    init {
        AgentContract.requireId(runId, "runId")
        require(policyVersion > 0L) { "policyVersion must be positive" }
    }
}

/**
 * One projected payload source (architecture 11.3). Only identity, hash and class travel here; the
 * actual content stays in the payload/journal. [sourceId] is a stable host identity, never model
 * text, and [sha256] pins the exact bytes that would be sent.
 */
data class AgentEgressSource(
    val sourceId: String,
    val sha256: String,
    val dataClass: AgentDataClass
) {
    init {
        require(AgentContract.isValidId(sourceId)) { "egress sourceId is not a stable id" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "egress source hash must be lowercase SHA-256" }
    }

    override fun toString(): String = "AgentEgressSource($sourceId, $dataClass)"
}

/** The exact projected payload about to be serialized for one provider/MCP request. */
data class AgentEgressPayload(
    val transport: AgentEgressTransport,
    val origin: String,
    val modelId: String?,
    val sources: List<AgentEgressSource>
) {
    init {
        require(origin.isNotBlank()) { "egress origin must not be blank" }
        require(modelId == null || modelId.isNotBlank()) { "egress modelId must be null or non-blank" }
        require(sources.size <= 512) { "egress payload source projection is too large" }
    }
}

/**
 * One user-visible egress grant: provider/MCP origin + allowed data classes + exact source ids.
 * A grant is either run-scoped ([runId] non-null) or a task-scoped template ([runId] null,
 * [taskId] non-null). V1.1 has no run-start binding flow, so the MCP catalog provider consumes only
 * run-scoped grants and never turns a task template into egress by itself. Grants carry the
 * policyVersion they were issued under; a version change makes them stale (architecture 11.2/11.3).
 */
data class AgentEgressGrant(
    val grantId: String,
    /** Run scope; null means a task-scoped template created at task creation. */
    val runId: String?,
    val transport: AgentEgressTransport,
    val origin: String,
    val allowedDataClasses: Set<AgentDataClass>,
    val allowedSourceIds: Set<String>,
    val policyVersion: Long,
    val revoked: Boolean = false,
    /** Required for a task-scoped grant, optional for a run-scoped grant (derived from the run). */
    val taskId: String? = null,
    val expiresAtMillis: Long? = null
) {
    init {
        require(runId != null || taskId != null) { "an egress grant needs a runId or a taskId" }
        require(runId == null || AgentContract.isValidId(runId)) { "egress grant runId is not a stable id" }
        require(taskId == null || AgentContract.isValidId(taskId)) { "egress grant taskId is not a stable id" }
        AgentContract.requireId(grantId, "grantId")
        require(origin.isNotBlank()) { "egress grant origin must not be blank" }
        require(origin.length <= 512) { "egress grant origin is too long" }
        require(origin.none { it == '\n' || it == '\r' || it == '\u0000' }) { "egress grant origin contains a control character" }
        require(allowedDataClasses.isNotEmpty()) { "an egress grant must allow at least one data class" }
        require(allowedSourceIds.size <= 512) { "an egress grant has too many source ids" }
        require(allowedSourceIds.all(AgentContract::isValidId)) { "egress grant sourceId is not a stable id" }
        require(policyVersion > 0L) { "egress grant policyVersion must be positive" }
        require(expiresAtMillis == null || expiresAtMillis > 0L) { "egress grant expiry must be positive" }
    }
}

/** User-visible egress authorization being requested by the UI layer. */
data class AgentEgressGrantRequest(
    val grantId: String,
    val transport: AgentEgressTransport,
    val origin: String,
    val dataClasses: Set<AgentDataClass>,
    val sourceIds: Set<String>
) {
    init {
        AgentContract.requireId(grantId, "grantId")
        require(origin.isNotBlank()) { "egress request origin must not be blank" }
        require(dataClasses.isNotEmpty()) { "egress request must allow at least one data class" }
    }
}

sealed interface AgentOriginResult {
    data class Valid(val origin: String) : AgentOriginResult

    data class Invalid(val error: AgentSecurityError) : AgentOriginResult
}

enum class AgentEgressAuthorizationReason {
    UNKNOWN_ORIGIN,
    NEW_SOURCES
}

sealed interface AgentEgressDecision {
    data class Allowed(
        val policyVersion: Long,
        val grantId: String,
        val grantedSourceIds: List<String>
    ) : AgentEgressDecision

    /** The transport itself may be acceptable but the user has not granted this origin/sources yet. */
    data class AuthorizationRequired(
        val transport: AgentEgressTransport,
        val origin: String,
        val reason: AgentEgressAuthorizationReason,
        val missingSourceIds: List<String>
    ) : AgentEgressDecision

    /** Hard transport denial; K04 must not open the connection and must map the typed code. */
    data class Denied(val error: AgentSecurityError) : AgentEgressDecision
}

sealed interface AgentEgressGrantOutcome {
    data class Created(val grant: AgentEgressGrant) : AgentEgressGrantOutcome

    data class Rejected(val error: AgentSecurityError) : AgentEgressGrantOutcome
}

/**
 * In-flight cancellation hook for this run's own network calls (never a dispatcher-wide cancel).
 * K04 implements this over its per-attempt child jobs/Call handles and must only cancel calls whose
 * runId (and policy epoch) matches.
 */
fun interface AgentInFlightEgressCalls {
    fun cancelOwnCalls(runId: String, reason: AgentEgressCancelReason)
}

enum class AgentEgressCancelReason {
    POLICY_OFFLINE,
    GRANT_REVOKED,
    RUN_STOPPED
}

/**
 * Revocation result. The owner must persist [newPolicyVersion], invalidate approvals whose
 * policyVersion is older (see AgentPermissionBroker), then invoke [cancellation]. Already-sent data
 * is not recalled; UI must not claim otherwise (architecture 11.2).
 */
data class AgentEgressRevocation(
    val runId: String,
    val revokedGrantId: String,
    val previousPolicyVersion: Long,
    val newPolicyVersion: Long,
    val cancellation: AgentInFlightEgressCalls
)

/**
 * Run-scoped network egress policy and source-coverage checker.
 *
 * Enforcement point: the transport layer, before any socket/TLS connection and again after the
 * final payload projection is assembled. LOCAL_ONLY never opens a cloud/remote connection
 * regardless of prompt text; it allows a LOCAL_SERVER only when the run holds an explicit grant for
 * that exact origin. Grants/classes/source ids are host facts that must be supplied by an explicit
 * authorization flow; they are never model text, MCP annotations, Skill allowed-tools or descriptor
 * hints. V1.1 does not yet ship the per-run MCP authorization flow, so the catalog provider consumes
 * only grants that already exist for the run and otherwise fails closed (it must not mint grants
 * from the standing server-config consent flag).
 */
class AgentNetworkEgressPolicy {
    /** Canonicalizes an origin for display/approval. Cleartext is only valid for LOCAL_SERVER. */
    fun canonicalOrigin(raw: String, transport: AgentEgressTransport): AgentOriginResult =
        AgentEgressOrigins.canonical(raw, allowCleartext = transport == AgentEgressTransport.LOCAL_SERVER)

    /** Transport decision; call before opening any connection. */
    fun checkTransport(
        scope: AgentEgressScope,
        transport: AgentEgressTransport,
        origin: String,
        grants: List<AgentEgressGrant>
    ): AgentEgressDecision {
        val canonical = when (val result = canonicalOrigin(origin, transport)) {
            is AgentOriginResult.Valid -> result.origin
            is AgentOriginResult.Invalid -> return AgentEgressDecision.Denied(result.error)
        }
        if (transport == AgentEgressTransport.LOCAL_SERVER && !AgentEgressOrigins.isLocalOrPrivate(canonical)) {
            return AgentEgressDecision.Denied(
                AgentSecurityErrors.egressDenied("LOCAL_SERVER must target a loopback or private address.")
            )
        }
        if (transport == AgentEgressTransport.REMOTE_MCP && AgentEgressOrigins.isLocalOrPrivate(canonical)) {
            return AgentEgressDecision.Denied(
                AgentSecurityErrors.egressDenied("REMOTE_MCP must not target a loopback or private address.")
            )
        }
        val runGrants = grants.filter { it.runId == scope.runId && it.transport == transport && it.origin == canonical }
        val active = runGrants.firstOrNull { !it.revoked && it.policyVersion == scope.policyVersion }
        if (scope.networkPolicy == AgentNetworkPolicy.LOCAL_ONLY) {
            if (transport != AgentEgressTransport.LOCAL_SERVER) {
                return AgentEgressDecision.Denied(AgentSecurityErrors.policyOffline())
            }
            if (active == null) {
                return AgentEgressDecision.Denied(
                    AgentSecurityErrors.egressDenied("LOCAL_ONLY requires an explicit grant for this local server origin.")
                )
            }
            return AgentEgressDecision.Allowed(scope.policyVersion, active.grantId, emptyList())
        }
        if (active != null) {
            return AgentEgressDecision.Allowed(scope.policyVersion, active.grantId, emptyList())
        }
        if (runGrants.isNotEmpty()) {
            return AgentEgressDecision.Denied(
                AgentSecurityErrors.egressDenied("The egress grant is revoked or the policy version changed.")
            )
        }
        return AgentEgressDecision.AuthorizationRequired(
            transport = transport,
            origin = canonical,
            reason = AgentEgressAuthorizationReason.UNKNOWN_ORIGIN,
            missingSourceIds = emptyList()
        )
    }

    /** Full payload check: transport decision first, then every projected source must be covered. */
    fun checkPayload(
        scope: AgentEgressScope,
        payload: AgentEgressPayload,
        grants: List<AgentEgressGrant>
    ): AgentEgressDecision {
        val transportDecision = checkTransport(scope, payload.transport, payload.origin, grants)
        if (transportDecision !is AgentEgressDecision.Allowed) return transportDecision
        val grant = grants.firstOrNull { it.grantId == transportDecision.grantId }
            ?: return AgentEgressDecision.Denied(AgentSecurityErrors.egressDenied("The active egress grant disappeared."))
        if (payload.sources.distinctBy { it.sourceId }.size != payload.sources.size &&
            payload.sources.groupBy { it.sourceId }.any { (_, sources) ->
                sources.map { it.sha256 to it.dataClass }.distinct().size > 1
            }
        ) {
            return AgentEgressDecision.Denied(
                AgentSecurityErrors.egressDenied("The egress source projection is internally inconsistent.")
            )
        }
        val missing = payload.sources
            .distinctBy { it.sourceId }
            .filter { source -> !grant.covers(source) }
            .map { it.sourceId }
            .sorted()
        if (missing.isNotEmpty()) {
            return AgentEgressDecision.AuthorizationRequired(
                transport = payload.transport,
                origin = AgentEgressOrigins.canonicalOrRaw(payload.origin, payload.transport),
                reason = AgentEgressAuthorizationReason.NEW_SOURCES,
                missingSourceIds = missing
            )
        }
        val sourceIds = payload.sources.map { it.sourceId }.distinct().sorted()
        return AgentEgressDecision.Allowed(scope.policyVersion, grant.grantId, sourceIds)
    }

    /** Creates the typed grant after explicit user authorization; never consults model text. */
    fun createGrant(scope: AgentEgressScope, request: AgentEgressGrantRequest): AgentEgressGrantOutcome {
        if (scope.networkPolicy == AgentNetworkPolicy.LOCAL_ONLY && request.transport != AgentEgressTransport.LOCAL_SERVER) {
            return AgentEgressGrantOutcome.Rejected(AgentSecurityErrors.policyOffline())
        }
        val canonical = when (val result = canonicalOrigin(request.origin, request.transport)) {
            is AgentOriginResult.Valid -> result.origin
            is AgentOriginResult.Invalid -> return AgentEgressGrantOutcome.Rejected(result.error)
        }
        if (request.transport == AgentEgressTransport.LOCAL_SERVER && !AgentEgressOrigins.isLocalOrPrivate(canonical)) {
            return AgentEgressGrantOutcome.Rejected(
                AgentSecurityErrors.egressDenied("LOCAL_SERVER must target a loopback or private address.")
            )
        }
        if (request.transport == AgentEgressTransport.REMOTE_MCP && AgentEgressOrigins.isLocalOrPrivate(canonical)) {
            return AgentEgressGrantOutcome.Rejected(
                AgentSecurityErrors.egressDenied("REMOTE_MCP must not target a loopback or private address.")
            )
        }
        return try {
            AgentEgressGrantOutcome.Created(
                AgentEgressGrant(
                    grantId = request.grantId,
                    runId = scope.runId,
                    transport = request.transport,
                    origin = canonical,
                    allowedDataClasses = request.dataClasses.toSet(),
                    allowedSourceIds = request.sourceIds.toSet(),
                    policyVersion = scope.policyVersion
                )
            )
        } catch (_: IllegalArgumentException) {
            AgentEgressGrantOutcome.Rejected(
                AgentSecurityErrors.egressDenied("The egress grant request is not canonical.")
            )
        }
    }

    /**
     * Revokes one grant, increments the run policy version and returns the in-flight cancellation
     * handle. The caller must apply the cancellation to its own calls and persist the new version.
     */
    fun revoke(
        scope: AgentEgressScope,
        grant: AgentEgressGrant,
        ownCalls: AgentInFlightEgressCalls,
        reason: AgentEgressCancelReason = AgentEgressCancelReason.GRANT_REVOKED
    ): AgentEgressRevocation {
        require(grant.runId == scope.runId) { "cannot revoke an egress grant from another run" }
        require(scope.policyVersion < Long.MAX_VALUE) { "policyVersion overflow" }
        return AgentEgressRevocation(
            runId = scope.runId,
            revokedGrantId = grant.grantId,
            previousPolicyVersion = scope.policyVersion,
            newPolicyVersion = scope.policyVersion + 1,
            cancellation = AgentInFlightEgressCalls { runId, _ ->
                require(runId == scope.runId) { "cancellation is scoped to this run only" }
                ownCalls.cancelOwnCalls(runId, reason)
            }
        )
    }

    private fun AgentEgressGrant.covers(source: AgentEgressSource): Boolean =
        source.sourceId in allowedSourceIds && source.dataClass in allowedDataClasses
}

