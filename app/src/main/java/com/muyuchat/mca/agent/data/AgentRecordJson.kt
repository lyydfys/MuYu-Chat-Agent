package com.muyuchat.mca.agent.data

import com.muyuchat.core.agent.contract.AgentBudget
import com.muyuchat.core.agent.contract.AgentCommitResult
import com.muyuchat.core.agent.contract.AgentModelBinding
import com.muyuchat.core.agent.contract.AgentNetworkPolicy
import com.muyuchat.core.agent.contract.AgentProtocolMode
import com.muyuchat.core.agent.contract.AgentRunPolicy
import com.muyuchat.core.agent.contract.AgentSpentBudget
import com.muyuchat.core.agent.contract.AgentWorkflowRunSpec
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Canonical JSON for the frozen Agent records that Room stores as bounded TEXT columns.
 *
 * Keys and enum names are stable persisted values. No credential or provider body is accepted by
 * these types, and every decoder throws a typed integrity error instead of falling back to
 * defaults. `samplingJson` is preserved as an opaque string so re-encoding never reorders it.
 */
object AgentRecordJson {
    private const val VERSION_KEY = "schemaVersion"

    fun encodeBinding(binding: AgentModelBinding): String = JSONObject()
        .put(VERSION_KEY, com.muyuchat.core.agent.contract.AgentContract.SCHEMA_VERSION)
        .put("providerId", binding.providerId)
        .put("modelId", binding.modelId)
        .put("runtime", binding.runtime)
        .put("assetFingerprint", binding.assetFingerprint)
        .put("protocolMode", binding.protocolMode.name)
        .put("modelDisplayName", binding.modelDisplayName)
        .put("contextWindowTokens", binding.contextWindowTokens ?: JSONObject.NULL)
        .put("samplingJson", binding.samplingJson)
        .put("capabilityEvidenceVersion", binding.capabilityEvidenceVersion ?: JSONObject.NULL)
        .toString()

    fun decodeBinding(raw: String): AgentModelBinding = guard("modelBindingJson") {
        val json = JSONObject(raw)
        AgentModelBinding(
            providerId = json.requireString("providerId"),
            modelId = json.requireString("modelId"),
            runtime = json.requireString("runtime"),
            assetFingerprint = json.requireString("assetFingerprint"),
            protocolMode = decodeEnum(json.requireString("protocolMode"), AgentProtocolMode.entries),
            modelDisplayName = json.requireString("modelDisplayName"),
            contextWindowTokens = json.optNullableInt("contextWindowTokens"),
            samplingJson = json.requireString("samplingJson"),
            capabilityEvidenceVersion = json.optNullableString("capabilityEvidenceVersion")
        )
    }

    fun encodePolicy(policy: AgentRunPolicy): String {
        val json = JSONObject()
            .put("networkPolicy", policy.networkPolicy.name)
            .put("policyVersion", policy.policyVersion)
            .put(
                "enabledToolKeys",
                policy.enabledToolKeys?.let { keys ->
                    JSONArray(keys.sorted())
                } ?: JSONObject.NULL
            )
            .put("skillIds", JSONArray(policy.skillIds.sorted()))
            .put("offlineForced", policy.offlineForced)
        // Additive field: omitted for the empty set so a pre-memory policy row keeps exactly the
        // bytes (and therefore the idempotency hash) it had before this feature existed.
        if (policy.memorySpaceIds.isNotEmpty()) {
            json.put("memorySpaceIds", JSONArray(policy.memorySpaceIds.sorted()))
        }
        policy.workflow?.let { workflow ->
            json.put(
                "workflow",
                JSONObject()
                    .put("definitionId", workflow.definitionId)
                    .put("version", workflow.version)
            )
        }
        return json.toString()
    }

    fun decodePolicy(raw: String): AgentRunPolicy = guard("policyJson") {
        val json = JSONObject(raw)
        AgentRunPolicy(
            networkPolicy = decodeEnum(json.requireString("networkPolicy"), AgentNetworkPolicy.entries),
            policyVersion = json.requireLong("policyVersion"),
            enabledToolKeys = json.optStringSet("enabledToolKeys"),
            skillIds = json.optStringSet("skillIds").orEmpty(),
            offlineForced = json.requireBoolean("offlineForced"),
            // Absent in rows written before memory existed; empty is the safe no-memory default.
            memorySpaceIds = json.optStringSet("memorySpaceIds").orEmpty(),
            // Absent for every normal Agent run and for rows written before run-owned workflows.
            workflow = json.optJSONObject("workflow")?.let { workflow ->
                AgentWorkflowRunSpec(
                    definitionId = workflow.requireString("definitionId"),
                    version = workflow.requireLong("version")
                )
            }
        )
    }

    fun encodeBudget(budget: AgentBudget): String = JSONObject()
        .put("modelAttempts", budget.modelAttempts)
        .put("toolInvocations", budget.toolInvocations)
        .put("callsPerModelResponse", budget.callsPerModelResponse)
        .put("protocolRepairs", budget.protocolRepairs)
        .put("modelWallTimeMillis", budget.modelWallTimeMillis)
        .put("toolTimeoutMillis", budget.toolTimeoutMillis)
        .put("runActiveTimeMillis", budget.runActiveTimeMillis)
        .put("resourceWaitMillis", budget.resourceWaitMillis)
        .put("toolResultToModelBytes", budget.toolResultToModelBytes)
        .put("toolResultPersistBytes", budget.toolResultPersistBytes)
        .put("textOutputTokens", budget.textOutputTokens)
        .put("totalGeneratedTokens", budget.totalGeneratedTokens)
        .toString()

    fun decodeBudget(raw: String): AgentBudget = guard("budgetJson") {
        val json = JSONObject(raw)
        AgentBudget(
            modelAttempts = json.requireInt("modelAttempts"),
            toolInvocations = json.requireInt("toolInvocations"),
            callsPerModelResponse = json.requireInt("callsPerModelResponse"),
            protocolRepairs = json.requireInt("protocolRepairs"),
            modelWallTimeMillis = json.requireLong("modelWallTimeMillis"),
            toolTimeoutMillis = json.requireLong("toolTimeoutMillis"),
            runActiveTimeMillis = json.requireLong("runActiveTimeMillis"),
            resourceWaitMillis = json.requireLong("resourceWaitMillis"),
            toolResultToModelBytes = json.requireInt("toolResultToModelBytes"),
            toolResultPersistBytes = json.requireInt("toolResultPersistBytes"),
            textOutputTokens = json.requireInt("textOutputTokens"),
            totalGeneratedTokens = json.requireInt("totalGeneratedTokens")
        )
    }

    fun encodeSpent(spent: AgentSpentBudget): String = JSONObject()
        .put("modelAttempts", spent.modelAttempts)
        .put("toolInvocations", spent.toolInvocations)
        .put("protocolRepairs", spent.protocolRepairs)
        .put("modelWallTimeMillis", spent.modelWallTimeMillis)
        .put("runActiveTimeMillis", spent.runActiveTimeMillis)
        .put("generatedTokens", spent.generatedTokens)
        .put("estimated", spent.estimated)
        .toString()

    fun decodeSpent(raw: String): AgentSpentBudget = guard("spentBudgetJson") {
        val json = JSONObject(raw)
        AgentSpentBudget(
            modelAttempts = json.requireInt("modelAttempts"),
            toolInvocations = json.requireInt("toolInvocations"),
            protocolRepairs = json.requireInt("protocolRepairs"),
            modelWallTimeMillis = json.requireLong("modelWallTimeMillis"),
            runActiveTimeMillis = json.requireLong("runActiveTimeMillis"),
            generatedTokens = json.requireInt("generatedTokens"),
            estimated = json.requireBoolean("estimated")
        )
    }

    fun encodeQuestionOptions(options: List<String>): String =
        JSONArray(options).toString()

    fun decodeQuestionOptions(raw: String): List<String> = guard("question.optionsJson") {
        val array = JSONArray(raw)
        (0 until array.length()).map { index -> array.getString(index) }
    }

    /** Bounded self-contained command result. The replay flag is never persisted. */
    fun encodeCommitResult(result: AgentCommitResult): String = JSONObject()
        .put("appliedRevision", result.appliedRevision)
        .put("taskRevision", result.taskRevision ?: JSONObject.NULL)
        .put("runRevision", result.runRevision ?: JSONObject.NULL)
        .put("eventSequences", JSONArray(result.eventSequences))
        .toString()

    fun decodeCommitResult(raw: String): AgentCommitResult = guard("command.resultRef") {
        val json = JSONObject(raw)
        val sequences = json.optJSONArray("eventSequences") ?: JSONArray()
        AgentCommitResult(
            appliedRevision = json.requireLong("appliedRevision"),
            taskRevision = json.optNullableLong("taskRevision"),
            runRevision = json.optNullableLong("runRevision"),
            eventSequences = (0 until sequences.length()).map { sequences.getLong(it) },
            idempotentReplay = false
        )
    }

    private fun JSONObject.requireString(name: String): String =
        optString(name).takeIf { it.isNotEmpty() }
            ?: throw JSONException("missing required string '$name'")

    private fun JSONObject.requireLong(name: String): Long =
        if (!has(name) || isNull(name)) throw JSONException("missing required number '$name'")
        else getLong(name)

    private fun JSONObject.requireInt(name: String): Int =
        if (!has(name) || isNull(name)) throw JSONException("missing required number '$name'")
        else getInt(name)

    private fun JSONObject.requireBoolean(name: String): Boolean =
        if (!has(name) || isNull(name)) throw JSONException("missing required boolean '$name'")
        else getBoolean(name)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private fun JSONObject.optNullableLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private fun JSONObject.optStringSet(name: String): Set<String>? {
        if (!has(name) || isNull(name)) return null
        val array = getJSONArray(name)
        return (0 until array.length()).mapTo(linkedSetOf()) { array.getString(it) }
    }

    private fun <T : Enum<T>> decodeEnum(raw: String, values: List<T>): T =
        values.firstOrNull { it.name == raw }
            ?: throw JSONException("unknown enum value '$raw'")

    private inline fun <T> guard(column: String, block: () -> T): T =
        try {
            block()
        } catch (error: JSONException) {
            throw AgentDataIntegrityException(
                AgentDataErrorCodes.STORAGE_CORRUPTION,
                "cannot decode $column: ${error.message}"
            )
        } catch (error: IllegalArgumentException) {
            throw AgentDataIntegrityException(
                AgentDataErrorCodes.STORAGE_CORRUPTION,
                "invalid $column: ${error.message}"
            )
        }
}
