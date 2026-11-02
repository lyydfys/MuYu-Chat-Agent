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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.muyuchat.feature.agent.workspace.AgentSkillAdminLimits
import com.muyuchat.feature.agent.workspace.AgentSkillOperationKind
import com.muyuchat.feature.agent.workspace.AgentSkillUiItem
import com.muyuchat.feature.agent.workspace.AgentSkillsUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions

/**
 * Skills management surface: one quiet LazyColumn, static progress text (no animation loop),
 * 48dp touch targets and the existing McaTheme roles only.
 *
 * The page never shows a raw manifest/hash/tool object; names/versions and the declared-tool count
 * are the only Skill facts rendered. Scripts are described as preview-only, never executable.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    actions: AgentWorkspaceActions,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Skills", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    McaIconButton(
                        contentDescription = "返回任务列表",
                        onClick = actions.onCloseSkills
                    ) {
                        Icon(McaIcons.Back, contentDescription = null)
                    }
                },
                actions = {
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = actions.onRefreshSkills,
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
                .testTag("agent.skills"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            val notice = state.notice
            if (notice != null) {
                item("skills.notice") {
                    AgentInlineBanner(notice, onDismiss = actions.onDismissSkillStatus)
                }
            }
            val error = state.error
            if (error != null) {
                item("skills.error") {
                    AgentInlineBanner(
                        message = error.message,
                        isError = true,
                        onDismiss = actions.onDismissSkillStatus
                    )
                }
            }
            val busyKind = state.busy
            if (busyKind != null) {
                item("skills.busy") { SkillsProgress(busyKind) }
            }
            val confirmationMarker = state.uninstallConfirmation
            if (confirmationMarker != null) {
                // The dialog owns its own focus; this row is intentionally not a focus target.
                item("skills.confirmation.marker") {
                    Text(
                        text = "等待确认卸载 ${confirmationMarker.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item("skills.header") {
                AgentSectionHeader(
                    title = "已安装 Skills",
                    trailing = "${state.enabledCount}/${AgentSkillAdminLimits.MAX_ENABLED_SKILLS} 已启用"
                )
            }

            when {
                state.loading && state.items.isEmpty() -> item("skills.loading") {
                    AgentStaticLoading("正在载入 Skills…")
                }

                state.empty -> item("skills.empty") {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(
                            text = "尚未安装 Skill",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "选择包含根 SKILL.md 的目录或 ZIP。脚本只展示来源，不会执行或安装运行时。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> items(state.items, key = { it.skillId }) { item ->
                    SkillsRow(item = item, state = state, actions = actions)
                }
            }

            if (state.limitReached) {
                item("skills.limit") {
                    AgentInlineBanner(
                        message = "最多同时启用 ${AgentSkillAdminLimits.MAX_ENABLED_SKILLS} 个 Skill；" +
                            "多个 Skill 只会收窄可用工具，不会扩大授权。"
                    )
                }
            }

            item("skills.import") {
                ImportActions(state = state, actions = actions)
            }
            item("skills.note") {
                Text(
                    text = "Skill 只能收窄工具授权。停用或卸载会使待审批失效；运行中的任务会保留" +
                        "其 Skill 快照。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
            }
        }
    }

    state.uninstallConfirmation?.let { confirmation ->
        AgentSkillUninstallDialog(
            name = confirmation.name,
            busy = state.busy == AgentSkillOperationKind.UNINSTALL,
            onConfirm = actions.onConfirmSkillUninstall,
            onCancel = actions.onCancelSkillUninstall
        )
    }
}

@Composable
private fun SkillsRow(
    item: AgentSkillUiItem,
    state: AgentSkillsUiState,
    actions: AgentWorkspaceActions
) {
    Column(modifier = Modifier.testTag("skills.item.${item.skillId}")) {
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
                        text = item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${if (item.enabled) "已启用" else "已停用"} · ${item.allowedToolsLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            McaSwitch(
                checked = item.enabled,
                onCheckedChange = { enabled -> actions.onSetSkillEnabled(item.skillId, enabled) },
                enabled = state.canToggle(item),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("skills.toggle.${item.skillId}")
                    .semantics { contentDescription = "${if (item.enabled) "停用" else "启用"} ${item.name}" }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = { actions.onRequestSkillUninstall(item.skillId) },
                enabled = state.canUninstall(),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("skills.uninstall.${item.skillId}"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("卸载")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ImportActions(state: AgentSkillsUiState, actions: AgentWorkspaceActions) {
    Column(modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(
            text = "导入 Skill",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            McaButton(
                style = McaButtonStyle.Outlined,
                shape = ButtonDefaults.outlinedShape,
                onClick = actions.onImportSkillZip,
                enabled = state.canImport,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("skills.import.zip")
            ) {
                Text("导入 ZIP")
            }
            McaButton(
                style = McaButtonStyle.Outlined,
                shape = ButtonDefaults.outlinedShape,
                onClick = actions.onImportSkillTree,
                enabled = state.canImport,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .testTag("skills.import.tree")
            ) {
                Text("导入目录")
            }
        }
    }
}

@Composable
private fun SkillsProgress(kind: AgentSkillOperationKind) {
    val text = when (kind) {
        AgentSkillOperationKind.LISTING -> "正在载入 Skills…"
        AgentSkillOperationKind.IMPORT_ZIP -> "正在校验并导入 Skill ZIP…"
        AgentSkillOperationKind.IMPORT_TREE -> "正在校验并导入 Skill 目录…"
        AgentSkillOperationKind.TOGGLE -> "正在更新 Skill 状态…"
        AgentSkillOperationKind.UNINSTALL -> "正在卸载 Skill…"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Uninstall confirmation. Cancel is declared first and nothing requests initial focus, so the
 * destructive action is never the default focus target.
 */
@Composable
private fun AgentSkillUninstallDialog(
    name: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    McaDialog(
        onDismissRequest = { if (!busy) onCancel() },
        title = { Text("卸载 Skill？") },
        text = {
            Column {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "卸载后新任务不再使用它；运行中的任务会保留其 Skill 快照，待审批会失效。" +
                        "历史任务不会被删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "正在卸载…",
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
                    .testTag("skills.cancel.uninstall")
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
                    .testTag("skills.confirm.uninstall"),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("卸载")
            }
        }
    )
}

