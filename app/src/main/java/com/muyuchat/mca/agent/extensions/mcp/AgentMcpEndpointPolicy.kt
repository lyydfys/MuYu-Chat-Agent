package com.muyuchat.mca.agent.extensions.mcp

import java.net.URI

/** Result of the local-only endpoint policy check. */
sealed interface AgentMcpEndpointCheck {
    data class Allowed(val origin: String) : AgentMcpEndpointCheck

    data class Rejected(val code: String) : AgentMcpEndpointCheck
}

/**
 * Tiny endpoint policy for user-authored MCP server configs (architecture 12.2).
 *
 * Remote servers must be plain HTTPS origins. A cleartext or loopback/private target is accepted
 * only when the user explicitly marked the server as local (`allowLocal=true`); cleartext itself is
 * limited to loopback. Credentials, fragments and non-HTTP(S) schemes are always rejected. The
 * actual egress decision (run policy, grants, provider origin allow-list) stays in
 * [AgentNetworkEgressGuard]; this policy only keeps impossible/unsafe URLs out of the store.
 */
object AgentMcpEndpointPolicy {
    const val MAX_ENDPOINT_CHARS: Int = 2_048

    fun check(endpoint: String, allowLocal: Boolean): AgentMcpEndpointCheck {
        if (endpoint.isBlank()) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_EMPTY")
        if (endpoint.length > MAX_ENDPOINT_CHARS) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_TOO_LONG")
        if (endpoint != endpoint.trim()) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_WHITESPACE")
        if (endpoint.any { it == '\u0000' || it.isISOControl() }) {
            return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_CONTROL_CHARACTER")
        }
        // `java.net.URI` rejects a bare "scheme://" as INVALID; the actionable cause is a missing
        // host, so classify it before parsing instead of leaking a generic invalid code.
        if (endpoint.endsWith("://")) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_HOST")
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_INVALID")
        if (uri.userInfo != null) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_EMBEDS_CREDENTIALS")
        if (uri.fragment != null) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_FRAGMENT")
        // V1 has no query-token auth; a query string is the easiest place to smuggle a secret into
        // a durable config file, so it is rejected instead of persisted.
        if (uri.rawQuery != null) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_QUERY_DENIED")
        val scheme = uri.scheme?.lowercase() ?: return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_SCHEME")
        if (scheme != "https" && scheme != "http") return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_SCHEME")
        val host = normalizedHost(uri.host ?: return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_HOST"))
        if (host.isBlank()) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_HOST")
        if (uri.port > 65_535) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_PORT")
        val local = isLocalOrPrivate(host)
        if (local && !allowLocal) return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_PRIVATE_DENIED")
        if (scheme == "http" && (!allowLocal || !isLoopback(host))) {
            return AgentMcpEndpointCheck.Rejected("MCP_ENDPOINT_CLEARTEXT_DENIED")
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        // IPv6 literals must keep their brackets in an origin, otherwise "https://::1:8765" is not
        // a parseable URL and the stored origin no longer matches the endpoint.
        val originHost = if (host.contains(':')) "[$host]" else host
        return AgentMcpEndpointCheck.Allowed("$scheme://$originHost$port")
    }

    fun isLocalOrPrivate(host: String): Boolean {
        val normalized = normalizedHost(host)
        if (normalized == "localhost" || normalized == "::1") return true
        val parts = normalized.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 || first == 127 || (first == 192 && second == 168) ||
            (first == 172 && second in 16..31) || (first == 169 && second == 254)
    }

    private fun isLoopback(host: String): Boolean {
        val normalized = normalizedHost(host)
        if (normalized == "localhost" || normalized == "::1") return true
        val parts = normalized.split('.')
        return parts.size == 4 && parts[0] == "127"
    }

    private fun normalizedHost(raw: String): String =
        raw.trim().lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
}

