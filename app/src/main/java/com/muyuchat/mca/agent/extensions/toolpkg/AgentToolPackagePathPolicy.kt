package com.muyuchat.mca.agent.extensions.toolpkg

import java.text.Normalizer

/**
 * Path admission for tool-package resources and archive entries.
 *
 * A package path is a relative, `/`-separated path below the private snapshot root. Backslashes and
 * drive-letter paths are rejected instead of being reinterpreted, `.` segments are discarded, and
 * `..` is always a typed traversal failure. Segments are NFC-normalized so two spellings cannot
 * become two distinct snapshot files. The same policy re-checks store writes (defense in depth).
 */
internal object AgentToolPackagePathPolicy {
    fun normalizeFile(raw: String, entryPath: String? = null): String {
        val cleaned = preNormalize(raw, entryPath)
        if (cleaned.endsWith("/")) {
            toolPackageFailure(
                AgentToolPackageErrorCode.INVALID_PATH,
                "a file path must not end with '/'",
                entryPath
            )
        }
        return finish(cleaned, entryPath)
    }

    fun normalizeDirectory(raw: String, entryPath: String? = null): String {
        val cleaned = preNormalize(raw.removeSuffix("/"), entryPath)
        if (cleaned.isEmpty()) {
            toolPackageFailure(
                AgentToolPackageErrorCode.INVALID_PATH,
                "directory path is empty",
                entryPath
            )
        }
        return finish(cleaned, entryPath)
    }

    fun isNormalizedRelativeFile(path: String): Boolean {
        if (path.isEmpty() || path.length > AgentToolPackageLimits.MAX_PATH_CHARS) return false
        if (path.startsWith('/') || path.contains('\\') || path.any { it.isISOControl() }) return false
        if (path.length >= 2 && path[1] == ':') return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (segments.size > AgentToolPackageLimits.MAX_PATH_DEPTH) return false
        if (segments.any { it.length > AgentToolPackageLimits.MAX_SEGMENT_CHARS }) return false
        return Normalizer.normalize(path, Normalizer.Form.NFC) == path
    }

    private fun preNormalize(raw: String, entryPath: String?): String {
        if (raw.isBlank()) {
            toolPackageFailure(AgentToolPackageErrorCode.INVALID_PATH, "path must not be blank", entryPath)
        }
        if (raw.any { it == '\u0000' || it.isISOControl() }) {
            toolPackageFailure(
                AgentToolPackageErrorCode.INVALID_PATH,
                "path contains control characters",
                entryPath
            )
        }
        if (raw.contains('\\')) {
            toolPackageFailure(
                AgentToolPackageErrorCode.INVALID_PATH,
                "backslashes are not accepted in package paths",
                entryPath
            )
        }
        if (raw.startsWith('/')) {
            toolPackageFailure(
                AgentToolPackageErrorCode.ABSOLUTE_PATH,
                "absolute paths are not allowed",
                entryPath
            )
        }
        if (raw.length >= 2 && raw[1] == ':') {
            toolPackageFailure(
                AgentToolPackageErrorCode.ABSOLUTE_PATH,
                "drive-letter paths are not allowed",
                entryPath
            )
        }
        return raw
    }

    private fun finish(path: String, entryPath: String?): String {
        val rawSegments = path.split('/')
        val normalized = ArrayList<String>(rawSegments.size)
        for (segment in rawSegments) {
            when (segment) {
                "." -> continue
                ".." -> toolPackageFailure(
                    AgentToolPackageErrorCode.PATH_TRAVERSAL,
                    "path traversal is not allowed",
                    entryPath
                )
                "" -> toolPackageFailure(
                    AgentToolPackageErrorCode.INVALID_PATH,
                    "path contains an empty segment",
                    entryPath
                )
                else -> {
                    if (segment.length > AgentToolPackageLimits.MAX_SEGMENT_CHARS) {
                        toolPackageFailure(
                            AgentToolPackageErrorCode.INVALID_PATH,
                            "path segment is too long",
                            entryPath
                        )
                    }
                    val nfc = Normalizer.normalize(segment, Normalizer.Form.NFC)
                    if (nfc.isEmpty() || nfc == "." || nfc == ".." ||
                        nfc.contains('/') || nfc.contains('\\')
                    ) {
                        toolPackageFailure(
                            AgentToolPackageErrorCode.INVALID_PATH,
                            "path segment is not valid after normalization",
                            entryPath
                        )
                    }
                    normalized += nfc
                }
            }
        }
        if (normalized.isEmpty()) {
            toolPackageFailure(
                AgentToolPackageErrorCode.INVALID_PATH,
                "path normalizes to nothing",
                entryPath
            )
        }
        if (normalized.size > AgentToolPackageLimits.MAX_PATH_DEPTH) {
            toolPackageFailure(
                AgentToolPackageErrorCode.PATH_DEPTH_EXCEEDED,
                "path depth exceeds ${AgentToolPackageLimits.MAX_PATH_DEPTH}",
                entryPath
            )
        }
        val joined = normalized.joinToString("/")
        if (joined.length > AgentToolPackageLimits.MAX_PATH_CHARS) {
            toolPackageFailure(AgentToolPackageErrorCode.INVALID_PATH, "path is too long", entryPath)
        }
        return joined
    }
}
