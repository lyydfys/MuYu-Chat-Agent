package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityHttpMethod
import java.security.MessageDigest

/**
 * Stable, non-reversible identity of one capability payload.
 *
 * The ledger binds a request id to this digest so a reused id with different method, path, query
 * or body is rejected instead of replaying a receipt for a different action. The raw payload (and
 * anything secret inside it) is never retained; only the 64-character SHA-256 hex digest is.
 *
 * The encoding is explicit and line-oriented so two different payloads cannot collide by simple
 * concatenation: `METHOD \n PATH \n k=v \n ... \n BODY`, query sorted by key.
 */
internal fun capabilityPayloadFingerprint(
    method: DshCapabilityHttpMethod,
    path: String,
    query: Map<String, String>,
    body: String
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(method.name.toByteArray(Charsets.UTF_8))
    digest.update(LINE_SEPARATOR)
    digest.update(path.toByteArray(Charsets.UTF_8))
    digest.update(LINE_SEPARATOR)
    query.toSortedMap().forEach { (key, value) ->
        digest.update(key.toByteArray(Charsets.UTF_8))
        digest.update(EQUALS_SEPARATOR)
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(LINE_SEPARATOR)
    }
    digest.update(body.toByteArray(Charsets.UTF_8))
    val bytes = digest.digest()
    val builder = StringBuilder(bytes.size * 2)
    bytes.forEach { byte ->
        val value = byte.toInt() and 0xFF
        builder.append(HEX_DIGITS[value ushr 4])
        builder.append(HEX_DIGITS[value and 0x0F])
    }
    return builder.toString()
}

private val LINE_SEPARATOR = byteArrayOf('\n'.code.toByte())
private val EQUALS_SEPARATOR = byteArrayOf('='.code.toByte())
private const val HEX_DIGITS = "0123456789abcdef"
