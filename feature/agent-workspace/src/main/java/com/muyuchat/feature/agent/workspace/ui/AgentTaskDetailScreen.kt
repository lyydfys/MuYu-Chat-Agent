package com.muyuchat.feature.agent.workspace.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaIconButton
import com.muyuchat.core.designsystem.mcaEnterReveal
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentStatusTone
import com.muyuchat.feature.agent.workspace.AgentTaskDetailUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceCommandKind
import com.muyuchat.feature.agent.workspace.AgentWorkspaceStatus
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import com.muyuchat.feature.agent.workspace.MAX_EVENTS
import com.muyuchat.feature.agent.workspace.statusTone
import com.muyuchat.feature.agent.workspace.taskDisplayTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private enum class AgentDetailTab(val label: String) {
    CONVERSATION("对话"),
    TRAJECTORY("轨迹")
}


/**
 * DSH conversation-style task detail.
 *
 * Top row: back, the same MCA mark and two-line title treatment as the home header (the fixed
 * "MCA Agent" brand line over this task's display title), and the overflow task-actions control.
 * Two tabs switch between the conversation and the execution trajectory. The conversation uses a
 * lavender user goal bubble, a quiet gray run-status line, assistant text/attachment blocks, and
 * inline question/approval cards. The floating composer only exposes real Agent actions; approval
 * keeps its modal sheet.
 */
@Composable
internal fun AgentTaskDetailScreen(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    initialScrollIndex: Int,
    modifier: Modifier = Modifier
) {
    val detail = state.detail ?: return
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollIndex.coerceAtLeast(0)
    )
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(AgentDetailTab.CONVERSATION) }
    var packageMenuOpen by remember { mutableStateOf(false) }
    var firstTabComposition by remember { mutableStateOf(true) }
    LaunchedEffect(detail.taskId) { packageMenuOpen = false }
    LaunchedEffect(selectedTab) {
        if (firstTabComposition) {
            firstTabComposition = false
        } else {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { actions.onDetailScrollAnchor(it) }
    }
    val showJumpToLatest by remember { derivedStateOf { listState.canScrollForward } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("agent.detail")
    ) {
        DetailTopBar(
            detail = detail,
            state = state,
            actions = actions,
            menuOpen = packageMenuOpen,
            onOpenMenu = { packageMenuOpen = true },
            onDismissMenu = { packageMenuOpen = false }
        )
        DetailTabs(selected = selectedTab, onSelect = { selectedTab = it })
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (selectedTab == AgentDetailTab.CONVERSATION) {
                    conversationItems(state = state, detail = detail, actions = actions)
                } else {
                    trajectoryItems(state = state, detail = detail, actions = actions)
                }
            }
            if (showJumpToLatest) {
                JumpToLatestPill(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    onClick = {
                        coroutineScope.launch {
                            val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                            listState.animateScrollToItem(target)
                        }
                    }
                )
            }
        }
        AgentDetailComposer(state = state, actions = actions, detail = detail)
    }
}

// ------------------------------------------------------------------------------- top row / tabs

@Composable
private fun DetailTopBar(
    detail: AgentTaskDetailUiState,
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    menuOpen: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        McaIconButton(
            contentDescription = "返回工作区",
            onClick = actions.onBackToHome
        ) {
            Icon(McaIcons.Back, contentDescription = null)
        }
        // One-line title under the same brand mark; the task title is the only persistent task
        // identity in the detail chrome.
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .mcaEnterReveal(index = 0, travelDp = 8)
        ) {
            BrandMark(size = 30.dp)
        }
        Text(
            text = taskDisplayTitle(detail.goal.ifBlank { "任务" }),
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp)
                .mcaEnterReveal(index = 0, travelDp = 8),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box {
            McaIconButton(
                contentDescription = "任务操作",
                enabled = !detail.loading,
                onClick = onOpenMenu
            ) {
                Icon(McaIcons.MoreVert, contentDescription = null)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                AgentPackageMenuItem(
                    label = "导出任务包",
                    enabled = !detail.loading && !state.packageState.busy,
                    onClick = {
                        onDismissMenu()
                        actions.onExportTaskPackage(detail.taskId)
                    }
                )
                AgentPackageMenuItem(
                    label = "保存到下载目录",
                    enabled = !detail.loading && !state.packageState.busy,
                    onClick = {
                        onDismissMenu()
                        actions.onExportTaskPackageToDownloads(detail.taskId)
                    }
                )
                if (detail.canStop) {
                    AgentPackageMenuItem(
                        label = "停止任务",
                        enabled = !detail.stopInFlight,
                        onClick = {
                            onDismissMenu()
                            actions.onStop()
                        }
                    )
                }
                AgentPackageMenuItem(
                    label = "删除任务",
                    enabled = !detail.loading && !state.packageState.busy,
                    destructive = true,
                    onClick = {
                        onDismissMenu()
                        actions.onRequestDeleteTask(detail.taskId)
                    }
                )
            }
        }
    }
}

@Composable
private fun DetailTabs(selected: AgentDetailTab, onSelect: (AgentDetailTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .padding(start = 48.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AgentDetailTab.entries.forEach { tab ->
                DetailTabItem(
                    tab = tab,
                    selected = selected == tab,
                    onClick = { onSelect(tab) }
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    }
}

@Composable
private fun DetailTabItem(tab: AgentDetailTab, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .widthIn(min = 48.dp)
            .height(48.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.width(IntrinsicSize.Max),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

// ------------------------------------------------------------------------------ conversation tab

private fun LazyListScope.conversationItems(
    state: AgentWorkspaceUiState,
    detail: AgentTaskDetailUiState,
    actions: AgentWorkspaceActions
) {
    systemItems(state = state, actions = actions)
    item("conversation.goal") {
        UserGoalBubble(detail.goal.ifBlank { "等待任务目标载入…" })
    }
    item("conversation.status") {
        ConversationStatusLine(detail)
    }
    val approval = detail.approval
    if (detail.waitingForApproval && approval != null) {
        item("conversation.approval") {
            AgentInlineApprovalCard(approval = approval, onOpenApproval = actions.onOpenApproval)
        }
    }
    detail.question?.let { question ->
        item("conversation.question") {
            AgentQuestionCard(
                question = question,
                answerDraft = state.questionAnswerDraft,
                busy = state.commandInFlight == AgentWorkspaceCommandKind.ANSWER_QUESTION,
                onAnswerChange = actions.onQuestionAnswerChange
            )
        }
    }
    val finalAnswer = detail.finalAnswer
    if (detail.selfReportedCompletion) {
        item("conversation.selfReportedCompletion") {
            AgentInlineBanner(
                message = "该任务没有调用任何工具，也没有产物；以下内容由模型自述，未经验证。",
                modifier = Modifier.testTag("agent.detail.selfReportedCompletion")
            )
        }
    }
    if (finalAnswer != null) {
        item("conversation.answer") {
            SelectionContainer {
                Text(
                    text = finalAnswer,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (detail.artifacts.isNotEmpty()) {
            item("conversation.artifacts.label") {
                ConversationSectionLabel("产物", "${detail.artifacts.size} 个")
            }
            items(detail.artifacts, key = { "conversation-artifact-${it.artifactId}" }) { artifact ->
                AgentArtifactRow(
                    artifact = artifact,
                    onClick = { actions.onOpenArtifact(artifact.artifactId) }
                )
            }
        }
        item("conversation.actions") {
            AssistantActionRow(detail = detail, actions = actions)
        }
    } else {
        if (detail.archived) {
            item("conversation.archived.note") {
                ConversationMutedLine("这是导入的归档任务，只读；没有可继续执行的对话内容。")
            }
        } else {
            activityItems(
                state = state,
                detail = detail,
                actions = actions,
                keyPrefix = "conversation"
            )
            if (detail.artifacts.isNotEmpty()) {
                item("conversation.artifacts.label") {
                    ConversationSectionLabel("产物", "${detail.artifacts.size} 个")
                }
                items(detail.artifacts, key = { "conversation-artifact-${it.artifactId}" }) { artifact ->
                    AgentArtifactRow(
                        artifact = artifact,
                        onClick = { actions.onOpenArtifact(artifact.artifactId) }
                    )
                }
            }
            if (detail.status == AgentWorkspaceStatus.SUCCEEDED) {
                item("conversation.answer.empty") {
                    ConversationMutedLine("运行已完成，但没有可展示的最终回答。")
                }
            }
        }
    }
}

@Composable
private fun UserGoalBubble(goal: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .semantics(mergeDescendants = true) { },
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 6.dp
            ),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            SelectionContainer {
                Text(
                    text = goal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ConversationStatusLine(detail: AgentTaskDetailUiState) {
    val suffix = buildString {
        if (detail.archived) append(" · 已归档")
        detail.runLabel?.let { append(" · ").append(it) }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Agent 任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = " · ",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = detail.statusLabel + suffix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(top = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun AssistantActionRow(detail: AgentTaskDetailUiState, actions: AgentWorkspaceActions) {
    val answer = detail.finalAnswer ?: return
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        McaIconButton(
            contentDescription = if (copied) "已复制回答" else "复制回答",
            onClick = {
                clipboard.setText(AnnotatedString(answer))
                copied = true
            }
        ) {
            Icon(
                imageVector = if (copied) McaIcons.Check else McaIcons.ContentCopy,
                contentDescription = null
            )
        }
        McaIconButton(
            contentDescription = "分享回答",
            onClick = {
                runCatching {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, answer)
                    }
                    context.startActivity(Intent.createChooser(send, "分享回答"))
                }
            }
        ) {
            Icon(McaIcons.Share, contentDescription = null)
        }
        McaIconButton(
            contentDescription = "导出任务包",
            onClick = { actions.onExportTaskPackage(detail.taskId) }
        ) {
            Icon(McaIcons.Download, contentDescription = null)
        }
        Spacer(Modifier.weight(1f))
        detail.timeline.lastOrNull()?.let { row ->
            Text(
                text = row.timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// -------------------------------------------------------------------------------- trajectory tab

private fun LazyListScope.trajectoryItems(
    state: AgentWorkspaceUiState,
    detail: AgentTaskDetailUiState,
    actions: AgentWorkspaceActions
) {
    systemItems(state = state, actions = actions)
    item("trajectory.run") {
        TrajectoryHeader(detail)
    }
    activityItems(state = state, detail = detail, actions = actions, keyPrefix = "trajectory")
}

@Composable
private fun TrajectoryHeader(detail: AgentTaskDetailUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = detail.statusLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = statusAccent(detail.status)
        )
        detail.statusDetail?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        detail.runLabel?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (detail.archived) {
            Text(
                text = "已导入的归档任务；只读，不会继续执行。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

// ------------------------------------------------------------------------------ shared sections

private fun LazyListScope.systemItems(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions
) {
    state.notice?.let { message ->
        item("detail.notice") {
            AgentInlineBanner(message = message, onDismiss = actions.onDismissNotice)
        }
    }
    state.error?.let { error ->
        item("detail.error") {
            AgentErrorBanner(error = error, onDismiss = actions.onDismissNotice)
        }
    }
    state.packageState.operation?.let { operation ->
        item("detail.package.status") {
            AgentPackageStatusBanner(
                operation = operation,
                onDismiss = actions.onDismissPackageStatus
            )
        }
    }
}

private fun LazyListScope.activityItems(
    state: AgentWorkspaceUiState,
    detail: AgentTaskDetailUiState,
    actions: AgentWorkspaceActions,
    keyPrefix: String
) {
    item("$keyPrefix.activity.label") {
        ConversationSectionLabel(
            title = "执行活动",
            trailing = if (detail.timeline.isNotEmpty()) {
                "${detail.timeline.size} 组"
            } else {
                null
            }
        )
    }
    when {
        detail.timelineLoading -> item("$keyPrefix.timeline.loading") {
            ConversationMutedLine("正在载入执行活动…")
        }

        detail.timeline.isEmpty() -> item("$keyPrefix.timeline.empty") {
            ConversationMutedLine("还没有执行活动。")
        }

        else -> items(detail.timeline, key = { "$keyPrefix-timeline-${it.key}" }) { row ->
            AgentTimelineRowItem(
                row = row,
                onToggle = { actions.onToggleTimelineRow(row.key) }
            )
        }
    }
    if (detail.timelineTruncated) {
        item("$keyPrefix.timeline.truncated") {
            ConversationMutedLine(
                "活动较多，仅显示最近 $MAX_EVENTS 条；完整记录仍保留在任务日志中。"
            )
        }
    }
    detail.timelineErrorMessage?.let { message ->
        item("$keyPrefix.timeline.error") {
            Column(modifier = Modifier.fillMaxWidth()) {
                AgentInlineBanner(message = message, isError = true)
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = actions.onRetryDetail,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("重试载入")
                }
            }
        }
    }
}

@Composable
private fun ConversationSectionLabel(title: String, trailing: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationMutedLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

@Composable
private fun JumpToLatestPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        McaButton(
            style = McaButtonStyle.Text,
            shape = ButtonDefaults.textShape,
            onClick = onClick,
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp)
        ) {
            Text("回到最新", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ------------------------------------------------------------------------------------ status

@Composable
private fun statusAccent(status: AgentWorkspaceStatus): Color = when (statusTone(status)) {
    AgentStatusTone.ACTIVE, AgentStatusTone.SUCCESS -> MaterialTheme.colorScheme.primary
    AgentStatusTone.ERROR -> MaterialTheme.colorScheme.error
    AgentStatusTone.WARNING, AgentStatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

