package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolCatalog
import com.muyuchat.mca.agent.workflow.WorkflowToolRegistry
import com.muyuchat.mca.agent.workflow.WorkflowToolTarget

/**
 * Adapts the one existing tool catalog to the workflow's narrow [WorkflowToolRegistry] port.
 *
 * The workflow package deliberately cannot see [AgentToolCatalog]: this adapter is the composition
 * root's single bridge, so a workflow node can only look up an already registered executor and call
 * that executor's own `execute`. It never builds a tool, a loop or a second catalog.
 */
class CatalogWorkflowToolRegistry(
    private val catalog: AgentToolCatalog
) : WorkflowToolRegistry {
    override fun find(toolKey: String): WorkflowToolTarget? {
        val executor = catalog.find(toolKey) ?: return null
        return WorkflowToolTarget(
            descriptor = executor.descriptor,
            execute = { command, context -> executor.execute(command, context) }
        )
    }
}
