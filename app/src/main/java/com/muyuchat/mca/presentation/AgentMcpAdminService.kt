package com.muyuchat.mca.presentation

import android.content.Context
import com.muyuchat.feature.agent.workspace.AgentMcpAdminListResult
import com.muyuchat.feature.agent.workspace.AgentMcpAdminOutcome
import com.muyuchat.feature.agent.workspace.AgentMcpAdminPort
import com.muyuchat.feature.agent.workspace.AgentMcpServerItem
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerLoad
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerMutation
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerRecord
import com.muyuchat.mca.agent.extensions.mcp.AgentMcpServerStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Host adapter for the MCP server management page. It reads/writes the durable registry and records
 * the user's explicit Agent-network authorization; it never opens a socket or performs discovery.
 *
 * The existing "allow Agent network" switch is the user-facing authorization action:
 * - turning it on calls [AgentMcpServerStore.authorizeForAgent], which snapshots the canonical origin
 *   and transport and grants only the explicit MCP data boundary (`PRIVATE`);
 * - turning it off revokes that durable authorization immediately.
 * A run-start binder may then derive a run-scoped egress grant for a network-enabled run; a
 * LOCAL_ONLY/offline run, or a run without this authorization, never connects. Per-call approval is
 * still required because every MCP tool is `EXTERNAL_SIDE_EFFECT`.
 */
class AgentMcpAdminService(
    context: Context,
    private val store: AgentMcpServerStore,
    private val credentials: com.muyuchat.mca.agent.extensions.mcp.McpCredentialStore =
        com.muyuchat.mca.agent.extensions.mcp.KeystoreMcpCredentialStore(context)
) : AgentMcpAdminPort {
    private val clock = AtomicLong()

    private companion object {
        const val MAX_TOKEN_CHARS: Int = 4_096
    }

    override suspend fun list(): AgentMcpAdminListResult = when (val loaded = store.load()) {
        is AgentMcpServerLoad.Loaded -> AgentMcpAdminListResult.Loaded(loaded.servers.map(::toItem))
        is AgentMcpServerLoad.Corrupt -> AgentMcpAdminListResult.Rejected(
            loaded.reasonCode,
            "MCP 配置无法读取，请修复或删除配置文件后重试。"
        )
    }

    override suspend fun add(
        displayName: String,
        endpoint: String,
        allowLocal: Boolean,
        bearerToken: String?
    ): AgentMcpAdminOutcome {
        val token = bearerToken?.trim()?.ifBlank { null }
        if (token != null && (token.length > MAX_TOKEN_CHARS || token.any { it.isISOControl() })) {
            return AgentMcpAdminOutcome.Rejected("MCP_AUTH_INVALID", "MCP 访问令牌无效。")
        }
        val serverId = newServerId(displayName)
        val credentialRefId = token?.let { "mcp-$serverId" }
        val record = runCatching {
            AgentMcpServerRecord(
                serverId = serverId,
                displayName = displayName.trim(),
                endpoint = endpoint.trim(),
                enabled = true,
                allowLocal = allowLocal,
                createdAtMillis = clock.updateAndGet { System.currentTimeMillis() },
                credentialRefId = credentialRefId
            )
        }.getOrElse { failure ->
            return AgentMcpAdminOutcome.Rejected(
                "MCP_CONFIG_INVALID",
                failure.message?.takeIf { it.isNotBlank() } ?: "MCP 配置无效。"
            )
        }
        if (token != null && credentialRefId != null && !credentials.put(credentialRefId, token)) {
            return AgentMcpAdminOutcome.Rejected("MCP_AUTH_STORE_FAILED", "MCP 访问令牌未能安全保存。")
        }
        return when (val mutation = store.add(record)) {
            is AgentMcpServerMutation.Applied -> AgentMcpAdminOutcome.Added(toItem(record))
            is AgentMcpServerMutation.Rejected -> {
                credentialRefId?.let(credentials::remove)
                AgentMcpAdminOutcome.Rejected(mutation.code, mutation.message)
            }
        }
    }

    override suspend fun setEnabled(serverId: String, enabled: Boolean): AgentMcpAdminOutcome {
        val before = find(serverId)
        return when (val mutation = store.setEnabled(serverId, enabled)) {
            is AgentMcpServerMutation.Applied -> {
                val item = mutation.servers.firstOrNull { it.serverId == serverId }?.let(::toItem)
                    ?: before?.copy(enabled = enabled)?.let(::toItem)
                    ?: return AgentMcpAdminOutcome.Rejected("MCP_SERVER_NOT_FOUND", "MCP 服务器不存在。")
                if (enabled) AgentMcpAdminOutcome.Enabled(item)
                else AgentMcpAdminOutcome.Disabled(item)
            }
            is AgentMcpServerMutation.Rejected -> AgentMcpAdminOutcome.Rejected(
                mutation.code,
                mutation.message
            )
        }
    }

    override suspend fun setAgentNetworkAllowed(
        serverId: String,
        allowed: Boolean
    ): AgentMcpAdminOutcome {
        val before = find(serverId)
        return when (val mutation = store.setAgentNetworkAllowed(serverId, allowed)) {
            is AgentMcpServerMutation.Applied -> {
                val item = mutation.servers.firstOrNull { it.serverId == serverId }?.let(::toItem)
                    ?: before?.copy(allowAgentNetwork = allowed)?.let(::toItem)
                    ?: return AgentMcpAdminOutcome.Rejected("MCP_SERVER_NOT_FOUND", "MCP 服务器不存在。")
                AgentMcpAdminOutcome.Updated(item)
            }
            is AgentMcpServerMutation.Rejected -> AgentMcpAdminOutcome.Rejected(
                mutation.code,
                mutation.message
            )
        }
    }

    override suspend fun remove(serverId: String): AgentMcpAdminOutcome {
        val before = find(serverId)
        return when (val mutation = store.remove(serverId)) {
            is AgentMcpServerMutation.Applied -> AgentMcpAdminOutcome.Removed(
                serverId,
                before?.displayName ?: serverId
            )
            is AgentMcpServerMutation.Rejected -> AgentMcpAdminOutcome.Rejected(
                mutation.code,
                mutation.message
            )
        }
    }

    private fun find(serverId: String): AgentMcpServerRecord? =
        (store.load() as? AgentMcpServerLoad.Loaded)?.servers?.firstOrNull { it.serverId == serverId }

    private fun toItem(record: AgentMcpServerRecord): AgentMcpServerItem = AgentMcpServerItem(
        serverId = record.serverId,
        displayName = record.displayName,
        endpoint = record.endpoint,
        enabled = record.enabled,
        allowLocal = record.allowLocal,
        allowAgentNetwork = record.allowAgentNetwork
    )

    private fun newServerId(displayName: String): String {
        val base = displayName.lowercase()
            .map { character ->
                if (character in 'a'..'z' || character in '0'..'9') character else '-'
            }
            .joinToString("")
            .trim('-')
            .take(40)
            .ifBlank { "server" }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "mcp-$base-$suffix".trim('-').take(64)
    }
}
