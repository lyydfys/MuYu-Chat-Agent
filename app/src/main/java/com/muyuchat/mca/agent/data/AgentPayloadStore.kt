package com.muyuchat.mca.agent.data

import java.io.File
import java.util.UUID

/**
 * Bounded, typed payload references for transcripts, tool results, frozen arguments and import
 * manifests (architecture 10.1).
 *
 * A ref embeds the payload kind, SHA-256 and byte size, e.g.
 * `payloads/tool_result/<sha256>.<size>.v1.pay`. A reference that has no real file is never reported
 * as usable: [read] returns null, and a present-but-corrupt file raises `STORAGE_CORRUPTION`.
 */
data class AgentPayloadRef(
    val ref: String,
    val kind: String,
    val sha256: String,
    val sizeBytes: Long,
    val schemaVersion: Int = 1
) {
    companion object {
        private val pattern = Regex(
            "^payloads/([a-z][a-z0-9_-]{0,31})/([0-9a-f]{64})\\.(\\d{1,12})\\.v(\\d{1,3})\\.pay$"
        )

        fun parseOrNull(ref: String): AgentPayloadRef? {
            val match = pattern.matchEntire(ref) ?: return null
            return AgentPayloadRef(
                ref = ref,
                kind = match.groupValues[1],
                sha256 = match.groupValues[2],
                sizeBytes = match.groupValues[3].toLongOrNull() ?: return null,
                schemaVersion = match.groupValues[4].toIntOrNull() ?: return null
            )
        }
    }
}

class AgentPayloadStore(
    private val agentRoot: File,
    private val clock: AgentClock = AgentClock.system(),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() }
) {
    fun write(
        workspaceId: String,
        taskId: String,
        kind: String,
        bytes: ByteArray
    ): AgentPayloadRef {
        require(kind.matches(Regex("^[a-z][a-z0-9_-]{0,31}$"))) { "payload kind is not a stable lower-case id" }
        if (bytes.size > AgentDataLimits.MAX_PAYLOAD_BYTES) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_NO_SPACE,
                "payload exceeds ${AgentDataLimits.MAX_PAYLOAD_BYTES} bytes"
            )
        }
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = true)
        val kindDirectory = File(taskRoot, "${AgentWorkspacePaths.PAYLOADS}/$kind")
        ensureDirectory(kindDirectory)
        val sha256 = AgentCommandFingerprint.sha256Hex(bytes)
        val ref = "payloads/$kind/$sha256.${bytes.size}.v1.pay"
        val target = File(taskRoot, ref)
        if (target.isFile) {
            return AgentPayloadRef(ref, kind, sha256, bytes.size.toLong())
        }
        val staging = File(taskRoot, "${AgentWorkspacePaths.STAGING}/payload-$kind-${idGenerator()}.part")
        try {
            AgentFileIo.writeBytes(staging, bytes)
            try {
                AgentFileIo.atomicMove(staging, target)
            } catch (error: java.io.IOException) {
                // Another writer may have published the identical immutable payload first.
                if (!target.isFile) throw error
            }
            AgentFileIo.fsyncDirectory(kindDirectory)
        } finally {
            AgentFileIo.deleteQuietly(staging)
        }
        return AgentPayloadRef(ref, kind, sha256, bytes.size.toLong())
    }

    /** Returns null when the file does not exist; never pretends a missing ref is usable. */
    fun readNullable(workspaceId: String, taskId: String, ref: String): ByteArray? {
        val parsed = AgentPayloadRef.parseOrNull(ref)
            ?: throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "payload ref is malformed")
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = false)
        val file = File(taskRoot, parsed.ref)
        if (!file.isFile) return null
        val canonicalRoot = taskRoot.canonicalFile
        if (!file.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_CORRUPTION, "payload ref escapes the task root")
        }
        val bytes = AgentFileIo.readBounded(file, AgentDataLimits.MAX_PAYLOAD_BYTES.toLong())
        if (bytes.size.toLong() != parsed.sizeBytes ||
            AgentCommandFingerprint.sha256Hex(bytes) != parsed.sha256
        ) {
            throw AgentStorageException(
                AgentDataErrorCodes.STORAGE_CORRUPTION,
                "payload content does not match its hash/size"
            )
        }
        return bytes
    }

    fun exists(workspaceId: String, taskId: String, ref: String): Boolean {
        val parsed = AgentPayloadRef.parseOrNull(ref) ?: return false
        val taskRoot = AgentWorkspacePaths.taskRoot(agentRoot, workspaceId, taskId, create = false)
        return File(taskRoot, parsed.ref).isFile
    }

    private fun ensureDirectory(directory: File) {
        if (directory.exists()) {
            if (!directory.isDirectory || java.nio.file.Files.isSymbolicLink(directory.toPath())) {
                throw AgentStorageException(
                    AgentDataErrorCodes.STORAGE_CORRUPTION,
                    "payload directory is not a regular directory"
                )
            }
            return
        }
        if (!directory.mkdirs()) {
            throw AgentStorageException(AgentDataErrorCodes.STORAGE_NO_SPACE, "cannot create payload directory")
        }
    }
}
