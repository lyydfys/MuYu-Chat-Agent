package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaIcons
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentQuestionUi
import com.muyuchat.feature.agent.workspace.AgentTaskDetailUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.AgentWorkspaceCommandKind
import com.muyuchat.feature.agent.workspace.AgentWorkspaceStatus
import com.muyuchat.feature.agent.workspace.AgentWorkspaceUiState
import com.muyuchat.core.designsystem.McaIconSize

/**
 * Inline assistant-side question block in the DSH conversation. Options are selectable and feed
 * the bottom composer's answer draft; the composer owns the free-text input and the send action.
 */
@Composable
internal fun AgentQuestionCard(
    question: AgentQuestionUi,
    answerDraft: String,
    busy: Boolean,
    onAnswerChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("agent.question"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "模型需要你的回答",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = question.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (question.options.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                question.options.forEach { option ->
                    QuestionOptionRow(
                        option = option,
                        selected = answerDraft == option,
                        enabled = !busy,
                        onClick = { onAnswerChange(option) }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
            Text(
                text = "在下方输入回答并发送。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun QuestionOptionRow(
    option: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = BorderStroke(
            1.dp,
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "选择这个回答", enabled = enabled, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = option,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = McaIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(McaIconSize.S)
                )
            }
        }
    }
}

private enum class ComposerMode {
    ANSWER,
    APPROVAL,
    STOP,
    START,
    CONTINUE,
    ARCHIVED,
    LOADING,
    DISABLED
}

// -------------------------------------------------------------------------------- DSH composer

@Composable
internal fun AgentDetailComposer(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    detail: AgentTaskDetailUiState
) {
    val question = detail.question
    val mode = when {
        detail.archived -> ComposerMode.ARCHIVED
        detail.status == AgentWorkspaceStatus.WAITING_USER && question != null -> ComposerMode.ANSWER
        detail.waitingForApproval -> ComposerMode.APPROVAL
        detail.loading -> ComposerMode.LOADING
        detail.canStop -> ComposerMode.STOP
        detail.canStart -> ComposerMode.START
        detail.canContinue -> ComposerMode.CONTINUE
        else -> ComposerMode.DISABLED
    }
    val answerBusy = state.commandInFlight == AgentWorkspaceCommandKind.ANSWER_QUESTION
    val continueBusy = state.commandInFlight == AgentWorkspaceCommandKind.CONTINUE_TASK
    val startBusy = state.commandInFlight == AgentWorkspaceCommandKind.START_RUN
    val enabled = mode != ComposerMode.ARCHIVED &&
        mode != ComposerMode.LOADING &&
        mode != ComposerMode.DISABLED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp, bottom = 2.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 2.dp,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            )
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 8.dp)
            ) {
                when (mode) {
                    ComposerMode.ANSWER -> DshTextField(
                        value = state.questionAnswerDraft,
                        onValueChange = actions.onQuestionAnswerChange,
                        placeholder = "输入回答…",
                        enabled = !answerBusy
                    )

                    ComposerMode.CONTINUE -> DshTextField(
                        value = state.detailGoalDraft,
                        onValueChange = actions.onDetailGoalChange,
                        placeholder = "发消息或创建任务，/ 调用指令，@ 文件或对话",
                        enabled = !continueBusy
                    )

                    ComposerMode.START -> ComposerPromptLine(
                        title = "发送以开始这个任务",
                        secondary = "Agent 会在本地执行目标并写入产物。"
                    )

                    ComposerMode.STOP -> ComposerPromptLine(
                        title = "Agent 正在执行",
                        secondary = detail.statusDetail ?: detail.statusLabel
                    )

                    ComposerMode.APPROVAL -> ComposerPromptLine(
                        title = "等待你的授权",
                        secondary = "处理授权后可继续执行。"
                    )

                    ComposerMode.ARCHIVED -> ComposerPromptLine(
                        title = "已归档 · 只读",
                        secondary = "导入的任务不会继续执行；仍可导出或删除。"
                    )

                    ComposerMode.LOADING -> ComposerPromptLine(
                        title = "正在载入任务…",
                        secondary = null
                    )

                    ComposerMode.DISABLED -> ComposerPromptLine(
                        title = detail.statusLabel,
                        secondary = detail.statusDetail
                    )
                }
                Spacer(Modifier.height(6.dp))
                ComposerToolbar(
                    state = state,
                    actions = actions,
                    mode = mode,
                    detail = detail,
                    enabled = enabled,
                    answerBusy = answerBusy,
                    continueBusy = continueBusy,
                    startBusy = startBusy
                )
            }
        }
        // Very bottom of the conversation, below the composer; matches the reference layout.
        detail.runStats?.let { stats ->
            AgentRunStatsBar(
                stats = stats,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun DshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean
) {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
        )
    }
}

@Composable
private fun ComposerPromptLine(title: String, secondary: String?) {
    Column(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (secondary != null) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ComposerToolbar(
    state: AgentWorkspaceUiState,
    actions: AgentWorkspaceActions,
    mode: ComposerMode,
    detail: AgentTaskDetailUiState,
    enabled: Boolean,
    answerBusy: Boolean,
    continueBusy: Boolean,
    startBusy: Boolean
) {
    var policyMenuOpen by remember { mutableStateOf(false) }
    val networkEnabled = state.models.networkEnabled
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ComposerCircleButton(
            icon = McaIcons.Add,
            contentDescription = "新建任务",
            onClick = actions.onBackToHome,
            enabled = enabled
        )
        Spacer(Modifier.width(2.dp))
        Box {
            Row(
                modifier = Modifier
                    .clickable(
                        enabled = enabled,
                        onClickLabel = "联网策略",
                        onClick = { policyMenuOpen = true }
                    )
                    .heightIn(min = 48.dp)
                    .semantics {
                        contentDescription = if (networkEnabled) {
                            "联网策略：允许联网，用于新任务"
                        } else {
                            "联网策略：仅本地运行"
                        }
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = McaIcons.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.S)
                )
                Icon(
                    imageVector = McaIcons.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(McaIconSize.Xs)
                )
            }
            DropdownMenu(
                expanded = policyMenuOpen,
                onDismissRequest = { policyMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text("仅本地运行") },
                    onClick = {
                        policyMenuOpen = false
                        if (networkEnabled) actions.onNetworkEnabledChange(false)
                    },
                    trailingIcon = if (!networkEnabled) {
                        { Icon(McaIcons.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
                DropdownMenuItem(
                    text = { Text("允许联网（用于新任务）") },
                    onClick = {
                        policyMenuOpen = false
                        if (!networkEnabled) actions.onNetworkEnabledChange(true)
                    },
                    trailingIcon = if (networkEnabled) {
                        { Icon(McaIcons.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                )
            }
        }
        McaButton(
            style = McaButtonStyle.Text,
            shape = ButtonDefaults.textShape,
            onClick = actions.onOpenModelPicker,
            enabled = enabled,
            contentColor = MaterialTheme.colorScheme.onSurface,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text(
                text = state.models.selected?.displayName ?: "选择模型",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = McaIcons.KeyboardArrowDown,
                contentDescription = null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else LocalContentColor.current,
                modifier = Modifier.size(McaIconSize.Xs)
            )
        }
        when (mode) {
            ComposerMode.STOP -> ComposerSendButton(
                icon = McaIcons.Stop,
                contentDescription = "停止任务",
                enabled = !detail.stopInFlight,
                onClick = actions.onStop
            )

            ComposerMode.APPROVAL -> ComposerSendButton(
                icon = McaIcons.Lock,
                contentDescription = "查看审批",
                enabled = true,
                onClick = actions.onOpenApproval
            )

            ComposerMode.ANSWER -> ComposerSendButton(
                icon = McaIcons.Send,
                contentDescription = "提交回答",
                enabled = detail.canAnswerQuestion && !answerBusy,
                onClick = actions.onAnswerQuestion
            )

            ComposerMode.CONTINUE -> ComposerSendButton(
                icon = McaIcons.Send,
                contentDescription = "继续任务",
                enabled = !continueBusy,
                onClick = actions.onContinue
            )

            ComposerMode.START -> ComposerSendButton(
                icon = McaIcons.Send,
                contentDescription = "开始任务",
                enabled = !startBusy,
                onClick = actions.onStartTask
            )

            else -> Unit
        }
    }
}

@Composable
private fun ComposerCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { this.contentDescription = contentDescription },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(McaIconSize.M))
    }
}

@Composable
private fun ComposerSendButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { this.contentDescription = contentDescription },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            },
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.60f)
        )
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(McaIconSize.M))
    }
}
