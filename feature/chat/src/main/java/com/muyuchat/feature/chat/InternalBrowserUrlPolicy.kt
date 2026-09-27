package com.muyuchat.feature.chat

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Navigation policy for the in-app web preview.
 *
 * A page is opened only after the user taps a source card. The initial URL
 * must be HTTPS and subsequent navigations stay on that source's registrable
 * host (including its subdomains). This keeps a result preview from silently
 * becoming an unrestricted browser or an intent trampoline.
 */
internal object InternalBrowserUrlPolicy {
    /**
     * Builds a user-requested search URL from an explicitly configured HTTPS
     * endpoint.  The endpoint must contain exactly one {query} placeholder;
     * the query is encoded before substitution so text cannot become URL
     * syntax.  This is intentionally a URL helper only: it does not grant the
     * page permission to click arbitrary result links or execute commands.
     */
    fun buildSearchUrl(template: String, query: String): String? {
        val cleanedQuery = query.trim()
        if (cleanedQuery.isBlank() || cleanedQuery.length > 512) return null
        if (template.count { it == '{' } != 1 || template.count { it == '}' } != 1) return null
        if (!template.contains("{query}")) return null
        val base = template.replace("{query}", "query-placeholder")
        val normalized = normalizeInitialUrl(base) ?: return null
        val encoded = URLEncoder.encode(cleanedQuery, StandardCharsets.UTF_8.name())
        return normalized.replace("query-placeholder", encoded)
    }

    fun normalizeInitialUrl(rawUrl: String): String? =
        parseHttpsUrl(rawUrl)?.toString()

    fun allowedHost(rawUrl: String): String? =
        parseHttpsUrl(rawUrl)?.host?.normalizedHost()

    fun allowedOrigin(rawUrl: String): String? = parseHttpsUrl(rawUrl)?.let { uri ->
        val host = uri.host.lowercase().trimEnd('.')
        "https://$host" + if (effectivePort(uri) == 443) "" else ":${effectivePort(uri)}"
    }

    fun allowsNavigation(
        initialUrl: String,
        candidateUrl: String,
        additionalAllowedHosts: Set<String> = emptySet(),
        additionalApprovedOrigins: Set<String> = emptySet()
    ): Boolean {
        val initial = parseHttpsUrl(initialUrl) ?: return false
        val initialHost = initial.host.normalizedHost()
        val candidate = parseHttpsUrl(candidateUrl) ?: return false
        val candidateHost = candidate.host.normalizedHost()
        val normalizedAdditionalHosts = additionalAllowedHosts
            .mapNotNull { parseHost(it) }
        val sourceAllowed = effectivePort(candidate) == effectivePort(initial) &&
            (candidateHost == initialHost || candidateHost.endsWith(".$initialHost"))
        val legacyHostAllowed = effectivePort(candidate) == 443 && normalizedAdditionalHosts.any {
            candidateHost == it || candidateHost.endsWith(".$it")
        }
        val approvedOrigin = allowedOrigin(candidateUrl)
        return sourceAllowed || legacyHostAllowed || approvedOrigin in additionalApprovedOrigins
    }

    private fun parseHost(rawHost: String): String? {
        val host = rawHost.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (host.isBlank() || host.contains('/') || host.contains(':')) return null
        return host
    }

    private fun parseHttpsUrl(rawUrl: String): URI? {
        val trimmed = rawUrl.trim()
        if (trimmed.length > 4096 || trimmed.isBlank()) return null
        if (trimmed.any { it <= ' ' || it == '\u007f' }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return null
        if (uri.port !in -1..65535 || uri.port == 0) return null
        return uri
    }

    private fun effectivePort(uri: URI): Int = if (uri.port == -1) 443 else uri.port

    private fun String.normalizedHost(): String =
        trimEnd('.').lowercase().removePrefix("www.")
}
