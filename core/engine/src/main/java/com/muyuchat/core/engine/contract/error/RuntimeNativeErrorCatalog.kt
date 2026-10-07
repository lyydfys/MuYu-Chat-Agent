package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability

/**
 * Error catalog definitions for runtime and native execution domain failures.
 */
object RuntimeNativeErrorCatalog {
    val entries: List<ErrorCatalogEntry> = listOf(
        // Runtime codes
        ErrorCatalogEntry(
            code = StableErrorCode.RT_OUT_OF_MEMORY,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.runtime.out_of_memory",
            publicMessage = "Insufficient system or GPU memory to complete the operation.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.RT_INITIALIZATION_FAILED,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.runtime.initialization_failed",
            publicMessage = "Failed to initialize the execution runtime backend.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.RT_CRASHED,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.runtime.crashed",
            publicMessage = "The native runtime process crashed or exited unexpectedly.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.RT_DEVICE_LOST,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.runtime.device_lost",
            publicMessage = "The hardware acceleration device was lost or reset.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.RT_UNHEALTHY,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.runtime.unhealthy",
            publicMessage = "The runtime host is in an unhealthy state and must be restarted.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.RT_CONCURRENT_LIMIT_EXCEEDED,
            category = ErrorCategory.Runtime,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.runtime.concurrent_limit_exceeded",
            publicMessage = "The maximum number of concurrent requests has been exceeded.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),

        // Native codes
        ErrorCatalogEntry(
            code = StableErrorCode.NAT_DSO_LOAD_FAILED,
            category = ErrorCategory.Native,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.Never,
            userMessageKey = "error.native.dso_load_failed",
            publicMessage = "Failed to load required native shared library.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.NAT_SYMBOL_NOT_FOUND,
            category = ErrorCategory.Native,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.Never,
            userMessageKey = "error.native.symbol_not_found",
            publicMessage = "A required native entrypoint symbol was not found.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = true
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.NAT_EXECUTION_FAULT,
            category = ErrorCategory.Native,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.native.execution_fault",
            publicMessage = "A fault occurred during native model execution.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.NAT_ABORTED,
            category = ErrorCategory.Native,
            phase = ErrorPhase.Execute,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.native.aborted",
            publicMessage = "Native execution was aborted by safety guards.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.NAT_JNI_CALL_FAILED,
            category = ErrorCategory.Native,
            phase = ErrorPhase.Execute,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.native.jni_call_failed",
            publicMessage = "A JNI boundary invocation encountered an unexpected failure.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        )
    )
}
