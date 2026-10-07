package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability

/**
 * Error catalog definitions for validation domain failures.
 */
object ValidationErrorCatalog {
    val entries: List<ErrorCatalogEntry> = listOf(
        ErrorCatalogEntry(
            code = StableErrorCode.VAL_INVALID_INPUT,
            category = ErrorCategory.Validation,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.validation.invalid_input",
            publicMessage = "The provided input parameters are invalid.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.VAL_MISSING_REQUIRED_FIELD,
            category = ErrorCategory.Validation,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.validation.missing_required_field",
            publicMessage = "A required request field is missing.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.VAL_CORRUPT_PACKAGE,
            category = ErrorCategory.Validation,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.validation.corrupt_package",
            publicMessage = "The package archive is corrupted or incomplete.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.VAL_INVALID_FORMAT,
            category = ErrorCategory.Validation,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.validation.invalid_format",
            publicMessage = "The format of the input file or model package is unsupported.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        ),
        ErrorCatalogEntry(
            code = StableErrorCode.VAL_ILLEGAL_ARGUMENT,
            category = ErrorCategory.Validation,
            phase = ErrorPhase.Validate,
            retryability = Retryability.UserAction,
            userMessageKey = "error.validation.illegal_argument",
            publicMessage = "An illegal argument was supplied to the operation.",
            allowExposeNativeCode = false,
            allowExposeHttpCode = true,
            allowExposeOsCode = false
        )
    )
}
