package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentFileEntry
import com.muyuchat.core.agent.contract.AgentFileKind
import com.muyuchat.core.agent.contract.AgentFileResult
import com.muyuchat.core.agent.contract.AgentListResult
import com.muyuchat.core.agent.contract.AgentReadResult
import com.muyuchat.core.agent.contract.AgentSearchMatch
import com.muyuchat.core.agent.contract.AgentSearchResult
import com.muyuchat.core.agent.contract.AgentWorkspaceFilePort
import com.muyuchat.core.agent.contract.AgentWriteResult
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal data class FakeListCall(val path: String, val limit: Int, val cursor: String?)

internal data class FakeReadCall(val path: String, val offsetBytes: Long, val limitBytes: Int)

internal data class FakeSearchCall(val query: String, val path: String, val maxMatches: Int)

internal data class FakeWriteCall(
    val workspaceId: String,
    val taskId: String,
    val invocationId: String,
    val logicalPath: String,
    val content: String,
    val expectedOldHash: String?,
    val policyVersion: Long
)

/**
 * In-memory stand-in for the app-owned [AgentWorkspaceFilePort].
 *
 * It deliberately implements the byte-level contract the real store must honour: strict UTF-8
 * decoding, character-boundary offsets, `LIMIT_TOO_SMALL` when one character cannot fit, directory
 * versions bound to pagination cursors, literal search with scan caps, and hash-checked writes.
 */
internal class FakeAgentWorkspaceFilePort(
    private val totalScanCapBytes: Long = DEFAULT_TOTAL_SCAN_CAP,
    private val perFileScanCapBytes: Long = DEFAULT_PER_FILE_SCAN_CAP
) : AgentWorkspaceFilePort {
    val files = LinkedHashMap<String, ByteArray>()

    var forcedListFailure: AgentFileResult.Failure? = null
    var forcedReadFailure: AgentFileResult.Failure? = null
    var forcedSearchFailure: AgentFileResult.Failure? = null
    var forcedWriteFailure: AgentFileResult.Failure? = null

    /** Extra characters appended to every search snippet, used to exercise snippet bounding. */
    var snippetPadding: Int = 0

    var listCalls: Int = 0
        private set
    var readCalls: Int = 0
        private set
    var searchCalls: Int = 0
        private set
    var writeCalls: Int = 0
        private set

    var lastListCall: FakeListCall? = null
        private set
    var lastReadCall: FakeReadCall? = null
        private set
    var lastSearchCall: FakeSearchCall? = null
        private set
    var lastWriteCall: FakeWriteCall? = null
        private set

    private var artifactSequence = 0

    fun putText(path: String, text: String) {
        files[path] = text.toByteArray(Charsets.UTF_8)
    }

    fun putBytes(path: String, bytes: ByteArray) {
        files[path] = bytes.copyOf()
    }

    fun sha256Of(path: String): String = sha256(files.getValue(path))

    @Suppress("UNUSED_PARAMETER")
    override suspend fun list(
        workspaceId: String,
        taskId: String,
        path: String,
        limit: Int,
        cursor: String?
    ): AgentFileResult<AgentListResult> {
        listCalls++
        lastListCall = FakeListCall(path, limit, cursor)
        forcedListFailure?.let { return it }
        if (limit !in 1..200) {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "List limit is outside 1..200.")
        }
        val root = path.removeSuffix("/")
        if (root != "inputs" && root != "artifacts") {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "List path is outside the task workspace.")
        }
        val prefix = "$root/"
        val children = LinkedHashMap<String, Boolean>()
        files.keys.forEach { file ->
            if (!file.startsWith(prefix)) return@forEach
            val rest = file.removePrefix(prefix)
            if (rest.isEmpty()) return@forEach
            val slash = rest.indexOf('/')
            if (slash < 0) children[rest] = false else children[rest.substring(0, slash)] = true
        }
        val entries = children.entries.sortedBy { it.key }.map { (name, directory) ->
            val logicalPath = "$prefix$name"
            if (directory) {
                AgentFileEntry(logicalPath, AgentFileKind.DIRECTORY, 0L, null)
            } else {
                val bytes = files.getValue(logicalPath)
                AgentFileEntry(logicalPath, AgentFileKind.FILE, bytes.size.toLong(), sha256(bytes))
            }
        }
        val directoryVersion = sha256(
            entries.joinToString("|") {
                "${it.logicalPath}:${it.kind.name}:${it.sizeBytes}:${it.sha256 ?: ""}"
            }.toByteArray(Charsets.UTF_8)
        )
        var start = 0
        if (cursor != null) {
            val parts = cursor.split(':')
            if (parts.size != 3 || parts[0] != "cursor") {
                return AgentFileResult.Failure("CURSOR_STALE", "Workspace cursor is not valid.")
            }
            if (parts[1] != directoryVersion) {
                return AgentFileResult.Failure(
                    "CURSOR_STALE",
                    "Workspace directory changed since the cursor was issued."
                )
            }
            start = parts[2].toIntOrNull()
                ?: return AgentFileResult.Failure("CURSOR_STALE", "Workspace cursor is not valid.")
            if (start < 0 || start > entries.size) {
                return AgentFileResult.Failure("CURSOR_STALE", "Workspace cursor is not valid.")
            }
        }
        val page = entries.drop(start).take(limit)
        val nextIndex = start + page.size
        val nextCursor = if (nextIndex < entries.size) "cursor:$directoryVersion:$nextIndex" else null
        return AgentFileResult.Ok(AgentListResult(page, nextCursor))
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun readText(
        workspaceId: String,
        taskId: String,
        path: String,
        offsetBytes: Long,
        limitBytes: Int
    ): AgentFileResult<AgentReadResult> {
        readCalls++
        lastReadCall = FakeReadCall(path, offsetBytes, limitBytes)
        forcedReadFailure?.let { return it }
        val bytes = files[path]
            ?: return AgentFileResult.Failure("TOOL_INPUT_INVALID", "File is not present in this task workspace.")
        if (limitBytes !in 1..16_384) {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "Read limit is outside 1..16384.")
        }
        if (offsetBytes < 0L || offsetBytes > bytes.size.toLong()) {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "Read offset is outside the file.")
        }
        val text = try {
            decodeUtf8(bytes)
        } catch (failure: CharacterCodingException) {
            return AgentFileResult.Failure(
                "TOOL_INPUT_INVALID",
                "File content is not valid UTF-8 text."
            )
        }
        val boundaries = byteBoundaries(text)
        val startChar = boundaries[offsetBytes]
            ?: return AgentFileResult.Failure(
                "TOOL_INPUT_INVALID",
                "Read offset is not on a UTF-8 character boundary."
            )
        val endByte = offsetBytes + limitBytes
        val endChar = boundaries.entries
            .filter { it.key in offsetBytes..endByte }
            .maxByOrNull { it.key }!!
            .value
        if (endChar == startChar && startChar < text.length) {
            return AgentFileResult.Failure(
                "LIMIT_TOO_SMALL",
                "Read limit is too small to hold one complete character."
            )
        }
        val content = text.substring(startChar, endChar)
        val nextOffset = offsetBytes + content.toByteArray(Charsets.UTF_8).size
        return AgentFileResult.Ok(
            AgentReadResult(
                content = content,
                offsetBytes = offsetBytes,
                nextOffsetBytes = nextOffset,
                eof = endChar == text.length,
                totalBytes = bytes.size.toLong(),
                sha256 = sha256(bytes)
            )
        )
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun searchText(
        workspaceId: String,
        taskId: String,
        query: String,
        path: String,
        maxMatches: Int
    ): AgentFileResult<AgentSearchResult> {
        searchCalls++
        lastSearchCall = FakeSearchCall(query, path, maxMatches)
        forcedSearchFailure?.let { return it }
        if (query.isEmpty()) {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "Search query must not be empty.")
        }
        val root = path.removeSuffix("/")
        if (root != "inputs" && root != "artifacts") {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "Search path is outside the task workspace.")
        }
        val prefix = "$root/"
        val matches = mutableListOf<AgentSearchMatch>()
        var scanned = 0L
        var truncated = false
        val orderedFiles = files.keys.filter { it.startsWith(prefix) }.sorted()
        fileLoop@ for (filePath in orderedFiles) {
            val bytes = files.getValue(filePath)
            if (bytes.size.toLong() > perFileScanCapBytes) {
                truncated = true
                scanned += perFileScanCapBytes
                continue
            }
            if (scanned + bytes.size > totalScanCapBytes) {
                truncated = true
                break
            }
            val text = try {
                decodeUtf8(bytes)
            } catch (failure: CharacterCodingException) {
                scanned += bytes.size
                continue
            }
            scanned += bytes.size
            val lines = text.split('\n')
            lineLoop@ for ((index, line) in lines.withIndex()) {
                var from = 0
                while (true) {
                    val at = line.indexOf(query, from)
                    if (at < 0) break
                    if (matches.size >= maxMatches) {
                        truncated = true
                        break@lineLoop
                    }
                    val start = (at - 40).coerceAtLeast(0)
                    val end = (at + query.length + 40).coerceAtMost(line.length)
                    val snippet = line.substring(start, end) +
                        if (snippetPadding > 0) "x".repeat(snippetPadding) else ""
                    matches.add(AgentSearchMatch(filePath, index + 1, snippet))
                    from = at + maxOf(1, query.length)
                }
            }
            if (matches.size >= maxMatches) {
                // Keep scanning progress truthful without pretending the file set was exhausted.
                continue
            }
        }
        return AgentFileResult.Ok(AgentSearchResult(matches, truncated, scanned))
    }

    @Suppress("UNUSED_PARAMETER")
    override suspend fun writeArtifact(
        workspaceId: String,
        taskId: String,
        invocationId: String,
        logicalPath: String,
        content: String,
        expectedOldHash: String?,
        policyVersion: Long
    ): AgentFileResult<AgentWriteResult> {
        writeCalls++
        lastWriteCall = FakeWriteCall(workspaceId, taskId, invocationId, logicalPath, content, expectedOldHash, policyVersion)
        forcedWriteFailure?.let { return it }
        if (!logicalPath.startsWith("artifacts/")) {
            return AgentFileResult.Failure("TOOL_INPUT_INVALID", "Writes are restricted to artifacts/.")
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val existing = files[logicalPath]
        if (expectedOldHash == null) {
            if (existing != null) {
                return AgentFileResult.Failure(
                    "STORAGE_PRECONDITION_CHANGED",
                    "Logical path already exists; expectedOldHash=null means create-only."
                )
            }
        } else {
            if (existing == null || sha256(existing) != expectedOldHash) {
                return AgentFileResult.Failure(
                    "STORAGE_PRECONDITION_CHANGED",
                    "Artifact content changed since the expected hash was recorded."
                )
            }
        }
        files[logicalPath] = bytes
        artifactSequence++
        return AgentFileResult.Ok(
            AgentWriteResult(
                artifactId = "artifact-$artifactSequence",
                logicalPath = logicalPath,
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes)
            )
        )
    }

    private fun byteBoundaries(text: String): Map<Long, Int> {
        val boundaries = LinkedHashMap<Long, Int>()
        var byteOffset = 0L
        var charIndex = 0
        while (true) {
            boundaries[byteOffset] = charIndex
            if (charIndex >= text.length) break
            val codePoint = text.codePointAt(charIndex)
            byteOffset += utf8Length(codePoint)
            charIndex += Character.charCount(codePoint)
        }
        return boundaries
    }

    private fun utf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    private fun decodeUtf8(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            val value = byte.toInt() and 0xFF
            builder.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private const val DEFAULT_TOTAL_SCAN_CAP = 32L * 1024 * 1024
        private const val DEFAULT_PER_FILE_SCAN_CAP = 8L * 1024 * 1024
        private const val HEX = "0123456789abcdef"
    }
}
