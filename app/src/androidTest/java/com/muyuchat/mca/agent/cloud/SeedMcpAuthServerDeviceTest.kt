package com.muyuchat.mca.agent.cloud

import androidx.test.platform.app.InstrumentationRegistry
import com.muyuchat.core.agent.contract.AgentDataClass
import com.muyuchat.mca.agent.security.AgentEgressTransport
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpAuthorization
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpEndpointCheck
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpEndpointPolicy
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerMutation
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerRecord
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerStore
import com.muyuchat.mca.agent.extensions.mcp.KeystoreMcpCredentialStore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Device-only helper: seeds one authenticated loopback MCP server (token passed via instrumentation
 * arguments, never checked in). Skips when `mcpToken` is absent.
 */
class SeedMcpAuthServerDeviceTest {

    @Test
    fun seedAuthenticatedLoopbackServer() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("mcpToken").orEmpty()
        assumeTrue("mcpToken instrumentation argument is required", token.isNotBlank())
        val endpoint = args.getString("mcpEndpoint") ?: "http://127.0.0.1:8765/mcp"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val check = AgentMcpEndpointPolicy.check(endpoint, allowLocal = true)
        assertTrue("endpoint rejected: $check", check is AgentMcpEndpointCheck.Allowed)
        val origin = (check as AgentMcpEndpointCheck.Allowed).origin
        val store = AgentMcpServerStore(context.filesDir)
        store.remove(SERVER_ID)
        val record = AgentMcpServerRecord(
            serverId = SERVER_ID,
            displayName = "AuthLoop",
            endpoint = endpoint,
            allowLocal = true,
            allowAgentNetwork = true,
            createdAtMillis = System.currentTimeMillis(),
            agentAuthorization = AgentMcpAuthorization(
                origin = origin,
                transport = AgentEgressTransport.LOCAL_SERVER,
                dataClasses = setOf(AgentDataClass.PRIVATE),
                grantedAtMillis = System.currentTimeMillis()
            ),
            credentialRefId = CREDENTIAL_REF
        )
        val mutation = store.add(record)
        assertTrue("server seed failed: $mutation", mutation is AgentMcpServerMutation.Applied)
        assertTrue(KeystoreMcpCredentialStore(context).put(CREDENTIAL_REF, token))
        Thread.sleep(2_000)
    }

    @Test
    fun storedTokenReadsBackAcrossInstrumentationRuns() {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("mcpToken").orEmpty()
        assumeTrue("mcpToken instrumentation argument is required", token.isNotBlank())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val read = KeystoreMcpCredentialStore(context).get(CREDENTIAL_REF)
        assertTrue("stored token did not decrypt; read=$read", read == token)
    }

    private companion object {
        const val SERVER_ID = "authloop"
        const val CREDENTIAL_REF = "mcp-authloop"
    }
}
