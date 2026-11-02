package com.muyuchat.feature.agent.workspace

/**
 * Feature-local MCP server registry port.
 *
 * This surface manages durable user-authored server configs and the user's explicit Agent-network
 * authorization. It never opens a socket, performs discovery or stores a token: the host adapter
 * maps these calls to `AgentMcpServerStore`, whose records always build `McpAuth.None`.
 *
 * Turning Agent network access on records a server-scope authorization (canonical origin +
 * transport + explicit data classes); turning it off revokes it. A run still connects only when the
 * run-start binder can derive a run-scoped egress grant, and every MCP tool still requires its own
 * approval. "Enabled" here is a registry preference, not connectivity.
 */

object AgentMcpAdminLimits {
    const val MAX_DISPLAY_NAME_CHARS: Int = 128
    const val MAX_ENDPOINT_CHARS: Int = 2_048
}

/** One registered server as the management page needs it; no token/header/credential field. */
data class AgentMcpServerItem(
    val serverId: String,
    val displayName: String,
    val endpoint: String,
    val enabled: Boolean,
    val allowLocal: Boolean,
    val statusLabel: String? = null,
    /** Explicit consent candidate for Agent network runs; default off and never a token. */
    val allowAgentNetwork: Boolean = false
) {
    init {
        require(serverId.isNotBlank()) { "serverId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(endpoint.isNotBlank()) { "endpoint must not be blank" }
    }
}

sealed interface AgentMcpAdminListResult {
    data class Loaded(val servers: List<AgentMcpServerItem>) : AgentMcpAdminListResult

    data class Rejected(val code: String, val safeMessage: String) : AgentMcpAdminListResult {
        init {
            require(code.isNotBlank()) { "list rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "list rejection message must not be blank" }
        }
    }
}

/** Typed operation outcomes; [Rejected] is a normal value whose message is safe to render. */
sealed interface AgentMcpAdminOutcome {
    data class Added(val item: AgentMcpServerItem) : AgentMcpAdminOutcome

    data class Updated(val item: AgentMcpServerItem) : AgentMcpAdminOutcome

    data class Enabled(val item: AgentMcpServerItem) : AgentMcpAdminOutcome

    data class Disabled(val item: AgentMcpServerItem) : AgentMcpAdminOutcome

    data class Removed(val serverId: String, val displayName: String) : AgentMcpAdminOutcome {
        init {
            require(serverId.isNotBlank()) { "removed serverId must not be blank" }
            require(displayName.isNotBlank()) { "removed displayName must not be blank" }
        }
    }

    data class Rejected(val code: String, val safeMessage: String) : AgentMcpAdminOutcome {
        init {
            require(code.isNotBlank()) { "rejection code must not be blank" }
            require(safeMessage.isNotBlank()) { "rejection message must not be blank" }
        }
    }
}

interface AgentMcpAdminPort {
    suspend fun list(): AgentMcpAdminListResult

    /** The host generates a stable id and validates endpoint policy before persisting. */
    suspend fun add(
        displayName: String,
        endpoint: String,
        allowLocal: Boolean,
        bearerToken: String? = null
    ): AgentMcpAdminOutcome

    suspend fun setEnabled(serverId: String, enabled: Boolean): AgentMcpAdminOutcome

    /** Persists the per-server network-consent preference; returns [AgentMcpAdminOutcome.Updated]. */
    suspend fun setAgentNetworkAllowed(
        serverId: String,
        allowed: Boolean
    ): AgentMcpAdminOutcome

    suspend fun remove(serverId: String): AgentMcpAdminOutcome

    companion object {
        /** Preview/test fallback only; production wiring must provide a real port. */
        val Unavailable: AgentMcpAdminPort = object : AgentMcpAdminPort {
            override suspend fun list(): AgentMcpAdminListResult = rejectedList()

            override suspend fun add(
                displayName: String,
                endpoint: String,
                allowLocal: Boolean,
                bearerToken: String?
            ): AgentMcpAdminOutcome = rejected()

            override suspend fun setEnabled(
                serverId: String,
                enabled: Boolean
            ): AgentMcpAdminOutcome = rejected()

            override suspend fun setAgentNetworkAllowed(
                serverId: String,
                allowed: Boolean
            ): AgentMcpAdminOutcome = rejected()

            override suspend fun remove(serverId: String): AgentMcpAdminOutcome = rejected()

            private fun rejected() = AgentMcpAdminOutcome.Rejected(
                code = "MCP_ADMIN_UNAVAILABLE",
                safeMessage = "MCP 管理尚未接入。"
            )

            private fun rejectedList() = AgentMcpAdminListResult.Rejected(
                code = "MCP_ADMIN_UNAVAILABLE",
                safeMessage = "MCP 管理尚未接入。"
            )
        }
    }
}
