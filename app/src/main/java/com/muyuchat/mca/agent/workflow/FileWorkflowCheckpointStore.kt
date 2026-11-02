package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointJson
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSink
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointSource
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed checkpoint store for one execution key (normally a run id).
 *
 * This is the pre-Room persistence seam for N19: it provides a strict, atomic CAS on the checkpoint
 * revision so an in-flight execution cannot be overwritten by a stale writer. The real owner will
 * replace this with a Room transaction that also writes the node fact/outbox; until then this store
 * is the testable durable form.
 *
 * - `save` returns false only when the stored revision is >= the offered one (CAS loss);
 * - a corrupt/unreadable file makes `save` throw (the runner maps it to `WF_CHECKPOINT_FAILED`);
 * - `load` maps corrupt content or a different definition version to a typed Rejected result.
 */
/**
 * Stable checkpoint keys across successor runs of the same task.
 *
 * A `continueTask` creates a new run for the same task. The task root key lets that successor load
 * the predecessor frame; the run id remains the read-only legacy fallback and the invocation/ledger
 * step identity stays run-scoped.
 */
internal object WorkflowCheckpointKeys {
    fun taskRoot(taskId: String): String =
        "wftask-" + AgentHashes.sha256HexUtf8(taskId).take(48)
}

class FileWorkflowCheckpointStore(
    rootDirectory: File,
    private val key: String,
    /**
     * Legacy read-only key from before task-scoped checkpoints (normally the run id).
     *
     * New writes only use [key]; a missing primary file may fall back to this key so a run created
     * before the migration still resumes. A present but corrupt primary file never falls back.
     */
    legacyKey: String? = null
) : WorkflowCheckpointStore {
    private val lock = Any()
    private val root: File = File(rootDirectory.absoluteFile, "workflow-checkpoints").also { it.mkdirs() }
    private val file: File = File(root, "$key.json").also { target ->
        require(target.canonicalFile.parentFile == root.canonicalFile) {
            "checkpoint file must stay inside the private root"
        }
    }
    private val legacyFile: File? = legacyKey?.takeIf { it != key }?.let { legacy ->
        require(legacy.matches(KEY_PATTERN)) { "legacy checkpoint key is not a safe file name" }
        File(root, "$legacy.json").also { target ->
            require(target.canonicalFile.parentFile == root.canonicalFile) {
                "legacy checkpoint file must stay inside the private root"
            }
        }
    }

    init {
        require(key.matches(KEY_PATTERN)) {
            "checkpoint key is not a safe file name"
        }
        if (!root.isDirectory) {
            error("checkpoint root is unavailable")
        }
    }

    override fun load(definition: WorkflowDefinition): WorkflowCheckpointLoad = synchronized(lock) {
        val primary = readCheckpoint(file, definition)
        if (primary !is WorkflowCheckpointLoad.Absent) return primary
        if (legacyFile == null) return WorkflowCheckpointLoad.Absent
        readCheckpoint(legacyFile, definition)
    }

    private fun readCheckpoint(
        source: File,
        definition: WorkflowDefinition
    ): WorkflowCheckpointLoad {
        if (!source.isFile) return WorkflowCheckpointLoad.Absent
        if (source.length() > MAX_BYTES) {
            return WorkflowCheckpointLoad.Rejected(
                "WF_CHECKPOINT_CORRUPT",
                "The stored checkpoint exceeds its byte limit."
            )
        }
        val text = try {
            source.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            return WorkflowCheckpointLoad.Rejected(
                "WF_CHECKPOINT_CORRUPT",
                "The stored checkpoint could not be read."
            )
        }
        val checkpoint = WorkflowCheckpointJson.decode(text)
            ?: return WorkflowCheckpointLoad.Rejected(
                "WF_CHECKPOINT_CORRUPT",
                "The stored checkpoint is not valid."
            )
        if (checkpoint.definitionId != definition.definitionId ||
            checkpoint.definitionVersion != definition.version
        ) {
            return WorkflowCheckpointLoad.Rejected(
                "WF_CHECKPOINT_MISMATCH",
                "The stored checkpoint belongs to another definition version."
            )
        }
        return WorkflowCheckpointLoad.Loaded(checkpoint)
    }

    override fun save(checkpoint: WorkflowRunCheckpoint): Boolean = synchronized(lock) {
        val existing = if (file.isFile) {
            if (file.length() > MAX_BYTES) {
                throw IllegalStateException("the stored checkpoint is oversized")
            }
            WorkflowCheckpointJson.decode(file.readText(Charsets.UTF_8))
                ?: throw IllegalStateException("the stored checkpoint is corrupt")
        } else {
            null
        }
        if (existing != null && checkpoint.revision <= existing.revision) {
            return false
        }
        val text = WorkflowCheckpointJson.encode(checkpoint)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) {
            throw IllegalStateException("the offered checkpoint is oversized")
        }
        val temp = File(root, "$key.json.tmp")
        try {
            temp.writeBytes(bytes)
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (failure: Exception) {
            temp.delete()
            throw IllegalStateException("checkpoint write failed", failure)
        }
        true
    }

    private companion object {
        const val MAX_BYTES = 512 * 1024
        val KEY_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}
