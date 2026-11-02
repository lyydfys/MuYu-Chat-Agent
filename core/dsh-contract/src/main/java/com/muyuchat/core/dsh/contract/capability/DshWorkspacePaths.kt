package com.muyuchat.core.dsh.contract.capability

/**
 * A strict, canonical workspace-relative path.
 *
 * Absolute paths, drive letters, backslashes, dot segments, empty segments, encoded traversal and
 * control characters are rejected at construction. This value only proves the lexical shape; the
 * broker must still canonicalize the real file and reject symlinks or any path that escapes the
 * canonical `DSH_WORKSPACE` root before touching the file system.
 */
@JvmInline
value class DshWorkspaceRelativePath private constructor(val value: String) {

    init {
        require(DshWorkspacePaths.isValid(value)) {
            "invalid DSH workspace relative path"
        }
    }

    override fun toString(): String = value

    companion object {
        /** Returns null instead of throwing for untrusted input. */
        fun parse(raw: String): DshWorkspaceRelativePath? =
            if (DshWorkspacePaths.isValid(raw)) DshWorkspaceRelativePath(raw) else null

        /** Throws [IllegalArgumentException] for untrusted input that must be rejected. */
        fun of(raw: String): DshWorkspaceRelativePath =
            parse(raw) ?: throw IllegalArgumentException("invalid DSH workspace relative path")
    }
}

/**
 * Pure lexical rules for files staged into the DSH workspace.
 *
 * The physical layout is `DSH_WORKSPACE/inbox/<generation>/<requestId>/<sanitized-name>`. The
 * contract owns the names and bounds; the app broker owns the real directory, canonicalization,
 * symlink checks, collision rejection and cleanup.
 */
object DshWorkspacePaths {
    const val INBOX_RELATIVE_ROOT = "inbox"

    private val ENCODED_TRAVERSAL = Regex("(?i)%2e|%2f|%5c")

    /** True only for a normalized relative path that cannot lexically escape the workspace. */
    fun isValid(relativePath: String): Boolean {
        if (relativePath.length !in 1..DshCapabilityLimits.MAX_WORKSPACE_RELATIVE_PATH_CHARS) return false
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) return false
        if (relativePath.contains('\\') || relativePath.contains(':')) return false
        if (relativePath.contains("//")) return false
        if (relativePath.any(::isControlCharacter)) return false
        if (ENCODED_TRAVERSAL.containsMatchIn(relativePath)) return false

        val segments = relativePath.split('/')
        if (segments.any { it.isEmpty() }) return false
        return segments.all { segment ->
            segment != "." &&
                segment != ".." &&
                segment.length <= DshCapabilityLimits.MAX_WORKSPACE_SEGMENT_CHARS &&
                segment == segment.trim() &&
                segment.none(::isControlCharacter)
        }
    }

    /** True only for `inbox/<generation>/<requestId>/<name>`. */
    fun isStagingPath(path: DshWorkspaceRelativePath): Boolean {
        val segments = path.value.split('/')
        if (segments.size != 4 || segments[0] != INBOX_RELATIVE_ROOT) return false
        val generation = segments[1].toLongOrNull() ?: return false
        if (generation < 0L) return false
        val requestId = segments[2]
        if (requestId.length !in 16..64) return false
        if (!requestId.all(::isRequestIdCharacter)) return false
        return isValidDisplayName(segments[3])
    }

    /**
     * Builds the staging path for one picked document.
     *
     * The broker must reject the write when this path already exists; the helper never overwrites
     * silently and never receives an absolute path from DSH.
     */
    fun stagingPath(
        generation: Long,
        requestId: DshCapabilityRequestId,
        displayName: String
    ): DshWorkspaceRelativePath {
        require(generation >= 0L) {
            "staging generation must be non-negative"
        }
        val safeName = sanitizeFileName(displayName)
        return DshWorkspaceRelativePath.of(
            "$INBOX_RELATIVE_ROOT/$generation/${requestId.value}/$safeName"
        )
    }

    /** Produces a single safe file name; path separators and traversal fragments are removed. */
    fun sanitizeFileName(raw: String): String {
        val baseName = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val builder = StringBuilder(baseName.length)
        for (character in baseName) {
            builder.append(
                when {
                    isControlCharacter(character) -> '_'
                    character == '<' || character == '>' || character == ':' ||
                        character == '"' || character == '/' || character == '\\' ||
                        character == '|' || character == '?' || character == '*' -> '_'
                    else -> character
                }
            )
        }
        val sanitized = builder.toString().trim().trim('.')
        val bounded = sanitized.take(DshCapabilityLimits.MAX_DISPLAY_NAME_CHARS)
        return bounded.ifEmpty { "file" }
    }

    /** Display names are result metadata only; they never contain path syntax or control chars. */
    fun isValidDisplayName(value: String): Boolean =
        value.isNotBlank() &&
            value.length <= DshCapabilityLimits.MAX_DISPLAY_NAME_CHARS &&
            value == value.trim() &&
            value != "." &&
            value != ".." &&
            value.none { isControlCharacter(it) || it == '/' || it == '\\' || it == ':' }
}
