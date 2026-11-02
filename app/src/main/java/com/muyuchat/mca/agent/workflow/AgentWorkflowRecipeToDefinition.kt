package com.muyuchat.mca.agent.workflow

import com.muyuchat.core.agent.contract.workflow.WorkflowDefinition
import com.muyuchat.core.agent.contract.workflow.WorkflowEdge
import com.muyuchat.core.agent.contract.workflow.WorkflowNode
import com.muyuchat.core.agent.contract.workflow.WorkflowNodeType
import com.muyuchat.core.agent.contract.workflow.WorkflowPort
import com.muyuchat.core.agent.contract.workflow.WorkflowPortType
import org.json.JSONObject

/**
 * Converts a legacy recipe into one immutable graph definition version (N17).
 *
 * The conversion is intentionally lossless only where the legacy model can express it: the graph is
 * `Trigger -> AgentTask -> Output`, the recipe goal becomes the AgentTask node's goal template, and
 * declared inputs become typed string inputs. Recipe metadata (model policy, tool allowlist,
 * approval policy) stays on the recipe and is validated by the existing preflight; the graph node
 * does not invent fields the old model did not have.
 *
 * The source recipe id/version are recorded (`recipe-<id>`, version) so a later conversion is
 * distinguishable, and the caller must publish the result as a new version — the old task/recipe
 * history is never mutated.
 */
object AgentWorkflowRecipeToDefinition {
    fun convert(recipe: AgentWorkflowRecipe): WorkflowDefinition = WorkflowDefinition(
        definitionId = "recipe-${recipe.id}",
        version = recipe.version,
        displayName = recipe.title,
        inputs = recipe.inputs.map { source ->
            WorkflowPort(name = source.id, type = WorkflowPortType.STRING, required = source.required)
        },
        outputs = listOf(WorkflowPort(name = RESULT_PORT, type = WorkflowPortType.STRING)),
        nodes = listOf(
            WorkflowNode(nodeId = TRIGGER_NODE, type = WorkflowNodeType.TRIGGER),
            WorkflowNode(
                nodeId = RUN_NODE,
                type = WorkflowNodeType.AGENT_TASK,
                configJson = JSONObject().put("goal", recipe.goalTemplate).toString()
            ),
            WorkflowNode(
                nodeId = OUTPUT_NODE,
                type = WorkflowNodeType.OUTPUT,
                configJson = JSONObject().put("port", RESULT_PORT).toString()
            )
        ),
        edges = listOf(
            WorkflowEdge(fromNodeId = TRIGGER_NODE, toNodeId = RUN_NODE),
            WorkflowEdge(fromNodeId = RUN_NODE, toNodeId = OUTPUT_NODE)
        ),
        triggerRef = "manual"
    )

    const val RESULT_PORT = "result"
    const val TRIGGER_NODE = "trigger"
    const val RUN_NODE = "run"
    const val OUTPUT_NODE = "done"
}
