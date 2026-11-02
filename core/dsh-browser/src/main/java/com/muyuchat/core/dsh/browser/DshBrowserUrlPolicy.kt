package com.muyuchat.core.dsh.browser

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode

/** Why a navigation target was refused. [detail] is a fixed sentence, never page content. */
enum class DshBrowserBlockReason(val detail: String) {
    EMPTY("navigation target is empty"),
    TOO_LONG("navigation target is too long"),
    SCHEME("only http/https navigation is allowed"),
    ABOUT_BLANK("about:blank is reserved for internal blank pages"),
    USERINFO("URLs carrying userinfo (@) are refused"),
    BACKSLASH("URLs containing a backslash are refused (parser differentials)"),
    CONTROL_CHARACTER("URLs containing control characters or spaces are refused"),
    AUTHORITY("URL has no usable host"),
    BAD_PORT("URL port is invalid"),
    NON_ASCII_HOST("URL host must be ASCII/punycode"),
    LOOPBACK("loopback hosts are refused: the DSH engine and the App services live there"),
    PRIVATE_ADDRESS("private, link-local and reserved addresses are refused"),
    LOCAL_NAME("single-label or local-network names are refused"),
    AMBIGUOUS_IP("ambiguous numeric host encodings are refused")
}

/** Result of one URL policy evaluation. Closed: only [Allow] may be loaded. */
sealed interface DshBrowserUrlDecision {
    data class Allow(val url: String) : DshBrowserUrlDecision

    data class Block(val reason: DshBrowserBlockReason) : DshBrowserUrlDecision

    /** Contract failure projection for the session/route layers. */
    fun failureOrNull(): DshBrowserFailure? = when (this) {
        is Allow -> null
        is Block -> browserFailure(
            reason = DshBrowserFailureReason.BLOCKED_URL,
            message = "Navigation refused by the browser URL policy",
            hint = reason.detail
        )
    }

    /** Capability error code when blocked; null when allowed. */
    fun capabilityErrorCode(): DshCapabilityErrorCode? =
        if (this is Block) DshCapabilityErrorCode.INVALID_REQUEST else null
}

/**
 * Navigation admission for the isolated AI browser.
 *
 * The pinned reference accepts `http(s)`/`about:blank` and rejects literal loopback hosts. MCA
 * keeps that behaviour and closes the parser gaps the reference notes in its own source: IPv4
 * shorthand/integer/hex forms, bracketed IPv6 loopback/ULA/link-local, trailing-dot
 * canonicalisation, userinfo, backslash forms, single-label and `.local`/`.lan` names.
 *
 * Honest limit (reported as a gap, not hidden): this is a *string* policy. A hostname that only
 * resolves to a loopback/private address after DNS lookup (DNS rebinding) cannot be classified
 * here; the WebView adapter therefore must re-apply this policy to every `shouldOverrideUrlLoading`
 * URL *and* the engine reports the final post-redirect URL, which the session re-validates.
 */
object DshBrowserUrlPolicy {

    private val HTTP_SCHEMES = setOf("http", "https")

    private val PRIVATE_SUFFIXES = listOf(
        ".local", ".lan", ".internal", ".home", ".intranet", ".corp", ".localhost"
    )

    /** Evaluates one URL for navigation. [allowInternalBlank] is true only for engine-owned pages. */
    fun evaluate(raw: String?, allowInternalBlank: Boolean = false): DshBrowserUrlDecision {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.EMPTY)
        if (text.length > DshBrowserLimits.MAX_URL_CHARS) {
            return DshBrowserUrlDecision.Block(DshBrowserBlockReason.TOO_LONG)
        }
        if (text.any { it.code <= 0x20 || it.code == 0x7F }) {
            return DshBrowserUrlDecision.Block(DshBrowserBlockReason.CONTROL_CHARACTER)
        }
        if (text.contains('\\')) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.BACKSLASH)

        val schemeEnd = text.indexOf("://")
        val scheme = if (schemeEnd > 0) text.substring(0, schemeEnd).lowercase() else ""
        if (schemeEnd <= 0) {
            val lower = text.lowercase()
            if (lower == "about:blank" || lower.startsWith("about:blank?")) {
                return if (allowInternalBlank) {
                    DshBrowserUrlDecision.Allow("about:blank")
                } else {
                    DshBrowserUrlDecision.Block(DshBrowserBlockReason.ABOUT_BLANK)
                }
            }
            return DshBrowserUrlDecision.Block(DshBrowserBlockReason.SCHEME)
        }
        if (scheme !in HTTP_SCHEMES) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.SCHEME)

        val rest = text.substring(schemeEnd + 3)
        if (rest.isEmpty()) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.AUTHORITY)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, authorityEnd)
        if (authority.isEmpty()) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.AUTHORITY)
        if (authority.contains('@')) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.USERINFO)

        val hostAndPort = splitHostAndPort(authority)
            ?: return DshBrowserUrlDecision.Block(DshBrowserBlockReason.AUTHORITY)
        val host = canonicalHost(hostAndPort.first)
        if (host.isEmpty()) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.AUTHORITY)
        if (hostAndPort.second != null) {
            val port = hostAndPort.second!!
            if (port < 1 || port > 65_535) return DshBrowserUrlDecision.Block(DshBrowserBlockReason.BAD_PORT)
        }
        if (host.any { it.code > 0x7E }) {
            return DshBrowserUrlDecision.Block(DshBrowserBlockReason.NON_ASCII_HOST)
        }

        classifyHost(host)?.let { return DshBrowserUrlDecision.Block(it) }
        return DshBrowserUrlDecision.Allow("$scheme://$authority${rest.substring(authorityEnd)}")
    }

    /** Re-validates a redirect target. Same admission in both directions; no silent upgrade path. */
    fun evaluateRedirect(target: String?): DshBrowserUrlDecision = evaluate(target, allowInternalBlank = false)

    /** True when [url] is the engine-owned blank page (never reachable from the model). */
    fun isInternalBlank(url: String?): Boolean = url?.trim()?.lowercase()?.let {
        it == "about:blank" || it.startsWith("about:blank?") || it.startsWith("about:blank#")
    } == true

    private fun splitHostAndPort(authority: String): Pair<String, Int?>? {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close <= 1) return null
            val host = authority.substring(1, close)
            val remainder = authority.substring(close + 1)
            if (remainder.isEmpty()) return Pair(host, null)
            if (!remainder.startsWith(":")) return null
            val port = remainder.substring(1).toIntOrNull() ?: return null
            return Pair(host, port)
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return Pair(authority, null)
        if (authority.indexOf(':') != colon) return null // bare IPv6 literal without brackets
        val host = authority.substring(0, colon)
        val portText = authority.substring(colon + 1)
        if (portText.isEmpty()) return Pair(host, null)
        if (portText.length > 5 || portText.any { it !in '0'..'9' }) return null
        return Pair(host, portText.toInt())
    }

    private fun canonicalHost(raw: String): String =
        raw.trim().lowercase().trimEnd('.')

    private fun classifyHost(host: String): DshBrowserBlockReason? {
        if (host.isEmpty()) return DshBrowserBlockReason.AUTHORITY
        if (host == "localhost" || host.endsWith(".localhost")) return DshBrowserBlockReason.LOOPBACK
        if (host.contains(':')) return classifyIpv6(host)
        if (isIpv4Literal(host)) return classifyIpv4(host)
        ambiguousIpReason(host)?.let { return it }
        if (PRIVATE_SUFFIXES.any { host.endsWith(it) }) return DshBrowserBlockReason.LOCAL_NAME
        if (!host.contains('.')) return DshBrowserBlockReason.LOCAL_NAME
        if (host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '.' }) {
            return DshBrowserBlockReason.NON_ASCII_HOST
        }
        if (host.startsWith('-') || host.endsWith('-')) return DshBrowserBlockReason.AUTHORITY
        return null
    }

    private fun classifyIpv4(host: String): DshBrowserBlockReason? {
        val parsed = host.split('.').map { parseOctet(it) }
        if (parsed.size != 4 || parsed.any { it == null || it !in 0..255 }) {
            return DshBrowserBlockReason.AMBIGUOUS_IP
        }
        val parts = parsed.map { it!! }
        val first = parts[0]
        val second = parts[1]
        return when {
            first == 0 -> DshBrowserBlockReason.PRIVATE_ADDRESS // 0.0.0.0/8 "this network"
            first == 10 -> DshBrowserBlockReason.PRIVATE_ADDRESS
            first == 127 -> DshBrowserBlockReason.LOOPBACK
            first == 169 && second == 254 -> DshBrowserBlockReason.PRIVATE_ADDRESS // link-local
            first == 172 && second in 16..31 -> DshBrowserBlockReason.PRIVATE_ADDRESS
            first == 192 && second == 168 -> DshBrowserBlockReason.PRIVATE_ADDRESS
            first == 100 && second in 64..127 -> DshBrowserBlockReason.PRIVATE_ADDRESS // CGNAT
            first == 198 && second in 18..19 -> DshBrowserBlockReason.PRIVATE_ADDRESS // benchmarking
            first == 192 && second == 0 && parts[2] == 0 -> DshBrowserBlockReason.PRIVATE_ADDRESS
            first in 224..255 -> DshBrowserBlockReason.PRIVATE_ADDRESS // multicast/reserved
            else -> null
        }
    }

    private fun isIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 4 && part.all { it in '0'..'9' } &&
                parseOctet(part)?.let { it in 0..255 } == true
        }
    }

    /**
     * `2130706433`, `0177.0.0.1`, `127.1`, `0x7f000001` are alternative spellings of an address.
     * A shorthand whose first part is 127 is still loopback; everything else numeric is ambiguous.
     */
    private fun ambiguousIpReason(host: String): DshBrowserBlockReason? {
        val parts = host.split('.')
        // `0x7f.0.0.1` mixes a hex part with decimal parts; no public host starts a label with 0x.
        if (parts.any { it.startsWith("0x") }) {
            // `0x7f.0.0.1` / `0x7f000001` are loopback written in hex.
            val hexValue = parts.first().removePrefix("0x").toLongOrNull(16)
                ?: return DshBrowserBlockReason.AMBIGUOUS_IP
            if (parts.size == 1) {
                val decoded = decodeIpv4Number(hexValue) ?: return DshBrowserBlockReason.AMBIGUOUS_IP
                return classifyIpv4(decoded)
            }
            return if (hexValue == 127L) {
                DshBrowserBlockReason.LOOPBACK
            } else {
                DshBrowserBlockReason.AMBIGUOUS_IP
            }
        }
        val stripped = host.replace(".", "")
        if (stripped.isEmpty() || stripped.any { it !in '0'..'9' }) return null
        if (parts.size > 1) {
            val first = parseOctet(parts.first()) ?: return DshBrowserBlockReason.AMBIGUOUS_IP
            return when (first) {
                127 -> DshBrowserBlockReason.LOOPBACK
                0, 10 -> DshBrowserBlockReason.PRIVATE_ADDRESS
                else -> DshBrowserBlockReason.AMBIGUOUS_IP
            }
        }
        // A single 32-bit number is a full IPv4 address (`2130706433` is 127.0.0.1).
        val value = stripped.toLongOrNull() ?: return DshBrowserBlockReason.AMBIGUOUS_IP
        val decoded = decodeIpv4Number(value) ?: return DshBrowserBlockReason.AMBIGUOUS_IP
        return classifyIpv4(decoded)
    }

    /** Leading-zero labels are octal in the legacy inet parser (`0177` is 127). */
    private fun parseOctet(part: String): Int? {
        if (part.length > 1 && part.startsWith("0") && part.all { it in '0'..'7' }) {
            return part.toIntOrNull(8)
        }
        return part.toIntOrNull()
    }

    /** `2130706433` / `0x7f000001` decode to a dotted IPv4 address, or null when out of range. */
    private fun decodeIpv4Number(value: Long): String? {
        if (value !in 0L..0xFFFF_FFFFL) return null
        return listOf(
            (value shr 24) and 0xFF,
            (value shr 16) and 0xFF,
            (value shr 8) and 0xFF,
            value and 0xFF
        ).joinToString(".")
    }

    private fun classifyIpv6(raw: String): DshBrowserBlockReason? {
        if (raw.contains('%')) return DshBrowserBlockReason.PRIVATE_ADDRESS // zone id
        val host = raw.lowercase()
        if (host.any { it !in '0'..'9' && it !in 'a'..'f' && it != ':' && it != '.' }) {
            return DshBrowserBlockReason.AUTHORITY
        }
        if (host == "::1" || host == "::" || host == "0:0:0:0:0:0:0:1" || host == "0:0:0:0:0:0:0:0") {
            return DshBrowserBlockReason.LOOPBACK
        }
        if (host.startsWith("::ffff:")) {
            val tail = host.removePrefix("::ffff:")
            return if (isIpv4Literal(tail)) classifyIpv4(tail) else DshBrowserBlockReason.AMBIGUOUS_IP
        }
        if (host.startsWith("fe80") || host.startsWith("fec0")) return DshBrowserBlockReason.PRIVATE_ADDRESS
        if (host.startsWith("fc") || host.startsWith("fd")) return DshBrowserBlockReason.PRIVATE_ADDRESS
        if (host.startsWith("ff")) return DshBrowserBlockReason.PRIVATE_ADDRESS // multicast
        return null
    }
}
