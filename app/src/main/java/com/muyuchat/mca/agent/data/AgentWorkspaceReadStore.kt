package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentFileEntry
import com.muyuchat.core.agent.contract.AgentFileKind
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchMatch
import com.muyuchat.core.agent.contract.AgentSearchResult
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read side of the private workspace: list / read_text / search_text with the exact architecture 9.1
 * byte semantics.
 *
 * `inputs/` is read directly from the immutable UTF-8 snapshot files. `artifacts/` is resolved
 * through [AgentArtifactCatalog] because committed artifacts live at immutable physical
 * `artifacts/.versions/...` refs while the logical latest path exists only in the artifact rows
 * (architecture 10.3). Without a catalog (read-only unit fixtures) artifacts fall back to the plain
 * directory tree; `.versions` is never exposed either way.
 *
 * All methods are bounded: list scans at most [AgentDataLimits.MAX_LIST_SCAN_ENTRIES] entries, reads
 * at most [AgentDataLimits.MAX_READ_BYTES], search scans at most
 * [AgentDataLimits.SEARCH_TOTAL_SCAN_BYTES] overall and [AgentDataLimits.SEARCH_PER_FILE_BYTES] per
 * file. Binary/non-UTF-8 files are never guessed or replaced.
 */
class AgentWorkspaceReadStore(
    private val agentRoot: File,
    private val artifactCatalog: AgentArtifactCatalog? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun list(
        workspaceId: String,
        taskId: String,
        path: String,
        limit: Int,
        cursor: String?
    ): AgentFileResult<AgentListResult> = guarded {
        requirePage(limit, AgentDataLimits.MAX_TASK_PAGE_LIMIT, "list limit")
        val logicalDirectory = if (path.isBlank()) {
            AgentWorkspacePaths.INPUTS
        } else {
            AgentWorkspacePaths.directoryLogicalPath(path)
        }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        if (isArtifactsPath(logicalDirectory) && artifactCatalog != null) {
            listArtifacts(taskId, taskRoot, logicalDirectory, limit, cursor)
        } else {
            listFileSystem(taskRoot, logicalDirectory, limit, cursor)
        }
    }

    suspend fun readText(
        workspaceId: String,
        taskId: String,
        path: String,
        offsetBytes: Long,
        limitBytes: Int
    ): AgentFileResult<AgentReadResult> = guarded {
        if (offsetBytes < 0L) throw invalid("offset must not be negative")
        requirePage(limitBytes, AgentDataLimits.MAX_READ_BYTES, "read limit")
        val logical = AgentWorkspacePaths.fileLogicalPath(path)
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        val target = resolveReadTarget(taskRoot, taskId, logical)
        readFile(target.file, offsetBytes, limitBytes, target.expectedSha256)
    }

    suspend fun searchText(
        workspaceId: String,
        taskId: String,
        query: String,
        path: String,
        maxMatches: Int
    ): AgentFileResult<AgentSearchResult> = guarded {
        if (query.isEmpty() || query.codePointCount(0, query.length) > AgentDataLimits.MAX_SEARCH_QUERY_CHARS) {
            throw invalid("query must contain 1..${AgentDataLimits.MAX_SEARCH_QUERY_CHARS} characters")
        }
        requirePage(maxMatches, AgentDataLimits.MAX_SEARCH_MATCHES, "maxMatches")
        val logical = if (path.isBlank()) {
            AgentWorkspacePaths.INPUTS
        } else {
            AgentWorkspacePaths.directoryLogicalPath(path)
        }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        var explicitFile = false
        var catalogMissingFile = false
        val targets: List<SearchTarget> = if (isArtifactsPath(logical) && artifactCatalog != null) {
            val all = artifactCatalog.listLatestCommitted(taskId, AgentDataLimits.MAX_LIST_SCAN_ENTRIES)
            val exact = all.firstOrNull { it.artifact.relativePath == logical }
            if (exact != null) {
                explicitFile = true
                listOf(searchTarget(taskRoot, exact, explicit = true))
            } else {
                val prefix = if (logical == AgentArtifactLayout.ARTIFACTS) {
                    "${AgentArtifactLayout.ARTIFACTS}/"
                } else {
                    "$logical/"
                }
                all.filter { it.artifact.relativePath.startsWith(prefix) }
                    .sortedBy { it.artifact.relativePath }
                    .mapNotNull { stored ->
                        runCatching { searchTarget(taskRoot, stored, explicit = false) }
                            .onFailure { catalogMissingFile = true }
                            .getOrNull()
                    }
            }
        } else {
            val start = AgentWorkspacePaths.resolve(taskRoot, logical)
            if (!start.exists()) {
                throw AgentStorageException(AgentDataErrorCodes.NOT_FOUND, "search path was not found")
            }
            if (Files.isSymbolicLink(start.toPath())) throw invalid("search path is a symbolic link")
            if (start.isFile) {
                explicitFile = true
                listOf(SearchTarget(logical, start))
            } else {
                collectFiles(taskRoot, start).map { file ->
                    SearchTarget(AgentWorkspacePaths.logicalPathOf(taskRoot, file), file)
                }
            }
        }
        scanTargets(targets, query, maxMatches, explicitFile, catalogMissingFile)
    }

    /** Creates the four task roots; used by import/composition before the first write. */
    fun ensureTaskRoots(workspaceId: String, taskId: String): File =
        AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)

    fun taskRoot(workspaceId: String, taskId: String): File =
        AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)

    private suspend fun listArtifacts(
        taskId: String,
        taskRoot: File,
        logicalDirectory: String,
        limit: Int,
        cursor: String?
    ): AgentListResult {
        if (artifactCatalog!!.latestCommitted(taskId, logicalDirectory) != null) {
            throw invalid("'$logicalDirectory' is a file, not a directory")
        }
        val committed = artifactCatalog.listLatestCommitted(taskId, AgentDataLimits.MAX_LIST_SCAN_ENTRIES)
            .filter { it.artifact.commitState == com.muyuchat.core.agent.contract.AgentArtifactCommitState.COMMITTED }
        val versionMaterial = buildString {
            committed.sortedBy { it.artifact.relativePath }.forEach { stored ->
                append(stored.artifact.relativePath).append('\u0000')
                append(stored.artifact.sha256).append('\u0000')
                append(stored.artifact.sizeBytes).append('\n')
            }
        }
        val version = AgentCommandFingerprint.sha256Hex(versionMaterial.toByteArray(Charsets.UTF_8))
        val prefix = if (logicalDirectory == AgentArtifactLayout.ARTIFACTS) {
            "${AgentArtifactLayout.ARTIFACTS}/"
        } else {
            "$logicalDirectory/"
        }
        val entries = linkedMapOf<String, AgentFileEntry>()
        committed.forEach { stored ->
            val path = stored.artifact.relativePath
            if (!path.startsWith(prefix)) return@forEach
            val remainder = path.removePrefix(prefix)
            if (remainder.isEmpty()) return@forEach
            val slash = remainder.indexOf('/')
            if (slash >= 0) {
                val directoryPath = prefix + remainder.substring(0, slash)
                entries.putIfAbsent(
                    directoryPath,
                    AgentFileEntry(directoryPath, AgentFileKind.DIRECTORY, 0L, null)
                )
            } else {
                entries[path] = AgentFileEntry(
                    logicalPath = path,
                    kind = AgentFileKind.FILE,
                    sizeBytes = stored.artifact.sizeBytes,
                    sha256 = stored.artifact.sha256
                )
            }
        }
        if (entries.size > AgentDataLimits.MAX_LIST_SCAN_ENTRIES) {
            throw AgentStorageException("LIST_LIMIT_EXCEEDED", "artifact workspace has too many entries")
        }
        val sorted = entries.values.sortedBy { it.logicalPath }
        val after = cursor?.takeIf { it.isNotBlank() }?.let { AgentListCursor.decode(it, version).lastPath }
        val filtered = if (after == null) sorted else sorted.filter { it.logicalPath > after }
        val page = filtered.take(limit)
        val nextCursor = if (filtered.size > limit) {
            AgentListCursor.encode(version, page.last().logicalPath)
        } else {
            null
        }
        return AgentListResult(entries = page, nextCursor = nextCursor)
    }

    private fun listFileSystem(
        taskRoot: File,
        logicalDirectory: String,
        limit: Int,
        cursor: String?
    ): AgentListResult {
        val directory = AgentWorkspacePaths.resolveReadableDirectory(taskRoot, logicalDirectory)
        val children = directory.listFiles()?.toList().orEmpty()
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .filterNot { isInternalVersions(taskRoot, it) }
            .sortedWith(compareBy<File> { AgentWorkspacePaths.logicalPathOf(taskRoot, it) })
        if (children.size > AgentDataLimits.MAX_LIST_SCAN_ENTRIES) {
            throw AgentStorageException(
                "LIST_LIMIT_EXCEEDED",
                "directory has more than ${AgentDataLimits.MAX_LIST_SCAN_ENTRIES} entries"
            )
        }
        val version = directoryVersion(children)
        val after = cursor?.takeIf { it.isNotBlank() }?.let { AgentListCursor.decode(it, version).lastPath }
        val filtered = if (after == null) {
            children
        } else {
            children.filter { AgentWorkspacePaths.logicalPathOf(taskRoot, it) > after }
        }
        val page = filtered.take(limit)
        val entries = page.map { child ->
            val logical = AgentWorkspacePaths.logicalPathOf(taskRoot, child)
            AgentFileEntry(
                logicalPath = logical,
                kind = if (child.isDirectory) AgentFileKind.DIRECTORY else AgentFileKind.FILE,
                sizeBytes = if (child.isFile) child.length() else 0L,
                sha256 = null
            )
        }
        val nextCursor = if (filtered.size > limit) {
            AgentListCursor.encode(version, entries.last().logicalPath)
        } else {
            null
        }
        return AgentListResult(entries = entries, nextCursor = nextCursor)
    }

    private data class ReadTarget(val file: File, val expectedSha256: String?)

    private suspend fun resolveReadTarget(
        taskRoot: File,
        taskId: String,
        logical: String
    ): ReadTarget {
        if (isArtifactsPath(logical) && artifactCatalog != null) {
            val stored = artifactCatalog.latestCommitted(taskId, logical)
                ?: throw AgentStorageException(AgentDataErrorCodes.NOT_FOUND, "artifact '$logical' was not found")
            val physical = physicalFile(taskRoot, stored.storageRef)
            return ReadTarget(physical, stored.artifact.sha256)
        }
        return ReadTarget(AgentWorkspacePaths.resolveReadableFile(taskRoot, logical), null)
    }

    private fun readFile(
        file: File,
        offsetBytes: Long,
        limitBytes: Int,
        expectedSha256: String?
    ): AgentReadResult {
        val totalBytes = file.length()
        if (offsetBytes > totalBytes) throw invalid("offset is past the end of the file")
        if (offsetBytes == totalBytes) {
            val sha = AgentFileIo.sha256File(file)
            verifyArtifactHash(sha, expectedSha256)
            return AgentReadResult(
                content = "",
                offsetBytes = offsetBytes,
                nextOffsetBytes = offsetBytes,
                eof = true,
                totalBytes = totalBytes,
                sha256 = sha
            )
        }
        RandomAccessFile(file, "r").use { random ->
            random.seek(offsetBytes)
            val firstByte = random.read()
            if (firstByte < 0) throw invalid("offset is past the end of the file")
            if (!AgentUtf8Bounds.isCharBoundary(firstByte)) {
                throw invalid("offset is not a UTF-8 character boundary")
            }
            random.seek(offsetBytes)
            val windowSize = minOf(limitBytes.toLong(), totalBytes - offsetBytes).toInt()
            val window = ByteArray(windowSize)
            random.readFully(window)
            val atEof = offsetBytes + windowSize >= totalBytes
            val decoded = AgentUtf8Bounds.decodeCompletePrefix(window, atEof)
            if (decoded.consumedBytes == 0 && window.isNotEmpty()) {
                throw AgentStorageException("LIMIT_TOO_SMALL", "limit cannot hold the first character")
            }
            val sha = AgentFileIo.sha256File(file)
            verifyArtifactHash(sha, expectedSha256)
            return AgentReadResult(
                content = decoded.text,
                offsetBytes = offsetBytes,
                nextOffsetBytes = offsetBytes + decoded.consumedBytes,
                eof = offsetBytes + decoded.consumedBytes >= totalBytes,
                totalBytes = totalBytes,
                sha256 = sha
            )
        }
    }

    private fun verifyArtifactHash(actual: String, expected: String?) {
        if (expected != null && actual != expected) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_CORRUPTION,
                "committed artifact content does not match its recorded hash"
            )
        }
    }

    private data class SearchTarget(val logicalPath: String, val file: File)

    private fun searchTarget(
        taskRoot: File,
        stored: AgentStoredArtifact,
        explicit: Boolean
    ): SearchTarget {
        val physical = physicalFile(taskRoot, stored.storageRef)
        if (!explicit && physical.length() != stored.artifact.sizeBytes) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "artifact size mismatch")
        }
        return SearchTarget(stored.artifact.relativePath, physical)
    }

    private fun scanTargets(
        targets: List<SearchTarget>,
        query: String,
        maxMatches: Int,
        explicitFile: Boolean,
        catalogMissingFile: Boolean
    ): AgentSearchResult {
        val matches = ArrayList<AgentSearchMatch>(maxMatches)
        var scannedBytes = 0L
        var truncated = catalogMissingFile
        var stopped = false
        for (target in targets) {
            if (stopped) break
            val remainingTotal = AgentDataLimits.SEARCH_TOTAL_SCAN_BYTES - scannedBytes
            if (remainingTotal <= 0L) {
                truncated = true
                break
            }
            val file = target.file
            val readLimit = minOf(file.length(), AgentDataLimits.SEARCH_PER_FILE_BYTES, remainingTotal)
            if (file.length() > readLimit) truncated = true
            val bytes = readBounded(file, readLimit)
            scannedBytes += bytes.size
            val text = decodeSearchText(bytes) ?: run {
                if (explicitFile) {
                    throw AgentStorageException(
                        AgentDataErrorCodes.INPUT_INVALID,
                        "file content is not valid UTF-8 text"
                    )
                }
                truncated = true
                continue
            }
            var lineStart = 0
            var lineNumber = 1
            while (lineStart <= text.length) {
                val newline = text.indexOf('\n', lineStart)
                val lineEnd = if (newline < 0) text.length else newline
                val line = text.substring(lineStart, lineEnd).removeSuffix("\r")
                var from = 0
                while (true) {
                    val index = line.indexOf(query, from)
                    if (index < 0) break
                    if (matches.size == maxMatches) {
                        truncated = true
                        stopped = true
                        break
                    }
                    matches += AgentSearchMatch(
                        logicalPath = target.logicalPath,
                        lineNumber = lineNumber,
                        snippet = snippetAround(line, index, query.length)
                    )
                    from = index + maxOf(1, query.length)
                }
                if (stopped || newline < 0) break
                lineStart = newline + 1
                lineNumber += 1
            }
        }
        return AgentSearchResult(matches = matches, truncated = truncated, scannedBytes = scannedBytes)
    }

    private fun collectFiles(taskRoot: File, start: File): List<File> {
        val result = ArrayList<File>()
        val artifactsRoot = File(taskRoot, AgentWorkspacePaths.ARTIFACTS)
        fun visit(directory: File) {
            val children = directory.listFiles()?.sortedBy { it.name }.orEmpty()
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    if (directory == artifactsRoot && child.name == AgentArtifactLayout.VERSIONS) continue
                    visit(child)
                } else if (child.isFile) {
                    if (result.size >= AgentDataLimits.MAX_LIST_SCAN_ENTRIES) {
                        throw AgentStorageException(
                            "SEARCH_LIMIT_EXCEEDED",
                            "directory tree has too many files to scan"
                        )
                    }
                    result += child
                }
            }
        }
        visit(start)
        return result
    }

    private fun readBounded(file: File, maxBytes: Long): ByteArray {
        if (maxBytes <= 0L) return ByteArray(0)
        val buffer = ByteArray(maxBytes.coerceAtMost(AgentDataLimits.SEARCH_PER_FILE_BYTES).toInt())
        file.inputStream().use { input ->
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == buffer.size) buffer else buffer.copyOf(offset)
        }
    }

    private fun decodeSearchText(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        null
    }

    private fun snippetAround(line: String, index: Int, queryLength: Int): String {
        if (line.length <= 512) return line
        val start = maxOf(0, index - 96)
        var end = minOf(line.length, start + 512)
        if (end < line.length && Character.isHighSurrogate(line[end - 1])) end -= 1
        return line.substring(start, end)
    }

    private fun directoryVersion(children: List<File>): String {
        val material = buildString {
            children.forEach { child ->
                append(child.name).append('\u0000')
                append(if (child.isDirectory) 'D' else 'F').append('\u0000')
                append(child.length()).append('\u0000')
                append(child.lastModified()).append('\n')
            }
        }
        return AgentCommandFingerprint.sha256Hex(material.toByteArray(Charsets.UTF_8))
    }

    private fun isInternalVersions(taskRoot: File, child: File): Boolean =
        child.isDirectory &&
            child.parentFile == File(taskRoot, AgentWorkspacePaths.ARTIFACTS) &&
            child.name == AgentArtifactLayout.VERSIONS

    private fun isArtifactsPath(logical: String): Boolean =
        logical == AgentArtifactLayout.ARTIFACTS || logical.startsWith("${AgentArtifactLayout.ARTIFACTS}/")

    private fun physicalFile(taskRoot: File, storageRef: String): File {
        val canonicalRoot = taskRoot.canonicalFile
        val file = File(canonicalRoot, storageRef)
        if (Files.isSymbolicLink(file.toPath())) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "artifact storage ref is a symlink")
        }
        if (!file.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "artifact storage ref escapes the task root")
        }
        if (!file.isFile) {
            throw AgentStorageException(AgentDataErrorCodes.NOT_FOUND, "artifact storage file was not found")
        }
        return file.canonicalFile
    }

    private fun requirePage(value: Int, max: Int, name: String) {
        if (value !in 1..max) throw invalid("$name must be in 1..$max, was $value")
    }

    private fun invalid(message: String): AgentStorageException =
        AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, message)

    private suspend fun <T> guarded(block: suspend () -> T): AgentFileResult<T> = withContext(ioDispatcher) {
        try {
            AgentFileResult.Ok(block())
        } catch (error: AgentStorageException) {
            AgentFileResult.Failure(
                errorCode = error.code,
                safeMessage = error.message ?: "workspace operation failed",
                possibleSideEffects = error.possibleSideEffects
            )
        } catch (error: java.io.IOException) {
            AgentFileResult.Failure(
                errorCode = AgentDataErrorCodes.STORAGE_CORRUPTION,
                safeMessage = "workspace file operation failed: ${error.javaClass.simpleName}"
            )
        }
    }
}
