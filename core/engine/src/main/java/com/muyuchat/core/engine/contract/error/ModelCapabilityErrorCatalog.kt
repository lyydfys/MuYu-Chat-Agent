package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability

/**
 * Error catalog definitions for model storage, integrity, and capability domain failures.
 */
object ModelCapabilityErrorCatalog {
    val entries: List<ErrorCatalogEntry> = listOf(
        // Capability codes
        ErrorCatalogEntry(
            code = StableErrorCode.CAP_UNSUPPORTED_OPERATION,
            category = ErrorCategory.Capability,
            phase = ErrorPhase.Validate,
            retryability = Retryability.Never,
            userMessageKey = "error.capability.unsupported_operation",
            publicMessage = "The requested operation is not supported by this engine.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.CAP_BACKEND_UNAVAILABLE,
            category = ErrorCategory.Capability,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.capability.backend_unavailable",
            publicMessage = "The requested acceleration backend is not available.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.CAP_PRECISION_UNSUPPORTED,
            category = ErrorCategory.Capability,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.Never,
            userMessageKey = "error.capability.precision_unsupported",
            publicMessage = "The specified model quantization or precision format is not supported.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.CAP_DEVICE_FALLBACK_REQUIRED,
            category = ErrorCategory.Capability,
            phase = ErrorPhase.Prepare,
            retryability = Retryability.SafeRetry,
            userMessageKey = "error.capability.device_fallback_required",
            publicMessage = "Acceleration backend failed; fallback to generic path is required.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),

        // Model codes
        ErrorCatalogEntry(
            code = StableErrorCode.MDL_NOT_FOUND,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Load,
            retryability = Retryability.UserAction,
            userMessageKey = "error.model.not_found",
            publicMessage = "The requested model file or directory was not found.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.MDL_CHECKSUM_MISMATCH,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.model.checksum_mismatch",
            publicMessage = "The model file checksum did not match the expected value.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.MDL_LOAD_FAILED,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Load,
            retryability = Retryability.RetryAfterRecovery,
            userMessageKey = "error.model.load_failed",
            publicMessage = "Failed to parse or load the model into memory.",
            allowExposeNativeCode = true,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.MDL_CORRUPT_BUNDLE,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.model.corrupt_bundle",
            publicMessage = "The model bundle metadata or weight files are corrupt.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.MDL_MISSING_REQUIRED_BINARY,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Load,
            retryability = Retryability.UserAction,
            userMessageKey = "error.model.missing_required_binary",
            publicMessage = "A required binary or tokenizer file is missing from the bundle.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.MDL_INCOMPATIBLE_ARCHITECTURE,
            category = ErrorCategory.Model,
            phase = ErrorPhase.Load,
            retryability = Retryability.Never,
            userMessageKey = "error.model.incompatible_architecture",
            publicMessage = "The model architecture is incompatible with the loaded engine.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        )
    )
}
