package com.muyuchat.core.engine.runtime

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability
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
        cause: Throwable? = null
    ): ErrorEnvelope {
        val safeDetail = detailMessage?.take(512) ?: (cause?.message ?: "Native error code: $nativeCode")
        val chain = listOf(safeDetail)

        return when (nativeCode) {
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
    }

    fun fromThrowable(throwable: Throwable, phase: ErrorPhase = ErrorPhase.Execute): ErrorEnvelope {
        val msg = throwable.message ?: throwable.javaClass.simpleName
        val chain = listOf(msg)
        return when {
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
    }
}
