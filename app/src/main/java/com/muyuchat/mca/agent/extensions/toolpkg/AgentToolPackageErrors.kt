package com.muyuchat.mca.agent.extensions.toolpkg

/**
 * Stable, typed failures for tool-package preview, validation and the private preview store.
 *
 * Runtime/UI code branches on [AgentToolPackageError.code]. [AgentToolPackageError.message] is a
 * bounded, content-free explanation: it must never contain file contents, credentials, manifest
 * values or absolute private paths. The package is rejected as a whole; there is no partial import.
 */
enum class AgentToolPackageErrorCode {
    // Archive limits / ZIP hardening.
    PACKAGE_TOO_LARGE,
    ENTRY_COUNT_EXCEEDED,
    EXPANDED_SIZE_EXCEEDED,
    ENTRY_SIZE_EXCEEDED,
    COMPRESSION_RATIO_EXCEEDED,
    PATH_TRAVERSAL,
    ABSOLUTE_PATH,
    INVALID_PATH,
    PATH_DEPTH_EXCEEDED,
    DUPLICATE_PATH,
    PATH_CONFLICT,
    SYMLINK_REJECTED,
    NON_REGULAR_FILE,
    ENCRYPTED_ENTRY,
    UNSUPPORTED_COMPRESSION,
    MALFORMED_ZIP,

    // Manifest shape and values.
    MISSING_MANIFEST,
    MANIFEST_TOO_LARGE,
    MANIFEST_INVALID_UTF8,
    MANIFEST_MALFORMED,
    MANIFEST_DUPLICATE_KEY,
    MANIFEST_UNKNOWN_FIELD,
    MANIFEST_EXECUTABLE_FIELD,
    MANIFEST_INVALID_FIELD,
    MANIFEST_INVALID_ID,
    MANIFEST_INVALID_VERSION,
    MANIFEST_UNSUPPORTED_SCHEMA_VERSION,
    MANIFEST_UNSUPPORTED_API_VERSION,
    MANIFEST_DUPLICATE_TOOL,
    MANIFEST_TOO_MANY_TOOLS,
    MANIFEST_DUPLICATE_RESOURCE,
    MANIFEST_RESOURCE_UNLISTED,
    MANIFEST_RESOURCE_MISSING,
    MANIFEST_RESOURCE_HASH_MISMATCH,
    MANIFEST_RESOURCE_TYPE_UNSUPPORTED,
    MANIFEST_HOST_OPERATION_NOT_ALLOWED,
    MANIFEST_PRESET_INVALID,
    MANIFEST_EXPOSE_INVALID,
    MANIFEST_TIMEOUT_INVALID,
    MANIFEST_DEPENDENCY_INVALID,
    MANIFEST_DEPENDENCY_DUPLICATE,
    MANIFEST_DEPENDENCY_SELF,
    MANIFEST_DEPENDENCY_MISSING,
    MANIFEST_DEPENDENCY_CYCLE,

    // Preview store.
    DUPLICATE_PACKAGE_ID,
    PACKAGE_NOT_FOUND,
    PACKAGE_CORRUPT,
    INVALID_SOURCE,
    STORE_FAILURE
}

/** One explainable failure. [entryPath] and [line] are null when they do not apply. */
data class AgentToolPackageError(
    val code: AgentToolPackageErrorCode,
    val message: String,
    val entryPath: String? = null,
    val line: Int? = null
) {
    init {
        require(message.isNotBlank()) { "tool-package error message must not be blank" }
        require(message.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) {
            "tool-package error message exceeds $MAX_MESSAGE_BYTES bytes"
        }
        require(line == null || line > 0) { "tool-package error line must be positive" }
    }

    companion object {
        const val MAX_MESSAGE_BYTES: Int = 2 * 1024
    }
}

/** Thrown at the tool-package validation/store boundary. Catch by [error]. */
class AgentToolPackageException(
    val error: AgentToolPackageError,
    cause: Throwable? = null
) : IllegalStateException(
    buildString {
        append(error.code.name).append(": ").append(error.message)
        error.entryPath?.let { append(" [path=").append(it).append(']') }
        error.line?.let { append(" [line=").append(it).append(']') }
    },
    cause
)

internal fun toolPackageFailure(
    code: AgentToolPackageErrorCode,
    message: String,
    entryPath: String? = null,
    line: Int? = null
): Nothing = throw AgentToolPackageException(AgentToolPackageError(code, message, entryPath, line))
