package com.muyuchat.mca.agent.extensions.mcp

/**
 * The exact egress question the MCP registry asks before it creates a client or opens a socket.
 *
 * The Lead supplies the implementation over K07's run/permission policy; the registry deliberately
 * does not import security types so the decision stays in one owner. `origin` is the canonical
 * `scheme://host[:port]` derived by [AgentMcpEndpointPolicy], never raw model text.
 */
data class AgentMcpEgressRequest(
    val serverId: String,
    val origin: String,
    val localServer: Boolean
)

sealed interface AgentMcpEgressDecision {
    data object Allowed : AgentMcpEgressDecision

    data class Denied(val code: String, val safeMessage: String) : AgentMcpEgressDecision
}

fun interface AgentNetworkEgressGuard {
    fun check(request: AgentMcpEgressRequest): AgentMcpEgressDecision
}
