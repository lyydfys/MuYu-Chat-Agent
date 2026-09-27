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

    fun allowsNavigation(
        initialUrl: String,
        candidateUrl: String,
        additionalAllowedHosts: Set<String> = emptySet()
    ): Boolean {
        val initialHost = allowedHost(initialUrl) ?: return false
        val candidate = parseHttpsUrl(candidateUrl) ?: return false
        val candidateHost = candidate.host.normalizedHost()
        val normalizedAdditionalHosts = additionalAllowedHosts
            .mapNotNull { parseHost(it) }
        return candidateHost == initialHost ||
            candidateHost.endsWith(".$initialHost") ||
            normalizedAdditionalHosts.any { candidateHost == it || candidateHost.endsWith(".$it") }
    }

    private fun parseHost(rawHost: String): String? {
        val host = rawHost.trim().lowercase().removePrefix("www.").trimEnd('.')
        if (host.isBlank() || host.contains('/') || host.contains(':')) return null
        return host
    }

    private fun parseHttpsUrl(rawUrl: String): URI? {
        val trimmed = rawUrl.trim()
        if (trimmed.length > 4096 || trimmed.isBlank()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.userInfo != null || uri.host.isNullOrBlank()) return null
        if (uri.fragment?.contains("\n") == true) return null
        return uri
    }

    private fun String.normalizedHost(): String =
        trimEnd('.').removePrefix("www.").lowercase()
}
