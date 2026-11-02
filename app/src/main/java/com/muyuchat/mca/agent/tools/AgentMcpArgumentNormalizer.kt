package com.muyuchat.mca.agent.tools

import com.muyuchat.core.agent.contract.AgentToolDescriptor
import com.muyuchat.mca.agent.security.AgentArgumentNormalization
import com.muyuchat.mca.agent.security.AgentSecurityErrors
import com.muyuchat.mca.agent.security.AgentToolArgumentNormalizer
import org.json.JSONObject

/**
 * Argument normalizer for MCP tools (`mcp:<serverId>:<name>`).
 *
 * MCP schemas are validated by the remote tool adapter before this point; the normalizer only needs
 * to preserve an object-shaped argument document and expose no workspace logical paths. The typed
 * network scope is supplied separately at freeze time.
 */
class AgentMcpArgumentNormalizer : AgentToolArgumentNormalizer {
    override fun normalize(descriptor: AgentToolDescriptor, rawArgumentsJson: String): AgentArgumentNormalization {
        if (!descriptor.toolKey.startsWith(MCP_PREFIX)) {
            return AgentArgumentNormalization.Rejected(AgentSecurityErrors.toolNotRegistered())
        }
        val trimmed = rawArgumentsJson.trim()
        val parsed = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return AgentArgumentNormalization.Rejected(
                AgentSecurityErrors.invalidArguments("MCP arguments must be a JSON object.")
            )
        return AgentArgumentNormalization.Normalized(
            normalizedArgumentsJson = parsed.toString(),
            logicalPaths = emptyList(),
            expectedOldHash = null
        )
    }

    companion object {
        const val MCP_PREFIX: String = "mcp:"
    }
}
