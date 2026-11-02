package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed [WorkflowEntryReceiptStore].
 *
 * One small JSON file under the private agent root, atomic replace, a bounded row count and a hard
 * byte cap. A corrupt/unreadable file throws instead of being reset, so a double click can never be
 * silently treated as "no previous receipt".
 */
class FileWorkflowEntryReceiptStore(
    rootDirectory: File
) : WorkflowEntryReceiptStore {
    private val root: File = File(rootDirectory.absoluteFile, "workflow-receipts").also { it.mkdirs() }
    private val file = File(root, "receipts.json")
    private val lock = Any()
    private val codec = StrictAgentJsonCodec()

    override suspend fun get(commandId: String): WorkflowEntryReceipt? = synchronized(lock) {
        readAll().firstOrNull { it.commandId == commandId }
    }

    override suspend fun findByRunId(runId: String): WorkflowEntryReceipt? = synchronized(lock) {
        readAll()
            .filter { it.runId == runId }
            .maxByOrNull { it.completedAtMillis }
    }

    override suspend fun put(receipt: WorkflowEntryReceipt) {
        synchronized(lock) {
            val entries = readAll().toMutableList()
            val index = entries.indexOfFirst { it.commandId == receipt.commandId }
            val stored = receipt.copy(idempotentReplay = false)
            if (index >= 0) entries[index] = stored else entries += stored
            writeAll(entries.sortedByDescending { it.completedAtMillis }.take(MAX_ENTRIES))
        }
    }

    override suspend fun list(limit: Int): List<WorkflowEntryReceipt> = synchronized(lock) {
        readAll().sortedByDescending { it.completedAtMillis }.take(limit.coerceAtLeast(0))
    }

    private fun readAll(): List<WorkflowEntryReceipt> {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_BYTES) error("workflow receipt file is oversized")
        val parsed = codec.parse(file.readText(Charsets.UTF_8)) as? AgentJsonParseResult.Parsed
            ?: error("workflow receipt file is not valid JSON")
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap()
            ?: error("workflow receipt file must be an object")
        if ((fields["schema"] as? AgentJsonValue.Num)?.literal?.toIntOrNull() != SCHEMA) {
            error("workflow receipt file has an unsupported schema")
        }
        val rows = (fields["receipts"] as? AgentJsonValue.Arr)?.items
            ?: error("workflow receipt file has no receipts")
        if (rows.size > MAX_ENTRIES) error("workflow receipt file has too many rows")
        return rows.map(::decode)
    }

    private fun writeAll(entries: List<WorkflowEntryReceipt>) {
        val json = AgentJsonValue.Obj(
            listOf(
                "schema" to AgentJsonValue.Num(SCHEMA.toString()),
                "receipts" to AgentJsonValue.Arr(entries.map(::encode))
            )
        )
        val bytes = codec.write(json).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) error("workflow receipt file would be oversized")
        val temp = File(root, "receipts.json.tmp")
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
            error("workflow receipt file could not be written: ${failure.javaClass.simpleName}")
        }
    }

    private fun encode(receipt: WorkflowEntryReceipt): AgentJsonValue = AgentJsonValue.Obj(
        listOfNotNull(
            "commandId" to AgentJsonValue.Str(receipt.commandId),
            "definitionId" to AgentJsonValue.Str(receipt.definitionId),
            "version" to AgentJsonValue.Num(receipt.version.toString()),
            "trigger" to AgentJsonValue.Str(receipt.trigger.name),
            receipt.runId?.let { "runId" to AgentJsonValue.Str(it) },
            receipt.slotMillis?.let { "slotMillis" to AgentJsonValue.Num(it.toString()) },
            "status" to AgentJsonValue.Str(receipt.status.name),
            receipt.errorCode?.let { "errorCode" to AgentJsonValue.Str(it) },
            receipt.safeMessage?.let { "safeMessage" to AgentJsonValue.Str(it) },
            receipt.outputsJson?.let { "outputsJson" to AgentJsonValue.Str(it) },
            "completedAtMillis" to AgentJsonValue.Num(receipt.completedAtMillis.toString())
        )
    )

    private fun decode(value: AgentJsonValue): WorkflowEntryReceipt {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("receipt")
        return WorkflowEntryReceipt(
            commandId = string(fields["commandId"], "commandId"),
            definitionId = string(fields["definitionId"], "definitionId"),
            version = long(fields["version"], "version"),
            trigger = enumValue<WorkflowTriggerKind>(fields["trigger"], "trigger"),
            runId = (fields["runId"] as? AgentJsonValue.Str)?.value,
            slotMillis = optionalLong(fields["slotMillis"], "slotMillis"),
            status = enumValue<WorkflowEntryStatus>(fields["status"], "status"),
            errorCode = (fields["errorCode"] as? AgentJsonValue.Str)?.value,
            safeMessage = (fields["safeMessage"] as? AgentJsonValue.Str)?.value,
            outputsJson = (fields["outputsJson"] as? AgentJsonValue.Str)?.value,
            completedAtMillis = long(fields["completedAtMillis"], "completedAtMillis")
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: AgentJsonValue?, name: String): T {
        val text = string(value, name)
        return enumValues<T>().firstOrNull { it.name == text } ?: invalid(name)
    }

    private fun string(value: AgentJsonValue?, name: String): String =
        (value as? AgentJsonValue.Str)?.value ?: invalid(name)

    private fun long(value: AgentJsonValue?, name: String): Long =
        (value as? AgentJsonValue.Num)?.literal?.toLongOrNull() ?: invalid(name)

    private fun optionalLong(value: AgentJsonValue?, name: String): Long? = when (value) {
        null -> null
        is AgentJsonValue.Num -> value.literal.toLongOrNull() ?: invalid(name)
        else -> invalid(name)
    }

    private fun invalid(name: String): Nothing = error("workflow entry receipt is invalid: $name")

    private companion object {
        const val SCHEMA = 1
        const val MAX_ENTRIES = 256
        const val MAX_BYTES = 512 * 1024
    }
}
