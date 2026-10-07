package com.muyuchat.core.engine.contract

import com.muyuchat.core.engine.contract.error.StableErrorCode

/**
 * Coarse error domain category.
 */
enum class ErrorCategory {
    Validation,
    Capability,
    Model,
    Runtime,
    Native,
    Request,
    Cancel,
    Timeout,
    Storage,
    Network,
    Auth,
    Internal
}

/**
 * Execution pipeline phase where the error occurred.
 */
enum class ErrorPhase {
    Validate,
    Load,
    Prepare,
    Execute,
    Callback,
    Persist,
    Shutdown
}

/**
 * Retryability semantics for client and orchestrator recovery policies.
 */
enum class Retryability {
    /**
     * Permanent failure; retrying with the same parameters will never succeed.
     */
    Never,

    /**
     * Transient failure; can be retried immediately or after backoff.
     */
    SafeRetry,

    /**
     * Requires subsystem re-initialization (e.g. backend restart or model reload) before retry.
     */
    RetryAfterRecovery,

    /**
     * Requires external user or operator intervention (e.g. free disk space, grant permissions, supply key).
     */
    UserAction
}

/**
 * Standardized cross-layer error envelope for MCA.
 *
 * Provides safe external error reporting while preserving diagnostic context internally.
 */
data class ErrorEnvelope(
    val code: StableErrorCode,
    val category: ErrorCategory,
    val phase: ErrorPhase,
    val retryability: Retryability,
    val userMessageKey: String,
    val publicMessage: String,
    val causeChain: List<String> = emptyList(),
    val identity: RequestIdentity? = null,
    val nativeCode: Int? = null,
    val httpCode: Int? = null,
    val osCode: Int? = null,
    val traceId: String = "",
    val timestampMs: Long = System.currentTimeMillis()
) {
    init {
        require(userMessageKey.isNotBlank()) { "userMessageKey must not be blank" }
        require(publicMessage.isNotBlank()) { "publicMessage must not be blank" }
    }

    /**
     * Returns a copy with sensitive diagnostic codes stripped for external public consumption.
     */
    fun sanitizeForPublicApi(
        allowNativeCode: Boolean = false,
        allowHttpCode: Boolean = true,
        allowOsCode: Boolean = false
    ): ErrorEnvelope = copy(
        causeChain = causeChain.map { sanitizeMessage(it) },
        nativeCode = if (allowNativeCode) nativeCode else null,
        httpCode = if (allowHttpCode) httpCode else null,
        osCode = if (allowOsCode) osCode else null
    )

    /**
     * Human-readable diagnostic summary.
     */
    fun toDiagnosticString(): String = buildString {
        append("ErrorEnvelope[code=").append(code)
        append(", category=").append(category)
        append(", phase=").append(phase)
        append(", retryability=").append(retryability)
        append(", userKey=").append(userMessageKey)
        append(", msg=").append(publicMessage)
        if (identity != null) append(", reqId=").append(identity.requestId)
        if (nativeCode != null) append(", native=").append(nativeCode)
        if (httpCode != null) append(", http=").append(httpCode)
        if (osCode != null) append(", os=").append(osCode)
        if (traceId.isNotBlank()) append(", traceId=").append(traceId)
        append("]")
    }

    companion object {
        private fun sanitizeMessage(msg: String): String {
            // Redact local path substrings or potentially sensitive tokens if present
            return msg.replace(Regex("""(?:[a-zA-Z]:\\[^\s,]+)|(?:\/(?:data|storage|emulated)[^\s,]+)"""), "<redacted-path>")
        }
    }
}
