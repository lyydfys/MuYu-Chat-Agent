package com.muyuchat.feature.dsh.workspace

import java.net.URI

/** Why a navigation or link was refused before any WebView or Android component touched it. */
enum class DshBlockReason {
    INVALID_URL,
    NON_HTTP_SCHEME,
    CREDENTIALS_IN_URL,
    URL_TOO_LONG
}

/** The only three outcomes a DSH page navigation may have. */
sealed interface DshNavigationDecision {
    /** Same-origin http navigation: keep it inside the WebView. */
    data object KeepInsideWebView : DshNavigationDecision

    /** Validated external http(s) link: the host must open it through Android, never in the WebView. */
    data class OpenExternally(val url: String) : DshNavigationDecision

    /** Everything else (javascript:, intent:, file:, content:, blob:, data:, malformed, ...). */
    data class Blocked(val reason: DshBlockReason) : DshNavigationDecision
}

/**
 * Pure navigation/restore policy shared by the WebView client, the ViewModel and the unit tests.
 *
 * Security invariants:
 * - Only `http`/`https` are ever considered; all other schemes are blocked even if they look like
 *   useful intents. This is intentionally stricter than a browser.
 * - Embedded `user:password@` credentials are rejected for both internal and external URLs.
 * - The WebView keeps only exact same-origin http navigations (see [DshOrigin.sameOrigin]).
 * - Restore state is a path only: query strings (which carry the DSH launch token) are never
 *   persisted, returned to a caller or shown in a log.
 */
object DshNavigationPolicy {

    /** Browsers can open long URLs, but an Android Intent should not carry an unbounded payload. */
    const val MAX_EXTERNAL_URL_LENGTH = 4096

    /** A SPA route path is tiny; anything longer is not a path we generated. */
    const val MAX_RESTORE_PATH_LENGTH = 512

    private val CONTROL_OR_SPACE = Regex("[\\u0000-\\u0020\\u007F]")
    private val PERCENT_ENCODED_SEPARATOR = Regex("(?i)%(?:2e|2f|5c)")
    private val SCHEME_PREFIX = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    fun classify(url: String?, origin: DshOrigin?): DshNavigationDecision {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL)
        if (candidate.length > MAX_EXTERNAL_URL_LENGTH) {
            return DshNavigationDecision.Blocked(DshBlockReason.URL_TOO_LONG)
        }
        // Classify the scheme before URI parsing: malformed non-http payloads (e.g. data: URLs
        // with < > characters) must still be reported as a blocked scheme, not as an unknown URL.
        val explicitScheme = SCHEME_PREFIX.find(candidate)?.groupValues?.getOrNull(1)?.lowercase()
        if (explicitScheme != null && explicitScheme != "http" && explicitScheme != "https") {
            return DshNavigationDecision.Blocked(DshBlockReason.NON_HTTP_SCHEME)
        }
        // A raw CR/LF/control character can smuggle header or Intent data; never accept one.
        if (CONTROL_OR_SPACE.containsMatchIn(candidate)) {
            return DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL)
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return DshNavigationDecision.Blocked(DshBlockReason.NON_HTTP_SCHEME)
        }
        if (uri.host.isNullOrBlank()) {
            return DshNavigationDecision.Blocked(DshBlockReason.INVALID_URL)
        }
        if (uri.rawUserInfo != null) {
            return DshNavigationDecision.Blocked(DshBlockReason.CREDENTIALS_IN_URL)
        }
        if (scheme == "http" && origin != null && origin.matches(candidate)) {
            return DshNavigationDecision.KeepInsideWebView
        }
        return DshNavigationDecision.OpenExternally(candidate)
    }

    /** True when an [DshNavigationDecision.OpenExternally] URL may be handed to an Android Intent. */
    fun isOpenableExternalUrl(url: String): Boolean =
        classify(url, origin = null) is DshNavigationDecision.OpenExternally

    /**
     * Accepts only a same-origin path component for persistence/restore.
     *
     * Rejected: absolute URLs, protocol-relative paths, queries/fragments (the launch token lives in
     * the query), control/space characters, backslashes, dot segments and encoded separators.
     */
    fun sanitizeRestorePath(raw: String?): String? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty() || candidate.length > MAX_RESTORE_PATH_LENGTH) return null
        if (!candidate.startsWith("/") || candidate.startsWith("//")) return null
        if (CONTROL_OR_SPACE.containsMatchIn(candidate)) return null
        if (candidate.contains('?') || candidate.contains('#')) return null
        if (candidate.contains('\\') || candidate.contains("://")) return null
        if (PERCENT_ENCODED_SEPARATOR.containsMatchIn(candidate)) return null
        val segments = candidate.split('/')
        if (segments.any { it == "." || it == ".." }) return null
        return candidate
    }

    /** Restore target for the current origin; null when the stored path is unusable. */
    fun restoreUrl(origin: DshOrigin, path: String?): String? =
        sanitizeRestorePath(path)?.let(origin::pathUrl)

    /**
     * The path to persist for a clean same-origin URL. Returns null for a token URL, for another
     * origin, or for a URL that carries a query/fragment.
     */
    fun pathForRestore(url: String?, origin: DshOrigin): String? {
        val candidate = url?.trim().orEmpty()
        if (!origin.matches(candidate)) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.rawQuery != null || uri.rawFragment != null) return null
        return sanitizeRestorePath(uri.rawPath)
    }

    /**
     * True when the URL still carries the one-shot launch token in its query. Never parses or
     * returns the token itself; used only to decide whether authentication has settled.
     */
    fun hasLaunchTokenQuery(url: String?): Boolean {
        val candidate = url?.trim().orEmpty()
        if (candidate.isEmpty()) return false
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        val query = uri.rawQuery ?: return false
        return query.split('&').any { part ->
            val name = part.substringBefore('=', "")
            name.equals("token", ignoreCase = true)
        }
    }

    /** The authenticated entry has been exchanged for the HttpOnly cookie and redirected cleanly. */
    fun isCleanAuthenticatedUrl(url: String?, origin: DshOrigin): Boolean =
        origin.matches(url) && !hasLaunchTokenQuery(url)

    /**
     * One-shot restore decision after the fresh cookie-authenticated page settled. Returns the
     * absolute same-origin URL to load, or null when the current page already is the restore target.
     */
    fun restoreTargetIfNeeded(origin: DshOrigin, currentCleanUrl: String?, restorePath: String?): String? {
        val path = sanitizeRestorePath(restorePath) ?: return null
        if (path == "/") return null
        val currentPath = pathForRestore(currentCleanUrl, origin)
        if (currentPath == path) return null
        return origin.pathUrl(path)
    }
}
