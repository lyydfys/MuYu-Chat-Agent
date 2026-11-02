package com.muyuchat.mca.agent.extensions.skills

/**
 * Stable, typed failures for Skill package import, parsing, installation and revocation.
 *
 * Runtime/UI code branches on [AgentSkillError.code]; [AgentSkillError.message] is a safe,
 * user-comprehensible explanation. Messages must never contain file contents, credentials or
 * absolute private paths.
 */
enum class AgentSkillErrorCode {
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
    LOOP_DOCUMENT_ID,
    ENCRYPTED_ENTRY,
    UNSUPPORTED_COMPRESSION,
    MALFORMED_ZIP,
    MISSING_SKILL_MD,
    SKILL_MD_TOO_LARGE,
    INVALID_UTF8,
    FRONTMATTER_MISSING,
    FRONTMATTER_UNSUPPORTED,
    FRONTMATTER_DUPLICATE_KEY,
    FRONTMATTER_INVALID_VALUE,
    NAME_INVALID,
    DESCRIPTION_TOO_LARGE,
    ALLOWED_TOOLS_INVALID,
    UNSUPPORTED_RESOURCE,
    TOO_MANY_ENABLED_SKILLS,
    SKILL_NOT_FOUND,
    VERSION_CONFLICT,
    STORE_FAILURE
}

/** One explainable failure. [entryPath] and [line] are null when they do not apply. */
data class AgentSkillError(
    val code: AgentSkillErrorCode,
    val message: String,
    val entryPath: String? = null,
    val line: Int? = null
) {
    init {
        require(message.isNotBlank()) { "skill error message must not be blank" }
        require(message.toByteArray(Charsets.UTF_8).size <= 2 * 1024) {
            "skill error message exceeds 2 KiB"
        }
        require(line == null || line > 0) { "skill error line must be positive" }
    }
}

/** Thrown by the Skill importer/store boundary. Catch by [AgentSkillException.error] only. */
class AgentSkillException(
    val error: AgentSkillError,
    cause: Throwable? = null
) : IllegalStateException(
    buildString {
        append(error.code.name).append(": ").append(error.message)
        error.entryPath?.let { append(" [path=").append(it).append(']') }
        error.line?.let { append(" [line=").append(it).append(']') }
    },
    cause
)

internal fun skillFailure(
    code: AgentSkillErrorCode,
    message: String,
    entryPath: String? = null,
    line: Int? = null
): Nothing = throw AgentSkillException(AgentSkillError(code, message, entryPath, line))
