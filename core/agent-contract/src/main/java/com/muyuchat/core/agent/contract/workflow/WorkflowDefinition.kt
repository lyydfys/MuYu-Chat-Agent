package com.muyuchat.core.agent.contract.workflow

import com.muyuchat.core.agent.contract.AgentContract

/** Node types of the workflow graph (DESIGN §7.1). */
enum class WorkflowNodeType {
    TRIGGER,
    TOOL,
    AGENT_TASK,
    MODEL,
    SCRIPT,
    CONDITION,
    LOGIC,
    EXTRACT,
    WAIT_APPROVAL,
    SUBWORKFLOW,
    /** MCA bounded iteration over a pinned child graph; never an arbitrary back edge. */
    FOREACH,
    OUTPUT,
    /**
     * A compensation step bound to one succeeded node. The runner never schedules it in the normal
     * flow: after a terminal failure it runs the declared compensation through the same tool path
     * (and therefore the same approval) and still reports the original failure.
     */
    COMPENSATE
}

/** How a failed activated node affects the run (DESIGN §7.4). */
enum class WorkflowFailurePolicy {
    FAIL_FAST,
    CONTINUE,
    WAIT_FOR_USER
}

/** One typed input/output port of a published definition. */
data class WorkflowPort(
    val name: String,
    val type: WorkflowPortType,
    val required: Boolean = true
) {
    init {
        AgentContract.requireId(name, "port name")
        require(name.length <= MAX_PORT_NAME_CHARS) { "port name is too long" }
    }

    private companion object {
        const val MAX_PORT_NAME_CHARS = 64
    }
}

enum class WorkflowPortType {
    STRING,
    NUMBER,
    BOOLEAN,
    JSON,
    ARTIFACT
}

/**
 * One node. [configJson] is a bounded, canonical JSON object whose required fields depend on
 * [type]; it never contains executable code. [subworkflowVersion] pins a subworkflow definition.
 */
data class WorkflowNode(
    val nodeId: String,
    val type: WorkflowNodeType,
    val label: String = "",
    val configJson: String = "{}",
    val subworkflowDefinitionId: String? = null,
    val subworkflowVersion: Long? = null
) {
    init {
        AgentContract.requireId(nodeId, "nodeId")
        require(label.length <= MAX_LABEL_CHARS) { "node label is too long" }
        require(configJson.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) {
            "node config exceeds $MAX_CONFIG_BYTES bytes"
        }
        require((subworkflowDefinitionId == null) == (subworkflowVersion == null)) {
            "a subworkflow reference needs both a definition id and a pinned version"
        }
        require(type == WorkflowNodeType.SUBWORKFLOW || subworkflowDefinitionId == null) {
            "only a subworkflow node may pin another definition"
        }
    }

    private companion object {
        const val MAX_LABEL_CHARS = 80
        const val MAX_CONFIG_BYTES = 8 * 1024
    }
}

/**
 * One directed edge. [whenLabel] is set only on a condition node's outgoing edges and is the route
 * label ("true"/"false"/...); any other node must not label its edges.
 */
data class WorkflowEdge(
    val fromNodeId: String,
    val toNodeId: String,
    val whenLabel: String? = null
) {
    init {
        AgentContract.requireId(fromNodeId, "edge fromNodeId")
        AgentContract.requireId(toNodeId, "edge toNodeId")
        whenLabel?.let {
            AgentContract.requireNotBlank(it, "edge whenLabel")
            require(it.length <= 32) { "edge label is too long" }
        }
    }
}

/** Run budget shared by every frame of this definition. */
data class WorkflowBudget(
    val maxModelCalls: Int = 32,
    val maxToolCalls: Int = 64,
    val maxEstimatedTokens: Int = 200_000,
    val maxDurationMillis: Long = 30 * 60 * 1000L
) {
    init {
        require(maxModelCalls in 1..10_000) { "maxModelCalls is out of range" }
        require(maxToolCalls in 1..10_000) { "maxToolCalls is out of range" }
        require(maxEstimatedTokens in 1..10_000_000) { "maxEstimatedTokens is out of range" }
        require(maxDurationMillis in 1L..24L * 60 * 60 * 1000) { "maxDurationMillis is out of range" }
    }
}

/**
 * One immutable, published workflow definition version.
 *
 * The graph is a plain DAG. Repetition is expressed by explicit bounded control nodes (a later
 * card), never by arbitrary back edges. [definitionHash] is computed over the canonical payload
 * (see `WorkflowDefinitionHasher`); editing a draft can never change a run's pinned version.
 */
data class WorkflowDefinition(
    val definitionId: String,
    val version: Long,
    val displayName: String,
    val inputs: List<WorkflowPort> = emptyList(),
    val outputs: List<WorkflowPort> = emptyList(),
    val nodes: List<WorkflowNode>,
    val edges: List<WorkflowEdge>,
    val budget: WorkflowBudget = WorkflowBudget(),
    val failurePolicy: WorkflowFailurePolicy = WorkflowFailurePolicy.FAIL_FAST,
    val triggerRef: String? = null,
    val definitionHash: String = ""
) {
    init {
        AgentContract.requireId(definitionId, "definitionId")
        require(version >= 1L) { "definition version must be >= 1" }
        require(displayName.isNotBlank()) { "definition displayName must not be blank" }
        require(nodes.isNotEmpty()) { "a definition needs at least one node" }
        triggerRef?.let {
            AgentContract.requireNotBlank(it, "triggerRef")
            require(it.length <= 128) { "triggerRef is too long" }
        }
        require(definitionHash.isEmpty() || definitionHash.matches(Regex("^[0-9a-f]{64}$"))) {
            "definitionHash must be a lowercase SHA-256 string"
        }
    }
}
