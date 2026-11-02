package com.muyuchat.mca.agent.data.egress

import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.mca.agent.data.AgentGrantEntity
import com.muyuchat.mca.agent.data.AgentGrantScope
import com.muyuchat.mca.agent.security.AgentEgressGrant
import com.muyuchat.mca.agent.security.AgentEgressTransport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEgressGrantMapperTest {
    private val now = 1_000L

    @Test
    fun `scope encoding is deterministic and round trips all fields`() {
        val grant = grant(
            allowedDataClasses = setOf(AgentDataClass.PRIVATE, AgentDataClass.PUBLIC),
            allowedSourceIds = setOf("source-z", "source-a")
        )
        val raw = AgentEgressGrantMapper.encodeScope(grant)
        val json = JSONObject(raw)
        assertEquals("CLOUD_PROVIDER", json.getString("transport"))
        assertEquals("https://api.example.com", json.getString("origin"))
        assertEquals(listOf("PRIVATE", "PUBLIC"), json.getJSONArray("dataClasses").toStringList())
        assertEquals(listOf("source-a", "source-z"), json.getJSONArray("sourceIds").toStringList())
        assertEquals(raw, AgentEgressGrantMapper.encodeScope(grant))
        val decoded = AgentEgressGrantMapper.decodeScope(raw)!!
        assertEquals(AgentEgressTransport.CLOUD_PROVIDER, decoded.transport)
        assertEquals(grant.origin, decoded.origin)
        assertEquals(grant.allowedDataClasses, decoded.allowedDataClasses)
        assertEquals(grant.allowedSourceIds, decoded.allowedSourceIds)
    }

    @Test
    fun `entity round trip keeps task run expiry and revoked state`() {
        val grant = grant(expiresAtMillis = 2_000L)
        val entity = AgentEgressGrantMapper.toEntity(grant, "task-1", now)
        assertEquals(AgentGrantScope.EGRESS_PROVIDER.name, entity.scopeType)
        assertEquals("task-1", entity.taskId)
        assertEquals("run-1", entity.runId)
        assertEquals(2_000L, entity.expiresAt)
        val mapped = AgentEgressGrantMapper.toContract(entity, now)!!
        assertEquals(grant.grantId, mapped.grantId)
        assertEquals("task-1", mapped.taskId)
        assertEquals("run-1", mapped.runId)
        assertEquals(grant.allowedDataClasses, mapped.allowedDataClasses)
        assertEquals(grant.allowedSourceIds, mapped.allowedSourceIds)
        assertEquals(grant.policyVersion, mapped.policyVersion)
        assertFalse(mapped.revoked)

        val revoked = AgentEgressGrantMapper.toContract(
            entity.copy(revokedAt = 1_500L, revokedReason = "USER"),
            now
        )!!
        assertTrue(revoked.revoked)
        assertNull("expiry must filter active rows", AgentEgressGrantMapper.toContract(entity, 2_000L))
    }

    @Test
    fun `invalid scope rows fail closed`() {
        val valid = AgentEgressGrantMapper.encodeScope(grant())
        assertNull(AgentEgressGrantMapper.decodeScope("{"))
        assertNull(AgentEgressGrantMapper.decodeScope(JSONObject(valid).put("transport", "UNKNOWN").toString()))
        assertNull(AgentEgressGrantMapper.decodeScope(JSONObject(valid).put("extra", true).toString()))
        assertNull(AgentEgressGrantMapper.decodeScope(JSONObject(valid).remove("origin").toString()))
        assertNull(
            AgentEgressGrantMapper.decodeScope(
                JSONObject(valid).put("sourceIds", JSONArray(listOf("source-1", "source-1"))).toString()
            )
        )
        assertNull(
            AgentEgressGrantMapper.decodeScope(
                JSONObject(valid).put("sourceIds", JSONArray(listOf("bad source"))).toString()
            )
        )
        assertNull(
            AgentEgressGrantMapper.decodeScope(
                JSONObject(valid).put("dataClasses", JSONArray(listOf("NOT_A_CLASS"))).toString()
            )
        )
        assertNull(
            AgentEgressGrantMapper.decodeScope(
                JSONObject(valid).put("origin", "https://API.example.com").toString()
            )
        )
        assertNull(
            AgentEgressGrantMapper.decodeScope(
                JSONObject(valid).put("transport", "REMOTE_MCP")
                    .put("origin", "https://127.0.0.1:8443").toString()
            )
        )
    }

    @Test
    fun `non egress malformed or expired entities never map to an active grant`() {
        val entity = AgentEgressGrantMapper.toEntity(grant(), "task-1", now)
        assertNull(
            AgentEgressGrantMapper.toContract(
                entity.copy(scopeType = AgentGrantScope.WORKSPACE_READ.name),
                now
            )
        )
        assertNull(AgentEgressGrantMapper.toContract(entity.copy(scopeJson = "{}"), now))
        assertNull(AgentEgressGrantMapper.toContract(entity.copy(expiresAt = 10L), now))
    }

    private fun grant(
        allowedDataClasses: Set<AgentDataClass> = setOf(AgentDataClass.PRIVATE),
        allowedSourceIds: Set<String> = setOf("source-1"),
        expiresAtMillis: Long? = null
    ): AgentEgressGrant = AgentEgressGrant(
        grantId = "grant-1",
        runId = "run-1",
        transport = AgentEgressTransport.CLOUD_PROVIDER,
        origin = "https://api.example.com",
        allowedDataClasses = allowedDataClasses,
        allowedSourceIds = allowedSourceIds,
        policyVersion = 3L,
        taskId = "task-1",
        expiresAtMillis = expiresAtMillis
    )
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { index -> getString(index) }
