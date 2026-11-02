package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowBudget
import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowFailurePolicy
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import org.json.JSONArray
import org.json.JSONObject

/** Canonical JSON for one workflow definition version (draft or published). */
internal object WorkflowDefinitionJson {
    fun encode(definition: WorkflowDefinition): JSONObject = JSONObject()
        .put("definitionId", definition.definitionId)
        .put("version", definition.version)
        .put("displayName", definition.displayName)
        .put("inputs", JSONArray().also { array -> definition.inputs.forEach { array.put(port(it)) } })
        .put("outputs", JSONArray().also { array -> definition.outputs.forEach { array.put(port(it)) } })
        .put("nodes", JSONArray().also { array -> definition.nodes.forEach { array.put(node(it)) } })
        .put("edges", JSONArray().also { array -> definition.edges.forEach { array.put(edge(it)) } })
        .put(
            "budget",
            JSONObject()
                .put("maxModelCalls", definition.budget.maxModelCalls)
                .put("maxToolCalls", definition.budget.maxToolCalls)
                .put("maxEstimatedTokens", definition.budget.maxEstimatedTokens)
                .put("maxDurationMillis", definition.budget.maxDurationMillis)
        )
        .put("failurePolicy", definition.failurePolicy.name)
        .put("triggerRef", definition.triggerRef ?: JSONObject.NULL)
        .put("definitionHash", definition.definitionHash)

    /** Returns null for any malformed or contract-invalid payload. */
    fun decode(json: JSONObject): WorkflowDefinition? = runCatching {
        WorkflowDefinition(
            definitionId = json.getString("definitionId"),
            version = json.getLong("version"),
            displayName = json.getString("displayName"),
            inputs = ports(json.optJSONArray("inputs")),
            outputs = ports(json.optJSONArray("outputs")),
            nodes = nodes(json.getJSONArray("nodes")),
            edges = edges(json.getJSONArray("edges")),
            budget = WorkflowBudget(
                maxModelCalls = json.getJSONObject("budget").getInt("maxModelCalls"),
                maxToolCalls = json.getJSONObject("budget").getInt("maxToolCalls"),
                maxEstimatedTokens = json.getJSONObject("budget").getInt("maxEstimatedTokens"),
                maxDurationMillis = json.getJSONObject("budget").getLong("maxDurationMillis")
            ),
            failurePolicy = WorkflowFailurePolicy.valueOf(json.getString("failurePolicy")),
            triggerRef = if (json.isNull("triggerRef")) null else json.getString("triggerRef"),
            definitionHash = json.getString("definitionHash")
        )
    }.getOrNull()

    private fun port(port: WorkflowPort): JSONObject = JSONObject()
        .put("name", port.name)
        .put("type", port.type.name)
        .put("required", port.required)

    private fun ports(array: JSONArray?): List<WorkflowPort> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            WorkflowPort(
                name = item.getString("name"),
                type = WorkflowPortType.valueOf(item.getString("type")),
                required = item.getBoolean("required")
            )
        }
    }

    private fun node(node: WorkflowNode): JSONObject = JSONObject()
        .put("nodeId", node.nodeId)
        .put("type", node.type.name)
        .put("label", node.label)
        .put("config", JSONObject(node.configJson))
        .put("subworkflowDefinitionId", node.subworkflowDefinitionId ?: JSONObject.NULL)
        .put("subworkflowVersion", node.subworkflowVersion ?: JSONObject.NULL)

    private fun nodes(array: JSONArray): List<WorkflowNode> = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        WorkflowNode(
            nodeId = item.getString("nodeId"),
            type = WorkflowNodeType.valueOf(item.getString("type")),
            label = item.optString("label"),
            configJson = item.getJSONObject("config").toString(),
            subworkflowDefinitionId = if (item.isNull("subworkflowDefinitionId")) {
                null
            } else {
                item.getString("subworkflowDefinitionId")
            },
            subworkflowVersion = if (item.isNull("subworkflowVersion")) null else item.getLong("subworkflowVersion")
        )
    }

    private fun edge(edge: WorkflowEdge): JSONObject = JSONObject()
        .put("from", edge.fromNodeId)
        .put("to", edge.toNodeId)
        .put("when", edge.whenLabel ?: JSONObject.NULL)

    private fun edges(array: JSONArray): List<WorkflowEdge> = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        WorkflowEdge(
            fromNodeId = item.getString("from"),
            toNodeId = item.getString("to"),
            whenLabel = if (item.isNull("when")) null else item.getString("when")
        )
    }
}
