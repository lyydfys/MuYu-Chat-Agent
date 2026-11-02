package com.muyuchat.core.agent.runtime.validation

/**
 * Logical path grammar shared by the four V1 workspace tools (architecture 9.1).
 *
 * Paths are task-relative logical paths only: `inputs/...` or `artifacts/...`. Absolute paths,
 * backslashes, `.`/`..` segments, NUL/control characters, empty segments, more than 16 levels and
 * more than 512 characters are rejected. Artifact writes must target `artifacts/` and must name a
 * file (no trailing slash).
 */
internal object AgentLogicalPaths {
    private const val MAX_PATH_CHARS = 512
    private const val MAX_DEPTH = 16

    fun validateDirectory(path: String): String? = validate(path, directory = true, artifactOnly = false)

    fun validateFile(path: String): String? = validate(path, directory = false, artifactOnly = false)

    fun validateArtifactTarget(path: String): String? =
        validate(path, directory = false, artifactOnly = true)

    private fun validate(path: String, directory: Boolean, artifactOnly: Boolean): String? {
        if (path.isEmpty()) return "path must not be empty"
        if (path.length > MAX_PATH_CHARS) return "path must be at most $MAX_PATH_CHARS characters"
        if (path.any { it.isISOControl() }) return "path must not contain control characters"
        if (path.contains('\\')) return "path must use '/' separators and a relative logical path"
        if (path.startsWith('/')) return "path must be a relative logical path"
        if (!path.startsWith("inputs/") && !path.startsWith("artifacts/")) {
            return "path must start with 'inputs/' or 'artifacts/'"
        }
        if (artifactOnly && !path.startsWith("artifacts/")) {
            return "artifact writes must target 'artifacts/'"
        }
        if (!directory && path.endsWith('/')) {
            return "path must reference a file, not a directory"
        }
        val segments = path.removeSuffix("/").split('/')
        if (segments.any { it.isEmpty() }) return "path must not contain empty segments"
        if (segments.any { it == "." || it == ".." }) {
            return "path must not contain '.' or '..' segments"
        }
        if (segments.any { it.isBlank() }) return "path segments must not be blank"
        if (segments.size > MAX_DEPTH) return "path must have at most $MAX_DEPTH levels"
        if (!directory && segments.size < 2) return "path must name a file below the root"
        return null
    }
}
