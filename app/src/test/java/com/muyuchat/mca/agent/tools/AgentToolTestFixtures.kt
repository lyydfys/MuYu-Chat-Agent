package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolContext
import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.core.agent.contract.AgentToolResult
import com.muyuchat.core.agent.contract.FrozenToolCommand
import org.json.JSONObject

internal fun toolCommand(
    descriptor: AgentToolDescriptor,
    argumentsJson: String,
    invocationId: String = "inv-1",
    policyVersion: Long = 1L
): FrozenToolCommand = FrozenToolCommand(
    invocationId = invocationId,
    runId = "run-1",
    stepId = "step-1",
    callOrdinal = 0,
    toolKey = descriptor.toolKey,
    toolVersion = descriptor.version,
    schemaHash = descriptor.schemaHash,
    normalizedArgumentsJson = argumentsJson,
    argumentsHash = "arguments-hash-1",
    resourceFingerprint = "resource-1",
    runEpoch = 1L,
    policyVersion = policyVersion,
    expiresAtMillis = 60_000L
)

internal fun toolContext(
    workspaceReadGranted: Boolean = true,
    policyVersion: Long = 1L,
    taskId: String = "task-1",
    workspaceId: String = "workspace-1"
): AgentToolContext = AgentToolContext(
    taskId = taskId,
    workspaceId = workspaceId,
    runId = "run-1",
    stepIndex = 0,
    runEpoch = 1L,
    policyVersion = policyVersion,
    workspaceReadGranted = workspaceReadGranted
)

internal fun resultEnvelope(result: AgentToolResult): JSONObject =
    JSONObject(result.dataJson ?: error("tool result did not carry a data document"))

internal fun resultData(result: AgentToolResult): JSONObject =
    resultEnvelope(result).getJSONObject("data")

internal fun arguments(vararg pairs: Pair<String, Any?>): String {
    val json = JSONObject()
    pairs.forEach { (key, value) -> json.put(key, value ?: JSONObject.NULL) }
    return json.toString()
}
