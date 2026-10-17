package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorCategory
import com.muyuchat.core.engine.contract.ErrorPhase
import com.muyuchat.core.engine.contract.Retryability

/**
 * Metadata entry for a single error code in the MCA Error Catalog.
 *
 * Defines static classification, user-facing localization key, sanitized public message,
 * and visibility policies for native/HTTP/OS error codes.
 */
data class ErrorCatalogEntry(
    val code: StableErrorCode,
    val category: ErrorCategory,
    val phase: ErrorPhase,
    val retryability: Retryability,
    val userMessageKey: String,
    val publicMessage: String,
    val allowExposeNativeCode: Boolean,
    val allowExposeHttpCode: Boolean,
    val allowExposeOsCode: Boolean
) {
    init {
        require(userMessageKey.isNotBlank()) { "userMessageKey must not be blank for $code" }
        require(publicMessage.isNotBlank()) { "publicMessage must not be blank for $code" }
    }
}
