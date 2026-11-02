package com.muyuchat.mca.agent.extensions.skills

import java.text.Normalizer

/**
 * Path admission for imported Skill content.
 *
 * Names are normalized as NFC, separators become `/`, `.` segments are dropped, and `..` is always
 * a typed traversal failure. The same policy re-checks repository writes (defense in depth).
 */
internal object AgentSkillPathPolicy {

    fun normalizeFile(raw: String, entryPath: String? = null): String {
        val cleaned = preNormalize(raw, entryPath)
        val segments = cleaned.split('/')
        if (segments.isEmpty() || segments.any { it.isEmpty() }) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "path contains an empty segment", entryPath)
        }
        return finish(segments, entryPath)
    }

    fun normalizeDirectory(raw: String, entryPath: String? = null): String {
        val cleaned = preNormalize(raw.removeSuffix("/"), entryPath)
        if (cleaned.isEmpty()) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "directory path is empty", entryPath)
        }
        val segments = cleaned.split('/')
        if (segments.any { it.isEmpty() }) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "directory path contains an empty segment", entryPath)
        }
        return finish(segments, entryPath)
    }

    /** Validates one SAF/tree display name; returns its NFC form. */
    fun normalizeLeafName(raw: String, entryPath: String? = null): String {
        if (raw.isEmpty() || raw == "." || raw == ".." || raw.contains('/') || raw.contains('\\')) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "invalid document display name: '$raw'", entryPath)
        }
        if (raw.any { it == '\u0000' || it.isISOControl() }) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "document display name contains control characters", entryPath)
        }
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        if (normalized.length > AgentSkillLimits.MAX_SEGMENT_CHARS) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "path segment is too long", entryPath)
        }
        return normalized
    }

    fun isNormalizedRelativeFile(path: String): Boolean {
        if (path.isEmpty() || path.length > AgentSkillLimits.MAX_PATH_CHARS) return false
        if (path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
        val segments = path.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        if (segments.size > AgentSkillLimits.MAX_PATH_DEPTH) return false
        if (segments.any { it.length > AgentSkillLimits.MAX_SEGMENT_CHARS }) return false
        return Normalizer.normalize(path, Normalizer.Form.NFC) == path
    }

    private fun preNormalize(raw: String, entryPath: String?): String {
        if (raw.isBlank()) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "path must not be blank", entryPath)
        }
        if (raw.any { it == '\u0000' || it == '\t' || it.isISOControl() }) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "path contains control characters", entryPath)
        }
        val slashed = raw.replace('\\', '/')
        if (slashed.startsWith("/")) {
            skillFailure(AgentSkillErrorCode.ABSOLUTE_PATH, "absolute paths are not allowed: '$raw'", entryPath)
        }
        if (slashed.length >= 2 && slashed[1] == ':') {
            skillFailure(AgentSkillErrorCode.ABSOLUTE_PATH, "drive-letter paths are not allowed: '$raw'", entryPath)
        }
        return slashed
    }

    private fun finish(segments: List<String>, entryPath: String?): String {
        val normalized = ArrayList<String>(segments.size)
        for (segment in segments) {
            when (segment) {
                "." -> continue
                ".." -> skillFailure(
                    AgentSkillErrorCode.PATH_TRAVERSAL,
                    "path traversal is not allowed",
                    entryPath
                )
                else -> {
                    if (segment.isEmpty()) {
                        skillFailure(AgentSkillErrorCode.INVALID_PATH, "path contains an empty segment", entryPath)
                    }
                    if (segment.length > AgentSkillLimits.MAX_SEGMENT_CHARS) {
                        skillFailure(AgentSkillErrorCode.INVALID_PATH, "path segment is too long", entryPath)
                    }
                    val nfc = Normalizer.normalize(segment, Normalizer.Form.NFC)
                    if (nfc.isEmpty() || nfc == "." || nfc == ".." || nfc.contains('/') || nfc.contains('\\')) {
                        skillFailure(AgentSkillErrorCode.INVALID_PATH, "path segment is not valid after normalization", entryPath)
                    }
                    normalized += nfc
                }
            }
        }
        if (normalized.isEmpty()) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "path normalizes to nothing", entryPath)
        }
        if (normalized.size > AgentSkillLimits.MAX_PATH_DEPTH) {
            skillFailure(
                AgentSkillErrorCode.PATH_DEPTH_EXCEEDED,
                "path depth ${normalized.size} exceeds ${AgentSkillLimits.MAX_PATH_DEPTH}",
                entryPath
            )
        }
        val joined = normalized.joinToString("/")
        if (joined.length > AgentSkillLimits.MAX_PATH_CHARS) {
            skillFailure(AgentSkillErrorCode.INVALID_PATH, "path is too long", entryPath)
        }
        return joined
    }
}
