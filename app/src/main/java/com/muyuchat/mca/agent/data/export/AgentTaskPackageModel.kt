package com.muyuchat.mca.agent.data.export

import com.muyuchat.core.agent.contract.AgentDisplayStatus
import com.muyuchat.core.agent.contract.AgentTask
import com.muyuchat.core.agent.contract.AgentTaskLifecycle
import java.io.File
import java.text.Normalizer

/** Stable package identity. Bump only with a new major format and an importer branch. */
object AgentTaskPackageFormat {
    const val FORMAT: String = "mca.agent.task-package.v1"
    const val VERSION: Int = 1
    const val MANIFEST_ENTRY: String = "manifest.json"
    const val ARTIFACT_ROOT: String = "artifacts/"
    const val SCHEMA_VERSION: Int = 1
}

/** Bounded importer/exporter limits (architecture 10.6). */
data class AgentTaskPackageLimits(
    val maxEntries: Int = 2_000,
    val maxArchiveBytes: Long = 512L * 1024 * 1024,
    val maxTotalExpandedBytes: Long = 512L * 1024 * 1024,
    val maxEntryBytes: Long = 256L * 1024 * 1024,
    val maxDepth: Int = 16,
    val maxCompressionRatio: Int = 100,
    val maxManifestBytes: Int = 1024 * 1024,
    val maxArtifacts: Int = 2_000,
    val maxPathChars: Int = 512,
    val maxTitleChars: Int = 200,
    val maxMediaTypeChars: Int = 128
)

/** Typed package failures; messages never contain file content or credentials. */
enum class AgentPackageErrorCode {
    NOT_FOUND,
    TASK_TOMBSTONED,
    INVALID_ARCHIVE,
    TRUNCATED_ARCHIVE,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    PACKAGE_TOO_LARGE,
    ENTRY_RATIO_EXCEEDED,
    UNSAFE_PATH,
    DUPLICATE_ENTRY,
    ENCRYPTED_ENTRY,
    UNSUPPORTED_COMPRESSION,
    UNSUPPORTED_VERSION,
    MANIFEST_INVALID,
    MANIFEST_PATH_MISMATCH,
    MISSING_MANIFEST,
    MISSING_ARTIFACT,
    UNREFERENCED_ENTRY,
    HASH_MISMATCH,
    SIZE_MISMATCH,
    SYMLINK_ENTRY,
    ARTIFACT_NOT_COMMITTED,
    ARTIFACT_FILE_MISSING,
    QUOTA_EXCEEDED,
    DISK_SPACE_INSUFFICIENT,
    PERSISTENCE_FAILED,
    IO_FAILURE
}

sealed interface AgentPackageResult<out T> {
    data class Success<T>(val value: T) : AgentPackageResult<T>

    data class Failure(
        val code: AgentPackageErrorCode,
        val safeMessage: String
    ) : AgentPackageResult<Nothing>
}

/** Display-only task summary; the goal text, grants, policy and credentials are never exported. */
data class AgentTaskDisplaySummary(
    val displayTitle: String,
    val displayStatus: AgentDisplayStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    init {
        require(displayTitle.isNotBlank()) { "displayTitle must not be blank" }
    }
}

data class AgentPackageArtifactEntry(
    val relativePath: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String
) {
    init {
        require(relativePath.startsWith(AgentTaskPackageFormat.ARTIFACT_ROOT)) {
            "artifact path must be under artifacts/"
        }
        require(sizeBytes >= 0L) { "artifact size must not be negative" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "artifact sha256 must be lowercase hex" }
    }
}

data class AgentTaskPackageManifest(
    val exportedAtMillis: Long,
    val task: AgentTaskDisplaySummary,
    val artifacts: List<AgentPackageArtifactEntry>,
    val unknownFieldsJson: String? = null,
    val schemaVersion: Int = AgentTaskPackageFormat.SCHEMA_VERSION
)

/** A verified artifact staged inside the importer's private output directory. */
data class AgentImportedArtifact(
    val relativePath: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val stagedFile: File
)

/**
 * A fully validated import result. It intentionally contains no run, no grants and no executable
 * state; the caller persists it as lifecycle IMPORTED_ARCHIVED under the generated ids.
 */
data class AgentImportedTaskPackage(
    val taskId: String,
    val workspaceId: String,
    val lifecycle: AgentTaskLifecycle,
    val displayTitle: String,
    val displayStatus: AgentDisplayStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val artifacts: List<AgentImportedArtifact>,
    val stagingRoot: File,
    val archiveSha256: String,
    val archiveBytes: Long,
    val unknownManifestJson: String?,
    val schemaVersion: Int = AgentTaskPackageFormat.SCHEMA_VERSION
) {
    init {
        require(lifecycle == AgentTaskLifecycle.IMPORTED_ARCHIVED) { "imports are archived, never ACTIVE" }
        require(artifacts.size <= 2_000) { "too many artifacts" }
    }

    /** Display summary only; no goal/run/grant is reconstructed from the package. */
    fun displaySummary(): AgentTaskDisplaySummary = AgentTaskDisplaySummary(
        displayTitle = displayTitle,
        displayStatus = displayStatus,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

data class AgentTaskExportSummary(
    val taskId: String,
    val artifactCount: Int,
    val packageBytes: Long,
    val packageSha256: String,
    val exportedAtMillis: Long
)

/**
 * Path validation shared by the manifest and ZIP entry names.
 *
 * Only `manifest.json` and normalized `artifacts/...` entries are legal. Absolute paths, drive
 * letters, backslashes, NUL/control characters, empty/`.`/`..` segments, Unicode-normalization
 * duplicates, `artifacts/.versions`, >512 characters and >16 segments are rejected before any
 * filesystem join.
 */
object AgentPackagePathGuard {
    private val controlChars = Regex("[\\u0000-\\u001f\\u007f]")

    fun validateArchiveEntry(name: String, limits: AgentTaskPackageLimits): String? {
        if (name == AgentTaskPackageFormat.MANIFEST_ENTRY) return name
        return validateArtifactPath(name, limits)
    }

    fun validateArtifactPath(path: String, limits: AgentTaskPackageLimits): String? {
        if (path.isEmpty()) return null
        if (path.length > limits.maxPathChars * 2) return null
        if (path.any { it.code < 0x20 || it.code == 0x7f }) return null
        if (path.contains('\\')) return null
        if (path.startsWith("/") || path.startsWith("~")) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return null
        if (controlChars.containsMatchIn(path)) return null
        val normalized = Normalizer.normalize(path, Normalizer.Form.NFC)
        if (normalized != path) return null
        if (normalized.codePointCount(0, normalized.length) > limits.maxPathChars) return null
        val segments = normalized.split('/')
        if (segments.size > limits.maxDepth) return null
        if (segments.first() != AgentTaskPackageFormat.ARTIFACT_ROOT.removeSuffix("/")) return null
        if (segments.size < 2) return null
        segments.forEach { segment ->
            if (segment.isEmpty() || segment == "." || segment == "..") return null
        }
        if (segments.size >= 3 && segments[1] == ".versions") return null
        return normalized
    }

    fun normalizeForComparison(path: String): String =
        Normalizer.normalize(path, Normalizer.Form.NFC).lowercase()

    /** Ensures [candidate] is a regular file under [root] without following symlinks. */
    fun resolveStagedFile(root: File, relativePath: String, limits: AgentTaskPackageLimits): File? {
        val safe = validateArtifactPath(relativePath, limits) ?: return null
        val canonicalRoot = root.canonicalFile
        val file = File(canonicalRoot, safe)
        if (file.canonicalFile.toPath().startsWith(canonicalRoot.toPath()).not()) return null
        return file
    }
}

internal fun AgentTask.toDisplaySummary(displayTitle: String): AgentTaskDisplaySummary =
    AgentTaskDisplaySummary(
        displayTitle = displayTitle,
        displayStatus = displayStatus,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )

/**
 * Optional display-title seam. `AgentTask` has no displayTitle field (it lives on the Room entity),
 * and the goal text must never be exported, so the composition may wire this to the task DAO.
 * The default is a generic title, never the goal.
 */
fun interface AgentTaskDisplayTitleReader {
    suspend fun displayTitle(taskId: String): String?
}

/** One validated central-directory record. */
internal data class AgentPackageZipEntry(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long
)

internal data class AgentPackageZipSource(
    val entry: AgentPackageArtifactEntry,
    val file: File
)

/** Internal typed exception used only to unwind within the package engine; callers get results. */
class AgentPackageException(
    val code: AgentPackageErrorCode,
    message: String
) : IllegalStateException(message)
