package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * File-backed bounded run history with atomic replace and strict fail-closed decoding.
 *
 * At most [MAX_ENTRIES] newest rows survive; a corrupt file throws instead of being reset, so an
 * operator sees the damage rather than silently losing run evidence.
 */
class FileWorkflowRunHistoryStore(
    rootDirectory: File
) : WorkflowRunHistoryStore {
    private val root: File = File(rootDirectory.absoluteFile, "workflow-runs").also { it.mkdirs() }
    private val file = File(root, "history.json")
    private val lock = Any()
    private val codec = StrictAgentJsonCodec()

    override suspend fun put(entry: WorkflowRunHistoryEntry): Unit = synchronized(lock) {
        val entries = readAll().toMutableList()
        val index = entries.indexOfFirst { it.runId == entry.runId }
        if (index >= 0) entries[index] = entry else entries += entry
        val bounded = entries.sortedByDescending { it.startedAtMillis }.take(MAX_ENTRIES)
        writeAll(bounded)
    }

    override suspend fun list(limit: Int): List<WorkflowRunHistoryEntry> = synchronized(lock) {
        readAll().sortedByDescending { it.startedAtMillis }.take(limit.coerceAtLeast(0))
    }

    private fun readAll(): List<WorkflowRunHistoryEntry> {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_BYTES) error("workflow history file is oversized")
        val parsed = codec.parse(file.readText(Charsets.UTF_8)) as? AgentJsonParseResult.Parsed
            ?: error("workflow history file is not valid JSON")
        val fields = (parsed.value as? AgentJsonValue.Obj)?.fields?.toMap()
            ?: error("workflow history file must be an object")
        if ((fields["schema"] as? AgentJsonValue.Num)?.literal?.toIntOrNull() != SCHEMA) {
            error("workflow history file has an unsupported schema")
        }
        val rows = (fields["runs"] as? AgentJsonValue.Arr)?.items
            ?: error("workflow history file has no runs")
        if (rows.size > MAX_ENTRIES + 8) error("workflow history file has too many rows")
        return rows.map(::decodeEntry)
    }

    private fun writeAll(entries: List<WorkflowRunHistoryEntry>) {
        val json = AgentJsonValue.Obj(
            listOf(
                "schema" to AgentJsonValue.Num(SCHEMA.toString()),
                "runs" to AgentJsonValue.Arr(entries.map(::encodeEntry))
            )
        )
        val bytes = codec.write(json).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) error("workflow history file would be oversized")
        val temp = File(root, "history.json.tmp")
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
            error("workflow history file could not be written: ${failure.javaClass.simpleName}")
        }
    }

    private fun encodeEntry(entry: WorkflowRunHistoryEntry): AgentJsonValue = AgentJsonValue.Obj(
        listOfNotNull(
            "runId" to AgentJsonValue.Str(entry.runId),
            "definitionId" to AgentJsonValue.Str(entry.definitionId),
            "version" to AgentJsonValue.Num(entry.version.toString()),
            "trigger" to AgentJsonValue.Str(entry.trigger.name),
            entry.slotMillis?.let { "slotMillis" to AgentJsonValue.Num(it.toString()) },
            "startedAtMillis" to AgentJsonValue.Num(entry.startedAtMillis.toString()),
            entry.finishedAtMillis?.let { "finishedAtMillis" to AgentJsonValue.Num(it.toString()) },
            "status" to AgentJsonValue.Str(entry.status.name),
            entry.errorCode?.let { "errorCode" to AgentJsonValue.Str(it) }
        )
    )

    private fun decodeEntry(value: AgentJsonValue): WorkflowRunHistoryEntry {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("entry")
        val trigger = enumValue<WorkflowTriggerKind>(fields["trigger"], "trigger")
        val status = enumValue<WorkflowRunStatus>(fields["status"], "status")
        return WorkflowRunHistoryEntry(
            runId = string(fields["runId"], "runId"),
            definitionId = string(fields["definitionId"], "definitionId"),
            version = long(fields["version"], "version"),
            trigger = trigger,
            slotMillis = optionalLong(fields["slotMillis"], "slotMillis"),
            startedAtMillis = long(fields["startedAtMillis"], "startedAtMillis"),
            finishedAtMillis = optionalLong(fields["finishedAtMillis"], "finishedAtMillis"),
            status = status,
            errorCode = (fields["errorCode"] as? AgentJsonValue.Str)?.value
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

    private fun invalid(name: String): Nothing = error("workflow history entry is invalid: $name")

    private companion object {
        const val SCHEMA = 1
        const val MAX_ENTRIES = 200
        const val MAX_BYTES = 512 * 1024
    }
}
