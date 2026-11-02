package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import com.muyuchat.core.agent.runtime.workflow.WorkflowSchedule
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime

/**
 * File-backed [WorkflowScheduleStore] with atomic replace and a strict, fail-closed decoder.
 *
 * This is the pre-WorkManager durable form: one small JSON file under the agent root, a bounded
 * record count and a hard byte cap. A corrupt file makes every read/write throw instead of silently
 * resetting the schedules (which would re-fire slots).
 */
class FileWorkflowScheduleStore(
    rootDirectory: File,
    private val clock: () -> Long = System::currentTimeMillis
) : WorkflowScheduleStore {
    private val root: File = File(rootDirectory.absoluteFile, "workflow-schedules").also { it.mkdirs() }
    private val file = File(root, "schedules.json")
    private val lock = Any()
    private val codec = StrictAgentJsonCodec()

    private fun readAll(): List<WorkflowScheduleRecord> {
        if (!file.isFile) return emptyList()
        if (file.length() > MAX_BYTES) error("workflow schedule file is oversized")
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (failure: Exception) {
            error("workflow schedule file could not be read: ${failure.javaClass.simpleName}")
        }
        val parsed = codec.parse(text) as? AgentJsonParseResult.Parsed
            ?: error("workflow schedule file is not valid JSON")
        val rootObject = parsed.value as? AgentJsonValue.Obj
            ?: error("workflow schedule file must be an object")
        val fields = rootObject.fields.toMap()
        if ((fields["schema"] as? AgentJsonValue.Num)?.literal?.toIntOrNull() != SCHEMA) {
            error("workflow schedule file has an unsupported schema")
        }
        val records = (fields["records"] as? AgentJsonValue.Arr)?.items
            ?: error("workflow schedule file has no records")
        if (records.size > MAX_RECORDS) error("workflow schedule file has too many records")
        return records.map { decodeRecord(it) }
    }

    private fun writeAll(records: List<WorkflowScheduleRecord>) {
        val json = AgentJsonValue.Obj(
            listOf(
                "schema" to AgentJsonValue.Num(SCHEMA.toString()),
                "records" to AgentJsonValue.Arr(records.map(::encodeRecord))
            )
        )
        val bytes = codec.write(json).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) error("workflow schedule file would be oversized")
        val temp = File(root, "schedules.json.tmp")
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
            error("workflow schedule file could not be written: ${failure.javaClass.simpleName}")
        }
    }

    override suspend fun load(): List<WorkflowScheduleRecord> = synchronized(lock) { readAll() }

    override suspend fun put(record: WorkflowScheduleRecord) {
        synchronized(lock) {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst {
                it.definitionId == record.definitionId && it.version == record.version
            }
            if (index >= 0) records[index] = record else records += record
            writeAll(records)
        }
    }

    override suspend fun remove(definitionId: String, version: Long): Boolean {
        synchronized(lock) {
            val records = readAll()
            val remaining = records.filterNot {
                it.definitionId == definitionId && it.version == version
            }
            if (remaining.size == records.size) return false
            writeAll(remaining)
            return true
        }
    }

    override suspend fun seed(definitionId: String, version: Long, nowMillis: Long): Boolean {        synchronized(lock) {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst {
                it.definitionId == definitionId && it.version == version
            }
            if (index < 0) return false
            val record = records[index]
            if (record.lastDeliveredMillis != null) return false
            records[index] = record.copy(lastDeliveredMillis = nowMillis, lastSlotKey = SEED_KEY)
            writeAll(records)
            return true
        }
    }

    override suspend fun claim(
        definitionId: String,
        version: Long,
        slotKey: String,
        slotMillis: Long
    ): WorkflowScheduleClaim {
        synchronized(lock) {
            val records = readAll().toMutableList()
            val index = records.indexOfFirst {
                it.definitionId == definitionId && it.version == version
            }
            if (index < 0) return WorkflowScheduleClaim.NotFound
            val record = records[index]
            val last = record.lastDeliveredMillis
            if (last != null && (slotMillis <= last || slotKey == record.lastSlotKey)) {
                return WorkflowScheduleClaim.Stale
            }
            if (slotMillis <= clock()) {
                records[index] = record.copy(lastDeliveredMillis = slotMillis, lastSlotKey = slotKey)
                writeAll(records)
                return WorkflowScheduleClaim.Claimed
            }
            // A slot in the future is not claimable; treat it as stale rather than pre-consuming it.
            return WorkflowScheduleClaim.Stale
        }
    }

    private fun encodeRecord(record: WorkflowScheduleRecord): AgentJsonValue = AgentJsonValue.Obj(
        listOfNotNull(
            "definitionId" to AgentJsonValue.Str(record.definitionId),
            "version" to AgentJsonValue.Num(record.version.toString()),
            "enabled" to AgentJsonValue.Bool(record.enabled),
            "schedule" to encodeSchedule(record.schedule),
            record.lastDeliveredMillis?.let { "lastDeliveredMillis" to AgentJsonValue.Num(it.toString()) },
            record.lastSlotKey?.let { "lastSlotKey" to AgentJsonValue.Str(it) },
            record.agentTask?.let { "agentTask" to encodeAgentTask(it) }
        )
    )

    private fun decodeRecord(value: AgentJsonValue): WorkflowScheduleRecord {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("record")
        val definitionId = (fields["definitionId"] as? AgentJsonValue.Str)?.value ?: invalid("definitionId")
        val version = (fields["version"] as? AgentJsonValue.Num)?.literal?.toLongOrNull()
            ?: invalid("version")
        val enabled = (fields["enabled"] as? AgentJsonValue.Bool)?.value
            ?: invalid("enabled")
        val schedule = decodeSchedule(fields["schedule"] ?: invalid("schedule"))
        val lastDelivered = when (val raw = fields["lastDeliveredMillis"]) {
            null -> null
            is AgentJsonValue.Num -> raw.literal.toLongOrNull() ?: invalid("lastDeliveredMillis")
            else -> invalid("lastDeliveredMillis")
        }
        val lastSlot = when (val raw = fields["lastSlotKey"]) {
            null -> null
            is AgentJsonValue.Str -> raw.value
            else -> invalid("lastSlotKey")
        }
        return WorkflowScheduleRecord(
            definitionId = definitionId,
            version = version,
            schedule = schedule,
            enabled = enabled,
            lastDeliveredMillis = lastDelivered,
            lastSlotKey = lastSlot,
            // Absent in records written before scheduled agent tasks existed; null is the safe
            // "no authorized model" value and keeps AGENT_TASK typed NOT_SUPPORTED.
            agentTask = fields["agentTask"]?.let(::decodeAgentTask)
        )
    }

    private fun encodeAgentTask(plan: WorkflowAgentTaskPlan): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "displayTitle" to AgentJsonValue.Str(plan.displayTitle),
            "binding" to encodeBinding(plan.modelBinding),
            "policy" to encodePolicy(plan.policy)
        )
    )

    private fun encodeBinding(binding: AgentModelBinding): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "providerId" to AgentJsonValue.Str(binding.providerId),
            "modelId" to AgentJsonValue.Str(binding.modelId),
            "runtime" to AgentJsonValue.Str(binding.runtime),
            "assetFingerprint" to AgentJsonValue.Str(binding.assetFingerprint),
            "protocolMode" to AgentJsonValue.Str(binding.protocolMode.name),
            "modelDisplayName" to AgentJsonValue.Str(binding.modelDisplayName),
            "contextWindowTokens" to (
                binding.contextWindowTokens?.let { AgentJsonValue.Num(it.toString()) }
                    ?: AgentJsonValue.Null
                ),
            "samplingJson" to AgentJsonValue.Str(binding.samplingJson),
            "capabilityEvidenceVersion" to (
                binding.capabilityEvidenceVersion?.let { AgentJsonValue.Str(it) }
                    ?: AgentJsonValue.Null
                )
        )
    )

    private fun encodePolicy(policy: AgentRunPolicy): AgentJsonValue = AgentJsonValue.Obj(
        listOf(
            "networkPolicy" to AgentJsonValue.Str(policy.networkPolicy.name),
            "policyVersion" to AgentJsonValue.Num(policy.policyVersion.toString()),
            "enabledToolKeys" to (
                policy.enabledToolKeys?.let { keys ->
                    AgentJsonValue.Arr(keys.sorted().map { AgentJsonValue.Str(it) })
                } ?: AgentJsonValue.Null
                ),
            "skillIds" to AgentJsonValue.Arr(policy.skillIds.sorted().map { AgentJsonValue.Str(it) }),
            "offlineForced" to AgentJsonValue.Bool(policy.offlineForced),
            "memorySpaceIds" to AgentJsonValue.Arr(
                policy.memorySpaceIds.sorted().map { AgentJsonValue.Str(it) }
            )
        )
    )

    private fun decodeAgentTask(value: AgentJsonValue): WorkflowAgentTaskPlan {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("agentTask")
        val displayTitle = (fields["displayTitle"] as? AgentJsonValue.Str)?.value
            ?: invalid("agentTask.displayTitle")
        val binding = decodeBinding(fields["binding"] ?: invalid("agentTask.binding"))
        val policy = decodePolicy(fields["policy"] ?: invalid("agentTask.policy"))
        // A schedule plan may not smuggle a nested workflow run into an AGENT_TASK child.
        if (policy.workflow != null) invalid("agentTask.policy.workflow")
        return WorkflowAgentTaskPlan(
            modelBinding = binding,
            policy = policy,
            displayTitle = displayTitle
        )
    }

    private fun decodeBinding(value: AgentJsonValue): AgentModelBinding {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("binding")
        return AgentModelBinding(
            providerId = (fields["providerId"] as? AgentJsonValue.Str)?.value
                ?: invalid("binding.providerId"),
            modelId = (fields["modelId"] as? AgentJsonValue.Str)?.value
                ?: invalid("binding.modelId"),
            runtime = (fields["runtime"] as? AgentJsonValue.Str)?.value
                ?: invalid("binding.runtime"),
            assetFingerprint = (fields["assetFingerprint"] as? AgentJsonValue.Str)?.value
                ?: invalid("binding.assetFingerprint"),
            protocolMode = enumValue<AgentProtocolMode>(
                (fields["protocolMode"] as? AgentJsonValue.Str)?.value
                    ?: invalid("binding.protocolMode"),
                "binding.protocolMode"
            ),
            modelDisplayName = (fields["modelDisplayName"] as? AgentJsonValue.Str)?.value
                ?: invalid("binding.modelDisplayName"),
            contextWindowTokens = when (val raw = fields["contextWindowTokens"]) {
                null, AgentJsonValue.Null -> null
                is AgentJsonValue.Num -> raw.literal.toIntOrNull()
                    ?: invalid("binding.contextWindowTokens")
                else -> invalid("binding.contextWindowTokens")
            },
            samplingJson = (fields["samplingJson"] as? AgentJsonValue.Str)?.value
                ?: invalid("binding.samplingJson"),
            capabilityEvidenceVersion = when (val raw = fields["capabilityEvidenceVersion"]) {
                null, AgentJsonValue.Null -> null
                is AgentJsonValue.Str -> raw.value
                else -> invalid("binding.capabilityEvidenceVersion")
            }
        )
    }

    private fun decodePolicy(value: AgentJsonValue): AgentRunPolicy {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("policy")
        return AgentRunPolicy(
            networkPolicy = enumValue<AgentNetworkPolicy>(
                (fields["networkPolicy"] as? AgentJsonValue.Str)?.value
                    ?: invalid("policy.networkPolicy"),
                "policy.networkPolicy"
            ),
            policyVersion = (fields["policyVersion"] as? AgentJsonValue.Num)?.literal?.toLongOrNull()
                ?: invalid("policy.policyVersion"),
            enabledToolKeys = when (val raw = fields["enabledToolKeys"]) {
                null, AgentJsonValue.Null -> null
                is AgentJsonValue.Arr -> raw.items.map { item ->
                    (item as? AgentJsonValue.Str)?.value ?: invalid("policy.enabledToolKeys")
                }.toSet()
                else -> invalid("policy.enabledToolKeys")
            },
            skillIds = stringSet(fields["skillIds"], "policy.skillIds"),
            offlineForced = (fields["offlineForced"] as? AgentJsonValue.Bool)?.value
                ?: invalid("policy.offlineForced"),
            memorySpaceIds = stringSet(fields["memorySpaceIds"], "policy.memorySpaceIds")
        )
    }

    private fun stringSet(value: AgentJsonValue?, name: String): Set<String> {
        if (value == null) return emptySet()
        val items = (value as? AgentJsonValue.Arr)?.items ?: invalid(name)
        return items.map { item -> (item as? AgentJsonValue.Str)?.value ?: invalid(name) }.toSet()
    }

    private inline fun <reified T : Enum<T>> enumValue(text: String, name: String): T =
        enumValues<T>().firstOrNull { it.name == text } ?: invalid(name)

    private fun encodeSchedule(schedule: WorkflowSchedule): AgentJsonValue = when (schedule) {
        is WorkflowSchedule.Interval -> AgentJsonValue.Obj(
            listOf(
                "kind" to AgentJsonValue.Str("interval"),
                "everyMinutes" to AgentJsonValue.Num(schedule.everyMinutes.toString())
            )
        )
        is WorkflowSchedule.Daily -> AgentJsonValue.Obj(
            listOf(
                "kind" to AgentJsonValue.Str("daily"),
                "time" to AgentJsonValue.Str(formatTime(schedule.time)),
                "zone" to AgentJsonValue.Str(schedule.zone.id)
            )
        )
        is WorkflowSchedule.Weekly -> AgentJsonValue.Obj(
            listOf(
                "kind" to AgentJsonValue.Str("weekly"),
                "days" to AgentJsonValue.Arr(schedule.days.sorted().map { AgentJsonValue.Str(it.name) }),
                "time" to AgentJsonValue.Str(formatTime(schedule.time)),
                "zone" to AgentJsonValue.Str(schedule.zone.id)
            )
        )
    }

    private fun decodeSchedule(value: AgentJsonValue): WorkflowSchedule {
        val fields = (value as? AgentJsonValue.Obj)?.fields?.toMap() ?: invalid("schedule")
        return when ((fields["kind"] as? AgentJsonValue.Str)?.value) {
            "interval" -> WorkflowSchedule.Interval(
                (fields["everyMinutes"] as? AgentJsonValue.Num)?.literal?.toIntOrNull()
                    ?: invalid("everyMinutes")
            )
            "daily" -> WorkflowSchedule.Daily(
                decodeTime(fields["time"] ?: invalid("time")),
                WorkflowScheduleZone.zone((fields["zone"] as? AgentJsonValue.Str)?.value ?: invalid("zone"))
            )
            "weekly" -> WorkflowSchedule.Weekly(
                days = (fields["days"] as? AgentJsonValue.Arr)?.items?.map { item ->
                    WorkflowScheduleZone.day((item as? AgentJsonValue.Str)?.value ?: invalid("day"))
                }?.toSet() ?: invalid("days"),
                time = decodeTime(fields["time"] ?: invalid("time")),
                zone = WorkflowScheduleZone.zone(
                    (fields["zone"] as? AgentJsonValue.Str)?.value ?: invalid("zone")
                )
            )
            else -> invalid("schedule.kind")
        }
    }

    private fun decodeTime(value: AgentJsonValue): LocalTime {
        val text = (value as? AgentJsonValue.Str)?.value ?: invalid("time")
        val parts = text.split(':')
        if (parts.size != 2) invalid("time")
        val hour = parts[0].toIntOrNull() ?: invalid("time")
        val minute = parts[1].toIntOrNull() ?: invalid("time")
        return try {
            WorkflowScheduleZone.time(hour, minute)
        } catch (_: Exception) {
            invalid("time")
        }
    }

    private fun formatTime(time: LocalTime): String =
        time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')

    private fun invalid(name: String): Nothing = error("workflow schedule record is invalid: $name")

    private companion object {
        const val SCHEMA = 1
        const val MAX_BYTES = 512 * 1024
        const val MAX_RECORDS = 256
        const val SEED_KEY = "seed"
    }
}
