package com.muyuchat.mca.agent.data.egress

import com.muyuchat.core.agent.contract.AgentContract
import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.mca.agent.data.AgentGrantEntity
import com.muyuchat.mca.agent.data.AgentGrantScope
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressOrigins
import com.muyuchat.mca.agent.security.AgentEgressTransport
import com.muyuchat.mca.agent.security.AgentOriginResult
import org.json.JSONArray
import org.json.JSONObject

/** Decoded `agent_grants.scopeJson` for an egress grant. */
data class AgentEgressGrantScope(
    val transport: AgentEgressTransport,
    val origin: String,
    val allowedDataClasses: Set<AgentDataClass>,
    val allowedSourceIds: Set<String>
)

/**
 * Pure `agent_grants` <-> [AgentEgressGrant] mapping.
 *
 * `scopeJson` contains only canonical transport/origin/data classes/source ids. It never contains
 * credentials, model text, provider bodies or exception text. Unknown transports, malformed rows or
 * non-egress scope types return null so callers fail closed instead of authorizing an unreadable row.
 */
object AgentEgressGrantMapper {
    const val MAX_SOURCES: Int = 512
    const val MAX_ORIGIN_CHARS: Int = 512
    const val MAX_SCOPE_JSON_CHARS: Int = 64 * 1024

    fun encodeScope(grant: AgentEgressGrant): String {
        require(grant.origin.length <= MAX_ORIGIN_CHARS) { "egress origin is too long" }
        require(grant.allowedSourceIds.size <= MAX_SOURCES) { "too many egress source ids" }
        return JSONObject()
            .put("transport", grant.transport.name)
            .put("origin", grant.origin)
            .put("dataClasses", JSONArray(grant.allowedDataClasses.map { it.name }.sorted()))
            .put("sourceIds", JSONArray(grant.allowedSourceIds.sorted()))
            .toString()
    }

    fun decodeScope(raw: String): AgentEgressGrantScope? {
        if (raw.length > MAX_SCOPE_JSON_CHARS) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (json.length() != 4 ||
            !json.has("transport") || !json.has("origin") ||
            !json.has("dataClasses") || !json.has("sourceIds")
        ) {
            return null
        }
        val transport = json.optString("transport", "")
            .let { name -> AgentEgressTransport.entries.firstOrNull { it.name == name } }
            ?: return null
        val origin = json.optString("origin", "")
        if (origin.isBlank() || origin.length > MAX_ORIGIN_CHARS ||
            origin.any { it == '\n' || it == '\r' || it == '\u0000' }
        ) {
            return null
        }
        val canonical = when (
            val result = AgentEgressOrigins.canonical(
                origin,
                allowCleartext = transport == AgentEgressTransport.LOCAL_SERVER
            )
        ) {
            is AgentOriginResult.Valid -> result.origin
            is AgentOriginResult.Invalid -> return null
        }
        if (canonical != origin) return null
        if (transport == AgentEgressTransport.LOCAL_SERVER && !AgentEgressOrigins.isLocalOrPrivate(origin)) {
            return null
        }
        if (transport == AgentEgressTransport.REMOTE_MCP && AgentEgressOrigins.isLocalOrPrivate(origin)) {
            return null
        }
        val dataClasses = decodeStrings(json.optJSONArray("dataClasses")) ?: return null
        if (dataClasses.isEmpty() || dataClasses.distinct().size != dataClasses.size) return null
        val classes = dataClasses.map { name ->
            AgentDataClass.entries.firstOrNull { it.name == name } ?: return null
        }.toSet()
        val sourceIds = decodeStrings(json.optJSONArray("sourceIds")) ?: return null
        if (sourceIds.size > MAX_SOURCES || sourceIds.distinct().size != sourceIds.size) return null
        if (sourceIds.any { !AgentContract.isValidId(it) }) return null
        return AgentEgressGrantScope(
            transport = transport,
            origin = origin,
            allowedDataClasses = classes,
            allowedSourceIds = sourceIds.toSet()
        )
    }

    fun toEntity(grant: AgentEgressGrant, taskId: String, grantedAtMillis: Long): AgentGrantEntity {
        require(!grant.revoked) { "a newly created grant must not already be revoked" }
        require(grant.taskId == null || grant.taskId == taskId) { "grant task scope mismatch" }
        return AgentGrantEntity(
            grantId = grant.grantId,
            taskId = taskId,
            runId = grant.runId,
            scopeType = AgentGrantScope.EGRESS_PROVIDER.name,
            scopeJson = encodeScope(grant),
            policyVersion = grant.policyVersion,
            modelFingerprint = null,
            grantedAt = grantedAtMillis,
            expiresAt = grant.expiresAtMillis,
            revokedAt = null,
            revokedReason = null
        )
    }

    /** Null for non-egress rows, malformed scope JSON, unknown values, invalid ids or expired rows. */
    fun toContract(entity: AgentGrantEntity, nowMillis: Long): AgentEgressGrant? {
        if (entity.scopeType != AgentGrantScope.EGRESS_PROVIDER.name) return null
        if (entity.expiresAt != null && entity.expiresAt <= nowMillis) return null
        val scope = decodeScope(entity.scopeJson) ?: return null
        return runCatching {
            AgentEgressGrant(
                grantId = entity.grantId,
                runId = entity.runId,
                transport = scope.transport,
                origin = scope.origin,
                allowedDataClasses = scope.allowedDataClasses,
                allowedSourceIds = scope.allowedSourceIds,
                policyVersion = entity.policyVersion,
                revoked = entity.revokedAt != null,
                taskId = entity.taskId,
                expiresAtMillis = entity.expiresAt
            )
        }.getOrNull()
    }

    private fun decodeStrings(array: JSONArray?): List<String>? {
        array ?: return null
        if (array.length() > MAX_SOURCES) return null
        val values = ArrayList<String>(array.length())
        for (index in 0 until array.length()) {
            val value = array.optString(index, null) ?: return null
            if (value.isEmpty()) return null
            values += value
        }
        return values
    }
}
