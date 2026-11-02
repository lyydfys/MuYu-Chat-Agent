package com.muyuchat.core.agent.runtime.run

import com.muyuchat.core.agent.contract.AgentScopePrecondition

/**
 * Optional executor capability: declares the exact typed resource scope of this tool.
 *
 * The frozen-command factory needs a scope before approval. Tools whose identity is not a workspace
 * path (MCP network tools, memory, device) provide it here from host facts owned by the executor, so
 * model text can never widen or invent the scope.
 */
interface AgentTypedScopeSource {
    fun scopePrecondition(): AgentScopePrecondition?
}
