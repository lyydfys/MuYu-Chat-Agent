package com.muyuchat.mca.agent.extensions.skills

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.UUID

/** One validated source file handed to the snapshot repository. [open] must return a fresh stream. */
class AgentSkillSourceFile(
    val relativePath: String,
    val sizeBytes: Long,
    val open: () -> InputStream
) {
    init {
        require(AgentSkillPathPolicy.isNormalizedRelativeFile(relativePath)) {
            "source file path is not a normalized relative path: '$relativePath'"
        }
        require(sizeBytes >= 0L && sizeBytes <= AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES) {
            "source file size is not bounded"
        }
    }
}

data class AgentSkillStoredFile(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String
)

data class AgentSkillStoredSnapshot(
    val snapshotId: String,
    val skillId: String,
    val version: Long,
    val sha256: String,
    val files: List<AgentSkillStoredFile>
) {
    init {
        require(snapshotId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,79}$"))) { "stored snapshot id is invalid" }
        require(sha256.matches(Regex("^[0-9a-f]{64}$"))) { "stored snapshot sha256 is invalid" }
    }
}

/**
 * Narrow persistence port for installed Skill manifests. The Lead wires a Room/prefs adapter; the
 * V1 tests use [InMemoryAgentSkillStore]. Implementations must be atomic per call and reject a
 * stale [update] with [AgentSkillErrorCode.VERSION_CONFLICT].
 */
interface AgentSkillStore {
    fun list(): List<AgentSkillManifest>
    fun find(skillId: String): AgentSkillManifest?
    fun create(manifest: AgentSkillManifest): AgentSkillManifest
    fun update(manifest: AgentSkillManifest, expectedVersion: Long): AgentSkillManifest
    fun setEnabled(skillId: String, enabled: Boolean): AgentSkillManifest
    fun delete(skillId: String): AgentSkillManifest
}

/** Writes private immutable file snapshots. Implementations must never follow a path outside root. */
interface AgentSkillSnapshotRepository {
    fun create(skillId: String, version: Long, files: List<AgentSkillSourceFile>): AgentSkillStoredSnapshot

    fun delete(snapshotId: String)

    fun exists(snapshotId: String): Boolean
}

/** Answers whether a historical run still references a snapshot; referenced snapshots are retained. */
fun interface AgentSkillSnapshotReferencePort {
    fun isSnapshotReferenced(snapshotId: String): Boolean

    companion object {
        val NONE = AgentSkillSnapshotReferencePort { false }
    }
}

enum class AgentSkillRevocationReason { DISABLED, UNINSTALLED }

/** Typed revocation signal. The integrator must invalidate pending approvals and bump policyVersion. */
data class AgentSkillRevocation(
    val skillId: String,
    val version: Long,
    val reason: AgentSkillRevocationReason,
    val declaredToolPatterns: List<String>
)

fun interface AgentSkillRevocationPort {
    fun onRevoked(revocation: AgentSkillRevocation)

    companion object {
        val NO_OP = AgentSkillRevocationPort { }
    }
}

/** Minimal thread-safe store for tests, previews and composition until persistence is wired. */
class InMemoryAgentSkillStore : AgentSkillStore {
    private val lock = Any()
    private val manifests = LinkedHashMap<String, AgentSkillManifest>()

    override fun list(): List<AgentSkillManifest> = synchronized(lock) { manifests.values.toList() }

    override fun find(skillId: String): AgentSkillManifest? = synchronized(lock) { manifests[skillId] }

    override fun create(manifest: AgentSkillManifest): AgentSkillManifest = synchronized(lock) {
        if (manifests.containsKey(manifest.id)) {
            skillFailure(AgentSkillErrorCode.VERSION_CONFLICT, "skill '${manifest.id}' is already installed")
        }
        manifests[manifest.id] = manifest
        manifest
    }

    override fun update(manifest: AgentSkillManifest, expectedVersion: Long): AgentSkillManifest =
        synchronized(lock) {
            val current = manifests[manifest.id] ?: skillFailure(
                AgentSkillErrorCode.SKILL_NOT_FOUND,
                "skill '${manifest.id}' is not installed"
            )
            if (current.version != expectedVersion) {
                skillFailure(
                    AgentSkillErrorCode.VERSION_CONFLICT,
                    "skill '${manifest.id}' changed from expected version $expectedVersion"
                )
            }
            manifests[manifest.id] = manifest
            manifest
        }

    override fun setEnabled(skillId: String, enabled: Boolean): AgentSkillManifest = synchronized(lock) {
        val current = manifests[skillId] ?: skillFailure(
            AgentSkillErrorCode.SKILL_NOT_FOUND,
            "skill '$skillId' is not installed"
        )
        val updated = current.copy(enabled = enabled)
        manifests[skillId] = updated
        updated
    }

    override fun delete(skillId: String): AgentSkillManifest = synchronized(lock) {
        manifests.remove(skillId) ?: skillFailure(
            AgentSkillErrorCode.SKILL_NOT_FOUND,
            "skill '$skillId' is not installed"
        )
    }
}

/** Computes the deterministic MCA content hash and per-file hashes shared by preview and repository. */
internal object AgentSkillContentHash {
    fun digest(files: List<AgentSkillSourceFile>): String = digestWithDetails(files).first

    fun digestWithDetails(files: List<AgentSkillSourceFile>): Pair<String, List<AgentSkillStoredFile>> {
        val sorted = files.sortedBy { it.relativePath }
        val snapshotDigest = AgentSkillHash.newDigest()
        snapshotDigest.update("MCA-SKILL-SNAPSHOT\n".toByteArray(Charsets.UTF_8))
        val stored = ArrayList<AgentSkillStoredFile>(sorted.size)
        for (file in sorted) {
            val fileDigest = AgentSkillHash.newDigest()
            var total = 0L
            file.open().use { input ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > file.sizeBytes || total > AgentSkillLimits.MAX_SINGLE_ENTRY_BYTES) {
                        skillFailure(
                            AgentSkillErrorCode.STORE_FAILURE,
                            "source file '${file.relativePath}' grew beyond its validated size"
                        )
                    }
                    fileDigest.update(buffer, 0, read)
                    snapshotDigest.update(buffer, 0, read)
                }
            }
            if (total != file.sizeBytes) {
                skillFailure(
                    AgentSkillErrorCode.STORE_FAILURE,
                    "source file '${file.relativePath}' size changed during import"
                )
            }
            val fileHash = AgentSkillHash.hex(fileDigest.digest())
            stored += AgentSkillStoredFile(file.relativePath, total, fileHash)
            snapshotDigest.update(file.relativePath.toByteArray(Charsets.UTF_8))
            snapshotDigest.update(0.toByte())
            snapshotDigest.update(le64(total))
        }
        return AgentSkillHash.hex(snapshotDigest.digest()) to stored
    }

    private fun le64(value: Long): ByteArray = ByteArray(8) { index ->
        ((value ushr (8 * index)) and 0xFFL).toByte()
    }

    const val COPY_BUFFER: Int = 16 * 1024
}

/**
 * Writes immutable snapshots under a private root. Content is staged, hashed, made read-only and
 * atomically renamed to `<skillId>-v<version>`; existing versions are never overwritten.
 */
class FileAgentSkillSnapshotRepository(rootDirectory: File) : AgentSkillSnapshotRepository {
    private val root = rootDirectory.absoluteFile

    init {
        if (!root.isDirectory && !root.mkdirs()) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "cannot create the private Skill snapshot root")
        }
    }

    override fun create(
        skillId: String,
        version: Long,
        files: List<AgentSkillSourceFile>
    ): AgentSkillStoredSnapshot {
        require(version >= 1L) { "snapshot version must be positive" }
        if (!skillId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,63}$"))) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "invalid skill id for snapshot storage")
        }
        val snapshotId = "$skillId-v$version"
        val target = File(root, snapshotId)
        if (target.exists()) {
            skillFailure(AgentSkillErrorCode.VERSION_CONFLICT, "snapshot '$snapshotId' already exists")
        }
        if (files.isEmpty()) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "a Skill snapshot must contain SKILL.md")
        }
        val staging = File(root, ".staging-${UUID.randomUUID()}")
        try {
            val (sha256, storedFiles) = writeStaging(staging, files)
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(staging.toPath(), target.toPath())
            }
            makeReadOnly(target)
            return AgentSkillStoredSnapshot(
                snapshotId = snapshotId,
                skillId = skillId,
                version = version,
                sha256 = sha256,
                files = Collections.unmodifiableList(storedFiles)
            )
        } catch (failure: AgentSkillException) {
            staging.deleteRecursivelyWithWritable()
            throw failure
        } catch (failure: Exception) {
            staging.deleteRecursivelyWithWritable()
            throw AgentSkillException(
                AgentSkillError(
                    AgentSkillErrorCode.STORE_FAILURE,
                    "could not write the private Skill snapshot"
                ),
                failure
            )
        }
    }

    override fun delete(snapshotId: String) {
        if (!snapshotId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,79}$"))) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "refusing to delete an invalid snapshot id")
        }
        val target = File(root, snapshotId)
        if (target.parentFile?.absoluteFile != root) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "snapshot path escaped the private root")
        }
        if (target.exists()) target.deleteRecursivelyWithWritable()
    }

    override fun exists(snapshotId: String): Boolean =
        snapshotId.matches(Regex("^[a-z0-9][a-z0-9._-]{0,79}$")) && File(root, snapshotId).isDirectory

    private fun writeStaging(
        staging: File,
        files: List<AgentSkillSourceFile>
    ): Pair<String, List<AgentSkillStoredFile>> {
        if (!staging.mkdirs()) {
            skillFailure(AgentSkillErrorCode.STORE_FAILURE, "cannot create the Skill snapshot staging directory")
        }
        val sorted = files.sortedBy { it.relativePath }
        val snapshotDigest = AgentSkillHash.newDigest()
        snapshotDigest.update("MCA-SKILL-SNAPSHOT\n".toByteArray(Charsets.UTF_8))
        val stored = ArrayList<AgentSkillStoredFile>(sorted.size)
        for (file in sorted) {
            if (!AgentSkillPathPolicy.isNormalizedRelativeFile(file.relativePath)) {
                skillFailure(AgentSkillErrorCode.STORE_FAILURE, "refusing to write a non-normalized snapshot path")
            }
            val destination = File(staging, file.relativePath)
            check(destination.canonicalFile.toPath().startsWith(staging.canonicalFile.toPath())) {
                "snapshot destination escaped the staging root"
            }
            destination.parentFile?.mkdirs()
            val fileDigest = AgentSkillHash.newDigest()
            var total = 0L
            file.open().use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(AgentSkillContentHash.COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > file.sizeBytes) {
                            skillFailure(
                                AgentSkillErrorCode.STORE_FAILURE,
                                "source file '${file.relativePath}' grew beyond its validated size"
                            )
                        }
                        output.write(buffer, 0, read)
                        fileDigest.update(buffer, 0, read)
                        snapshotDigest.update(buffer, 0, read)
                    }
                }
            }
            if (total != file.sizeBytes) {
                skillFailure(
                    AgentSkillErrorCode.STORE_FAILURE,
                    "source file '${file.relativePath}' size changed during import"
                )
            }
            val fileHash = AgentSkillHash.hex(fileDigest.digest())
            stored += AgentSkillStoredFile(file.relativePath, total, fileHash)
            snapshotDigest.update(file.relativePath.toByteArray(Charsets.UTF_8))
            snapshotDigest.update(0.toByte())
            snapshotDigest.update(ByteArray(8) { index -> ((total ushr (8 * index)) and 0xFFL).toByte() })
        }
        return AgentSkillHash.hex(snapshotDigest.digest()) to stored
    }

    private fun makeReadOnly(directory: File) {
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) makeReadOnly(child) else child.setWritable(false, false)
        }
        directory.setWritable(false, false)
    }

    private fun File.deleteRecursivelyWithWritable() {
        if (!exists()) return
        walkBottomUp().forEach { it.setWritable(true, true) }
        deleteRecursively()
    }
}
