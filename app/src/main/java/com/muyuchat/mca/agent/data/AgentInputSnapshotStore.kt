package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentFileResult
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One immutable, read-only UTF-8 input snapshot plus its provenance. */
data class AgentImportedInput(
    val inputId: String,
    val logicalPath: String,
    val mediaType: String,
    val sourceUriSha256: String,
    val sourceSha256: String,
    val sourceSizeBytes: Long,
    val sourceEncoding: String,
    val convertedToUtf8: Boolean,
    val bomRemoved: Boolean,
    val storedSha256: String,
    val storedSizeBytes: Long,
    val manifestRef: String,
    val createdAtMillis: Long,
    val schemaVersion: Int = 1
)

/** A caller-provided source stream; the provider URI is only fingerprinted, never persisted raw. */
data class AgentInputImportRequest(
    val sourceUri: String,
    val displayName: String,
    val mediaType: String = "text/plain",
    val sourceEncoding: String? = null,
    val openStream: () -> InputStream
)

/**
 * Copies user-selected documents into the task's read-only `inputs/` snapshot area.
 *
 * Provenance recorded: SHA-256 of the source URI (not the URI itself), source SHA-256/size, chosen
 * source encoding, whether a UTF-8 conversion and BOM removal happened, and the stored UTF-8
 * SHA-256/size. A binary or unknown-encoding stream is rejected explicitly; encoding is never
 * guessed and raw bytes are never overwritten with a lossy decode.
 */
class AgentInputSnapshotStore(
    private val agentRoot: File,
    private val payloadStore: AgentPayloadStore,
    private val clock: AgentClock = AgentClock.system(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    suspend fun importInput(
        workspaceId: String,
        taskId: String,
        request: AgentInputImportRequest
    ): AgentFileResult<AgentImportedInput> = withContext(ioDispatcher) {
        try {
            AgentFileResult.Ok(importInternal(workspaceId, taskId, request))
        } catch (error: AgentStorageException) {
            AgentFileResult.Failure(error.code, error.message ?: "input import failed")
        } catch (error: java.io.IOException) {
            AgentFileResult.Failure(
                AgentDataErrorCodes.STORAGE_CORRUPTION,
                "input import failed: ${error.javaClass.simpleName}"
            )
        }
    }

    fun importedInputs(workspaceId: String, taskId: String, limit: Int = 100): List<AgentImportedInput> {
        require(limit in 1..1_000) { "limit must be in 1..1000" }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        val directory = File(taskRoot, "${AgentWorkspacePaths.PAYLOADS}/import_manifest")
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        return files.mapNotNull { file ->
            val ref = "${AgentWorkspacePaths.PAYLOADS}/import_manifest/${file.name}"
            runCatching {
                String(payloadStore.readNullable(workspaceId, taskId, ref) ?: return@runCatching null)
                    .let { decodeManifest(it) }
            }.getOrNull()?.takeIf { it.logicalPath.isNotEmpty() }
        }.sortedByDescending { it.createdAtMillis }.take(limit)
    }

    private fun importInternal(
        workspaceId: String,
        taskId: String,
        request: AgentInputImportRequest
    ): AgentImportedInput {
        if (request.sourceUri.isBlank() || request.sourceUri.length > 2_048) {
            throw AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, "source URI is missing or too long")
        }
        if (request.mediaType.isBlank()) {
            throw AgentStorageException(AgentDataErrorCodes.INPUT_INVALID, "media type must not be blank")
        }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)
        val sourceUriSha256 = AgentCommandFingerprint.sha256Hex(request.sourceUri.toByteArray(Charsets.UTF_8))
        val sourceEncoding = request.sourceEncoding?.let { raw ->
            if (!Charset.isSupported(raw)) {
                throw AgentStorageException(
                    "TEXT_ENCODING_UNSUPPORTED",
                    "source encoding '$raw' is not supported"
                )
            }
            Charset.forName(raw)
        }
        val importId = idGenerator()
        val rawTemp = File(taskRoot, "${AgentWorkspacePaths.STAGING}/import-$importId.raw")
        val utf8Temp = File(taskRoot, "${AgentWorkspacePaths.STAGING}/import-$importId.utf8")
        var published: File? = null
        try {
            val copied = AgentFileIo.copyAndHash(
                input = request.openStream(),
                target = rawTemp,
                maxBytes = AgentDataLimits.MAX_IMPORT_BYTES
            )
            val prefix = readPrefix(rawTemp, AgentFileIo.BINARY_SNIFF_BYTES)
            val bom = detectBom(prefix)
            val charset: Charset
            val bomRemoved: Boolean
            when {
                sourceEncoding != null -> {
                    charset = sourceEncoding
                    bomRemoved = bom != null && bom.charset == sourceEncoding
                }
                bom != null -> {
                    charset = bom.charset
                    bomRemoved = true
                }
                copied.binaryDetected || isBinaryMediaType(request.mediaType) ->
                    throw AgentStorageException(
                        "BINARY_NOT_SUPPORTED",
                        "binary input cannot become a text snapshot"
                    )
                else -> {
                    charset = Charsets.UTF_8
                    bomRemoved = false
                }
            }
            if (sourceEncoding == null && !isUtf8Prefix(prefix) && bom == null) {
                throw AgentStorageException(
                    "TEXT_ENCODING_UNSUPPORTED",
                    "input is not valid UTF-8 and no explicit encoding was supplied"
                )
            }
            transcodeToUtf8(rawTemp, utf8Temp, charset, bom)
            if (containsNul(utf8Temp)) {
                throw AgentStorageException(
                    "BINARY_NOT_SUPPORTED",
                    "decoded text contains NUL characters"
                )
            }
            val storedSha256 = AgentFileIo.sha256File(utf8Temp)
            val storedSize = utf8Temp.length()
            ensureWithinQuota(workspaceId, taskId, storedSize)
            val existingNames = File(taskRoot, AgentWorkspacePaths.INPUTS).listFiles().orEmpty().map { it.name }
            var logicalName = AgentWorkspacePaths.uniqueImportName(request.displayName, existingNames)
            var target = File(taskRoot, "${AgentWorkspacePaths.INPUTS}/$logicalName")
            var attempts = 0
            while (target.exists()) {
                if (attempts >= 8) {
                    throw AgentStorageException("INPUT_INVALID", "cannot allocate a unique input path")
                }
                logicalName = AgentWorkspacePaths.uniqueImportName(
                    "${request.displayName} (${attempts + 2})",
                    existingNames
                )
                target = File(taskRoot, "${AgentWorkspacePaths.INPUTS}/$logicalName")
                attempts += 1
            }
            try {
                AgentFileIo.atomicMove(utf8Temp, target)
            } catch (error: java.io.IOException) {
                if (!target.isFile) throw error
            }
            published = target
            AgentFileIo.fsyncDirectory(File(taskRoot, AgentWorkspacePaths.INPUTS))
            target.setReadOnly()
            val input = AgentImportedInput(
                inputId = importId,
                logicalPath = "${AgentWorkspacePaths.INPUTS}/$logicalName",
                mediaType = request.mediaType,
                sourceUriSha256 = sourceUriSha256,
                sourceSha256 = copied.sha256,
                sourceSizeBytes = copied.sizeBytes,
                sourceEncoding = charset.name(),
                convertedToUtf8 = charset != Charsets.UTF_8,
                bomRemoved = bomRemoved,
                storedSha256 = storedSha256,
                storedSizeBytes = storedSize,
                manifestRef = "",
                createdAtMillis = clock.nowMillis()
            )
            val manifestBytes = encodeManifest(input).toByteArray(Charsets.UTF_8)
            val manifest = payloadStore.write(workspaceId, taskId, "import_manifest", manifestBytes)
            val result = input.copy(manifestRef = manifest.ref)
            AgentFileIo.fsyncDirectory(File(taskRoot, AgentWorkspacePaths.PAYLOADS))
            published = null
            return result
        } finally {
            AgentFileIo.deleteQuietly(rawTemp)
            AgentFileIo.deleteQuietly(utf8Temp)
            published?.let { if (it.name != rawTemp.name) AgentFileIo.deleteQuietly(it) }
        }
    }

    private fun transcodeToUtf8(source: File, target: File, charset: Charset, bom: Bom?) {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val skip = if (bom != null && bom.charset == charset) bom.length else 0
        try {
            source.inputStream().use { raw ->
                var skipped = 0
                while (skipped < skip) {
                    if (raw.read() < 0) break
                    skipped += 1
                }
                InputStreamReader(raw, decoder).use { reader ->
                    target.outputStream().use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                            val buffer = CharArray(8 * 1024)
                            while (true) {
                                val read = reader.read(buffer)
                                if (read < 0) break
                                if (read > 0) writer.write(buffer, 0, read)
                            }
                            writer.flush()
                        }
                        output.flush()
                    }
                }
            }
            java.io.FileOutputStream(target, true).use { it.fd.sync() }
        } catch (error: java.io.IOException) {
            AgentFileIo.deleteQuietly(target)
            throw AgentStorageException(
                "TEXT_ENCODING_UNSUPPORTED",
                "input cannot be decoded as ${charset.name()}"
            )
        }
    }

    private fun ensureWithinQuota(workspaceId: String, taskId: String, newBytes: Long) {
        val taskUsage = AgentStorageUsage.taskBytes(agentRoot, workspaceId, taskId)
        if (taskUsage + newBytes > AgentDataLimits.TASK_SOFT_QUOTA_BYTES) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_NO_SPACE,
                "task workspace exceeds the 256 MiB soft quota"
            )
        }
        val totalUsage = AgentStorageUsage.totalBytes(agentRoot)
        if (totalUsage + newBytes > AgentDataLimits.TOTAL_SOFT_QUOTA_BYTES) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_NO_SPACE,
                "Agent storage exceeds the 1 GiB soft quota"
            )
        }
        val usable = agentRoot.usableSpace
        if (usable in 1..(newBytes + AgentDataLimits.DISK_FREE_SAFETY_MARGIN_BYTES)) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_NO_SPACE, "device storage is low")
        }
    }

    private data class Bom(val charset: Charset, val length: Int)

    private fun detectBom(prefix: ByteArray): Bom? = when {
        prefix.size >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() &&
            prefix[2] == 0xBF.toByte() -> Bom(Charsets.UTF_8, 3)
        prefix.size >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte() ->
            Bom(Charsets.UTF_16LE, 2)
        prefix.size >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte() ->
            Bom(Charsets.UTF_16BE, 2)
        else -> null
    }

    private fun isUtf8Prefix(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
        true
    } catch (_: Exception) {
        false
    }

    private fun containsNul(file: File): Boolean {
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(AgentFileIo.BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return false
                for (index in 0 until read) {
                    if (buffer[index] == 0.toByte()) return true
                }
            }
        }
    }

    private fun readPrefix(file: File, maxBytes: Int): ByteArray {
        file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            var offset = 0
            while (offset < buffer.size) {
                val read = input.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                offset += read
            }
            return if (offset == buffer.size) buffer else buffer.copyOf(offset)
        }
    }

    private fun isBinaryMediaType(mediaType: String): Boolean {
        val normalized = mediaType.lowercase()
        return normalized.startsWith("image/") ||
            normalized.startsWith("audio/") ||
            normalized.startsWith("video/") ||
            normalized.startsWith("application/zip") ||
            normalized.startsWith("application/pdf") ||
            normalized.startsWith("application/octet-stream")
    }

    private fun encodeManifest(input: AgentImportedInput): String = JSONObject()
        .put("schemaVersion", input.schemaVersion)
        .put("inputId", input.inputId)
        .put("logicalPath", input.logicalPath)
        .put("mediaType", input.mediaType)
        .put("sourceUriSha256", input.sourceUriSha256)
        .put("sourceSha256", input.sourceSha256)
        .put("sourceSizeBytes", input.sourceSizeBytes)
        .put("sourceEncoding", input.sourceEncoding)
        .put("convertedToUtf8", input.convertedToUtf8)
        .put("bomRemoved", input.bomRemoved)
        .put("storedSha256", input.storedSha256)
        .put("storedSizeBytes", input.storedSizeBytes)
        .put("createdAt", input.createdAtMillis)
        .toString()

    private fun decodeManifest(raw: String): AgentImportedInput {
        val json = JSONObject(raw)
        return AgentImportedInput(
            inputId = json.getString("inputId"),
            logicalPath = json.getString("logicalPath"),
            mediaType = json.getString("mediaType"),
            sourceUriSha256 = json.getString("sourceUriSha256"),
            sourceSha256 = json.getString("sourceSha256"),
            sourceSizeBytes = json.getLong("sourceSizeBytes"),
            sourceEncoding = json.getString("sourceEncoding"),
            convertedToUtf8 = json.getBoolean("convertedToUtf8"),
            bomRemoved = json.getBoolean("bomRemoved"),
            storedSha256 = json.getString("storedSha256"),
            storedSizeBytes = json.getLong("storedSizeBytes"),
            manifestRef = "",
            createdAtMillis = json.getLong("createdAt"),
            schemaVersion = json.optInt("schemaVersion", 1)
        )
    }
}

/** Recursive byte accounting for the 256 MiB/task and 1 GiB/total soft thresholds. */
object AgentStorageUsage {
    fun taskBytes(agentRoot: File, workspaceId: String, taskId: String): Long {
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId)
        if (!taskRoot.isDirectory) return 0L
        return directoryBytes(taskRoot, excludeStaging = false)
    }

    fun totalBytes(agentRoot: File): Long {
        val workspaces = File(agentRoot, "workspaces")
        if (!workspaces.isDirectory) return 0L
        return workspaces.listFiles().orEmpty().filter { it.isDirectory }.sumOf { workspace ->
            val tasks = File(workspace, "tasks")
            if (!tasks.isDirectory) {
                0L
            } else {
                tasks.listFiles().orEmpty().filter { it.isDirectory }.sumOf { taskRoot ->
                    directoryBytes(taskRoot, excludeStaging = false)
                }
            }
        }
    }

    private fun directoryBytes(directory: File, excludeStaging: Boolean): Long {
        val children = directory.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isDirectory) {
                if (excludeStaging && child.name == AgentWorkspacePaths.STAGING) continue
                total += directoryBytes(child, excludeStaging)
            } else if (child.isFile) {
                total += child.length()
            }
        }
        return total
    }
}
