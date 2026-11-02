package com.muyuchat.feature.dsh.workspace

import java.net.URI

/**
 * Log-safe rendering of DSH URLs and free text.
 *
 * The authenticated DSH entry carries a single-use launch token as `?token=...`. That value must
 * never reach logcat, a crash report, a saved-state bundle or an external Intent. Instead of trying
 * to classify "which query parameter is secret", this object drops the whole query and fragment of
 * any URL; they are never needed for diagnostics. Long high-entropy path segments are redacted too,
 * because a future auth flow could put the ticket in the path.
 */
object DshUrlRedaction {

    private const val REDACTED = "<redacted>"

    /** Long base64url/hex-ish runs are treated as credentials wherever they appear. */
    private val HIGH_ENTROPY_RUN = Regex("[A-Za-z0-9_\\-+/=]{32,}")

    private val SECRET_ASSIGNMENT = Regex(
        "(?i)(token|ticket|launch|secret|password|passwd|apikey|api_key|authorization|bearer)=([^&\\s\"']+)"
    )

    /** Returns `scheme://host[:port]/path` with query/fragment/entropy segments replaced. */
    fun redact(raw: String?): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return ""
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return redactText(candidate)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme.isNullOrBlank() || host.isNullOrBlank()) return redactText(candidate)
        val portPart = if (uri.port >= 0) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val safePath = path.split('/').joinToString("/") { segment ->
            if (segment.length >= 32 && HIGH_ENTROPY_RUN.matches(segment)) REDACTED else segment
        }
        val queryPart = if (uri.rawQuery != null) "?$REDACTED" else ""
        val fragmentPart = if (uri.rawFragment != null) "#$REDACTED" else ""
        return "$scheme://$host$portPart$safePath$queryPart$fragmentPart"
    }

    /**
     * Redacts secrets from a free-form message (console message, WebView error description). This
     * is still only ever shown in debug builds.
     */
    fun redactText(raw: String?): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return ""
        val withoutAssignments = SECRET_ASSIGNMENT.replace(candidate) { match ->
            "${match.groupValues[1]}=$REDACTED"
        }
        return HIGH_ENTROPY_RUN.replace(withoutAssignments, REDACTED)
    }
}
