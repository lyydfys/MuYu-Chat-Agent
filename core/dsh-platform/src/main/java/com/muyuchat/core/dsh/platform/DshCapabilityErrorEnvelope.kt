package com.muyuchat.core.dsh.platform

import com.muyuchat.core.dsh.contract.capability.DshCapabilityErrorCode

/**
 * One HTTP response the loopback adapter writes.
 *
 * The official DSH tool packages parse the body as JSON and read an `ok` flag, so every error
 * document carries `{"ok":false,"error":...}` and additionally the typed MCA `code` for diagnostics.
 * [extraHeaders] is only used for protocol headers such as `Allow` and `Retry-After`; values are
 * sanitised by the adapter before they reach the socket.
 */
data class DshCapabilityHttpResult(
    val statusCode: Int,
    val bodyJson: String,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    init {
        require(statusCode in 100..599) { "capability response status is out of range" }
    }
}

/**
 * Stable JSON error envelope of the App-local capability server.
 *
 * Shape: `{"ok":false,"error":"<prompt-free message>","code":"<CODE>","status":<int>}` plus
 * `retryAfterMs` when the server knows a bounded retry delay. It matches what the pinned official
 * packages already understand (`ok` / `error`) without inventing a second wire protocol.
 */
object DshCapabilityErrorEnvelope {
    /** Prompt-free message used when a handler produced something the server cannot describe. */
    const val GENERIC_INTERNAL_MESSAGE = "capability request failed"

    /** HTTP status for one typed failure. Never leaks more than the official packages need. */
    fun statusFor(code: DshCapabilityErrorCode): Int = when (code) {
        DshCapabilityErrorCode.UNAUTHORIZED -> 401
        DshCapabilityErrorCode.STALE_GENERATION -> 409
        DshCapabilityErrorCode.INVALID_REQUEST -> 400
        DshCapabilityErrorCode.USER_CANCELLED -> 409
        DshCapabilityErrorCode.USER_PRESENCE_REQUIRED -> 409
        DshCapabilityErrorCode.PERMISSION_DENIED -> 403
        DshCapabilityErrorCode.NOT_ENABLED -> 503
        DshCapabilityErrorCode.CAPABILITY_UNAVAILABLE -> 403
        DshCapabilityErrorCode.RATE_LIMITED -> 429
        DshCapabilityErrorCode.TOO_LARGE -> 413
        DshCapabilityErrorCode.TIMEOUT -> 504
        DshCapabilityErrorCode.TARGET_CHANGED -> 409
        DshCapabilityErrorCode.REQUEST_IN_PROGRESS -> 409
        DshCapabilityErrorCode.OUTCOME_UNKNOWN -> 409
        DshCapabilityErrorCode.INTERNAL -> 500
    }

    /** Builds one error document. [message] must already be prompt-free; it is JSON-escaped here. */
    fun error(
        code: DshCapabilityErrorCode,
        message: String,
        statusCode: Int = statusFor(code),
        retryAfterMillis: Long? = null
    ): DshCapabilityHttpResult {
        require(retryAfterMillis == null || retryAfterMillis >= 0L) {
            "retry delay must be non-negative"
        }
        val body = buildString {
            append("{\"ok\":false,\"error\":")
            append(quote(message))
            append(",\"code\":\"")
            append(code.name)
            append("\",\"status\":")
            append(statusCode)
            if (retryAfterMillis != null) {
                append(",\"retryAfterMs\":")
                append(retryAfterMillis)
            }
            append('}')
        }
        val headers = if (retryAfterMillis != null) {
            mapOf("Retry-After" to ((retryAfterMillis + 999L) / 1000L).coerceAtLeast(1L).toString())
        } else {
            emptyMap()
        }
        return DshCapabilityHttpResult(statusCode, body, headers)
    }

    /** Escapes one string as a JSON string literal (including the surrounding quotes). */
    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20 || character.code in 0x7F..0x9F) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

/** Minimal reason phrases for the status codes this server actually emits. */
object DshCapabilityHttpStatus {
    fun reason(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status $statusCode"
    }
}
