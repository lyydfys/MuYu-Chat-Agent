package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.core.designsystem.McaDialog
import com.muyuchat.core.designsystem.McaButton
import com.muyuchat.core.designsystem.McaButtonStyle
import com.muyuchat.feature.agent.workspace.AgentWorkflowDraftUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchUiState
import com.muyuchat.feature.agent.workspace.AgentWorkflowOperationKind
import com.muyuchat.feature.agent.workspace.AgentWorkflowRecipeUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowScheduleUiState
import com.muyuchat.feature.agent.workspace.AgentWorkflowsUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions

/**
 * Workflow recipe list with an explicit, separate schedule entry.
 *
 * Manual launch stays manual: one create dialog and one preview/create action over the existing
 * task/run owner. A plan is only authored inside [WorkflowScheduleDialog], one recipe at a time;
 * saving asks the host to persist it and install the platform wake. The page never computes a
 * next-run instant, never installs a wake and never starts a run from a schedule.
 */
@Composable
internal fun AgentWorkflowsScreen(
    state: AgentWorkflowsUiState,
    launch: AgentWorkflowLaunchUiState,
    schedule: AgentWorkflowScheduleUiState,
    actions: AgentWorkspaceActions,
    modifier: Modifier = Modifier
) {
    AgentSettingsScaffold(
        title = "工作流",
        listTestTag = "agent.workflows",
        backDescription = "返回任务列表",
        onBack = actions.settings.onCloseWorkflows,
        onRefresh = actions.settings.onRefreshWorkflows,
        refreshEnabled = state.canRefresh,
        modifier = modifier
    ) {
        val notice = state.notice
        if (notice != null) {
            item("workflows.notice") {
                AgentInlineBanner(
                    message = notice,
                    onDismiss = actions.settings.onDismissWorkflowStatus
                )
            }
        }
        val error = state.error
        if (error != null && state.createDraft == null) {
            item("workflows.error") {
                AgentInlineBanner(
                    message = error.message,
                    isError = true,
                    onDismiss = actions.settings.onDismissWorkflowStatus
                )
            }
        }
        if (state.busy == AgentWorkflowOperationKind.CREATING) {
            item("workflows.creating") { AgentStaticLoading("正在创建手动配方…") }
        }

        item("workflows.header") {
            AgentSectionHeader(
                title = "任务配方",
                trailing = "${state.recipes.size} 个 · 手动 / 定时"
            )
        }

        item("workflows.create") {
            McaButton(
                style = McaButtonStyle.Outlined,
                shape = ButtonDefaults.outlinedShape,
                onClick = actions.settings.onOpenWorkflowCreate,
                enabled = state.canOpenCreate,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("workflows.create")
            ) {
                Text("新建配方")
            }
        }

        item("workflows.run") {
            McaButton(
                style = McaButtonStyle.Outlined,
                shape = ButtonDefaults.outlinedShape,
                onClick = actions.settings.onOpenWorkflowRun,
                enabled = state.recipes.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("workflows.openRun")
            ) {
                Text("运行图（手动）")
            }
        }

        when {
            state.loading && state.recipes.isEmpty() -> item("workflows.loading") {
                AgentStaticLoading("正在载入工作流配方…")
            }

            state.empty -> item("workflows.empty") {
                AgentSettingsEmptyState(
                    title = "尚无任务配方",
                    body = "手动创建的任务配方会出现在这里；定时计划在配方行内单独设置。"
                )
            }

            else -> items(state.recipes, key = { it.recipeId }) { recipe ->
                WorkflowRecipeRow(
                    recipe = recipe,
                    selected = launch.selectedRecipeId == recipe.recipeId,
                    onClick = { actions.settings.onSelectWorkflow(recipe.recipeId) },
                    onOpenSchedule = {
                        actions.settings.onOpenWorkflowSchedule(recipe.recipeId, recipe.name)
                    }
                )
            }
        }

        item("workflows.launch") {
            AgentWorkflowLaunchSection(
                launch = launch,
                actions = actions,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
            )
        }

        item("workflows.note") {
            AgentSettingsNote(
                text = "配方只保存名称与任务目标；手动创建一次任务后由现有任务/运行 owner 执行，" +
                    "正常审批与预算不变。定时计划需在配方行显式打开后保存，由系统唤醒在到期 " +
                    "slot 触发一次；系统可能延迟，页面不承诺具体运行时刻。"
            )
        }
    }

    WorkflowScheduleDialog(state = schedule, actions = actions)

    state.createDraft?.let { draft ->
        WorkflowCreateDialog(
            draft = draft,
            busy = state.busy == AgentWorkflowOperationKind.CREATING,
            enabled = state.canSubmitCreate,
            onNameChange = actions.settings.onWorkflowNameChange,
            onGoalChange = actions.settings.onWorkflowGoalChange,
            onSubmit = actions.settings.onSubmitWorkflowCreate,
            onDismiss = actions.settings.onDismissWorkflowCreate
        )
    }
}

@Composable
private fun WorkflowRecipeRow(
    recipe: AgentWorkflowRecipeUi,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenSchedule: () -> Unit
) {
    Column(
        modifier = Modifier
            .testTag("workflows.item.${recipe.recipeId}")
            .clickable(onClickLabel = "选择 ${recipe.name}", onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recipe.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = recipeMeta(recipe),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (recipe.goalTemplatePreview.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = recipe.goalTemplatePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (selected) "已选择" else "预览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                McaButton(
                    style = McaButtonStyle.Text,
                    shape = ButtonDefaults.textShape,
                    onClick = onOpenSchedule,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .testTag("workflows.schedule.open.${recipe.recipeId}")
                ) {
                    Text("定时")
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun recipeMeta(recipe: AgentWorkflowRecipeUi): String = buildString {
    append(recipe.manualLabel).append(" · ").append(recipe.versionLabel)
    recipe.updatedLabel?.let { append(" · 更新 ").append(it) }
}

@Composable
private fun WorkflowCreateDialog(
    draft: AgentWorkflowDraftUi,
    busy: Boolean,
    enabled: Boolean,
    onNameChange: (String) -> Unit,
    onGoalChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    McaDialog(
        modifier = Modifier.testTag("workflows.dialog"),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("新建手动配方") },
        text = {
            Column {
                Text(
                    text = "只保存名称与任务目标；不会创建定时任务，也不会在后台运行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = onNameChange,
                    label = { Text("配方名称") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workflows.name")
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = draft.goalTemplate,
                    onValueChange = onGoalChange,
                    label = { Text("任务目标") },
                    minLines = 3,
                    maxLines = 6,
                    enabled = !busy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("workflows.goal")
                )
                val validation = draft.validationMessage
                if (validation != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = validation,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("workflows.dialog.error")
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
                    .testTag("workflows.cancel")
            ) {
                Text("取消")
            }
        },
        confirmButton = {
            McaButton(
                style = McaButtonStyle.Text,
                shape = ButtonDefaults.textShape,
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("workflows.submit")
            ) {
                Text(if (busy) "正在创建…" else "创建")
            }
        }
    )
}
