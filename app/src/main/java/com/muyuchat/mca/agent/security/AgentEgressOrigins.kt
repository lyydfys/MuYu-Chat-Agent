package com.muyuchat.mca.agent.security

import java.net.URI

/** Origin rules (architecture 11.3): HTTPS by default, no redirect/userinfo/query, exact host:port. */
internal object AgentEgressOrigins {
    private const val MAX_ORIGIN_CHARS = 2_048
    private const val LOOPBACK_HOST = "localhost"

    fun canonical(raw: String, allowCleartext: Boolean): AgentOriginResult {
        if (raw.isBlank() || raw.length > MAX_ORIGIN_CHARS) return invalid()
        if (raw.any { it.isWhitespace() || it < ' ' }) return invalid()
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return invalid()
        }
        val scheme = uri.scheme?.lowercase() ?: return invalid()
        if (scheme != "https" && !(allowCleartext && scheme == "http")) return invalid()
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return invalid()
        if (!uri.path.isNullOrEmpty() && uri.path != "/") return invalid()
        val rawHost = uri.host ?: return invalid()
        val host = rawHost.lowercase()
        if (host.isEmpty() || host.any { it.isWhitespace() || it < ' ' }) return invalid()
        val defaultPort = if (scheme == "https") 443 else 80
        val port = uri.port
        if (port != -1 && port !in 1..65_535) return invalid()
        val suffix = if (port == -1 || port == defaultPort) "" else ":$port"
        return AgentOriginResult.Valid("$scheme://$host$suffix")
    }

    fun canonicalOrRaw(raw: String, transport: AgentEgressTransport): String =
        when (val result = canonical(raw, allowCleartext = transport == AgentEgressTransport.LOCAL_SERVER)) {
            is AgentOriginResult.Valid -> result.origin
            is AgentOriginResult.Invalid -> ""
        }

    fun isLocalOrPrivate(canonicalOrigin: String): Boolean {
        val host = try {
            URI(canonicalOrigin).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: return false
        val bare = host.removePrefix("[").removeSuffix("]")
        if (bare == LOOPBACK_HOST || bare == "::1" || bare == "::") return true
        if (bare.contains(':')) {
            return bare.startsWith("fc") || bare.startsWith("fd") || bare.startsWith("fe80")
        }
        val octets = bare.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 127 ||
            octets[0] == 10 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254)
    }

    private fun invalid(): AgentOriginResult.Invalid =
        AgentOriginResult.Invalid(
            AgentSecurityErrors.egressDenied("Origin must be an exact HTTPS origin (no path, query, userinfo or redirect).")
        )
}
