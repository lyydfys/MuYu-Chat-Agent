package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentContract
import java.io.File
import java.nio.file.Files
import java.text.Normalizer
import java.util.Base64

/**
 * Logical-path validation and private task-root resolution (architecture 8.4 / 9.1).
 *
 * Rules enforced for every caller:
 *  * a path is task-relative, `/`-separated and starts with `inputs` or `artifacts`;
 *  * absolute paths, drive letters, `~`, backslashes, NUL/control characters, empty, `.` and `..`
 *    segments, `//`, normalization-only duplicates, >512 characters and >16 segments are rejected;
 *  * `artifacts/.versions` is reserved physical storage and can never be addressed logically;
 *  * a resolved file/directory must stay under the canonical task root and no path segment may be a
 *    symbolic link (prevents prefix and symlink escapes).
 */
object AgentWorkspacePaths {
    const val INPUTS: String = AgentArtifactLayout.INPUTS
    const val ARTIFACTS: String = AgentArtifactLayout.ARTIFACTS
    const val PAYLOADS: String = AgentArtifactLayout.PAYLOADS
    const val STAGING: String = AgentArtifactLayout.STAGING

    private val readableRoots = setOf(INPUTS, ARTIFACTS)
    private val reservedWindowsNames = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )
    private val forbiddenNameChars = Regex("[\\u0000-\\u001f\\u007f<>:\"/\\\\|?*]")

    fun taskRoot(agentRoot: File, workspaceId: String, taskId: String, create: Boolean = false): File {
        val safeWorkspace = requireSafeId(workspaceId, "workspaceId")
        val safeTask = requireSafeId(taskId, "taskId")
        val canonicalAgentRoot = agentRoot.canonicalFile
        val workspaces = File(canonicalAgentRoot, "workspaces")
        val workspaceDir = File(workspaces, safeWorkspace)
        val tasksDir = File(workspaceDir, "tasks")
        val taskRoot = File(tasksDir, safeTask)
        for (directory in listOf(workspaces, workspaceDir, tasksDir, taskRoot)) {
            if (directory.exists() && Files.isSymbolicLink(directory.toPath())) {
                throw AgentStorageException(
                    AgentDataErrorCodes.INPUT_INVALID,
                    "task root component is a symbolic link"
                )
            }
        }
        if (create) {
            for (directory in listOf(workspaces, workspaceDir, tasksDir, taskRoot)) {
                if (!directory.exists() && !directory.mkdir()) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_NO_SPACE,
                        "cannot create private workspace directory"
                    )
                }
            }
            for (name in listOf(INPUTS, ARTIFACTS, PAYLOADS, STAGING)) {
                val directory = File(taskRoot, name)
                if (!directory.exists() && !directory.mkdir()) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.STORAGE_NO_SPACE,
                        "cannot create workspace root '$name'"
                    )
                }
            }
        }
        requireInside(canonicalAgentRoot, taskRoot.canonicalFile)
        return taskRoot.canonicalFile
    }

    /**
     * Canonicalizes a logical path. [allowRoot] permits the directory path equal to the root
     * (`inputs` / `artifacts`) used by list/search defaults.
     */
    fun canonicalize(
        raw: String,
        allowedRoots: Set<String> = readableRoots,
        allowRoot: Boolean = true
    ): String {
        if (raw.isEmpty()) throw invalid("path must not be empty")
        if (raw.length > AgentDataLimits.MAX_LOGICAL_PATH_CHARS * 2) throw invalid("path is too long")
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) throw invalid("path contains control characters")
        if (raw.contains('\\')) throw invalid("path must use '/' separators")
        if (raw.startsWith("/") || raw.startsWith("~")) throw invalid("absolute paths are not allowed")
        if (Regex("^[A-Za-z]:").containsMatchIn(raw)) throw invalid("drive-letter paths are not allowed")
        if (raw.contains("//")) throw invalid("path contains an empty segment")
        val normalized = Normalizer.normalize(raw, Normalizer.Form.NFC)
        if (normalized.codePointCount(0, normalized.length) > AgentDataLimits.MAX_LOGICAL_PATH_CHARS) {
            throw invalid("path exceeds ${AgentDataLimits.MAX_LOGICAL_PATH_CHARS} characters")
        }
        val segments = normalized.split('/')
        if (segments.size > AgentDataLimits.MAX_LOGICAL_PATH_DEPTH) {
            throw invalid("path exceeds ${AgentDataLimits.MAX_LOGICAL_PATH_DEPTH} segments")
        }
        segments.forEach { segment ->
            when {
                segment.isEmpty() -> throw invalid("path contains an empty segment")
                segment == "." || segment == ".." -> throw invalid("path traversal segments are not allowed")
                segment != Normalizer.normalize(segment, Normalizer.Form.NFC) ->
                    throw invalid("path is not Unicode-normalized")
            }
        }
        val root = segments.first()
        if (root !in allowedRoots) {
            throw invalid("path must start with ${allowedRoots.sorted().joinToString(" or ")}/")
        }
        if (segments.size == 1 && !allowRoot) throw invalid("a file path is required")
        if (root == ARTIFACTS) {
            if (segments.size >= 2 && segments[1] == AgentArtifactLayout.VERSIONS) {
                throw invalid("artifacts/.versions is internal storage")
            }
        }
        return segments.joinToString("/")
    }

    fun fileLogicalPath(raw: String): String =
        canonicalize(raw, allowRoot = false).also { path ->
            if (path == INPUTS || path == ARTIFACTS) throw invalid("expected a file path")
        }

    fun directoryLogicalPath(raw: String): String =
        canonicalize(raw, allowRoot = true)

    fun resolveReadableFile(taskRoot: File, logicalPath: String): File {
        val resolved = resolve(taskRoot, fileLogicalPath(logicalPath))
        if (!resolved.isFile) {
            throw AgentStorageException(AgentDataErrorCodes.NOT_FOUND, "file '$logicalPath' was not found")
        }
        return resolved
    }

    fun resolveReadableDirectory(taskRoot: File, logicalPath: String): File {
        val canonical = directoryLogicalPath(logicalPath)
        val resolved = resolve(taskRoot, canonical)
        if (!resolved.isDirectory) {
            throw AgentStorageException(
                AgentDataErrorCodes.INPUT_INVALID,
                "'$logicalPath' is not a directory"
            )
        }
        return resolved
    }

    /** Resolves a logical path under [taskRoot], rejecting symlink components and containment escapes. */
    fun resolve(taskRoot: File, logicalPath: String): File {
        val canonicalRoot = taskRoot.canonicalFile
        var current = canonicalRoot
        for (segment in logicalPath.split('/')) {
            current = File(current, segment)
            if (current.exists() && Files.isSymbolicLink(current.toPath())) {
                throw invalid("path contains a symbolic link")
            }
        }
        val canonicalTarget = current.canonicalFile
        requireInside(canonicalRoot, canonicalTarget)
        return canonicalTarget
    }

    fun logicalPathOf(taskRoot: File, file: File): String {
        val rootPath = taskRoot.canonicalFile.toPath()
        val filePath = file.canonicalFile.toPath()
        if (!filePath.startsWith(rootPath) || filePath == rootPath) {
            throw invalid("file is outside the task root")
        }
        return rootPath.relativize(filePath).toString().replace(File.separatorChar, '/')
    }

    fun sanitizeImportName(raw: String): String {
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        var normalized = Normalizer.normalize(lastSegment, Normalizer.Form.NFC)
            .replace(forbiddenNameChars, "_")
            .trim()
            .trim('.', ' ')
        if (normalized.isEmpty()) normalized = "input"
        while (normalized.codePointCount(0, normalized.length) > 120) {
            normalized = normalized.dropLast(1)
        }
        if (normalized.startsWith('.')) normalized = "_$normalized"
        if (normalized.lowercase() in reservedWindowsNames) normalized = "_$normalized"
        return normalized
    }

    /** Adds a numeric suffix before the extension until [desired] is unique (NFC, case-folded). */
    fun uniqueImportName(desired: String, existingNames: Collection<String>): String {
        val normalizedExisting = existingNames
            .map { Normalizer.normalize(it, Normalizer.Form.NFC) }
            .toMutableSet()
        var candidate = sanitizeImportName(desired)
        if (candidate !in normalizedExisting && candidate.lowercase() !in normalizedExisting.map { it.lowercase() }) {
            return candidate
        }
        val dot = candidate.lastIndexOf('.')
        val base = if (dot > 0) candidate.substring(0, dot) else candidate
        val extension = if (dot > 0) candidate.substring(dot) else ""
        var index = 2
        while (true) {
            candidate = "$base ($index)$extension"
            val folded = candidate.lowercase()
            if (candidate !in normalizedExisting && folded !in normalizedExisting.map { it.lowercase() }) {
                return candidate
            }
            index += 1
            if (index > 10_000) throw invalid("cannot allocate a unique input name")
        }
    }

    fun requireSafeId(raw: String, field: String): String = try {
        AgentContract.requireId(raw, field)
    } catch (error: IllegalArgumentException) {
        throw AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, error.message ?: "$field is invalid")
    }

    private fun requireInside(root: File, candidate: File) {
        if (!candidate.toPath().startsWith(root.toPath())) {
            throw invalid("path escapes the task root")
        }
    }

    private fun invalid(message: String): AgentStorageException =
        AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, message)
}

/**
 * Directory-version-bound cursor for workspace.list. The version is a hash of the sorted directory
 * entries; any add/remove/rename/size/mtime change invalidates an outstanding cursor instead of
 * silently skipping the shifted rows.
 */
object AgentListCursor {
    private const val PREFIX = "v1"
    private const val MAX_CHARS = AgentDataLimits.MAX_CURSOR_CHARS

    data class Decoded(val version: String, val lastPath: String)

    fun encode(version: String, lastPath: String): String {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(lastPath.toByteArray(Charsets.UTF_8))
        return "$PREFIX.$version.$encoded"
    }

    fun decode(cursor: String, currentVersion: String): Decoded {
        if (cursor.length > MAX_CHARS) {
            throw AgentStorageException("CURSOR_STALE", "list cursor is too long")
        }
        val parts = cursor.split('.', limit = 3)
        if (parts.size != 3 || parts[0] != PREFIX) {
            throw AgentStorageException("CURSOR_STALE", "list cursor is malformed")
        }
        if (parts[1] != currentVersion) {
            throw AgentStorageException("CURSOR_STALE", "directory changed since the cursor was issued")
        }
        val lastPath = try {
            String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw AgentStorageException("CURSOR_STALE", "list cursor payload is malformed")
        }
        return Decoded(parts[1], lastPath)
    }
}
