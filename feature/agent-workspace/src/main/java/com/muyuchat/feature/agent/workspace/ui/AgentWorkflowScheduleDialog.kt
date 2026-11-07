package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaTextField
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleDraftUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleKind
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleUiState
import com.muyuchat.feature.agent.workspace.AgentWorkflowWeekday
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions
import com.muyuchat.feature.agent.workspace.scheduleSummaryLabel
import com.muyuchat.feature.agent.workspace.weekdayLabel

/**
 * The one schedule dialog: create, edit, enable/disable and delete a plan for a pinned recipe
 * version. Copy never promises an exact run time; the platform may delay a wake and the host's
 * at-most-once claim stays authoritative.
 */
@Composable
internal fun WorkflowScheduleDialog(
    state: AgentWorkflowScheduleUiState,
    actions: AgentWorkspaceActions
) {
    if (!state.visible) return
    val settings = actions.settings
    McaDialog(
        modifier = Modifier.testTag("workflows.schedule.dialog"),
        onDismissRequest = { if (!state.busy) settings.onCloseWorkflowSchedule() },
        title = {
            Text(
                text = state.recipeName?.let { "定时计划 · $it" } ?: "定时计划",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val schedule = state.schedule
                if (schedule != null) {
                    Text(
                        text = scheduleSummaryLabel(schedule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("workflows.schedule.current")
                    )
                    schedule.lastDeliveredLabel?.let { last ->
                        Text(
                            text = "上次触发：$last",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "固定使用 v${schedule.definitionVersion}；编辑配方不会自动升级此计划。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(8.dp))
                }

                val draft = state.draft
                when {
                    state.loading -> Text(
                        text = "正在载入定时计划…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    draft == null -> Column {
                        Text(
                            text = state.error?.message ?: "定时计划暂时不可用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        McaButton(
                            style = McaButtonStyle.Outlined,
                            shape = ButtonDefaults.outlinedShape,
                            onClick = settings.onRetryWorkflowSchedule,
                            enabled = state.canRetry,
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .testTag("workflows.schedule.retry")
                        ) {
                            Text("重试")
                        }
                    }

                    else -> ScheduleDraftFields(
                        draft = draft,
                        enabled = !state.busy,
                        actions = actions
                    )
                }

                val notice = state.notice
                if (notice != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                val error = state.error
                if (error != null && draft != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        dismissButton = {
            Row {
                if (state.schedule != null) {
                    McaButton(
                        style = McaButtonStyle.Text,
                        shape = ButtonDefaults.textShape,
                        onClick = settings.onDeleteWorkflowSchedule,
                        enabled = state.canDelete,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("workflows.schedule.delete")
                    ) {
                        Text("删除计划")
                    }
                }
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = settings.onCloseWorkflowSchedule,
                    enabled = !state.busy,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("workflows.schedule.cancel")
                ) {
                    Text("关闭")
                }
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = settings.onSubmitWorkflowSchedule,
                enabled = state.canSave,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("workflows.schedule.submit")
            ) {
                Text(if (state.busy) "正在保存…" else "保存")
            }
        }
    )
}

@Composable
private fun ScheduleDraftFields(
    draft: AgentWorkflowScheduleDraftUi,
    enabled: Boolean,
    actions: AgentWorkspaceActions
) {
    val settings = actions.settings
    Column {
        Text(
            text = "触发方式",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            AgentWorkflowScheduleKind.entries.forEach { kind ->
                val selected = draft.kind == kind
                McaButton(
                    style = if (selected) McaButtonStyle.Tonal else McaButtonStyle.Outlined,
                    shape = ButtonDefaults.outlinedShape,
                    onClick = { settings.onWorkflowScheduleKindChange(kind) },
                    enabled = enabled && !selected,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp)
                        .heightIn(min = 44.dp)
                        .testTag("workflows.schedule.kind.${kind.name}")
                ) {
                    Text(kindLabel(kind), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when (draft.kind) {
            AgentWorkflowScheduleKind.INTERVAL -> McaTextField(
                value = draft.everyMinutesText,
                onValueChange = settings.onWorkflowScheduleMinutesChange,
                label = { Text("间隔（分钟）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workflows.schedule.minutes")
            )

            AgentWorkflowScheduleKind.DAILY, AgentWorkflowScheduleKind.WEEKLY -> McaTextField(
                value = draft.timeText,
                onValueChange = settings.onWorkflowScheduleTimeChange,
                label = { Text("时间（HH:mm）") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("workflows.schedule.time")
            )
        }
        if (draft.kind == AgentWorkflowScheduleKind.WEEKLY) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "星期",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                AgentWorkflowWeekday.entries.forEach { day ->
                    val selected = day in draft.weekdays
                    McaButton(
                        style = if (selected) McaButtonStyle.Tonal else McaButtonStyle.Outlined,
                        shape = ButtonDefaults.outlinedShape,
                        onClick = { settings.onWorkflowScheduleWeekdayToggle(day) },
                        enabled = enabled,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 2.dp)
                            .heightIn(min = 40.dp)
                            .testTag("workflows.schedule.day.${day.name}")
                    ) {
                        Text(weekdayLabel(day), maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        McaButton(
            style = McaButtonStyle.Outlined,
            shape = ButtonDefaults.outlinedShape,
            onClick = { settings.onWorkflowScheduleEnabledChange(!draft.enabled) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .testTag("workflows.schedule.enabled")
        ) {
            Text(if (draft.enabled) "状态：已启用（点按停用）" else "状态：已停用（点按启用）")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "时区 ${draft.zoneId} · 由系统唤醒执行，可能延迟；同一时段最多执行一次。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val validation = draft.validationMessage
        if (validation != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = validation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("workflows.schedule.error")
            )
        }
    }
}

private fun kindLabel(kind: AgentWorkflowScheduleKind): String = when (kind) {
    AgentWorkflowScheduleKind.INTERVAL -> "间隔"
    AgentWorkflowScheduleKind.DAILY -> "每天"
    AgentWorkflowScheduleKind.WEEKLY -> "每周"
}
