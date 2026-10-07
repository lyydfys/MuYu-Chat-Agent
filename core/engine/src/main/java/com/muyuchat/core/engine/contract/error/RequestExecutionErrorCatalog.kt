package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability

/**
 * Error catalog definitions for request lifecycle, cancellation, and timeout failures.
 */
object RequestExecutionErrorCatalog {
    val entries: List<ErrorCatalogEntry> = listOf(
        // Request codes
        ErrorCatalogEntry(
            code = StableErrorCode.REQ_INVALID_STATE,
            category = ErrorCategory.Request,
            phase = ErrorPhase.Execute,
            retryability = Retryability.Never,
            userMessageKey = "error.request.invalid_state",
            publicMessage = "The request is in an invalid state for the requested operation.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.REQ_DUPLICATE_TERMINAL,
            category = ErrorCategory.Request,
            phase = ErrorPhase.Callback,
            retryability = Retryability.Never,
            userMessageKey = "error.request.duplicate_terminal",
            publicMessage = "A duplicate terminal event was received for an already terminated request.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = false,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.REQ_STALE_CALLBACK,
            category = ErrorCategory.Request,
            phase = ErrorPhase.Callback,
            retryability = Retryability.Never,
            userMessageKey = "error.request.stale_callback",
            publicMessage = "A callback event was rejected because its epoch or generation is stale.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = false,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.REQ_REJECTED,
            category = ErrorCategory.Request,
            phase = ErrorPhase.Validate,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.request.rejected",
            publicMessage = "The request was rejected by the engine queue policy.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.REQ_NOT_FOUND,
            category = ErrorCategory.Request,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.request.not_found",
            publicMessage = "The specified request ID was not found.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),

        // Cancel codes
        ErrorCatalogEntry(
            code = StableErrorCode.CNC_USER_CANCELLED,
            category = ErrorCategory.Cancel,
            phase = ErrorPhase.Execute,
            retryability = Retryability.Never,
            userMessageKey = "error.cancel.user_cancelled",
            publicMessage = "Execution was cancelled by the user.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.CNC_DRAIN_TIMEOUT,
            category = ErrorCategory.Cancel,
            phase = ErrorPhase.Shutdown,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.cancel.drain_timeout",
            publicMessage = "Timed out waiting for native callbacks to drain during cancellation.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.CNC_ALREADY_CANCELLED,
            category = ErrorCategory.Cancel,
            phase = ErrorPhase.Execute,
            retryability = Retryability.Never,
            userMessageKey = "error.cancel.already_cancelled",
            publicMessage = "The request has already been cancelled.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),

        // Timeout codes
        ErrorCatalogEntry(
            code = StableErrorCode.TIM_LOAD_TIMEOUT,
            category = ErrorCategory.Timeout,
            phase = ErrorPhase.Load,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.timeout.load_timeout",
            publicMessage = "Model loading timed out before completing.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.TIM_EXECUTION_TIMEOUT,
            category = ErrorCategory.Timeout,
            phase = ErrorPhase.Execute,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.timeout.execution_timeout",
            publicMessage = "Inference execution timed out before generating output.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.TIM_DRAIN_TIMEOUT,
            category = ErrorCategory.Timeout,
            phase = ErrorPhase.Shutdown,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.timeout.drain_timeout",
            publicMessage = "Draining intermediate buffers timed out.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.TIM_HEARTBEAT_EXPIRED,
            category = ErrorCategory.Timeout,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.timeout.heartbeat_expired",
            publicMessage = "Native worker heartbeat expired without progress.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        )
    )
}
