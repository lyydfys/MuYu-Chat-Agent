package com.muyuchat.feature.agent.workspace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchCheckUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchInputUi
import com.muyuchat.feature.agent.workspace.AgentWorkflowLaunchUiState
import com.muyuchat.feature.agent.workspace.AgentWorkspaceActions

/**
 * Manual launch preview for the selected task recipe.
 *
 * It renders only port values: the resolved goal, the declared inputs, the model label and the
 * declarative preflight rows. The single button calls one explicit create intent; there is no
 * schedule, trigger, next-run or "run automatically" control anywhere in this surface.
 */
@Composable
internal fun AgentWorkflowLaunchSection(
    launch: AgentWorkflowLaunchUiState,
    actions: AgentWorkspaceActions,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().testTag("workflows.launch")) {
        val notice = launch.notice
        if (notice != null) {
            AgentInlineBanner(
                message = notice,
                onDismiss = actions.settings.onDismissWorkflowStatus
            )
        }
        val error = launch.error
        if (error != null) {
            AgentInlineBanner(
                message = error.message,
                isError = true,
                onDismiss = actions.settings.onDismissWorkflowStatus
            )
        }

        val recipeId = launch.selectedRecipeId
        if (recipeId == null) {
            Text(
                text = "选择上方任务模板以预览并手动创建任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            return@Column
        }

        AgentSectionHeader(
            title = "手动创建任务",
            trailing = launch.preview?.let { "v${it.versionLabel.removePrefix("v")}" }
        )

        if (launch.loadingPreview) {
            AgentStaticLoading("正在校验任务模板…")
            return@Column
        }

        val preview = launch.preview
        if (preview == null) {
            if (error == null) {
                Text(
                    text = "任务模板预览不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            return@Column
        }

        Text(
            text = preview.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "模型 · ${preview.modelLabel} · 仅本地",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = preview.goal,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("workflows.launch.goal")
        )

        if (preview.inputs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AgentSectionHeader(title = "输入")
            preview.inputs.forEach { input ->
                WorkflowInputField(
                    input = input,
                    value = launch.inputValues[input.inputId].orEmpty(),
                    enabled = !launch.busy,
                    onValueChange = { value ->
                        actions.settings.onWorkflowInputChange(input.inputId, value)
                    }
                )
            }
        }

        if (preview.checks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            AgentSectionHeader(title = "创建前检查")
            preview.checks.forEachIndexed { index, check ->
                WorkflowCheckRow(index = index, check = check)
            }
        }

        val blocked = preview.blockedMessage
        if (!preview.canCreate && blocked != null) {
            Spacer(Modifier.height(8.dp))
            AgentInlineBanner(message = blocked, isError = true)
        }

        Spacer(Modifier.height(12.dp))
        AgentPrimaryButton(
            text = if (launch.busy) "正在创建…" else "创建任务",
            onClick = actions.settings.onWorkflowCreateTask,
            enabled = launch.canCreate,
            busy = launch.busy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("workflows.createTask")
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "仅手动：点击后创建一次任务并启动一次运行；不包含定时、后台计划或外部触发，" +
                "正常审批与预算不变。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkflowInputField(
    input: AgentWorkflowLaunchInputUi,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    if (input.editable) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = {
                Text(if (input.required) "${input.label}（必填）" else input.label)
            },
            singleLine = false,
            maxLines = 4,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .testTag("workflows.launch.input.${input.inputId}")
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = input.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = input.pathHint.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WorkflowCheckRow(index: Int, check: AgentWorkflowLaunchCheckUi) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(vertical = 2.dp)
            .testTag("workflows.launch.check.$index"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = check.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (check.passed) "通过" else "未通过",
            style = MaterialTheme.typography.labelSmall,
            color = if (check.passed) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            }
        )
    }
    val detail = check.detail
    if (!check.passed && detail != null) {
        Text(
            text = detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
