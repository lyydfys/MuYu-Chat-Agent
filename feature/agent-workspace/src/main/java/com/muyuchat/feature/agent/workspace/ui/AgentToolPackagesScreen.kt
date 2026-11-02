package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageAction
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageConfirmation
import com.muyuchat.feature.agent.workspace.AgentExecutablePackageUiItem
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewToolUi
import com.muyuchat.feature.agent.workspace.AgentToolPackagePreviewUi
import com.muyuchat.feature.agent.workspace.AgentToolPackageUiItem
import com.muyuchat.feature.agent.workspace.AgentToolPackagesUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions

/**
 * Tool-package settings surface.
 *
 * Two blocks, deliberately separate:
 * - **已安装预览** (v1, read-only): listing plus a private ZIP preview. It still has no enable
 *   switch, no import-to-run-catalog path, and cannot create an executor;
 * - **可执行工具包** (format v2, R6): install a ZIP, then enable / disable / roll back / revoke /
 *   clear revocation / uninstall. Every destructive action goes through an explicit confirmation
 *   dialog; one lifecycle call at a time disables the other row actions. A host that has not wired
 *   the lifecycle backend shows an inert "not available" note instead of fake toggles.
 *
 * Touch targets are at least 48dp, every control carries a stable test tag, and each action button
 * has an accessibility label that names the package it belongs to.
 */
@Composable
internal fun AgentToolPackagesScreen(
    state: AgentToolPackagesUiState,
    actions: AgentWorkspaceActions,
    modifier: Modifier = Modifier
) {
    val confirmation = state.executable.confirmation
    if (confirmation != null) {
        ExecutablePackageConfirmDialog(confirmation = confirmation, actions = actions)
    }
    AgentSettingsScaffold(
        title = "工具包",
        listTestTag = "agent.toolPackages",
        backDescription = "返回任务列表",
        onBack = actions.settings.onCloseToolPackages,
        onRefresh = actions.settings.onRefreshToolPackages,
        refreshEnabled = state.canRefresh,
        modifier = modifier
    ) {
        val notice = state.notice
        if (notice != null) {
            item("toolPackages.notice") {
                AgentInlineBanner(
                    message = notice,
                    onDismiss = actions.settings.onDismissToolPackageStatus
                )
            }
        }
        val error = state.error
        if (error != null) {
            item("toolPackages.error") {
                Column {
                    AgentInlineBanner(
                        message = error.message,
                        isError = true,
                        onDismiss = actions.settings.onDismissToolPackageStatus
                    )
                    error.details.forEach { detail ->
                        Text(
                            text = "· $detail",
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item("toolPackages.gate") {
            Column {
                AgentInlineBanner(message = state.gateMessage)
                Text(
                    text = "审批闭环已完成；v1 预览只读，可执行包需在下方安装并逐包启用。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item("toolPackages.header") {
            AgentSectionHeader(
                title = "已安装预览",
                trailing = "${state.items.size} 个 · ${state.totalToolCount} 个工具"
            )
        }

        when {
            state.loading && state.items.isEmpty() -> item("toolPackages.loading") {
                AgentStaticLoading("正在载入工具包预览…")
            }

            state.empty -> item("toolPackages.empty") {
                AgentSettingsEmptyState(
                    title = "尚未安装工具包预览",
                    body = "导入的 ZIP 只会生成私有的只读预览，不会启用或加入运行目录。"
                )
            }

            else -> items(state.items, key = { it.rowKey }) { item ->
                ToolPackageRow(item = item)
            }
        }

        item("toolPackages.actions") {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    text = "只读预览",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McaButton(
                        style = McaButtonStyle.Outlined,
                        shape = ButtonDefaults.outlinedShape,
                        onClick = actions.settings.onPreviewToolPackages,
                        enabled = state.previewEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("toolPackages.preview")
                    ) {
                        Text(if (state.previewing) "正在校验…" else "预览 ZIP")
                    }
                    McaButton(
                        style = McaButtonStyle.Outlined,
                        shape = ButtonDefaults.outlinedShape,
                        onClick = actions.settings.onImportToolPackages,
                        enabled = state.importEnabled,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("toolPackages.import")
                    ) {
                        Text("导入工具包")
                    }
                }
                if (!state.importEnabled) {
                    Text(
                        text = "只读预览不会进入运行目录；需要真实工具请使用下方可执行包。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.previewing) {
                    Text(
                        text = "正在校验清单、资源与哈希；不会启用任何工具。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        val preview = state.preview
        if (preview != null) {
            item("toolPackages.previewCard") {
                ToolPackagePreviewCard(preview = preview)
            }
        }

        item("toolPackages.executable.header") {
            AgentSectionHeader(
                title = "可执行工具包",
                trailing = if (state.executable.supported) state.executable.countLabel else "未接入"
            )
        }

        if (!state.executable.supported) {
            item("toolPackages.executable.unavailable") {
                AgentSettingsNote(
                    text = "可执行工具包管理尚未接入；安装、启用、停用、回滚、撤销与卸载暂不可用。"
                )
            }
        } else {
            val executableError = state.executable.error
            if (executableError != null) {
                item("toolPackages.executable.error") {
                    AgentInlineBanner(
                        message = executableError.message,
                        isError = true,
                        onDismiss = actions.settings.onDismissToolPackageStatus
                    )
                }
            }
            val executableNotice = state.executable.notice
            if (executableNotice != null) {
                item("toolPackages.executable.notice") {
                    AgentInlineBanner(
                        message = executableNotice,
                        onDismiss = actions.settings.onDismissToolPackageStatus
                    )
                }
            }
            when {
                state.executable.loading && state.executable.items.isEmpty() ->
                    item("toolPackages.executable.loading") {
                        AgentStaticLoading("正在载入可执行工具包…")
                    }

                state.executable.empty -> item("toolPackages.executable.empty") {
                    AgentSettingsEmptyState(
                        title = "尚未安装可执行工具包",
                        body = "通过下方按钮选择 ZIP：安装后会先校验，不会自动启用。"
                    )
                }

                else -> items(
                    state.executable.items,
                    key = { "executable.${it.rowKey}" }
                ) { item ->
                    ExecutablePackageRow(item = item, actions = actions)
                }
            }
            item("toolPackages.executable.actions") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    McaButton(
                        style = McaButtonStyle.Outlined,
                        shape = ButtonDefaults.outlinedShape,
                        onClick = actions.settings.onInstallExecutableToolPackage,
                        enabled = state.executable.canInstall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("toolPackages.executable.install")
                    ) {
                        Text(
                            if (state.executable.installing) "正在安装…" else "安装可执行包 (.zip)"
                        )
                    }
                    Text(
                        text = "安装只写入并校验内容，包在手动启用前不会进入运行目录。",
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item("toolPackages.note") {
            AgentSettingsNote(
                text = "可执行包安装后需在列表中手动启用；启用/停用/回滚/撤销/卸载均需二次确认。" +
                    "工具包不能携带网络客户端或第二套工具执行器。"
            )
        }
    }
}

@Composable
private fun ToolPackageRow(item: AgentToolPackageUiItem) {
    Column(modifier = Modifier.testTag("toolPackages.item.${item.packageId}")) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.packageId}@${item.versionLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "副作用：${item.sideEffectLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                item.sourceLabel?.let { source ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "来源：$source",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            AgentTag(text = item.toolCountLabel)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ToolPackagePreviewCard(preview: AgentToolPackagePreviewUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp)
            .testTag("toolPackages.previewCard")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = preview.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            AgentTag(text = "仅预览")
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "${preview.packageId}@${preview.versionLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${preview.toolCountLabel} · ${preview.resourceLabel} · ${preview.sizeLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "来源：${preview.sourceLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "副作用：${preview.sideEffectLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        preview.tools.forEach { tool ->
            ToolPackagePreviewToolRow(tool = tool)
        }
        AgentSettingsNote(
            text = "预览不会启用工具，也不会写入运行目录。"
        )
    }
}

@Composable
private fun ToolPackagePreviewToolRow(tool: AgentToolPackagePreviewToolUi) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Text(
            text = "${tool.displayName} · ${tool.toolName}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "宿主操作：${tool.hostOperation}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "副作用：${tool.sideEffectLabel} · ${tool.dataClassLabel} · ${tool.timeoutLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "参数：${tool.parametersLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** One executable (format v2) package row with its lifecycle actions. */
@Composable
private fun ExecutablePackageRow(
    item: AgentExecutablePackageUiItem,
    actions: AgentWorkspaceActions
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag("toolPackages.executable.item.${item.packageId}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.packageId}@${item.versionLabel} · ${item.toolCountLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            AgentTag(text = if (item.busy) "处理中…" else item.statusLabel)
        }
        if (item.rollbackAvailable && item.contentVerified) {
            Spacer(Modifier.height(4.dp))
            AgentTag(text = "可回滚")
        }
        item.problemLabel?.let { problem ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = problem,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (item.enabled) {
                ExecutableActionButton(
                    item = item,
                    action = AgentExecutablePackageAction.DISABLE,
                    label = "停用",
                    enabled = item.canDisable,
                    onAction = actions.settings.onExecutableToolPackageAction
                )
            } else {
                ExecutableActionButton(
                    item = item,
                    action = AgentExecutablePackageAction.ENABLE,
                    label = "启用",
                    enabled = item.canEnable,
                    onAction = actions.settings.onExecutableToolPackageAction
                )
            }
            ExecutableActionButton(
                item = item,
                action = AgentExecutablePackageAction.ROLLBACK,
                label = "回滚",
                enabled = item.canRollback,
                onAction = actions.settings.onExecutableToolPackageAction
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (item.revoked) {
                ExecutableActionButton(
                    item = item,
                    action = AgentExecutablePackageAction.CLEAR_REVOCATION,
                    label = "解除撤销",
                    enabled = item.canClearRevocation,
                    onAction = actions.settings.onExecutableToolPackageAction
                )
            } else {
                ExecutableActionButton(
                    item = item,
                    action = AgentExecutablePackageAction.REVOKE,
                    label = "撤销",
                    enabled = item.canRevoke,
                    onAction = actions.settings.onExecutableToolPackageAction
                )
            }
            ExecutableActionButton(
                item = item,
                action = AgentExecutablePackageAction.UNINSTALL,
                label = "卸载",
                enabled = item.canUninstall,
                onAction = actions.settings.onExecutableToolPackageAction
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

/** 48dp-minimum action button with a package-scoped accessibility label and stable test tag. */
@Composable
private fun RowScope.ExecutableActionButton(
    item: AgentExecutablePackageUiItem,
    action: AgentExecutablePackageAction,
    label: String,
    enabled: Boolean,
    onAction: (String, AgentExecutablePackageAction) -> Unit
) {
    val actionTag = when (action) {
        AgentExecutablePackageAction.ENABLE -> "enable"
        AgentExecutablePackageAction.DISABLE -> "disable"
        AgentExecutablePackageAction.ROLLBACK -> "rollback"
        AgentExecutablePackageAction.REVOKE -> "revoke"
        AgentExecutablePackageAction.CLEAR_REVOCATION -> "clearRevocation"
        AgentExecutablePackageAction.UNINSTALL -> "uninstall"
    }
    McaButton(
        style = McaButtonStyle.Outlined,
        shape = ButtonDefaults.outlinedShape,
        onClick = { onAction(item.packageId, action) },
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("toolPackages.executable.${item.packageId}.$actionTag")
            .semantics {
                contentDescription = "${item.displayName}：$label"
            }
    ) {
        Text(text = label)
    }
}

@Composable
private fun ExecutablePackageConfirmDialog(
    confirmation: AgentExecutablePackageConfirmation,
    actions: AgentWorkspaceActions
) {
    McaDialog(
        onDismissRequest = actions.settings.onCancelExecutableToolPackageAction,
        title = { Text(text = confirmation.title) },
        text = { Text(text = "${confirmation.displayName}\n${confirmation.body}") },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = actions.settings.onConfirmExecutableToolPackageAction,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("toolPackages.executable.confirm")
            ) {
                Text(text = confirmation.confirmLabel)
            }
        },
        dismissButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = actions.settings.onCancelExecutableToolPackageAction,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("toolPackages.executable.cancel")
            ) {
                Text(text = "取消")
            }
        }
    )
}
