package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentToolResultStatus
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * One durable terminal result of a workflow tool node.
 *
 * It exists because the tool ledger and the graph checkpoint are separate stores: a process can die
 * after the ledger terminal commit and before the node checkpoint. The receipt is written after the
 * ledger settlement and before the node returns, so a resumed node can reuse the exact result
 * instead of re-dispatching a possibly non-idempotent external effect.
 *
 * [callHash] is the run-independent identity of the logical call (node, tool and resolved
 * arguments). It lets a successor run of the same task reuse the receipt without hijacking a result
 * that belongs to a different call.
 */
data class WorkflowToolReceipt(
    val invocationId: String,
    val toolKey: String,
    val callHash: String,
    val status: AgentToolResultStatus,
    val summary: String,
    val dataJson: String?,
    val artifactIds: List<String>,
    val errorCode: String?,
    val possibleSideEffects: Boolean
) {
    init {
        require(invocationId.isNotBlank()) { "receipt invocation id must not be blank" }
        require(toolKey.isNotBlank()) { "receipt tool key must not be blank" }
        require(callHash.isNotBlank()) { "receipt call hash must not be blank" }
        require(summary.isNotBlank()) { "receipt summary must not be blank" }
        require(artifactIds.size <= MAX_ARTIFACTS) { "receipt has too many artifacts" }
        require(dataJson == null || dataJson.toByteArray(Charsets.UTF_8).size <= MAX_DATA_BYTES) {
            "receipt data exceeds the bounded payload"
        }
    }

    private companion object {
        const val MAX_ARTIFACTS = 20
        const val MAX_DATA_BYTES = 64 * 1024
    }
}

sealed interface WorkflowToolReceiptLoad {
    data object Absent : WorkflowToolReceiptLoad

    data class Loaded(val receipt: WorkflowToolReceipt) : WorkflowToolReceiptLoad

    /** A receipt exists but cannot be used; the node must fail closed, never re-dispatch. */
    data class Corrupt(val safeMessage: String) : WorkflowToolReceiptLoad
}

/**
 * Atomic, bounded file store for workflow tool receipts.
 *
 * Two layouts are read:
 *  * task-scoped (`tasks/<taskKey>/<nodeKey>.json`) — the durable identity across successor runs of
 *    the same task; new writes always use it;
 *  * legacy run-scoped (`<runId>/<stepId>.json`) — read-only fallback for receipts written before
 *    the task-scoped migration.
 *
 * The store never contains model transcripts or credentials; it contains only the bounded tool
 * result already approved for that invocation.
 */
class WorkflowToolReceiptStore(
    rootDirectory: File,
    private val maxReceiptBytes: Long = DEFAULT_MAX_RECEIPT_BYTES
) {
    private val lock = Any()
    private val root: File = File(rootDirectory.absoluteFile, "workflow-tool-receipts").also {
        it.mkdirs()
    }

    init {
        require(maxReceiptBytes in 1..DEFAULT_MAX_RECEIPT_BYTES) { "receipt byte limit is out of range" }
    }

    /** Task-scoped receipt for one stable execution frame/node pair; the primary lookup across runs. */
    fun saveTask(
        taskId: String,
        frameKey: String,
        nodeId: String,
        receipt: WorkflowToolReceipt
    ): Boolean {
        val target = taskFileFor(taskId, frameKey, nodeId) ?: return false
        return write(target, receipt)
    }

    fun loadTask(taskId: String, frameKey: String, nodeId: String): WorkflowToolReceiptLoad {
        val target = taskFileFor(taskId, frameKey, nodeId) ?: return WorkflowToolReceiptLoad.Absent
        return read(target)
    }

    /**
     * Primary-then-legacy lookup for one node. A corrupt primary never falls back to a legacy file:
     * a damaged record must fail closed instead of reviving an older result.
     */
    fun loadNode(
        taskId: String,
        frameKey: String,
        nodeId: String,
        legacyRunId: String,
        legacyStepId: String
    ): WorkflowToolReceiptLoad {
        val primary = loadTask(taskId, frameKey, nodeId)
        if (primary !is WorkflowToolReceiptLoad.Absent) return primary
        return load(legacyRunId, legacyStepId)
    }

    /** Legacy run-scoped layout. New writes go through [saveTask]. */
    fun save(runId: String, stepId: String, receipt: WorkflowToolReceipt): Boolean {
        val target = fileFor(runId, stepId) ?: return false
        return write(target, receipt)
    }

    /** Legacy run-scoped layout. */
    fun load(runId: String, stepId: String): WorkflowToolReceiptLoad {
        val target = fileFor(runId, stepId) ?: return WorkflowToolReceiptLoad.Absent
        return read(target)
    }

    private fun write(target: File, receipt: WorkflowToolReceipt): Boolean {
        val encoded = encode(receipt)
        if (encoded == null || encoded.toByteArray(Charsets.UTF_8).size > maxReceiptBytes) return false
        synchronized(lock) {
            return try {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".tmp")
                temp.writeBytes(encoded.toByteArray(Charsets.UTF_8))
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
                true
            } catch (_: Throwable) {
                target.parentFile?.listFiles()
                    ?.firstOrNull { it.name == target.name + ".tmp" }
                    ?.delete()
                false
            }
        }
    }

    private fun read(target: File): WorkflowToolReceiptLoad {
        synchronized(lock) {
            if (!target.isFile) return WorkflowToolReceiptLoad.Absent
            if (target.length() > maxReceiptBytes) {
                return WorkflowToolReceiptLoad.Corrupt("The stored tool receipt exceeds its byte limit.")
            }
            val text = try {
                target.readText(Charsets.UTF_8)
            } catch (_: Throwable) {
                return WorkflowToolReceiptLoad.Corrupt("The stored tool receipt cannot be read.")
            }
            return decode(text)
        }
    }

    private fun fileFor(runId: String, stepId: String): File? {
        if (!runId.matches(SAFE_KEY) || !stepId.matches(SAFE_KEY)) return null
        val directory = File(root, runId)
        return confinedFile(directory, "$stepId.json")
    }

    private fun taskFileFor(taskId: String, frameKey: String, nodeId: String): File? {
        if (taskId.isBlank() || frameKey.isBlank() || nodeId.isBlank()) return null
        val taskKey = "task-" + AgentHashes.sha256HexUtf8(taskId).take(48)
        val nodeKey = "node-" + AgentHashes.sha256HexUtf8("$frameKey\u0000$nodeId").take(40)
        val directory = File(File(root, "tasks"), taskKey)
        return confinedFile(directory, "$nodeKey.json")
    }

    private fun confinedFile(directory: File, name: String): File? {
        val target = File(directory, name)
        return target.takeIf { it.canonicalFile.parentFile == directory.canonicalFile }
    }

    private fun encode(receipt: WorkflowToolReceipt): String? = try {
        JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("invocationId", receipt.invocationId)
            .put("toolKey", receipt.toolKey)
            .put("callHash", receipt.callHash)
            .put("status", receipt.status.name)
            .put("summary", receipt.summary)
            .put("dataJson", receipt.dataJson ?: JSONObject.NULL)
            .put("artifactIds", JSONArray(receipt.artifactIds))
            .put("errorCode", receipt.errorCode ?: JSONObject.NULL)
            .put("possibleSideEffects", receipt.possibleSideEffects)
            .toString()
    } catch (_: Throwable) {
        null
    }

    private fun decode(text: String): WorkflowToolReceiptLoad = try {
        val json = JSONObject(text)
        if (json.getInt("schemaVersion") != SCHEMA_VERSION) {
            return WorkflowToolReceiptLoad.Corrupt("The stored tool receipt has an unsupported version.")
        }
        val status = AgentToolResultStatus.valueOf(json.getString("status"))
        val artifacts = json.optJSONArray("artifactIds")?.let { array ->
            (0 until array.length()).map { index -> array.getString(index) }
        }.orEmpty()
        WorkflowToolReceiptLoad.Loaded(
            WorkflowToolReceipt(
                invocationId = json.getString("invocationId"),
                toolKey = json.getString("toolKey"),
                callHash = json.getString("callHash"),
                status = status,
                summary = json.getString("summary"),
                dataJson = json.optString("dataJson").takeIf { !json.isNull("dataJson") },
                artifactIds = artifacts,
                errorCode = json.optString("errorCode").takeIf { !json.isNull("errorCode") },
                possibleSideEffects = json.getBoolean("possibleSideEffects")
            )
        )
    } catch (_: Throwable) {
        WorkflowToolReceiptLoad.Corrupt("The stored tool receipt is not a valid record.")
    }

    private companion object {
        // v2 adds the run-independent call hash; a v1 record cannot be verified and fails closed.
        const val SCHEMA_VERSION = 2
        const val DEFAULT_MAX_RECEIPT_BYTES = 80L * 1024L
        val SAFE_KEY = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    }
}
