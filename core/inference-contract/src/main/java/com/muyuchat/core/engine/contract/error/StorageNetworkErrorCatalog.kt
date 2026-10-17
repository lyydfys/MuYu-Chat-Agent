package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability

/**
 * Error catalog definitions for storage, network, authentication, and internal failures.
 */
object StorageNetworkErrorCatalog {
    val entries: List<ErrorCatalogEntry> = listOf(
        // Storage codes
        ErrorCatalogEntry(
            code = StableErrorCode.STR_READ_ERROR,
            category = ErrorCategory.Storage,
            phase = ErrorPhase.Load,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.storage.read_error",
            publicMessage = "Failed to read data from local disk storage.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.STR_WRITE_ERROR,
            category = ErrorCategory.Storage,
            phase = ErrorPhase.Persist,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.storage.write_error",
            publicMessage = "Failed to write data to local disk storage.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.STR_DISK_FULL,
            category = ErrorCategory.Storage,
            phase = ErrorPhase.Persist,
            retryability = Retryability.UserAction,
            userMessageKey = "error.storage.disk_full",
            publicMessage = "Local storage volume is full. Free up space and try again.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.STR_PERMISSION_DENIED,
            category = ErrorCategory.Storage,
            phase = ErrorPhase.Persist,
            retryability = Retryability.UserAction,
            userMessageKey = "error.storage.permission_denied",
            publicMessage = "Permission denied while accessing local storage path.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),

        // Network codes
        ErrorCatalogEntry(
            code = StableErrorCode.NET_CONNECT_FAILED,
            category = ErrorCategory.Network,
            phase = ErrorPhase.Load,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.network.connect_failed",
            publicMessage = "Failed to connect to the remote network endpoint.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.NET_TIMEOUT,
            category = ErrorCategory.Network,
            phase = ErrorPhase.Load,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.network.timeout",
            publicMessage = "Network request timed out.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.NET_PROTOCOL_ERROR,
            category = ErrorCategory.Network,
            phase = ErrorPhase.Load,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.network.protocol_error",
            publicMessage = "Received an invalid protocol response from remote server.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),

        // Auth codes
        ErrorCatalogEntry(
            code = StableErrorCode.AUTH_MISSING_KEY,
            category = ErrorCategory.Auth,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.auth.missing_key",
            publicMessage = "API key or bearer token is missing.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.AUTH_INVALID_KEY,
            category = ErrorCategory.Auth,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.auth.invalid_key",
            publicMessage = "The supplied API key or token is invalid.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.AUTH_FORBIDDEN,
            category = ErrorCategory.Auth,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.auth.forbidden",
            publicMessage = "Access to the requested resource is forbidden.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),

        // Internal codes
        ErrorCatalogEntry(
            code = StableErrorCode.INT_UNEXPECTED_EXCEPTION,
            category = ErrorCategory.Internal,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.internal.unexpected_exception",
            publicMessage = "An unexpected internal error occurred.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.INT_CORRUPTED_STATE,
            category = ErrorCategory.Internal,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.internal.corrupted_state",
            publicMessage = "Internal state was corrupted and requires restart.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        )
    )
}
