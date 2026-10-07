package com.muyuchat.core.engine.contract.error

import com.muyuchat.core.engine.contract.ErrorEnvelope
import com.muyuchat.core.engine.contract.RequestIdentity

/**
 * Central registry providing fast, immutable lookup of [ErrorCatalogEntry] by [StableErrorCode].
 *
 * Guarantees 100% complete coverage for all declared [StableErrorCode] values.
 */
object ErrorCatalogRegistry {

    private val catalogMap: Map<StableErrorCode, ErrorCatalogEntry> by lazy {
        val allEntries = buildList {
            addAll(ValidationErrorCatalog.entries)
            addAll(RuntimeNativeErrorCatalog.entries)
            addAll(ModelCapabilityErrorCatalog.entries)
            addAll(RequestExecutionErrorCatalog.entries)
            addAll(StorageNetworkErrorCatalog.entries)
        }

        val map = allEntries.associateBy { it.code }

        // Sanity check: Ensure every code declared in StableErrorCode is cataloged
        val missingCodes = StableErrorCode.entries.filter { it !in map }
        check(missingCodes.isEmpty()) {
            "Error catalog is missing entries for: $missingCodes"
        }

        map
    }

    /**
     * Looks up metadata for the given error code.
     * Guaranteed non-null because all enum values are validated on initialization.
     */
    fun get(code: StableErrorCode): ErrorCatalogEntry {
        return catalogMap[code] ?: error("No catalog entry found for code: $code")
    }

    /**
     * Returns an unmodifiable list of all registered catalog entries.
     */
    fun allEntries(): List<ErrorCatalogEntry> = catalogMap.values.toList()

    /**
     * Creates a fully initialized [ErrorEnvelope] using catalog defaults for the given code,
     * overriding specific fields as needed.
     */
    fun createEnvelope(
        code: StableErrorCode,
        identity: RequestIdentity? = null,
        causeChain: List<String> = emptyList(),
        nativeCode: Int? = null,
        httpCode: Int? = null,
        osCode: Int? = null,
        traceId: String = identity?.traceId ?: "",
        overridePublicMessage: String? = null
    ): ErrorEnvelope {
        val entry = get(code)
        return ErrorEnvelope(
            code = entry.code,
            category = entry.category,
            phase = entry.phase,
            retryability = entry.retryability,
            userMessageKey = entry.userMessageKey,
            publicMessage = overridePublicMessage ?: entry.publicMessage,
            causeChain = causeChain,
            identity = identity,
            nativeCode = nativeCode,
            httpCode = httpCode,
            osCode = osCode,
            traceId = traceId
        )
    }
}
