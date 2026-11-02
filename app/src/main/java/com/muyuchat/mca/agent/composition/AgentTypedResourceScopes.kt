package com.muyuchat.mca.agent.composition

import com.muyuchat.core.agent.contract.AgentEffectKind
import com.muyuchat.core.agent.contract.AgentResourceScope
import com.muyuchat.core.agent.contract.AgentScopePrecondition
import com.muyuchat.core.agent.runtime.run.AgentFrozenCommandRequest
import com.muyuchat.mca.agent.memory.AgentMemoryResourceScopes
import com.muyuchat.mca.agent.memory.AgentMemoryToolDescriptors
import com.muyuchat.mca.agent.tools.AgentDeviceInfoTool
import com.muyuchat.mca.agent.tools.AgentDeviceNotificationTool
import com.muyuchat.mca.agent.tools.AgentDeviceClipboardTool
import com.muyuchat.mca.agent.tools.AgentDeviceNotificationSettingsTool
import com.muyuchat.mca.agent.tools.AgentDeviceOpenUrlTool
import com.muyuchat.mca.agent.tools.AgentDeviceScopes
import com.muyuchat.mca.agent.tools.AgentDeviceStatusTool

/**
 * Host-side policy that assigns a typed resource scope to tools whose real resource identity is not
 * a workspace path (N02c).
 *
 * The scope is derived only from frozen host facts: `memory.search` reads exactly the memory spaces
 * frozen into the run policy at task creation (`AgentMemorySelectionService` wrote them in the same
 * transaction as the task). Model text, tool descriptions and MCP/Skill hints never contribute a
 * space id.
 *
 * A tool with no typed identity (built-in workspace/artifact tools) keeps the V1 workspace scope, so
 * existing approvals stay readable and their fingerprints do not change.
 */
internal object AgentTypedResourceScopes {
    /** Tools whose resource identity is not a workspace path and therefore require a typed scope. */
    fun requiresTypedScope(toolKey: String): Boolean =
        toolKey.startsWith("mcp:") || when (toolKey) {
        AgentMemoryToolDescriptors.TOOL_KEY,
        AgentMemoryToolDescriptors.TOOL_KEY_GET,
        AgentDeviceInfoTool.TOOL_KEY,
        AgentDeviceStatusTool.TOOL_KEY,
        AgentDeviceNotificationTool.TOOL_KEY,
        AgentDeviceOpenUrlTool.TOOL_KEY,
        AgentDeviceClipboardTool.TOOL_KEY,
        AgentDeviceNotificationSettingsTool.TOOL_KEY -> true
        else -> false
    }

    fun forRequest(request: AgentFrozenCommandRequest): AgentScopePrecondition? =
        when (request.descriptor.toolKey) {
            AgentMemoryToolDescriptors.TOOL_KEY,
            AgentMemoryToolDescriptors.TOOL_KEY_GET ->
                AgentMemoryResourceScopes.precondition(request.run.policy.memorySpaceIds)

            AgentDeviceInfoTool.TOOL_KEY -> AgentDeviceScopes.info()

            AgentDeviceStatusTool.TOOL_KEY -> AgentDeviceScopes.status()

            AgentDeviceNotificationTool.TOOL_KEY ->
                AgentDeviceScopes.notification(request.normalizedArgumentsJson)

            AgentDeviceOpenUrlTool.TOOL_KEY ->
                AgentDeviceScopes.openUrl(request.normalizedArgumentsJson)

            AgentDeviceClipboardTool.TOOL_KEY ->
                AgentDeviceScopes.clipboardWrite(request.normalizedArgumentsJson)

            AgentDeviceNotificationSettingsTool.TOOL_KEY -> AgentDeviceScopes.notificationSettings()

            else -> null
        }
}
