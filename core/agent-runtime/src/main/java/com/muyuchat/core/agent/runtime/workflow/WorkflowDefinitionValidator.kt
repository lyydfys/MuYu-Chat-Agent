package com.muyuchat.core.agent.runtime.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.runtime.AgentJsonParseResult
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.StrictJsonCodec
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.protocol.AgentJsonValues
import com.muyuchat.core.agent.runtime.protocol.StrictAgentJsonCodec
import java.util.ArrayDeque

/** One stable validation problem. [nodeId] is null for definition-level issues. */
data class WorkflowValidationIssue(
    val code: String,
    val nodeId: String?,
    val safeMessage: String
)

sealed interface WorkflowValidationResult {
    data class Valid(val topologicalOrder: List<String>) : WorkflowValidationResult

    data class Invalid(val issues: List<WorkflowValidationIssue>) : WorkflowValidationResult
}

/**
 * Static validation for a published workflow definition (N17).
 *
 * It never executes a node and never evaluates an expression: it only proves the graph is a bounded
 * DAG with complete condition routing, reachable outputs, pinned subworkflow versions and typed
 * required configuration. All limits are explicit; a definition that exceeds them is invalid, not
 * "best effort".
 */
class WorkflowDefinitionValidator(
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()
) {
    fun validate(
        definition: WorkflowDefinition,
        referencedDefinitions: Map<SubworkflowRef, WorkflowDefinition> = emptyMap(),
        subworkflowDepth: Int = 0
    ): WorkflowValidationResult {
        val issues = ArrayList<WorkflowValidationIssue>()

        if (definition.nodes.size > MAX_NODES) {
            issues += issue("WF_TOO_MANY_NODES", null, "A definition may declare at most $MAX_NODES nodes.")
        }
        if (definition.edges.size > MAX_EDGES) {
            issues += issue("WF_TOO_MANY_EDGES", null, "A definition may declare at most $MAX_EDGES edges.")
        }
        if (definition.inputs.size + definition.outputs.size > MAX_PORTS) {
            issues += issue("WF_TOO_MANY_PORTS", null, "A definition may declare at most $MAX_PORTS ports.")
        }
        if (subworkflowDepth > MAX_SUBWORKFLOW_DEPTH) {
            issues += issue(
                "WF_SUBWORKFLOW_DEPTH",
                null,
                "Subworkflow nesting exceeds $MAX_SUBWORKFLOW_DEPTH levels."
            )
            // Stop here: continuing would recursively validate a self/mutually recursive definition
            // forever (StackOverflowError) instead of failing the publish closed.
            return WorkflowValidationResult.Invalid(issues)
        }

        val byId = LinkedHashMap<String, WorkflowNode>(definition.nodes.size)
        definition.nodes.forEach { node ->
            if (byId.put(node.nodeId, node) != null) {
                issues += issue("WF_DUPLICATE_NODE", node.nodeId, "Node id is declared twice.")
            }
        }
        val portNames = definition.outputs.map { it.name }.toSet()
        if (definition.outputs.distinctBy { it.name }.size != definition.outputs.size) {
            issues += issue("WF_DUPLICATE_PORT", null, "Output port names must be unique.")
        }
        if (definition.inputs.distinctBy { it.name }.size != definition.inputs.size) {
            issues += issue("WF_DUPLICATE_PORT", null, "Input port names must be unique.")
        }

        val triggers = definition.nodes.filter { it.type == WorkflowNodeType.TRIGGER }
        if (triggers.size != 1) {
            issues += issue("WF_TRIGGER_COUNT", null, "A definition needs exactly one trigger node.")
        }
        val outputs = definition.nodes.filter { it.type == WorkflowNodeType.OUTPUT }
        if (outputs.isEmpty()) {
            issues += issue("WF_NO_OUTPUT", null, "A definition needs at least one output node.")
        }

        definition.nodes.forEach { node ->
            validateNodeConfig(node, portNames, issues)
        }

        definition.edges.forEach { edge ->
            if (edge.fromNodeId !in byId) {
                issues += issue("WF_UNKNOWN_EDGE_ENDPOINT", edge.fromNodeId, "Edge starts at an unknown node.")
            }
            if (edge.toNodeId !in byId) {
                issues += issue("WF_UNKNOWN_EDGE_ENDPOINT", edge.toNodeId, "Edge ends at an unknown node.")
            }
            if (edge.fromNodeId == edge.toNodeId) {
                issues += issue("WF_SELF_LOOP", edge.fromNodeId, "A node must not point at itself.")
            }
        }

        // Condition routing: every outgoing edge of a condition node is labeled and labels are
        // unique; only a condition node may label edges.
        definition.nodes.forEach { node ->
            val outgoing = definition.edges.filter { it.fromNodeId == node.nodeId }
            if (node.type == WorkflowNodeType.CONDITION) {
                val labels = outgoing.mapNotNull { it.whenLabel }
                if (outgoing.size < 2) {
                    issues += issue(
                        "WF_CONDITION_INCOMPLETE",
                        node.nodeId,
                        "A condition node needs at least two labeled outgoing edges."
                    )
                }
                if (labels.size != outgoing.size) {
                    issues += issue(
                        "WF_CONDITION_UNLABELED",
                        node.nodeId,
                        "Every condition edge must carry a route label."
                    )
                }
                if (labels.distinct().size != labels.size) {
                    issues += issue(
                        "WF_CONDITION_DUPLICATE_LABEL",
                        node.nodeId,
                        "Condition route labels must be unique."
                    )
                }
            } else if (outgoing.any { it.whenLabel != null }) {
                issues += issue(
                    "WF_EDGE_LABEL_ON_NON_CONDITION",
                    node.nodeId,
                    "Only a condition node may label its outgoing edges."
                )
            }
        }

        // Duplicate edges (same endpoints+label) are almost always an authoring mistake.
        val edgeKeys = HashSet<String>()
        definition.edges.forEach { edge ->
            if (!edgeKeys.add("${edge.fromNodeId}->${edge.toNodeId}:${edge.whenLabel.orEmpty()}")) {
                issues += issue("WF_DUPLICATE_EDGE", edge.fromNodeId, "Duplicate edge declaration.")
            }
        }

        // Subworkflow/foreach references must resolve to a pinned version of a valid definition.
        definition.nodes.filter {
            it.type == WorkflowNodeType.SUBWORKFLOW || it.type == WorkflowNodeType.FOREACH
        }.forEach { node ->
            val ref = if (node.type == WorkflowNodeType.SUBWORKFLOW) {
                SubworkflowRef(
                    definitionId = node.subworkflowDefinitionId.orEmpty(),
                    version = node.subworkflowVersion ?: 0L
                )
            } else {
                val fields = ((codec.parse(node.configJson) as? AgentJsonParseResult.Parsed)
                    ?.value as? AgentJsonValue.Obj)?.fields?.toMap()
                val definitionId = (fields?.get("subworkflowDefinitionId") as? AgentJsonValue.Str)
                    ?.value?.takeIf { it.isNotBlank() }
                val version = (fields?.get("subworkflowVersion") as? AgentJsonValue.Num)
                    ?.literal?.toLongOrNull()
                if (definitionId == null || version == null) return@forEach
                SubworkflowRef(definitionId, version)
            }
            val referenced = referencedDefinitions[ref]
            if (referenced == null) {
                issues += issue(
                    "WF_SUBWORKFLOW_UNRESOLVED",
                    node.nodeId,
                    "Subworkflow ${ref.definitionId}@${ref.version} is not pinned or not available."
                )
            } else {
                val nested = validate(referenced, referencedDefinitions, subworkflowDepth + 1)
                if (nested is WorkflowValidationResult.Invalid) {
                    issues += issue(
                        "WF_SUBWORKFLOW_INVALID",
                        node.nodeId,
                        "Subworkflow ${ref.definitionId}@${ref.version} is invalid."
                    )
                }
            }
        }

        if (definition.definitionHash.isNotEmpty()) {
            val actual = WorkflowDefinitionHasher.hash(definition)
            if (actual != definition.definitionHash) {
                issues += issue("WF_HASH_MISMATCH", null, "The declared definition hash does not match its content.")
            }
        }

        // Compensation nodes never join the flow: the runner triggers them after a failure. Each
        // target may have at most one compensation, and the target must be an executable node.
        val compensatedTargets = HashSet<String>()
        definition.nodes.filter { it.type == WorkflowNodeType.COMPENSATE }.forEach { node ->
            val parsed = codec.parse(node.configJson)
            val fields = ((parsed as? AgentJsonParseResult.Parsed)?.value as? AgentJsonValue.Obj)
                ?.fields?.toMap()
            val target = (fields?.get("compensates") as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
            if (target != null) {
                val targetNode = byId[target]
                if (targetNode == null || targetNode.type == WorkflowNodeType.TRIGGER ||
                    targetNode.type == WorkflowNodeType.COMPENSATE
                ) {
                    issues += issue(
                        "WF_COMPENSATION_TARGET",
                        node.nodeId,
                        "A compensation must target an executable node."
                    )
                } else if (!compensatedTargets.add(target)) {
                    issues += issue(
                        "WF_COMPENSATION_DUPLICATE",
                        node.nodeId,
                        "A node may have at most one compensation."
                    )
                }
            }
            if (definition.edges.any { it.fromNodeId == node.nodeId || it.toNodeId == node.nodeId }) {
                issues += issue(
                    "WF_COMPENSATION_EDGE",
                    node.nodeId,
                    "A compensation node must not be wired into the flow."
                )
            }
        }

        val topological = topologicalOrder(byId.keys, definition.edges)
        if (topological == null) {
            issues += issue("WF_CYCLE", null, "The graph contains a cycle; repetition requires explicit control nodes.")
        } else if (issues.isEmpty()) {
            val triggerId = triggers.single().nodeId
            val reachable = reachableFrom(triggerId, definition.edges)
            // A compensation is deliberately outside the flow; the runner reaches it from a failure.
            definition.nodes.filterNot { it.type == WorkflowNodeType.COMPENSATE }.forEach { node ->
                if (node.nodeId !in reachable) {
                    issues += issue("WF_UNREACHABLE_NODE", node.nodeId, "Node is not reachable from the trigger.")
                }
            }
            val canReachOutput = canReach(outputs.map { it.nodeId }.toSet(), definition.edges)
            definition.nodes.filterNot { it.type == WorkflowNodeType.COMPENSATE }.forEach { node ->
                if (node.nodeId !in canReachOutput) {
                    issues += issue(
                        "WF_DEAD_END",
                        node.nodeId,
                        "Node cannot reach any output node."
                    )
                }
            }
        }

        return if (issues.isEmpty()) {
            WorkflowValidationResult.Valid(topological ?: emptyList())
        } else {
            WorkflowValidationResult.Invalid(issues)
        }
    }

    private fun validateNodeConfig(
        node: WorkflowNode,
        outputPorts: Set<String>,
        issues: MutableList<WorkflowValidationIssue>
    ) {
        val parsed = codec.parse(node.configJson)
        val fields = ((parsed as? AgentJsonParseResult.Parsed)?.value as? AgentJsonValue.Obj)?.fields?.toMap()
        if (fields == null) {
            issues += issue("WF_INVALID_CONFIG", node.nodeId, "Node config must be one JSON object.")
            return
        }
        fun text(name: String): String? = (fields[name] as? AgentJsonValue.Str)?.value?.takeIf { it.isNotBlank() }
        when (node.type) {
            WorkflowNodeType.TRIGGER -> Unit
            WorkflowNodeType.TOOL -> if (text("toolKey") == null) {
                issues += issue("WF_CONFIG_MISSING", node.nodeId, "A tool node requires a toolKey.")
            }
            WorkflowNodeType.AGENT_TASK -> if (text("goal") == null) {
                issues += issue("WF_CONFIG_MISSING", node.nodeId, "An agent-task node requires a goal template.")
            }
            WorkflowNodeType.MODEL -> {
                if (text("prompt") == null) {
                    issues += issue("WF_CONFIG_MISSING", node.nodeId, "A model node requires a prompt.")
                }
                val maxOutputTokens = (fields["maxOutputTokens"] as? AgentJsonValue.Num)
                    ?.literal?.toIntOrNull()
                if (maxOutputTokens != null && maxOutputTokens !in 1..8192) {
                    issues += issue(
                        "WF_CONFIG_INVALID",
                        node.nodeId,
                        "A model node maxOutputTokens must be in 1..8192."
                    )
                }
            }
            WorkflowNodeType.SCRIPT -> if (text("scriptRef") == null) {
                issues += issue("WF_CONFIG_MISSING", node.nodeId, "A script node requires a scriptRef.")
            }
            WorkflowNodeType.CONDITION -> {
                when (val expression = fields["expression"]) {
                    is AgentJsonValue.Str -> if (expression.value.isBlank()) {
                        issues += issue("WF_CONFIG_MISSING", node.nodeId, "A condition node requires an expression.")
                    }
                    is AgentJsonValue.Obj -> when (val parsed = WorkflowExpressionParser.parse(expression)) {
                        is WorkflowExpressionParseResult.Invalid -> issues += issue(
                            "WF_EXPRESSION_INVALID",
                            node.nodeId,
                            parsed.safeMessage
                        )
                        is WorkflowExpressionParseResult.Parsed -> Unit
                    }
                    else -> issues += issue(
                        "WF_CONFIG_MISSING",
                        node.nodeId,
                        "A condition node requires an expression (AST object or legacy string)."
                    )
                }
            }
            WorkflowNodeType.LOGIC -> if (text("operation") == null) {
                issues += issue("WF_CONFIG_MISSING", node.nodeId, "A logic node requires an operation.")
            }
            WorkflowNodeType.EXTRACT -> {
                val op = text("op")
                if (text("source") == null || text("path") == null) {
                    issues += issue("WF_CONFIG_MISSING", node.nodeId, "An extract node requires source and path.")
                } else if (op != null && op !in setOf("REGEX", "JSON", "SUB", "CONCAT", "RANDOM_INT", "RANDOM_STRING")) {
                    issues += issue("WF_CONFIG_MISSING", node.nodeId, "An extract node requires a supported op.")
                }
            }
            WorkflowNodeType.COMPENSATE -> {
                if (text("compensates") == null) {
                    issues += issue(
                        "WF_CONFIG_MISSING",
                        node.nodeId,
                        "A compensation node requires a compensates target."
                    )
                }
                if (text("toolKey") == null) {
                    issues += issue(
                        "WF_CONFIG_MISSING",
                        node.nodeId,
                        "A compensation node requires a toolKey."
                    )
                }
            }
            WorkflowNodeType.WAIT_APPROVAL -> if (text("prompt") == null) {
                issues += issue("WF_CONFIG_MISSING", node.nodeId, "A wait node requires a prompt.")
            }
            WorkflowNodeType.SUBWORKFLOW -> Unit
            WorkflowNodeType.FOREACH -> {
                if (text("subworkflowDefinitionId") == null || text("itemsPath") == null) {
                    issues += issue(
                        "WF_CONFIG_MISSING",
                        node.nodeId,
                        "A foreach node requires a pinned subworkflow and an itemsPath."
                    )
                }
                val version = (fields["subworkflowVersion"] as? AgentJsonValue.Num)
                    ?.literal?.toLongOrNull()
                if (version == null || version < 1L) {
                    issues += issue(
                        "WF_CONFIG_MISSING",
                        node.nodeId,
                        "A foreach node requires a positive subworkflowVersion."
                    )
                }
                val maxItems = (fields["maxItems"] as? AgentJsonValue.Num)?.literal?.toIntOrNull()
                if (maxItems != null && maxItems !in 1..100) {
                    issues += issue(
                        "WF_CONFIG_INVALID",
                        node.nodeId,
                        "A foreach maxItems must be in 1..100."
                    )
                }
                val parallel = (fields["parallel"] as? AgentJsonValue.Num)?.literal?.toIntOrNull()
                if (parallel != null && parallel != 1) {
                    issues += issue(
                        "WF_CONFIG_INVALID",
                        node.nodeId,
                        "This slice only supports sequential foreach (parallel=1)."
                    )
                }
            }
            WorkflowNodeType.OUTPUT -> {
                val port = text("port")
                when {
                    port == null ->
                        issues += issue("WF_CONFIG_MISSING", node.nodeId, "An output node requires a port.")
                    port !in outputPorts ->
                        issues += issue("WF_UNKNOWN_OUTPUT_PORT", node.nodeId, "Output port is not declared.")
                }
            }
        }
    }

    private fun topologicalOrder(
        nodeIds: Set<String>,
        edges: List<WorkflowEdge>
    ): List<String>? {
        val indegree = nodeIds.associateWith { 0 }.toMutableMap()
        val adjacency = nodeIds.associateWith { ArrayList<String>() }.toMutableMap()
        edges.forEach { edge ->
            if (edge.fromNodeId in nodeIds && edge.toNodeId in nodeIds && edge.fromNodeId != edge.toNodeId) {
                adjacency.getValue(edge.fromNodeId) += edge.toNodeId
                indegree[edge.toNodeId] = (indegree[edge.toNodeId] ?: 0) + 1
            }
        }
        val queue = ArrayDeque(nodeIds.filter { indegree[it] == 0 })
        val order = ArrayList<String>(nodeIds.size)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            order += current
            adjacency.getValue(current).forEach { next ->
                val remaining = (indegree[next] ?: 0) - 1
                indegree[next] = remaining
                if (remaining == 0) queue.addLast(next)
            }
        }
        return if (order.size == nodeIds.size) order else null
    }

    private fun reachableFrom(start: String, edges: List<WorkflowEdge>): Set<String> {
        val adjacency = edges.groupBy({ it.fromNodeId }, { it.toNodeId })
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            adjacency[current]?.forEach { next -> if (next !in seen) queue.addLast(next) }
        }
        return seen
    }

    private fun canReach(targets: Set<String>, edges: List<WorkflowEdge>): Set<String> {
        val reverse = edges.groupBy({ it.toNodeId }, { it.fromNodeId })
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(targets)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            reverse[current]?.forEach { previous -> if (previous !in seen) queue.addLast(previous) }
        }
        return seen
    }

    private fun issue(code: String, nodeId: String?, message: String) =
        WorkflowValidationIssue(code, nodeId, message)

    /** Pinned identity of a referenced definition. */
    data class SubworkflowRef(val definitionId: String, val version: Long)

    private companion object {
        const val MAX_NODES = 100
        const val MAX_EDGES = 400
        const val MAX_PORTS = 32
        const val MAX_SUBWORKFLOW_DEPTH = 8
    }
}

/** Canonical hash of a definition payload (see [WorkflowDefinition.definitionHash]). */
object WorkflowDefinitionHasher {
    private val codec: StrictJsonCodec = StrictAgentJsonCodec()

    fun hash(definition: WorkflowDefinition): String {
        val payload = AgentJsonValues.obj(
            "definitionId" to AgentJsonValues.str(definition.definitionId),
            "version" to AgentJsonValues.long(definition.version),
            "displayName" to AgentJsonValues.str(definition.displayName),
            "inputs" to AgentJsonValues.arr(definition.inputs.map(::portJson)),
            "outputs" to AgentJsonValues.arr(definition.outputs.map(::portJson)),
            "nodes" to AgentJsonValues.arr(definition.nodes.map(::nodeJson)),
            "edges" to AgentJsonValues.arr(definition.edges.map(::edgeJson)),
            "budget" to AgentJsonValues.obj(
                "maxModelCalls" to AgentJsonValues.int(definition.budget.maxModelCalls),
                "maxToolCalls" to AgentJsonValues.int(definition.budget.maxToolCalls),
                "maxEstimatedTokens" to AgentJsonValues.int(definition.budget.maxEstimatedTokens),
                "maxDurationMillis" to AgentJsonValues.long(definition.budget.maxDurationMillis)
            ),
            "failurePolicy" to AgentJsonValues.str(definition.failurePolicy.name),
            "triggerRef" to (definition.triggerRef?.let(AgentJsonValues::str) ?: AgentJsonValue.Null)
        )
        return AgentHashes.sha256HexUtf8(codec.write(payload))
    }

    private fun portJson(port: com.muyuchat.core.agent.contract.workflow.WorkflowPort) = AgentJsonValues.obj(
        "name" to AgentJsonValues.str(port.name),
        "type" to AgentJsonValues.str(port.type.name),
        "required" to AgentJsonValues.bool(port.required)
    )

    private fun nodeJson(node: WorkflowNode) = AgentJsonValues.obj(
        "nodeId" to AgentJsonValues.str(node.nodeId),
        "type" to AgentJsonValues.str(node.type.name),
        "label" to AgentJsonValues.str(node.label),
        "config" to ((codec.parse(node.configJson) as? AgentJsonParseResult.Parsed)?.value
            ?: AgentJsonValue.Obj(emptyList())),
        "subworkflowDefinitionId" to (node.subworkflowDefinitionId?.let(AgentJsonValues::str) ?: AgentJsonValue.Null),
        "subworkflowVersion" to (node.subworkflowVersion?.let(AgentJsonValues::long) ?: AgentJsonValue.Null)
    )

    private fun edgeJson(edge: WorkflowEdge) = AgentJsonValues.obj(
        "from" to AgentJsonValues.str(edge.fromNodeId),
        "to" to AgentJsonValues.str(edge.toNodeId),
        "when" to (edge.whenLabel?.let(AgentJsonValues::str) ?: AgentJsonValue.Null)
    )
}
