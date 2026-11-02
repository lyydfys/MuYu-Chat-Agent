package com.muyuchat.mca.agent.security

/**
 * Logical, task-root-relative path rules shared by the frozen-command factory and the broker.
 *
 * Rules (architecture 8.4 / 9.1): paths are logical UTF-8 strings, never absolute; drive letters,
 * NUL/control characters, backslashes and `.`/`..` segments are rejected; a canonical path never
 * has a trailing slash; only `inputs` and `artifacts` roots exist; the write target is always a
 * file below `artifacts`. A real absolute path or symlink check belongs to the K06/K08 file port,
 * which is the only component allowed to resolve logical paths to device files.
 */
internal sealed interface AgentPathResult {
    data class Valid(val path: String) : AgentPathResult

    data class Invalid(val error: AgentSecurityError) : AgentPathResult
}

internal object AgentLogicalPaths {
    const val MAX_PATH_CHARS = 512
    const val MAX_DEPTH = 16
    const val INPUTS_ROOT = "inputs"
    const val ARTIFACTS_ROOT = "artifacts"

    private val drivePrefix = Regex("^[A-Za-z]:.*")

    fun normalizeReadPath(raw: String): AgentPathResult = normalize(raw, writeTarget = false)

    fun normalizeWritePath(raw: String): AgentPathResult = normalize(raw, writeTarget = true)

    /**
     * Directory grants are prefix scopes: `inputs` covers `inputs` and every descendant, while
     * `inputs/reports` covers that subtree only. Coverage is always checked before ALLOW_FOR_TASK.
     */
    fun covers(prefix: String, path: String): Boolean =
        path == prefix || path.startsWith("$prefix/")

    private fun normalize(raw: String, writeTarget: Boolean): AgentPathResult {
        if (raw.isBlank()) return invalid("Logical path must not be blank.")
        if (raw.length > MAX_PATH_CHARS) return invalid("Logical path exceeds the maximum length.")
        if (raw != raw.trim()) return invalid("Logical path must not have leading or trailing whitespace.")
        if (raw.indexOf('\u0000') >= 0) return invalid("Logical path must not contain NUL.")
        if (raw.any { it < ' ' }) return invalid("Logical path must not contain control characters.")
        if (raw.contains('\\')) return invalid("Logical path must use '/' separators only.")
        if (raw.startsWith("/")) return invalid("Absolute paths are not allowed.")
        if (drivePrefix.matches(raw)) return invalid("Drive-letter paths are not allowed.")

        val withoutTrailingSlash = raw.trimEnd('/')
        if (withoutTrailingSlash.isEmpty()) return invalid("Logical path must not be the task root.")
        val segments = withoutTrailingSlash.split('/')
        if (segments.any { it.isEmpty() }) return invalid("Logical path must not contain empty segments.")
        if (segments.any { it == "." || it == ".." }) return invalid("Logical path must not contain '.' or '..' segments.")
        if (segments.size > MAX_DEPTH) return invalid("Logical path exceeds the maximum depth.")
        if (!isKnownRoot(segments.first())) {
            return invalid("Logical path must stay under the task's inputs/ or artifacts/ root.")
        }

        val canonical = segments.joinToString("/")
        if (writeTarget) {
            if (segments.size < 2 || segments.first() != ARTIFACTS_ROOT) {
                return invalid("Write targets must be a file below artifacts/.")
            }
        }
        return AgentPathResult.Valid(canonical)
    }

    private fun isKnownRoot(segment: String): Boolean =
        segment == INPUTS_ROOT || segment == ARTIFACTS_ROOT

    private fun invalid(detail: String): AgentPathResult.Invalid =
        AgentPathResult.Invalid(AgentSecurityErrors.invalidArguments(detail))
}
