package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointJson
import com.muyuchat.core.agent.runtime.workflow.WorkflowCheckpointLoad
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint
import com.muyuchat.mca.agent.data.AgentTransactionRunner
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointDao
import com.muyuchat.mca.agent.data.AgentWorkflowCheckpointEntity
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * Room-backed workflow frame store (N19 follow-up).
 *
 * The graph runner's sink is synchronous by contract, so this adapter blocks the current coroutine
 * on the shared Room transaction runner. Workflow execution already runs on the Agent scope (never
 * the main thread) and the previous file store blocked on disk in the same places; the CAS and
 * payload validation stay identical.
 *
 * - a primary row is authoritative; a corrupt row is a typed rejection, never a silent fallback;
 * - a missing primary row falls back to the legacy run-id row and then to the legacy file document,
 *   importing that frame once so the next save CASes on Room;
 * - `save` replaces only with a strictly newer revision and re-encodes under the 512 KiB bound.
 */
class RoomWorkflowCheckpointStore(
    private val dao: AgentWorkflowCheckpointDao,
    private val transactions: AgentTransactionRunner,
    private val key: String,
    private val legacyKey: String? = null,
    private val legacyFiles: FileWorkflowCheckpointStore? = null,
    private val clock: () -> Long = System::currentTimeMillis
) : WorkflowCheckpointStore {
    init {
        require(key.matches(SAFE_KEY)) { "checkpoint key is not a safe file name" }
        if (legacyKey != null) {
            require(legacyKey.matches(SAFE_KEY)) { "legacy checkpoint key is not a safe file name" }
        }
    }

    override fun load(definition: WorkflowDefinition): WorkflowCheckpointLoad = runBlocking {
        loadSuspend(definition)
    }

    override fun save(checkpoint: WorkflowRunCheckpoint): Boolean = runBlocking {
        saveSuspend(checkpoint)
    }

    private suspend fun loadSuspend(definition: WorkflowDefinition): WorkflowCheckpointLoad {
        val primary = dao.find(key)
        if (primary != null) {
            val decoded = primary.toCheckpoint()
                ?: return corrupt("The stored checkpoint is not valid.")
            return validate(decoded, definition)
        }
        val legacyRoom = legacyKey
            ?.takeIf { it != key }
            ?.let { dao.find(it) }
        if (legacyRoom != null) {
            val decoded = legacyRoom.toCheckpoint()
                ?: return corrupt("The legacy checkpoint row is not valid.")
            importIntoPrimary(decoded)
            return validate(decoded, definition)
        }
        val files = legacyFiles ?: return WorkflowCheckpointLoad.Absent
        return when (val loaded = files.load(definition)) {
            is WorkflowCheckpointLoad.Loaded -> {
                importIntoPrimary(loaded.checkpoint)
                loaded
            }

            is WorkflowCheckpointLoad.Rejected -> loaded
            WorkflowCheckpointLoad.Absent -> WorkflowCheckpointLoad.Absent
        }
    }

    private suspend fun saveSuspend(checkpoint: WorkflowRunCheckpoint): Boolean =
        transactions.inTransaction {
            val existing = dao.find(key)
            if (existing != null) {
                val decoded = existing.toCheckpoint()
                    ?: throw IllegalStateException("the stored checkpoint is corrupt")
                if (checkpoint.revision <= decoded.revision) return@inTransaction false
            }
            val json = WorkflowCheckpointJson.encode(checkpoint)
            if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
                throw IllegalStateException("the offered checkpoint is oversized")
            }
            dao.upsert(checkpoint.toEntity(json))
            true
        }

    private suspend fun importIntoPrimary(checkpoint: WorkflowRunCheckpoint) {
        try {
            transactions.inTransaction {
                if (dao.find(key) == null) {
                    dao.upsert(
                        checkpoint.toEntity(WorkflowCheckpointJson.encode(checkpoint))
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // The legacy frame still serves this load; a later save will retry the import.
        }
    }

    private fun validate(
        checkpoint: WorkflowRunCheckpoint,
        definition: WorkflowDefinition
    ): WorkflowCheckpointLoad =
        if (checkpoint.definitionId != definition.definitionId ||
            checkpoint.definitionVersion != definition.version
        ) {
            WorkflowCheckpointLoad.Rejected(
                "WF_CHECKPOINT_MISMATCH",
                "The stored checkpoint belongs to another definition version."
            )
        } else {
            WorkflowCheckpointLoad.Loaded(checkpoint)
        }

    private fun corrupt(message: String): WorkflowCheckpointLoad.Rejected =
        WorkflowCheckpointLoad.Rejected("WF_CHECKPOINT_CORRUPT", message)

    private fun AgentWorkflowCheckpointEntity.toCheckpoint(): WorkflowRunCheckpoint? =
        runCatching { WorkflowCheckpointJson.decode(payloadJson) }.getOrNull()

    private fun WorkflowRunCheckpoint.toEntity(json: String): AgentWorkflowCheckpointEntity =
        AgentWorkflowCheckpointEntity(
            frameKey = key,
            revision = revision,
            definitionId = definitionId,
            definitionVersion = definitionVersion,
            payloadJson = json,
            updatedAtMillis = clock()
        )

    private companion object {
        const val MAX_BYTES = 512 * 1024
        val SAFE_KEY = Regex("^[A-Za-z0-9_-]{1,96}$")
    }
}

/** Production factory: Room rows with a legacy file document as the only fallback source. */
class RoomWorkflowCheckpointStoreFactory(
    private val dao: AgentWorkflowCheckpointDao,
    private val transactions: AgentTransactionRunner,
    private val legacyRoot: File,
    private val clock: () -> Long = System::currentTimeMillis
) : WorkflowCheckpointStoreFactory {
    override fun create(key: String, legacyKey: String?): WorkflowCheckpointStore =
        RoomWorkflowCheckpointStore(
            dao = dao,
            transactions = transactions,
            key = key,
            legacyKey = legacyKey,
            legacyFiles = runCatching {
                FileWorkflowCheckpointStore(
                    rootDirectory = legacyRoot,
                    key = key,
                    legacyKey = legacyKey
                )
            }.getOrNull(),
            clock = clock
        )
}
