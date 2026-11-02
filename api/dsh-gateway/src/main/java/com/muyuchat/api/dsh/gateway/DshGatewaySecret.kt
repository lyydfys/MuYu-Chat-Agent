package com.muyuchat.api.dsh.gateway

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Per-run bearer token generation and comparison.
 *
 * The token guards a loopback-only listener. It is generated with [SecureRandom], returned only
 * through [com.muyuchat.core.dsh.contract.DshGatewayEndpoint] and never written to a log, an
 * exception message or an HTTP error body.
 */
internal object DshGatewaySecret {
    private const val TOKEN_PREFIX = "dshg_"
    private const val TOKEN_BYTES = 32

    fun newBearerToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Constant-time comparison. A blank [expected] token never matches: the gateway always holds
     * a token, so an accidental blank must fail closed.
     */
    fun matches(expected: String, presented: String?): Boolean {
        if (expected.isEmpty() || presented.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            presented.toByteArray(Charsets.UTF_8)
        )
    }

    /** Removes [secret] from [text]; used by tests and by diagnostics that must stay secret-free. */
    fun redact(text: String, secret: String): String {
        if (secret.isEmpty()) return text
        return text.replace(secret, "[redacted]")
    }
}
