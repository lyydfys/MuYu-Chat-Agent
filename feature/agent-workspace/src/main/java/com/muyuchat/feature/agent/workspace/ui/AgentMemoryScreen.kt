package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentMemorySelectionUi
import com.muyuchat.feature.agent.workspace.AgentMemorySpaceUiItem
import com.muyuchat.feature.agent.workspace.AgentMemoryUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.core.designsystem.McaIconSize

/**
 * Agent memory-spaces list.
 *
 * It shows only real space names, store-provided labels and item counts. Selection is local view
 * state, and the page never reads or synthesises memory content: an empty or rejected list shows an
 * empty/error state rather than a placeholder space.
 */
@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    actions: AgentWorkspaceActions,
    modifier: Modifier = Modifier
) {
    AgentSettingsScaffold(
        title = "记忆空间",
        listTestTag = "agent.memory",
        backDescription = "返回任务列表",
        onBack = actions.settings.onCloseMemory,
        onRefresh = actions.settings.onRefreshMemory,
        refreshEnabled = state.canRefresh,
        modifier = modifier
    ) {
        val notice = state.notice
        if (notice != null) {
            item("memory.notice") {
                AgentInlineBanner(
                    message = notice,
                    onDismiss = actions.settings.onDismissMemoryStatus
                )
            }
        }
        val error = state.error
        if (error != null) {
            item("memory.error") {
                AgentInlineBanner(
                    message = error.message,
                    isError = true,
                    onDismiss = actions.settings.onDismissMemoryStatus
                )
            }
        }

        item("memory.header") {
            AgentSectionHeader(
                title = "记忆空间",
                trailing = "${state.spaces.size} 个空间 · ${state.totalItemCount} 条记忆"
            )
        }

        item("memory.openManagement") {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = actions.settings.onOpenMemoryManagement,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("agent.memory.openManagement")
            ) {
                Text("管理条目（搜索 / 编辑 / 删除 / 恢复）")
            }
        }

        when {
            state.loading && state.spaces.isEmpty() -> item("memory.loading") {
                AgentStaticLoading("正在载入记忆空间…")
            }

            state.empty -> item("memory.empty") {
                AgentSettingsEmptyState(
                    title = "尚无记忆空间",
                    body = "记忆空间由本机 Agent 存储提供；这里只显示真实存在的空间和条目" +
                        "数量，不展示或编造记忆内容。"
                )
            }

            else -> items(state.spaces, key = { it.spaceId }) { space ->
                MemorySpaceRow(space = space, state = state, actions = actions)
            }
        }

        item("memory.selection") {
            AgentSectionHeader(title = "选择信息")
            val selected = state.selectedSpace
            if (selected == null) {
                AgentSettingsEmptyState(
                    title = "尚未选择记忆空间",
                    body = "点按上方任一空间，可查看它的条目数量与存储信息。"
                )
            } else {
                Column(modifier = Modifier.testTag("memory.selection.info")) {
                    Text(
                        text = selected.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = selectionMeta(selected),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (selected.description.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = selected.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item("memory.note") {
            AgentSettingsNote(
                text = "本页只读取空间清单与条目数量，不读取记忆正文。任务实际使用哪些空间" +
                    "由创建任务时的冻结选择决定，不会因这里的选择而改变。"
            )
        }
    }
}

@Composable
private fun MemorySpaceRow(
    space: AgentMemorySpaceUiItem,
    state: AgentMemoryUiState,
    actions: AgentWorkspaceActions
) {
    val selected = state.isSelected(space)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = "选择记忆空间 ${space.displayName}"
                ) { actions.settings.onSelectMemorySpace(space.spaceId) }
                .heightIn(min = 56.dp)
                .testTag("memory.item.${space.spaceId}")
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = space.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selected) {
                        Spacer(Modifier.width(8.dp))
                        AgentTag(text = "已选择")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = rowMeta(space),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (space.description.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = space.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = McaIcons.Forward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(McaIconSize.S)
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun rowMeta(space: AgentMemorySpaceUiItem): String = buildString {
    append(space.itemsLabel)
    space.egressLabel?.let { append(" · ").append(it) }
    space.updatedLabel?.let { append(" · 更新 ").append(it) }
}

private fun selectionMeta(selection: AgentMemorySelectionUi): String =
    buildString {
        append(selection.itemsLabel)
        selection.egressLabel?.let { append(" · ").append(it) }
    }
