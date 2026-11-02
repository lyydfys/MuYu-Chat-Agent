package com.muyuchat.mca.agent.memory

import com.muyuchat.core.agent.contract.AgentScopePreconditionCodec
import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.FrozenToolCommand

/** Shared fixtures for the memory-domain JVM tests. */
internal object AgentMemoryTestFixtures {
    const val NOW: Long = 1_700_000_000_000L

    fun itemsApproval(
        spaceId: String,
        reason: AgentMemoryWriteReason = AgentMemoryWriteReason.USER_IMPORT
    ): AgentMemoryWriteApproval = AgentMemoryWriteApproval.items(
        spaceId = spaceId,
        principal = "local-user",
        reason = reason,
        approvedAtMillis = NOW
    )

    fun adminApproval(): AgentMemoryWriteApproval = AgentMemoryWriteApproval.spaceAdmin(
        principal = "local-user",
        approvedAtMillis = NOW
    )

    fun store(
        persistence: AgentMemoryPersistence,
        ids: () -> String
    ): AgentMemoryStore = AgentMemoryStore(persistence = persistence, clock = { NOW }, idFactory = ids)

    /** Deterministic id factory: `id-1`, `id-2`, ... so assertions can name exact rows. */
    fun sequentialIds(prefix: String = "id"): () -> String {
        var next = 0
        return {
            next++
            "$prefix-$next"
        }
    }

    fun command(
        argumentsJson: String,
        toolKey: String = AgentMemoryToolDescriptors.TOOL_KEY,
        toolVersion: String = "1",
        schemaHash: String = AgentMemoryToolDescriptors.MEMORY_SEARCH.schemaHash,
        spaceIds: Set<String> = setOf("space-1"),
        legacyPrecondition: Boolean = false
    ): FrozenToolCommand {
        val typed = if (legacyPrecondition) null else AgentMemoryResourceScopes.precondition(spaceIds)
        val preconditionJson = typed?.let(AgentScopePreconditionCodec::canonicalJson) ?: "{}"
        val fingerprint = typed?.let(AgentScopePreconditionCodec::fingerprint) ?: "resource-fingerprint"
        return FrozenToolCommand(
            invocationId = "inv-1",
            runId = "run-1",
            stepId = "step-1",
            callOrdinal = 0,
            toolKey = toolKey,
            toolVersion = toolVersion,
            schemaHash = schemaHash,
            normalizedArgumentsJson = argumentsJson,
            argumentsHash = "arguments-hash",
            resourceFingerprint = fingerprint,
            runEpoch = 1L,
            policyVersion = 1L,
            expiresAtMillis = NOW + 60_000L,
            resourcePreconditionsJson = preconditionJson
        )
    }

    fun context(taskId: String = "task-1"): AgentToolContext = AgentToolContext(
        taskId = taskId,
        workspaceId = "workspace-1",
        runId = "run-1",
        stepIndex = 0,
        runEpoch = 1L,
        policyVersion = 1L,
        workspaceReadGranted = false
    )

    fun searchArguments(query: String, spaceId: String? = null, maxItems: Int? = null): String {
        val builder = StringBuilder("{\"query\":\"").append(escape(query)).append('"')
        if (spaceId != null) builder.append(",\"spaceId\":\"").append(escape(spaceId)).append('"')
        if (maxItems != null) builder.append(",\"maxItems\":").append(maxItems)
        return builder.append('}').toString()
    }

    fun getArguments(itemId: String, spaceId: String? = null, contentHash: String? = null): String {
        val builder = StringBuilder("{\"itemId\":\"").append(escape(itemId)).append('"')
        if (spaceId != null) builder.append(",\"spaceId\":\"").append(escape(spaceId)).append('"')
        if (contentHash != null) builder.append(",\"contentHash\":\"").append(escape(contentHash)).append('"')
        return builder.append('}').toString()
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }
}

/** Grant source that returns a fixed grant (or none) for every task. */
internal class FixedMemoryGrantSource(var grant: AgentMemoryReadGrant?) : AgentMemoryReadGrantSource {
    var lookups: Int = 0
        private set

    override suspend fun grantForTask(taskId: String): AgentMemoryReadGrant? {
        lookups++
        return grant
    }
}
