package com.muyuchat.core.dsh.contract.auth

import java.net.URI

/**
 * Pure rules for the official DeepSeek account URLs, shared by the host and the Web container so a
 * handed-off popup can be correlated with the live official attempt.
 *
 * Grounding (pinned `dsh-deepseek-account-platform`):
 * - `loginOrigin(callbackOrigin)` accepts only an HTTP loopback origin with an explicit non-zero
 *   port and no user information/path/query/fragment;
 * - `browserUrl(value, origin, path)` accepts only the configured HTTPS platform origin, the exact
 *   `/dsh/authorize` (or `/dsh/authorized`) path, and no user information/fragment;
 * - the official settings client may append or replace the single `theme` query parameter of the
 *   already-validated authorize URL. Nothing else may change, and the comparison must not reject a
 *   legitimate theme conversion.
 *
 * This object contains no secret: it only classifies and compares URLs.
 */
object DshAuthorizeUrlRules {

    private const val THEME = "theme"

    /** Canonical `http://loopback:port` callback origin, or null when not accepted. */
    fun normalizeCallbackOrigin(origin: String): String? {
        val uri = runCatching { URI(origin) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host ?: return null
        if (!isLoopback(host)) return null
        if (uri.port !in 1..65535) return null
        if (uri.rawPath != null && uri.rawPath.isNotEmpty() && uri.rawPath != "/") return null
        if (uri.rawQuery != null) return null
        if (uri.rawFragment != null) return null
        return "http://$host:${uri.port}"
    }

    fun isValidCallbackOrigin(origin: String): Boolean = normalizeCallbackOrigin(origin) != null

    /** The fixed official redirect URI (`callbackOrigin + "/oauth/callback"`), or null. */
    fun redirectUri(
        callbackOrigin: String,
        bounds: DshAuthAttemptBounds = DshAuthAttemptBounds()
    ): String? {
        val normalized = normalizeCallbackOrigin(callbackOrigin) ?: return null
        return normalized + bounds.callbackPath
    }

    /** True for the official HTTPS origin + authorize path shape, without user info or fragment. */
    fun isOfficialAuthorizeUrl(
        url: String,
        bounds: DshAuthAttemptBounds = DshAuthAttemptBounds()
    ): Boolean = parse(url)?.let { parsed ->
        parsed.protocol.equals("https", ignoreCase = true) &&
            parsed.origin == bounds.platformOrigin &&
            parsed.path == bounds.authorizePath &&
            parsed.userInfo == null &&
            parsed.fragment == null
    } ?: false

    /**
     * Compares the authorize URL produced by the official module with the URL the client wants to
     * open. The only accepted difference is one `theme` parameter; every other parameter, including
     * its multi-value set, must match exactly.
     */
    fun matchesOfficialAuthorizeUrl(
        original: String,
        candidate: String,
        bounds: DshAuthAttemptBounds = DshAuthAttemptBounds()
    ): Boolean {
        if (!isOfficialAuthorizeUrl(original, bounds)) return false
        if (!isOfficialAuthorizeUrl(candidate, bounds)) return false
        val expected = queryMultiset(original).filter { it.first != THEME }
        val actual = queryMultiset(candidate).filter { it.first != THEME }
        if (expected != actual) return false
        val originalTheme = queryMultiset(original).filter { it.first == THEME }
        val candidateTheme = queryMultiset(candidate).filter { it.first == THEME }
        if (candidateTheme.size > 1) return false
        if (originalTheme.isEmpty()) return true
        return candidateTheme.size == 1
    }

    private fun queryMultiset(url: String): List<Pair<String, String>> {
        val raw = parse(url)?.rawQuery ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split('&').mapNotNull { segment ->
            if (segment.isEmpty()) return@mapNotNull null
            val name = segment.substringBefore('=')
            if (name.isEmpty()) return@mapNotNull null
            name to segment.substringAfter('=', "")
        }
    }

    private fun isLoopback(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "::1", "[::1]", "localhost" -> true
        else -> false
    }

    private data class ParsedUrl(
        val protocol: String,
        val origin: String,
        val path: String,
        val userInfo: String?,
        val fragment: String?,
        val rawQuery: String?
    )

    private fun parse(url: String): ParsedUrl? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else -1
        val origin = if (port > 0) "$scheme://$host:$port" else "$scheme://$host"
        return ParsedUrl(
            protocol = scheme,
            origin = origin,
            path = uri.rawPath ?: "",
            userInfo = uri.rawUserInfo,
            fragment = uri.rawFragment,
            rawQuery = uri.rawQuery
        )
    }
}
