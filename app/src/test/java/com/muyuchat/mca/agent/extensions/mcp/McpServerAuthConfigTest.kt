package com.muyuchat.mca.agent.extensions.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpServerAuthConfigTest {

    @Test
    fun credentialRefRoundTripsWithoutSerializingTheSecret() {
        val record = AgentMcpServerRecord(
            serverId = "auth-server",
            displayName = "Auth",
            endpoint = "https://mcp.example.com/mcp",
            credentialRefId = "cred-1"
        )
        assertEquals(McpAuth.SecretReference("cred-1"), record.toClientConfig().auth)
        val json = record.toJson()
        assertEquals("cred-1", json.getString("credentialRefId"))
        assertTrue("the token itself must never be serialized", !json.toString().contains("Bearer"))

        val restored = AgentMcpServerRecord.fromJson(json)
        assertEquals("cred-1", restored.credentialRefId)
        assertEquals(McpAuth.SecretReference("cred-1"), restored.toClientConfig().auth)
    }

    @Test
    fun legacyRecordKeepsNoAuth() {
        val record = AgentMcpServerRecord(
            serverId = "plain-server",
            displayName = "Plain",
            endpoint = "https://mcp.example.com/mcp"
        )
        assertNull(record.credentialRefId)
        assertTrue(record.toClientConfig().auth is McpAuth.None)
        val restored = AgentMcpServerRecord.fromJson(record.toJson())
        assertNull(restored.credentialRefId)
    }
}
