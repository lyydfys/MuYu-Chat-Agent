package com.muyuchat.core.dsh.runtime.auth

import java.net.URI

/**
 * One authenticated Web entry printed by the official `dsh --profile web` boot.
 *
 * [authenticatedUrl] carries the single-use-in-process launch token consumed by the official
 * launch-token -> HttpOnly cookie flow. It must be kept in memory only: never logged,
 * persisted, put in an Intent/Bundle-to-disk or shared with another origin. [origin] is the
 * loopback origin without the token and is safe for diagnostics.
 */
data class DshLaunchEntry(
    val authenticatedUrl: String,
    val origin: String,
    val port: Int
) {
    override fun toString(): String = "DshLaunchEntry(origin=$origin, token=<redacted>)"
}

/** Parses and redacts the official readiness line `dsh web: http://127.0.0.1:<port>/?token=...`. */
object DshAuthEntryParser {

    private const val PREFIX = "dsh web:"
    private const val TOKEN_QUERY = "token"
    private const val MIN_TOKEN_LENGTH = 16

    /**
     * Extracts the authenticated entry from one output line, or null when the line is not the
     * official readiness line. Strict about the loopback shape so a stray URL in tool output
     * can never be mistaken for the host entry.
     */
    fun parseLine(line: String): DshLaunchEntry? {
        val at = line.indexOf(PREFIX)
        if (at < 0) return null
        val text = line.substring(at + PREFIX.length).trim()
        if (!text.startsWith("http://")) return null
        val url = text.substringBefore(' ').substringBefore('\t')
        return parseUrl(url)
    }

    /** Parses a strict loopback authenticated URL. */
    fun parseUrl(url: String): DshLaunchEntry? {
        val uri = try {
            URI(url)
        } catch (t: Throwable) {
            return null
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) return null
        val host = uri.host ?: return null
        if (!isLoopback(host)) return null
        val port = uri.port
        if (port !in 1..65535) return null
        val query = uri.rawQuery ?: return null
        val token = tokenValue(query) ?: return null
        if (token.length < MIN_TOKEN_LENGTH) return null
        if (query.split('&').count { it.substringBefore('=') == TOKEN_QUERY } != 1) return null
        // The official entry is the root path (possibly empty) with exactly the token query.
        val path = uri.rawPath ?: ""
        if (path != "" && path != "/") return null
        val origin = "http://$host:$port"
        return DshLaunchEntry(authenticatedUrl = url, origin = origin, port = port)
    }

    private fun tokenValue(query: String): String? {
        for (segment in query.split('&')) {
            val name = segment.substringBefore('=')
            if (name == TOKEN_QUERY) {
                val value = segment.substringAfter('=', "")
                return value.ifEmpty { null }
            }
        }
        return null
    }

    private fun isLoopback(host: String): Boolean = when (host.lowercase()) {
        "127.0.0.1", "::1", "[::1]", "localhost" -> true
        else -> false
    }

    /** Safe form of an output line for logs/ring buffers: any token query value is redacted. */
    fun redact(line: String): String =
        line.replace(TOKEN_PATTERN, "$1<redacted>")

    private val TOKEN_PATTERN = Regex("([?&]token=)[^&\\s\"']+")
}
