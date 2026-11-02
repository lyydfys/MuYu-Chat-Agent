package com.muyuchat.feature.agent.workspace

import java.util.Locale

/** Operation currently owned by [AgentMcpCoordinator]; at most one is in flight. */
enum class AgentMcpOperationKind { LISTING, ADD, TOGGLE, NETWORK, REMOVE }

data class AgentMcpAdminErrorUi(
    val code: String,
    val message: String
)

/** Editable add-dialog state; a rejected submit keeps the draft and its safe message. */
data class AgentMcpAddDraftUi(
    val displayName: String = "",
    val endpoint: String = "",
    val allowLocal: Boolean = false,
    val bearerToken: String = "",
    val validationMessage: String? = null
)

data class AgentMcpRemoveConfirmationUi(
    val serverId: String,
    val displayName: String
)

/** Projected row for the MCP list; endpoints are shown as text, never opened or probed. */
data class AgentMcpUiItem(
    val serverId: String,
    val displayName: String,
    val endpoint: String,
    val enabled: Boolean,
    val allowLocal: Boolean,
    val statusLabel: String,
    val allowAgentNetwork: Boolean = false
)

data class AgentMcpUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val servers: List<AgentMcpUiItem> = emptyList(),
    val busy: AgentMcpOperationKind? = null,
    val error: AgentMcpAdminErrorUi? = null,
    val notice: String? = null,
    val addDraft: AgentMcpAddDraftUi? = null,
    val removeConfirmation: AgentMcpRemoveConfirmationUi? = null
) {
    val empty: Boolean get() = !loading && servers.isEmpty()

    val enabledCount: Int get() = servers.count { it.enabled }

    val canAdd: Boolean get() = busy == null && addDraft == null

    fun canToggle(): Boolean = busy == null

    fun canRemove(): Boolean = busy == null
}

internal data class AgentMcpProjectionInput(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val servers: List<AgentMcpServerItem> = emptyList(),
    val busy: AgentMcpOperationKind? = null,
    val error: AgentMcpAdminErrorUi? = null,
    val notice: String? = null,
    val addDraft: AgentMcpAddDraftUi? = null,
    val removeConfirmation: AgentMcpRemoveConfirmationUi? = null
)

internal fun projectMcpState(input: AgentMcpProjectionInput): AgentMcpUiState = AgentMcpUiState(
    visible = input.visible,
    loading = input.loading,
    servers = input.servers.map(::projectMcpServer)
        .sortedWith(compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.serverId })),
    busy = input.busy,
    error = input.error,
    notice = input.notice,
    addDraft = input.addDraft,
    removeConfirmation = input.removeConfirmation
)

internal fun projectMcpServer(item: AgentMcpServerItem): AgentMcpUiItem = AgentMcpUiItem(
    serverId = item.serverId,
    displayName = item.displayName.take(AgentMcpAdminLimits.MAX_DISPLAY_NAME_CHARS),
    endpoint = item.endpoint.take(AgentMcpAdminLimits.MAX_ENDPOINT_CHARS),
    enabled = item.enabled,
    allowLocal = item.allowLocal,
    statusLabel = item.statusLabel?.takeIf { it.isNotBlank() }
        ?: if (item.enabled) "已启用" else "已停用",
    allowAgentNetwork = item.allowAgentNetwork
)

/**
 * Fast, user-safe form validation for the add dialog. The host store remains authoritative for the
 * full endpoint policy (credentials/query/fragments/private-host rules); a port rejection is shown
 * in the same dialog without closing it.
 */
internal fun mcpDraftValidationError(
    displayName: String,
    endpoint: String,
    allowLocal: Boolean
): String? {
    val name = displayName.trim()
    if (name.isEmpty()) return "请输入服务器名称。"
    if (name.length > AgentMcpAdminLimits.MAX_DISPLAY_NAME_CHARS) {
        return "名称不能超过 ${AgentMcpAdminLimits.MAX_DISPLAY_NAME_CHARS} 个字符。"
    }
    if (name.any { it == '\n' || it == '\r' || it.isISOControl() }) {
        return "名称只能是一行，不能包含控制字符。"
    }
    val url = endpoint.trim()
    if (url.isEmpty()) return "请输入 MCP 端点地址。"
    if (url.length > AgentMcpAdminLimits.MAX_ENDPOINT_CHARS) {
        return "端点地址不能超过 ${AgentMcpAdminLimits.MAX_ENDPOINT_CHARS} 个字符。"
    }
    if (url.any { it.isISOControl() }) return "端点地址不能包含控制字符。"
    val https = url.startsWith("https://", ignoreCase = true)
    val http = url.startsWith("http://", ignoreCase = true)
    if (!https && !http) return "端点必须以 https:// 开头（本地服务器可使用 http://）。"
    if (!https && !allowLocal) return "远程服务器必须使用 HTTPS 地址。"
    return null
}

internal fun normalizedMcpDisplayName(value: String): String = value.trim()

internal fun normalizedMcpEndpoint(value: String): String = value.trim()
