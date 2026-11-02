package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaSwitch
import com.muyuchat.feature.agent.workspace.AgentTaskListItem
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import com.muyuchat.core.designsystem.McaIconSize

/**
 * DSH-shaped left drawer.
 *
 * Brand header + full-width new-task button + two real expandable rows (plugin/tool screens and
 * archived/import) + workspace task list + back-to-chat exit and local-workspace footer.
 * Search/filter/folder actions are deliberately omitted because the feature has no handlers for
 * them; every rendered control maps to an existing Agent action.
 */
@Composable
internal fun AgentHomeDrawerContent(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    onClose: () -> Unit,
    closeThen: (() -> Unit) -> Unit
) {
    var pluginsExpanded by rememberSaveable { mutableStateOf(false) }
    var automationExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("agent.drawer")
    ) {
        DrawerBrandHeader(onClose = onClose)
        Spacer(Modifier.height(8.dp))
        McaButton(
            style = McaButtonStyle.Outlined,
            onClick = { closeThen { actions.onDraftChange("") } },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 48.dp)
                .testTag("agent.drawer.new"),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface
            )
        ) {
            Icon(
                imageVector = McaIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(McaIconSize.Optical.Compact)
            )
            Spacer(Modifier.width(8.dp))
            Text("新任务", style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))

        DrawerExpandableRow(
            icon = McaIcons.AutoAwesome,
            label = "插件",
            expanded = pluginsExpanded,
            onClick = { pluginsExpanded = !pluginsExpanded }
        )
        if (pluginsExpanded) {
            DrawerSubActionRow(
                label = "工具包",
                enabled = !state.settings.toolPackages.loading,
                onClick = { closeThen(actions.settings.onOpenToolPackages) }
            )
            DrawerSubActionRow(
                label = "Skills",
                enabled = state.skills.busy == null,
                onClick = { closeThen(actions.onOpenSkills) }
            )
            DrawerSubActionRow(
                label = "MCP 服务器",
                enabled = state.mcp.busy == null,
                onClick = { closeThen(actions.onOpenMcp) }
            )
        }

        // Memory is its own Agent domain, not a plugin; the plan keeps it out of the 插件 group.
        DrawerDirectRow(
            icon = McaIcons.Psychology,
            label = "记忆空间",
            onClick = { closeThen(actions.settings.onOpenMemory) }
        )
        DrawerDirectRow(
            icon = McaIcons.AccountTree,
            label = "工作流",
            onClick = { closeThen(actions.settings.onOpenWorkflows) }
        )

        DrawerExpandableRow(
            icon = McaIcons.History,
            label = "自动化任务",
            expanded = automationExpanded,
            onClick = { automationExpanded = !automationExpanded }
        )
        if (automationExpanded) {
            DrawerArchivedRow(
                checked = state.home.includeArchived,
                enabled = !state.home.loading,
                onToggle = actions.onToggleIncludeArchived
            )
            DrawerSubActionRow(
                label = "导入任务包",
                enabled = !state.packageState.busy,
                onClick = { closeThen(actions.onImportTaskPackage) }
            )
        }

        WorkspaceHeader(
            taskCount = state.home.activeTasks.size + state.home.recentTasks.size
        )

        Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val taskError = state.home.errorMessage
            if (taskError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = taskError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = actions.onRetryTasks,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("重试")
                    }
                }
            }
            when {
                state.home.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "正在载入任务…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.home.empty -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    EmptyTaskHistory()
                }

                else -> TaskHistoryList(
                    state = state,
                    actions = actions,
                    closeThen = closeThen
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DrawerBackToChatRow(onClick = { closeThen(actions.onBack) })
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandMark(size = 28.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = "本地工作区 · 仅本地",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "任务与产物保存在本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DrawerBackToChatRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "返回对话", onClick = onClick)
            .heightIn(min = 48.dp)
            .testTag("agent.drawer.exit")
            .semantics { contentDescription = "返回对话" }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = McaIcons.Back,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(McaIconSize.M)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = "返回对话",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DrawerBrandHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BrandMark(size = 34.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = "MCA",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.inverseSurface
        ) {
            Text(
                text = "AGENT",
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.inverseOnSurface
            )
        }
        Spacer(Modifier.weight(1f))
        McaIconButton(
            contentDescription = "关闭抽屉",
            onClick = onClose
        ) {
            Icon(McaIcons.Close, contentDescription = null)
        }
    }
}

@Composable
private fun DrawerExpandableRow(
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(McaIconSize.M)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) McaIcons.KeyboardArrowUp else McaIcons.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DrawerSubActionRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = 56.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = McaIcons.Forward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(McaIconSize.S)
        )
    }
}

@Composable
private fun DrawerDirectRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = label, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(McaIconSize.M)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DrawerArchivedRow(
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 56.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "显示已导入归档",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        McaSwitch(
            checked = checked,
            onCheckedChange = { onToggle() },
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("agent.drawer.archived")
                .semantics { contentDescription = "显示已导入归档" }
        )
    }
}

@Composable
private fun WorkspaceHeader(taskCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "工作区",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$taskCount 个任务",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TaskHistoryList(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    closeThen: (() -> Unit) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 8.dp,
            end = 8.dp,
            bottom = 8.dp
        )
    ) {
        if (state.home.activeTasks.isNotEmpty()) {
            item("drawer.active.header") {
                DrawerSectionHeader("进行中", state.home.activeTasks.size)
            }
            items(state.home.activeTasks, key = { "active-${it.taskId}" }) { task ->
                DrawerTaskRow(
                    task = task,
                    actions = actions,
                    closeThen = closeThen,
                    packageBusy = state.packageState.busy
                )
            }
        }
        item("drawer.recent.header") {
            DrawerSectionHeader("最近", state.home.recentTasks.size)
        }
        if (state.home.recentTasks.isEmpty()) {
            item("drawer.recent.empty") {
                Text(
                    text = "暂无其他任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
        }
        items(state.home.recentTasks, key = { "recent-${it.taskId}" }) { task ->
            DrawerTaskRow(
                task = task,
                actions = actions,
                closeThen = closeThen,
                packageBusy = state.packageState.busy
            )
        }
        if (!state.home.endReached) {
            item("drawer.more") {
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = actions.onLoadMoreTasks,
                    enabled = !state.home.loadingMore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(if (state.home.loadingMore) "正在载入…" else "加载更多")
                }
            }
        }
    }
}

@Composable
private fun EmptyTaskHistory() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = McaIcons.ChatBubbleOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(34.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "暂无任务",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "从上方新任务开始",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

@Composable
private fun DrawerSectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DrawerTaskRow(
    task: AgentTaskListItem,
    actions: AgentWorkspaceActions,
    closeThen: (() -> Unit) -> Unit,
    packageBusy: Boolean
) {
    var menuOpen by remember(task.taskId) { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "打开任务") {
                    closeThen { actions.onOpenTask(task.taskId) }
                }
                .heightIn(min = 48.dp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .testTag("agent.drawer.task.${task.taskId}"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.goal,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (task.archived) {
                        AgentTag(text = task.statusLabel)
                    } else {
                        AgentStatusChip(task.status)
                    }
                    Text(
                        text = task.updatedText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box {
                McaIconButton(
                    contentDescription = "任务操作：${task.goal}",
                    onClick = { menuOpen = true },
                    glyphSize = McaIconSize.L,
                ) {
                    Icon(McaIcons.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("导出任务包") },
                        enabled = !packageBusy,
                        onClick = {
                            menuOpen = false
                            closeThen { actions.onExportTaskPackage(task.taskId) }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                    DropdownMenuItem(
                        text = {
                            Text("删除任务", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuOpen = false
                            closeThen { actions.onRequestDeleteTask(task.taskId) }
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
