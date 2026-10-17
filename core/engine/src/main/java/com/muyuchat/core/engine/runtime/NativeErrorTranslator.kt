package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.RequestIdentity
import com.muyuchat.core.engine.contract.Retryability
import com.muyuchat.core.engine.contract.error.ErrorCatalogRegistry
import com.muyuchat.core.engine.contract.error.StableErrorCode

/**
 * Translates native and runtime errors into typed, normalized ErrorEnvelope objects.
 * Strict invariant: Never silent, never leaks raw sensitive memory pointers, deterministic categorization.
 */
object NativeErrorTranslator {

    fun translate(
        nativeCode: Int,
        phase: ErrorPhase = ErrorPhase.Execute,
        detailMessage: String? = null,
        cause: Throwable? = null,
        identity: RequestIdentity? = null,
        traceId: String = identity?.traceId.orEmpty()
    ): ErrorEnvelope {
        val safeDetail = detailMessage?.take(512) ?: (cause?.message ?: "Native error code: $nativeCode")
        val chain = listOf(safeDetail)

        val envelope = when (nativeCode) {
            0 -> ErrorEnvelope(
                code = StableErrorCode.INT_CORRUPTED_STATE,
                category = ErrorCategory.Internal,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.internal.inconsistent",
                publicMessage = "Internal state inconsistent.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            // Model errors (100-199)
            101 -> ErrorEnvelope(
                code = StableErrorCode.MDL_NOT_FOUND,
                category = ErrorCategory.Model,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.model.not_found",
                publicMessage = "Model binary not found at specified path.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            102 -> ErrorEnvelope(
                code = StableErrorCode.VAL_INVALID_FORMAT,
                category = ErrorCategory.Model,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.model.unsupported_format",
                publicMessage = "Model package format is invalid or unsupported.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            103 -> ErrorEnvelope(
                code = StableErrorCode.MDL_CORRUPT_BUNDLE,
                category = ErrorCategory.Model,
                phase = phase,
                retryability = Retryability.UserAction,
                userMessageKey = "error.model.corrupt",
                publicMessage = "Model package is incomplete or corrupted.",
                causeChain = chain,
                nativeCode = nativeCode
            )

            // Runtime and Native init errors (200-299)
            201 -> ErrorEnvelope(
                code = StableErrorCode.CAP_BACKEND_UNAVAILABLE,
                category = ErrorCategory.Runtime,
                phase = phase,
                retryability = Retryability.RetryAfterRecovery,
                userMessageKey = "error.runtime.not_ready",
                publicMessage = "Inference runtime is not initialized.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            202 -> ErrorEnvelope(
                code = StableErrorCode.NAT_DSO_LOAD_FAILED,
                category = ErrorCategory.Native,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.native.library_missing",
                publicMessage = "Required native shared library is missing.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            203 -> ErrorEnvelope(
                code = StableErrorCode.RT_INITIALIZATION_FAILED,
                category = ErrorCategory.Native,
                phase = phase,
                retryability = Retryability.RetryAfterRecovery,
                userMessageKey = "error.native.init_failed",
                publicMessage = "Native backend initialization failed.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            204 -> ErrorEnvelope(
                code = StableErrorCode.NAT_ABORTED,
                category = ErrorCategory.Native,
                phase = phase,
                retryability = Retryability.SafeRetry,
                userMessageKey = "error.native.execution_aborted",
                publicMessage = "Native execution terminated unexpectedly.",
                causeChain = chain,
                nativeCode = nativeCode
            )

            // Memory and Resource limits (300-399)
            301 -> ErrorEnvelope(
                code = StableErrorCode.RT_OUT_OF_MEMORY,
                category = ErrorCategory.Native,
                phase = phase,
                retryability = Retryability.RetryAfterRecovery,
                userMessageKey = "error.native.oom",
                publicMessage = "Device memory exhausted during execution.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            302 -> ErrorEnvelope(
                code = StableErrorCode.VAL_INVALID_INPUT,
                category = ErrorCategory.Request,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.request.context_exceeded",
                publicMessage = "Input sequence exceeds the model context window.",
                causeChain = chain,
                nativeCode = nativeCode
            )

            // Cancellation and Timeout (400-499)
            401 -> ErrorEnvelope(
                code = StableErrorCode.CNC_USER_CANCELLED,
                category = ErrorCategory.Cancel,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.request.cancelled",
                publicMessage = "Request was cancelled.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            402 -> ErrorEnvelope(
                code = StableErrorCode.TIM_EXECUTION_TIMEOUT,
                category = ErrorCategory.Timeout,
                phase = phase,
                retryability = Retryability.SafeRetry,
                userMessageKey = "error.request.timeout",
                publicMessage = "Execution timed out waiting for backend completion.",
                causeChain = chain,
                nativeCode = nativeCode
            )

            // Storage and I/O (500-599)
            501 -> ErrorEnvelope(
                code = StableErrorCode.STR_WRITE_ERROR,
                category = ErrorCategory.Storage,
                phase = phase,
                retryability = Retryability.UserAction,
                userMessageKey = "error.storage.write_failed",
                publicMessage = "Failed to write output artifact to disk.",
                causeChain = chain,
                nativeCode = nativeCode
            )
            502 -> ErrorEnvelope(
                code = StableErrorCode.STR_DISK_FULL,
                category = ErrorCategory.Storage,
                phase = phase,
                retryability = Retryability.UserAction,
                userMessageKey = "error.storage.disk_full",
                publicMessage = "Insufficient storage space.",
                causeChain = chain,
                nativeCode = nativeCode
            )

            // Fallback
            else -> ErrorEnvelope(
                code = StableErrorCode.INT_UNEXPECTED_EXCEPTION,
                category = ErrorCategory.Internal,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.internal.unexpected",
                publicMessage = "Unexpected native error occurred.",
                causeChain = chain,
                nativeCode = nativeCode
            )
        }
        return attachContext(envelope, identity, traceId)
    }

    fun fromThrowable(
        throwable: Throwable,
        phase: ErrorPhase = ErrorPhase.Execute,
        identity: RequestIdentity? = null,
        traceId: String = identity?.traceId.orEmpty()
    ): ErrorEnvelope {
        val msg = throwable.message ?: throwable.javaClass.simpleName
        val chain = listOf(msg)
        val envelope = when {
            throwable is java.io.FileNotFoundException -> ErrorEnvelope(
                code = StableErrorCode.MDL_NOT_FOUND,
                category = ErrorCategory.Model,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.model.not_found",
                publicMessage = "Required file not found.",
                causeChain = chain
            )
            throwable is OutOfMemoryError -> ErrorEnvelope(
                code = StableErrorCode.RT_OUT_OF_MEMORY,
                category = ErrorCategory.Native,
                phase = phase,
                retryability = Retryability.RetryAfterRecovery,
                userMessageKey = "error.native.oom",
                publicMessage = "Out of memory.",
                causeChain = chain
            )
            throwable is java.util.concurrent.TimeoutException -> ErrorEnvelope(
                code = StableErrorCode.TIM_EXECUTION_TIMEOUT,
                category = ErrorCategory.Timeout,
                phase = phase,
                retryability = Retryability.SafeRetry,
                userMessageKey = "error.request.timeout",
                publicMessage = "Operation timed out.",
                causeChain = chain
            )
            else -> ErrorEnvelope(
                code = StableErrorCode.INT_UNEXPECTED_EXCEPTION,
                category = ErrorCategory.Internal,
                phase = phase,
                retryability = Retryability.Never,
                userMessageKey = "error.internal.unexpected",
                publicMessage = "Unexpected exception [${throwable.javaClass.name}]: $msg",
                causeChain = chain
            )
        }
        return attachContext(envelope, identity, traceId)
    }

    /**
     * Translates the legacy `GenerateEvent.Error` message/code pair into the same typed envelope the
     * newer event field can carry. Known stable codes use the shared catalog; the historical
     * cancellation string `REQUEST_CANCELLED` maps to [StableErrorCode.CNC_USER_CANCELLED].
     */
    fun fromChatError(
        message: String,
        code: String?,
        identity: RequestIdentity? = null,
        phase: ErrorPhase? = null,
        traceId: String = identity?.traceId.orEmpty()
    ): ErrorEnvelope {
        val publicMessage = message.trim().take(512).ifBlank { "Chat execution failed." }
        val normalizedCode = code?.trim()?.takeIf { it.isNotEmpty() }
        val codeText = normalizedCode.orEmpty()
        val stableCode = normalizedCode?.let { raw ->
            StableErrorCode.entries.firstOrNull { candidate -> candidate.name.equals(raw, ignoreCase = true) }
        }
        val numericCode = normalizedCode?.toIntOrNull()
        val envelope = when {
            stableCode != null -> catalogEnvelope(
                code = stableCode,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            numericCode != null -> translate(
                nativeCode = numericCode,
                phase = phase ?: ErrorPhase.Execute,
                detailMessage = publicMessage
            )
            normalizedCode.isCancellationCode() -> catalogEnvelope(
                code = StableErrorCode.CNC_USER_CANCELLED,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            normalizedCode.isNativeFailureCode() -> catalogEnvelope(
                code = StableErrorCode.NAT_EXECUTION_FAULT,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            codeText.contains("OOM", ignoreCase = true) ||
                codeText.contains("OUT_OF_MEMORY", ignoreCase = true) -> catalogEnvelope(
                code = StableErrorCode.RT_OUT_OF_MEMORY,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            codeText.contains("TIMEOUT", ignoreCase = true) -> catalogEnvelope(
                code = StableErrorCode.TIM_EXECUTION_TIMEOUT,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            codeText.startsWith("HTTP", ignoreCase = true) -> httpEnvelope(
                code = codeText,
                publicMessage = publicMessage,
                phase = phase
            )
            codeText.contains("MODEL", ignoreCase = true) &&
                codeText.contains("NOT_FOUND", ignoreCase = true) -> catalogEnvelope(
                code = StableErrorCode.MDL_NOT_FOUND,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            codeText.contains("MODEL", ignoreCase = true) &&
                codeText.contains("LOAD", ignoreCase = true) -> catalogEnvelope(
                code = StableErrorCode.MDL_LOAD_FAILED,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            codeText.contains("NET", ignoreCase = true) -> catalogEnvelope(
                code = StableErrorCode.NET_CONNECT_FAILED,
                publicMessage = publicMessage,
                phase = phase,
                causeChain = listOf(publicMessage)
            )
            else -> ErrorEnvelope(
                code = StableErrorCode.INT_UNEXPECTED_EXCEPTION,
                category = ErrorCategory.Internal,
                phase = phase ?: ErrorPhase.Execute,
                retryability = Retryability.Never,
                userMessageKey = "error.internal.unexpected",
                publicMessage = publicMessage,
                causeChain = buildList {
                    add(publicMessage)
                    normalizedCode?.let { add("sourceCode=$it") }
                }
            )
        }
        return attachContext(envelope, identity, traceId)
    }

    /** Builds the terminal envelope used when the owner settles a cancelled execution. */
    fun cancelled(
        identity: RequestIdentity? = null,
        reason: String = "Request was cancelled.",
        traceId: String = identity?.traceId.orEmpty()
    ): ErrorEnvelope = attachContext(
        catalogEnvelope(
            code = StableErrorCode.CNC_USER_CANCELLED,
            publicMessage = "Request was cancelled.",
            phase = ErrorPhase.Execute,
            causeChain = listOf(reason.trim().take(512).ifBlank { "Request was cancelled." }),
            userMessageKey = "error.request.cancelled"
        ),
        identity,
        traceId
    )

    /**
     * Binds an already typed event failure to the admitted request attempt. The explicit trace ID
     * on the event wins; otherwise the admitted identity/context trace is used. A null trace remains
     * null-equivalent (empty) for source compatibility with callers that do not use tracing.
     */
    fun attachContext(
        error: ErrorEnvelope,
        identity: RequestIdentity?,
        traceId: String = identity?.traceId.orEmpty()
    ): ErrorEnvelope {
        val resolvedIdentity = identity ?: error.identity
        val resolvedTrace = sequenceOf(error.traceId, traceId, resolvedIdentity?.traceId)
            .map { it.orEmpty().trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (resolvedIdentity == error.identity && resolvedTrace == error.traceId) return error
        return error.copy(identity = resolvedIdentity, traceId = resolvedTrace)
    }

    private fun catalogEnvelope(
        code: StableErrorCode,
        publicMessage: String,
        phase: ErrorPhase?,
        causeChain: List<String>,
        userMessageKey: String? = null
    ): ErrorEnvelope {
        val catalog = ErrorCatalogRegistry.get(code)
        return ErrorEnvelope(
            code = code,
            category = catalog.category,
            phase = phase ?: catalog.phase,
            retryability = catalog.retryability,
            userMessageKey = userMessageKey ?: catalog.userMessageKey,
            publicMessage = publicMessage,
            causeChain = causeChain
        )
    }

    private fun httpEnvelope(code: String, publicMessage: String, phase: ErrorPhase?): ErrorEnvelope {
        val httpCode = HTTP_CODE_PATTERN.matchEntire(code)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val stableCode = when (httpCode) {
            401 -> StableErrorCode.AUTH_INVALID_KEY
            403 -> StableErrorCode.AUTH_FORBIDDEN
            429 -> StableErrorCode.REQ_REJECTED
            else -> StableErrorCode.NET_PROTOCOL_ERROR
        }
        val catalog = ErrorCatalogRegistry.get(stableCode)
        return ErrorEnvelope(
            code = stableCode,
            category = catalog.category,
            phase = phase ?: catalog.phase,
            retryability = catalog.retryability,
            userMessageKey = catalog.userMessageKey,
            publicMessage = publicMessage,
            causeChain = listOf(publicMessage),
            httpCode = httpCode
        )
    }

    private fun String?.isCancellationCode(): Boolean {
        val value = this?.uppercase().orEmpty()
        return value == "REQUEST_CANCELLED" || value == "CANCELLED" || value == "CANCELED" ||
            value.contains("USER_CANCEL")
    }

    private fun String?.isNativeFailureCode(): Boolean {
        val value = this?.uppercase().orEmpty()
        return value == "NATIVE_FAILURE" || value == "NATIVE_ERROR" ||
            value == "NATIVE_EXECUTION_FAILURE"
    }

    private val HTTP_CODE_PATTERN = Regex("""HTTP[_-]?([1-5]\d{2})""", RegexOption.IGNORE_CASE)
}
