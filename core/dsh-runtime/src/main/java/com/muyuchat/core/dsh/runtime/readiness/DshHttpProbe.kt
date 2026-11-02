package com.muyuchat.core.dsh.runtime.readiness

/** One bounded HTTP response from the loopback DSH server. */
data class DshHttpResponse(
    val code: Int,
    val body: String,
    val contentType: String?,
    val headers: Map<String, List<String>>,
    val failure: String? = null
) {
    fun header(name: String): String? {
        for ((key, values) in headers) {
            if (key.equals(name, ignoreCase = true)) return values.joinToString(",")
        }
        return null
    }

    val setCookie: String? get() = header("Set-Cookie")
}

/** Loopback-only HTTP probe seam; the readiness gate is testable without a network. */
fun interface DshHttpProbe {
    fun get(url: String, cookie: String?): DshHttpResponse
}

/**
 * Small set-cookie reader for the official `dsh-auth-<sha256(authority)>=<value>; ...` header.
 * Returns only the `name=value` pair needed for the next request; attributes stay server-side.
 */
object DshSetCookie {
    fun firstCookie(setCookieHeader: String?): String? {
        if (setCookieHeader.isNullOrBlank()) return null
        for (part in setCookieHeader.split(',')) {
            val candidate = part.trim().substringBefore(';').trim()
            if (candidate.startsWith("dsh-auth-") && candidate.contains('=')) return candidate
        }
        return null
    }

    fun hasAttribute(setCookieHeader: String?, attribute: String): Boolean =
        setCookieHeader?.split(';')?.any { it.trim().equals(attribute, ignoreCase = true) } == true
}
