package com.muyuchat.mca.agent.toolpack

/**
 * Stable failure vocabulary for executable tool packages (N09–N12, MCA format v2).
 *
 * Two different layers use these codes:
 * - package definition (manifest / script bundle) validation at import time;
 * - one isolated script execution at run time.
 *
 * [agentCode] is the value surfaced through `AgentToolResult.errorCode`, so it must stay
 * UPPER_SNAKE_CASE and within the contract budget. [isDenial] distinguishes a policy refusal
 * (the caller must show it as DENIED) from a failure (FAILED); an unknown/undeclared host API is a
 * policy denial because the script asked for a capability it was never granted.
 */
enum class ToolPackageErrorCode(
    val agentCode: String,
    val isDenial: Boolean = false
) {
    // Manifest definition.
    MANIFEST_TOO_LARGE("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_INVALID_UTF8("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_MALFORMED("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_DUPLICATE_KEY("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_UNSUPPORTED_FORMAT("TOOLPACK_MANIFEST_UNSUPPORTED"),
    MANIFEST_FORMAT_AMBIGUOUS("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_UNSUPPORTED_HOST_API_VERSION("TOOLPACK_MANIFEST_UNSUPPORTED"),
    MANIFEST_UNKNOWN_FIELD("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_FORBIDDEN_FIELD("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_INVALID_FIELD("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_INVALID_ID("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_INVALID_VERSION("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_INVALID_ENTRY("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_TOO_MANY_TOOLS("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_DUPLICATE_TOOL("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_INVALID_SCHEMA("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_TOOL_TIMEOUT_INVALID("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_HOST_API_INVALID("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_HOST_API_UNKNOWN("TOOLPACK_HOST_API_UNKNOWN"),
    MANIFEST_HOST_API_DUPLICATE("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_HOST_API_TOO_MANY("TOOLPACK_MANIFEST_INVALID"),

    // Exact dependency lock (PKG-08/PKG-17/PKG-19).
    MANIFEST_DEPENDENCY_INVALID("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_DEPENDENCY_TOO_MANY("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_DEPENDENCY_DUPLICATE("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_DEPENDENCY_SELF("TOOLPACK_MANIFEST_INVALID"),
    MANIFEST_DEPENDENCY_UNKNOWN_FIELD("TOOLPACK_MANIFEST_INVALID"),

    // Script bundle definition.
    SCRIPT_INVALID_PATH("TOOLPACK_SCRIPT_INVALID"),
    SCRIPT_MISSING("TOOLPACK_SCRIPT_MISSING"),
    SCRIPT_TOO_LARGE("TOOLPACK_SCRIPT_INVALID"),
    SCRIPT_BUNDLE_TOO_LARGE("TOOLPACK_SCRIPT_INVALID"),
    SCRIPT_TOO_MANY("TOOLPACK_SCRIPT_INVALID"),

    // ZIP archive admission (N09a install input).
    ARCHIVE_INVALID("TOOLPACK_ARCHIVE_INVALID"),
    ARCHIVE_MISSING_MANIFEST("TOOLPACK_MANIFEST_MISSING"),
    ARCHIVE_ENTRY_UNSUPPORTED("TOOLPACK_ARCHIVE_INVALID"),

    // Host API policy at run time.
    HOST_API_UNKNOWN("TOOLPACK_HOST_API_UNKNOWN", isDenial = true),
    HOST_API_NOT_DECLARED("TOOLPACK_HOST_API_NOT_DECLARED", isDenial = true),
    HOST_API_WRITE_NOT_APPROVED("TOOLPACK_HOST_API_WRITE_NOT_APPROVED", isDenial = true),
    HOST_API_APPROVAL_REVOKED("TOOLPACK_HOST_APPROVAL_REVOKED", isDenial = true),
    HOST_CALL_REPLAY_MISMATCH("TOOLPACK_HOST_CALL_REPLAY", isDenial = true),
    HOST_CALL_RESOURCE_UNRESOLVED("TOOLPACK_HOST_RESOURCE_UNRESOLVED", isDenial = true),
    HOST_CALL_RESOURCE_UNDECLARED("TOOLPACK_HOST_RESOURCE_UNDECLARED", isDenial = true),
    HOST_CALL_ORDINAL_INVALID("TOOLPACK_HOST_CALL_INVALID"),
    HOST_CALL_ARGUMENTS_INVALID("TOOLPACK_HOST_CALL_INVALID"),
    HOST_CALL_ARGUMENTS_TOO_LARGE("TOOLPACK_HOST_CALL_INVALID"),
    HOST_CALL_LIMIT("TOOLPACK_HOST_CALL_LIMIT"),
    HOST_CALL_DENIED("TOOLPACK_HOST_CALL_DENIED", isDenial = true),
    HOST_CALL_RESULT_INVALID("TOOLPACK_HOST_FAILED"),
    HOST_CALL_OUTPUT_LIMIT("TOOLPACK_HOST_OUTPUT_LIMIT"),
    HOST_CALL_TOTAL_OUTPUT_LIMIT("TOOLPACK_HOST_OUTPUT_LIMIT"),
    HOST_CALL_FAILED("TOOLPACK_HOST_FAILED"),

    // Execution.
    EXECUTION_ARGUMENTS_INVALID("TOOL_INPUT_INVALID"),
    EXECUTION_TOOL_UNKNOWN("TOOL_NOT_FOUND"),
    EXECUTION_TIMEOUT("TOOL_TIMEOUT"),
    EXECUTION_OUTPUT_LIMIT("TOOLPACK_OUTPUT_LIMIT"),
    EXECUTION_RESULT_INVALID("TOOLPACK_RESULT_INVALID"),
    EXECUTION_CANCELLED("TOOL_FAILED"),
    INSTANCE_BUSY("COMMAND_BUSY"),
    ENGINE_FAILED("TOOL_FAILED"),

    // Catalog projection.
    TOOL_KEY_UNSUPPORTED("TOOLPACK_TOOL_KEY_INVALID"),
    WRITE_CAPABILITY_UNSUPPORTED("TOOLPACK_WRITE_UNSUPPORTED")
}

/**
 * One bounded, content-free failure detail. Messages never contain script source, credentials,
 * host file contents or absolute private paths.
 */
data class ToolPackageError(
    val code: ToolPackageErrorCode,
    val safeMessage: String
) {
    init {
        require(safeMessage.isNotBlank()) { "tool-package error message must not be blank" }
        require(safeMessage.none { it.isISOControl() }) {
            "tool-package error message must not contain control characters"
        }
        require(safeMessage.toByteArray(Charsets.UTF_8).size <= MAX_SAFE_MESSAGE_BYTES) {
            "tool-package error message exceeds $MAX_SAFE_MESSAGE_BYTES bytes"
        }
    }

    companion object {
        const val MAX_SAFE_MESSAGE_BYTES: Int = 2 * 1024
    }
}

/** Thrown at every package-definition, host-policy and execution boundary. */
class ToolPackageException(val error: ToolPackageError) :
    IllegalStateException(error.code.name + ": " + error.safeMessage)

/**
 * Cooperative cancellation signal. It deliberately does not extend [kotlinx.coroutines.CancellationException]:
 * a script engine aborts its own interpreter loop, while actual coroutine/owner cancellation keeps
 * propagating as coroutine cancellation.
 */
internal class ToolPackageCancelledException : Exception("tool package execution was cancelled")

internal fun toolPackageFailure(
    code: ToolPackageErrorCode,
    message: String
): Nothing = throw ToolPackageException(ToolPackageError(code, message))
