package com.muyuchat.feature.agent.workspace.ui

import com.muyuchat.core.designsystem.McaIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.feature.agent.workspace.AgentMcpAddDraftUi
import com.muyuchat.feature.agent.workspace.AgentMcpOperationKind
import com.muyuchat.feature.agent.workspace.AgentMcpUiItem
import com.muyuchat.feature.agent.workspace.AgentMcpUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions

/**
 * MCP registry surface: one quiet LazyColumn, static progress text, 48dp targets, McaTheme roles.
 *
 * This page only edits durable server configs. It deliberately offers no "connect"/"test" action:
 * connections stay gated by the per-run egress policy, so a green enabled switch must never imply
 * reachability. A bearer token may be entered; it is stored only in the Keystore-backed credential
 * store and never rendered back.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AgentMcpScreen(
    state: AgentMcpUiState,
    actions: AgentWorkspaceActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务器", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    McaIconButton(
                        contentDescription = "返回任务列表",
                        onClick = actions.onCloseMcp
                    ) {
                        Icon(McaIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = actions.onRefreshMcp,
                        enabled = state.busy == null && !state.loading,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("agent.mcp"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            val notice = state.notice
            if (notice != null) {
                item("mcp.notice") {
                    AgentInlineBanner(notice, onDismiss = actions.onDismissMcpStatus)
                }
            }
            val error = state.error
            if (error != null) {
                item("mcp.error") {
                    AgentInlineBanner(
                        message = error.message,
                        isError = true,
                        onDismiss = actions.onDismissMcpStatus
                    )
                }
            }
            val busyKind = state.busy
            if (busyKind != null) {
                item("mcp.busy") { McpProgress(busyKind) }
            }

            item("mcp.header") {
                AgentSectionHeader(
                    title = "已注册服务器",
                    trailing = "${state.servers.size} 个 · ${state.enabledCount} 已启用"
                )
            }

            when {
                state.loading && state.servers.isEmpty() -> item("mcp.loading") {
                    AgentStaticLoading("正在载入 MCP 服务器…")
                }

                state.empty -> item("mcp.empty") {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "尚未添加 MCP 服务器",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "这里只保存服务器地址和启用偏好；不会测试连通性，也不会发起连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> items(state.servers, key = { it.serverId }) { server ->
                    McpServerRow(server = server, state = state, actions = actions)
                }
            }

            item("mcp.add") {
                McaButton(
                    style = McaButtonStyle.Outlined,
                    shape = ButtonDefaults.outlinedShape,
                    onClick = actions.onOpenMcpAddDialog,
                    enabled = state.canAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .heightIn(min = 48.dp)
                        .testTag("mcp.add")
                ) {
                    Text("添加 MCP 服务器")
                }
            }
            item("mcp.note") {
                Text(
                    text = "Bearer token 只写入 Android Keystore；servers.json 只保存 opaque 引用。" +
                "启用只表示注册表偏好；" +
                        "运行时仍需当次任务的出站授权，管理页不会验证服务器是否在线。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }

    state.removeConfirmation?.let { confirmation ->
        McpRemoveDialog(
            displayName = confirmation.displayName,
            busy = state.busy == AgentMcpOperationKind.REMOVE,
            onConfirm = actions.onConfirmMcpRemove,
            onCancel = actions.onCancelMcpRemove
        )
    }

    state.addDraft?.let { draft ->
        McpAddDialog(
            draft = draft,
            busy = state.busy == AgentMcpOperationKind.ADD,
            onNameChange = actions.onMcpAddNameChange,
            onEndpointChange = actions.onMcpAddEndpointChange,
            onTokenChange = actions.onMcpAddTokenChange,
            onAllowLocalChange = actions.onMcpAddAllowLocalChange,
            onSubmit = actions.onSubmitMcpAdd,
            onDismiss = actions.onDismissMcpAddDialog
        )
    }
}

@Composable
private fun McpServerRow(
    server: AgentMcpUiItem,
    state: AgentMcpUiState,
    actions: AgentWorkspaceActions
) {
    Column(modifier = Modifier.testTag("mcp.item.${server.serverId}")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (server.allowLocal) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "本地",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = server.endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = server.statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            McaSwitch(
                checked = server.enabled,
                onCheckedChange = { enabled -> actions.onSetMcpEnabled(server.serverId, enabled) },
                enabled = state.canToggle(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.toggle.${server.serverId}")
                    .semantics {
                        contentDescription = "${if (server.enabled) "停用" else "启用"} ${server.displayName}"
                    }
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "允许 Agent 联网任务访问该服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "仅在该任务网络策略开启并取得出站授权时生效，不是长期 token。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            McaSwitch(
                checked = server.allowAgentNetwork,
                onCheckedChange = { allowed ->
                    actions.onSetMcpAgentNetworkAllowed(server.serverId, allowed)
                },
                enabled = state.canToggle(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.network.${server.serverId}")
                    .semantics {
                        contentDescription =
                            "${if (server.allowAgentNetwork) "关闭" else "允许"} ${server.displayName} 的 Agent 网络访问"
                    }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = { actions.onRequestMcpRemove(server.serverId) },
                enabled = state.canRemove(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.remove.${server.serverId}"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("移除")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun McpProgress(kind: AgentMcpOperationKind) {
    val text = when (kind) {
        AgentMcpOperationKind.LISTING -> "正在载入 MCP 服务器…"
        AgentMcpOperationKind.ADD -> "正在保存 MCP 服务器配置…"
        AgentMcpOperationKind.TOGGLE -> "正在更新 MCP 服务器状态…"
        AgentMcpOperationKind.NETWORK -> "正在更新 MCP 网络许可…"
        AgentMcpOperationKind.REMOVE -> "正在移除 MCP 服务器…"
    }
    AgentInlineBanner(message = text)
}

/**
 * Add dialog. Cancel is declared first and no focus is requested, so the destructive/none-default
 * path is never focused. A rejected submit keeps the draft open with the host's safe message.
 */
@Composable
private fun McpAddDialog(
    draft: AgentMcpAddDraftUi,
    busy: Boolean,
    onNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onAllowLocalChange: (Boolean) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    McaDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("添加 MCP 服务器") },
        text = {
            Column {
                OutlinedTextField(
                    value = draft.displayName,
                    onValueChange = onNameChange,
                    label = { Text("名称") },
                    singleLine = true,
                    enabled = !busy,
                    isError = draft.validationMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mcp.add.name")
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.endpoint,
                    onValueChange = onEndpointChange,
                    label = { Text("HTTPS 端点") },
                    placeholder = { Text("https://example.com/mcp") },
                    singleLine = true,
                    enabled = !busy,
                    isError = draft.validationMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mcp.add.endpoint")
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.bearerToken,
                    onValueChange = onTokenChange,
                    label = { Text("Bearer token（可选）") },
                    placeholder = { Text("只写入 Android Keystore") },
                    singleLine = true,
                    enabled = !busy,
                    isError = draft.validationMessage != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mcp.add.token")
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "本地服务器",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "仅在明确允许时接受 loopback 或私网地址。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    McaSwitch(
                        checked = draft.allowLocal,
                        onCheckedChange = onAllowLocalChange,
                        enabled = !busy,
                        contentDescription = "本地服务器",
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("mcp.add.local")
                    )
                }
                val validation = draft.validationMessage
                if (validation != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = validation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = "不会保存 token 或 Authorization；端点中的查询参数和凭据会被拒绝。" +
                        "新服务器的 Agent 网络访问默认关闭。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在保存…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onDismiss,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.add.cancel")
            ) {
                Text("取消")
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onSubmit,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.add.submit")
            ) {
                Text("添加")
            }
        }
    )
}

/** Remove confirmation; cancel comes first and nothing requests initial focus. */
@Composable
private fun McpRemoveDialog(
    displayName: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    McaDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("移除 MCP 服务器？") },
        text = {
            Column {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "移除后新任务不再加载该服务器；运行中的任务会保留其工具快照。" +
                        "这不会删除任何该服务器上的数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在移除…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onCancel,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.remove.cancel")
            ) {
                Text("取消")
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onConfirm,
                enabled = !busy,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mcp.remove.confirm"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("移除")
            }
        }
    )
}
