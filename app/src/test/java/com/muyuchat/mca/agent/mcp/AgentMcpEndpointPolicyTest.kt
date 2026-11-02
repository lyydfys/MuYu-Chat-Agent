package com.muyuchat.mca.agent.extensions.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpoint policy for user-authored MCP servers.
 *
 * The policy is the first SSRF/credential-leak boundary: public servers must be HTTPS, cleartext is
 * loopback-only and gated by an explicit local-server flag, and credentials/query tokens/fragments
 * never reach durable config.
 */
class AgentMcpEndpointPolicyTest {

    private fun allowed(endpoint: String, allowLocal: Boolean = false): String {
        val check = AgentMcpEndpointPolicy.check(endpoint, allowLocal)
        assertTrue("expected allowed, got $check", check is AgentMcpEndpointCheck.Allowed)
        return (check as AgentMcpEndpointCheck.Allowed).origin
    }

    private fun rejected(endpoint: String, allowLocal: Boolean = false, code: String) {
        val check = AgentMcpEndpointPolicy.check(endpoint, allowLocal)
        assertEquals(AgentMcpEndpointCheck.Rejected(code), check)
    }

    @Test
    fun publicHttpsOriginIsAllowedAndNormalised() {
        assertEquals("https://mcp.example.com:8443", allowed("HTTPS://MCP.Example.com.:8443/path"))
    }

    @Test
    fun localhostCleartextNeedsTheExplicitLocalFlag() {
        rejected("http://127.0.0.1:8765/mcp", allowLocal = false, code = "MCP_ENDPOINT_PRIVATE_DENIED")
        assertEquals("http://127.0.0.1:8765", allowed("http://127.0.0.1:8765/mcp", allowLocal = true))
        assertEquals("http://localhost:8765", allowed("http://localhost:8765/sse", allowLocal = true))
    }

    @Test
    fun cleartextIsLoopbackOnlyEvenWhenLocalIsAllowed() {
        // A LAN address is local, but plaintext to a non-loopback host is still refused.
        rejected("http://192.168.1.5:8765/mcp", allowLocal = true, code = "MCP_ENDPOINT_CLEARTEXT_DENIED")
    }

    @Test
    fun privateHttpsTargetsRequireTheLocalFlag() {
        rejected("https://192.168.1.5:8765/mcp", allowLocal = false, code = "MCP_ENDPOINT_PRIVATE_DENIED")
        assertEquals("https://192.168.1.5:8765", allowed("https://192.168.1.5:8765/mcp", allowLocal = true))
        rejected("https://10.0.0.8/mcp", allowLocal = false, code = "MCP_ENDPOINT_PRIVATE_DENIED")
        rejected("https://172.20.1.1/mcp", allowLocal = false, code = "MCP_ENDPOINT_PRIVATE_DENIED")
    }

    @Test
    fun ipv6LoopbackIsTreatedAsLocal() {
        rejected("https://[::1]:8765/mcp", allowLocal = false, code = "MCP_ENDPOINT_PRIVATE_DENIED")
        assertEquals("https://[::1]:8765", allowed("https://[::1]:8765/mcp", allowLocal = true))
    }

    @Test
    fun credentialsQueriesAndFragmentsAreNeverPersisted() {
        rejected("https://user:secret@mcp.example.com/mcp", code = "MCP_ENDPOINT_EMBEDS_CREDENTIALS")
        rejected("https://mcp.example.com/mcp?token=abc", code = "MCP_ENDPOINT_QUERY_DENIED")
        rejected("https://mcp.example.com/mcp#frag", code = "MCP_ENDPOINT_FRAGMENT")
    }

    @Test
    fun malformedOrNonHttpEndpointsAreRejected() {
        rejected("", code = "MCP_ENDPOINT_EMPTY")
        rejected("  https://mcp.example.com/mcp", code = "MCP_ENDPOINT_WHITESPACE")
        rejected("file:///etc/passwd", code = "MCP_ENDPOINT_SCHEME")
        rejected("https://", code = "MCP_ENDPOINT_HOST")
        rejected("not a url", code = "MCP_ENDPOINT_INVALID")
        rejected("https://mcp.example.com/" + "a".repeat(2_048), code = "MCP_ENDPOINT_TOO_LONG")
    }
}
