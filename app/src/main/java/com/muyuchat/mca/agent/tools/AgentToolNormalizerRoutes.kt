package com.muyuchat.mca.agent.tools

import com.muyuchat.mca.agent.memory.AgentMemoryToolArgumentNormalizer
import com.muyuchat.mca.agent.memory.AgentMemoryToolDescriptors

/**
 * The production normalizer route table, shared by composition and the coverage test.
 *
 * Keeping one table (instead of inlining it in the assembly and mirroring it in a test) is what
 * makes "every advertised tool has a normalizer" checkable rather than aspirational.
 */
object AgentToolNormalizerRoutes {
    fun production(): AgentDispatchToolArgumentNormalizer = AgentDispatchToolArgumentNormalizer(
        routes = listOf(
            setOf(
                AgentMemoryToolDescriptors.TOOL_KEY,
                AgentMemoryToolDescriptors.TOOL_KEY_GET
            ) to AgentMemoryToolArgumentNormalizer(),
            setOf(
                AgentDeviceInfoTool.TOOL_KEY,
                AgentDeviceStatusTool.TOOL_KEY,
                AgentDeviceNotificationSettingsTool.TOOL_KEY
            ) to AgentDeviceInfoArgumentNormalizer(),
            setOf(AgentDeviceNotificationTool.TOOL_KEY) to AgentDeviceNotificationArgumentNormalizer(),
            setOf(AgentDeviceOpenUrlTool.TOOL_KEY) to AgentDeviceOpenUrlArgumentNormalizer(),
            setOf(AgentDeviceClipboardTool.TOOL_KEY) to AgentDeviceClipboardArgumentNormalizer(),
            setOf(AgentScreenTool.TOOL_KEY) to AgentScreenArgumentNormalizer(),
            setOf(
                AgentExtendedToolArgumentNormalizer.WORKSPACE_WRITE_TEXT,
                AgentExtendedToolArgumentNormalizer.WORKSPACE_EDIT_TEXT,
                AgentExtendedToolArgumentNormalizer.HTTP_GET,
                AgentExtendedToolArgumentNormalizer.WEB_FETCH,
                AgentExtendedToolArgumentNormalizer.TERMINAL_EXEC
            ) to AgentExtendedToolArgumentNormalizer()
        ),
        prefixRoutes = listOf(
            AgentMcpArgumentNormalizer.MCP_PREFIX to AgentMcpArgumentNormalizer()
        )
    )
}
