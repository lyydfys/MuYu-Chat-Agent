package com.muyuchat.mca.agent.extensions.skills

import java.security.MessageDigest

/**
 * Frozen V1 Skill limits (architecture 10.6 / 12.1).
 *
 * The importer enforces every value; the snapshot repository re-checks path safety before writing
 * so a caller that bypasses the importer cannot escape the private snapshot root.
 */
object AgentSkillLimits {
    const val SCHEMA_VERSION: Int = 1

    /** Whole uploaded package (directory tree or ZIP bytes) may not exceed this. */
    const val MAX_PACKAGE_BYTES: Long = 512L * 1024 * 1024

    /** Sum of all installed regular-file bytes plus previewed script metadata. */
    const val MAX_EXPANDED_BYTES: Long = 512L * 1024 * 1024

    const val MAX_ENTRIES: Int = 2_000
    const val MAX_SINGLE_ENTRY_BYTES: Long = 256L * 1024 * 1024
    const val MAX_PATH_DEPTH: Int = 16
    const val MAX_PATH_CHARS: Int = 512
    const val MAX_SEGMENT_CHARS: Int = 255
    const val MAX_COMPRESSION_RATIO: Long = 100L

    const val MAX_SKILL_MD_BYTES: Long = 64L * 1024
    const val MAX_NAME_CODE_POINTS: Int = 64
    const val MAX_DESCRIPTION_BYTES: Int = 2 * 1024
    const val MAX_TOOL_PATTERNS: Int = 64
    const val MAX_ENABLED_SKILLS: Int = 4
    const val MAX_METADATA_KEYS: Int = 64
    const val MAX_METADATA_VALUE_BYTES: Int = 8 * 1024
}

/** Lowercase hex SHA-256 helpers shared by importer, repository and snapshots. */
internal object AgentSkillHash {
    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(utf8: String): String = sha256Hex(utf8.toByteArray(Charsets.UTF_8))

    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    fun hex(digest: ByteArray): String = buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            append(HEX[value ushr 4])
            append(HEX[value and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * One document exposed by the SAF/directory adapter.
 *
 * Implementations must return only provider-visible children under the selected root. The importer
 * additionally rejects repeated [documentId]s (provider loops), symlinks/other node types, unsafe
 * display names and path traversal. `openStream` is called only for regular files that will be
 * installed; a script document is never opened (V1 never executes or installs scripts).
 */
interface AgentSkillTreeDocument {
    val documentId: String
    val displayName: String
    val type: AgentSkillDocumentType
    val sizeBytes: Long

    fun children(): List<AgentSkillTreeDocument>

    fun openStream(): java.io.InputStream
}

enum class AgentSkillDocumentType { FILE, DIRECTORY, SYMLINK, OTHER }
