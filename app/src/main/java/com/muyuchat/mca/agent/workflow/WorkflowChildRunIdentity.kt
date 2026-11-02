package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.runtime.AgentJsonValue
import com.muyuchat.core.agent.runtime.protocol.AgentHashes
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeOutcome
import com.muyuchat.core.agent.runtime.workflow.WorkflowNodeState
import com.muyuchat.core.agent.runtime.workflow.WorkflowRunCheckpoint

/**
 * Deterministic child-run identity for SUBWORKFLOW and FOREACH nodes.
 *
 * The id is a pure function of the parent identity, the node id and (for FOREACH) the item index,
 * so a restarted parent resolves the exact same child run and its checkpoint instead of starting a
 * second execution. The 48-hex suffix keeps the result inside the safe-run-id budget.
 */
internal fun deriveChildRunId(parentRunId: String, nodeId: String): String =
    "wfsub-" + AgentHashes.sha256HexUtf8("$parentRunId\u0000$nodeId").take(48)

internal fun deriveForeachChildRunId(parentRunId: String, nodeId: String, index: Int): String =
    "wfforeach-" + AgentHashes.sha256HexUtf8(
        "$parentRunId\u0000$nodeId\u0000$index"
    ).take(48)

/**
 * Checkpoint a FOREACH child starts from: the trigger node is already SUCCEEDED with the item as
 * its output, so only the trigger's outgoing edges are active and nothing is re-executed.
 */
internal fun newForeachCheckpoint(
    childDefinition: WorkflowDefinition,
    trigger: WorkflowNode,
    item: AgentJsonValue
): WorkflowRunCheckpoint {
    val activeEdges = childDefinition.edges
        .filter { it.fromNodeId == trigger.nodeId }
        .map { "${it.fromNodeId}->${it.toNodeId}:${it.whenLabel.orEmpty()}" }
        .toSet()
    return WorkflowRunCheckpoint(
        definitionId = childDefinition.definitionId,
        definitionVersion = childDefinition.version,
        revision = 0,
        outcomes = listOf(
            WorkflowNodeOutcome(trigger.nodeId, WorkflowNodeState.SUCCEEDED, item)
        ),
        nodeOutputs = mapOf(trigger.nodeId to item),
        outputs = emptyMap(),
        activeEdges = activeEdges,
        skipped = emptySet()
    )
}
