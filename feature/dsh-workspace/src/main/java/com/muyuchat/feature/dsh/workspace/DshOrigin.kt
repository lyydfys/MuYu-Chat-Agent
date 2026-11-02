package com.muyuchat.feature.dsh.workspace

import java.net.URI

/**
 * The exact origin of one DSH runtime generation.
 *
 * The contract guarantees a loopback origin of the form `http://127.0.0.1:<port>` with a random,
 * per-generation port. The WebView origin fence is deliberately stricter than "is loopback":
 *
 * - scheme must be `http` (the DSH Web profile is plain HTTP on loopback; the app never serves TLS
 *   locally),
 * - host must be exactly `127.0.0.1` (not `localhost`, not another loopback alias, not an IPv6
 *   form; a different spelling would be a different WebView origin and could bypass cookies),
 * - port must be explicit and in `1..65535`,
 * - userinfo, query, fragment and any non-root path are rejected: the value is an origin, never a
 *   launch entry.
 *
 * The authenticated entry URL (`origin/?token=...`) is never stored in this type.
 */
@JvmInline
value class DshOrigin(val value: String) {

    /** Builds an absolute same-origin URL from a path beginning with `/`. */
    fun pathUrl(path: String): String = value + path

    /** True only for an http URL with host `127.0.0.1` and this generation's exact port. */
    fun matches(url: String?): Boolean = sameOrigin(value, url)

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        /** `http://` + IPv4 + `:` + up to five digits; a longer string cannot be a valid origin. */
        private const val MAX_ORIGIN_LENGTH = 64

        /**
         * Parses the runtime snapshot origin. Returns null for anything that is not an exact
         * loopback HTTP origin, so a malformed host value can never widen the WebView origin fence.
         */
        fun parse(raw: String?): DshOrigin? {
            val candidate = raw?.trim().orEmpty()
            if (candidate.isEmpty() || candidate.length > MAX_ORIGIN_LENGTH) return null
            val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true)) return null
            if (!LOOPBACK_HOST.equals(uri.host, ignoreCase = true)) return null
            if (uri.rawUserInfo != null) return null
            if (uri.rawQuery != null || uri.rawFragment != null) return null
            val port = uri.port
            if (port !in 1..65535) return null
            val path = uri.rawPath
            if (path != null && path.isNotEmpty() && path != "/") return null
            return DshOrigin("http://$LOOPBACK_HOST:$port")
        }

        /**
         * Exact same-origin check for a candidate URL. This is the single fence used by the
         * WebViewClient and must stay conservative: any parse failure is "not same origin".
         */
        fun sameOrigin(origin: String, url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val originUri = runCatching { URI(origin) }.getOrNull() ?: return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            if (!uri.scheme.equals("http", ignoreCase = true)) return false
            if (!LOOPBACK_HOST.equals(uri.host, ignoreCase = true)) return false
            if (uri.rawUserInfo != null) return false
            return uri.port == originUri.port
        }
    }
}
