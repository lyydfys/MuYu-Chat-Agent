package com.muyuchat.feature.agent.workspace

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Page-local MCP registry coordinator.
 *
 * It owns the one-at-a-time gate and the add/remove/confirm flows, but performs no networking:
 * [AgentMcpAdminPort] is a durable-config facade. Enabling a server records a preference; turning
 * on Agent network access records the server-scope authorization, and the run-start binder still
 * decides whether a particular run may connect.
 */
internal class AgentMcpCoordinator(
    private val scope: CoroutineScope,
    private val port: AgentMcpAdminPort
) {
    private val mutable = MutableStateFlow(AgentMcpUiState())
    val state: StateFlow<AgentMcpUiState> = mutable.asStateFlow()

    fun open() {
        if (mutable.value.visible) return
        val shouldLoad = mutable.value.servers.isEmpty() &&
            !mutable.value.loading &&
            mutable.value.busy == null
        mutable.update {
            it.copy(
                visible = true,
                loading = shouldLoad || it.loading,
                addDraft = null,
                removeConfirmation = null
            )
        }
        if (shouldLoad) scope.launch { refreshNow() }
    }

    fun close() {
        mutable.update {
            it.copy(visible = false, addDraft = null, removeConfirmation = null)
        }
    }

    fun refresh() {
        if (mutable.value.busy != null) return
        scope.launch { refreshNow() }
    }

    fun openAddDialog() {
        if (mutable.value.busy != null) return
        mutable.update {
            it.copy(
                addDraft = AgentMcpAddDraftUi(),
                removeConfirmation = null,
                error = null,
                notice = null
            )
        }
    }

    fun updateAddDisplayName(value: String) {
        if (mutable.value.busy != null) return
        val draft = mutable.value.addDraft ?: return
        mutable.update { it.copy(addDraft = draft.copy(displayName = value, validationMessage = null)) }
    }

    fun updateAddEndpoint(value: String) {
        if (mutable.value.busy != null) return
        val draft = mutable.value.addDraft ?: return
        mutable.update { it.copy(addDraft = draft.copy(endpoint = value, validationMessage = null)) }
    }

    fun updateAddAllowLocal(value: Boolean) {
        if (mutable.value.busy != null) return
        val draft = mutable.value.addDraft ?: return
        mutable.update { it.copy(addDraft = draft.copy(allowLocal = value, validationMessage = null)) }
    }

    fun dismissAddDialog() {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(addDraft = null) }
    }

    fun submitAdd() {
        if (mutable.value.busy != null || mutable.value.addDraft == null) return
        scope.launch { addNow() }
    }

    fun requestSetEnabled(serverId: String, enabled: Boolean) {
        if (mutable.value.busy != null) return
        val item = mutable.value.servers.firstOrNull { it.serverId == serverId } ?: return
        if (item.enabled == enabled) return
        if (mutable.value.addDraft != null || mutable.value.removeConfirmation != null) return
        scope.launch { setEnabledNow(serverId, enabled) }
    }

    fun requestSetAgentNetworkAllowed(serverId: String, allowed: Boolean) {
        if (mutable.value.busy != null) return
        val item = mutable.value.servers.firstOrNull { it.serverId == serverId } ?: return
        if (item.allowAgentNetwork == allowed) return
        if (mutable.value.addDraft != null || mutable.value.removeConfirmation != null) return
        scope.launch { setAgentNetworkAllowedNow(serverId, allowed) }
    }

    fun requestRemove(serverId: String) {
        if (mutable.value.busy != null) return
        val item = mutable.value.servers.firstOrNull { it.serverId == serverId } ?: return
        mutable.update {
            it.copy(
                removeConfirmation = AgentMcpRemoveConfirmationUi(item.serverId, item.displayName),
                addDraft = null,
                notice = null
            )
        }
    }

    fun confirmRemove() {
        val confirmation = mutable.value.removeConfirmation ?: return
        if (mutable.value.busy != null) return
        mutable.update { it.copy(removeConfirmation = null) }
        scope.launch { removeNow(confirmation.serverId) }
    }

    fun cancelRemove() {
        mutable.update { it.copy(removeConfirmation = null) }
    }

    fun dismissStatus() {
        if (mutable.value.busy != null) return
        mutable.update { it.copy(error = null, notice = null) }
    }

    // ------------------------------------------------------------ deterministic suspend entry points

    internal suspend fun refreshNow() {
        if (mutable.value.busy != null) return
        begin(AgentMcpOperationKind.LISTING)
        val result = try {
            port.list()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            AgentMcpAdminListResult.Rejected(
                "MCP_ADMIN_FAILED",
                "MCP 服务器列表暂时无法载入，请稍后重试。"
            )
        }
        when (result) {
            is AgentMcpAdminListResult.Loaded -> mutable.update {
                it.copy(
                    loading = false,
                    servers = sortServers(result.servers.map(::projectMcpServer)),
                    busy = null,
                    error = null
                )
            }

            is AgentMcpAdminListResult.Rejected -> mutable.update {
                it.copy(
                    loading = false,
                    busy = null,
                    error = AgentMcpAdminErrorUi(result.code, result.safeMessage)
                )
            }
        }
    }

    internal suspend fun addNow() {
        if (mutable.value.busy != null) return
        val draft = mutable.value.addDraft ?: return
        val displayName = normalizedMcpDisplayName(draft.displayName)
        val endpoint = normalizedMcpEndpoint(draft.endpoint)
        val validation = mcpDraftValidationError(displayName, endpoint, draft.allowLocal)
        if (validation != null) {
            mutable.update { it.copy(addDraft = draft.copy(validationMessage = validation)) }
            return
        }
        begin(AgentMcpOperationKind.ADD)
        val outcome = try {
            port.add(
                displayName = displayName,
                endpoint = endpoint,
                allowLocal = draft.allowLocal,
                bearerToken = draft.bearerToken.trim().ifBlank { null }
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("MCP_ADMIN_FAILED", "MCP 服务器未能添加，请检查配置后重试。")
        }
        when (outcome) {
            is AgentMcpAdminOutcome.Added -> {
                upsert(outcome.item)
                mutable.update {
                    it.copy(
                        busy = null,
                        error = null,
                        addDraft = null,
                        notice = "已添加 MCP 服务器：${outcome.item.displayName}"
                    )
                }
            }

            is AgentMcpAdminOutcome.Updated -> {
                upsert(outcome.item)
                mutable.update {
                    it.copy(
                        busy = null,
                        error = null,
                        addDraft = null,
                        notice = "已更新 MCP 服务器：${outcome.item.displayName}"
                    )
                }
            }

            is AgentMcpAdminOutcome.Rejected -> mutable.update {
                it.copy(
                    busy = null,
                    error = AgentMcpAdminErrorUi(outcome.code, outcome.safeMessage),
                    addDraft = draft.copy(
                        displayName = displayName,
                        endpoint = endpoint,
                        validationMessage = outcome.safeMessage
                    )
                )
            }

            else -> finishUnexpected()
        }
    }

    internal suspend fun setEnabledNow(serverId: String, enabled: Boolean) {
        if (mutable.value.busy != null) return
        if (mutable.value.servers.none { it.serverId == serverId }) return
        begin(AgentMcpOperationKind.TOGGLE)
        val outcome = try {
            port.setEnabled(serverId, enabled)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("MCP_ADMIN_FAILED", "MCP 服务器状态未能更新，请稍后重试。")
        }
        when (outcome) {
            is AgentMcpAdminOutcome.Enabled -> {
                replace(outcome.item)
                mutable.update {
                    it.copy(busy = null, error = null, notice = "已启用 ${outcome.item.displayName}")
                }
            }

            is AgentMcpAdminOutcome.Disabled -> {
                replace(outcome.item)
                mutable.update {
                    it.copy(
                        busy = null,
                        error = null,
                        notice = "已停用 ${outcome.item.displayName}；运行中的任务保留其工具快照。"
                    )
                }
            }

            is AgentMcpAdminOutcome.Rejected -> finishRejected(outcome)
            else -> finishUnexpected()
        }
    }

    internal suspend fun setAgentNetworkAllowedNow(serverId: String, allowed: Boolean) {
        if (mutable.value.busy != null) return
        val item = mutable.value.servers.firstOrNull { it.serverId == serverId } ?: return
        if (item.allowAgentNetwork == allowed) return
        begin(AgentMcpOperationKind.NETWORK)
        val outcome = try {
            port.setAgentNetworkAllowed(serverId, allowed)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("MCP_ADMIN_FAILED", "MCP 网络许可未能更新，请稍后重试。")
        }
        when (outcome) {
            is AgentMcpAdminOutcome.Updated -> {
                replace(outcome.item)
                val name = outcome.item.displayName
                mutable.update {
                    it.copy(
                        busy = null,
                        error = null,
                        notice = if (allowed) {
                            "已允许 $name 参与 Agent 联网任务：已记录 server 级出站授权（精确 origin、仅 PRIVATE 数据）；仍需任务联网策略与逐次审批。"
                        } else {
                            "已关闭 $name 的 Agent 网络许可并撤销其出站授权。"
                        }
                    )
                }
            }

            is AgentMcpAdminOutcome.Rejected -> finishRejected(outcome)
            else -> finishUnexpected()
        }
    }

    internal suspend fun removeNow(serverId: String) {
        if (mutable.value.busy != null) return
        if (mutable.value.servers.none { it.serverId == serverId }) return
        begin(AgentMcpOperationKind.REMOVE)
        val outcome = try {
            port.remove(serverId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            rejected("MCP_ADMIN_FAILED", "MCP 服务器未能移除，请稍后重试。")
        }
        when (outcome) {
            is AgentMcpAdminOutcome.Removed -> mutable.update { current ->
                current.copy(
                    servers = current.servers.filterNot { it.serverId == outcome.serverId },
                    busy = null,
                    error = null,
                    notice = "已移除 ${outcome.displayName}；运行中的任务保留其工具快照。"
                )
            }

            is AgentMcpAdminOutcome.Rejected -> finishRejected(outcome)
            else -> finishUnexpected()
        }
    }

    // ---------------------------------------------------------------- state helpers

    private fun begin(kind: AgentMcpOperationKind) {
        mutable.update {
            it.copy(
                busy = kind,
                loading = kind == AgentMcpOperationKind.LISTING || it.loading,
                error = null,
                notice = null
            )
        }
    }

    private fun upsert(item: AgentMcpServerItem) {
        val projected = projectMcpServer(item)
        mutable.update { current ->
            current.copy(
                servers = sortServers(
                    current.servers.filterNot { it.serverId == projected.serverId } + projected
                )
            )
        }
    }

    private fun replace(item: AgentMcpServerItem) {
        val projected = projectMcpServer(item)
        mutable.update { current ->
            current.copy(
                servers = sortServers(
                    current.servers.map { if (it.serverId == projected.serverId) projected else it }
                )
            )
        }
    }

    private fun finishRejected(outcome: AgentMcpAdminOutcome.Rejected) {
        mutable.update {
            it.copy(
                loading = false,
                busy = null,
                error = AgentMcpAdminErrorUi(outcome.code, outcome.safeMessage)
            )
        }
    }

    private fun finishUnexpected() {
        mutable.update {
            it.copy(
                loading = false,
                busy = null,
                error = AgentMcpAdminErrorUi(
                    "MCP_ADMIN_UNEXPECTED",
                    "MCP 管理返回了无法识别的结果，请稍后重试。"
                )
            )
        }
    }

    private fun rejected(code: String, safeMessage: String): AgentMcpAdminOutcome =
        AgentMcpAdminOutcome.Rejected(code, safeMessage)

    private fun sortServers(servers: List<AgentMcpUiItem>): List<AgentMcpUiItem> =
        servers.sortedWith(
            compareBy({ it.displayName.lowercase(Locale.ROOT) }, { it.serverId })
        )
}
