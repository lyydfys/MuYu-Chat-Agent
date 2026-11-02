package com.muyuchat.feature.dsh.workspace

/**
 * Pure construction of cookie-expiry directives for one DSH origin.
 *
 * The DSH runtime mints a first-party HttpOnly cookie for `http://127.0.0.1:<port>`. When the
 * runtime generation changes the port changes too, so the old cookie is already unusable; expiring
 * it explicitly keeps the WebView cookie jar from accumulating stale auth state. Only cookies for
 * the DSH loopback origin are touched; unrelated WebView cookies (the chat internal browser) are
 * never cleared by this feature.
 */
object DshCookiePurge {

    private val VALID_COOKIE_NAME = Regex("[A-Za-z0-9_\\-.%]{1,128}")
    private const val EXPIRED = "Thu, 01 Jan 1970 00:00:00 GMT"

    /** Turns `CookieManager.getCookie(origin)` output (`a=1; b=2`) into expiry Set-Cookie values. */
    fun expiryHeaders(cookieHeader: String?): List<String> {
        val header = cookieHeader?.trim().orEmpty()
        if (header.isEmpty()) return emptyList()
        return header
            .split(';')
            .mapNotNull { segment ->
                val name = segment.substringBefore('=', "").trim()
                if (!VALID_COOKIE_NAME.matches(name)) return@mapNotNull null
                "$name=; Max-Age=0; Path=/; Expires=$EXPIRED"
            }
            .distinct()
    }

    /** Cookie names only, useful for diagnostics that must never contain values. */
    fun cookieNames(cookieHeader: String?): List<String> =
        cookieHeader
            ?.split(';')
            ?.map { it.substringBefore('=', "").trim() }
            ?.filter { VALID_COOKIE_NAME.matches(it) }
            ?.distinct()
            .orEmpty()
}
