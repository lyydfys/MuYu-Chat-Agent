package com.muyuchat.core.agent.runtime.journal

import com.muyuchat.core.agent.contract.ToolInvocationStatus
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec

/**
 * Canonical JSON for the dispatch outbox document (N03/N19).
 *
 * The runtime owns the encoding so a file/Room adapter never reconstructs dispatch facts from
 * ad-hoc JSON. Decoding is strict: a missing mandatory key, an unknown status, a duplicate key or a
 * constructor invariant violation returns null. Callers must treat null as corrupt (fail closed),
 * never as an empty outbox.
 */
object AgentDispatchOutboxJson {
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
    private const val SCHEMA_VERSION = 1L

    fun encode(entries: Collection<AgentDispatchOutboxEntry>): String = codec.write(
        AgentJsonValue.Obj(
            listOf(
                "schemaVersion" to AgentJsonValue.Num(SCHEMA_VERSION.toString()),
                "entries" to AgentJsonValue.Arr(
                    entries.sortedBy { it.idempotencyKey }.map(::encodeEntry)
                )
            )
        )
    )

    fun decode(text: String): List<AgentDispatchOutboxEntry>? {
        val parsed = codec.parse(text) as? AgentJsonParseResult.Parsed ?: return null
        val root = parsed.value as? AgentJsonValue.Obj ?: return null
        val fields = root.fields.toMap()
        val version = (fields["schemaVersion"] as? AgentJsonValue.Num)?.literal?.toLongOrNull()
            ?: return null
        if (version != SCHEMA_VERSION) return null
        val items = (fields["entries"] as? AgentJsonValue.Arr)?.items ?: return null
        val entries = ArrayList<AgentDispatchOutboxEntry>(items.size)
        for (item in items) {
            entries += decodeEntry(item) ?: return null
        }
        if (entries.map { it.idempotencyKey }.distinct().size != entries.size) return null
        return entries
    }

    private fun encodeEntry(entry: AgentDispatchOutboxEntry): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "idempotencyKey" to AgentJsonValue.Str(entry.idempotencyKey),
            "invocationId" to AgentJsonValue.Str(entry.invocationId),
            "runId" to AgentJsonValue.Str(entry.runId),
            "stepId" to AgentJsonValue.Str(entry.stepId),
            "callOrdinal" to AgentJsonValue.Num(entry.callOrdinal.toString()),
            "toolKey" to AgentJsonValue.Str(entry.toolKey),
            "toolVersion" to AgentJsonValue.Str(entry.toolVersion),
            "approvedAtMillis" to AgentJsonValue.Num(entry.approvedAtMillis.toString()),
            "status" to AgentJsonValue.Str(entry.status.name),
            "leaseOwner" to nullableStr(entry.leaseOwner),
            "leaseExpiresAtMillis" to AgentJsonValue.Num(entry.leaseExpiresAtMillis.toString()),
            "dispatchAttempts" to AgentJsonValue.Num(entry.dispatchAttempts.toString()),
            "settledInvocationStatus" to nullableStr(entry.settledInvocationStatus?.name),
            "errorCode" to nullableStr(entry.errorCode),
            "updatedAtMillis" to AgentJsonValue.Num(entry.updatedAtMillis.toString()),
            "revision" to AgentJsonValue.Num(entry.revision.toString())
        )
    )

    private fun decodeEntry(value: AgentJsonValue): AgentDispatchOutboxEntry? {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: return null
        val idempotencyKey = str(fields, "idempotencyKey") ?: return null
        val invocationId = str(fields, "invocationId") ?: return null
        val runId = str(fields, "runId") ?: return null
        val stepId = str(fields, "stepId") ?: return null
        val callOrdinal = num(fields, "callOrdinal") ?: return null
        val toolKey = str(fields, "toolKey") ?: return null
        val toolVersion = str(fields, "toolVersion") ?: return null
        val approvedAtMillis = num(fields, "approvedAtMillis") ?: return null
        val status = status(fields, "status") ?: return null
        val leaseOwner = optionalStr(fields, "leaseOwner")
        val leaseExpiresAtMillis = num(fields, "leaseExpiresAtMillis") ?: return null
        val dispatchAttempts = num(fields, "dispatchAttempts") ?: return null
        val settledStatus = optionalStr(fields, "settledInvocationStatus")?.let { name ->
            ToolInvocationStatus.entries.firstOrNull { it.name == name }
        }
        val errorCode = optionalStr(fields, "errorCode")
        val updatedAtMillis = num(fields, "updatedAtMillis") ?: return null
        val revision = num(fields, "revision") ?: return null
        return runCatching {
            AgentDispatchOutboxEntry(
                idempotencyKey = idempotencyKey,
                invocationId = invocationId,
                runId = runId,
                stepId = stepId,
                callOrdinal = callOrdinal.toInt(),
                toolKey = toolKey,
                toolVersion = toolVersion,
                approvedAtMillis = approvedAtMillis,
                status = status,
                leaseOwner = leaseOwner,
                leaseExpiresAtMillis = leaseExpiresAtMillis,
                dispatchAttempts = dispatchAttempts.toInt(),
                settledInvocationStatus = settledStatus,
                errorCode = errorCode,
                updatedAtMillis = updatedAtMillis,
                revision = revision
            )
        }.getOrNull()
    }

    private fun status(
        fields: Map<String, AgentJsonValue>,
        key: String
    ): AgentDispatchOutboxStatus? {
        val raw = str(fields, key) ?: return null
        return AgentDispatchOutboxStatus.entries.firstOrNull { it.name == raw }
    }

    private fun str(fields: Map<String, AgentJsonValue>, key: String): String? =
        (fields[key] as? AgentJsonValue.Str)?.value

    private fun optionalStr(fields: Map<String, AgentJsonValue>, key: String): String? =
        when (val value = fields[key]) {
            AgentJsonValue.Null -> null
            is AgentJsonValue.Str -> value.value
            else -> null
        }

    private fun num(fields: Map<String, AgentJsonValue>, key: String): Long? =
        (fields[key] as? AgentJsonValue.Num)?.literal?.toLongOrNull()

    private fun nullableStr(value: String?): AgentJsonValue =
        if (value == null) AgentJsonValue.Null else AgentJsonValue.Str(value)
}
